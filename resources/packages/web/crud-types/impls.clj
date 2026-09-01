(ns graphden.packages.web.crud-types.impls
  "Implementations for the `web.crud` type API and the type-row compound stages.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry]
    [graphden.types.check :as tcheck]
    [graphden.types.check.literals :as types-lit]
    [graphden.types.core :as types]))


(defbase json-to-type-fn
  "Decode the JSON wire-shape of a type expression back to its
   Clojure AST. Inverse of cheshire's default Clojure → JSON
   encoding; refinement constraints decode base-aware so literal-
   string predicates (`[:not= \"\"]`) stay strings instead of being
   re-keywordized. Atomic wrapper over `types-api/json->type` —
   single recursive deserializer, no admin-extensible composition."
  [json]
  (types-api/json->type json))


(defbase to-set-fn
  "Coerce a sequence (or any seqable) into a Clojure set
   (`clojure.core/set`). Atomic library boundary. Useful when a
   downstream consumer uses the result as a containment predicate
   (`every? the-set xs`, `contains?`-style guards) — vector or list
   would silently mis-behave there."
  [coll]
  (set coll))


(defbase subtype?-fn
  "Atomic library boundary over `types/subtype?` — true iff
   `candidate` is a subtype of `expected` under graphden's type
   hierarchy. The subtype algorithm itself (refinement / list /
   union / record / fn-shape recursion) is §3.3 invariant logic
   that lives in `types/core`."
  [expected candidate]
  (types/subtype? candidate expected))


(defbase fn-type?-fn
  "Atomic library boundary over `types/fn-type?` — true iff the type
   expression is a callable shape (`[:fn args ret]` / `[:fn args ret
   effects]`). The candidate filter branches on it: a callable slot
   admits by SIGNATURE, every other slot by return type."
  [type]
  (boolean (types/fn-type? type)))


