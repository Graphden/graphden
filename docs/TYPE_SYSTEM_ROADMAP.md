# Type system — architectural roadmap

Branch: `feat/type-system-flow-sensitive`.

## End-goal

A type system that delivers, simultaneously:

1. **Soundness** — type-check accepts only what runtime accepts.
2. **Completeness for common patterns** — author writes a natural
   composition; type-check sees through it.
3. **Editor narrowing parity** — types shown in UI reflect what the
   code actually does at each call site, not the loosest signature.
4. **Maintenance ergonomics** — adding a new fn-def doesn't require
   reasoning about global slot-identity bleeding across unrelated
   flows.
5. **Automatic, comprehensive checks** — type-check + effect-track +
   secret-flow + the sweep all run automatically, all fail loud at
   sync, none are advisory-only for prod-affecting issues.

## Current state — 2026-06-16 (post-α' close)

- **Sweep at ZERO** across all 2236 fn-defs. Allowlist
  (`allowed-type-check-failures`) is `#{}`.
- Phase E hard-gate stays armed both directions
  (`:types/sweep-regression` on new failures,
  `:types/sweep-stale-allowlist` on dead entries).
- The original 10 `:_X-apply-*` family failures closed via Phase α'
  (caller-context propagation to rename-host leaves + per-use-site
  anon naming).
- The 11 follow-up nullability gaps that α' surfaced closed via
  author `:type T` assertions on the binding form (see ledger in
  `docs/TYPE_CHECK_BACKLOG.md § 2026-06-16 — Sweep at ZERO`).

Remaining architectural items below are NOT bugs in the current
state — the type-system is sound. They're forward-looking design
notes for the next time the type-checker's reach needs to grow.

## Phase #170 — Control-flow narrowing through `:if`/`:cond` guards

### Motivation

The 11 nullability gaps that α' surfaced (and that we closed with
ad-hoc `:type T` assertions) all share a shape:

```clojure
{:name :_X-data
 :parent :cond
 :args {:clauses [:_X-nil?     :err-rsp-nil
                  :_X-blank?   :err-rsp-blank
                  …
                  :true        :_X-apply]
        :parsed  :_X-parsed}}
```

`:_X-apply` is only reached when every preceding guard returned
false. So inside `:_X-apply`'s tree, the slot(s) those guards
protected are non-null / non-blank / etc. The author KNOWS this;
the type-checker doesn't.

Today's workaround: per-binding `{:ref :_X-parsed :type :uuid}`
(or similar) annotations document the runtime invariant. Sound
but ad-hoc — each new flow needs new annotations.

### Implementation sketch (multi-week)

A proper #170 implementation would:

1. **Recognise `:cond` / `:if` shapes in `build-caller-
   narrowings`**: when F's `:parent` is `:cond` (or `:if`),
   enumerate its clauses / branches in evaluation order.

2. **Per-clause guard inference**: for each clause's result-target,
   compute the conjunction of negated tests from prior clauses.
   For an `:if`, the `:then` branch sees `:test = true`, `:else`
   sees `:test = false`.

3. **Guard-to-slot-narrowing rules**: a small dispatch table that
   maps a recognised predicate shape to the narrowing it implies:
   - `:nil? :_x` true → `:_x := :null`; false → strip `:null`.
   - `:some? :_x` mirror.
   - `:str-blank? :_x` false → `:_x` is non-null non-blank text.
   - `:and [pred1 pred2 …]` false → each pred is recursively
     decomposed (any false-arm in the AND being true contributes
     a narrowing).
   - `:or [pred1 pred2 …]` true → narrowing from ANY arm.
   - Composed guards (`:_X-blank?` wrapping `:str-blank? :_X`)
     resolved one level deep — beyond that the inference gives
     up (still sound, just less precise).

4. **Propagate narrowings into `*caller-narrowings*` for the
   result-target**: each clause's `:result` ref gets the union of
   negated-guard narrowings overlaid on the existing α'
   narrowings.

5. **Soundness**: the inferred narrowing is a runtime invariant the
   `:cond` / `:if` semantics ENFORCE. The type-checker is
   reflecting (not asserting) what the runtime already guarantees.

### Tradeoffs

Cost: ~2 weeks to land cleanly. The guard-decomposition needs
careful design (which predicates to recognise, how deep to walk,
how to combine via `:and` / `:or`).

Value: replaces the ~11 author assertions with automatic
narrowings. Future flows that follow the pattern get correct
types without per-site annotations.

Risk: precision degradations elsewhere. Tighter narrowings can
expose new gaps downstream — the same way α' surfaced the 11
nullability cases.

