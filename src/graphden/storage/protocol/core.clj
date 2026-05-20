(ns graphden.storage.protocol.core
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

   ## Facade Pattern (Re-exports)

   This namespace serves as a unified facade for the storage-protocol component,
   re-exporting functions from internal modules (errors, config, validation, etc.).
   This is an intentional component pattern that provides:

   - **Single import point** - Users only need `[graphden.storage.protocol.core :as sp]`
   - **Stable API** - Internal refactoring doesn't break user code
   - **Discoverability** - All public functions visible in one place

   Internal modules (not for direct import by users):
   - `errors.clj` - Error types, classification, sensitive data redaction
   - `config.clj` - Dynamic vars, timeouts, limits
   - `validation.clj` - Input validation helpers
   - `constraints.clj` - Graph constraint implementations
   - `graph.clj` - BFS traversal, execution graph resolution
   - `metadata.clj` - Schema migration helpers
   - `naming.clj` - Keyword/snake_case conversions
   - `locks.clj` - Read-write lock utilities
   - `codec.clj` - Value encoding/decoding utilities

   ## Naming Conventions

   Functions follow these naming patterns:
   - `get-*` - Returns value or nil if not found (optional lookup)
   - `read-*` - Returns value or throws if not found (required lookup)
   - `create-*` / `update-*` / `delete-*` - Mutating operations
   - `query-*` - Returns collection (possibly empty)
   - `validate-*!` - Validates input, throws on failure (public API)
   - `check-*!` - Checks conditions, throws on failure (migration/internal)
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
    [graphden.storage.protocol.codec :as codec]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.constraints :as constraints]
    [graphden.storage.protocol.errors :as errors]
    [graphden.storage.protocol.graph :as graph]
    [graphden.storage.protocol.locks :as locks]
    [graphden.storage.protocol.metadata :as metadata]
    [graphden.storage.protocol.naming :as naming]
    [graphden.storage.protocol.redaction :as redaction]
    [graphden.storage.protocol.validation :as validation]))


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

  (update-entities
    [this entity-name data-seq]
    "Updates multiple entity records. Each record must have :id.")

  (upsert-entities
    [this entity-name data-seq]
    "Inserts or updates multiple entity records (INSERT ... ON CONFLICT DO UPDATE).
     Each record must have :id. Returns seq of upserted records.")

  (delete-entities
    [this entity-name ids]
    "Deletes multiple entity records."))


(defprotocol GraphConstraints
  "Constraints for graph integrity."

  (validate-no-dependency-cycle!
    [this owner-fn-id ref-fn-id]
    "Validates that referencing ref-fn does not create dependency cycle."))


(defprotocol ConstraintHelpers
  "Helper protocol for constraint validation."

  (collect-dependency-chain
    [this fn-id]
    "Returns a set of all fn-ids that fn-id depends on (transitive)."))


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


;; ============================================================================
;; CONSTRAINT HELPER IMPLEMENTATIONS
;; ============================================================================

(defn validate-no-dependency-cycle-impl
  "Shared implementation of no-dependency-cycle validation."
  [helpers owner-fn-id ref-fn-id]
  (constraints/validate-no-dependency-cycle-impl
    collect-dependency-chain helpers owner-fn-id ref-fn-id))


;; ============================================================================
;; RE-EXPORTS FROM HELPER MODULES
;; ============================================================================

;; === Error re-exports ===
(def storage-error-types errors/storage-error-types)
(def make-error-context errors/make-error-context)
(def make-storage-error errors/make-storage-error)
(def redact-sensitive-map redaction/redact-sensitive-map)
(def redact-sensitive-deep redaction/redact-sensitive-deep)


(def wrap-storage-error
  "Wraps an exception with application context, redacts before logging.
   See errors/wrap-storage-error for details."
  errors/wrap-storage-error)


;; === Unified Validation Error Factory ===
;;
;; Use these functions for consistent error creation across all validators.

(def create-validation-error
  "Creates a validation error with consistent structure.
   See errors/create-validation-error for details."
  errors/create-validation-error)


