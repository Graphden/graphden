# Closure-Capture in fn-graph composition

## Why

graphden's existing HOF wrapping (`hof-callable` in
`graphden.executor.compile-runtime`) treats every free-arg of a
wrapped fn-graph as a **call-site arg** — the executor passes the
arg's value when invoking the callable. That works perfectly for
data-flow HOFs like `:map`, `:filter`, `:reduce`: the callable
receives one arg per element / accumulator and pure-functionally
produces a result.

It does NOT work for **supervised long-running patterns** like cron.
Concretely:

```edn
;; Hypothetical pure-composition for cron schedule:
{:name :schedule
 :parent :future                    ; ← :body is HOF-wrapped
 :args {:body :_cron-loop}}

{:name :_cron-loop
 :parent :loop-until-interrupted    ; ← :body is HOF-wrapped
 :args {:body :_cron-step}}

{:name :_cron-step
 :parent :do
 :args {:steps [:_sleep-to-next :_fire-target]}}

{:name :_fire-target
 :parent :call-noargs
 :args {:func {:as :fn}}}            ; ← `:fn` becomes a free-arg
```

When admin derives `:my-cron :parent :schedule :args {:cron … :fn …}`
and the reconciler starts it:
1. `:schedule` is invoked with `:cron` and `:fn` bound.
2. Inside, `:future` HOF-wraps `:_cron-loop` and calls it as `(body)`.
3. Existing wrapper signature: `(body args-map)` — but we pass zero
   args, so `args-map` is `{}`.
4. `:_cron-loop` internally references `:_cron-step` which references
   `:_fire-target` which expects free-arg `:fn`. With `args-map = {}`,
   `:fn` is unbound → runtime error.

The `:fn` binding **exists** at admin's `:my-cron` level — but the
HOF wrapper doesn't carry it through. Inner free-args are treated as
call-site args even when no caller in the chain supplies them.

## Specification

Each free-arg of a wrapped fn-graph belongs to ONE of two categories:

| Category | Source | Resolved when |
|----------|--------|---------------|
| **call-site** | Listed in the parent slot's structural `[:fn {args-shape} ret]` type | At each invocation, the executor passes the arg |
| **captured** | Free-arg NOT in `args-shape` | At wrap time, executor captures the binding-chain |

For `:map :fn [:fn {:item a} b]`:
- `:item` is in `args-shape` → call-site (passed per element)
- Any other free-arg of the wrapped fn-graph → captured

For `:future :body [:fn {} :any]`:
- `args-shape = {}` → no call-site args
- ALL free-args of the wrapped fn-graph → captured

For `:schedule` composition:
- `:_cron-loop` wraps `:_cron-step` (via `:loop-until-interrupted`)
- `:_cron-step` references `:_fire-target` which uses `:fn`
- `:fn` is NOT in `:loop-until-interrupted`'s `:body` `args-shape` (`{}`)
- → `:fn` is a captured arg of the wrapped callable
- → must be in the outer binding-chain at wrap time
- → IS, because admin's `:my-cron :args {:fn …}` binds it before
  `:future` evaluates its `:body` arg

## Implementation contract

### Wrap-time capture (`hof-callable`)

When a fn-graph is HOF-wrapped, the executor:
1. Computes the call-site args from the slot's declared `[:fn ARGS _]` shape.
2. For every OTHER free-arg of the fn-graph (transitively, through
   references), captures its value from the current binding-chain.
3. Returns a callable that, when invoked with `call-site-args`, evaluates
   the fn-graph with `(merge captured call-site-args)`.

Captured args resolve once, at wrap time — invariant per wrap. Call-site
args resolve per invocation.

### Transitive free-arg propagation (`free-arg-slot-map`)

A composed fn-def's free-args include:
1. Slots in the inheritance chain that aren't bound (existing semantics)
2. **PLUS** captured-args of any HOF-typed binding's referenced
   fn-graph that aren't bound at this fn-def's level (NEW)

For `:schedule` (fn-def):
- Inherited slots from `:future`: `{:body}` — bound to `:_cron-loop`
- Captured args of `:_cron-loop`: `{:cron, :fn}` (transitive through
  `:_cron-step` → `:_fire-target`)
