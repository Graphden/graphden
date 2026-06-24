## Performance investigation notes — executor hot path

Not a roadmap. Production endpoints comfortably fit their
request budgets today. This file holds the diagnosis +
attempted-fix lessons from the 2026-05 perf investigation,
preserved for the next time we need to push further on the
executor hot path.

## Current state (2026-06-16)

| path | wall | size | notes |
|---|---|---|---|
| `/api/graph/entities` (base-fn impl) | ~95 ms | 4.5 MB | direct Clojure call, no executor — baseline. Full scope. |
| `/api/graph/entities?scope=index` | ~30 ms | 1.6 MB | fns + namespaces only (sidebar payload). 65% smaller. Editor uses this on initGraph (commit `c7a14348`). |
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

1. **Transient maps in `build-closure`** — `final-fa` and the
   `aug` builder use persistent maps via `reduce-kv` /
   `assoc`. For fns with N bindings, that's N intermediate
   persistent versions per call. Switch to `transient` +
   final `persistent!` when bindings count > some threshold.
2. **Skip thunk allocation for eager refs** —
   `make-ref-entry` wraps every `:ref` binding in a
   `rt/thunk` so `resolve-arg` can decide at call time
   whether to deref. For impls that we know force ALL their
   args (the common case), the thunk wrap is pure overhead.
   Compile-time classification of "this impl forces arg X"
   lets us bind the resolved value directly.
3. **Call-cache: per-execution `HashMap` not an `Atom`** —
   every `:get` / `:assoc` / `:vec` invocation does
   `swap! *call-cache* assoc k v`. The cache is read/written
   from a single thread per execution; the `Atom`'s CAS is
   wasted. Replace with a per-execution `java.util.HashMap`
   threaded through dynamic var. Saves the CAS + 2-version
   persistent map per cache write.
4. **Pool the per-call `volatile!` in `build-closure`** —
   the `volatile! free-args` allocates one volatile per
   closure invocation. The volatile only exists so
   thunks/HOF wraps created inside `build-args-and-aug` see
   the post-merge map. Once #2 lands and we skip thunks for
   eager refs, the volatile can go too in those cases.

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
