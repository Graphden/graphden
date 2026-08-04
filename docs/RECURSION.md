# Recursion in graphden — Design space and the shipped approach

> **Status**: Approach A (`:fix`) is **shipped**. The `:fix`
> Y-combinator base-fn lives in `resources/packages/core/recursion/`,
> is depth-guarded (`*max-recursion-depth*`, default 1000), tested
> (`recursion_test` runs a real factorial + exercises the depth bound),
> and used in production — `storage/branches` `:branch-chain` walks a
> branch's parent chain as a `:fix` graph composition. Approach B
> (relax the cycle check) is the road not taken; it's kept below as the
> design alternative. Graph-level cycles (bare self- / mutual-ref)
> remain structurally forbidden by the two cycle-check layers — `:fix`
> expresses recursion through a runtime `:self` callable, NOT a graph
> cycle, so Code = Graph holds without weakening the DAG invariant.

## Why this needed solving

Recursion isn't optional for an expressive functional language —
tree-walk (JSON / hiccup / AST visitor) is the bread-and-butter
practical case. Before `:fix`, admins/users could not express
recursion in the graph; they had to escape to Clojure inside a base-fn
impl, which hid the recursive structure from every other consumer
(editor, type-checker, layout, layout substitution, delta-recompile).
`:fix` closes that gap.

## Two viable approaches

We've narrowed the design space to two principled options:

| | **A. `:fix` (Y-combinator + closure-capture)** | **B. Lazy ref resolution (relax cycle check)** |
|---|---|---|
| New entities | 1 base-fn (`:fix`) | 0 |
| Cycle invariant | Preserved — graph-level cycles remain forbidden | Relaxed for fns that opt in (or globally) |
| Use case shape | Recursion via "step function" + captured `:self` | Direct self/mutual ref in fn-graph |
| Mutual recursion | Tag-dispatch convention within one `:fix` | Natural — refs cycle freely |
| Type checker impact | Recursive types (loose-typed MVP) | Cycle-aware traversal in topo-sort |
| Executor impact | None — uses existing closure-capture | Forward declarations + lazy thunks at every ref |
| Risk to existing code | Minimal | High — touches compile pipeline + delta-recompile |
| Effort estimate | ~3 hours focused | ~1-2 days focused |

Both approaches achieve correctness; the trade-off is **explicitness

+ minimum-change (A)** vs **user-side ergonomics (B)**.

---

## Approach A: `:fix` (Y-combinator + closure-capture)

### Core idea

Add ONE new base-fn that synthesizes a self-referential callable at
runtime. The recursive function is structured as a **step function**
that receives `:self` (the recursive callable) as a captured arg —
synthesized by `:fix`'s impl at wrap time using the closure-capture
mechanism shipped in commits 1–7 of the closure-capture series.

No graph-level cycle is required. `:fix` references `:step`; `:step`
references `:self` as a captured (free) arg. The recursive structure
emerges at runtime when `:fix`'s impl wraps `:step` into a callable
that references itself through an atom.

### Concrete shape

```edn
;; Factorial: fact(n) = n * fact(n-1), base fact(0) = 1.
;; :fix binds BOTH :step and :input; it invokes :step with
;; {:input <current> :self <1-arg callable>}.
{:name :fact
 :parent :fix
 :args {:step :_fact-step
        :input {:as :n}}}        ; initial value — user supplies :n

{:name :_fact-step
 :parent :if
 :args {:test :_input-zero?
        :then 1                  ; base case — does NOT invoke :self
        :else :_recurse-mul}}

;; the step reads the current iteration value from its :input free arg
{:name :_input-zero? :parent :zero? :args {:number {:as :input}}}

{:name :_recurse-mul
 :parent :mul
 :args {:nums [{:as :input} :_call-self]}}

;; recurse: :invoke the :self callable (:fix supplies it as a free arg)
;; with the next input, input - 1
{:name :_call-self
 :parent :invoke
 :args {:func {:as :self} :arg :_input-minus-1}}

{:name :_input-minus-1 :parent :sub :args {:nums [{:as :input} 1]}}
```

User invokes `:fact :n 5` → returns 120.

### Impl sketch

```clojure
(defbase fix-fn [step input]
  ;; step is invoked with {:input <current> :self <1-arg callable>}.
  ;; :self is a POSITIONAL single-arg callable that re-enters the step
  ;; with a new input; we synthesize it via an atom-closure so the
  ;; step's callable references the same `f`.
  (let [self-ref (atom nil)
        f (fn [next-input] (step {:input next-input :self @self-ref}))]
    (reset! self-ref f)
    (f input)))
```