### Recommendation

Defer until the next time the type-checker's precision needs
investment. The current `:type T` annotations are sound and
self-documenting; the Phase E gate ensures we won't lose
visibility into similar new gaps.

## Phase γ — Row polymorphism

### Motivation

Phase α' closed cross-flow contamination by uniquifying inline
anons per use-site. That works because the SAME structural anon
appearing in two flows now has two registry entries with
independent narrowings. The cost is a modest registry growth
(532 → 695 anons) and the loss of the "same shape collapses"
optimisation.

A row-polymorphic type system would solve the same problem from
the opposite direction: keep the ONE registry entry, but type
its slots POLYMORPHICALLY so each caller's narrowing applies
without poisoning siblings. A slot would carry a row variable
`'ρ` denoting "the rest of the record's fields", unified per
call-site.

### Worked example

A naive open-record subtype check `record-subtype? sub sup`
accepts sub iff every field in sup has a sub field with
sub.k ⊆ sup.k. The implementation today is structural.

Row-polymorphic equivalent:

```
:_X-apply-entity-type-str's anon : (record-with :coll T_ρ_1 + ρ_1)
                                   where T_ρ_1 = the create-flow caller's :parsed
:_Y-some-other-anon              : (record-with :coll T_ρ_2 + ρ_2)
                                   where T_ρ_2 = the seq-flow caller's :parsed
```

The two anons COULD be unified onto one registry entry with type
`(record-with :coll 'τ + 'ρ)`, where `'τ` and `'ρ` get bound at
each call-site. The narrowing per-call-site is encoded in the
substitution, not in the registry entry's resolved-bindings.

### Implementation cost

The type-rep change reaches every consumer of
`types.core/record-type?` / `subtype?` / `unify`. Records would
gain an optional row-variable; the unifier would need to
distinguish "closed" (all fields known) from "open" (row-variable
present) records. Existing rules that introspect records
(`:get`, `:assoc`, `:select-keys`, …) would need to handle the
open case.

Estimate: ~3 weeks of focused work, plus a long settle-in period
as edge cases surface.

### Value over α'

Concrete benefit: smaller registry, no need for per-use-site
anon naming. Conceptual cleanliness: types describe what the
function CAN handle, not what one specific caller does.

But α' delivers identical CORRECTNESS today with a fraction of
the implementation cost. The remaining gap is mostly aesthetic
(registry size + the per-use-site naming convention).

### Recommendation

Defer indefinitely unless: (a) registry size becomes a real
problem, OR (b) a feature genuinely requires open-record
polymorphism that α' can't express (none identified today).

## Sequencing & checkpoints — historical (kept for context)

- Sweep baseline: 11 fn-defs fail. All in `web/crud`'s `_X-apply-*`
  family (CREATE / UPDATE / DELETE flows).
- Pattern: `:_X-apply-entity-type-str = (:name (:get :parsed
  :entity-type :default nil))` where `:parsed` is a free arg whose
  type the type-checker sees as `:any` at per-fn-def-isolated check
  time.
- Real type, at the call chain root (`:process-create-entity`):
  `:parsed :_create-parsed`, where `:_create-parsed` produces a
  record matching `:_create-parsed-shape`.
- Architectural gap: **caller-context type information does not
  flow DOWN to callees in the current single-pass topological
  type-check**.
- Symptom is narrow (runtime unaffected, editor strips missing),
  but the gap is general — every shared-arg-name pattern across
  structurally-different flows hits the same ceiling.

## The root architectural tension

> Slots in graphden are **global identities** (one-shot creation,
> immutable). Bindings overlay them. The same slot-id can be used
> by multiple fn-defs in **structurally different flows**.

When a shared slot — e.g. `:get`'s `:coll`, or a renamed-view slot
like `:parsed` — is used in CREATE / UPDATE / DELETE flows with
different record shapes, the type-checker has TWO bad options:

* **Type the slot globally** → bleeds across flows. Tightening one
  breaks siblings.
* **Leave the slot `:any`** → loses narrowing in every flow.

Neither is sound + complete. The honest fix: types must be
**call-context-local**, not slot-local.

## Three viable architectural answers

