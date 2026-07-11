# Audit: name-based vs id-based internal resolution

**Date:** 2026-07-11 · branch `feat/type-per-fn-slots`
**Principle:** internal mechanisms must resolve/match/dispatch by stable
ID (uuid: fn-id, slot-id, binding-id), NOT by human NAME (fn-name,
arg/slot as-name). Names are for humans (readability + docs). Graphden IDs
are deterministic `uuid-v5(namespace,name)` / `uuid-v5(fn-id,slot-name)`;
fn-NAMES are globally unique (sync-validated); **arg/slot as-names are NOT
unique across flows** — that's where name-keying is a real bug surface.

Classification: **FRAGILE** (name not stable/unique in context → real
risk) · **BENIGN** (unique fn-name convenience key, id derivable, no
collision risk) · **CORRECT-BY-ID** (already id-based — the pattern to
mirror).

---

## Why name-resolution exists at all (root cause)

Graphden IDs are *derived from names* (deterministic uuid-v5). Combined
with globally-unique fn-names, this makes name and id nearly
interchangeable at the leaf — so name-keying "just worked" and accreted.
It breaks precisely where names are NOT unique: **slot/arg as-names**
(`{:as :parsed}` at two sites = one name, two `slot-id(owner,:parsed)`).

The slot-id-keyed runtime (Phase 2/4/5) was added *later* than the
name-keyed machinery and only got **half** the surface: readers + public
boundary are id-based; **dispatch + cross-fn value-copy are still
name-based**. The type checker predates it entirely and is fully
name-based (see `ADR-slot-id-keyed-type-checker.md`).

---

## `src/graphden/executor/**` — FRAGILE (ranked)

Runtime `fa` is deliberately **hybrid**: every caller value is written
under BOTH its slot-id AND its ext-name. Readers use slot-id (good);
the debt is name-keyed dispatch/copy around HOF wrap, ref-renames,
env-bindings, and the cache key.

| # | Where | Keys on | Risk |
|---|-------|---------|------|
| **F3** | `compile_eager.clj:67-88` `fa-key-for-cache` + `renames.clj:184-230` `cache-projection-frees` | ext-name (cache key projection) | **HIGHEST — latent wrong cache-hit / stale data on the hottest path** (every `:ref`). Two distinct slots sharing an ext-name collide; name cell holds only last write → two differing calls hash equal → wrong hit. Invariant only guards *missing* names, not collisions. |
| **F1** | `compile_eager.clj:466-474`, `:534-546`; producer `renames.clj:860-911` `build-ref-renames` | arg-name→arg-name copy | ref-rename value misroute on name collision; pure name→name, no slot-id involved. |
| **F2** | `renames.clj:723-857` `hof-lambda-params`/`alpha-equiv-lambda-params`; consumed `compile_eager.clj:444,516` | arg-name + structural conv name | wrong lambda-param → wrong wrap arity → silent all-nil rows. |
| **F6** | `compile_eager.clj:263-268` `make-shape-callable`, merge `:328/:334` | lambda-param name | inherits F2; lambda value overwrites a colliding `fa` cell. One unit with F2. |
| **F4** | `bindings.clj:302-375` `collect-env-bindings` (dedup by env-name), write `compile_eager.clj:616`, read `:418-419` | slot-name (env-name) | env-binding dedup collapses two different non-root slots sharing a name; dropped entry vanishes from `fa`. Value-flow is name-only (never gets a slot-id key). |
| **F5** | `renames.clj:434-445`, `:577-591`, `own-rename-chain-map:44-56`, `compute-rename-aliases:958-987`; runtime `apply-rename-aliases` (`compile_eager.clj:606`) | as-name / slot-name | positional `{:as}` deep-walk resolves via `[fid name]`; runtime alias copy is pure name→name on every top-level invocation. |

**Each has an existing id-based analogue to mirror:**
`deep-free-ext-entries` (`renames.clj:101`), `apply-hof-translation` /
`build-hof-translation` (`renames.clj:914`, `compile_eager.clj:271`),
`effective-reader-slot-id` (`lookups.clj:286`), `seq-item-builder`
slot-id-primary (`compile_eager.clj:225`), `compile/deps.clj` fully fn-id.

**Dependency between fixes:** F2+F6 are one unit; F3's closure-capture
half depends on F2. F3's projection line alone is near-local. F1/F4/F5
each need threading (producer returns slot-id pairs + runtime copy writes
slot-id keys).

