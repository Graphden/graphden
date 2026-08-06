## Type system — architecture decisions record

Not a roadmap. The type system is at **sweep-at-zero** across
all 2236 fn-defs (`allowed-type-check-failures` is `#{}`, Phase
E hard-gate armed both directions). This file records the
architectural decisions that got us there — especially the
ones we **rejected** — so the next time someone reaches for
"caller-context propagation should be easy" or "row polymorphism
would clean this up", the analysis that closed those paths is
one click away.

For how the type system works in practice, see
[TYPES.md](TYPES.md).

## Current state — 2026-06-16

- **Sweep at zero** across all fn-defs.
- Allowlist (`allowed-type-check-failures` in
  `src/graphden/types/check.clj`) is empty `#{}`.
- Phase E hard-gate stays armed both directions
  (`:types/sweep-regression` on new failures,
  `:types/sweep-stale-allowlist` on dead entries).
- Original 10 `:_X-apply-*` family failures closed via Phase α'
  (caller-context propagation to rename-host leaves +
  per-use-site anon naming).
- The 11 follow-up nullability gaps closed via author
  `:type T` assertions on the binding form (see
  [TYPES.md § Control-flow narrowing](TYPES.md)).
- Phase #170 v1 (direct-predicate `:if`/`:cond` narrowing for
  `:some?`/`:nil?` roots) landed.

## The root architectural tension

> Slots in graphden are **global identities** (one-shot
> creation, immutable). Bindings overlay them. The same slot-id
> can be used by multiple fn-defs in **structurally different
> flows**.

When a shared slot — e.g. `:get`'s `:coll`, or a renamed-view
slot like `:parsed` — is used in CREATE / UPDATE / DELETE
flows with different record shapes, the type-checker has TWO
bad options:

- **Type the slot globally** → bleeds across flows. Tightening
  one breaks siblings.
- **Leave the slot `:any`** → loses narrowing in every flow.

Neither is sound + complete. The honest answer: types must be
**call-context-local**, not slot-local.

## Three architectural answers we considered

| # | Approach | Cost | Cleanliness | Status |
|---|----------|------|-------------|--------|
| α | Per-flow rename (mechanical) — `{:as :create-parsed}` / `{:as :update-parsed}` / `{:as :delete-parsed}` | low | low | superseded by α' |
| α' | Per-use-site anon naming + rename-leaf narrowing + `effective-ref-return` merge-flip | mid | mid-high | **LANDED — closed the original 10** |
| β | Caller-context propagation (downward type-flow second pass) | mid | high in theory | **ATTEMPTED + REVERTED** — see below |
| γ | True row polymorphism (HM-style row variables) | high | highest | **REJECTED** — see below |

## β attempt (2026-06-16) — REVERTED

A first prototype of β was implemented and reverted in the same
session. The core insight that killed it:

> **Per-name propagation through the ref-graph is unsound
> because graphden's slot identities are distinct from slot
> NAMES.** Two `{:as :parsed}` renames at different inline-anon
> sites create two DIFFERENT slot-ids — they share the AS-NAME
> but ARE NOT the same slot.

Multiple flows independently use the name `:parsed` (create /
update / delete / seq-append / record-type / list-type /
types-compatible — 9 distinct binders, 7 distinct shapes), and
they re-use shared utility fn-defs (`:_rejection-response`,
`:html-error-response`, `:_request-body`, …). A BFS over the
ref-graph keyed on `:parsed` correctly narrowed the create-flow
LEAVES but also leaked the narrowing to unrelated fn-defs
sharing `:parsed` semantics elsewhere — sweep went 10 → 28-31
with new false-positive failures on update/seq-flow descendants.

Three increasing restrictions were tried (parent-slots filter,
free-arg-of-child filter, walk-through-rebind filter). Each cut
some false propagations but none captured the root constraint:
**two flows can share a free-arg name without sharing its slot
identity**.

### Lesson — why per-name propagation is fundamentally wrong

The runtime semantics of a free-arg are tied to its **slot
identity** (the slot-id introduced by the original rename in
the parser pre-pass). The type-checker today operates one
level above this: it sees the AS-NAME and treats it as the
free arg's identity. For Pass-1's local check this is fine —
the slot type doesn't escape the fn-def's local computation.
For Pass-2 propagation across the ref-graph it ISN'T fine —
the AS-NAME's TYPE flows through distinct slot-ids that may
need different types.