- Neither bound at `:schedule` level → both become free-args of `:schedule`

When admin derives `:my-cron :parent :schedule :args {:cron … :fn …}`:
- `:cron` and `:fn` are now bound at `:my-cron`'s level
- Inherited captured args from `:schedule` are now resolved
- `:my-cron` has zero free-args → service-eligible

### Type-system

Structural fn-type `[:fn {args-shape} ret effects]` is unchanged. The
distinction between call-site and captured is computed at use-site
(when matching a binding's value against the slot type's structural
shape), not encoded in the type itself.

Two cooperating type-system changes ship with this extension:

1. **Free-arg propagation** (`types/check.clj`). `ref-free-args`
   lifts a HOF-bound ref's free args MINUS the slot's structural
   call-site arg names. The remainder are captured and surface on
   the calling fn-def's `:args`. Without this, `:schedule`'s
   computed `:args` would be `{}` and the editor / parser would
   have no way for admins to bind `:cron` / `:fn`.

2. **fn-args subtyping** (`types/core.clj`). `fn-args-subtype?`
   accepts ANY sub arity when the sup slot is 0-arg. This is the
   structural counterpart to wrap-time capture: a sub callable
   with captured args still satisfies a 0-arg HOF slot — the wrap
   passes nothing per call; captured args resolve from the binding
   chain. Without this, `:_cron-step` (computed type
   `[:fn {:cron :text, :fn [:fn {} a]} :any]`) would fail the
   `[:fn {} :any]` subtype check at `:loop-until-interrupted :body`.

### Runtime

`hof-wrap` (`executor/compile.clj`) is the captured-vs-call-site merge
point — `outer-free-args` snapshots the binding chain at wrap time,
lambda-params overlay per call. ALREADY structurally correct pre-
extension (verified in commit 3).

`hof-lambda-params` (`executor/compile/renames.clj`) IS the wrap-arity
dispatcher. It reads the slot's structural shape (via
`slot-structural-call-site-args`) and chooses:

- **0-arg slot** (`[:fn {} ret]`) → 0 lambda-params → variadic-ignore
  wrap. All sub free-args captured. The cron / `:future :body` /
  `:loop-until-interrupted :body` path.
- **1-arg slot** (`[:fn {:x T} ret]`) → structural name when R's free
  args include it (the fast path — sub-fn was written to match the
  slot, e.g. `:try`'s `:on-throw` typed `[:fn {:exception :any} a]`
  with `_rollback` declaring `:exception`); otherwise the
  alpha-equivalence resolver picks the lambda-param name from R's
  non-captured frees — covers conventional positional callsites like
  `:filter :pred :some?` (slot's positional `:item` vs `:some?`'s
  domain-named `:value`).
- **2+-arg slot** (`[:fn {:a A :b B} ret]`) → sub free args matching
  slot's structural names. Map-callable; covers
  `:wrap-middleware :handler` (`{:request _ :next-handler _}`).
- **Bare `:fn` keyword constraint** → REJECTED at compile time. Every
  HOF slot must declare its callable shape structurally as
  `[:fn {ARGS} RET]` — gives the type-checker something to constrain
  on and makes the wrap-time dispatch deterministic.

Without this dispatcher rewrite, the cron's wrap would be built with
sub's full free-arg count (e.g. 2-arg map-callable for `:_cron-step`),
and `:future`'s impl `(body)` would throw `ArityException` at runtime
— a bug the type system couldn't catch.

## Edge cases

### Captured arg shadowed by call-site arg

```edn
{:name :my-mapper
 :parent :map
 :args {:item :_some-default}}      ; ← binds `:item` (the call-site arg!)
```

`:map`'s `:fn` slot declares `:item` as call-site. Admin bound `:item`
to `:_some-default`. What wins at invocation?

**Rule:** call-site arg WINS over captured / bound. `:map`'s impl
passes `:item element` per iteration; this overrides admin's binding.

This matches Clojure semantics: `(fn [item] item)` always sees its
caller's arg, regardless of a `let` that might happen to define a
local `item` later.

### Captured arg not in scope

