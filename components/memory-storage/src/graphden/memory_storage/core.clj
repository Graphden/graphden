(ns graphden.memory-storage.core
  "In-memory implementation of Storage protocol."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Internal helpers ===


(defn- build-entities-structure
  "Builds :entities structure from DataSchema."
  [schema]
  (into {}
        (for [entity-name (ds/entities schema)]
          [entity-name
           {:fields (into {}
                          (for [[field-name field-spec] (ds/entity-fields schema entity-name)]
                            [field-name {:type (:type field-spec)
                                         :nullable? (get field-spec :nullable? false)}]))
            :constraints (ds/entity-constraints schema entity-name)}])))


(defn- build-enums-structure
  "Builds :enums structure from DataSchema."
  [schema]
  (into {}
        (for [[enum-name {:keys [values]}] (ds/enums schema)]
          [enum-name {:values (set (keys values))}])))


(defn- check-single-field-change
  "Checks a single field for type/nullable changes. Throws on unsafe change."
  [entity-name field-name field-spec old-fields old-metadata]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (let [old-field-name (:field old-field-info)
            old-type (get-in old-fields [old-field-name :type])
            new-type (:type field-spec)
            old-nullable? (get-in old-fields [old-field-name :nullable?])
            new-nullable? (get field-spec :nullable? false)]
        (sp/check-type-change! entity-name field-name old-type new-type)
        (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?)))))


(defn- check-entity-type-changes
  "Checks all field type changes for a single entity. Throws on unsafe change."
  [entity-name old-state old-metadata schema]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)
        old-fields (get-in old-state [:entities old-entity-name :fields])]
    (when old-fields
      (run! (fn [[field-name field-spec]]
              (check-single-field-change entity-name field-name field-spec old-fields old-metadata))
            (ds/entity-fields schema entity-name)))))


(defn- check-type-changes
  "Checks that all field type changes are safe. Throws on unsafe change."
  [old-state old-metadata schema]
  (run! #(check-entity-type-changes % old-state old-metadata schema)
        (ds/entities schema)))


(defn- rename-row-fields
  "Renames fields in a single row using the renames map."
  [renames row]
  (persistent!
    (reduce-kv (fn [acc k v]
                 (assoc! acc (get renames k k) v))
               (transient {})
               row)))


(defn- rename-entity-rows
  "Renames fields in all rows of an entity."
  [renames entity-data]
  (persistent!
    (reduce-kv (fn [acc row-id row]
                 (assoc! acc row-id (rename-row-fields renames row)))
               (transient {})
               entity-data)))


(defn- migrate-data
  "Migrates existing data when entities/fields are renamed.
   Uses transients for O(n) performance instead of O(n²)."
  [old-data old-metadata schema]
  (let [entity-uuid->old-name (:entities old-metadata)
        entity-uuid->new-name (into {}
                                    (map (fn [entity-name]
                                           [(ds/entity-uuid schema entity-name) entity-name])
                                         (ds/entities schema)))
        field-uuid->old-info (:fields old-metadata)
        ;; Build field renames per entity
        field-renames (reduce (fn [acc entity-name]
                                (let [entity-uuid (ds/entity-uuid schema entity-name)
                                      old-entity-name (get entity-uuid->old-name entity-uuid)]
                                  (if-not old-entity-name
                                    acc
                                    (let [renames (reduce (fn [racc [field-name field-spec]]
                                                            (let [field-uuid (:uuid field-spec)
                                                                  old-info (get field-uuid->old-info field-uuid)]
                                                              (if (and old-info (not= (:field old-info) field-name))
                                                                (assoc racc (:field old-info) field-name)
                                                                racc)))
                                                          {}
                                                          (ds/entity-fields schema entity-name))]
                                      (assoc acc entity-uuid renames)))))
                              {}
                              (ds/entities schema))]
    (reduce-kv (fn [acc entity-uuid entity-new-name]
                 (let [old-entity-name (get entity-uuid->old-name entity-uuid)
                       entity-data (get old-data old-entity-name)]
                   (if-not entity-data
                     acc
                     (let [renames (get field-renames entity-uuid {})]
                       (assoc acc entity-new-name
                              (if (empty? renames)
                                entity-data
                                (rename-entity-rows renames entity-data)))))))
               {}
               entity-uuid->new-name)))


;; === CRUD helpers ===

(defn- validate-required-fields!
  "Validates that all required (non-nullable) fields are present and not nil.
   Uses shared utility from storage-protocol."
  [state entity-name data]
  (let [fields (get-in state [:entities entity-name :fields])]
    (sp/validate-required-fields! entity-name fields data)))


