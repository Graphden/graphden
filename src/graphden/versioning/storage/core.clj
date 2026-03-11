(ns graphden.versioning.storage.core
  "Storage decorator that adds Git-like versioning with branch support.

   Wraps any storage implementation with branch-aware CRUD:
   - Versioned entities (fn, arg) are intercepted: reads resolve versions
     on the current branch, writes append version records
   - Non-versioned entities (branch, branch-merge, all version tables)
     delegate directly to base storage
   - ExecutionGraph resolution works transparently via CRUD interception

   ## 2-Entity Schema

   Only two entities are versioned:
   - fn: function entity (parent-id for inheritance)
   - arg: argument entity (source-id for inheritance, value/ref-id for data)

   ## Usage

   (def base (-> (pg/create-storage config) (sp/initialize-with-cleanup! schema)))
   (def storage (vs/wrap-with-versioning base))

   ;; CRUD works like normal storage, but is branch-aware
   (sp/create-entity storage :fn {:name \"foo\" :parent-id id})

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


(defn- create-version-record!
  "Creates a version record in the version table for a versioned entity."
  [base-storage entity-name entity-id branch-id data]
  (let [{:keys [version-entity version-id-field version-data-fields]}
        (get res/entity-config entity-name)
        version-data (-> (select-keys data version-data-fields)
                         (assoc :id (random-uuid)
                                version-id-field entity-id
                                :branch-id branch-id
                                :created-at (now)))]
    (sp/create-entity base-storage version-entity version-data)))


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
      ;; Versioned: create full record in base table + version record
      (let [id (or (:id data) (random-uuid))
            full-data (assoc data :id id)]
        ;; Create base record if it doesn't exist (idempotent for deterministic UUIDs)
        (when-not (sp/read-entity base-storage entity-name id)
          (sp/create-entity base-storage entity-name full-data))
        ;; Create version record on current branch
        (create-version-record! base-storage entity-name id branch-id data)
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
      ;; Versioned: append new version with merged data
      (let [current (res/resolve-entity base-storage entity-name id branch-id)]
        (when-not current
          (throw (ex-info "Entity not found"
                          {:type :not-found
                           :entity-name entity-name
                           :id id})))
        (let [merged (merge current data)]
          (create-version-record! base-storage entity-name id branch-id merged)
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
      (res/resolve-all-entities base-storage entity-name branch-id where)))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/create-entities base-storage entity-name data-seq)
      ;; Versioned: batch create base records + batch create version records
      (let [data-with-ids (mapv (fn [data]
                                  (if (:id data) data (assoc data :id (random-uuid))))
                                data-seq)
            ids (mapv :id data-with-ids)
            ;; Find which base records don't exist yet
            existing-ids (set (keys (sp/read-entities base-storage entity-name ids)))
            new-base-records (filterv #(not (contains? existing-ids (:id %))) data-with-ids)
            ;; Batch create base records
            _ (when (seq new-base-records)
                (sp/create-entities base-storage entity-name new-base-records))
            ;; Prepare and batch create version records
            {:keys [version-entity version-id-field version-data-fields]}
            (get res/entity-config entity-name)
            version-records (mapv (fn [data]
                                    (-> (select-keys data version-data-fields)
                                        (assoc :id (random-uuid)
                                               version-id-field (:id data)
                                               :branch-id branch-id
                                               :created-at (now))))
                                  data-with-ids)]
        (sp/create-entities base-storage version-entity version-records)
        data-with-ids)))


  (read-entities
    [_ entity-name ids]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entities base-storage entity-name ids)
      ;; Batch resolve: load identity records, then batch resolve versions
      (let [identity-records (vals (sp/read-entities base-storage entity-name ids))]
        (res/resolve-entities-batch base-storage entity-name identity-records branch-id))))


  (update-entities
    [this entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/update-entities base-storage entity-name data-seq)
      (mapv #(sp/update-entity this entity-name (:id %) (dissoc % :id)) data-seq)))


  (upsert-entities
    [this entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/upsert-entities base-storage entity-name data-seq)
      ;; For versioned: batch check existence, then create/update accordingly
      (let [ids (keep :id data-seq)
            ;; Single batch query to check which IDs exist
            existing-ids (if (seq ids)
                           (set (keys (sp/read-entities this entity-name (vec ids))))
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
    [this entity-name ids]
    (if-not (res/versioned-entity? entity-name)
      (sp/delete-entities base-storage entity-name ids)
      (reduce (fn [cnt id]
                (if (sp/delete-entity this entity-name id) (inc cnt) cnt))
              0 ids)))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    (gc/validate-no-dependency-cycle! this owner-fn-id value-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; Use batch resolution: loads all data in 4 queries, then BFS in memory
    ;; This avoids the N+1 query problem (~400 queries → 4 queries)
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
   Returns true on success."
  [versioned-storage branch-id]
  (let [base (:base-storage versioned-storage)
        branch (sp/read-entity base :branch branch-id)]
    (when-not branch
      (throw (ex-info "Branch not found"
                      {:type :not-found :branch-id branch-id})))
    (when (= "main" (:name branch))
      (throw (ex-info "Cannot delete main branch"
                      {:type :constraint-violation :branch-id branch-id})))
    ;; Check no child branches
    (let [children (sp/query-entities base :branch {:base-branch-id branch-id})]
      (when (seq children)
        (throw (ex-info "Branch has child branches"
                        {:type :constraint-violation
                         :branch-id branch-id
                         :child-branch-ids (mapv :id children)}))))
    ;; Delete all version records on this branch (batch)
    (doseq [[_ {:keys [version-entity]}] res/entity-config]
      (let [version-ids (mapv :id (sp/query-entities base version-entity {:branch-id branch-id}))]
        (when (seq version-ids)
          (sp/delete-entities base version-entity version-ids))))
    ;; Delete branch-merge records referencing this branch (single query)
    ;; Query all branch-merge records and filter in memory (smaller table, avoids 2 queries)
    (let [all-merges (sp/query-entities base :branch-merge {})
          merge-ids (->> all-merges
                         (filter #(or (= (:source-branch-id %) branch-id)
                                      (= (:target-branch-id %) branch-id)))
                         (mapv :id))]
      (when (seq merge-ids)
        (sp/delete-entities base :branch-merge merge-ids)))
    ;; Delete the branch record
    (sp/delete-entity base :branch branch-id)
    true))