```edn
{:name :_floating
 :parent :call-noargs
 :args {:func {:as :missing-binding}}}

{:name :wrap-floating
 :parent :future
 :args {:body :_floating}}
```

`:_floating` requires captured arg `:missing-binding`. `:wrap-floating`
doesn't bind it. At wrap-time, executor looks for `:missing-binding`
in the binding-chain and doesn't find it.

**Rule:** captured arg unresolved at wrap time = the fn-def has
`:missing-binding` as a free-arg. Surfaces at validate-execute /
service-create / wherever free-args matter.

### Captured arg vs ancestor binding

```edn
{:name :outer
 :args {:my-thing "outer-value"}    ; ← ancestor binding
 :parent :wrap-floating}            ; from previous example
```

Now `:wrap-floating`'s `:_floating` wants `:my-thing` (captured). The
binding-chain at wrap time includes `:outer`'s `:my-thing "outer-value"`.
Captured.

**Rule:** captured arg resolution walks the standard binding-chain,
same as any other resolved binding. Closer-fn-wins applies.

### Cross-tree rename via ref-target chain

```edn
{:name :_html-error-body
 :parent :str
 :args {:parts [{:value "<p class=\"error\">"} {:as :reason} {:value "</p>"}]}}

{:name :html-error-response
 :parent :assoc
 :args {:map {…}
        :key {:value :body}
        :value :_html-error-body}}     ; ← ref-target to :_html-error-body

{:name :_delete-err-fn-in-use
 :parent :html-error-response
 :args {:status {:value 409}
        :reason {:as :fn-in-use-reason}}}      ; ← renames R's free arg
```

`:_html-error-body` is reached via the `:value` ref-target of
`:html-error-response`. Inside its `:str :parts` it positionally
captures `{:as :reason}`, exposing `:reason` as a deep free arg
of `:html-error-response`. The descendant `:_delete-err-fn-in-use`
binds `:reason {:as :fn-in-use-reason}`, creating a renamed-view
slot on the descendant whose `:source-slot-id` FK points at
`:_html-error-body.reason`.

**Rule:** at compile time, `build-ref-renames` (in
`executor/compile/renames.clj`) resolves cross-tree renames in two
passes:

1. **Same-name binding rename** — F's collected bindings contain a
   `:free` row with a renamed `:ext-name` matching one of R's deep
   frees BY NAME. Covers e.g. `:_pocb-rows-consumer.func {:as
   :storage-query}`, where F directly binds a slot R exposes under
   the same name. Locked by
   `executor.compile.renames-test/build-ref-renames-translates-
   renamed-slot`.

2. **Cross-tree slot-id rename** — for any R-free not covered by
   pass 1, walk R's tree (inheritance + non-HOF ref-targets + seq
   items) via `find-slot-id-in-tree` to find R's slot-id, then ask
   F's inheritance chain via `l/rename-for-slot` whether it owns a
   renamed-view slot whose `:source-slot-id` FK points at THAT
   source. Picks up the `:html-error-response`-style case above —
   where the rename's source slot lives on a fn reached via
   ref-target, not via inheritance. Locked by
   `executor.compile.renames-test/build-ref-renames-cross-tree-via-
   slot-id`.

The two passes are merged; the same-name pass wins on conflict.
The cross-tree pass relies on the parser ALSO emitting the empty
pure-rename binding row (signal that triggers
`effective-binding`'s direct-inheritance rename path in
`executor/compile/bindings.clj`); both mechanisms work side by
side.

## Existing HOF behavior compatibility

`:map` / `:filter` / `:reduce` / middleware / router / Ring handlers —
all use HOF wrap. After this change:

- Any free-arg of their wrapped fn-graphs that matches a call-site
  arg in the declared shape — works exactly as today.
- Any OTHER free-arg becomes a captured arg.

**Compatibility check:** existing fn-defs that reference HOF args
without binding the inner free-args were either:
- Already broken (runtime error on call) — these would become "captured
  arg unresolved" errors instead, equivalent failure mode.
- Working because the chain SOMEWHERE bound the args — these continue
  working: captured args resolve through the binding-chain same as
  before.

