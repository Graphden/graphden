# ADR: once-semantics for ref thunks, and the identity-keyed call-cache

> Status: **ACCEPTED** (once-thunks shipped 2026-08-24, commit
> `3c38e090`). The residual-window section is a documented
> NON-decision — revisit only with evidence.

## Context

The compiled executor defers every `:ref` argument behind a 0-arity
thunk (`rt/thunk`) and memoises child invocations in a per-execute
call-cache keyed by `[ref-id (select-keys fa projection-frees)]`
(`compile-eager/call-with-cache`, `fa-key-for-cache`). Two facts
interact:

1. Values inside `fa` are largely **thunks and callables**, so the
   projected key compares them by **object identity**.
2. A thunk may wrap an **effectful** subtree — `:_call-base-handler`
   is the entire app handler chain.

Before this ADR, `rt/thunk` ran its body on **every** force. The only
thing standing between a second force and a second run of an
effectful subtree was a cache hit — i.e. *identity equality of thunk
instances across call sites*. That is not an invariant anyone
maintains: env-bindings (`env-arg-builder`) mint a fresh thunk per
parent run, and an unrelated graph edit can change how many parent
runs happen.

**Observed failure (2026-08-24):** an inline-anon `:cond` added in
`app/branches` shifted thunk identities in the response-cache/encode
wrap chain of `web/http`; a projected key diverged, `:response` was
re-forced, and the candidates POST handler **executed twice inside
one HTTP request** — the second run against an already-drained body
stream returned an internal 400 whose passthrough headers overwrote
the encoded ones (gzip body, no `Content-Encoding`). Full ledger in
the session memory `inline-anon-cond-breaks-encode-2026-08-24`.

## Decision

`rt/thunk` is **once** (delay-backed): the wrapped fn runs at most
once per thunk instance; later forces return the first result. The
0-arity fn shape is kept so impls reading args raw (`((:x args))`)
still work; the delay inside provides thread-safe run-at-most-once.

Why this is the model and not a patch:

- CLAUDE.md § Lazy Execution promises **delay-based** evaluation; a
  slot denotes ONE value per evaluation.
- CLOSURE_CAPTURE.md's contract says captured args resolve **once,
  invariant per wrap** — previously aspirational, now enforced.
- It makes the identity-keyed cache **sound by construction**: with
  once, a given thunk instance always denotes one value, so key
  equality implies input equality. A spurious MISS now recomputes
  with the *same* inputs (forcing shared thunks returns their first
  value) instead of re-running effects.

Explicitly NOT changed:

- HOF callables (`[:fn …]` slots — `:map`'s `:func`, `:future` /
  `:loop-until-interrupted` bodies) are not thunks; invoke-per-call
  is their semantics.
- Laziness / short-circuit: an unforced branch still never runs.
- `apply-hof-translation`'s thunk-skip stays: a re-entrant force of a
  delay from the same thread recurses (mid-computation), so copying
  env thunks under slot-id keys is still forbidden.

## Audit of the wider pattern (2026-08-24)

Every deferred-construction site in the executor was reviewed after
the fix:

| Site | Verdict |
|---|---|
| `arg-builder` `:ref` (plain + renames), `:resolved-value`, all `env-arg-builder` `:ref` arms | `rt/thunk` → once ✓ |
| `:seq` non-lazy | Clojure lazy-seq — realized cells memoised ✓ |
| `:seq` `lazy-seq?` | `delay` per item ✓ |
| `resolve-arg` IDeref arm | delay ✓ |
| `hof-wrap` / `make-shape-callable` / HOF env arm | callables, invoke-per-call by design ✓ |
| `run-with-timeout` | plain fn param, submitted once ✓ |
| public boundary (`execute`, `make-single-arg-callable`) | plain caller values in `fa` ✓ |

A graph-wide scan then looked for the **residual window**: an
effectful fn (root base-fn declares non-`:time`/`:random` effects)
with **>1 incoming executable ref** whose `cache-projection-frees`
intersect **env-binding names** — the combination where per-parent-run
env thunks could diverge keys and re-run the effect. 29 candidates
matched (scan sources in the session ledger). Review outcome:

- Reads (branch/package/fn-row lookups, `_resource-or-nil`, stats):
  re-execution is state-idempotent; cost is perf + snapshot skew,
  both already accepted semantics (raw reads are create-time).
- Writes (`_create-fn-slot-*`, `_merge-record-committed`,
  `_pub-apply`, `_apply-*-rollback`, `_conflicts-apply-raw`,
  `_delete-secret-fn-row`, `_upd-rewrite`): every one keys on
  request-scoped env names (`:parsed`, `:journal`, `:pkg-*`,
  `:source`/`:target`) whose binder runs once per request — one thunk
  instance, all call sites share one cache slot. Empirically no
  double-write has ever surfaced (a doubled `_create-fn-slot-fn-row`
  would trip `UNIQUE (fn-id, slot-id)`).

**Conclusion: no live instance of the pattern remains.** What remains
is a *constructive* gap: nothing guarantees an effectful node is
reached under a single env-thunk generation. Closing it for good
means moving the call-cache off identity keys (semantic value keys,
or a per-execute env scope) — a redesign of the cache-key model, not
a local fix.

## Consequences / revisit triggers

- If an effect is ever observed firing twice in one execute (a
  duplicate write, a doubled counter), suspect a **multi-run parent**
  minting fresh env thunks; the scan in the ledger localises
  candidates. That evidence — not speculation — is the trigger to
  design semantic cache keys.
- New effectful base-fns composed under env-bindings with multiple
  call sites extend the candidate list; prefer a single named
  intermediate (one call site) when composing them.
- The once-regression is pinned by `runtime-test`'s thunk suite and
  the integration canary
  `compile-packages-test/response-cache-wrap-single-handler-invocation-test`
  (one request ⇒ exactly one handler run, encode applied to body AND
  headers together).

## Consumer note 2026-09-02 — a long-lived render must CALL, not capture

Once-thunks have one consumer-visible consequence that took nine days to
surface: a callable that a base-fn keeps and invokes repeatedly (the SSE
stream's `:render`, re-run on every tick) sees its CAPTURED args resolve
exactly once — so a fragment passed as a hiccup VALUE renders its first
tree forever, every tick hashes equal, and the stream pushes nothing after
its first frame. The Tests panel and the demo clock were dead like that
from 2026-08-24 until 2026-09-02; the tutorial's lesson-14 e2e still passed
because the auto-run usually beat the panel's first fetch.

The fix is graph-side and keeps the ADR's model: `:sse-fragment-handler`'s
`:fragment` is a `[:fn {} :hiccup-node]` slot and `:_sse-fragment-rendered`
CALLS it (`:call-noargs`) on every render — a callable's body runs per
call (its own bindings are minted per run); only what the WRAPPER captured
is once. Rule for any base-fn that re-invokes a callable over time: the
thing that must be fresh has to be a fn it calls, never a value it was
handed. `packages.app.sse-fragment-live-test` pins it against the real
graph handler over a live socket.
