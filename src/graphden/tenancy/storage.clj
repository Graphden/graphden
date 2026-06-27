(ns graphden.tenancy.storage
  "OrgScopedStorage — the tenancy addon's storage decorator (PLATFORM_PLAN
   §3.0). Stamps the current org (`tenancy.context/current-org`) on writes
   and filters reads to {current-org, public} — the §2.8 own-plus-public
   model, so platform packages stay visible inside every org while a
   tenant's own rows stay private.

   It wraps a base storage and delegates every NON-tenant protocol verbatim
   (Storage / Introspection / Constraints / Codec / ErrorClassifier /
   ExecutionGraph). Only the CRUD + batch-CRUD paths carry org logic, and
   only for entities in `scoped?` — platform/system entities (schema,
   executions, branches) are global.

   Placement: BENEATH versioning — `Versioned(OrgScoped(Postgres))` — so the
   branch-router's `vs/unwrap` lands on this layer and the tenant filter
   survives the unwrap (ADR §3.0 nuance 1). Postgres RLS is the
   belt-and-suspenders second layer (the decorator is the app-level filter;
   RLS is the can't-be-bypassed-by-a-raw-query backstop, incl. the batch
   update/delete own-guards this layer leaves to it)."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(def default-scoped-entities
  "Graph entities that carry a tenant. Everything else is global."
  #{:fn :slot :fn-slot :binding :binding-list-item})


(defn- visible?
  "A row is readable by the current org iff it belongs to that org or to
   the shared public org."
  [row]
  ;; `conj`, not `#{a b}` — the set literal throws on a duplicate key when
  ;; the current org IS the public org.
  (contains? (conj #{tc/public-org} (tc/current-org)) (:org-id row)))


(defn- own?
  "A row is writable by the current org iff it belongs to that org — public
   and other-org rows are read-only here (RLS enforces the same)."
  [row]
  (= (tc/current-org) (:org-id row)))


(defn- stamp
  [data]
  (assoc data :org-id (tc/current-org)))


(defrecord OrgScopedStorage
  [base scoped?]

  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (sp/create-entity base entity-name
                      (cond-> data (scoped? entity-name) stamp)))


  (read-entity
    [_ entity-name id]
    (let [row (sp/read-entity base entity-name id)]
      (if (and row (scoped? entity-name) (not (visible? row))) nil row)))


  (update-entity
    [_ entity-name id data]
    (if (scoped? entity-name)
      ;; Only own rows are writable, and a tenant can never be reassigned.
      (when (some-> (sp/read-entity base entity-name id) own?)
        (sp/update-entity base entity-name id (dissoc data :org-id)))
      (sp/update-entity base entity-name id data)))


  (delete-entity
    [_ entity-name id]
    (if (scoped? entity-name)
      (when (some-> (sp/read-entity base entity-name id) own?)
        (sp/delete-entity base entity-name id))
      (sp/delete-entity base entity-name id)))


  (query-entities
    [_ entity-name where]
    (cond-> (sp/query-entities base entity-name where)
      (scoped? entity-name) (->> (filterv visible?))))


  (query-entities
    [_ entity-name where opts]
    (cond-> (sp/query-entities base entity-name where opts)
      (scoped? entity-name) (->> (filterv visible?))))


  (query-latest-per-group
    [_ entity-name where group-cols]
    (cond-> (sp/query-latest-per-group base entity-name where group-cols)
      (scoped? entity-name) (->> (filterv visible?))))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (sp/create-entities base entity-name
                        (cond->> data-seq (scoped? entity-name) (mapv stamp))))


  (read-entities
    [_ entity-name ids]
    (cond-> (sp/read-entities base entity-name ids)
      (scoped? entity-name) (->> (filterv visible?))))


  (update-entities
    [_ entity-name data-seq]
    ;; Strip any org reassignment; batch own-guard is left to RLS.
    (sp/update-entities base entity-name
                        (cond->> data-seq
                          (scoped? entity-name) (mapv #(dissoc % :org-id)))))


  (upsert-entities
    [_ entity-name data-seq]
    (sp/upsert-entities base entity-name
                        (cond->> data-seq (scoped? entity-name) (mapv stamp))))


  (delete-entities
    [_ entity-name ids]
    (sp/delete-entities base entity-name ids))


  (query-ref-many-owners
    [_ entity-name field-name target-id]
    (sp/query-ref-many-owners base entity-name field-name target-id))


  sp/Storage

  (initialize [_ schema] (sp/initialize base schema))


  (close [_] (sp/close base))


  sp/StorageIntrospection

  (current-entities [_] (sp/current-entities base))


  (current-fields [_ entity-name] (sp/current-fields base entity-name))


  (current-enums [_] (sp/current-enums base))


  (current-enum-values [_ enum-name] (sp/current-enum-values base enum-name))


  (schema-metadata [_] (sp/schema-metadata base))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [_ owner-fn-id ref-fn-id]
    (sp/validate-no-dependency-cycle! base owner-fn-id ref-fn-id))


  sp/ConstraintHelpers

  (collect-dependency-chain
    [_ fn-id]
    (sp/collect-dependency-chain base fn-id))


  sp/StorageValueCodec

  (encode-value [_ value field-spec] (sp/encode-value base value field-spec))


  (decode-value [_ value field-spec] (sp/decode-value base value field-spec))


  (encode-row [_ row field-specs] (sp/encode-row base row field-specs))


  (decode-row [_ row field-specs] (sp/decode-row base row field-specs))


  sp/StorageErrorClassifier

  (classify-error [_ exception] (sp/classify-error base exception))


  (wrap-error
    [_ exception operation context]
    (sp/wrap-error base exception operation context))


  sp/ExecutionGraph

  (resolve-execution-graph [_ fn-id] (sp/resolve-execution-graph base fn-id)))


(defn org-scoped-storage
  "Wrap `base` so reads see {current-org, public} and writes stamp the
   current org. `scoped-entities` (default `default-scoped-entities`) is the
   set of entity names that carry a tenant."
  ([base] (org-scoped-storage base default-scoped-entities))
  ([base scoped-entities]
   (->OrgScopedStorage base (set scoped-entities))))
