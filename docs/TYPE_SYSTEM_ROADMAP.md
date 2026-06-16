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

## Current state — 2026-06-16

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

## β — Caller-context type propagation

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

## Sequencing & checkpoints

| Order | Phase | Checkpoint signal |
|-------|-------|-------------------|
| 1 | C (`:type` rename localisation) | `bb ci` green + new test covers multi-rename case |
| 2 | A (`:if`-rule structural narrowing) | `:_create-parsed` declares `:return-type :_create-parsed-shape`, sweep stays at 11 (no new failures) |
| 3 | β Pass 2 scaffolding (no propagation yet) | unit test confirms second pass visits expected fn-defs |
| 4 | β Pass 2 single-level narrowing | 3-5 of the 11 close |
| 5 | β Pass 2 transitive narrowing | all 11 close, sweep = 0 |
| 6 | E (CI gating) | sweep failure → CI red |
| 7 | D (editor surface) | editor shows narrowed types on the 11 chains |

Each step is its own commit (or small commit group) with
green CI. β scaffolding can land before any narrowing wires up;
γ stays reserved as future direction if β plateaus.

## Open questions for sign-off

1. Order — start with C (small, independent, low-risk) or jump
   straight to A → β (tackles the root cause faster but each
   step is bigger)?
2. Phase E — fail-CI-on-sweep is a hard cut-over. Land
   simultaneously with β-completion, or earlier as a soft gate
   (warning becomes error after a grace period)?
3. Phase D — UI changes likely need their own design pass.
   Backlog now, or co-design with β?
4. γ — keep as documented future direction, or scope it out
   entirely now? (If kept, β's `:call-context-types` field is
   structured so a future γ migration can replace it without
   API breakage.)
