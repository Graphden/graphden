(ns graphden.storage-protocol.interface
  "Protocol for storage implementations.

   Storage brings itself into sync with a DataSchema through the
   initialize function. It uses UUIDs to detect renames vs add/delete.

   Key concepts:
   - Storage reads the current state via StorageIntrospection methods
   - UUID mappings stored in _schema_metadata table enable rename detection
   - Destructive changes (removing entities/fields/enums) throw errors
   - Safe type widening (int→numeric, text→jsonb) is allowed

   The typical flow:
   1. Create storage instance (implementation-specific)
   2. Call (initialize storage schema) to sync with DataSchema
   3. Use storage for CRUD operations
   4. Call (close storage) when done

   ## Naming Conventions

   Functions follow these naming patterns:
   - `get-*` - Returns value or nil if not found (optional lookup)
   - `read-*` - Returns value or throws if not found (required lookup)
   - `create-*` / `update-*` / `delete-*` - Mutating operations
   - `query-*` - Returns collection (possibly empty)
   - `validate-*!` - Validates and throws on failure
   - `*-impl` - Internal implementation (not for direct use)

   ## Security

   Sensitive data is automatically redacted in logs and error messages.
   Use `register-sensitive-field-name!` to add application-specific fields.
   Default patterns cover: password, secret, token, api-key, credentials.

   ## Query Timeout

   All storage backends support configurable query timeout via:
   - `*query-timeout-ms*` - Dynamic var (default: 30s)
   - `with-query-timeout` - Macro for temporary timeout change
   - `get-query-timeout-seconds` - Get timeout in seconds for JDBC"
  (:require
    [graphden.storage-protocol.config :as config]
    [graphden.storage-protocol.constraints :as constraints]
    [graphden.storage-protocol.errors :as errors]
    [graphden.storage-protocol.graph :as graph]
    [graphden.storage-protocol.locks :as locks]
    [graphden.storage-protocol.metadata :as metadata]
    [graphden.storage-protocol.naming :as naming]
    [graphden.storage-protocol.validation :as validation])
  (:import
    (graphden.storage_protocol.graph
      ExecutionGraphResult)))


;; ============================================================================
;; PROTOCOL DEFINITIONS
;; ============================================================================

(defprotocol Storage
  "Protocol for data storage backends."

  (initialize
    [this schema]
    "Initializes/migrates storage to match the given DataSchema.")

  (close
    [this]
    "Releases storage resources."))


(defprotocol StorageIntrospection
  "Protocol for reading storage state."

  (current-entities
    [this]
    "Returns set of entity names currently in storage.")

  (current-fields
    [this entity-name]
    "Returns map of field definitions for entity.")

  (current-enums
    [this]
    "Returns set of enum type names in storage.")

  (current-enum-values
    [this enum-name]
    "Returns set of keyword values for enum.")

  (schema-metadata
    [this]
    "Returns stored UUID→name mappings."))


(defprotocol StorageCRUD
  "Protocol for CRUD operations on stored entities."

  (create-entity
    [this entity-name data]
    "Creates a new entity record.")

  (read-entity
    [this entity-name id]
    "Reads an entity record by ID.")

  (update-entity
    [this entity-name id data]
    "Updates an existing entity record.")

  (delete-entity
    [this entity-name id]
    "Deletes an entity record by ID.")

  (query-entities
    [this entity-name where]
    "Queries entities matching the given criteria."))


(defprotocol StorageBatchCRUD
  "Protocol for batch CRUD operations."

  (create-entities
    [this entity-name data-seq]
    "Creates multiple entity records.")

  (read-entities
    [this entity-name ids]
    "Reads multiple entity records by IDs.")

  (delete-entities
    [this entity-name ids]
    "Deletes multiple entity records."))


