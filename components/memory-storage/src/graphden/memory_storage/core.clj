(ns graphden.memory-storage.core
  "In-memory implementation of Storage protocol."
  (:require
    [clojure.set :as set]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]))


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
                                         :nullable? (get field-spec :nullable? false)}]))}])))


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


(defn- get-record
  "Gets a single record by id."
  [state entity-name id]
  (get-in state [:data entity-name id]))


(defn- put-record!
  "Puts a record into state atom. Returns the record."
  [state-atom entity-name record]
  (let [id (:id record)]
    (swap! state-atom assoc-in [:data entity-name id] record)
    record))


(defn- remove-record!
  "Removes a record from state atom. Returns true if existed."
  [state-atom entity-name id]
  (let [existed? (some? (get-in @state-atom [:data entity-name id]))]
    (swap! state-atom update-in [:data entity-name] dissoc id)
    existed?))


;; === GraphConstraints helpers ===

(defn- get-fn-schema-id
  "Gets fn-schema-id for a fn record."
  [state fn-id]
  (:fn-schema-id (get-record state :fn fn-id)))


(defn- get-parent-fn-id
  "Gets parent-fn-id for a fn record."
  [state fn-id]
  (:parent-fn-id (get-record state :fn fn-id)))


(defn- get-arg-schema-fn-schema-id
  "Gets fn-schema-id for an arg-schema record."
  [state arg-schema-id]
  (:fn-schema-id (get-record state :arg-schema arg-schema-id)))


