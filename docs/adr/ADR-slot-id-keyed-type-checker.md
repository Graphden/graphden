# ADR: slot-id-keyed type checker (Variant B — fix the foundation)

**Status:** ⛔ **EVALUATED + REJECTED** on `feat/type-per-fn-slots` after
two deep code investigations. B does not achieve its goal (reduce the
accidental complexity while keeping soundness). Evidence in § Conclusion.
This closes the `TYPE_SYSTEM_DECISIONS.md § β` "if revisiting" note: the
sketch there is infeasible-for-payoff, not merely un-done.
**Date:** 2026-07-11

## Conclusion (read first) — B does not pay off

Two independent investigations of the actual code (registry/narrowing
consumer side + parser/runtime slot-id infrastructure) converge:

**1. The general slot-id migration is infeasible for the payoff.** Making
the registry `{name → {:type T :slot-id S}}` works for *rename AS-names*
(a rename's slot is owned by the renaming fn-def → `slot-id(fd-id,
as-name)` is locally derivable). But it is NOT derivable for
**inherited / ref-lifted free args**: their real slot identity is an
*ancestor's* slot resolved through a `source-slot-id` chain computed by a
whole separate subsystem (`slot_resolution.clj` + `compile/renames.clj` +
`compile/lookups.clj`) that the type-check sweep never runs.
`collect-free-args` merges parent args **by name** and discards which
ancestor owns each slot. Re-deriving it inside the checker = re-implement
that resolution subsystem — the ADR's "mid-week of work" is a large
underestimate, for **zero correctness gain** (sweep is already at 0).

**2. The feasible subset (slot-id-key the narrowing only) is a LATERAL
move, not a reduction.** The conflation is confined to the AS-name-keyed
`*caller-narrowings*` path; re-keying it on a derived slot-id is sound and
possible — but it delivers the *same* correctness α' already delivers,
while:
- it **cannot remove the per-use-site anon-split hack** (the main +30%
  complexity). Anon fn-DEFS are split per use-site precisely so Pass 3 can
  bind a *per-use-site* narrowing. If you dedup them, the single anon has
  ONE owner-scoped slot-id per arg — **shared across both use-sites** — so
  slot-id keying conflates them exactly as name-keying does. Per-use-site
  distinctness is what the split provides; slot-id keying only re-expresses
  the identity the split already created. Removing the split needs a
  different check model (check a deduped anon once *per calling context*),
  which is MORE complex and risks check-time blow-up.
- it **adds** surface: a parallel `:arg-slot-ids` side-map, owner-fn-id
  threaded into `binding-info-entry` / `bindings-info-for-rule` /
  `collect-free-args`, and bridging two disjoint "root" notions
  (runtime root = root SLOT via `source-slot-id`; checker root = root
  base-fn NAME via `:primary-parent`). Net **name-keying + slot-id-keying
  both** — more code, same result.

**3. The accidental complexity is INHERENT to soundness on this data
model.** The anon-split, Pass 2/3, and the `:type T` assertions all exist
to *prove* per-flow types with zero false positives. No key change removes
the obligation; only dropping the obligation does. The one variant that
genuinely deletes the complexity is **Variant A (drop soundness → gradual
best-effort checker)** — it removes Pass 2/3, the anon-split, and the
`:type T` ritual because an un-narrowable slot simply degrades to `:any`
instead of forcing a proof.

**This vindicates the project's original α' decision** (β/γ rejected,
α' chosen). B was the "keep soundness, fix foundation" hope; the
foundation cannot be cleanly fixed *while keeping soundness*, and the
data-model change that would help either breaks DRY (per-fn slot
instances) or re-implements a resolution subsystem for no correctness
gain (general slot-id registry).

**Recommendation:** do NOT implement B. The real fork is a product-values
choice — **A (drop soundness, real −40% simplification)** vs **keep α'
as-is (sound, near-optimal for this model)**. That is a decision about
what the type system *guarantees to users*, so it is the user's to make,
not a technical-correctness call.

---

## (Original plan below — retained for the record; superseded by the
## Conclusion above.)

**Supersedes the "if revisiting" note in** `TYPE_SYSTEM_DECISIONS.md § β`.

## Problem (recap)

The type checker's caller-narrowing (`types/check/narrowing.clj`,
Phase α' / Phase 9 in TYPES.md) keys on the arg **NAME**. But graphden
slot identities are distinct from slot names: two `{:as :parsed}` renames
at different sites are the SAME name, DIFFERENT slot-ids. Name-keyed
propagation therefore conflates structurally-different flows — which is
exactly why β (aggressive downward propagation) was reverted (sweep
10 → 28-31 false positives).

α' works around this by **manufacturing unique names**: the parser's
per-use-site anon-naming mixes the use-site host into the anon hash so
identical-shape anons get distinct synthetic names (registry +30%,
532 → 695). Plus 7 hand-written `:type T` author-assertions patch the
gaps the name-keyed narrowing still can't trace.

This is the ~35-40% accidental complexity: it exists ONLY to reconcile
"global slot identity" with "the checker keys on names".

## Why THIS variant (not literal per-fn slot instances)

The review framed "Variant B" as "per-fn slot instances". On grounding,
that is the WRONG realization:

- Making each fn's slot use its own identity would break the whole point
  of shared slot identities (DRY: one-shot creation, immutable, shared
  across fns via `fn-slot`). It needs a schema/storage migration and
  touches the runtime, editor, and every consumer of slot identity.
- It buys nothing over the cheaper fix, because the runtime ALREADY keys
  on slot-id (the "slot-id-keyed runtime refactor", Phase 2/4/5 in
  `compile_runtime.clj`) and it works. The slots are already globally
  identified WITH stable slot-ids; the parser already assigns them.

The architecturally-correct Variant B is therefore: **make the TYPE
CHECKER slot-id-aware, mirroring what the runtime already does.** Keep
slots as shared global identities; thread their slot-id into the type
registry and match narrowing on (root) slot-id. This is the ADR's own
"if revisiting" sketch. It:

- makes β-style propagation **sound** (match on identity, not name),
- lets us **delete** the per-use-site anon-naming hack (registry −30%),
- lets us **delete** the `:type T` assertions that only existed to patch
  name-conflation,
- keeps soundness (Phase E sweep-at-zero stays armed),
- changes no schema, no storage, no runtime — it's contained to `types/`
  and the registry entry that feeds it.

## Change surface (as understood; refined by investigation)

1. **Registry entry** (`executor/registry/core.clj`,
   `record-rich-types!` → `arg-spec->rich-type`): add a per-fn
   `{arg-name → root-slot-id}` map to the rich-type entry. ADDITIVE —
   `:args` stays `{name → type}` so the ~10 existing `rich-type-of`
   consumers don't break; a new key (`:arg-slot-ids`) carries identity.
   - **OPEN (agent-1):** is the slot-id reachable at
     `record-rich-types!` time? The fn-def's arg-spec must carry it, or
     we thread it from the parser's pre-pass output.

2. **Narrowing** (`types/check/narrowing.clj`): `build-caller-narrowings`
   + `propagate-narrowing-to-rename-hosts` key the narrowing map and the
   BFS match on **root-slot-id** (via the `source-slot-id` chain), not
   `as-name`. `check.clj`'s readers of `*caller-narrowings*`
   (`bindings-info-for-rule`, `collect-free-args`, `effective-ref-return`)
   look up by slot-id.
   - **OPEN (agent-2):** confirm the `source-slot-id` root-chain helper
     to reuse (`root-source-slot-id` seen in `fn_execution/lookup.clj`).

3. **Remove the per-use-site anon-naming hack** (parser pre-pass anon
   hashing): once identity comes from real slot-ids, identical-shape
   anons can dedup again. Registry shrinks back toward ~532.
   - Guard: verify dedup doesn't reintroduce the conflict α' fixed —
     with slot-id-keyed narrowing it must not, since identity is now
     carried explicitly.

4. **Remove compensating `:type T` assertions**: the sites tagged
   "per-use-site anon split" in TYPES.md § Author `:type T` assertions
   (`:_seq-remove-apply-do-delete`, `:_inline-bind-target-fn-row`,
   `:_delete-secret-fn-row`, `:_update-id-uuid`) should no longer be
   needed. Remove each, confirm sweep stays 0. Sites tagged with a real
   unrecognised-guard reason (`:str-starts-with?` shims, `:and`/`:or`
   guards) STAY — those are #170-v2 territory, out of scope, and remain
   explicit-over-implicit by design.

## Verification gates (every stage)

- `bb check` (clj-kondo/splint/cljstyle) clean on touched files.
- The **type sweep stays at zero** (`allowed-type-check-failures #{}`,
  Phase E gate armed) — the hard invariant. Any regression = stop.