| # | Approach | Cost | Cleanliness |
|---|----------|------|-------------|
| α | Per-flow rename (mechanical) — `{:as :create-parsed}` / `{:as :update-parsed}` / `{:as :delete-parsed}` | low | low (touches dozens of fn-defs in web/crud, doesn't generalise) |
| β | Caller-context propagation (DOWNWARD type-flow second pass) | mid | high (extends the existing `effective-ref-return` mechanism symmetrically) |
| γ | True row polymorphism (HM-style row variables) | high | highest (reworks `subtype?` / `unify` deeply) |

α is a band-aid — declared dead in earlier session ("без
компромиссов ради скорости"). γ is the textbook answer but reworks
type representation across the codebase. β is a measured extension
of the current mechanism — adds a second pass, preserves single
slot-identity semantics, generalises beyond the 11 failures.

**Recommendation: β as the next milestone. γ as a long-horizon
option if β's expressiveness ceiling shows up empirically.**

## β attempt (2026-06-16) — reverted

A first prototype of β was implemented and reverted in the same
session. The core insight: **per-NAME propagation through the
ref-graph is unsound because graphden's slot identities are
distinct from slot NAMES.** Multiple flows independently use the
name `:parsed` (create / update / delete / seq-append / record-
type / list-type / types-compatible — 9 distinct binders, 7
distinct shapes), and the create-flow's chain re-uses utility fn-
defs (`:_rejection-response`, `:html-error-response`, `:_request-
body`, …) shared with update-flow / handler scaffolding. A BFS over
the ref-graph keyed on `:parsed` correctly narrowed the create-flow
LEAVES (`:_create-apply-entity-type-str` → `:text`, `:_anon-XXX`'s
`:coll` → `:_create-parsed-shape`) but also leaked the narrowing
to unrelated fn-defs sharing `:parsed` semantics elsewhere — sweep
went 10 → 28-31 with new false-positive `:get :body` /
`:get :id` failures on update-flow / seq-flow descendants.

The prototype tried three increasing restrictions to scope
propagation:

1. **Parent-slots filter** — skip propagation when the binder's
   arg-name is one of the parent base-fn's slot names (it's
   binding the parent's contract, not a free-arg).
2. **Free-arg-of-child filter** — propagation requires `arg-name`
   to be in the union of F's ref-children's lifted free args.
3. **Walk-through-rebind filter** — terminate BFS at fn-defs that
   re-bind `arg-name` with a real value/ref (a bare `{:as :name}`
   rename does NOT terminate, it just re-exposes the slot under
   the same name).

Each step cut false propagations but none captured the root
constraint: **two flows can share a free-arg name without sharing
its slot identity**. The shared-utility ref-tree (`:html-error-
response` → `:_html-error-body`'s `{:as :reason}` rename → its
`:status` rename → …) creates ad-hoc identity-by-name overlaps
the per-name view can't disambiguate.

The reverted code is in the git history; the dormant primitives
(`*caller-narrowings*` dynvar, the rename branch in `bindings-info-
for-rule`, `collect-free-args`'s rename-narrowing, the parent-args
overlay) were removed too — each adds complexity that returns no
value until the propagation problem is solved.

### Why per-name propagation is fundamentally wrong

The runtime semantics of a free-arg are tied to its **slot
identity** (the slot-id introduced by the original rename in the
parser pre-pass). Two `{:as :parsed}` renames at the inline-anon
level of `:_create-apply-entity-type-str` and `:_seq-append-body-
invalid?` create two DIFFERENT slot-ids (each rename is a fresh
slot record). They share the AS-NAME `:parsed` but ARE NOT the
same slot.

The type-checker today operates one-level above this: it sees the
AS-NAME and treats it as the free arg's identity. For Pass-1's
local check this is fine — the slot type doesn't escape the
fn-def's local computation. For Pass-2 propagation across the
ref-graph it ISN'T fine — the AS-NAME's TYPE flows through
distinct slot-ids that may need different types.

## Architectural direction forward

Two clean answers, each substantial:

### Option γ — Row polymorphism (architecturally correct)

Slot types parameterised over **row variables** — a record type
`[:record {fields} 'rho]` where `'rho` is unbound row content
that can be unified per call-site. `:get :coll :parsed :key :K`
fires with `:coll = [:record {:K T} 'rho]` (open record, requires
field K, accepts any other fields). At each call-site that binds
`:parsed`, row-variable unification fills `'rho` with the
caller's actual record minus the required fields.

Tradeoffs: clean semantics, full HM treatment of polymorphism.
Touches `types/core`'s `subtype?` + `unify` significantly;
record-type representation gains a row-variable field; the
existing record-subtype rule needs to handle open / closed
distinction; downstream code that introspects records (rules,
editor) needs to know about row variables. Multi-week.

### Option α + slot-id awareness

A pragmatic middle: keep records closed but track **slot-id**
through the rename chain. Every free arg in the registry carries
its source slot-id (from the rename that introduced it). Pass 2
propagation matches on slot-id, not name — so `:parsed` from
create-flow's `_anon-XXX` has slot-id X, and only fn-defs whose
free-arg `:parsed` originates from slot-id X get narrowed.

Tradeoff: avoids the type-system rewrite of γ, but requires
threading slot-id through the parser's pre-pass output, the
registry's `:args` map (currently `{name → type}` → becomes
`{name → {:type T :slot-id S}}`), and the BFS walker. Mid-week.

## Recommendation

α + slot-id awareness is the smaller commitment with a clear
endpoint and gives us a sound β. γ is the textbook answer but
intersects with several other open type-system items (subtype
asymmetries, `:any`-as-typevar, secret-flow's wrap/unwrap
contract) and would be best done as a dedicated initiative when
the broader type-system has fewer open fronts.

Holding β at the "reverted prototype" mark; resuming via the α
route in a focused follow-up.

## β archive — original design notes

The remainder of this section documents the original β design
(retained for reference; the implementation is no longer in the
codebase).

### Concept

Currently `effective-ref-return` (types/check.clj:1645) re-fires
ONE ref's root-base-fn rule with caller bindings overlaid. This
narrows the IMMEDIATE binding but doesn't propagate further: the
ref's INNER call-graph is not re-checked.

Extend that mechanism to a **second post-pass** over the topology:

1. **Pass 1 (current)** — topological per-fn-def isolated check.
   Records `:return`, `:args` (free-args), `:slot-types`,
   `:resolved-bindings`. Unchanged from today.

2. **Pass 2 (NEW)** — reverse topological. For each fn-def F:
   - Read F's `:args` ref-bindings (e.g. `:parsed :_create-parsed`).
   - For each ref R, compute the ref's narrowed return WITH F's
     bindings as caller-context (same as `effective-ref-return`,
     extended to look at R's transitive ref tree).
   - Propagate: for every fn-def G transitively reachable from R
     by following ref-fn-ids in F's binding closure, refine G's
     free-arg type-map: if G has a free arg `:x` that R or one of
     R's transitive callees binds to F's narrowed value, refine
     `:x`'s type to that narrowed value.
   - Memoise per `(G, call-context-signature)` pair to bound the
     work — same caller-context for same fn-def is computed once.

3. **Pass 3** — iterate Pass 2 to fixpoint (or N=3 max with a
   sweep-residual report). Refinements in Pass 2 may unlock
   further narrowings.

### Concrete worked example — 11 failures

After Pass 1: `:_create-apply-entity-type-str :return [:union :null :text]`. 
`:process-create-entity :args {:parsed :_create-parsed}`.

Pass 2 visits `:process-create-entity`:
- `:parsed` ref'd to `:_create-parsed`, narrow type `:_create-parsed-shape`
  (assuming Phase A — see below — lets `:_create-parsed` declare it).
- Transitive ref tree under `:_create-parsed`: includes `:_create-apply`,
  `:_create-apply-result`, `:_create-apply-entity-type-str`, …
- Each of those has a free arg `:parsed`. Refine to
  `:_create-parsed-shape`.
- `:_create-apply-entity-type-str`'s rule fires again with
  `:parsed :_create-parsed-shape` → inline `:get`'s `:coll` is
  now a typed record → `:entity-type` narrows to `:keyword` →
  `:name`'s narrowing rule (already landed, see commit
  `0bb04678`) lifts `[:union :null :keyword]` to `:keyword`,
  hence final `:text`. Computed return now matches the parent's
  `:entity-type :text` slot. **Closed.**

The 11 close together, not one at a time.

### Boundaries — what β does NOT add

- Type representation unchanged. Slots remain global identities;
  records stay closed.
- Effect / secret tracking unchanged (those pipe through
  `:effects` / `:secret`-marked unions, orthogonal).
- Author-facing API unchanged: `:type` annotations on rename
  bindings, `:return-type` declarations on fn-defs continue to
  work and override Pass-2 inference.

### Risk register — β

| Risk | Mitigation |
|------|-----------|
| Pass 2 changes the recorded `:return` of fn-defs whose Pass 1 return was `:any`-ish — downstream consumers may now see narrower types and trip `enforce-declared-return!` against stale `:return-type` declarations | Pass 2 narrowings recorded under a SEPARATE registry field (`:call-context-types`) — `:return` stays Pass-1 value. `enforce-declared-return!` continues to read `:return`. Editor / `bb types-drift` query the narrower form when available. |
| Combinatorial blowup of per-(fn, context) keys in the memo | Bound by sweep-time iteration budget; if explosion observed, fall back to a one-level-only Pass 2 (the 11 failures only need one level). |
| Hidden ordering dependency (Pass 2 result depends on which caller is visited first when a fn-def has multiple callers with different contexts) | If multiple callers narrow the same free arg differently, take the LUB (least-upper-bound — union) of their types. This is the sound choice; loses precision but never lies. |
| Existing `effective-ref-return` already implements one-level narrowing — duplication or conflict? | Reuse `effective-ref-return` as the per-step propagator. Pass 2 calls it across the transitive ref-tree systematically. The implementations converge, not duplicate. |

### Test plan — β

1. Unit tests in `test/graphden/types/check_test.clj`:
   - `pass-2-narrows-free-arg-through-ref-tree` — a 3-level chain
     where free arg flows down, asserts narrowed type at the leaf.
   - `pass-2-lubs-on-multiple-callers` — same fn-def called from
     two contexts with different types → result is the LUB.
   - `pass-2-respects-author-annotation` — `:type T` on rename
     binding wins over Pass-2 inference.
2. End-to-end sweep assertion in `system/core.clj` startup tests:
   - After Pass 2, sweep count == 0.
3. Performance gate: `bb test` total runtime must not grow >5%.

## Phase A — Enable `:_X-parsed :return-type :_X-parsed-shape`

Independent of β but unlocks its highest-impact case.

`:_create-parsed`'s computed return today is `:if`'s union over
`:then`/`:else` branches. The branches both produce
structurally-`:_create-parsed-shape`-compatible records, but the
unifier widens to `:jsonb`. Tighten `:if`'s rule:

- When BOTH branches' computed types are record-types AND one is
  a subtype of the other, return the wider record.
- When BOTH branches' computed types unify to a SHARED structural
  shape (record / list / map), return that shape.
- Else fall back to today's `[:union :branch1 :branch2]`.

After this fix, the author can declare `:return-type` on
`:_create-parsed` / `:_update-parsed` / `:_delete-parsed`, and
`enforce-declared-return!` will validate it. With those
declarations, Pass 2 has typed roots for the call-context flow.

## Phase C — `:type` on rename bindings localised

`{:as :parsed :type T}` on ONE inline anon today contaminates the
SHARED `:parsed` free arg type at the outer fn-def, breaking
siblings that bind `:parsed` without `:type`. Fix in
`collect-free-args`: when multiple renames lift to the same
`:as`-name, prefer the most-specific typed one and verify the
others are subtype-compatible (error otherwise).

This is a small, independent fix — could land before β as a way
to opt into call-context narrowing manually for high-value chains.

## Phase D — Editor surface

`/api/types/registry` exposes `:return`. After β, also expose
`:call-context-types`. Editor's type-chip (effect/return strip,
`createTypeChip`, provenance popover) shows the narrowed form
when available. Backlog of UI work; documented in
`docs/CLOSURE_CAPTURE.md` adjacent.