(defn- get-entity-data
  "Gets all records for an entity from state."
  [state entity-name]
  (get-in state [:data entity-name] {}))


(defn- find-conflicting-record
  "Finds the first record that conflicts with new-values for the given fields.
   Returns the conflicting record or nil."
  [records exclude-id fields new-values]
  (some (fn [record]
          (when (and (not= (:id record) exclude-id)
                     (= new-values (mapv #(get record %) fields)))
            record))
        records))


(defn- validate-unique-constraints!
  "Validates unique constraints for an entity.
   Checks that the data doesn't violate any unique constraints.
   exclude-id: optional id to exclude from check (for updates).

   NULL handling follows PostgreSQL semantics: NULL values are not considered
   equal to each other for uniqueness purposes. If ANY field in a unique
   constraint is NULL, the constraint check is skipped (line with `every? some?`).
   This means multiple rows can have NULL in unique constraint fields."
  [state entity-name data exclude-id]
  (let [constraints (get-in state [:entities entity-name :constraints])
        existing-records (vals (get-entity-data state entity-name))]
    (doseq [{:keys [fields] :as constraint} constraints
            :when (= (:type constraint) :unique)
            :let [new-values (mapv #(get data %) fields)]
            :when (every? some? new-values)
            :when (find-conflicting-record existing-records exclude-id fields new-values)]
      (log/warn "Unique constraint violation" {:entity entity-name :fields fields :values new-values})
      (throw (ex-info "Unique constraint violation"
                      {:type :constraint-violation/unique
                       :entity entity-name
                       :fields fields
                       :values new-values})))))


(defn- validate-entity-exists!
  "Validates that entity exists in schema. Throws :entity-not-in-schema if not.
   Note: postgres-storage uses :table-not-found (PostgreSQL-specific term),
   while memory-storage uses :entity-not-in-schema (storage-agnostic term)."
  [state entity-name]
  (when-not (contains? (:entities state) entity-name)
    (throw (ex-info "Entity not found in schema"
                    {:type :entity-not-in-schema
                     :entity entity-name}))))


(defn- get-record
  "Gets a single record by id."
  [state entity-name id]
  (get-in state [:data entity-name id]))


(defn- create-record-atomic!
  "Atomically validates and creates a record. Returns the record.
   Validation happens inside swap! to prevent race conditions."
  [state-atom entity-name record]
  (swap! state-atom
         (fn [state]
           (validate-required-fields! state entity-name record)
           (validate-unique-constraints! state entity-name record nil)
           (assoc-in state [:data entity-name (:id record)] record)))
  record)


(defn- update-record-atomic!
  "Atomically validates and updates a record. Returns the updated record.
   Validation happens inside swap! to prevent race conditions.
   Uses swap-vals! to safely extract result without nested atoms."
  [state-atom entity-name id updated-record]
  (let [[_old-state new-state]
        (swap-vals! state-atom
                    (fn [state]
                      (let [existing (get-record state entity-name id)]
                        (when-not existing
                          (throw (ex-info "Entity not found"
                                          {:type :not-found
                                           :entity entity-name
                                           :id id})))
                        (let [merged (merge existing updated-record {:id id})]
                          (validate-required-fields! state entity-name merged)
                          (validate-unique-constraints! state entity-name merged id)
                          (assoc-in state [:data entity-name id] merged)))))]
    ;; Extract updated record from new state
    (get-in new-state [:data entity-name id])))


(defn- remove-record!
  "Removes a record from state atom. Returns true if existed."
  [state-atom entity-name id]
  (let [existed? (some? (get-in @state-atom [:data entity-name id]))]
    (swap! state-atom update-in [:data entity-name] dissoc id)
    existed?))


;; === Batch CRUD helpers ===

(defn- create-records-atomic!
  "Atomically validates and creates multiple records. Returns sequence of records.
   All validations happen inside swap! to ensure atomicity.
   On validation failure, logs which record (by index) failed and includes
   batch context in the exception data."
  [state-atom entity-name records]
  (swap! state-atom
         (fn [state]
           (first
             (reduce (fn [[s idx] record]
                       (try
                         (validate-required-fields! s entity-name record)
                         (validate-unique-constraints! s entity-name record nil)
                         [(assoc-in s [:data entity-name (:id record)] record) (inc idx)]
                         (catch clojure.lang.ExceptionInfo e
                           (log/error "Batch create failed at record index" idx
                                      {:entity entity-name :record-id (:id record)})
                           (throw (ex-info (ex-message e)
                                           (assoc (ex-data e)
                                                  :batch-index idx
                                                  :batch-size (count records))
                                           e)))))
                     [state 0]
                     records))))
  records)


(defn- read-records
  "Reads multiple records by ids. Returns {id -> record} for found records."
  [state entity-name ids]
  (let [entity-data (get-entity-data state entity-name)]
    (->> ids
         (keep (fn [id]
                 (when-let [record (get entity-data id)]
                   [id record])))
         (into {}))))


(defn- remove-records!
  "Removes multiple records from state atom. Returns count of removed.
   ids must be a sequential collection (list, vector, etc.) or nil."
  [state-atom entity-name ids]
  (when (and (some? ids) (not (sequential? ids)))
    (throw (ex-info "ids must be a sequential collection or nil"
                    {:type :invalid-args
                     :entity-name entity-name
                     :ids ids
                     :ids-type (type ids)})))
  (if (empty? ids)
    0
    (let [ids-set (set ids)
          existing-ids (set (keys (get-entity-data @state-atom entity-name)))
          to-remove (set/intersection ids-set existing-ids)
          removed-count (count to-remove)]
      (swap! state-atom update-in [:data entity-name]
             (fn [data] (apply dissoc data to-remove)))
      removed-count)))


;; === ConstraintHelpers implementation for Memory storage ===

(defrecord MemoryConstraintHelpers
  [state-atom]

  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (:fn-schema-id (get-record @state-atom :fn fn-id)))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (:fn-schema-id (get-record @state-atom :arg-schema arg-schema-id)))


  (get-parent-fn-id
    [_this fn-id]
    (:parent-fn-id (get-record @state-atom :fn fn-id)))


  (collect-parent-chain
    [this fn-id]
    (sp/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [this fn-id]
    (let [ancestor-ids (sp/collect-parent-chain this fn-id)]
      (->> (get-entity-data @state-atom :arg-value)
           (vals)
           (filter #(contains? ancestor-ids (:owner-fn-id %)))
           (map :arg-schema-id)
           (set))))


  (collect-dependency-chain
    [_this owner-fn-id]
    (let [state @state-atom
          ;; Build index: owner-fn-id -> [arg-values...]
          ;; This changes O(N*M) to O(N+M) where N=fns, M=arg-values
          arg-values-by-owner (group-by :owner-fn-id (vals (get-entity-data state :arg-value)))
          fns-data (get-entity-data state :fn)]
      (loop [to-visit [owner-fn-id]
             visited #{}
             iter-count 0]
        ;; Check iteration limit to prevent infinite loops
        (sp/check-graph-iteration-limit! iter-count owner-fn-id)
        (if (empty? to-visit)
          visited
          (let [current-id (first to-visit)
                rest-to-visit (rest to-visit)]
            (if (contains? visited current-id)
              (recur rest-to-visit visited (inc iter-count))
              (let [arg-values (get arg-values-by-owner current-id [])
                    ;; Get fn references from arg-values (UUIDs that are fn refs)
                    ref-fn-ids (->> arg-values
                                    (map :value)
                                    (filter uuid?)
                                    ;; Check if this UUID is actually a fn
                                    (filter #(contains? fns-data %)))]
                (recur (concat rest-to-visit ref-fn-ids)
                       (conj visited current-id)
                       (inc iter-count))))))))))


;; === ExecutionGraph helpers ===

(defn- collect-fn-parent-chain
  "Collects all fn-ids in the parent chain (including the fn itself)."
  [state fn-id]
  (loop [current-id fn-id
         chain []]
    (if-not current-id
      chain
      (let [fn-rec (get-record state :fn current-id)]
        (recur (:parent-fn-id fn-rec) (conj chain current-id))))))


(defn- merge-arg-values-from-chain
  "Merges arg-values from parent chain, child values override parent.
   Requires pre-built arg-values-by-owner index for O(N+M) performance.
   Returns {arg-schema-id -> arg-value-record}."
  [state fn-id arg-values-by-owner]
  (let [chain (collect-fn-parent-chain state fn-id)]
    ;; Process from root to leaf so child overrides parent
    (reduce (fn [acc chain-fn-id]
              (let [arg-values (get arg-values-by-owner chain-fn-id [])]
                (reduce (fn [a av]
                          (assoc a (:arg-schema-id av) av))
                        acc
                        arg-values)))
            {}
            (reverse chain))))


(defn- extract-fn-refs-from-arg-values
  "Extracts fn-ids referenced in arg-values."
  [arg-values-map]
  (->> (vals arg-values-map)
       (map :value)
       (filter uuid?)
       (set)))


(defn- resolve-execution-graph-impl
  "Resolves execution graph starting from fn-id.
   Uses BFS to collect all transitively referenced functions.
   Builds indexes once for O(N+M) performance instead of O(N*M).
   Throws if iteration count exceeds sp/*max-graph-iterations*."
  [state fn-id]
  ;; Build indexes once for efficient lookups
  (let [arg-values-by-owner (group-by :owner-fn-id (vals (get-entity-data state :arg-value)))
        arg-schemas-by-fn-schema (group-by :fn-schema-id (vals (get-entity-data state :arg-schema)))]
    (loop [to-visit #{fn-id}
           visited #{}
           fns {}
           fn-schemas {}
           arg-schemas {}
           resolved-args {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        {:fns fns
         :fn-schemas fn-schemas
         :arg-schemas arg-schemas
         :resolved-args resolved-args}
        (let [current-fn-id (first to-visit)
              rest-to-visit (disj to-visit current-fn-id)]
          (if (contains? visited current-fn-id)
            (recur rest-to-visit visited fns fn-schemas arg-schemas resolved-args
                   (inc iter-count))
            (let [fn-rec (get-record state :fn current-fn-id)]
              (if-not fn-rec
                ;; fn doesn't exist, skip (might be literal value that looks like UUID)
                (recur rest-to-visit (conj visited current-fn-id)
                       fns fn-schemas arg-schemas resolved-args
                       (inc iter-count))
                (let [fn-schema-id (:fn-schema-id fn-rec)
                      fn-schema (get-record state :fn-schema fn-schema-id)
                      ;; Get arg-schemas for this fn-schema using pre-built index
                      new-arg-schemas (if (contains? fn-schemas fn-schema-id)
                                        {}
                                        (->> (get arg-schemas-by-fn-schema fn-schema-id [])
                                             (map (juxt :id identity))
                                             (into {})))
                      ;; Merge arg-values from parent chain using pre-built index
                      merged-args (merge-arg-values-from-chain state current-fn-id arg-values-by-owner)
                      ;; Find referenced fns
                      ref-fn-ids (extract-fn-refs-from-arg-values merged-args)
                      new-to-visit (set/difference ref-fn-ids visited)]
                  (recur (set/union rest-to-visit new-to-visit)
                         (conj visited current-fn-id)
                         (assoc fns current-fn-id fn-rec)
                         (if fn-schema
                           (assoc fn-schemas fn-schema-id fn-schema)
                           fn-schemas)
                         (merge arg-schemas new-arg-schemas)
                         (assoc resolved-args current-fn-id merged-args)
                         (inc iter-count)))))))))))


(defn- do-initialize
  "Performs initialization, returns [new-state changes]."
  [state schema]
  (let [old-state @state
        old-metadata (:metadata old-state)]
    (if (nil? old-metadata)
      ;; First-time initialization
      (let [new-metadata (sp/build-metadata-from-schema schema)
            new-entities (build-entities-structure schema)
            new-enums (build-enums-structure schema)
            new-state {:entities new-entities
                       :enums new-enums
                       :metadata new-metadata
                       :data {}}
            changes (sp/build-first-init-changes schema)]
        [new-state changes])
      ;; Migration
      (do
        ;; Check for destructive changes
        (sp/check-all-removals! old-metadata schema)
        (check-type-changes old-state old-metadata schema)
        ;; Compute changes
        (let [entity-changes (sp/compute-entity-changes old-metadata schema)
              field-changes (sp/compute-field-changes old-metadata schema)
              enum-changes (sp/compute-enum-changes old-metadata schema)
              enum-value-changes (sp/compute-enum-value-changes old-metadata schema)
              ;; Build new state
              new-metadata (sp/build-metadata-from-schema schema)
              new-entities (build-entities-structure schema)
              new-enums (build-enums-structure schema)
              new-data (migrate-data (:data old-state) old-metadata schema)
              new-state {:entities new-entities
                         :enums new-enums
                         :metadata new-metadata
                         :data new-data}
              changes {:entities entity-changes
                       :fields field-changes
                       :enums enum-changes
                       :enum-values enum-value-changes}]
          [new-state changes])))))


;; === Storage record ===

(defrecord MemoryStorage
  [state ^ReentrantReadWriteLock rw-lock]

  sp/Storage

  (initialize
    [_this schema]
    (sp/with-write-lock rw-lock
                        (fn []
                          (log/info "Initializing memory storage")
                          (let [[new-state changes] (do-initialize state schema)]
                            (reset! state new-state)
                            (log/info "Memory storage initialized" {:entities (count (:entities new-state))
                                                                    :enums (count (:enums new-state))})
                            changes))))


  (close
    [_this]
    (sp/with-write-lock rw-lock
                        (fn []
                          (log/info "Closing memory storage")
                          (reset! state {:entities {}
                                         :enums {}
                                         :metadata nil
                                         :data {}})
                          nil)))


  sp/StorageIntrospection

  (current-entities
    [_this]
    (sp/with-read-lock rw-lock
                       #(set (keys (:entities @state)))))


  (current-fields
    [_this entity-name]
    (sp/with-read-lock rw-lock
                       #(get-in @state [:entities entity-name :fields])))


  (current-enums
    [_this]
    (sp/with-read-lock rw-lock
                       #(set (keys (:enums @state)))))


  (current-enum-values
    [_this enum-name]
    (sp/with-read-lock rw-lock
                       #(get-in @state [:enums enum-name :values])))


  (schema-metadata
    [_this]
    (sp/with-read-lock rw-lock
                       #(:metadata @state)))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    ;; Validate data type before acquiring lock
    (sp/validate-data-is-map! entity-name data)
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [id (or (:id data) (random-uuid))
                                record (assoc data :id id)]
                            (create-record-atomic! state entity-name record)))))


  (read-entity
    [_this entity-name id]
    (sp/with-read-lock rw-lock
                       #(get-record @state entity-name id)))


  (update-entity
    [_this entity-name id data]
    (sp/with-write-lock rw-lock
                        #(update-record-atomic! state entity-name id data)))


  (delete-entity
    [_this entity-name id]
    (sp/with-write-lock rw-lock
                        #(remove-record! state entity-name id)))


  (query-entities
    [_this entity-name where]
    ;; Validate where clause type before acquiring lock
    (sp/validate-where-clause! where)
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [s @state]
                           (validate-entity-exists! s entity-name)
                           (let [all-records (vals (get-entity-data s entity-name))]
                             (if (empty? where)
                               all-records
                               (filter (fn [record]
                                         (every? (fn [[k v]] (= (get record k) v)) where))
                                       all-records)))))))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (sp/with-write-lock rw-lock
                        (fn []
                          (if (empty? data-seq)
                            []
                            (do
                              (sp/validate-no-duplicate-ids! entity-name data-seq)
                              (let [records (map (fn [data]
                                                   (let [id (or (:id data) (random-uuid))]
                                                     (assoc data :id id)))
                                                 data-seq)]
                                (create-records-atomic! state entity-name records)))))))


  (read-entities
    [_this entity-name ids]
    (sp/with-read-lock rw-lock
                       #(read-records @state entity-name ids)))


  (delete-entities
    [_this entity-name ids]
    (sp/with-write-lock rw-lock
                        #(remove-records! state entity-name ids)))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (sp/with-read-lock rw-lock
                       #(sp/validate-parent-same-schema-impl (->MemoryConstraintHelpers state) fn-id parent-fn-id)))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(sp/validate-no-arg-override-impl (->MemoryConstraintHelpers state) fn-id arg-schema-id)))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(sp/validate-arg-schema-belongs-to-fn-impl (->MemoryConstraintHelpers state) fn-id arg-schema-id)))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (sp/with-read-lock rw-lock
                       #(sp/validate-no-inheritance-cycle-impl (->MemoryConstraintHelpers state) fn-id parent-fn-id)))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (sp/with-read-lock rw-lock
                       #(sp/validate-no-dependency-cycle-impl (->MemoryConstraintHelpers state) owner-fn-id value-fn-id)))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [s @state]
                           (when-not (get-record s :fn fn-id)
                             (throw (ex-info "Function not found"
                                             {:type :not-found
                                              :fn-id fn-id})))
                           (resolve-execution-graph-impl s fn-id))))))


(defn create-storage
  "Creates a new in-memory storage instance.
   Thread-safe with ReentrantReadWriteLock for concurrent access."
  []
  (->MemoryStorage (atom {:entities {}
                          :enums {}
                          :metadata nil
                          :data {}})
                   (ReentrantReadWriteLock.)))
