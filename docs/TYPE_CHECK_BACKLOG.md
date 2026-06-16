# Type-check / type-aliases backlog

Audit captured during followups B8 + B9 (2026-05-29). Both surfaces
warn at startup; runtime is unaffected, the editor's effect/return
strips just may be missing for the named fn-defs. Listed here so a
future type-system pass can pick them up.

## Status (2026-05-29 follow-up pass)

- B8.1 string-keyed `[:map :text :text]` classifier extension: **DONE**
- B8.2 typevar-slot literal nil/text: **DONE** (`:case` re-typed +
  list-item typevar union; see B8.3 fix below for the remainder).
- B8.3 HOF/typevar under-convergence: **DONE** (six type-system
  fixes; see "B8.3 fixes" below).
- B9 loader selection: **DONE** (`type-row-role` now uses
  `:return-type-fn-id` as the canonical base-fn signal).
- `merge-return-rule` extension for homogeneous `[:map K V]` inputs.

Sweep count: **13 → 0** (2026-05-29 baseline).

## 2026-06-16 — Sweep at ZERO; allowlist emptied

Post-α' the 11 nullability gaps fell to author-typed assertions in a
follow-up pass (same session). Each binding that passed a nullable
value into a non-null slot got a `{:type T}` override on the
inline-get / ref binding, documenting the runtime-guaranteed
narrowing the type-checker can't (yet) see through:

| fn-def | binding-form | override |
|---|---|---|
| `:str-starts-with?` (decl tightened) | `:string {:type :text}` → `:string {:type [:union :null :text]}` | impl is `(boolean (and string …))` — nil-safe by construction; the decl was lying. |
| `:bearer-token-raw` | `:string :authorization-header` → `:string {:ref :authorization-header :type :text}` | sole consumer `:bearer-token :if :test :has-bearer-prefix?` gates evaluation behind the non-null check. |
| `:_list-exec-limit-less-than-1?` / `:_list-exec-limit-over-max?` | `:nums [… :_list-exec-limit-parsed]` → `… {:ref :_list-exec-limit-parsed :type :int}` | wrapping `:_list-exec-limit-invalid? :or [(:nil? …) :_list-exec-limit-less-than-1?]` short-circuits before the non-null check fires. |
| `:_seq-remove-apply-do-delete` | `:id {:parent :get :args …}` → `:id {:parent :get :type :uuid :args …}` | upstream validation guard rejects the request when `:item-id` is missing. |
| `:_list-exec-by-fn-version-id` | `:fn-id {:parent :get …}` → `:fn-id {:parent :get :type :uuid …}` | same pattern. |
| `:_create-branch-apply-row` | `:branch-name {:parent :get …}` → `:branch-name {:parent :get :type :text …}` | `:_create-branch-name-blank?` guard rejects empty / nil names. |
| `:_inline-bind-target-fn-row` / `:_delete-secret-fn-row` | `:id {:parent :get …}` → `:id {:parent :get :type :uuid …}` | upstream `fn-id-missing?` guards reject the request. |
| `:_list-exec-by-version-rows` | `:count :_list-exec-clamped-limit` → `:count {:ref :_list-exec-clamped-limit :type :non-negative-int}` | `:cond`-clamped result is always non-negative int by construction. |
| `:_execute-fn-not-found-anchor` | `:default :_execute-parsed-fn-id` → `:default {:parent :to-str :args {:value :_execute-parsed-fn-id}}` | `:coalesce`'s typevar requires `:value` and `:default` to share a type; stringifying the UUID closes the join — sound because the sole consumer is `:str :parts`. |
| `:_list-exec-by-fn-synth-parsed` | structural rewrite | shape extended to include `:fn-id` + `:type` overrides on the rename items so the synth matches `:_list-exec-by-version`'s declared `:parsed` shape. |

The allowlist (`graphden.types.check/allowed-type-check-failures`)
is now `#{}`. The Phase E hard-gate stays armed: any NEW failure
trips `:types/sweep-regression`; any STALE entry would trip
`:types/sweep-stale-allowlist`. From here the type-system is
fully sound across the production package set.

## 2026-06-16 — Phase α' lands: original 10 closed, 11 nullability gaps surface

Phase α' (caller-context propagation to rename-host fn-defs)
combined with per-use-site anon naming
(`packages/records/parse.clj :: anon-fn-name` now mixes the host
fn-def + arg-name into the synthetic-name hash) closes the original
10 `:_X-apply-*` family failures — `:_create-apply-entity-type-str`,
its update/delete siblings, and the `-result/-do-invalidate/-do-
notify` consumers all narrow correctly to `:text` via
`name-return-rule` reading a typed `:parsed`.