(defbase fn-signature
  "The callable shape of the fn named `fn-name` — `[:fn args ret
   effects]`, or nil when the registry has no entry yet.

   Atomic library boundary over `types.check/assemble-fn-type`: the
   SAME assembler `check-binding!` uses on write. Assembling it a
   second time from the raw registry fields looks equivalent and is
   not — it misses the producer-of-callable case (a fn whose return is
   itself a fn-type surfaces the INNER type) and the per-invocation
   `:call-time-effects` subset HOF slots measure against — so the
   picker would offer or refuse binds the write path judges the other
   way."
  [fn-name]
  (tcheck/assemble-fn-type fn-name))


(defbase describe-type-mismatch-fn
  "Atomic library boundary over `types-api/describe-mismatch` —
   one-line human-readable explanation of why `candidate` ⊄ `expected`.
   Used by the editor's type-picker tooltip; admins can vary the
   wording downstream by composing on top, but the dispatch cases
   themselves are §3.3 algorithm."
  [expected candidate]
  (types-api/describe-mismatch expected candidate))


(defbase classify-literal
  "Atomic library boundary over `types.check/classify-literal` —
   infer the structural type expression of a literal Clojure value.
   nil for shapes the classifier doesn't recognise; callers fall
   back to `:any`."
  [value]
  (types-lit/classify-literal value))


(defbase diff-value-against-type
  "Atomic library boundary over `types.check/diff-value-against-type` —
   leaf-level disagreements between `value` and `expected` as
   `[{:path :expected :actual}, …]`. Empty vector when the value
   satisfies the type."
  [value expected]
  (types-lit/diff-value-against-type value expected))


(defbase closed-enum-of
  "Atomic library boundary over `types.check/closed-enum-of` —
   returns `{:base :members}` when `expected` resolves to a closed-
   enum refinement (`[:refine base [:in [members]]]`), nil otherwise.
   Members are sorted + colon-prefixed for `:keyword`-based enums."
  [expected]
  (types-lit/closed-enum-of expected))


(defbase fn-type-bound-effects
  "Atomic library boundary over `types.check/fn-type-bound-effects` —
   returns a vec of effect-name strings (without the leading colon)
   when `expected` is a `[:fn args ret eff]` with concrete eff, nil
   otherwise (no constraint to surface)."
  [expected]
  (types-lit/fn-type-bound-effects expected))


(defbase rich-type-of-name
  "One registry lookup — the full rich-type entry for the named fn
   (nil-safe: nil / unknown names → nil). `:rich-return-of-fn`
   (fns.edn) composes this with `:get-entity` + `:get` to go
   fn-id → row → name → entry → `:return`."
  [fn-name]
  (when fn-name
    (registry/rich-type-of (keyword fn-name))))


(defbase rule-owner-of-name
  "Atomic library boundary over `registry/rule-owner-info-of` —
   `{:name … :fn-id …}` of the base-fn whose `:return-type-rule`
   computed the named fn's return type; nil when the fn is unknown, is
   itself a base-fn, or its root ancestor carries no rule. The `:fn-id`
   comes off the registry entry itself (id-keyed truth), so partials
   emit nav-links without a name→id graph query. The walk lives next to
   `registry/root-base-fn-name` (single source of truth); the layout
   strip-facts pass calls the name-only `registry/rule-owner-of`."
  [fn-name]
  (registry/rule-owner-info-of fn-name))


(defbase declarable-effect-categories
  "Sorted vec of the declarable effect-category names — the canonical
   `types.check/known-effect-categories` set, the same one sync-time
   validation accepts. Single source for the declared-effects form's
   checkbox roster."
  []
  (vec (sort (map name tcheck/known-effect-categories))))


(defbase type-name-kinds
  "Sorted `{:name :kind}` rows for the editor's type-name datalist —
   named type-rows classified via `compute-fn-role` plus the bare
   primitives. Single library call into `types-api/type-name-kinds`."
  []
  (cr/record-effect! :db)
  (types-api/type-name-kinds ctx))


(defbase compatible-type-names
  "`type-name-kinds` rows filtered to the names that can legally
   narrow `expected` — one alias-aware `subtype?` sweep server-side.
   Single library call into `types-api/compatible-type-names`."
  [expected]
  (cr/record-effect! :db)
  (types-api/compatible-type-names ctx expected))


(defbase _types-usages-apply
  "Stage 3 of types-usages — walk the graph for every reference to the
   target fn row. Returns `{:ok :type-fn-id :type-name :count :usages}`
   where `:usages` is a vec of `{:fn-id :fn-name :role :kind …}` entries
   spanning the type plane (`:base-of`, `:element-of`, `:return-of`,
   `:union-branch` / `:variant-branch` / `:fn-type-arg-or-return`
   constraint uses, `:slot-of`, `:binding-of`) AND the composition
   plane (`:parent-of` children, `:ref-of` binding/list-item refs,
   `:resolver-of`) — one walk serves /api/types/usages and the
   /api/fns/usages alias behind the inspector's Used-by section.

   §3.3 algorithm — the constraint-uses category requires
   `constraint-contains-type-ref?` which is a RECURSIVE walk into
   nested `[:union …]` / `[:variant …]` / `[:fn args ret]` /
   `[:refine base [:and …]]` constraint vectors — a cycle-tracking
   shared-state walk, the §3.3 carve-out proper (unchanged by `:fix`
   shipping: `:fix` gives structural recursion, not shared
   cycle-state across mutually-recursive walks). Decomposing the 5
   non-recursive categories independently would split the response
   shape across Clojure + graph and double the round-trip cost
   (each category needs the cached-graph snapshot) without giving
   admins meaningful tuning surface for the recursive one.

   The sibling `parse-constraint` defbase stays one unit for a
   different reason — it is a self-contained wire-format parse
   contract (see its docstring)."
  [parsed]
  (cr/record-effect! :db)
  (types-api/apply-types-usages parsed ctx))


;; === Type-row compound handlers ===
;; `process-create-record-type` / `process-create-list-type` /
;; `process-update-record-type` are `:if` graph fn-defs (`web/crud`
;; fns.edn) — an `:if` over the validation result, branching to the
;; `{:ok false :error}` rejection or to the transactional apply.
;; These base-fns are the parse / validate / apply stages; `_rejected?`
;; (below) is shared with the entity create/update handlers.



;; Body / rollback split for create-record-type — body and rollback
;; share the same journal atom via wrap-time capture at the `:try`
;; call site (see `:_create-record-type-apply` in fns.edn). The body
;; throws on storage failure; rollback derefs the journal and
;; replays in reverse.


(defbase _apply-create-record-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-create-record-type-body parsed journal ctx))


(defbase _apply-create-record-type-rollback
  [journal exception]
  (cr/record-effect! :db)
  (entities/apply-create-rollback journal exception ctx))


;; Same body/rollback split for create-list-type — the rollback
;; defbase is shared with record-type (`:_apply-create-record-type-
;; rollback`) since the rollback logic is type-agnostic.


(defbase _apply-create-list-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-create-list-type-body parsed journal ctx))


;; `:_update-record-type-apply` is a graph `:try` fn-def in fns.edn
;; composing `:_apply-update-record-type-body` + `-rollback` over a
;; shared `:atom` journal. Each side is one thin defbase below.


(defbase _apply-update-record-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-update-record-type-body parsed journal ctx))


(defbase _apply-update-record-type-rollback
  [journal exception]
  (cr/record-effect! :db)
  (entities/apply-update-record-type-rollback journal exception ctx))


;; === Form data parsing base functions ===



;; === Action Handlers (context-aware) ===
;; `process-create-entity` / `process-update-entity` are graph fn-defs
;; (`web/crud` fns.edn) — an `:if` over `parse → validate`, branching
;; to a 400 or to the apply (write) stage. These base-fns are the
;; pipeline stages; `_rejected?` / `_rejection-response` are shared by
;; both handlers.



;; `:_create-write-rej` is a graph fn-def — see fns.edn — composing
;; over the `:write-rej` atomic primitive (the same the update chain
;; uses). The former `:_create-binding-type-rej` guard is gone
;; (error-tolerance Phase 2): type validity no longer gates the write.


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:json-to-type json-to-type-fn
   :to-set to-set-fn
   :subtype? subtype?-fn
   :fn-type? {:impl fn-type?-fn :taint-propagate? true}
   :fn-signature {:impl fn-signature :taint-propagate? true}
   :describe-type-mismatch describe-type-mismatch-fn
   :classify-literal classify-literal
   :diff-value-against-type diff-value-against-type
   :closed-enum-of closed-enum-of
   :fn-type-bound-effects fn-type-bound-effects
   :rich-type-of-name rich-type-of-name
   :rule-owner-of-name rule-owner-of-name
   :declarable-effect-categories declarable-effect-categories
   :type-name-kinds type-name-kinds
   :compatible-type-names compatible-type-names
   :_types-usages-apply _types-usages-apply
   :_apply-create-record-type-body _apply-create-record-type-body
   :_apply-create-record-type-rollback _apply-create-record-type-rollback
   :_apply-create-list-type-body _apply-create-list-type-body
   :_apply-update-record-type-body _apply-update-record-type-body
   :_apply-update-record-type-rollback _apply-update-record-type-rollback})