The `:self` callable threads through every iteration: `:_call-self`
`:invoke`s it with the next input; `fix-fn` re-enters the step;
recursion bounded by `*max-recursion-depth*` (default 1000).

### Type signature (MVP)

```edn
;; LOOSE — recursive types are a follow-up. :step is bare :fn;
;; editor doesn't auto-derive recursive return type.
:fix
  :args {:step {:type :fn :description "Step fn; receives :self + user args, returns result."}
         :input {:type :any :description "Initial input to the recursive call."}}
  :return-type :any
```

A future tightening would express `:step :type [:fn {:self [:fn {:input a} b] :input a} b]` —
recursive type via the type-checker's bounded type-var unification.
That requires the type-checker to handle finite recursive type
expansion without infinite-looping (well-studied — see
"equirecursive types" in PL literature). MVP punts on this.

### Mutual recursion via tag-dispatch

For two-way mutual recursion (e.g. `even?` / `odd?`):

```edn
{:name :even-odd :parent :fix :args {:step :_eo-step :input {:as :n}}}

;; :cond's :clauses is a FLAT alternating seq [test result test result …]
{:name :_eo-step :parent :cond
 :args {:clauses [:_is-even-call :_eo-even-body
                  :_is-odd-call  :_eo-odd-body]}}

{:name :_eo-even-body :parent :if
 :args {:test :_input-zero? :then true
        :else :_call-self-with-odd}}

;; etc. — each branch :invoke's :self with a tagged input
```

Awkward but expressible. If mutual recursion becomes a frequent
pattern, fast-follow with a `:fix-pair` specialization or move to
approach B.

### Implementation cost

| Step | Time |
|---|---|
| `:fix` base-fn + impl in `core/system` (or new `core/recursion`) | 30m |
| Loose type signature + return type generic | 20m |
| `:ex-factorial` example in `examples/recursion/` | 20m |
| Unit test for `:fix-fn` impl | 30m |
| E2E integration test (factorial via executor) | 30m |
| Docs: replace ARCHITECTURE.md § Part 3 + cross-link CLOSURE_CAPTURE | 30m |
| Mutual recursion example via tag-dispatch (optional) | 30m |

**Total**: ~3 hours focused.

### Open questions

+ **Type-checker for recursive types**: when do we tighten from
  `:any` to structurally-recursive? Costs ramp; benefit accrues
  to editor's return-type display and to compile-time arity
  verification.
+ **`:self` calling convention**: map-callable vs positional? Map is
  simpler to type-check (no arity ambiguity); positional is closer to
  Clojure idiom. **Shipped: positional** — `:self` is a 1-arg callable
  re-entered via `:invoke :func :self :arg <next>`.
+ **Effects propagation**: ideally `:fix`'s declared effects = the
  union of `:step`'s effects; the type-checker would need to handle
  the recursive effect propagation without an infinite loop. As
  shipped, `:fix` declares `:effects #{}` (the HOF pure-by-contract
  house rule — same as `:map`/`:filter`): the static checker does no
  recursive union, so a `:fix` over an effectful step under-reports
  statically, while the runtime effect gate still records the step's
  actual effects at execution.

---

## Approach B: Lazy ref resolution (relax cycle check)

### Core idea

Remove the cycle check (per-binding + topological-sort) for fns
that opt in (or globally), and have the compiler generate **lazy
thunks** at every `ref-fn-id` site. The thunk looks up the target's
closure from the registry at first invocation, not at compile time.

No new base-fn. Recursion is written exactly as you'd expect:

```edn
{:name :fact :parent :if
 :args {:test :_n-zero?
        :then 1
        :else :_recurse-mul}}

{:name :_recurse-mul :parent :mul
 :args {:nums [:n :_fact-tail]}}

;; Direct ref BACK to :fact via parent — cycle-check
;; would currently reject this; under approach B it's allowed.
{:name :_fact-tail :parent :fact :args {:n :_n-minus-1}}

{:name :_n-minus-1 :parent :sub :args {:nums [:n 1]}}
```

### What needs to change