(defprotocol GraphConstraints
  "Constraints for graph integrity."

  (validate-parent-same-schema!
    [this fn-id parent-fn-id]
    "Validates that parent-fn has the same fn-schema-id.")

  (validate-no-arg-override!
    [this fn-id arg-schema-id]
    "Validates that arg-schema-id is not already defined in parent chain.")

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    "Validates that arg-schema belongs to the fn-schema of this fn.")

  (validate-no-inheritance-cycle!
    [this fn-id parent-fn-id]
    "Validates that setting parent-fn-id does not create inheritance cycle.")

  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    "Validates that referencing value-fn does not create dependency cycle."))


(defprotocol ConstraintHelpers
  "Helper protocol for constraint validation."

  (get-fn-schema-id-for-fn
    [this fn-id]
    "Returns the fn-schema-id for the given fn-id.")

  (get-fn-schema-id-for-arg-schema
    [this arg-schema-id]
    "Returns the fn-schema-id for the given arg-schema-id.")

  (get-parent-fn-id
    [this fn-id]
    "Returns the parent-fn-id for the given fn-id.")

  (collect-parent-chain
    [this fn-id]
    "Returns a set of all ancestor fn-ids.")

  (collect-arg-schema-ids-in-chain
    [this fn-id]
    "Returns a set of arg-schema-ids defined in the parent chain.")

  (collect-dependency-chain
    [this fn-id]
    "Returns a set of all fn-ids that fn-id depends on."))


(defprotocol StorageValueCodec
  "Protocol for encoding/decoding values between Clojure and storage backend."

  (encode-value
    [this value field-spec]
    "Encodes a Clojure value for storage.")

  (decode-value
    [this value field-spec]
    "Decodes a storage value to Clojure.")

  (encode-row
    [this row field-specs]
    "Encodes all values in a row map for storage.")

  (decode-row
    [this row field-specs]
    "Decodes all values in a storage row to Clojure."))


(defprotocol StorageErrorClassifier
  "Protocol for classifying storage-specific errors."

  (classify-error
    [this exception]
    "Classifies a storage exception into canonical error type.")

  (wrap-error
    [this exception operation context]
    "Wraps a storage exception with application context."))


(defprotocol ExecutionGraph
  "Protocol for retrieving complete execution graph for a function."

  (resolve-execution-graph
    [this fn-id]
    "Resolves the complete execution graph for a function."))


(defprotocol GraphDataLoader
  "Protocol for loading graph data during execution graph resolution."

  (load-fn-record
    [this fn-id]
    "Loads a single fn record by ID.")

  (load-fn-schema-record
    [this fn-schema-id]
    "Loads a single fn-schema record by ID.")

  (load-arg-schemas-for-fn-schema
    [this fn-schema-id]
    "Loads all arg-schemas for a fn-schema.")

  (load-parent-chain
    [this fn-id]
    "Loads the parent chain for a fn.")

  (load-arg-values-for-fns
    [this fn-ids]
    "Loads all arg-values for a set of fn-ids.")

  (classify-uuid-refs
    [this uuid-refs]
    "Classifies UUIDs into fn-refs vs fn-result-value-refs."))


(defprotocol ExecutionGraphReader
  "Protocol for reading data from execution graphs."

  (graph-get-fn
    [this fn-id]
    "Returns fn record for fn-id.")

  (graph-get-fn-schema
    [this fn-schema-id]
    "Returns fn-schema record for fn-schema-id.")

  (graph-get-arg-schemas
    [this fn-schema-id]
    "Returns map of arg-schemas for fn-schema-id.")

  (graph-get-resolved-args
    [this fn-id]
    "Returns map of resolved arg-values for fn-id.")

  (graph-get-fn-result-value
    [this frv-id]
    "Returns fn-result-value record for frv-id."))


;; ============================================================================
;; CONSTRAINT HELPER IMPLEMENTATIONS
;; ============================================================================

(defn collect-parent-chain-impl
  "Default implementation of collect-parent-chain."
  [helpers fn-id]
  (constraints/collect-parent-chain-impl get-parent-fn-id helpers fn-id))