- Full `bb test` green (the sweep runs inside it).
- `graphden-packages-quality` / `graphden-code-quality` skills on touched
  code.
- Registry-size assertion: anon count drops (proves the hack is gone) and
  no new false-positive sweep failures (proves slot-id keying is sound).
- New tests: a case that name-keying conflated but slot-id-keying
  separates (the create/update/delete `:parsed` flow) — pin the win.

## Grounding — infrastructure map (agent 2, confirmed by code)

Reusable, all pure or lookups-based:
- `ids/slot-id [owner-fn-id name]` — deterministic slot-id (uuid-v5),
  owner-scoped; same fn the parser/runtime use. `ids/anonymous-fn-id`,
  `ids/shape-hash`.
- `root-source-slot-id [slot-id slots-by-id]`
  (`crud/fn_execution/lookup.clj:180`) — walks `:source-slot-id` to the
  ROOT slot; "two slots are equivalent free-arg surfaces iff they share a
  root" (#51). `chain-source-slot-ids` (`compile/renames.clj:17`).
- `build-lookups` (`compile/lookups.clj:14`) — `:slot-map`,
  `:slot-by-fn-name {[fn-id name] → slot-row}`, `:slot-by-fn-source-slot`,
  `:fn-slots-by-fn`. A ready per-fn slot index.