### CORRECT-BY-ID in executor (mirror these)
`deep-free-ext-entries`, `translate-named-args` (name-match only to
accept the caller's necessarily-named args, output is per-slot-id),
`effective-reader-slot-id` + `arg-builder :free`, `seq-item-builder`,
`build-hof-translation`/`apply-hof-translation`, `compile/deps.clj`,
`find-rename-slot`/`rename-for-slot`/`effective-binding` via
`slot.source-slot-id` FK.

### BENIGN in executor (do NOT churn)
`rich-type-of` keyed by unique fn-name (resolution at `bindings.clj:181,193`,
`compile_runtime.clj:326`); `resolve-impl`/`has-impl?` (base-fns
name-registry by design); `compute-fn-typed-fn-ids` matching the `:fn`
primitive name; `composition/*` sync path (`name→id` is THE
name→deterministic-id boundary — names legitimately live here);
`execute-by-name` (explicit name-lookup public API → resolves to id then
delegates).

---

## `src/graphden/{crud,storage,versioning,schema}/**`

Overwhelmingly id-based and disciplined. **One genuine bug**, one
cosmetic, a benign registry cluster.

### FRAGILE

**C1 (REAL BUG) — root branch protected/bootstrapped by the NAME string
`"main"`, not by structural root-ness.** `versioning/storage/core.clj:761`
`delete-branch!` guards with `(= "main" (:name branch))`. But:
- branch names are only `[:org-id :name]`-unique (per-org), not global
  (`schema/versioned/schema.clj:355`);
- the main branch id is minted with **`random-uuid`**
  (`core.clj:606` `ensure-main-branch!`) — no derivable stable id;
- the stable identity IS in hand: `:base-branch-id = nil` (root marker).

Consequence: a root branch NOT named "main" is unprotected; a non-root
branch named "main" is falsely protected. Same root cause at
`core.clj:632` (`wrap-with-versioning` name-special-case) and
`core.clj:600-612` (`ensure-main-branch!` name query + random id).
Fix: guard on `(nil? (:base-branch-id branch))` — LOCAL. Optionally give
the root a deterministic well-known id so bootstrap stops leaning on the
name (small plumbing). **Must fix (correctness).**

### BORDERLINE
**C2 — sequence slot detected by name.** `crud/entities/seq.clj:44`
`(= "sequence" (:name (get fns-by-id (:type-fn-id slot))))` — dispatches
on the resolved name of an id it already holds. Safe today (base-fn name
unique + deterministic id). Tidy: compare `(:type-fn-id slot)` against the
resolved `sequence` type-fn-id. LOCAL, cosmetic.

### BENIGN (name-keyed rich-type/tag registry round-trips; decisions id-based)
`crud/type_check.clj:356`, `crud/fn_execution/persist.clj:207`
`declared-effects-of`, `crud/entities.clj:946` `chain-has-process-effect?`,
`crud/entities.clj:318` `unregister-rich-type!`, `crud/secret_shape.clj:29`
`fn-ids-with-tag` (→ immediately to ids; gating compares `parent-ids` by
id). Keying the registry by fn-id is plumbing for nil value — leave.

### CORRECT-BY-ID / boundary (no change)
Versioning resolves by `(entity-name, entity-id, branch-id)` throughout
(`resolution.clj`, `merge.clj` matches by fn-id, `:fn-name` display-only);
`entities/seq.clj` `[fn-id slot-id]`; renamed-view slots get deterministic
`records/slot-id`; `mi-collision-check` keys by-name *on purpose* (it
ENFORCES arg-name uniqueness). Boundary name→id: `resolve-branch-ref`,
`resolve-fn-id`/`resolve-fn`, `resolve-type-fn-id`, seq JSON `ref-name`.

## `src/graphden/{packages,system,layout,services,tenancy,auth}/**`

Runtime spine (executor-dispatch, reconciler, routers) is clean id.
Debt clusters in authz, structural type constraints, slot-owner tie-break.

### FRAGILE
- **P1 (security) — authz subject + session tokens keyed by USERNAME,
  not user-id.** `tenancy/users.clj:213` (token `:user username`),
  `:184` (grant cascade by name), `tenancy/authz.clj:39,71` +
  `grant.clj:54` (`can?`/`grant-allows?` match username). The `:user`
  row has a stable uuid but every authz/session linkage uses the mutable
  username → a rename detaches grants+sessions; delete+recreate can carry
  privileges over. Fix: `:token.user-id` + `:grant.subject-id`, resolve
  username→id at login. Plumbing (schema col + write/read sites).
- **P2 — structural type-constraint members stored BY NAME.**
  `parse.clj` union/variant/map/tuple/fn-type stash member types as
  keyword names in `:constraint`; every *other* type ref is a fn-id.
  `export.clj:589 constraint-type-names` special-cases this (the dep
  scanner is blind to them). Rename → dangling name. Fix: resolve members
  to fn-ids at parse time, names only at EDN export. Plumbing.
- **P3 — `resolve-slot-owner` ambiguity falls through to inheritance
  owner SILENTLY.** `slot_resolution.clj:360-438`: on a true
  same-name/same-type collision the tie-break defaults to `inh-hit` →
  a `binding-id` against a real-but-wrong `slot-id` (silent mis-bind).
  Author writes names (boundary), so can't eliminate — but should
  **hard-fail on unresolved ambiguity** instead of guessing.
- **P4 (low, display-only) — layout free-arg migration by arg-name.**
  `layout/graph.clj:229 migration-via-free-arg-name` fallback (slot-id
  paths tried first). Affects which editor edge is drawn, not runtime.

### BENIGN / documented-dependency
- **anon `[parent-name arg-name]` use-site hash** (`parse.clj:798`): SOUND
  today — parent-name globally unique, arg-name unique in parent →
  id-equivalent. BUT load-bearing on global-fn-name-uniqueness (omits
  namespace, dedups by `:name`). **Add an assertion/comment**, don't
  rewrite.
- `register-type-aliases!` name-keyed (feeds P2); namespace grants by
  dot-path (path IS the ns identity — `ids/fn-id` derives from the path
  string, so a ns rename already changes every fn-id → paths are
  effectively immutable ids).

### CORRECT-BY-ID
services reconciler (all `service-id`/`fn-id`), service seeding
(deterministic service-id, stores fn-id), branch router (`branch-id` +
pre-resolved `handler-fn-id`), app router/deploy (`:org.handler-fn-id`),
layout core (fn-id/slot-id/arg-id), export/parse round-trip, org identity
(slug).

---

## SYNTHESIS — prioritized fix plan

The principle surfaced a **real, distributed debt**, but NONE of it is a
firefight — the hot execution path is clean; the FRAGILE items are latent
(structurally-possible, not currently-triggered). Root cause: deterministic
name-derived ids + globally-unique fn-names made name-keying "just work",
so it accreted; it breaks only on non-unique names (slot/arg as-names) and
mutable names (username, branch name).

**Tier 1 — genuine correctness bugs, LOCAL, safe, do first:**
- **C1** root branch by `:base-branch-id nil` (not name "main").
- **P3** hard-fail `resolve-slot-owner` on unresolved ambiguity (surfaces
  any existing latent mis-bind via `bb test` golden bootstrap).

**Tier 2 — finish the slot-id runtime migration (executor F1–F6).** Hot
path, high-value (retires the #104 wrong-cache-hit / mis-route class),
higher risk. Order: F3 (cache key by slot-id) → F1 (ref-rename copy) →
F2+F6 (HOF lambda by slot-id) → F4 (env-binding) → F5 (positional
rename). Each mirrors an existing id-based analogue; each gated by full
`bb test` + live re-verify. Keep the name half as compat until the
slot-id half is proven, then drop.

**Tier 3 — plumbing robustness:**
- **P1** authz/session by user-id (security).
- **P2** type-constraint members by fn-id.

**Tier 4 — cosmetic / invariant-documentation:**
- **C2** sequence slot by id; **P4** layout note; anon-hash assertion +
  comment pinning the global-fn-name-uniqueness dependency.

**Type-checker (option-3):** its name-keying is mostly BENIGN (fn-name
unique). The FRAGILE core is the α' as-name narrowing — which we KEEP
(soundness). option-3 = tidy/consolidate/test/document the α' machinery,
NOT re-key it (per `ADR-slot-id-keyed-type-checker.md`, re-keying is a
lateral move). Fold into Tier 4 as debt-reduction where it overlaps the
slot-chain helpers Tier 2 consolidates.

Every fix: own commit, sweep-green + full `bb test`, skills + linters,
new test pinning the id-based behaviour.

## `src/graphden/types/**`

Fully name-based; see `ADR-slot-id-keyed-type-checker.md`. Narrowing keys
on arg as-name (`*caller-narrowings*`, `build-caller-narrowings`) — the
FRAGILE core. `rich-type-of` fn-name keying is BENIGN (unique). The
inherited-free-arg slot identity is not locally derivable (needs the
`source-slot-id` resolution subsystem) — the one genuinely hard case.

---

## Synthesis / plan

_pending full audit — see next section once all subsystems reported._