The Pass-2/3 sweep extension lives in
`graphden.types.check :: build-caller-narrowings` +
`check-fn-def-with-narrowings!`. Pass 2 walks every binder
`F :arg :ref-name` where `:arg` is NOT a parent-contract slot, and
propagates the ref's return type to every fn-def in F's
ref-tree (EXCLUDING `:ref-name` itself) that has a rename
`{:as :arg}` in its OWN args. Pass 3 re-runs `check-fn-def!` with
`*caller-narrowings*` bound to the per-callee entry; the rename
branches in `bindings-info-for-rule` + `collect-free-args` honour
the narrowed type.

Side fix in `effective-ref-return` (PRE-EXISTING bug): the merge
order was `(merge ref-bindings caller-bindings)` — caller-wins —
which let a deeper rename chain's `:coll` narrowing leak across
unrelated refs whose own `:coll` should have dominated. Flipped
to `(merge caller-bindings ref-bindings)` so ref's own bindings
shadow caller's on key collision. Without this, α' v2/v3 produced
28-30 cross-flow false-positives.

### Post-α' residual: 11 nullability gaps

α'-driven tighter return types now SURFACE 11 pre-existing
`[:union :null T] vs T` mismatches that were previously masked by
looser Pass-1 returns:

| fn-def | offending binding | expected | actual |
|---|---|---|---|
| `:bearer-token-raw` | `:string ← :authorization-header` | `:text` | `[:union :null :text]` |
| `:has-bearer-prefix?` | `:string ← :authorization-header` | `:text` | `[:union :null :text]` |
| `:_create-branch-apply-row` | `:branch-name ← anon` | `:text` | `[:union :null :text]` |
| `:_delete-secret-fn-row` | `:id ← anon` | `:uuid` | `[:union :null :uuid]` |
| `:_execute-fn-not-found-anchor` | (TBD) | | |
| `:_inline-bind-target-fn-row` | `:id ← anon` | `:uuid` | `[:union :null :uuid]` |
| `:_list-exec-by-fn-version-id` | `:fn-id ← anon` | `:uuid` | `[:union :null :uuid]` |
| `:_list-exec-by-version-rows` | `:count ← :_list-exec-clamped-limit` | `[:refine :int [:>= 0]]` | `[:union :int :null]` |
| `:_list-exec-limit-less-than-1?` | `:nums ← :_list-exec-limit-parsed` | `:numeric` | `[:union :int :null]` |
| `:_list-exec-limit-over-max?` | (same) | `:numeric` | `[:union :int :null]` |
| `:_seq-remove-apply-do-delete` | `:id ← anon` | `:uuid` | `[:union :null :uuid]` |

These need either Phase #170 (control-flow narrowing through
`:if`/`:cond` guards) or per-fn-def `:assert-some` annotations.
The runtime is guarded — an upstream nil-check rejects the null
case before the apply branch runs — but the type-checker can't see
through the guard. Architectural debt, allowlisted.

## 2026-06-16 — Phase E hard-gate on sweep failures (allowlisted)

The 10 remaining failures listed below (all `:_X-apply-*` family)
are now allowlisted in `graphden.types.check/allowed-type-check-
failures`. `system.core/sync-fn-entities-from-packages!` calls
`assert-sweep-failures-match-allowlist!` after every sync:

* Any failure NOT in the allowlist throws `:types/sweep-regression`
  at sync time — the system refuses to start. CI catches it loud.
* Any allowlisted name that's NO LONGER failing throws
  `:types/sweep-stale-allowlist` — the ledger must shrink as the
  type-system gains expressiveness.

The 10 entries map 1:1 to the `:get :parsed :entity-type → :name`
chain through shared `:parsed` slot identity. Closing them requires
Phase α' (slot-id-aware caller-context propagation) or Phase γ
(row polymorphism) — see `docs/TYPE_SYSTEM_ROADMAP.md` for the
architectural tradeoffs and recommended path.

## 2026-06-16 — sweep regressed to 12 (control-flow narrowing gap)

