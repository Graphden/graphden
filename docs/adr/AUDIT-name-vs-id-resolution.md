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

### ⚠ RECONCILIATION with `RUNTIME_SLOT_ID_REFACTOR.md` (#104) — READ

The executor audit was run WITHOUT the #104 design record. Cross-checking
it flips most of F1–F6 from "debt" to **deliberate final design**:

- The **hybrid `fa` (slot-id + name) is the FINAL architecture, not a
  transitional state** (that doc §3/§4/§7). slot-id keys distinguish
  *structural* ambiguity at the boundary (#104); **name keys deliberately
  cover DYNAMIC writes** (env-bindings, HOF lambda-args, cross-fn
  cascades) where "a value flows under one name, set per call" — no
  structural ambiguity.
- The full slot-id-only flip was **attempted twice and dropped**:
  env-builder slot-id writes caused **stack overflows** (thunk re-entry
  via `force-value`, `:list-fn-versions` chains); the cross-fn rename
  slot-id cascade needs those writes + readers dropping name-fallback.
  Marginal benefit (2 `:on-throw :const` sites) didn't justify the risk.

So the reclassification:

- **F1 (`build-ref-renames`)** — the doc calls this "the load-bearing
  cross-fn cascade path." Flipping it = the rejected env-builder path.
  **NOT debt. Do not touch.**
- **F2/F6 (HOF lambda by name)** — Phase 5 already shipped the
  *conservative* HOF wrap-time slot-id translation
  (`build/apply-hof-translation`). Going further hits the same rejected
  ground. **Leave.**
- **F4 (env-binding by name)** — the doc EXPLICITLY keeps env-builder
  name-only; slot-id writes = the stack-overflow class. A partial sweep
  (commit `c49f577c`) was reverted after `bb test-e2e` reproduced a
  failure. **Do not touch** (residual leak managed by documented
  defensive pins).
- **F5 (`apply-rename-aliases` positional)** — "still use name-fallback,"
  final. **Leave.**
- **F3 (cache projection by slot-id)** — the ONE genuine gap: the readers
  are slot-id-aware but the cache KEY still projected by NAME, so the
  cache could serve a wrong result the readers would have routed
  correctly. The `RUNTIME_SLOT_ID_REFACTOR.md` §4 target arch itself
  lists `cache-projection → set of slot-uuid`; it was unfinished.
  **FIXED — additively** (slot-ids ADDED to the name projection, superset
  invariant preserved, no fa-write/read change → the stack-overflow class
  is structurally impossible). Test:
  `cache-projection-carries-collision-slot-ids-test`.

**Net executor outcome: F3 fixed; F1/F2/F4/F5 are the deliberate,
empirically-validated hybrid, vindicated by the design record — like
Variant B, "half-built" is actually "the other half was tried and
rejected for real runtime reasons."**

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

`crud/secrets.clj` `vault-get-fn-id` — name query, audited 2026-07-24
and kept DELIBERATELY: the base-fn filter (`:return-type-fn-id` set) makes
the pick deterministic (base-fn bare names stay globally unique), and the
per-request storage query stays correct on editor DBs whose row ids don't
match the package uuid-v5 derivation. The registry alternative
(`(:fn-id (rich-type-of :vault-get))`) was REJECTED: the tenant per-org
slice prefers an org's own same-named entry, so a tenant composing a fn
named `vault-get` would shadow the platform resolver — the storage query
has no such precedence hazard.

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
  INVESTIGATED — **BENIGN (deliberate).** union/variant/map/tuple/fn-type
  stash member types as keyword names in `:constraint`, resolved via the
  **name-keyed type-alias registry** (`register-type-aliases!` /
  `resolve-alias`) — which is how the type-alias system works BY DESIGN
  (TYPES.md § Type Aliases: types are a name-keyed namespace). So this is
  the intended reference mechanism, not debt:
  - the package version-update rewrite (`rewrite-refs-to-version!`) only
    rewrites `:ref-fn-id` — it doesn't touch parent-ids / type-override /
    constraints for ANY type ref, and name-in-constraint auto-resolves to
    the current version's type-row BY NAME (stable across versions), so
    it's if anything MORE robust than an id-ref there;
  - no type-rename feature (a rename changes the deterministic fn-id too,
    same as P1);
  - export's dep-scanner blindness is already handled
    (`constraint-type-names`).
  Resolving members to fn-ids would FIGHT the name-keyed alias design for
  consistency-only gain with no bug fixed — same call as the rejected
  slot-id type-checker migration. Leave.
- **P3 — `resolve-slot-owner` ambiguity → inheritance owner.**
  `slot_resolution.clj:360-438`. INVESTIGATED — **NOT a bug.** A
  parse-time hard-fail was tried and **reverted**: it false-positives on
  legitimate shared args. Real case: `:_er-list-total-count` binds
  `:coll`; inheritance resolves to `:count.:coll` (type includes `:text`),
  the ref tree to `:get.:coll` (subtype, no `:text`) — the SAME logical
  shared arg, value propagates to both via the ext-name. Inheritance-wins
  is a sound DEFAULT, backstopped: a typed binding value disambiguates
  when present; a free arg that genuinely can't satisfy two incompatible
  slots surfaces as a **type-check failure** (the sweep is the backstop),
  not a silent mis-bind. Fix applied: **document the rationale in the
  code** (the silent-ness the audit flagged is now explained, not
  removed). No behaviour change.
- **P4 (low, display-only) — layout free-arg migration by arg-name.**
  `layout/graph.clj migration-via-free-arg-name` fallback (slot-id
  paths tried first). Affects which editor edge is drawn, not runtime.
  Arg-name↔arg-name (no id in hand), so it's a legitimate boundary
  fallback — the CI-guard below correctly does NOT flag it. Leave.
- **P4b ✅ FIXED — layout synth-slot detection by resolved name.**
  `layout/graph.clj` identified the loader's synthetic `value`/`items`
  slot by resolving `(:slot-id arg)` back to its name and comparing.
  We hold both the owner (`display-fn-id`) and the slot-id, so the synth
  id is deterministic — `(ids/slot-id display-fn-id slot-name)`, exactly
  how `records/parse.clj` seeds it. Now compares that id (also stricter:
  no longer hides a same-named non-synthetic slot). This occurrence was
  NOT individually catalogued in the manual pass — the **CI-guard found
  it** (it compares against a symbol, not a string literal, so grep
  missed it). Commit `d1d1ff64`.

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

**Tier 1 — genuine correctness bugs, LOCAL, safe:**

- **C1** ✅ DONE — root branch by `:base-branch-id nil` (not name "main").
- **P3** ✅ CLOSED — investigated, NOT a bug (hard-fail reverted, false-
  positived on shared args); documented the sound-default rationale.
  `bb test` (golden bootstrap) confirmed the hard-fail was over-eager.

**Tier 2 — executor. RESCOPED to F3 only** (see § RECONCILIATION). The
hybrid `fa` is the final design per `RUNTIME_SLOT_ID_REFACTOR.md`;
F1/F2/F4/F5 are deliberate and their slot-id flip was tried + rejected
(stack overflows). Only **F3 (cache-projection by slot-id)** was a real
unfinished gap — **DONE** additively + tested. No further executor work:
pursuing F1/F2/F4/F5 would repeat failed multi-day attempts for marginal
benefit.

**Tier 3 — plumbing robustness:**

- **P1** authz/session by user-id (security). CONFIRMED a real fix (unlike
  F1–F5, no rejection-record; `username` is a mutable field used as
  identity). Latent today (no user-rename feature + `delete-user!`
  cascades tokens/grants by name), but the id is the correct identity.
  Change surface (implement as ONE unit, full `bb test` + tenancy/auth
  suites):
  1. schema: `token.user-id` + `grant.subject-id` (ref → `:user`,
     nullable for migration).
  2. `login!` (`users.clj:210`): also store `:user-id (:id user)` on the
     token (the `user` row is already fetched).
  3. token-provider / `*current-principal*`: carry `:user-id`.
  4. grant creation (`grant.clj:99`): resolve subject username → user-id,
     store `:subject-id`.
  5. `grant-allows?`/`can?` (`grant.clj:50,158`) + `authz` (`writable?`/
     `executable?`): match on `:user-id`/`:subject-id`, with a
     name-fallback for un-backfilled rows during migration.
  6. `delete-user!` (`users.clj:166`): cascade by user-id (already held).
  7. migration: backfill `user-id`/`subject-id` from `username` on
     existing rows.
  Boundary stays name-authored ("alice may write ns X") — only the
  STORED + ENFORCED subject flips to the resolved id.

  **✅ IMPLEMENTED + fully verified (2026-07-11) — full `bb test` green
  (1897 tests, 0 failures).** All 10 steps below landed. The one design
  refinement made during implementation: the `:subject-id`/`:user-id`
  columns are `:text` carrying the id's STRING form (`(str id)`), not
  `:uuid` — so the same column format holds prod uuids and any
  test-supplied id, and matching is uniform string equality. Human-facing
  surfaces (login form, grant authoring) stay username-keyed; only the
  stored + enforced subject flipped to the id.**Plan (all done):**

  The irreducible crux: grant MATCHING wants user-id (stable), but the
  personal-namespace (`users.<username>`, wired in prod via
  `addon.clj:208 with-personal-namespaces`) wants USERNAME. So the
  identity must thread as a PAIR `{:id user-id :name username}` through
  the `GrantStore` subsystem — stores match stored grants by `:id`,
  `PersonalNamespaceGrantStore` builds the path from `:name`.

  1. ✅ Schema: `token.user-id` + `grant.subject-id` (`:uuid`, nullable,
     indexed) — DONE in `token_schema.clj` / `grant_schema.clj`.
  2. `login!` (`users.clj:210`): store `:user-id (:id user)` on the token.
  3. principal (`auth.clj:83` `{:user … :org …}`): add
     `:user-id (:user-id row)`; the token-provider (`users.clj` §token
     read) selects it.
  4. `grant.clj` — thread the pair (the ~6 fns + 4 stores):
     - `grant-allows?` match `(= (:subject-id grant) (:id subj))`.
     - `grants-for [store subj]` — subj = `{:id :name}`;
       `StaticGrantStore`/`MemoGrantStore` key by `:id`;
       `PersonalNamespaceGrantStore` uses `(:name subj)` for the path
       (grant it emits carries `:subject-id (:id subj)`).
     - `can?`/`has-capability?`/`can-mutate?`/`workspace` take subj.
     - `authorized? [store principal …]` builds
       `subj = {:id (:user-id principal) :name (:user principal)}`;
       deny when no `:user-id`.
  5. `grant_schema.clj` `StorageBackedGrantStore/grants-for`: query
     `:grant {:subject-id (:id subj)}`; returned grant maps carry
     `:subject-id`.
  6. `authz.clj` `writable?`/`executable?` + `addon.clj:327/339`
     (`authorized?`/`workspace`): pass the pair from the principal.
  7. package grant-create (`tenancy-admin/grants/impls.clj:35`): resolve
     the authored username → user-id, store `:subject-id` (keep
     `:subject` for display/personal-ns).
  8. `delete-user!` (`users.clj:184`): cascade `:token {:user-id …}` +
     `:grant {:subject-id …}` (user-id already held).
  9. Migration: backfill `token.user-id` / `grant.subject-id` from the
     name columns; enforcement keeps a name-fallback until backfilled.
  10. Tests: extend the tenancy/authz suites — a grant enforced across a
     username change still authorizes (the pin the whole change earns).
- **P2** type-constraint members by fn-id — investigate deliberateness
  first (the alias registry is name-keyed by design; may be BENIGN like
  ns-path-grants).

**Tier 4 — cosmetic / invariant-documentation:** ✅ DONE.

- **C2** ✅ sequence slot dispatched by id (`entities/seq.clj`): resolve
  the `:sequence` type-fn-id once, compare per-slot by id.
- **anon-hash** ✅ documented the load-bearing global-fn-name-uniqueness
  invariant on `parse.clj/anon-fn-name` (assertion would duplicate
  `validate-no-name-collisions!`; a comment pinning the dependency is the
  right fix).
- **P4** (layout free-arg migration by arg-name) — display-only,
  arg-name↔arg-name boundary fallback; left as-is (not a name-of-id).
- **P4b** ✅ FIXED (`d1d1ff64`) — layout synth-slot detection now compares
  the deterministic `(ids/slot-id owner name)` id, not the resolved name.
  Found by the CI-guard, not the manual pass.

**Type-checker (option-3):** ✅ DONE (behavior-preserving consolidation;
soundness untouched — re-keying was NOT attempted, it's a lateral move
per `ADR-slot-id-keyed-type-checker.md`). Its name-keying is mostly
BENIGN (fn-name unique); the FRAGILE core is the α' as-name narrowing,
KEPT. A subagent survey mapped the real debt; verified + fixed:

- **`strip-null`** was byte-identical in `narrowing.clj` + `core/logic/
  impls.clj` → one `types.core/strip-null` (single source of truth).
- **root-of-inheritance walk** was copied THREE times (`check.clj`
  `root-base-fn-name`, `narrowing.clj` `root-of-ref`, impls
  `root-base-fn-name`) → one `registry.core/root-base-fn-name` (lives in
  registry so no caller needs a type-checker dep — the cycle the copies
  existed to dodge).
- **`contains-secret?` / `has-type-var?`** were the same 8-arm structural
  fold with different leaf predicates (each docstring warned "a missing
  arm lets X slip through") → a `types.core/type-any?` combinator over a
  single `child-types` enumeration; a new type-kind is now covered in ONE
  place. Proven arm-equivalent (incl. the secret-node short-circuit).
- **undocumented ordering invariant** on `check-all-defs!` +
  `build-{caller-narrowings,ref-return-overrides}` (they need a
  topo-ordered / fully-swept registry or silently under-narrow) — now
  stated at each entry point.
Deliberately NOT done: `check-fn-def!` phase-extraction and the
`unify-or-keep` micro-helper (readability-only, not worth churn on the
checker's hottest function); `walk-type-shallow` merge of `resolve`/
`freshen*` (the agent flagged `freshen*`'s intentional `:effects`-drop —
a naive merge would change behavior).

Every fix: own commit, sweep-green + full `bb test`, skills + linters,
new test pinning the id-based behaviour.

## Enforcement (durable) — the CI-guard

`test/graphden/id_resolution_guard_test.clj` (commit `d1d1ff64`) makes the
core anti-pattern a red build so this audit's discipline doesn't erode:
it scans `src/` and fails on **dispatching by the NAME of a value held by
id** — `(:name (get/get-in/read-entity … id))` fed into `=`/`not=`/`case`/
`condp`. Drawn narrowly to have no false positives: name EXTRACTION for
display/serialisation (not in a comparison) and finding an entity BY name
(loop var, not an id-deref) are both left alone. It caught P4b on its
first run — an occurrence the manual pass had missed because it compares
against a symbol, not a string literal. Scope: `src/` mechanism layer
only (package `impls.clj` legitimately resolve name→id at boundaries).

## Incidental pre-existing bugs found + fixed along the way

Not name/id issues, but surfaced by the "clean at every stage" full
`bb test` and fixed per the no-ignored-errors bar:

- **app-router error/timeout sentinel namespace mismatch** (fixed
  `ecc78fdb`). `tenancy/app_router.clj` matched `run-with-timeout`'s
  result with BARE `::error`/`::timeout` — which resolve to app_router's
  own namespace, so they silently never matched
  `:compile-runtime/{error,timeout}`. An errored tenant handler (incl. a
  cloud-sandbox forbidden-effect throw → "blocked → 500") leaked the raw
  `::error` keyword instead of a 500. Reproduced on the pristine base
  (`7d39f34a`). `byo.clj` already matched correctly (`::cr/error`); the
  fix aligns app-router + documents the footgun on `run-with-timeout`.
  Caught by `faas-app-test/app-router-runs-handler-effect-gated`.

## `src/graphden/types/**`

Fully name-based; see `ADR-slot-id-keyed-type-checker.md`. Narrowing keys
on arg as-name (`*caller-narrowings*`, `build-caller-narrowings`) — the
FRAGILE core. `rich-type-of` fn-name keying is BENIGN (unique). The
inherited-free-arg slot identity is not locally derivable (needs the
`source-slot-id` resolution subsystem) — the one genuinely hard case.

---

## Synthesis / plan

*pending full audit — see next section once all subsystems reported.*
