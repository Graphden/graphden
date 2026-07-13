# ADR: `free-arg-slot-map` is the real `/api/execute` latency, not the executor

**Status:** VERIFIED live on the production-scale graph (4085 fns) — see
§ Verification results. On `review/followups`.
**Date:** 2026-07-11

## Context

The executor is benchmarked at **~1 µs/node** (`docs/PERF_NOTES.md`). That
number is real but measures `compile-runtime/execute` by **fn-id** — a
pure in-memory closure invocation. It does not measure the path a user
actually hits when they click **Run**.

Measured live on the running production container (nREPL :9099,
4085 compiled fns), warm, steady-state:

| Call | Latency | On the `/api/execute` path? |
|---|---|---|
| `execute` by fn-id (pure executor) | **~0.0–0.2 ms** | inner loop |
| `resolve-fn` (by id or name) | ~0 ms | yes |
| `resolve-fn-version-id` | ~1.5 ms | yes |
| **`free-arg-slot-map`** | **1300–1900 ms** | **yes — every request** |

`crud.fn-execution/apply-execute` (the `/api/execute` handler) calls
`lookup/free-arg-slot-map ctx fn-id` on every execution. Confirmed across
several fns of varying size — even a leaf predicate (`_resource-nil?`)
took ~1.3 s; a trivial `:not-found-response` (a 3-key map) took ~1.85 s.
The cost is essentially constant in fn size because it is dominated by
the per-query overhead of the `VersionedStorage` stack, not by BFS
breadth.

### Root cause

`free-arg-slot-map` → `collect-reachable-graph` walks the target fn's
reachable subgraph (`parent-ids` + `ref-fn-id` edges) issuing a
`query-entities` per BFS level per entity type, each resolved through
the versioned-storage decorator (branch-chain resolution + version
filtering). The pure `free-args-via` step that consumes the result is
in-memory and cheap. There is **no caching** — the full BFS re-runs on
every request.

The same graph is already held in memory: `compile-runtime/rebuild!`
populates `:graph-cache`, and `registry/deep-free-ext-entries` already
emits `{:ext-name :slot-id}` entries from it (used by
`translate-named-args` / `free-arg-ext-names`). So the data is a
memory read away; the request path just doesn't use it.

## Decision

Add a memoized variant **`free-arg-slot-map-cached`** used ONLY by the
`/api/execute` hot path (`apply-execute`); leave `free-arg-slot-map`
itself pure. Drop the memo at the existing invalidation choke point
(`invalidate-graph-cache!`).

- The result is a **pure function of the graph state** for a given
  `[storage branch fn]`. A fn's free-arg surface changes only when
  something in its own-org∪public reachable closure changes — exactly
  the signal that already fires `invalidate-graph-cache!`.
- **Key = `[base-storage-identity branch-id fn-id]`.**
  - *Org is not in the key:* fn-ids are globally-unique UUIDs and a
    fn's closure is confined to own-org∪public (enforced by
    `reject-cross-org-refs!`), so the free-arg map is invariant across
    requesters who can see the fn.
  - *Branch is in the key* because versioned bindings differ per branch.
  - *Base-storage identity* (via `vs/unwrap`) is in the key so two
    distinct graphs — e.g. per-test storages sharing the process-wide
    cache — can never collide even without an intervening clear. In
    prod the base `PostgresStorage` is a single long-lived object, so
    this is stable and the cache hits.
- Full drop (not delta) on invalidation: the ~1.5 s recompute lands at
  most once per fn after each edit, never per request.

**Why a separate cached variant instead of caching `free-arg-slot-map`
directly** (this was caught live — see § Verification, closure-capture):
the memo is only correct where *every* mutation before the call routes
through `invalidate-graph-cache!`. That holds for `/api/execute` (runs
after CRUD writes, never during one) but NOT for direct callers that
mutate storage and re-query without invalidating — notably the
`closure-capture` tests and the `:free-arg-slot-map` admin base-fn.
Keeping `free-arg-slot-map` pure preserves their exact, always-fresh
behaviour; only the proven-safe hot path opts into the cache.

Implemented as a dependency-free leaf ns
(`crud.fn-execution.free-arg-cache`) so `context → free-arg-cache` can't
create a namespace cycle back through the versioned-storage stack that
`lookup` pulls in.

### Alternatives considered

1. **Route through `deep-free-ext-entries` (in-memory walker).** The
   *correct-in-principle* fix — delete the versioned BFS entirely and
   read from `:graph-cache`. Rejected FOR NOW: there are two parallel
   free-arg implementations (`free-args-via` here vs
   `deep-free-ext-*` in `executor.registry`) with subtly different
   closure-capture / HOF-call-site-subtraction / `value-present`
   handling. Unifying them is the right end-state (see
   `REVIEW_FOLLOWUPS.md` #1b) but changes semantics and needs its own
   test pass. Memoization preserves the exact current output, so it's
   the low-risk first step.
2. **Delta-clear** using the `:compile-deps` reverse-index. Correct and
   strictly better, but adds complexity for a cost that only lands once
   per fn per edit. Deferred until measured to matter.

## Consequences

- `/api/execute` warm latency drops from ~1.5 s to the executor's real
  cost (single-digit ms) for any fn executed since the last edit.
- First execute of a fn after an edit still pays the ~1.5 s BFS once.
  If that first-hit cost matters, alternative 1 removes it entirely.
- One new leaf ns + two edits (`lookup`, `context`). No semantic change
  to the free-arg computation itself.

## Verification results (2026-07-11, live on the 4085-fn graph)

**Speed + correctness** — `free-arg-slot-map-cached` on the production
container, per fn, cold (miss) vs warm (hit), output compared to a
pre-fix baseline captured on the old code:

| fn | before | cold (miss) | warm (hit) | output correct? |
|----|--------|-------------|------------|-----------------|
| `not-found-response` | ~1791 ms | 2048 ms | **0.054 ms** | ✓ (`{}`) |
| `request-authenticated?` | ~1168 ms | 1264 ms | **0.044 ms** | ✓ |
| `_resource-nil?` | ~1184 ms | 1337 ms | **0.038 ms** | ✓ |

Warm hit is **~30,000× faster** (~1.5 s → ~0.04 ms) with byte-identical
output.

**Invalidation** — populated the memo (size 3), then:

- `invalidate-graph-cache! ctx` (full arity) → size 0 ✓
- `invalidate-graph-cache! ctx #{changed}` (delta arity) → size 0 ✓
- post-invalidate recompute measured 1262 ms (cold again) → the memo was
  genuinely dropped, not serving stale ✓

**Regression** — `72 tests, 265 assertions, 0 failures` across
`closure-capture-test`, `fn-execution-test`, `free-arg-cache-test`,
`base-fn-isolation-test`.

### What the window caught

The first implementation cached inside `free-arg-slot-map` itself. It
passed the live speed/correctness probe but **failed two
`closure-capture` tests**: those tests mutate the graph and re-query
`free-arg-slot-map` directly without an intervening
`invalidate-graph-cache!`, so the process-wide memo served a stale
result. Causation was confirmed by bypassing the memo (tests went
green). Fix: move the cache to the `-cached` variant on the proven-safe
hot path and add base-storage identity to the key. This is exactly the
kind of multi-tenant/lifecycle correctness issue that only a live run
surfaces — the reason the fix was written but not trusted until verified.
