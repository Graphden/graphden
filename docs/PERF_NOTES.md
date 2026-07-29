## Performance investigation notes — executor hot path

> **🔧 Internal engineering record — not user documentation.** A
> contributor-facing investigation note, indexed from [CLAUDE.md](../CLAUDE.md)
> and outside the reader path (see [docs/README.md](README.md)).

Not a roadmap. Production endpoints comfortably fit their
request budgets today. This file holds the diagnosis +
attempted-fix lessons from the 2026-05 perf investigation,
preserved for the next time we need to push further on the
executor hot path.

## Current state (2026-06-16)

| path | wall | size | notes |
|---|---|---|---|
| `/api/graph/entities` (base-fn impl) | ~95 ms | 4.5 MB | direct Clojure call, no executor — baseline. Full scope (CLI / e2e helper). |
| `/api/graph/entities?scope=tree` | ~15 ms | O(namespaces), ~few KB | namespaces + per-namespace named-fn counts, NO fn rows. The editor's `initGraph` / post-mutation refresh use this — the O(all-fns) `scope=index` pull is gone from the hot path. |
| `/api/graph/entities?scope=namespace&namespace-id=X` | ~5-15 ms | O(ns fns) | one namespace's light fn rows (id/name/role/counts). Fetched lazily when the sidebar expands that namespace. |
| `/api/graph/entities?scope=search&q=…` | ~5-20 ms | O(matches), capped 200 | name-substring matches (light rows). Backs the sidebar filter box, the fn/namespace/reparent pickers, and name→id resolution. |
| `/api/graph/entities?scope=index` | ~30 ms | 1.6 MB | fns + namespaces only. Still O(all-fns); retained for CLI / backward-compat — the editor no longer uses it (superseded by `scope=tree`). |
| `/api/graph/entities?scope=subtree&root-id=X` | ~5-40 ms | 1.5 KB - 4.2 MB | BFS closure from `root-id`. Leaf fn = 1.5 KB; app-root = 4.2 MB. Editor uses this per `selectFn` (commits `bec65163` + `55bee689`). |
| `/api/branches` (graph composition via `:resolve-fn-rows`) | ~20 ms | 498 B | small dataset |
| `resolve-versioned-rows-matches-clojure-end-to-end` (test) | ~16 s | n/a | bootstrap-included; 4× executes + 4× Clojure SOT compares; per-execute slice estimated ~0.5–1 s |

The graph-executor path is now within an order of magnitude
of direct Clojure (vs the original 900× pre-eager-compile).
Wins that brought it here:

- executor-eager-compile refactor (Clojure-native delays, lazy
  semantics): ~2000× faster worst-case