No regressions expected for the existing HOF use cases. Specifically:
- Ring handlers: their `:request` free-arg matches `:handler`'s
  `[:fn {:request _} _]` call-site arg. Unchanged.
- Middleware: each middleware's wrapped body has `:request` /
  `:next-handler` matching the structural type. Unchanged.
- `:map` / `:filter`: the wrapped fn's `:item` matches the structural
  type's call-site arg. Unchanged.

## Implementation plan (as shipped)

| # | Commit | What landed | Touches |
|---|--------|-------------|---------|
| 1 | `6d4c0f43` | Spec + failing tests + regression baseline (3 `:hof-regression` invariants pinned) | `docs/CLOSURE_CAPTURE.md`, `test/graphden/types/closure_capture_test.clj` |
| 2 | `62b276f9` | `free-arg-slot-map` walks captured chain — service-eligibility recognizes `:schedule`'s captured args. Internal recursion via `free-args-via` threads a pre-queried bulk-entity `db` map to avoid re-fetching | `src/graphden/crud/fn_execution/lookup.clj` |
| 3 | `7d309ce2` | Verified `hof-wrap` already implements wrap-time capture per spec; added regression test + docstring pointer (no implementation change in compile.clj's `hof-wrap`) | `src/graphden/executor/compile.clj` (doc), `test/graphden/types/closure_capture_test.clj` |
| 4 | `ecb99fc4` | Type-checker propagates transitive captured free-args at HOF boundaries. New `hof-call-site-arg-names`; `ref-free-args` lifts `ref-args - call-site` | `src/graphden/types/check.clj`, `test/graphden/types/check_test.clj` |
| 5 | `a8e95f41` | Re-composed `:schedule` as fn-def over `:future` / `:loop-until-interrupted` / `:do` / `:sleep-until-ms` / `:cron-next-after` / `:call-noargs`. Added `:call-noargs` + `:sleep-until-ms` base-fns. `fn-args-subtype?` accepts any sub arity for 0-arg sup slots. Type-level acceptance tests green | `src/graphden/types/core.clj`, `resources/packages/core/system/{fns.edn,impls.clj}`, `resources/packages/core/concurrency/{fns.edn,impls.clj}`, `test/graphden/types/closure_capture_test.clj` |
| 6 | `a44ca326` | HOF regression audit — 279 tests across executor / type-system / reitit / crud / hof / collections / concurrency / fn-execution all clean. Retired final `:closure-capture-pending` placeholder (semantic structurally guaranteed by `hof-wrap`'s merge order) | `test/graphden/types/closure_capture_test.clj` |
| 7 | `eb5687d5` | **Runtime gap fix.** Type-level acceptance was green but the end-to-end cron wouldn't actually fire — `hof-lambda-params` computed wrap arity from sub's free-arg count instead of slot's structural shape, so the cron loop's `(body)` got an N-arg map-callable wrap and threw `ArityException`. New `slot-structural-call-site-args` + 4-way dispatch (0-arg / 1-arg / 2+-arg / bare-`:fn`). `:slot-id` added to `classify-slot` output so the wrap site can read the slot's structural type. New `cron-schedule-runtime-test` brings up the full Integrant system + creates a derived `:_cron-runtime-target`, watches the counter increment from inside the background thread, asserts the stopper unwinds cleanly | `src/graphden/executor/compile/{renames.clj,bindings.clj}`, `src/graphden/executor/compile.clj`, `test/graphden/integration/cron_schedule_runtime_test.clj` (NEW) |

## Glossary

- **Wrap site** — the location where `hof-callable` wraps a fn-graph
  into a Clojure callable. Typically inside a parent fn's binding
  resolution (e.g. `:map`'s `:fn` slot).
- **Call site** — the location where the wrapped callable is invoked.
  Inside the parent fn's impl (e.g. `(clojure.core/map fn coll)`).
- **Captured binding** — the binding-chain entry that resolves a
  captured arg. Snapshotted at wrap time, immutable for that wrap's
  lifetime.
- **Call-site arg** — a free-arg of the wrapped fn-graph whose name
  matches a key in the parent slot's structural `[:fn ARGS _]` shape.
  Supplied per-invocation by the parent fn's impl.