| Area | Change |
|---|---|
| `storage/protocol/constraints.clj` | Relax `validate-no-dependency-cycle-impl` — allow cycles (optionally gated by a `:recursive?` flag on the fn-row). |
| `executor/composition/deps.clj` | `topological-sort` learns to break cycles by breaking at any node — treats the cycle as a strongly-connected component, picks a root, emits the rest in best-effort order. |
| `executor/compile_eager.clj` | Every ref-entry builder becomes a `(rt/thunk #(let [callee (get all-fns ref-id)] ...))` — lazy registry lookup. Existing `*call-cache*` handles memoization within one invocation; `always-fresh-fn-ids` already bypasses cache for `:time` / `:random`. |
| `executor/compile-runtime.clj` `delta-recompile!` | Invalidation across SCC: when a node in a recursive cycle changes, recompile ALL members of the SCC together. The reverse-deps index needs to be SCC-aware. |
| `types/check.clj` | Type-check learns to handle recursive fn-types without infinite expansion. Same problem as approach A's type-tightening — well-studied PL territory. |

### Concrete API

If we add a `:recursive?` boolean to fn-row:

```edn
{:name :fact
 :recursive? true             ; opts into cycle check relaxation
 :parent :if
 :args {... :else :_recurse-mul}}
```

Without the flag, cycle check stays strict (preserves the current
invariant for non-recursive fn-defs). With the flag, the writer
explicitly says "this is recursive, I know what I'm doing."

This adds ONE field to fn-row — modest violation of principle #2,
defended by the gain in user ergonomics.

### Implementation cost

| Step | Time |
|---|---|
| Schema migration: add `:recursive?` field on fn-row | 30m |
| Relax `validate-no-dependency-cycle-impl` for recursive fns | 30m |
| Rewrite `topological-sort` to handle SCCs | 1h |
| Rewrite the ref-entry builder to be lazy for cyclic refs | 1h |
| Rewrite `delta-recompile!` to handle SCCs | 1h |
| Type-checker recursive-fn-type handling | 2-3h |
| Tests + examples + docs | 2h |

**Total**: ~1-2 days focused.

### Risks

+ **`delta-recompile!` correctness**: invalidating an SCC is more
  complex than invalidating a forward dependency chain. Bugs here
  produce stale closures or unnecessary recompilations.
+ **Type-checker termination**: recursive types need explicit
  bounds (equirecursive vs isorecursive trade-offs). Without
  careful handling, type-check infinite-loops on recursive defs.
+ **`*call-cache*` interaction**: the cache key is `[fn-id
  free-args]`. For recursive calls with different free-args, no
  collision. For recursive calls with the SAME free-args (rare but
  possible — e.g. memoization), the cache shortcuts the recursive
  call — actually CORRECT behavior (memo).
+ **Mutual recursion across packages**: if package A's fn refs
  package B's fn refs back to A, the topo-sort cycle now crosses
  package boundaries. Need to handle SCCs that span loader
  iterations.

### Why this is more lift

The cycle invariant is currently load-bearing in many subsystems:

+ `topological-sort` for compile order
+ `delta-recompile!` for invalidation
+ `delta-recompile!`'s reverse-deps index
+ Type-checker's dependency order
+ Layout's parent-chain rendering

Each of these assumes the dependency graph is a DAG. Relaxing that
invariant requires audits + targeted fixes in each.

---

## Recommended order

1. **Ship Approach A first**. Lower risk, smaller scope, leverages
   already-shipped closure-capture infrastructure. Covers ~80% of
   practical use cases (self-recursion). Provides a working answer
   to "how do I write a recursive fn-def in graphden today."
2. **Observe usage**. If mutual recursion via tag-dispatch becomes
   awkward in practice (3+ different recursive patterns hitting
   this in real apps), revisit.
3. **If revisit warranted**: build Approach B incrementally, gated
   behind the `:recursive?` flag so existing fns stay DAG-bound
   and the cycle invariant is preserved for non-opt-in cases.
4. **`exec/execute-by-name` from impl** stays an explicit
   anti-pattern after A lands — remove on sight in code review.

## Cross-links

+ [ARCHITECTURE.md § Part 3 — Recursion and Cycles](ARCHITECTURE.md#part-3-recursion-and-cycles) — current state
+ [CLOSURE_CAPTURE.md](CLOSURE_CAPTURE.md) — the mechanism Approach A leverages for `:self` synthesis
+ [CONSTRAINTS.md § 1 — No Dependency Cycle](CONSTRAINTS.md) — the invariant Approach A preserves and Approach B relaxes
+ [PHILOSOPHY.md § Design Principles](PHILOSOPHY.md) — basis for the per-approach scoring above