(def throw-validation-error!
  "Creates and throws a validation error with consistent structure.
   See errors/throw-validation-error! for details."
  errors/throw-validation-error!)


(defmacro with-storage-error-handling
  "Executes body with consistent error handling and wrapping.
   See errors/with-storage-error-handling for details."
  [error-type operation context & body]
  `(errors/with-storage-error-handling ~error-type ~operation ~context ~@body))


;; === Error Registry ===
;;
;; Extensible error type registry for custom application errors.
;; Pre-registered types cover common storage scenarios.

(def error-categories
  "Valid error categories for classification."
  errors/error-categories)


(def error-severities
  "Valid error severities."
  errors/error-severities)


(def register-error-type!
  "Registers a custom error type with metadata.
   See errors/register-error-type! for details."
  errors/register-error-type!)


(def get-error-metadata
  "Returns metadata for an error type, or nil if not registered."
  errors/get-error-metadata)


(def error-retryable?
  "Returns true if the error type is marked as retryable."
  errors/error-retryable?)


(def error-category
  "Returns the category of an error type."
  errors/error-category)


(def registered-error-types
  "Returns a set of all registered error type keywords."
  errors/registered-error-types)


;; === Sensitive Field Registry ===

(def register-sensitive-field-name!
  "Registers an explicit field name as sensitive for redaction.
   See redaction/register-sensitive-field-name! for details."
  redaction/register-sensitive-field-name!)


(def register-sensitive-field-pattern!
  "Registers a regex pattern for matching sensitive field names.
   See redaction/register-sensitive-field-pattern! for details."
  redaction/register-sensitive-field-pattern!)


(def register-sensitive-field-predicate!
  "Registers a custom predicate for sensitive field detection.
   See redaction/register-sensitive-field-predicate! for details."
  redaction/register-sensitive-field-predicate!)


(def reset-sensitive-field-registry!
  "Resets sensitive field registry to defaults. Use with caution."
  redaction/reset-sensitive-field-registry!)


(def get-sensitive-field-registry
  "Returns current sensitive field registry state. Useful for tests."
  redaction/get-sensitive-field-registry)


(def set-sensitive-field-registry!
  "Sets sensitive field registry to a specific state. Useful for tests."
  redaction/set-sensitive-field-registry!)


(defmacro with-sensitive-field-registry
  "Executes body with isolated sensitive field registry.
   Automatically saves and restores registry state for test isolation."
  [& body]
  `(redaction/with-sensitive-field-registry (do ~@body)))


(def sensitive-field?
  "Returns true if field name matches sensitive patterns.
   Checks explicit names, regex patterns, and custom predicates."
  redaction/sensitive-field?)


(def critical-sensitive-patterns
  "Critical patterns that must be matched for security."
  redaction/critical-sensitive-patterns)


(def validate-sensitive-field-coverage!
  "Validates that all critical sensitive patterns are properly matched.
   Throws if any critical pattern would not be detected as sensitive.
   Use this at application startup to verify security configuration."
  redaction/validate-sensitive-field-coverage!)


(def warn-on-suspicious-field
  "Logs warning if field looks sensitive but isn't registered.
   Returns true if field looks suspicious but not registered."
  redaction/warn-on-suspicious-field)


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
;; `default-query-timeout-ms` is the only constant external callers
;; (tests, in particular) reach via this namespace; everything else
;; that used to be re-exported here had no consumers and was dropped.
(def default-query-timeout-ms config/default-query-timeout-ms)


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


;; === ExecutionGraph accessor functions ===
;; Stable API for accessing graph data.

(def get-graph-fns        graph/get-graph-fns)
(def get-graph-slots      graph/get-graph-slots)
(def get-graph-fn-slots   graph/get-graph-fn-slots)
(def get-graph-bindings   graph/get-graph-bindings)
(def get-graph-list-items graph/get-graph-list-items)
(def get-bindings-for-fn  graph/get-bindings-for-fn)
(def get-fn-slots-for-fn  graph/get-fn-slots-for-fn)
(def get-items-for-binding graph/get-items-for-binding)


