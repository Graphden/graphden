(ns graphden.memory-storage.crud
  "CRUD helpers for memory storage.

   Provides functions for:
   - Record validation (required fields, unique constraints)
   - Single record operations (get, create, update, remove)
   - Batch operations (create-many, read-many, remove-many)"
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.storage-protocol.interface :as sp]))


;; === Validation helpers ===

(defn validate-required-fields!
  "Validates that all required (non-nullable) fields are present and not nil.
   Uses shared utility from storage-protocol."
  [state entity-name data]
  (let [fields (get-in state [:entities entity-name :fields])]
    (sp/validate-required-fields! entity-name fields data)))


(defn get-entity-data
  "Gets all records for an entity from state."
  [state entity-name]
  (get-in state [:data entity-name] {}))


(defn get-entity-fields
  "Gets the fields map for an entity from state."
  [state entity-name]
  (get-in state [:entities entity-name :fields]))


(defn- find-conflicting-record
  "Finds the first record that conflicts with new-values for the given fields.
   Returns the conflicting record or nil."
  [records exclude-id fields new-values]
  (some (fn [record]
          (when (and (not= (:id record) exclude-id)
                     (= new-values (mapv #(get record %) fields)))
            record))
        records))


(defn validate-unique-constraints!
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
      ;; Log with redacted values to avoid exposing sensitive data
      (log/warn "Unique constraint violation"
                {:entity entity-name
                 :fields fields
                 :value-count (count new-values)})
      ;; Exception includes redacted values to prevent sensitive data leakage.
      ;; Use redact-sensitive-map with zipmap for consistent error format across backends.
      (throw (ex-info "Unique constraint violation"
                      {:type :constraint-violation/unique
                       :entity entity-name
                       :fields fields
                       :values (sp/redact-sensitive-map (zipmap fields new-values))})))))


(defn validate-entity-exists!
  "Validates that entity exists in schema. Throws :entity-not-in-schema if not.
   See storage-protocol.interface/storage-error-types for error type documentation."
  [state entity-name]
  (when-not (contains? (:entities state) entity-name)
    (throw (ex-info "Entity not found in schema"
                    {:type :entity-not-in-schema
                     :entity entity-name}))))


;; === Single record operations ===

(defn get-record
  "Gets a single record by id."
  [state entity-name id]
  (get-in state [:data entity-name id]))


(defn create-record-atomic!
  "Atomically validates and creates a record. Returns the record.
   Validation happens inside swap! to prevent race conditions."
  [state-atom entity-name record]
  (swap! state-atom
         (fn [state]
           (validate-required-fields! state entity-name record)
           (validate-unique-constraints! state entity-name record nil)
           (assoc-in state [:data entity-name (:id record)] record)))
  record)


(defn update-record-atomic!
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


(defn remove-record!
  "Removes a record from state atom. Returns true if existed."
  [state-atom entity-name id]
  (let [existed? (some? (get-in @state-atom [:data entity-name id]))]
    (swap! state-atom update-in [:data entity-name] dissoc id)
    existed?))


;; === Batch operations ===

(defn create-records-atomic!
  "Atomically validates and creates multiple records. Returns sequence of records.
   All validations happen inside swap! to ensure atomicity.
   On validation failure, logs which record (by index) failed and includes
   batch context in the exception data.
   Uses sp/wrap-batch-error for consistent error formatting across storage backends."
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
                           (throw (sp/wrap-batch-error e idx (count records) (:id record))))))
                     [state 0]
                     records))))
  records)


(defn read-records
  "Reads multiple records by ids. Returns {id -> record} for found records."
  [state entity-name ids]
  (let [entity-data (get-entity-data state entity-name)]
    (->> ids
         (keep (fn [id]
                 (when-let [record (get entity-data id)]
                   [id record])))
         (into {}))))


(defn remove-records!
  "Removes multiple records from state atom. Returns count of removed.
   ids must be a sequential collection (list, vector, etc.) or nil."
  [state-atom entity-name ids]
  (when (and (some? ids) (not (sequential? ids)))
    (throw (ex-info "ids must be a sequential collection or nil"
                    {:type :invalid-data
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