- per-execute DRY memo (handlers don't fire siblings twice)
- shared golden DB via `CREATE DATABASE … TEMPLATE`
- rich-types snapshot-at-bind isolation

**The 4-step real fix sketch (below) is preserved but the
priority dropped sharply.** Before allocating multi-commit
work, re-benchmark with `clj-async-profiler` on the current
executor pipeline — the 2026-05 flame-graph predates the
eager-compile refactor and may show a different hot-frame
profile now.

### 2026-07-09 re-profile — executor is already well-optimized

Re-benched the current pipeline with the harness now committed at
`development/bench/graphden/exec_profile.clj` (bootstrap `[core]`,
map a 2-node composed callback over `(range 2000)` ≈ 4–6k
node-executes, criterium). Result: **~3.8 ms → ~1 µs/node** — the
executor is now near the sketch's `<500 ns/node` target; the
eager-compile refactor already captured the big win.

Sketch status: **#3 (HashMap call-cache, not Atom) is DONE** —
`::call-cache` is a `java.util.HashMap` today. **#1 (transients)
is PARTLY DONE** — the arg-map builder loop already uses
`transient`/`persistent!`. Landed a variant of **#4**: skip the
per-call `fa-ref` volatile when a fn has no env-bindings (commit
`9d79b29d`). That point-fix measured **~5% — inside the run's
std-dev, i.e. noise**, re-confirming the 2026-05 lesson: single-site
fixes don't move a smeared profile. Kept only because it's
provably-equivalent + cleaner, not for the number.

**Do NOT expect executor micro-opts to speed up `bb test`** — the
slow tests (`entities-graph`, `branches-lifecycle`,
`compile-packages`) are CRUD / compile / HTTP-bound, not
execute-bound. The banked test-suite wins (2026-07-09, ~36 min →
~23 min) came from delta-invalidation + minimal package sets, not
execute-path tuning.

## Diagnosis (2026-05) — "no single hot frame, GC pressure smear"

Leaf-time top frames (collapsed) from a 63-second
`exec/execute :probe-call` run over 2400-row dataset:

```
5500  PersistentHashMap$BitmapIndexedNode.assoc
2942  PersistentHashMap$NodeSeq.kvreduce
2111  StringLatin1.replace
1963  PersistentArrayMap.indexOf
1908  Var.getRawRoot                          # dynamic var deref (call-cache)
1886  RT.nthFrom
1403  G1BarrierSetRuntime::write_ref_array_post_entry
1387  ConcurrentHashMap.get
1180  oop_disjoint_arraycopy                   # GC scavenging
1152  Util.hasheq
 991  G1ParScanThreadState::trim_queue_to_threshold
 806  Keyword.intern
```

The cost is smeared evenly over `PersistentHashMap.assoc` and
`kvreduce` — there is no single hot frame. Each graph-node
execution allocates a handful of small maps (free-args, aug,
ref-thunk env), and a single iteration of `:map :_rv-resolve-one`
hits 10+ nodes. With 2400 outer iterations
× N inner iterations, that compounds to millions of map
allocations and gives the GC a lot to clean up
(`G1BarrierSetRuntime`, `oop_disjoint_arraycopy`,
`trim_queue_to_threshold` are all collector frames in the
top 12).

## Counterintuitive lesson — point-fixes don't help when the cost is smeared

Two point-fixes were attempted; **neither gave measurable
speedup**, and one made things WORSE:

- **Skip `reduce-kv` in `build-closure` when `aug` is empty**
  — tiny composed fns (`:get`, `:assoc`, `:vec`) end with
  empty `aug`. Replaced unconditional `reduce-kv fa-base aug`
  with `(if (seq aug) (reduce-kv …) fa-base)`. No measurable
  change — the surviving `:ref`-binding fns still have
  non-empty aug, and they dominate the call graph.
- **Memoise `snake->kw` over a `ConcurrentHashMap`** —
  decode-row does `(keyword (str/replace col "_" "-"))` per
  (row, column). A cache that returns interned keywords on
  hit should remove the `Keyword.intern` + `String.replace`
  cost. In practice the run got **slower** (~270 s), so the
  bottleneck must be elsewhere; reverted.

**Lesson**: when the diagnosis is "smear, not a hot frame",
shrinking ONE allocation site is wasted effort — the
allocator + GC are paying the cost across the whole call
graph. The real fix has to change the allocation PROFILE of
the whole executor, not optimise individual helpers.

## Real fix sketch — sized at multiple commits (not done)

The executor pipeline needs to drop allocations on the hot
path. Candidate work, roughly in order:

1. **[PARTLY DONE 2026-07-09]** Transient maps in `build-closure`
   — the arg-map builder loop in `compile-fn`'s closure already uses
   `transient`/`assoc!`/`persistent!`. The env-binding merge loop
   still uses persistent `assoc`, but it only runs when `env-n > 0`
   (uncommon).
2. **Skip thunk allocation for eager refs** —
   `make-ref-entry` wraps every `:ref` binding in a
   `rt/thunk` so `resolve-arg` can decide at call time
   whether to deref. For impls that we know force ALL their
   args (the common case), the thunk wrap is pure overhead.
   Compile-time classification of "this impl forces arg X"
   lets us bind the resolved value directly.
3. **[DONE]** Call-cache: per-execution `HashMap` not an `Atom`
   — `::call-cache` in `ctx` is a `java.util.HashMap` installed once
   per top-level execute; nested siblings inherit it through `ctx`.
   No per-write CAS or persistent-map churn.
4. **[PARTLY DONE 2026-07-09, commit `9d79b29d`]** Pool/skip the
   per-call `volatile!` — now skipped entirely for fns with no
   env-bindings (the common case; the volatile is only read by
   env-binding delays). Measured noise-level, kept for cleanliness.
   Fully pooling it for the `env-n > 0` case is still open but
   below the noise floor.

Expected total: bring per-graph-node overhead from ~10 µs
down to <500 ns. Target ~50× speedup on the resolve-versioned-
rows fixture, which would make `:resolve-versioned-rows` over
the full graph fit in ~1 s.

**Allocate this work only after** a fresh `clj-async-profiler`
run confirms the smear is still in the same place after the
eager-compile refactor.

## CI test runtime — currently within budget

Historically CI hit the 600 s `tests` ceiling under in-JVM
parallel runs (4 concurrent NS bootstraps × ~38 s caused
memory pressure). The eager-compile refactor + shared golden
DB + DRY memo + rich-types snapshot fix brought walls to
~6:30–7:00 for all 1400+ tests / 0 failures. Ceiling now at
900 s. The `bb test-parallel 4` worker-isolation workaround
is no longer needed.

### Finding H — test-JVM live-set growth is NOT a leak (2026-07-15)

A prior handoff flagged "~177 MB of live-set growth over a
suite run (95 → 272 MB after a full GC), unexplained." It is
**not a leak** — it is the fixed working set of a fully-loaded
test JVM. A run boots the whole graph (252 base-fns + 2728
fn-defs compiled), builds the process-global registries
(base-fns, rich-types, compile-all closures) and the golden
bootstrap; that footprint loads early and **plateaus** — it
does not grow proportionally to the test count. Measured
end-of-`:unit`-suite live-set: **257 MB**, stable across
identical runs. Mirrors the demo-instance note in the JVM
flags ("live set 125 MB after a full GC … not a leak").

The one real per-NS leak — test namespaces that create a
storage and never `sp/close` it, so its HikariCP pool outlives
the NS — was measured and is **within GC noise** at the test
pool-size of 2: closing every tracked storage moved the
end-state 260.7 → 257.0 MB (~3.7 MB). An earlier ad-hoc probe
overstated it 5× by using the default pool-size (10). A
suite-end backstop now closes every tracked storage
(`shared-container/register-storage!` + `close-all-storages!`
in the plugin `post-run`) — cheap insurance against the
footprint growing if the pool size is raised or many leaking
NSes are added, not a fix for H.

**Do not re-investigate H as a leak.** Re-measure before/after
with the real pool-size (2), not an ad-hoc probe, if the
question resurfaces.

## Phase 5 HOF translation — below noise floor

`build-hof-translation` + `apply-hof-translation` shipped on
`refactor/slot-id-keyed-runtime` add per-HOF-wrap-time work:

- `build-hof-translation` runs at COMPILE time (once per HOF
  binding's arg-builder construction). Inside is a single
  `(deep-free-ext-entries r-fn-id lookups)` call (memoised via
  `:deep-free-ext-entries-cache` in lookups) plus a reduce
  filter — O(R-walker-entries), typically 1-20 entries.
- `apply-hof-translation` runs at RUNTIME (per HOF callable
  invocation). The empty-translation case short-circuits and
  returns fa unchanged — the COMMON case for fns that don't
  surface their ext-names through HOF boundaries. The non-
  empty case is `reduce-kv` over translation entries (typically
  1-5) with two `contains?` checks + one `rt/thunk?` check per
  entry.

Worst-case per-HOF-wrap runtime overhead: ~5 entries × ~10 ns
per entry = ~50 ns. Per-request HOF wraps are O(log handler-
chain-depth), typically 3-5. Total per-request: ~250 ns.

Versus the actual per-request work — HTTP parsing, DB query,
graph-compose response, JSON encode — typically 10-50 ms per
endpoint per current measurements. The translation overhead
is 5-6 orders of magnitude below the per-request budget.

Smoke + e2e regression checked after ship: no measurable
slowdown on any of the 11 smoke checks or the 51 e2e tests.
No explicit benchmark added since the overhead is below
`bb rebuild` round-trip noise (rebuild itself is ~3 minutes).
