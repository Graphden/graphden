# Performance debt — executor hot path

## TL;DR

`exec/execute` of a composed fn-def over a 2400-row dataset takes
~63 s, vs ~70 ms for the equivalent direct-Clojure call —
**~900× slowdown** on `resolve-versioned-rows`-shape graphs.

This isn't a regression from any one change; it's the cost of the
current executor pipeline showing up as soon as a single graph
execution iterates over thousands of items inside nested HOFs.
Production endpoints haven't hit it yet because each request
typically processes <10 entities — but the headroom is much smaller
than expected.

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

## Workaround in the meantime

`bb ci` runs tests inside a single JVM with kaocha's in-JVM
parallelism (=4 for integration). Under that path each parallel
NS does its own `ig/init` concurrently → 4 systems × 38 s in the
same JVM → severe memory pressure and swap → `tests` step times
out at the 600 s ceiling.

`bb test-parallel 4` spawns 4 separate JVM workers (one
testcontainer each through `shared-container`'s per-NS logical
DB). Wall time settles around **480 s**, well under the 600 s
ceiling. CI was switched to this path (see `scripts/ci.clj`'s
`tests` entry).

When the executor work above lands, `bb ci` should switch back
to in-JVM `bb test`.