(defn validate-parent-same-schema-impl
  "Shared implementation of parent-same-schema validation."
  [helpers fn-id parent-fn-id]
  (constraints/validate-parent-same-schema-impl
    get-fn-schema-id-for-fn helpers fn-id parent-fn-id))


(defn validate-no-arg-override-impl
  "Shared implementation of no-arg-override validation."
  [helpers fn-id arg-schema-id]
  (constraints/validate-no-arg-override-impl
    collect-arg-schema-ids-in-chain helpers fn-id arg-schema-id))


(defn validate-arg-schema-belongs-to-fn-impl
  "Shared implementation of arg-schema-belongs-to-fn validation."
  [helpers fn-id arg-schema-id]
  (constraints/validate-arg-schema-belongs-to-fn-impl
    get-fn-schema-id-for-fn get-fn-schema-id-for-arg-schema
    helpers fn-id arg-schema-id))


(defn validate-no-inheritance-cycle-impl
  "Shared implementation of no-inheritance-cycle validation."
  [helpers fn-id parent-fn-id]
  (constraints/validate-no-inheritance-cycle-impl
    collect-parent-chain helpers fn-id parent-fn-id))


(defn validate-no-dependency-cycle-impl
  "Shared implementation of no-dependency-cycle validation."
  [helpers owner-fn-id value-fn-id]
  (constraints/validate-no-dependency-cycle-impl
    collect-dependency-chain helpers owner-fn-id value-fn-id))


;; ============================================================================
;; EXECUTIONGRAPHREADER EXTENSION FOR EXECUTIONGRAPHRESULT
;; ============================================================================

(extend-type ExecutionGraphResult
  ExecutionGraphReader

  (graph-get-fn [this fn-id]
    (get (:fns this) fn-id))

  (graph-get-fn-schema [this fn-schema-id]
    (get (:fn-schemas this) fn-schema-id))

  (graph-get-arg-schemas [this fn-schema-id]
    (into {}
          (filter (fn [[_ arg-schema]]
                    (= fn-schema-id (:fn-schema-id arg-schema))))
          (:arg-schemas this)))

  (graph-get-resolved-args [this fn-id]
    (get (:resolved-args this) fn-id))

  (graph-get-fn-result-value [this frv-id]
    (get (:fn-result-values this) frv-id)))


;; ============================================================================
;; RE-EXPORTS FROM HELPER MODULES
;; ============================================================================

;; === Error re-exports ===
(def storage-error-types errors/storage-error-types)
(def make-error-context errors/make-error-context)
(def make-storage-error errors/make-storage-error)
(def redact-sensitive-map errors/redact-sensitive-map)
(def redact-sensitive-deep errors/redact-sensitive-deep)