**If revisiting**: thread slot-id through the parser's pre-pass
output and the registry's `:args` map (currently `{name →
type}` → would become `{name → {:type T :slot-id S}}`), then
have the BFS walker match on slot-id, not name. Mid-week of
work. The α' path was preferred because it delivers the same
correctness with smaller surface change.

## Phase #170 v2 — REJECTED (composed-guard narrowing)

Phase #170 v1 (direct `:some?`/`:nil?` guards on `:if`/`:cond`
test refs) landed. v2 was to extend recognition to composed
guards (`:_X-blank?` wrapping `:str-blank? + :get`, `:and`/`:or`
decomposition, narrowing through `:get` of record fields).

Driving question: does v2 make the system architecturally
better OR the user's life easier?

- **Architecture (principle #3 — explicit over implicit)**:
  AGAINST. Each remaining `:type T` site is paired with a
  one-line comment (`;; validation guard upstream rejects nil`)
  pointing at the invariant. That's the runtime contract
  written down at the use-site, where someone reading the code
  needs it. v2 would dissolve that into type-checker inference
  rules — to understand "why is `:branch-name` non-null here?",
  a reader would have to track which composed-guard shapes the
  inferer recognises and find the upstream `:cond` clauses.
- **Author UX**: NEUTRAL. Adding a `:type T` assertion after a
  sweep failure is a 30-second one-time edit per new flow. The
  Phase E gate makes the failure loud and unmissable.
- **Reader UX**: AGAINST. Explicit assertion + comment beats
  implicit inference.
- **Cost**: ~2 weeks, plus risk of new under-convergence cases
  surfacing downstream.

v1 was worth doing because `:if :test (:some? :_x) :then :_y`
is trivially readable — the guard, target, and branch are
right there in the parent shape. Composed guards
(`:str-blank?`-shims, `:and`/`:or` decompositions,
`:get`-through-record narrowing) don't have that property.

**Net**: no architectural win, no UX win, mid-size
implementation cost, real risk of regressions. Don't do it.

## Phase γ — REJECTED (row polymorphism)

Slot types parameterised over **row variables** — a record
type `[:record {fields} 'rho]` where `'rho` is unbound row
content that can be unified per call-site. The textbook answer
to "per-use-site narrowing without losing the structural
abstraction."

Why not pursued:

- **Correctness**: α' already delivers it. γ does not fix any
  unsoundness or false positive in the type-checker.
- **Author UX**: zero visible change. Users don't write row
  variables; the type-checker would derive them. Same per-flow
  experience as today.
- **Reader UX**: WORSE. `rich-type-of`'s output today shows
  concrete record shapes. Row-polymorphic form
  (`(record-with :coll T + 'ρ)`) is strictly more abstract —
  one more concept to hold in your head when inspecting a fn's
  type.
- **Registry size**: α' added ~30% anon-fn-def rows
  (532 → 695). Full type-check sweep finishes in seconds; this
  is not on any hot path. No measured pain.
- **Feature unlock**: none. Open-record polymorphism would
  enable "extend a record at the call-site" patterns — but no
  current or queued feature requires it.
- **Cost**: ~3 weeks + an unknown settle-in period. Every
  record-consuming rule (`:get`, `:assoc`, `:select-keys`,
  `:merge`, `:zipmap`, …) needs updating; `subtype?` / `unify`
  would need an "open vs closed" distinction throughout.

**Revisit only if** either (a) registry size becomes a real
performance problem, OR (b) a queued feature genuinely needs
open-record polymorphism.

## Outcome — 2026-06-16 closure

| Phase | Status |
|-------|--------|
| **A** — typed `_X-parsed` returns + typevar binding + narrowing-assertion | LANDED |
| **β** — caller-context propagation | ATTEMPTED + REVERTED — per-name BFS conflated flows; superseded by α' |
| **α'** — per-use-site anon naming + rename-leaf narrowing + `effective-ref-return` merge-flip | LANDED — closed the original 10 |
| **E** — hard-gate sweep + allowlist + tests | LANDED — bidirectional gate |
| **C** — `:type` rename localisation | not needed — α' supersedes the use case |
| **D** — editor surface | LANDED implicitly — α' writes narrowed types into the canonical `:return` field; editor reads via `/api/types` without UI change |
| **#170 v1** — direct-predicate `:if`/`:cond` narrowing | LANDED — see [TYPES.md § Control-flow narrowing](TYPES.md) |
| **#170 v2** — composed-guard narrowing | REJECTED — see above |
| **γ** — row polymorphism | REJECTED — see above |

Final state: sweep at zero, allowlist empty, Phase E gate
armed. No known type-system bugs in production.