## Phase E — Safety surface

- Every type-check warning gated as build-failing in CI (today
  the sweep WARN is advisory).
- `bb verify` extended to fail on:
  - Any sweep failure.
  - Any effect-drift gap > 0.
  - Any secret-flow validator warning.

This is the "автоматическая всесторонняя проверка" deliverable.

## Outcome — 2026-06-16 closure

The original plan called for sequencing C → A → β → E → D with γ
held as long-horizon. Actual landings:

| Phase | Status |
|-------|--------|
| A (typed `_X-parsed` returns + typevar binding + narrowing-assertion) | LANDED |
| β (caller-context propagation) | ATTEMPTED + REVERTED — per-name BFS conflated flows; superseded by α' v3 |
| α' (per-use-site anon naming + rename-leaf narrowing + `effective-ref-return` merge-flip) | LANDED — closed the original 10 |
| E (hard-gate sweep + allowlist + tests) | LANDED — both directions, bidirectional gate |
| C (`:type` rename localisation) | not needed — α' supersedes the use case |
| D (editor surface) | LANDED implicitly — α' writes narrowed types into the canonical `:return` field; editor reads them via `/api/types` without any UI change |
| #170 (control-flow narrowing) | DEFERRED — see § above; 11 nullability gaps closed via author-typed assertions instead |
| γ (row polymorphism) | DEFERRED — see § above; α' delivers identical correctness today |

Final state: sweep at zero, allowlist empty, Phase E gate armed.
No known type-system bugs in production. The deferred work is
listed above with implementation sketches for when next-stage
investment makes sense.
