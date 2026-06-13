(ns graphden.packages.app.secrets.impls
  "Impls for `app.secrets` endpoints. Each `defbase` is a thin shim
   that parses the JSON body and delegates to `graphden.crud.secrets`.
   The URL-based parsers (`:_delete-secret-parsed`,
   `:_rotate-secret-parsed`) are now graph fn-defs in fns.edn
   composing `:uri-segment-after` + `:parse-uuid` + `:parse-json-body`
   + `:zipmap` — no defbase shim needed."
  (:require
    [graphden.crud.secrets :as secrets]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


;; `:query-ref-many-owners` migrated to `web/crud/impls.clj`.


;; `:shape-secret` is now a graph fn-def — see fns.edn. Pure composition
;; over `:list-entities` (binding lookup) + `:vault-metadata-get` (under
;; `:try`) + `:zipmap` + `:merge`. Admins can vary the per-row shape by
;; reparenting `:_shape-secret-base` / `:_shape-secret-enrichment` —
;; no Clojure needed.


;; `:_list-secrets-data` is now a graph fn-def — see fns.edn.


;; --- C7 atoms: create-secret variant-2 decomposition.
;; `:_create-secret-data` is now a `:cond` graph fn-def in fns.edn
;; composing parse / leaf-lookup / four guard predicates / apply.
;; Lazy `:cond` — vault writes + graphden creates only happen when
;; every guard passes.

;; `:_create-secret-parsed` is now a graph fn-def — see fns.edn.


;; `:_create-secret-leaf-id` is now a graph fn-def — see fns.edn.
;; Composed over the new `:fn-names-with-tag` registry primitive +
;; `:list-entities :fn :where {:name <vec>}` + `:first` + `:get :id`
;; via the shared `:_secret-leaf-fn-id`.


;; `:_create-secret-name-blank?` / `-path-blank?` / `-value-missing?` /
;; `-leaf-missing?` are now graph fn-defs — see fns.edn.


;; `:_create-secret-name-taken?` is now a graph fn-def — see fns.edn.


;; `:_create-secret-apply` is now a graph `:try` over a shared `:atom`
;; journal — see fns.edn. Body is the §3.3 defbase below; rollback
;; reuses the shared `:_apply-secret-rollback`.
(defbase _apply-create-secret-body
  [parsed leaf-id journal]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/apply-create-secret-body parsed leaf-id journal ctx))


;; --- C11 atoms: create-inline-binding variant-2 decomposition.

;; `:_inline-bind-parsed` is now a graph fn-def — see fns.edn.


;; `:_inline-bind-fn-id-missing?` / `-slot-id-missing?` / `-path-blank?` /
;; `-value-missing?` / `-target-missing?` / `-exists?` are now graph
;; fn-defs — see fns.edn.

;; `:_inline-bind-target-fn-row` and `:_inline-bind-existing` are now
;; graph fn-defs — see fns.edn. The former is `:get-entity :fn :id`
;; (which handles nil-id internally); the latter is `:first` of
;; `:list-entities :binding {:fn-id :slot-id}`.


;; `:_inline-bind-apply` is now a graph `:try` over a shared `:atom`
;; journal — see fns.edn. Body and rollback are §3.3 defbases below.
(defbase _apply-inline-bind-body
  [parsed journal]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/apply-create-inline-binding-body parsed journal ctx))


(defbase _apply-secret-rollback
  "Shared on-throw branch for inline-bind + create-secret. Replays the
   journal in reverse (vault-delete / storage-delete by tag) and
   builds the rejection envelope."
  [journal exception]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/replay-secret-rollback! journal exception ctx))


;; --- C9 atoms: rotate-secret variant-2 decomposition.
;; Reuses `_delete-secret-fn-row` / `_delete-secret-leaf-id` /
;; the `_delete-secret-not-found?` / `_delete-secret-not-a-secret?`
;; guards by re-binding the `parsed` slot at the rotate cond — same
;; shape (both parsed values have `:fn-id` + `:fn-id-ref`).

;; `:_rotate-secret-parsed` is now a graph fn-def — see fns.edn.


;; `:_rotate-secret-value-missing?` is now a graph fn-def — see fns.edn.


;; `:_rotate-secret-path` is now a graph fn-def — see fns.edn.
;; Composes existing `:_list-secrets-leaf-id` + `:_list-secrets-path-
;; slot-id` reverse-lookups with `:list-entities :binding` + `:first`
;; + `:get :value`; gated on fn-row + secret-leaf-id both present.


;; `:_rotate-secret-path-missing?` is now a graph fn-def — see fns.edn.


;; `:_rotate-secret-apply` is now a graph fn-def — see fns.edn. Pure
;; composition over `:vault-put` (returns the version number) +
;; `:zipmap` (response shape) + `:to-str` (fn-id stringification).


;; --- C8 atoms: delete-secret variant-2 decomposition.

;; `:_delete-secret-parsed` is now a graph fn-def composing
;; `:uri-segment-after` + `:parse-uuid` + `:zipmap` — see fns.edn.


;; `:_delete-secret-fn-row` is now a graph fn-def — `:get-entity :fn :id`
;; pulled from the parsed `:fn-id` field.


;; `:_delete-secret-leaf-id` is now a graph fn-def — see fns.edn.
;; Same shared `:_secret-leaf-fn-id` composition the create / rotate
;; chains use.


;; `:_delete-secret-not-found?` is now a graph fn-def — see fns.edn.


;; `:_delete-secret-not-a-secret?` is now a graph fn-def composing
;; `:and :some? + :not :secret-fn?` — see fns.edn.


;; `:find-fn-usages` is now a graph fn-def — see fns.edn. Pure
;; composition over `:query-ref-many-owners` (parents) + 2
;; `:list-entities` reverse-ref scans (binding / list-item) +
;; per-source reason maps + `:merge`-precedence (binding < list-item
;; < parent, last-wins per Clojure :merge — reorder the `:maps`
;; vector on `:_find-fn-usages-reasons-merged` to vary the policy)
;; + name lookup over the merged fn-ids.

;; `:_delete-secret-usages` is now a graph fn-def — `:find-fn-usages`
;; pulled from the parsed `:fn-id` field.


;; `:_delete-secret-in-use?` is now a graph fn-def — see fns.edn.


;; `:_delete-secret-apply` is now a graph fn-def — see fns.edn.
;; Decomposes the orchestration into vault-cleanup + storage-cleanup
;; + response build. Vault-cleanup is best-effort (no rollback);
;; storage-cleanup atomically removes the binding + fn-row (its own
;; helper holds the multi-row contract).
(defbase _delete-secret-vault-cleanup
  "Best-effort vault delete. Suppresses failures internally — graph
   doesn't need a separate :try wrapper."
  [path]
  (cr/record-effect! :network)
  (secrets/delete-secret-vault-cleanup! path ctx))


(defbase _delete-secret-storage-cleanup
  "Delete the path-binding (if present) and the fn-row through
   `crud-entities/delete-entity` so the graph cache invalidates on
   each. Atomic helper (multi-row delete contract)."
  [parsed]
  (cr/record-effect! :db)
  (secrets/delete-secret-storage-cleanup! parsed ctx))


(def impls
  {:_apply-create-secret-body     _apply-create-secret-body
   :_delete-secret-vault-cleanup  _delete-secret-vault-cleanup
   :_delete-secret-storage-cleanup _delete-secret-storage-cleanup
   :_apply-inline-bind-body       _apply-inline-bind-body
   :_apply-secret-rollback        _apply-secret-rollback})