;; === Sensitive Field Registry ===
;;
;; Extensible system for identifying and redacting sensitive data in logs
;; and error messages. Use these functions to protect PII, credentials,
;; and other sensitive information from appearing in logs.
;;
;; Default protected fields: password, secret, token, api-key, auth-token,
;; access-key, private-key, jdbc-url, connection-string, credentials, etc.
;;
;; Usage examples:
;;
;;   ;; Register a custom field name as sensitive
;;   (register-sensitive-field-name! :patient-medical-id)
;;   (register-sensitive-field-name! :social-security-number)
;;
;;   ;; Register a pattern for field name matching
;;   (register-sensitive-field-pattern! #"(?i)hipaa[_-]?.*")
;;   (register-sensitive-field-pattern! #"(?i)gdpr[_-]?data")
;;
;;   ;; Register a predicate for complex logic
;;   (register-sensitive-field-predicate!
;;     (fn [field-kw] (= "pii" (namespace field-kw))))
;;
;;   ;; Check if a field is sensitive
;;   (sensitive-field? :password)        ; => true
;;   (sensitive-field? :user-name)       ; => false
;;   (sensitive-field? :patient-medical-id) ; => true (after registration)
;;
;;   ;; Redact sensitive values in maps
;;   (redact-sensitive-map {:user "john" :password "secret123"})
;;   ; => {:user "john" :password "[REDACTED]"}
;;
;;   ;; Deep redaction for nested structures
;;   (redact-sensitive-deep {:config {:db {:password "x"}}})
;;   ; => {:config {:db {:password "[REDACTED]"}}}

(def register-sensitive-field-name!
  "Registers an explicit field name as sensitive for redaction.
   See errors/register-sensitive-field-name! for details."
  errors/register-sensitive-field-name!)


(def register-sensitive-field-pattern!
  "Registers a regex pattern for matching sensitive field names.
   See errors/register-sensitive-field-pattern! for details."
  errors/register-sensitive-field-pattern!)


(def register-sensitive-field-predicate!
  "Registers a custom predicate for sensitive field detection.
   See errors/register-sensitive-field-predicate! for details."
  errors/register-sensitive-field-predicate!)


(def reset-sensitive-field-registry!
  "Resets sensitive field registry to defaults. Use with caution."
  errors/reset-sensitive-field-registry!)


(def sensitive-field?
  "Returns true if field name matches sensitive patterns.
   Checks explicit names, regex patterns, and custom predicates."
  errors/sensitive-field?)


;; === Metadata re-exports ===

(defn types-equivalent?
  [t1 t2]
  (metadata/types-equivalent? t1 t2))


(defn safe-type-change?
  [old-type new-type]
  (metadata/safe-type-change? old-type new-type))


(defn safe-nullable-change?
  [old-nullable? new-nullable?]
  (metadata/safe-nullable-change? old-nullable? new-nullable?))


(def check-removed! metadata/check-removed!)
(def check-type-change! metadata/check-type-change!)
(def check-nullable-change! metadata/check-nullable-change!)
(def build-metadata-from-schema metadata/build-metadata-from-schema)
(def build-first-init-changes metadata/build-first-init-changes)
(def check-all-removals! metadata/check-all-removals!)
(def compute-entity-changes metadata/compute-entity-changes)
(def compute-field-changes metadata/compute-field-changes)
(def compute-enum-changes metadata/compute-enum-changes)
(def compute-enum-value-changes metadata/compute-enum-value-changes)


;; === Validation re-exports ===
(def validate-required-fields! validation/validate-required-fields!)
(def validate-no-duplicate-ids! validation/validate-no-duplicate-ids!)
(def validate-data-is-map! validation/validate-data-is-map!)
(def validate-where-clause! validation/validate-where-clause!)
(def validate-where-clause-fields! validation/validate-where-clause-fields!)
(def validate-where-clause-types! validation/validate-where-clause-types!)
(def validate-entity-name! validation/validate-entity-name!)
(def validate-credential-length! validation/validate-credential-length!)
(def validate-no-control-chars! validation/validate-no-control-chars!)
(def validate-credentials! validation/validate-credentials!)
(def validate-jdbc-url! validation/validate-jdbc-url!)
(def canonical-field-types validation/canonical-field-types)


(defn canonical-type?
  [type-kw]
  (validation/canonical-type? type-kw))


(def type-category validation/type-category)


(defn reference-type?
  [type-kw]
  (validation/reference-type? type-kw))


(defn complex-type?
  [type-kw]
  (validation/complex-type? type-kw))


;; === Graph re-exports ===
(def default-query-timeout-ms graph/default-query-timeout-ms)
(def default-max-depth graph/default-max-depth)
(def default-max-unknown-types graph/default-max-unknown-types)


;; Re-export dynamic var
(def ^:dynamic *max-graph-iterations*
  "Maximum iterations for graph resolution. Binds to graph/*max-graph-iterations*."
  10000)


(defn with-max-graph-iterations
  "Binds *max-graph-iterations* in both this namespace and graph namespace."
  [limit f]
  (binding [*max-graph-iterations* limit
            graph/*max-graph-iterations* limit]
    (f)))


(def check-graph-iteration-limit! graph/check-graph-iteration-limit!)
(def traverse-bfs graph/traverse-bfs)
(def try-parse-uuid graph/try-parse-uuid)
(def ->execution-graph graph/->execution-graph)


(defn execution-graph?
  [x]
  (graph/execution-graph? x))


(def merge-arg-values-for-chain graph/merge-arg-values-for-chain)
(def extract-uuid-refs-from-arg-values graph/extract-uuid-refs-from-arg-values)


;; === Constraint limits re-exports ===
(def default-max-parent-chain-depth constraints/default-max-parent-chain-depth)
(def default-max-dependency-chain-depth constraints/default-max-dependency-chain-depth)


;; === Lock re-exports ===
(def with-read-lock locks/with-read-lock)
(def with-write-lock locks/with-write-lock)
(def create-rw-lock locks/create-rw-lock)


;; === Naming re-exports ===
(def kw->snake-case naming/kw->snake-case)
(def snake->kw naming/snake->kw)
(def check-snake-case-collisions! naming/check-snake-case-collisions!)


;; === Query timeout re-exports ===
(def ^:dynamic *query-timeout-ms*
  "Timeout for storage queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use with-query-timeout for safe rebinding."
  config/*query-timeout-ms*)


(def min-query-timeout-ms config/min-query-timeout-ms)
(def validate-query-timeout! config/validate-query-timeout!)
(def with-query-timeout config/with-query-timeout)
(def get-query-timeout-seconds config/get-query-timeout-seconds)
(def execute-with-timeout! config/execute-with-timeout!)


;; ============================================================================
;; ADDITIONAL HELPERS
;; ============================================================================

(defn needs-special-encoding?
  "Returns true if field type requires special encoding (not passthrough)."
  [field-type]
  (contains? #{:jsonb :union :enum} field-type))


(defn standard-crud-validations!
  "Performs standard validations for CRUD operations."
  [entity-name data fields]
  (validate-data-is-map! entity-name data)
  (when fields
    (validate-required-fields! entity-name fields data)))


(defn standard-query-validations!
  "Performs standard validations for query operations.
   Validates the where clause structure, field names, and value types."
  [entity-name fields where]
  (validate-where-clause! where)
  (when fields
    (validate-where-clause-fields! entity-name fields where)
    (validate-where-clause-types! entity-name fields where)))


(defn standard-batch-validations!
  "Performs standard validations for batch CRUD operations."
  [entity-name data-seq]
  (validate-no-duplicate-ids! entity-name data-seq))


(defn wrap-batch-error
  "Wraps an exception with batch context information."
  ([exception index batch-size]
   (wrap-batch-error exception index batch-size nil))
  ([exception index batch-size failed-id]
   (let [base-data (if (instance? clojure.lang.ExceptionInfo exception)
                     (ex-data exception)
                     {:type :batch-error/partial-failure})
         batch-data (cond-> {:batch-index index
                             :batch-size batch-size}
                      failed-id (assoc :failed-id failed-id))]
     (ex-info (ex-message exception)
              (merge base-data batch-data)
              exception))))


(defn process-batch-with-index
  "Processes a sequence of items with error handling that includes batch context."
  [items get-id-fn process-fn]
  (let [items-vec (vec items)
        batch-size (count items-vec)]
    (map-indexed
      (fn [idx item]
        (try
          (process-fn item idx)
          (catch clojure.lang.ExceptionInfo e
            (throw (wrap-batch-error e idx batch-size (when get-id-fn (get-id-fn item)))))
          (catch Exception e
            (throw (wrap-batch-error e idx batch-size (when get-id-fn (get-id-fn item)))))))
      items-vec)))


(defn initialize-with-cleanup!
  "Initializes storage with schema, cleaning up on failure."
  [storage schema]
  (try
    (initialize storage schema)
    storage
    (catch Exception e
      (close storage)
      (throw e))))
