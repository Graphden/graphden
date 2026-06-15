(ns graphden.versioning.storage.core
  "Storage decorator that adds Git-like versioning with branch support.

   Wraps any storage implementation with branch-aware CRUD:
   - Versioned entities (fn, fn-slot, binding, binding-list-item) are
     intercepted: reads resolve versions on the current branch, writes
     append version records
   - Non-versioned entities (slot, namespace, branch, branch-merge,
     all version tables) delegate directly to base storage
   - ExecutionGraph resolution works transparently via CRUD interception

   The authoritative versioned-entity list lives in
   `graphden.versioning.storage.resolution/entity-config`.

   ## Usage

   (def base (-> (pg/create-storage config) (sp/initialize-with-cleanup! schema)))
   (def storage (vs/wrap-with-versioning base))

   ;; CRUD works like normal storage, but is branch-aware
   (sp/create-entity storage :fn {:name \"foo\" :parent-ids [id]})

   ;; Create branch and switch
   (def branch (vs/create-branch! storage \"feature\"))
   (def feature (vs/switch-branch storage (:id branch)))

   ## Design

   Two-table pattern: the base entity table stores identity fields (id + immutable refs),
   the version table stores mutable data fields. VersionedStorage merges them on read.

   Composability: VersionedStorage has ZERO imports from cached-storage or cache-protocol.
   It is independently usable: VersionedStorage(BaseStorage) works without any cache.
   CachedStorage(VersionedStorage(BaseStorage)) works via simple stacking."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-constraints :as gc]
    [graphden.storage.protocol.graph :as graph]
    [graphden.versioning.storage.merge :as mrg]
    [graphden.versioning.storage.resolution :as res])
  (:import
    (java.time
      Instant)))


;; === Internal Helpers ===

(defn- now
  []
  (Instant/now))


(defn- prepare-version-record
  "Build a row for the version table from a versioned-entity payload:
   pull the version-data fields off `data`, stamp identity (`:id`,
   `<version-id-field>`, `:branch-id`, `:created-at`). Used by both
   single-entity and batch write paths."
  [version-config entity-id branch-id timestamp data]
  (let [{:keys [version-id-field version-data-fields]} version-config]
    (-> (select-keys data version-data-fields)
        (assoc :id (random-uuid)
               version-id-field entity-id
               :branch-id branch-id
               :created-at timestamp))))


(defn- create-version-record!
  "Creates a version record in the version table for a versioned entity."
  [base-storage entity-name entity-id branch-id data]
  (let [config (get res/entity-config entity-name)
        version-data (prepare-version-record config entity-id branch-id (now) data)]
    (sp/create-entity base-storage (:version-entity config) version-data)))


(defn- check-list-item-position-collision!
  "Per-branch resolved-view check: throw if another item-id resolves
   to the SAME `(binding-id, position)` on this branch. Replaces the
   pre-versioning base-table `UNIQUE (binding_id, position)` index
   (retired so cross-branch divergence isn't blocked) — the
   identity-row constraint mis-modelled the invariant; uniqueness is
   a per-branch resolved-view property, not a cross-branch one.

   Skips when `entity-name` isn't `:binding-list-item`. Cheap: one
   query against the version table on the branch chain, then in-memory
   resolve of just the items that COULD collide."
  [base-storage branch-id entity-name new-data]
  (when (= :binding-list-item entity-name)
    (let [{:keys [binding-id position id]} new-data]
      (when (and binding-id (some? position))
        (let [chain (#'res/collect-branch-chain base-storage branch-id)
              ;; Every item-version on the binding's chain. The
              ;; SQL WHERE narrows to the binding so we don't scan
              ;; the whole version table.
              versions (sp/query-entities base-storage :binding-list-item-version
                                          {:binding-id binding-id
                                           :branch-id (vec chain)})
              touched-ids (into #{} (map :item-id) versions)
              ;; Resolve each touched item on this branch — collision
              ;; rule applies to the LIVE view, not raw version rows.
              identity-records (vals (sp/read-entities base-storage
                                                       :binding-list-item
                                                       (vec touched-ids)))
              resolved (res/resolve-entities-batch base-storage
                                                   :binding-list-item
                                                   identity-records branch-id)
              touched-on-chain? (fn [eid]
                                  ;; resolve-entities-batch returns the bare
                                  ;; identity row for entities WITHOUT a
                                  ;; version on the chain (parity with the
                                  ;; executor's base-fn reads); filter those
                                  ;; out — only items that actually live on
                                  ;; this branch can collide.
                                  (contains? touched-ids eid))
              collisions (for [[item-id row] resolved
                               :when (and (some? row)
                                          (not= item-id id)
                                          (touched-on-chain? item-id)
                                          (= position (:position row)))]
                           item-id)]
          (when (seq collisions)
            (throw (ex-info (str "Position " position
                                 " is already taken in this binding on branch "
                                 branch-id)
                            {:type :constraint-violation/position-collision
                             :entity-name :binding-list-item
                             :binding-id binding-id
                             :position position
                             :branch-id branch-id
                             :colliding-item-ids (vec collisions)}))))))))


;; === VersionedStorage Record ===

(defrecord VersionedStorage
  [base-storage branch-id]

  sp/Storage

  (initialize
    [_ schema]
    (sp/initialize base-storage schema))


  (close
    [_]
    (sp/close base-storage))


  sp/StorageIntrospection

  (current-entities
    [_]
    (sp/current-entities base-storage))


  (current-fields
    [_ entity-name]
    (sp/current-fields base-storage entity-name))


  (current-enums
    [_]
    (sp/current-enums base-storage))


  (current-enum-values
    [_ enum-name]
    (sp/current-enum-values base-storage enum-name))


  (schema-metadata
    [_]
    (sp/schema-metadata base-storage))


  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (if-not (res/versioned-entity? entity-name)
      (sp/create-entity base-storage entity-name data)
      ;; Versioned: create full record in base table + version record.
      ;; When the identity row already exists (deterministic UUID re-sync),
      ;; resolve the current version directly from `existing` instead of
      ;; calling `resolve-entity` (which would re-fetch identity).
      (let [id (or (:id data) (random-uuid))
            full-data (assoc data :id id)
            _ (check-list-item-position-collision! base-storage branch-id
                                                   entity-name full-data)
            existing (sp/read-entity base-storage entity-name id)]
        (when-not existing
          (sp/create-entity base-storage entity-name full-data))
        (let [{:keys [version-id-field version-data-fields]}
              (get res/entity-config entity-name)
              current-version (when existing
                                (res/resolve-version base-storage entity-name id branch-id))
              current-data (when current-version
                             (select-keys (merge existing
                                                 (dissoc current-version
                                                         :id :branch-id :created-at
                                                         version-id-field))
                                          version-data-fields))
              new-data (select-keys full-data version-data-fields)]
          (when (or (nil? current-version) (not= current-data new-data))
            (create-version-record! base-storage entity-name id branch-id data)))
        full-data)))


  (read-entity
    [_ entity-name id]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entity base-storage entity-name id)
      (res/resolve-entity base-storage entity-name id branch-id)))


  (update-entity
    [_ entity-name id data]
    (if-not (res/versioned-entity? entity-name)
      (sp/update-entity base-storage entity-name id data)
      ;; Versioned: append new version with merged data (if changed)
      (let [current (res/resolve-entity base-storage entity-name id branch-id)]
        (when-not current
          (throw (ex-info "Entity not found"
                          {:type :not-found
                           :entity-name entity-name
                           :id id})))
        (let [merged (merge current data)
              _ (check-list-item-position-collision! base-storage branch-id
                                                     entity-name
                                                     (assoc merged :id id))
              ;; Only compare version-controlled fields, not :id
              {:keys [version-data-fields]} (get res/entity-config entity-name)
              current-data (select-keys current version-data-fields)
              merged-data (select-keys merged version-data-fields)
              ;; Non-versioned fields (e.g. :ref-many junction fields like
              ;; :parent-ids) live on the identity row / junction tables,
              ;; not in the version table. Updates to those have to be
              ;; flowed through to base storage explicitly — without this,
              ;; PUTs that change ONLY non-versioned fields are silent
              ;; no-ops because version-data-fields-only diffing returns
              ;; equal and create-version-record! is skipped.
              non-versioned-data (apply dissoc data version-data-fields)]
          ;; Skip creating new version if data unchanged
          (when (not= current-data merged-data)
            (create-version-record! base-storage entity-name id branch-id merged))
          ;; Apply non-versioned fields directly. base-storage's
          ;; update-entity handles columnar identity columns AND
          ;; ref-many junction replacement.
          (when (seq non-versioned-data)
            (sp/update-entity base-storage entity-name id non-versioned-data))
          merged))))


  (delete-entity
    [_ entity-name id]
    (if-not (res/versioned-entity? entity-name)
      (sp/delete-entity base-storage entity-name id)
      ;; Delete all version records for this entity on this branch only
      (let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
            versions (sp/query-entities base-storage version-entity
                                        {version-id-field id :branch-id branch-id})
            deleted (count versions)]
        (when (pos? deleted)
          (sp/delete-entities base-storage version-entity (mapv :id versions)))
        (pos? deleted))))


  (query-entities
    [_ entity-name where]
    (if-not (res/versioned-entity? entity-name)
      (sp/query-entities base-storage entity-name where)
      ;; Branch chain cache is process-wide (`global-chain-cache`)
      ;; — no per-call binding needed.
      (res/resolve-all-entities base-storage entity-name branch-id where)))


  (query-entities
    [this entity-name where opts]
    (cond
      (or (nil? opts) (empty? opts))
      (sp/query-entities this entity-name where)

      (res/versioned-entity? entity-name)
      ;; Versioned reads run a deduplicate-by-id resolver that
      ;; collapses N version rows into ≤ N base rows. Applying
      ;; ORDER BY / LIMIT at the SQL layer would happen BEFORE that
      ;; dedup and silently return the wrong page. Refuse rather
      ;; than mislead — callers that need pagination over a
      ;; versioned table should fetch + page in memory.
      (throw (ex-info (str "query-entities :opts not supported on versioned entity "
                           entity-name " — pagination must run after version resolution")
                      {:type :storage-error/unsupported-opts
                       :entity-name entity-name :opts opts}))

      :else
      (sp/query-entities base-storage entity-name where opts)))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/create-entities base-storage entity-name data-seq)
      ;; Versioned: batch create base records + batch create version records
      (let [data-with-ids (mapv (fn [data]
                                  (if (:id data) data (assoc data :id (random-uuid))))
                                data-seq)
            ;; Per-item position-collision check, mirroring the singular
            ;; create-entity. The check also catches INTRA-batch
            ;; duplicates implicitly: two items in the same batch
            ;; sharing `(binding-id, position)` will both pass the
            ;; against-storage check (neither is committed yet), but
            ;; the duplicate-position-within-data-seq check below
            ;; rejects upfront so batch import doesn't silently land
            ;; in a broken state.
            _ (doseq [d data-with-ids]
                (check-list-item-position-collision! base-storage branch-id
                                                     entity-name d))
            _ (when (= :binding-list-item entity-name)
                (let [by-key (group-by (juxt :binding-id :position) data-with-ids)
                      dupe (some (fn [[k items]]
                                   (when (and (some? (first k))
                                              (some? (second k))
                                              (> (count items) 1))
                                     [k items]))
                                 by-key)]
                  (when dupe
                    (throw (ex-info "Batch contains items with duplicate (binding-id, position)"
                                    {:type :constraint-violation/position-collision
                                     :entity-name :binding-list-item
                                     :binding-id (ffirst dupe)
                                     :position (second (first dupe))
                                     :colliding-item-ids (mapv :id (second dupe))})))))
            ids (mapv :id data-with-ids)
            ;; Find which base records don't exist yet
            existing-ids (set (keys (sp/read-entities base-storage entity-name ids)))
            new-base-records (vec (remove #(contains? existing-ids (:id %)) data-with-ids))
            ;; Batch create base records
            _ (when (seq new-base-records)
                (sp/create-entities base-storage entity-name new-base-records))
            ;; Prepare and batch create version records
            config (get res/entity-config entity-name)
            timestamp (now)
            version-records (mapv #(prepare-version-record config (:id %) branch-id
                                                           timestamp %)
                                  data-with-ids)]
        (sp/create-entities base-storage (:version-entity config) version-records)
        data-with-ids)))


  (read-entities
    [_ entity-name ids]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entities base-storage entity-name ids)
      ;; Batch resolve: load identity records, then batch resolve versions
      (let [identity-records (vals (sp/read-entities base-storage entity-name ids))]
        (res/resolve-entities-batch base-storage entity-name identity-records branch-id))))


  (update-entities
    [_ entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/update-entities base-storage entity-name data-seq)
      ;; Batch update: resolve all current versions, compute diffs, batch create versions
      (let [ids (mapv :id data-seq)
            ;; Batch resolve current versions using efficient batch resolution
            identity-records (vals (sp/read-entities base-storage entity-name ids))
            current-versions (res/resolve-entities-batch base-storage entity-name
                                                         identity-records branch-id)
            current-by-id (into {} (map (fn [e] [(:id e) e])) (vals current-versions))
            ;; Check all entities exist
            missing-ids (remove #(contains? current-by-id %) ids)]
        (when (seq missing-ids)
          (throw (ex-info "Entities not found"
                          {:type :not-found
                           :entity-name entity-name
                           :missing-ids (vec missing-ids)})))
        ;; Per-item position-collision check, mirroring the singular
        ;; update-entity. Uses the merged shape (current + incoming
        ;; partial update) so a position-only update is checked against
        ;; the rest of the binding's items on the chain.
        (doseq [data data-seq
                :let [id (:id data)
                      current (get current-by-id id)
                      merged (merge current data)]
                :when current]
          (check-list-item-position-collision! base-storage branch-id
                                               entity-name
                                               (assoc merged :id id)))
        ;; Compute merged versions and filter to only changed ones
        (let [{:keys [version-entity version-data-fields] :as config}
              (get res/entity-config entity-name)
              timestamp (now)
              ;; Build version records only for changed entities
              version-records
              (into []
                    (keep (fn [data]
                            (let [id (:id data)
                                  current (get current-by-id id)
                                  merged (merge current data)
                                  current-data (select-keys current version-data-fields)
                                  merged-data (select-keys merged version-data-fields)]
                              (when (not= current-data merged-data)
                                (prepare-version-record config id branch-id
                                                        timestamp merged)))))
                    data-seq)]
          ;; Batch create all version records
          (when (seq version-records)
            (sp/create-entities base-storage version-entity version-records))
          ;; Ref-many junctions (notably fn `:parent-ids`) and other
          ;; non-versioned columns live on the base identity row, not in
          ;; a version record — the diff above only covers
          ;; `version-data-fields`. Flow every non-versioned change
          ;; through to base storage, whose singular `update-entity`
          ;; merges with the existing row (filling NOT-NULL columns) and
          ;; reconciles ref-many junctions. Without this a base-fn →
          ;; composed-fn parent change is silently dropped. Mirrors the
          ;; singular `update-entity` above; guarded so an unchanged
          ;; re-sync stays a no-op.
          (doseq [data data-seq
                  :let [id (:id data)
                        non-versioned (apply dissoc data :id version-data-fields)
                        current (get current-by-id id)]
                  :when (and (seq non-versioned)
                             (not= non-versioned
                                   (select-keys current (keys non-versioned))))]
            (sp/update-entity base-storage entity-name id non-versioned))
          ;; Return merged data for all records (including unchanged)
          (mapv (fn [data]
                  (merge (get current-by-id (:id data)) data))
                data-seq)))))


  (upsert-entities
    [this entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/upsert-entities base-storage entity-name data-seq)
      ;; For versioned: batch check existence in BASE storage (no version resolution),
      ;; then create/update accordingly
      (let [ids (keep :id data-seq)
            ;; Check existence in base-storage directly - O(n) not O(n × versions)
            ;; We only need to know if identity record exists, not resolve versions
            existing-ids (if (seq ids)
                           (set (keys (sp/read-entities base-storage entity-name (vec ids))))
                           #{})
            {to-update true to-create false}
            (group-by #(contains? existing-ids (:id %)) data-seq)]
        ;; Batch create new records
        (when (seq to-create)
          (sp/create-entities this entity-name to-create))
        ;; Batch update existing records
        (when (seq to-update)
          (sp/update-entities this entity-name to-update))
        ;; Return all records
        (vec data-seq))))


  (delete-entities
    [_ entity-name ids]
    (if-not (res/versioned-entity? entity-name)
      (sp/delete-entities base-storage entity-name ids)
      ;; Batch delete: single query to find all versions, single batch delete
      (let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
            ;; Single WHERE IN query for all entity versions on this branch
            all-versions (sp/query-entities base-storage version-entity
                                            {version-id-field (vec ids)
                                             :branch-id branch-id})
            version-ids (mapv :id all-versions)
            ;; Count unique entity-ids that had versions (for return value)
            deleted-entity-ids (into #{} (map version-id-field) all-versions)]
        (when (seq version-ids)
          (sp/delete-entities base-storage version-entity version-ids))
        (count deleted-entity-ids))))


  (query-ref-many-owners
    [_ entity-name field-name target-id]
    ;; Junction tables are NOT versioned (the model versions :parent-ids
    ;; only via fn re-creation, not a separate junction-version table).
    ;; Pass straight through to the base storage.
    (sp/query-ref-many-owners base-storage entity-name field-name target-id))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    (gc/validate-no-dependency-cycle! this owner-fn-id value-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; Batch resolution loads all data in 4 queries + BFS in memory
    ;; (avoiding the N+1 ~400-query path). Chain hierarchy lookups
    ;; consult the process-wide `global-chain-cache` directly.
    (let [result (res/resolve-execution-graph-batch base-storage fn-id branch-id)]
      (when (empty? (:fns result))
        (throw (ex-info "Function not found"
                        {:type :not-found
                         :fn-id fn-id})))
      (graph/->execution-graph result))))


;; === Branch Operations ===

(defn- ensure-main-branch!
  "Creates the 'main' branch if it doesn't exist. Returns its id."
  [base-storage]
  (let [existing (sp/query-entities base-storage :branch {:name "main"})]
    (if (seq existing)
      (:id (first existing))
      (let [id (random-uuid)]
        (sp/create-entity base-storage :branch
                          {:id id
                           :name "main"
                           :base-branch-id nil
                           :created-at (now)})
        id))))


(defn wrap-with-versioning
  "Wraps a storage with versioning support.

   Creates a 'main' branch if it doesn't exist.
   Returns a VersionedStorage on the main branch.

   Arguments:
   - base-storage: Any storage implementing Storage protocols, initialized with
     versioned-data-schema (or compatible schema that includes version entities)

   Example:
   (def schema (vds/build-schema (mds/create-builder)))
   (def base (-> (pg/create-storage config) (sp/initialize-with-cleanup! schema)))
   (def storage (wrap-with-versioning base))"
  ([base-storage]
   (wrap-with-versioning base-storage "main"))
  ([base-storage branch-name]
   (let [branch-id (if (= branch-name "main")
                     (ensure-main-branch! base-storage)
                     (let [existing (sp/query-entities base-storage :branch
                                                       {:name branch-name})]
                       (if (seq existing)
                         (:id (first existing))
                         (throw (ex-info "Branch not found"
                                         {:type :not-found
                                          :branch-name branch-name})))))]
     (->VersionedStorage base-storage branch-id))))


(defn create-branch!
  "Creates a new branch forked from the current branch (or specified base).
   Returns the branch record.

   Arguments:
   - versioned-storage: VersionedStorage instance
   - branch-name: Name for the new branch (must be unique)
   - opts: Optional map with :base-branch-id to fork from a different branch"
  ([versioned-storage branch-name]
   (create-branch! versioned-storage branch-name {}))
  ([versioned-storage branch-name {:keys [base-branch-id]}]
   (let [parent-id (or base-branch-id (:branch-id versioned-storage))]
     (sp/create-entity (:base-storage versioned-storage) :branch
                       {:id (random-uuid)
                        :name branch-name
                        :base-branch-id parent-id
                        :created-at (now)}))))


(defn switch-branch
  "Returns a new VersionedStorage pointing to the specified branch.
   Does NOT copy data. Just changes the branch context."
  [versioned-storage branch-id]
  (when-not (sp/read-entity (:base-storage versioned-storage) :branch branch-id)
    (throw (ex-info "Branch not found"
                    {:type :not-found
                     :branch-id branch-id})))
  (->VersionedStorage (:base-storage versioned-storage) branch-id))


(defn list-branches
  "Lists all branches."
  [versioned-storage]
  (sp/query-entities (:base-storage versioned-storage) :branch {}))


(defn get-branch
  "Returns branch record by id, or nil."
  [versioned-storage branch-id]
  (sp/read-entity (:base-storage versioned-storage) :branch branch-id))


(defn current-branch-id
  "Returns the current branch-id."
  [versioned-storage]
  (:branch-id versioned-storage))


(defn versioned-storage?
  "Returns true if storage is a VersionedStorage wrapper."
  [storage]
  (instance? VersionedStorage storage))


(defn unwrap
  "Returns the base storage from a VersionedStorage wrapper.
   Returns the storage unchanged if it's not a VersionedStorage."
  [storage]
  (if (versioned-storage? storage)
    (:base-storage storage)
    storage))


;; === Merge Operations ===

(defn detect-conflicts
  "Finds entities modified in both source and target branches after fork point.

   Returns a map:
   {:conflicts [{:entity-name :fn
                 :entity-id uuid
                 :source-version <resolved version data>
                 :target-version <resolved version data>}]
    :fork-point <Instant>}"
  [versioned-storage source-branch-id]
  (mrg/detect-conflicts (:base-storage versioned-storage)
                        source-branch-id (:branch-id versioned-storage)))


(defn merge-branch!
  "Merges source branch into the current (target) branch.

   Creates a branch-merge record that makes source versions visible on target
   via the resolution algorithm. No version records are copied.

   If conflicts exist, throws unless conflict-resolutions are provided.

   conflict-resolutions: {[entity-name entity-id] :source | :target}
   - :source — keep source branch version
   - :target — keep target branch version

   Returns the branch-merge record."
  ([versioned-storage source-branch-id]
   (mrg/merge-branch! versioned-storage source-branch-id))
  ([versioned-storage source-branch-id opts]
   (mrg/merge-branch! versioned-storage source-branch-id opts)))


;; === Delete Branch ===

(defn delete-branch!
  "Deletes a branch and all its version records.
   Throws if branch has child branches or if trying to delete main branch.
   Returns true on success.

   Cascade: any `:service` row scoped to this branch is soft-disabled
   (`:enabled? false`) BEFORE the branch row goes away. The next
   reconcile pass picks the row up via the standard diff-desired path,
   stops the running closure, and releases its advisory lock. We don't
   hard-delete the rows because they form part of the audit trail and
   may be wanted for `branch-restore` (planned)."
  [versioned-storage branch-id]
  (let [base (:base-storage versioned-storage)
        branch (sp/read-entity base :branch branch-id)]
    (when-not branch
      (throw (ex-info "Branch not found"
                      {:type :not-found :branch-id branch-id})))
    (when (= "main" (:name branch))
      (throw (ex-info "Cannot delete main branch"
                      {:type :constraint-violation/main-branch-undeletable :branch-id branch-id})))
    ;; Check no child branches
    (let [children (sp/query-entities base :branch {:base-branch-id branch-id})]
      (when (seq children)
        (throw (ex-info "Branch has child branches"
                        {:type :constraint-violation/branch-has-children
                         :branch-id branch-id
                         :child-branch-ids (mapv :id children)}))))
    ;; Soft-disable services scoped to this branch so the reconciler
    ;; stops them on its next pass — see the docstring's cascade note.
    ;; The `:service` entity is only registered when the services
    ;; schema is loaded (production system + integration tests);
    ;; storage-only tests use a smaller schema that omits it. Skip
    ;; the cascade quietly in that case rather than throwing
    ;; `:table-not-found` on every branch delete.
    (when (contains? (sp/current-entities base) :service)
      (doseq [svc (sp/query-entities base :service {:branch-id branch-id
                                                    :enabled? true})]
        (sp/update-entity base :service (:id svc) {:enabled? false})))
    ;; Delete all version records on this branch (batch)
    (doseq [[_ {:keys [version-entity]}] res/entity-config]
      (let [version-ids (mapv :id (sp/query-entities base version-entity {:branch-id branch-id}))]
        (when (seq version-ids)
          (sp/delete-entities base version-entity version-ids))))
    ;; Delete branch-merge records referencing this branch
    ;; Two targeted queries are more efficient than full table scan + memory filter
    (let [source-merges (sp/query-entities base :branch-merge {:source-branch-id branch-id})
          target-merges (sp/query-entities base :branch-merge {:target-branch-id branch-id})
          merge-ids (into [] (comp (map :id) (distinct))
                          (concat source-merges target-merges))]
      (when (seq merge-ids)
        (sp/delete-entities base :branch-merge merge-ids)))
    ;; Delete the branch record
    (sp/delete-entity base :branch branch-id)
    ;; Drop any cached chain that referenced this branch as an
    ;; ancestor — globals survive across CRUD calls and would
    ;; otherwise still hand back the pre-delete chain.
    (res/invalidate-chain-cache! branch-id)
    true))


(defn query-all-graph-entities
  "Loads every entity of the slot/fn-slot/binding model with a shared
   branch-chain cache. More efficient than calling `query-entities`
   five times sequentially.

   Returns {:fns [...] :slots [...] :fn-slots [...] :bindings [...]
            :list-items [...]}."
  [versioned-storage]
  (let [{:keys [base-storage branch-id]} versioned-storage]
    ;; Chain cache is process-wide; no per-call binding required.
    {:fns        (vec (res/resolve-all-entities base-storage :fn branch-id {}))
     :slots      (vec (sp/query-entities base-storage :slot {}))
     :fn-slots   (vec (res/resolve-all-entities base-storage :fn-slot branch-id {}))
     :bindings   (vec (res/resolve-all-entities base-storage :binding branch-id {}))
     :list-items (vec (res/resolve-all-entities base-storage :binding-list-item
                                                branch-id {}))}))
