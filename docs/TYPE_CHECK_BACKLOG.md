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

Sweep count: **13 → 0**.

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
