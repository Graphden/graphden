(ns graphden.datomic-storage.metadata
  "Datomic metadata transaction building and persistence.

   Provides functions for:
   - Building metadata transaction data from schema
   - Saving metadata to database with rollback support"
  (:require
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.introspection :as introspection]))


;; === Metadata transaction builders (pure functions for testability) ===

(defn- build-entity-metadata-tx
  "Builds transaction data for a single entity's metadata."
  [schema entity-name]
  {:graphden.metadata/uuid (ds/entity-uuid schema entity-name)
   :graphden.metadata/kind :entity
   :graphden.metadata/name entity-name})


(defn- build-field-metadata-tx
  "Builds transaction data for a single field's metadata."
  [schema entity-name field-name field-spec]
  (cond-> {:graphden.metadata/uuid (:uuid field-spec)
           :graphden.metadata/kind :field
           :graphden.metadata/name field-name
           :graphden.metadata/parent-uuid (ds/entity-uuid schema entity-name)
           :graphden.metadata/field-type (:type field-spec)
           :graphden.metadata/field-nullable (get field-spec :nullable? false)}
    ;; Include enum-name for enum fields
    (= (:type field-spec) :enum)
    (assoc :graphden.metadata/field-enum-name (:enum-name field-spec))
    ;; Include ref-entity for ref fields
    (= (:type field-spec) :ref)
    (assoc :graphden.metadata/field-ref-entity (:ref-entity field-spec))))


(defn- build-enum-metadata-tx
  "Builds transaction data for a single enum's metadata."
  [enum-name {:keys [uuid]}]
  {:graphden.metadata/uuid uuid
   :graphden.metadata/kind :enum
   :graphden.metadata/name enum-name})


(defn- build-enum-value-metadata-tx
  "Builds transaction data for a single enum value's metadata."
  [parent-uuid value-kw value-uuid]
  {:graphden.metadata/uuid value-uuid
   :graphden.metadata/kind :enum-value
   :graphden.metadata/name value-kw
   :graphden.metadata/parent-uuid parent-uuid})


(defn build-all-metadata-tx-data
  "Builds complete metadata transaction data from schema.
   Pure function - no side effects, easy to test.

   Arguments:
   - schema: DataSchema to extract metadata from

   Returns sequence of transaction maps ready for Datomic transact."
  [schema]
  (concat
    ;; Entities
    (map #(build-entity-metadata-tx schema %) (ds/entities schema))
    ;; Fields
    (mapcat (fn [entity-name]
              (map (fn [[field-name field-spec]]
                     (build-field-metadata-tx schema entity-name field-name field-spec))
                   (ds/entity-fields schema entity-name)))
            (ds/entities schema))
    ;; Enums
    (map (fn [[enum-name enum-def]] (build-enum-metadata-tx enum-name enum-def))
         (ds/enums schema))
    ;; Enum values
    (mapcat (fn [[_enum-name {:keys [uuid values]}]]
              (map (fn [[value-kw value-uuid]]
                     (build-enum-value-metadata-tx uuid value-kw value-uuid))
                   values))
            (ds/enums schema))))


;; === Metadata persistence ===

(defn- fetch-existing-metadata
  "Fetches existing metadata entities from database.
   Returns {:entity-ids [[eid] ...] :full-data [pulled-entity ...]}."
  [db]
  (when (introspection/metadata-schema-exists? db)
    (let [existing (d/q '[:find ?e
                          :where [?e :graphden.metadata/uuid _]]
                        db)]
      {:entity-ids existing
       :full-data (when (seq existing)
                    (mapv (fn [[e]] (d/pull db '[*] e)) existing))})))


(defn- retract-metadata!
  "Retracts existing metadata entities."
  [conn entity-ids]
  (when (seq entity-ids)
    (log/debug "Retracting old metadata" {:count (count entity-ids)})
    (d/transact conn {:tx-data (vec (map (fn [[e]] [:db/retractEntity e]) entity-ids))})))


(defn- assert-metadata!
  "Asserts new metadata, rolling back on failure.
   Returns nil on success, throws on failure."
  [conn tx-data old-metadata]
  (when (seq tx-data)
    (try
      (log/debug "Asserting new metadata" {:count (count tx-data)})
      (d/transact conn {:tx-data (vec tx-data)})
      ;; Note: We intentionally catch broad Exception here because:
      ;; 1. Datomic can throw various exception types (ExceptionInfo, RuntimeException, etc.)
      ;; 2. ANY transaction failure should trigger a rollback attempt
      ;; 3. We re-throw the original exception after rollback attempt
      (catch Exception e
        (log/error e "Failed to save new metadata, attempting rollback")
        (if (seq old-metadata)
          (try
            ;; Remove :db/id from old data (Datomic will assign new ids)
            (let [restore-data (mapv #(dissoc % :db/id) old-metadata)]
              (d/transact conn {:tx-data restore-data})
              (log/info "Successfully restored old metadata after failure"))
            ;; Rollback can also fail with various exception types
            (catch Exception restore-ex
              (log/error restore-ex "Failed to restore old metadata")
              ;; Throw combined exception so caller knows DB may be inconsistent
              (throw (ex-info "Metadata save failed and rollback also failed - database may be inconsistent"
                              {:type :metadata-error/rollback-failed
                               :original-error (ex-message e)
                               :rollback-error (ex-message restore-ex)}
                              e))))
          (log/warn "No old metadata to restore - this was likely first initialization"))
        (throw e)))))


(defn save-metadata!
  "Saves metadata to the database (retract old, then assert new).

   NOTE: Uses two separate transactions because Datomic doesn't allow
   retracting and asserting the same :db/unique value in a single transaction.
   This causes :db.error/datoms-conflict for metadata UUID updates.

   If the second transaction fails after the first succeeds, we attempt to
   restore the old metadata to maintain consistency.

   SAFETY: If rollback also fails, we throw an exception with both errors
   to ensure the caller knows the database may be in an inconsistent state.

   This function orchestrates the smaller pure/side-effecting functions:
   - build-all-metadata-tx-data (pure, testable)
   - fetch-existing-metadata (read)
   - retract-metadata! (write)
   - assert-metadata! (write with rollback)"
  [conn schema]
  ;; Capture old metadata for potential rollback
  (let [db (d/db conn)
        {:keys [entity-ids full-data]} (fetch-existing-metadata db)]
    ;; First, retract all existing metadata
    (retract-metadata! conn entity-ids)
    ;; Then add new metadata
    (let [tx-data (build-all-metadata-tx-data schema)]
      (assert-metadata! conn tx-data full-data))))