(defn- collect-parent-chain
  "Collects all ancestor fn-ids by following parent-fn-id links.
   Returns a set of fn-ids (not including the starting fn-id)."
  [state fn-id]
  (loop [current-id (get-parent-fn-id state fn-id)
         ancestor-ids #{}]
    (if (or (nil? current-id) (contains? ancestor-ids current-id))
      ancestor-ids
      (recur (get-parent-fn-id state current-id)
             (conj ancestor-ids current-id)))))


(defn- collect-arg-schema-ids-in-chain
  "Collects all arg-schema-ids defined in the parent chain (not including fn-id itself)."
  [state fn-id]
  (let [ancestor-ids (collect-parent-chain state fn-id)]
    (->> (get-entity-data state :arg-value)
         (vals)
         (filter #(contains? ancestor-ids (:owner-fn-id %)))
         (map :arg-schema-id)
         (set))))


(defn- collect-dependency-chain
  "Collects all fn-ids that owner-fn depends on through arg-values.
   DFS traversal of value refs."
  [state owner-fn-id]
  (loop [to-visit [owner-fn-id]
         visited #{}]
    (if (empty? to-visit)
      visited
      (let [current-id (first to-visit)
            rest-to-visit (rest to-visit)]
        (if (contains? visited current-id)
          (recur rest-to-visit visited)
          (let [arg-values (filter #(= (:owner-fn-id %) current-id)
                                   (vals (get-entity-data state :arg-value)))
                ;; Get fn references from arg-values (UUIDs that are fn refs)
                ref-fn-ids (->> arg-values
                                (map :value)
                                (filter uuid?)
                                ;; Check if this UUID is actually a fn
                                (filter #(some? (get-record state :fn %))))]
            (recur (concat rest-to-visit ref-fn-ids)
                   (conj visited current-id))))))))


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
   Returns {arg-schema-id -> arg-value-record}."
  [state fn-id]
  (let [chain (collect-fn-parent-chain state fn-id)]
    ;; Process from root to leaf so child overrides parent
    (reduce (fn [acc chain-fn-id]
              (let [arg-values (filter #(= (:owner-fn-id %) chain-fn-id)
                                       (vals (get-entity-data state :arg-value)))]
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
   Uses BFS to collect all transitively referenced functions."
  [state fn-id]
  (loop [to-visit #{fn-id}
         visited #{}
         fns {}
         fn-schemas {}
         arg-schemas {}
         resolved-args {}]
    (if (empty? to-visit)
      {:fns fns
       :fn-schemas fn-schemas
       :arg-schemas arg-schemas
       :resolved-args resolved-args}
      (let [current-fn-id (first to-visit)
            rest-to-visit (disj to-visit current-fn-id)]
        (if (contains? visited current-fn-id)
          (recur rest-to-visit visited fns fn-schemas arg-schemas resolved-args)
          (let [fn-rec (get-record state :fn current-fn-id)]
            (if-not fn-rec
              ;; fn doesn't exist, skip (might be literal value that looks like UUID)
              (recur rest-to-visit (conj visited current-fn-id)
                     fns fn-schemas arg-schemas resolved-args)
              (let [fn-schema-id (:fn-schema-id fn-rec)
                    fn-schema (get-record state :fn-schema fn-schema-id)
                    ;; Get arg-schemas for this fn-schema if not already loaded
                    new-arg-schemas (if (contains? fn-schemas fn-schema-id)
                                      {}
                                      (->> (vals (get-entity-data state :arg-schema))
                                           (filter #(= (:fn-schema-id %) fn-schema-id))
                                           (map (juxt :id identity))
                                           (into {})))
                    ;; Merge arg-values from parent chain
                    merged-args (merge-arg-values-from-chain state current-fn-id)
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
                       (assoc resolved-args current-fn-id merged-args))))))))))


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
  [state]

  sp/Storage

  (initialize
    [_this schema]
    (let [[new-state changes] (do-initialize state schema)]
      (reset! state new-state)
      changes))


  (close
    [_this]
    (reset! state {:entities {}
                   :enums {}
                   :metadata nil
                   :data {}})
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (set (keys (:entities @state))))


  (current-fields
    [_this entity-name]
    (get-in @state [:entities entity-name :fields]))


  (current-enums
    [_this]
    (set (keys (:enums @state))))


  (current-enum-values
    [_this enum-name]
    (get-in @state [:enums enum-name :values]))


  (schema-metadata
    [_this]
    (:metadata @state))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    (validate-required-fields! @state entity-name data)
    (let [id (or (:id data) (random-uuid))
          record (assoc data :id id)]
      (put-record! state entity-name record)))


  (read-entity
    [_this entity-name id]
    (get-record @state entity-name id))


  (update-entity
    [_this entity-name id data]
    (let [s @state
          existing (get-record s entity-name id)]
      (when-not existing
        (throw (ex-info "Entity not found"
                        {:type :not-found
                         :entity entity-name
                         :id id})))
      (let [updated (merge existing data {:id id})]
        (validate-required-fields! s entity-name updated)
        (put-record! state entity-name updated))))


  (delete-entity
    [_this entity-name id]
    (remove-record! state entity-name id))


  (query-entities
    [_this entity-name where]
    (let [all-records (vals (get-entity-data @state entity-name))]
      (if (empty? where)
        all-records
        (filter (fn [record]
                  (every? (fn [[k v]] (= (get record k) v)) where))
                all-records))))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (when parent-fn-id
      (let [s @state
            fn-record (get-record s :fn fn-id)
            parent-record (get-record s :fn parent-fn-id)
            fn-schema-id (:fn-schema-id fn-record)
            parent-schema-id (:fn-schema-id parent-record)]
        (when (and fn-schema-id parent-schema-id
                   (not= fn-schema-id parent-schema-id))
          (throw (ex-info "Parent fn has different fn-schema-id"
                          {:type :constraint-violation/parent-schema-mismatch
                           :fn-id fn-id
                           :parent-fn-id parent-fn-id
                           :fn-schema-id fn-schema-id
                           :parent-schema-id parent-schema-id}))))))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (let [s @state
          parent-arg-schema-ids (collect-arg-schema-ids-in-chain s fn-id)]
      (when (contains? parent-arg-schema-ids arg-schema-id)
        (throw (ex-info "Argument already defined in parent chain"
                        {:type :constraint-violation/arg-already-defined
                         :fn-id fn-id
                         :arg-schema-id arg-schema-id})))))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (let [s @state
          fn-schema-id (get-fn-schema-id s fn-id)
          arg-fn-schema-id (get-arg-schema-fn-schema-id s arg-schema-id)]
      (when (and fn-schema-id arg-fn-schema-id
                 (not= fn-schema-id arg-fn-schema-id))
        (throw (ex-info "Arg-schema does not belong to fn's schema"
                        {:type :constraint-violation/arg-schema-mismatch
                         :fn-id fn-id
                         :arg-schema-id arg-schema-id
                         :fn-schema-id fn-schema-id
                         :arg-fn-schema-id arg-fn-schema-id})))))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (when parent-fn-id
      (let [s @state]
        ;; Check if fn-id would appear in the parent chain of parent-fn-id
        (when (= fn-id parent-fn-id)
          (throw (ex-info "Cannot set self as parent"
                          {:type :constraint-violation/inheritance-cycle
                           :fn-id fn-id
                           :parent-fn-id parent-fn-id})))
        (let [parent-ancestors (collect-parent-chain s parent-fn-id)]
          (when (contains? parent-ancestors fn-id)
            (throw (ex-info "Setting parent would create inheritance cycle"
                            {:type :constraint-violation/inheritance-cycle
                             :fn-id fn-id
                             :parent-fn-id parent-fn-id
                             :cycle-through (conj parent-ancestors parent-fn-id)})))))))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (when value-fn-id
      (let [s @state
            ;; Check if owner-fn-id is in the dependency chain of value-fn-id
            value-deps (collect-dependency-chain s value-fn-id)]
        (when (contains? value-deps owner-fn-id)
          (throw (ex-info "Reference would create dependency cycle"
                          {:type :constraint-violation/dependency-cycle
                           :owner-fn-id owner-fn-id
                           :value-fn-id value-fn-id}))))))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (let [s @state]
      (when-not (get-record s :fn fn-id)
        (throw (ex-info "Function not found"
                        {:type :not-found
                         :fn-id fn-id})))
      (resolve-execution-graph-impl s fn-id))))


(defn create-storage
  "Creates a new in-memory storage instance."
  []
  (->MemoryStorage (atom {:entities {}
                          :enums {}
                          :metadata nil
                          :data {}})))