;; === Constraint limits re-exports ===
(def default-max-dependency-chain-depth constraints/default-max-dependency-chain-depth)


;; === Lock re-exports ===
(def with-read-lock locks/with-read-lock)
(def with-write-lock locks/with-write-lock)
(def create-rw-lock locks/create-rw-lock)


(def with-double-check-locking
  "Double-check locking for lazy cached initialization.
   See locks/with-double-check-locking for details."
  locks/with-double-check-locking)


;; === Naming re-exports ===
(def kw->snake-case naming/kw->snake-case)
(def snake->kw naming/snake->kw)
(def check-snake-case-collisions! naming/check-snake-case-collisions!)


;; === Query timeout re-exports ===
;; === Dynamic Configuration Vars ===

(def ^:dynamic *query-timeout-ms*
  "Timeout for storage queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use with-query-timeout for safe rebinding."
  config/*query-timeout-ms*)


(def min-query-timeout-ms config/min-query-timeout-ms)
(def validate-query-timeout! config/validate-query-timeout!)


(defn with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).
   Binds both config/*query-timeout-ms* and this namespace's *query-timeout-ms*
   for consistent behavior regardless of which var code reads."
  [timeout-ms f]
  (config/validate-query-timeout! timeout-ms)
  (binding [config/*query-timeout-ms* timeout-ms
            *query-timeout-ms* timeout-ms]
    (f)))


(def get-query-timeout-seconds config/get-query-timeout-seconds)
(def execute-with-timeout! config/execute-with-timeout!)


;; === Collection generation limits re-exports ===
(def ^:dynamic *max-range-size*
  "Maximum elements in range to prevent memory exhaustion. Default: 1000000."
  config/*max-range-size*)


(def ^:dynamic *max-repeat-size*
  "Maximum elements in repeat to prevent memory exhaustion. Default: 1000000."
  config/*max-repeat-size*)


;; === Regex safety configuration re-exports ===

(def ^:dynamic *max-regex-length*
  "Maximum regex pattern length to prevent complex pattern attacks. Default: 100."
  config/*max-regex-length*)


(def ^:dynamic *max-regex-input-length*
  "Maximum input string length for regex operations. Default: 100000."
  config/*max-regex-input-length*)


(def ^:dynamic *regex-compile-timeout-ms*
  "Timeout for regex compilation in milliseconds. Default: 100."
  config/*regex-compile-timeout-ms*)


(defn with-regex-limits
  "Executes f with custom regex safety limits.
   Binds both config and interface vars for compatibility.

   Arguments:
   - opts: map with optional keys:
     - :max-pattern-length - maximum regex pattern length
     - :max-input-length - maximum input string length
     - :compile-timeout-ms - regex compilation timeout
   - f: zero-arg function to execute"
  [opts f]
  (let [pattern-len (get opts :max-pattern-length config/*max-regex-length*)
        input-len (get opts :max-input-length config/*max-regex-input-length*)
        timeout (get opts :compile-timeout-ms config/*regex-compile-timeout-ms*)]
    (binding [config/*max-regex-length* pattern-len
              config/*max-regex-input-length* input-len
              config/*regex-compile-timeout-ms* timeout
              *max-regex-length* pattern-len
              *max-regex-input-length* input-len
              *regex-compile-timeout-ms* timeout]
      (f))))


;; === Batch size validation re-exports ===
(def ^:dynamic *max-batch-size*
  "Maximum entities in a single batch operation. Default: 1000."
  config/*max-batch-size*)


(def validate-batch-size!
  "Validates batch size is within allowed limits. Throws if exceeded."
  config/validate-batch-size!)


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


;; === Codec utilities re-exports ===

(def generic-encode-row
  "Generic row encoding that applies encode-value to each field.
   See codec/generic-encode-row for details."
  codec/generic-encode-row)


(def generic-decode-row
  "Generic row decoding that applies decode-value to each field.
   See codec/generic-decode-row for details."
  codec/generic-decode-row)
