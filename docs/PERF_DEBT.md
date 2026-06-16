# Performance debt — executor hot path

## TL;DR — 2026-06-16: numbers superseded by eager-compile + DRY memo

The original measurement (2026-05) reported `exec/execute` of a
composed fn-def over a 2400-row dataset at ~63 s vs ~70 ms for the
direct-Clojure equivalent — a 900× slowdown on `resolve-versioned-
rows`-shape graphs. That number predates several executor wins:

- executor-eager-compile refactor (Clojure-native delays, lazy
  semantics): ~2000× faster worst-case;
- per-execute DRY memo (handlers don't fire siblings twice);
- shared golden DB via `CREATE DATABASE … TEMPLATE` (NS clones
  in ~100 ms);
- rich-types snapshot-at-bind isolation.

Fresh measurement (2026-06-16, full `core+web+app` package set,
2236 fn-defs):

| path | wall | size | notes |
|---|---|---|---|
| `/api/graph/entities` (base-fn impl) | ~95 ms | 4.0 MB | direct Clojure call, no executor — baseline |
| `/api/branches` (graph composition via `:resolve-fn-rows`) | ~20 ms | 498 B | small dataset |
| `resolve-versioned-rows-matches-clojure-end-to-end` (test) | ~16 s | n/a | bootstrap-included; 4× executes + 4× Clojure SOT compares; per-execute slice estimated ~0.5–1 s |

The graph-executor path is now within an order of magnitude of
direct-Clojure (vs the original 900×). The architectural design
sketch below is preserved for the next time we need to push further,
but the **priority drops sharply** — production endpoints comfortably
fit their request budgets today.

Re-benchmark with `clj-async-profiler` before allocating multi-commit
work — the flame-graph below also predates the eager-compile
refactor and may show a different hot-frame profile now.

## Observed

Setup: testcontainers PG, full package set (`core+web+app`,
~2400 `:fn` rows). Test fn-def:

```clojure
{:name :probe-call
 :parent :resolve-versioned-rows
 :args {:identities :_probe-ids :version-rows :_probe-versions
        :branch-chain :_probe-chain
        :version-id-field {:value :fn-id}
        :version-data-fields {:value [:name]}}}
```

| step                                  | time     |
| ------------------------------------- | -------- |
| `pth/create-container-fixture` (shared) | 3.6 s    |
| `ig/init [:exec/compiled-registry]`   | ~38 s    |
| `sync-fns-to-storage!` (8 defs)       | ~300 ms  |
| `cr/rebuild! ctx`                     | ~17 s    |
| `exec/execute :probe-call` #1         | **~63 s** |
| `exec/execute :probe-call` #2 (warm)  | **~62 s** |
| `res/resolve-all-entities` (Clojure SOT) | ~70 ms |

The Clojure source-of-truth and the graph fn-def use the same algo
(group-by version rows by entity-id, walk branch chain picking
latest per branch, materialise merged row). All 900× of the delta
sits in the executor.

## Flame-graph (clj-async-profiler, CPU, 63 s run)

Leaf-time top frames (collapsed):

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
 ...
```

The cost is **smeared evenly over `PersistentHashMap.assoc` and
`kvreduce`** — there is no single hot frame. Each graph-node
execution allocates a handful of small maps (free-args + aug +
ref-thunk env), and a single iteration of `:map :_rv-resolve-one`
hits 10+ nodes. With 2400 outer iterations × N inner iterations,
that compounds to millions of map allocations and gives the GC a
lot to clean up (`G1BarrierSetRuntime`, `oop_disjoint_arraycopy`,
`trim_queue_to_threshold` are all collector frames in the top 12).

### Attempted point-fixes (none gave measurable speedup)

- **Skip `reduce-kv` in `build-closure` when `aug` is empty** —
  tiny composed fns (`:get`, `:assoc`, `:vec`) end with empty
  `aug`. Replaced unconditional `reduce-kv fa-base aug` with
  `(if (seq aug) (reduce-kv …) fa-base)`. No measurable change
  — the surviving `:ref`-binding fns still have non-empty aug,
  and they dominate the call graph.
- **Memoise `snake->kw` over a `ConcurrentHashMap`** —
  decode-row does `(keyword (str/replace col "_" "-"))` per
  (row, column). A cache that returns interned keywords on hit
  should remove the `Keyword.intern` + `String.replace` cost.
  In practice the run got **slower** (~270 s), so the bottleneck
  must be elsewhere; reverted.

The smear is the diagnosis: the slow path is the **per-graph-node
overhead** itself (map allocation, atom CAS on the call-cache,
thunk closure allocation in `make-ref-entry`), not any single
helper.

## Real fix (not done — sized at multiple commits)

The executor pipeline needs to drop allocations on the hot path.
Candidate work, roughly in order:

1. **Transient maps in `build-closure`** — `final-fa` and the
   `aug` builder use persistent maps via `reduce-kv` / `assoc`.
   For fns with N bindings, that's N intermediate persistent
   versions per call. Switch to `transient` + final `persistent!`
   when bindings count > some threshold.
2. **Skip thunk allocation for eager refs** — `make-ref-entry`
   wraps every `:ref` binding in a `rt/thunk` so `resolve-arg`
   can decide at call time whether to deref. For impls that we
   know force ALL their args (the common case), the thunk wrap is
   pure overhead. Compile-time classification of "this impl forces
   arg X" lets us bind the resolved value directly.
3. **Call-cache: per-execution `HashMap` not an `Atom`** — every
   `:get` / `:assoc` / `:vec` invocation does
   `swap! *call-cache* assoc k v`. The cache is read/written from
   a single thread per execution; the `Atom`'s CAS is wasted.
   Replace with a per-execution `java.util.HashMap` threaded
   through dynamic var. Saves the CAS + 2-version persistent map
   per cache write.
4. **Pool the per-call `volatile!` in `build-closure`** — the
   `volatile! free-args` allocates one volatile per closure
   invocation. The volatile only exists so thunks/HOF wraps
   created inside `build-args-and-aug` see the post-merge map.
   Once #2 lands and we skip thunks for eager refs, the volatile
   can go too in those cases.

Expected total: bring per-graph-node overhead from ~10 µs
(current — at 63 s / 6M node executions) down to <500 ns. Target
~50× speedup on the resolve-versioned-rows fixture, which would
make `:resolve-versioned-rows` over the full graph fit in ~1 s.

## Workaround status — 2026-06-16: NOT needed today

Historically CI hit the 600 s `tests` ceiling under in-JVM
parallel runs because each NS bootstrap (`ig/init :exec/compiled-
registry`) was ~38 s, and 4 concurrent bootstraps in one heap
caused memory pressure / swap. The fix used to be `bb test-parallel
4` (4 separate JVM workers).

Several intervening changes brought in-JVM under ceiling:

- The executor-eager-compile refactor (lazy semantics via Clojure-
  native delays) — ~2000× faster worst-case.
- Shared golden DB via `CREATE DATABASE … TEMPLATE`: one
  bootstrap per JVM × package-set, NS clones in ~100 ms.
- Per-execute DRY memo: handlers don't fire siblings twice.
- Rich-types race fix + the snapshot-at-bind isolation pattern.

CI is now back on in-JVM `bb test` (`scripts/ci.clj :: test-cmd`),
with measured walls of ~6:30–7:00 for all 1400+ tests / 0
failures and the `tests` ceiling at 900 s. The TL;DR's 900×
figure above predates the eager-compile refactor — a fresh
measurement could revise it downward significantly. Holding the
real-fix design as still-valid; benchmarking it on the new
executor pipeline is the next step before allocating the
multi-commit fix.