After the CRUD apply-stage decomposition (Phase 4.2 — splitting
`_create-apply` / `_update-apply` / `_delete-apply` into separate
`-result` / `-existing` / `-do-invalidate` / `-do-notify` /
`-do-delete` nodes), the type-check sweep started reporting **12
fn-defs failed** at startup. Captured 2026-06-16 by temporarily
promoting the per-fn `log/debug` to `log/warn` in
`system.core/sync-fn-entities-from-packages!`.

### Failure pattern (11 of 12)

`expected :text, actual [:union :null :text]` on `:entity-type`
slot. The 11 affected fn-defs:

* `:_create-apply-result` / `-do-invalidate` / `-do-notify`
* `:_update-apply-result` / `-do-invalidate` / `-do-notify`
* `:_delete-apply-existing` / `-do-delete` / `-do-invalidate` /
  `-do-notify`

Root cause (refined 2026-06-16):

Each binds `:entity-type :_X-apply-entity-type-str` where
`:_X-apply-entity-type-str = (:name (:get :parsed :entity-type
:default nil))`. `:name`'s declared return is `[:union :null
:text]` (because `(name nil)` is `nil`); for the chain to narrow
to `:text`, the type-checker needs to see `:get`'s return as a
non-null keyword. That requires `:parsed`'s `coll-type` to be a
known record with `:entity-type :keyword`.

The `:parsed` SLOT is shared globally across the CREATE / UPDATE
/ DELETE flows with structurally DIFFERENT record shapes:

| Flow   | Shape                                          | `:id`? |
|--------|------------------------------------------------|--------|
| create | `:_create-parsed-shape` (no `:id`)             | no  |
| update | `:_update-parsed-shape` (has `:id-str`/`:id-uuid`) | no  |
| delete | `:_delete-parsed-shape` (has `:id`)            | yes |

Any global tightening — e.g. annotating
`{:as :parsed :type :_create-parsed-shape}` at one create-flow
site, OR declaring `:return-type :_create-parsed-shape` on
`:_create-parsed` — propagates the type to the SHARED slot and
breaks delete-flow / update-flow fn-defs that do
`(:get :parsed :id …)`: my new `:get` rule (commit 240aef5e)
correctly typo-throws on a literal key absent from the now-typed
record, surfacing those siblings as new failures.

So the principled fix is one of:

A. Flow-specific slot rename — replace `{:as :parsed}` with
   `{:as :create-parsed}` / `{:as :update-parsed}` /
   `{:as :delete-parsed}` everywhere, then each per-flow
   `:_X-parsed` can be typed independently. Mechanical but
   touches dozens of fn-defs across web/crud.

B. Flow-sensitive type-checker — walk the rename chain from
   each binding-site back to a TYPED upstream, narrow per-chain.
   Substantial type-system addition; intersects with the broader
   control-flow narrowing work below.

Neither fits a single session. The 11 surface only as missing
editor effect/return strips — runtime is unaffected.

### Partial enablement landed 2026-06-16

Two general-purpose primitives added that make per-chain typing
PRODUCTIVE once flow-disambiguation lands:

* `:name` return-rule: typed input narrows to typed output
  (`:keyword` → `:text`, `[:union :null :keyword]` → `[:union
  :null :text]`).
* `:assert-some` base-fn: runtime non-null assertion + type-level
  `:null` strip. Companion to `:coalesce` for the audit-as-
  unreachable case.

Once `:parsed` slots are disambiguated per flow, these primitives
close the chains without further type-checker changes.

### Failure 12 — `:_value-form-root-attrs`

`expected [:map a :any], actual {:data-binding-id :text}`. `:merge`'s
:maps slot expects a homogeneous map; the binding's computed type is
a single-field record. The two are structurally compatible at
runtime but the type-checker doesn't accept `record-type ⊆ [:map K
V]`. Adjacent to the record/map subtype work in B8.

### Fix paths (deferred)

* **Control-flow narrowing via `:if`/`:case` guard propagation** —
  the principled fix. Substantial type-system change.
* **Mechanical `:coalesce`-with-sentinel** — wrap each
  entity-type-str chain with `:coalesce :default :_unknown` to
  strip null. Runtime never hits the default (guarded upstream), but
  pollutes the type-spec and semantically lies.
* **`record-type ⊆ [:map K V]` subtype rule** — fixes the
  `:_value-form-root-attrs` case. Pure type-system addition.

All 12 surface only as missing editor effect/return strips — runtime
is unaffected. Holding for a focused type-system iteration.

## In-session pass (2026-06-07): sweep count 74 → 0 (-100 %)

13 fixes landed across `types/check.clj`, `types/core.clj`,
`system/core.clj`, `packages/records/parse.clj`,
`core/collections/impls.clj`, `core/logic/impls.clj`, and a
small number of fn-def annotations in `web/crud`,
`web/ring-adapter`, `app/execution`. Phase 1 (fixes 1-7) brought
74 → 7; phase 2 (fixes 8-13) cleared the rest.

## Phase 1 fixes (74 → 7)

Six fixes landed across `types/check.clj`, `types/core.clj`,
`system/core.clj`, and `packages/records/parse.clj`. Below in
landing order:

Two fixes landed in `types/check.clj`, `types/core.clj`, and
`system/core.clj`:

1. **`:empty-map` sentinel from `classify-literal`** — `{}` now
   classifies as `:empty-map` instead of `:jsonb`. New subtype rule
   accepts `:empty-map ⊆ {:jsonb, [:map K V], record-type,
   :empty-map}` as vacuous truth (an empty map carries no entries
   to violate any structural constraint). New `make-union` absorb
   rule drops `:empty-map` when a sibling map-shape (`:jsonb`,
   `[:map K V]`, record-type) is already in the union — keeps
   downstream consumers from special-casing the sentinel.

2. **`:map`-shaped alias registration** — `register-type-aliases!`
   in `system/core.clj` had branches for `:refine` / `:type` /
   `:list` / `:union` / `:variant` / `:fn-type` but NONE for
   `:map`. A fn-def like `:_storage-where-map :map {:key :keyword
   :value :any}` never reached the alias registry; downstream
   `:list-entities :where :_storage-where-map` slot reference saw
   an opaque keyword the type-checker treated as a primitive. Add
   the missing `:map` branch — registers `[:map K V]`.

3. **Inline-anon fn-def expansion before the sweep.**
   `expand-inline-anons-in-module` (parser pre-pass) lifts every
   `{:parent :X :args Y}` map in arg-binding position into a
   synthetic `_anon-<hash>` fn-def — used by storage / runtime /
   compile. But `:exec/fn-entities`'s type-check sweep was iterating
   the ORIGINAL EDN, classifying each inline anon as a literal
   map. Expose the pre-pass (drop the `-` privacy) and run it on
   the fn-defs list before topo-sorting + checking.

4. **`types/freshen` — single-type version of `freshen-args`**.
   Renames every type-var in a single type via `freshen*`'s subst
   atom. Used by the next two fixes; previously only
   `freshen-args` existed (for maps).

5. **Freshen ref-binding returns at the value-binding path.**
   When `check-one-binding` resolves a ref-binding's actual via
   `(:return (rich-type-of …))`, run `types/freshen` over the
   result. Without that, an actual like `[:union :null a]` shares
   the typevar `'a` with the caller's `:some? :value {:type a}`
   slot — `unify 'a [:union :null 'a]` then trips the occurs
   check.