- **`deep-free-ext-entries [fn-id lookups]`** (`compile/renames.clj:101`)
  → `[{:ext-name K :slot-id UUID} …]`, deduped by slot-id, rename-aware
  (`effective-reader-slot-id`). This is the per-fn name↔slot-id surface
  the checker wants — it already exists at the compile layer.

Two "root" notions today do NOT meet: runtime root = root SLOT (via
`source-slot-id`); checker root = root base-fn NAME (via `:primary-parent`,
`registry.core/root-base-fn-name`). Bridging them is the work.

## ⚠ Critical risk surfaced by investigation (blocks the "−30%" claim)

Anon fn-DEFS are split per use-site (`parse.clj:798-821`): identical-shape
anons at two sites get distinct fn-ids → distinct slot-ids. Slots are
owner-fn-scoped (`ids/slot-id [owner-fn-id name]`). Therefore:

> If S3 removes the anon-split hack and identical anons **dedup to one
> fn-def**, that one anon has ONE slot-id per arg — SHARED across both
> use-sites. Keying narrowing on slot-id would then conflate the two
> flows **exactly as name-keying does**. Slot-id keying does NOT, by
> itself, make the anon-split hack removable.

Implication: the per-use-site distinctness may be load-bearing for
Pass-3's per-callee narrowing binding, independent of name-vs-slot-id.
The real, achievable win of B may be **soundness of cross-utility
propagation** (no leak through SHARED `:_rejection-response`-style
utilities — where slot-id matching genuinely beats name matching), NOT
the −30% registry reduction. **Reassess the value proposition once
agent-1 (narrowing/Pass-3 consumer mechanics) reports.** If B cannot
reduce the accidental complexity, say so with evidence rather than
rewrite for a lateral move.

## Staging

- **S0** — investigation (agents) + this plan. ← current
- **S1** — thread root-slot-id into the registry entry (additive), with a
  test that the map is populated correctly. No behaviour change yet.
- **S2** — switch narrowing to match on slot-id; keep the anon-hack for
  now. Sweep must stay 0.
- **S3** — remove the per-use-site anon-naming hack; sweep stays 0,
  registry shrinks.
- **S4** — remove the compensating `:type T` assertions one by one; sweep
  stays 0 after each.
- **S5** — cleanliness pass (refactor duplicated narrowing/slot-chain
  logic), docs update (TYPES.md Phase 9 rewrite, TYPE_SYSTEM_DECISIONS.md
  β → RESOLVED, tutorial lesson if any).

Each stage is its own commit with the sweep green.