6. **`check-sequence-items` freshens per-item ref returns.**
   Two siblings whose computed return both name `'v` (e.g.
   `:zipmap :vals [:_list-branches-count :_list-branches-json]`
   both `[:map :keyword v]`) collapsed into a shared `'v` that
   the outer slot's typevar then couldn't bind to via occurs.
   Freshen each item so siblings keep separate scopes.

7. **Closure-capture stripping + variadic-ignore on fn-type
   slot bindings.** When a fn-ref's actual signature has MORE
   args than the slot's `[:fn {ARGS} _]` callable contract, the
   extras are closed over from the OUTER binding-chain
   (docs/CLOSURE_CAPTURE.md). Drop those keys before passing
   `actual'` to `check-binding!`. When the actual has ZERO args
   and the slot expects ≥1, adopt the slot's arg map (the
   runtime hof-wrap variadic-ignores at invocation). Cardinality-
   match cases skip both transforms so 1-arg `:str-upper :func`
   still binds to `:map`'s `{:item a}` via the unifier's
   alpha-equivalence path.

**51 originals fixed.** 8 new failures surfaced — REAL bugs the
masking previously hid:

- `:_list-secrets-filtered`, `:_execute-unknown-args`,
  `:_lfv-versions-json` — predicate / HOF callback declared with
  effect `#{:db}` against `:filter` / `:map`'s `:func` slot
  constrained `[:fn {…} _ :any]`. Now that `[:map K V]` slots
  resolve, the callable subtype check reaches effect-compatibility
  too. Real bug — needs tightening the callback or loosening the
  slot's effect contract.
- `:ex-invoke-handler` — `:make-handler` declares `:return-type a`
  (the response itself, not `[:fn {:req _} a]`). With the response
  shape now visible, `:invoke`'s `[:fn {:arg a} b]` slot rejects
  the binding. Needs `:make-handler` to declare its real
  callable-producing return-type.
- Two `:on-throw` cases where `:try`'s `:body` binds `'a` to
  body's return, then `:on-throw` fails because its return shape
  differs from body's — `:try`'s return-type rule must allow
  union over the two branches rather than forcing equality.

## Remaining 23 failures

Three root causes drive the rest (same buckets as before, scaled
down by the 14 alias-resolution wins):

The post-2026-05-29 wave of decomposition (C6-C18 + branches /
secrets / executions parse-validate-apply splits + Phase-6 rename
work) introduced new fn-defs whose literal-bound or ref-bound args
exceed the type-checker's structural reasoning. Patterns AND counts
captured by tee-ing the topological sweep into
`/tmp/type-check-output.txt`; histogram below reflects the
pre-2026-06-07 picture.

### Failure histogram by argument name (pre-fix snapshot)

| Count | Arg name | Dominant pattern |
|---|---|---|
| 12 | `:where`   | `(literal {})` → classifier returns `:jsonb`, slot expects `:_storage-where-map = [:map :keyword :any]` |
| 8  | `:ref`     | `(literal {:parent :get :args {…}})` — inline-anon fn-def map literal; classifier sees a record-shaped map, slot expects the parent's return-type |
| 7  | `:string`  | same inline-anon pattern against predicate `:string` slots |
| 4  | `:id`      | `(literal {:parent :get :args {…}})` against `:uuid` |
| 4  | `:func`    | fn-ref's computed signature includes captured-closure args the parent slot doesn't expect |
| 4  | `:default` | nullable-default literal vs slot's parametric `a` |
| 4  | `:body`    | fn-ref → handler whose computed `[:fn …]` carries extra slots vs `:try`'s `[:fn {} a :any]` |
| 3  | `:value`   | inline-anon vs predicate `:value` (mirror of `:string` case) |
| 3  | `:vals`    | sequence-binding's per-item record-types form a union the parent's typevar refuses to unify against |
| 3  | `:data`    | downstream of `:func` failures, propagated through `:cond` |
| 3  | `:coll`    | inline-anon as `:coll` |
| 2  | `:f`       | `:_merge-in-fn`-style closure-capture signature mismatch |
| 1× | various    | `:to`, `:then`, `:s`, `:nums`, `:handler`, `:fn-id`, `:entity-type`, `:count`, `:base-handler` — long-tail single-case quirks |

**Sweep count: 74. Runtime UNAFFECTED** — the warning emits at
boot, individual failures get DEBUG-logged, and the editor's
effect / return-type strips for the named fn-defs may be missing
or stale. The graph itself executes correctly.

### Three root causes drive the 74

1. **Empty-map literal classifier** (12+ cases including ripples
   through `:vals` / `:coll`). Current `(empty? v) → :jsonb` loses
   "this is an empty map, vacuously satisfies every map shape" —
   makes `{:where {:value {}}}` fail against `[:map :keyword :any]`.

   Naive fix (`:empty-map` sentinel + `:empty-map ⊆ [:map K V]`
   subtype rule + union absorb in `make-union`) introduces ~1
   regression per misuse of `{}` as a stand-in for a non-empty
   record (e.g. `:ex-invoke-handler` uses `{}` as a fake Ring
   request — semantically wrong but masked by the `:jsonb` loose
   path). Clean fix needs either (a) make examples use realistic
   literals, or (b) introduce a "bottom-of-records" interpretation
   that admits empty-map against non-empty records too (lossy).

2. **Inline-anon fn-def in arg-binding position** (18+ cases:
   `:ref` / `:string` / `:value` / `:id` / `:coll`). The parser's
   `expand-inline-anons-in-module` pre-pass lifts
   `{:parent :X :args Y}` into a synthetic `_anon-<hash>` fn-def
   BEFORE storage sync — but the type-check sweep runs over the
   ORIGINAL EDN fn-defs, never the expanded ones, so it sees the
   inline anon as a plain keyword-keyed record.

   Clean fix: run `expand-inline-anons-in-module` BEFORE
   `check-fn-def!` in `:exec/fn-entities`, OR teach
   `classify-literal` to recognise the shape and look up the
   parent's rich-type.

3. **HOF closure-capture / typevar-via-union under-convergence**
   (B8.3 originals — `:_merge-in-fn`, `:auth-required-middleware`,
   `:router-result` family, `:_layout-build-apply`, etc., ~10
   cases). Same three sub-problems as in the original B8.3 audit
   below; the 2026-05-29 fix made the canonical cases pass but
   later code added new shape variants the rules don't recognise.

### Scope

Fixing the 74 cleanly is a **dedicated type-system project** (the
original B8.3 audit estimated similar scope and took multiple
focused sessions). Each root cause's "fix" interacts with the
others — tightening (1) creates new failures downstream that need
(2) or (3) to be in place. Doing one at a time produces transient
regressions of 1-10 fn-defs.

**Today's runtime is unaffected.** Tracked here so the next
type-system pass can pick it up with an accurate starting count.

## 2026-06-07 closing pass: sweep count 7 → 0 (FULL CLEAR)

All 7 originally identified remaining failures were resolved.
Five additional type-system improvements landed; each unlocked
one or more of the remaining failures:

8. **Inline-anon `:type` override propagation**
   (`packages/records/parse.clj`). When `expand-inline-anons-in-module`
   lifts an inline `{:parent X :args Y :type T ...}` map to a
   synthetic `_anon-<hash>` fn-def, the `:type T` is now stripped
   off the lifted fn-def and re-emitted as `{:ref _anon-<hash>
   :type T}` on the binding side. This lets author-pinned types
   on inline anons act as type-overrides at the call site (same
   semantics as bare `{:ref :foo :type T}` bindings). Fixes
   `:_update-pre-existing-fetched`-style guarded nullables.

9. **`:type` honoured on vector binding-item closure-captures**
   (`types/check.clj :: vector-binding-elem-types`,
   `sequence-item-actual-type`). The `{:as :name :type T}` and
   `{:ref :foo :type T}` shapes already worked as scalar bindings;
   extend the same honour to vector items so a sequence-binding
   element like `[{:ref :_uri-marker-pos :type :int} {:value 1}]`
   strips `:_uri-marker-pos`'s nullable surface for the type-check.

10. **Refinement ↔ primitive subtype-aware unification**
    (`types/core.clj :: unify`). Add an arm to the lenient
    subtype-aware branch (sibling of the existing primitive /
    `:jsonb` / `:empty-map` arms): when either side is a
    refinement type AND `subtype?` succeeds in either direction,
    unify without further binding. Without this, a record field
    unifying `:int ↔ :non-negative-int` (or vice-versa) falls
    into `::fail` even though the relation holds.

11. **`:zipmap-return-rule`** (`core/collections/impls.clj`). When
    the `:keys` binding is a literal vector of `{:value <kw>}`
    items and the `:vals` binding's per-item types are known,
    return a record-type whose fields are exactly those keys.
    Lets a downstream `:assoc` (e.g. `:html-error-response`)
    chain reconstruct the response shape `{:status :int :headers
    _ :body :text}` instead of collapsing to `[:map :keyword :any]`.

12. **`:if-return-rule` literal-int refinement**
    (`core/logic/impls.clj`). When BOTH `:then` and `:else`
    bindings are positive integer literals, refine `[:union :int
    :int] = :int` to `:positive-int` (`:non-negative-int` when
    one branch is zero). Lets `:_drop-count` (returns `1` or `2`)
    satisfy `:drop :count :non-negative-int`.

13. **Per-arg effect tracking + `:call-time-effects` registry
    field** (`types/check.clj`). Splits a fn's `:effects` set
    into wrap-time vs call-time. Per-arg effect contributions are
    recorded; refs bound to BOUND args contribute wrap-time
    effects only; refs bound to FREE args (call-site lift-through)
    + the parent's body effects form `:call-time-effects`.
    `assemble-fn-type` prefers `:call-time-effects` over the full
    `:effects` set when building the structural fn-type used at
    HOF binding sites. Fixes `:_list-secrets-filtered` /
    `:_execute-unknown-args` style cases where a predicate has a
    captured DB-read at construction but is pure per-invocation.

Final sweep count: **0 fn-defs failing**. Type-drift warnings (where
declared is strictly wider than computed) remain as soft signals —
those are documentation contracts, not bugs.

## B8: 6 remaining fn-defs failing the topo-sorted type-check sweep

Captured by tee-ing the failure log into `/tmp/type-check-failures.edn`
during a one-off sweep. Four root causes:

### B8.1 — string-keyed map literal classifies as `:jsonb`, not `[:map :text :text]`

`crud/type-check/classify-literal` returns `:jsonb` for any
string-keyed map (the keyword-keyed branch only fires when EVERY
key is a keyword). So a fn-def that binds `:headers
{"Content-Type" "..."}` literally fails the parent's
`[:map :text :text]` expectation.

| fn-def | file:line |
|---|---|
| `:_default-auth-fail-body` | `packages/web/ring-adapter/fns.edn:253` |
| `:cached-svg-content-type` | `packages/app/common/fns.edn:138` |
| `:html-content-type`       | `packages/app/common/fns.edn:126` |
| `:text-content-type`       | `packages/app/common/fns.edn:131` |
| `:json-content-type`       | `packages/app/common/fns.edn:121` |
| `:html-action-response`    | `packages/web/crud/fns.edn:547`  (via `:_action-headers` returning `[:map :any :any]`) |

Fix candidates:
- (a) Extend `classify-literal` to detect a fully string-valued map
  as `[:map :text :text]`. Risk: changes classification for any
  string-keyed map elsewhere.
- (b) Loosen parent slot types from `[:map :text :text]` to
  `:jsonb` where the impl already coerces both directions.
  Practical for `:ring-response/:headers` since impls already
  stringify on the wire boundary.
- (c) Declare these literals via an explicit `:type [:map :text :text]`
  override on the binding so the slot's wider type doesn't classify
  back to `:jsonb`.

### B8.2 — literal `nil` / `:text` flowing into a slot typed as a type-variable

| fn-def | parent | binding | actual |
|---|---|---|---|
| `:ex-only-some` | `:filter` | `:coll {:value nil}` | `:null` ⊄ `a` |
| `:ex-status-label-by-kw` | `:case` | `:default {:value "Unknown"}` | `:text` ⊄ `a` |

Both are in `packages/examples/*` so impact is documentation-only,
not runtime. Fix candidates:
- (a) Update the example to declare `:type :text` on the binding
  to bind the typevar.
- (b) The check could treat `{:value nil}` on a typevar slot as
  "bind typevar to `:null`".

## B8.3 fixes (DONE — 6 → 0)

| # | fn-def | Root cause | Fix site |
|---|---|---|---|
| 1 | `:ex-only-some` | `[1 nil 2 nil 3]` against `[:list a]` — per-item reduce binds `a := :int` from first item, fails on `:null` | `check-sequence-items`: when elem-type is a bare typevar, build the LUB union of all items first and unify the var with the union in one pass |
| 2 | `:router-result` | `[:fn {:arg :ring-request-shape} :ring-response-shape]` override against `[:fn {:arg a} b :any]` — monotonicity check rejected concrete-into-typevar as "widening" | `check-binding-monotonicity!`: extend the "skip typevars" gate from bare `type-var?` to full `has-type-var?` (covers structural-typevar inheriteds) |
| 3 | `:html-action-response` | `:_action-headers` (merge) returns `[:map :any :any]`; slot expects `[:map :text :text]` | `check-binding!` + `any-shape?` helper: structural-`:any` actual (`[:map :any :any]`, `[:list :any]`, …) gets the same escape-hatch as bare `:any` |
| 4 | `:router-ring-response` | Declared `:ring-response-shape`, computed `:any` (rule chain widening) | `enforce-declared-return!`: structural-declared types accept `any-shape?` computed (assertion mode). Primitive declared still strict-rejects (protected by existing test) |
| 5 | `:merge-in` | `:_merge-in-fn`'s computed signature carried `{:as :keyword, :description :text}` as its merge inputs — the literal `{:as :defaults}` items were being classified as record-types | `vector-binding-elem-types`: closure-capture items (`{:as :name}` markers) get type `:any`, mirroring `sequence-item-actual-type`. Without this, downstream return-rules (`merge-return-rule`'s all-records branch) build wrong merged shapes from meta-fields |
| 6 | `:auth-required-middleware` | `:_auth-required-body`'s `:next-handler [:fn ...]` against `:middleware`'s `:next-handler :any` — contravariant args check rejected narrower sub-arg against loose sup-arg | `fn-args-subtype?` (core): when sup's arg is `:any`, accept any sub-arg silently — the slot promises no constraint, callee may narrow freely (assertion-style) |

All commits land in this followup-cycle.

### B8.3 — HOF / structural-fn-type unify under-convergence (original audit, NOW RESOLVED)

| fn-def | parent | symptom |
|---|---|---|
| `:merge-in` | `:update-in` | `:f` is `:_merge-in-fn` whose computed signature carries the closure-captured `:defaults` arg + a record return that the typevar `b` can't accept |
| `:router-result` | `:invoke` | `:func` declared as `[:fn {:arg :ring-request-shape} :ring-response-shape]`; refuses NARROWING into the inherited `[:fn {:arg a} b :any]` (sees it as "widening") |
| `:router-ring-response` | (inherited from `:merge-in`) | computed return widens to `:any` against declared `:ring-response-shape` |
| `:auth-required-middleware` | `:middleware` | `:body` ref's computed sig has extra slots (`:default`, `:end`, `:next-handler` mismatch) vs the parent's expected sig |
| `:html-action-response` | `:ring-response` | `:headers` ← `:_action-headers` (merge) computes `[:map :any :any]` because `:_action-headers-raw`'s `:get`-derived return is `:any`, defeating `merge-return-rule`'s narrowing |
| `:ex-only-some` | `:filter` | `:coll [1 nil 2 nil 3]` classifies as `[:list :any]` (heterogeneous elements); `[:list :any] ⊄ [:list a]` for free typevar `a`. Binding-type override also rejected as "widening" |

The HOF/typevar unification has known limits (memory item
`project_hof_type_inference_bug`). These six are the canonical
remaining cases. Three sub-problems compose them:

1. **Closure-capture signature mismatch** — when a HOF callable
   passed as an `:f` binding has captured args via `:as`, the
   computed signature includes those captures even though the parent
   only expects the per-call params. `:merge-in`, `:auth-required-
   middleware` hit this.
2. **Typevar refuses concrete narrowing in fn-type slots** — when
   the parent declares `[:fn {:arg a} b :any]` and the binding
   provides `[:fn {:arg :concrete} :concrete-ret]`, unify treats the
   concrete shape as a widening of the typevar. `:router-result`
   hits this.
3. **Return-rule chain widens to `:any`** — `:get`/`:merge`
   defaults propagate `:any` upstream when the input shapes aren't
   one of the specifically-narrowed cases the rules recognise.
   `:router-ring-response`, `:html-action-response` hit this.

Plus `:ex-only-some` — the literal-classifier produces `[:list :any]`
for heterogeneous-element vectors and there's no path to communicate
"actually `a := [:union :null :int]`" through to the typevar binding.

Fix is a type-system project. None blocks runtime; the editor's
effect / return-type strips just won't surface a value for these six.

## B9: ~5–6 fn-defs skipped by `register-type-aliases-from-db!`

Startup warnings (one per fn-def):
```
register-type-aliases-from-db!: skipped :parse-binding-list-item-from-form — body not well-formed
register-type-aliases-from-db!: skipped :create-entity — body not well-formed
register-type-aliases-from-db!: skipped :parse-fn-from-form — body not well-formed
register-type-aliases-from-db!: skipped :list-entities — body not well-formed
register-type-aliases-from-db!: skipped :update-entity — body not well-formed
register-type-aliases-from-db!: skipped :parse-ns-from-form — body not well-formed
register-type-aliases-from-db!: skipped :parse-fn-slot-from-form — body not well-formed
register-type-aliases-from-db!: skipped :parse-slot-from-form — body not well-formed
register-type-aliases-from-db!: skipped :parse-binding-from-form — body not well-formed
```

All are form-parsers in `web/crud/fns.edn` / sibling packages —
they were attempted as type aliases because their `:return-type`
references a structural shape with names that fail `well-formed?`
at sync-time. Same root cause as B8.1: the form-parsers return
JSON-flavoured shapes that don't have a name in the alias
registry yet.

Fix: depends on whether each is meant to BE a type alias. Probably
NOT — they're regular fn-defs whose return shape happens to look
alias-like to the loader. The selection logic in
`register-type-aliases-from-db!` should reject form-parsers earlier
(e.g. require explicit `:type-alias? true` marker, or skip if the
fn-def has `:impl-hash`).

## Scope decision

Both surfaces are PRE-EXISTING tech-debt unrelated to the
secret-flow series. Fixing them needs:
- type-system extension for string-keyed map literals (B8.1, B9),
- HOF/typevar unification work (B8.3),
- example cleanups (B8.2),
- loader selection logic (B9).

Each is its own focused effort. Marked DONE-for-audit; the per-name
fixes live as future TaskCreate entries when prioritised.
