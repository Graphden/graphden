(ns graphden.storage.protocol.errors
  "Error classification, registry, context helpers, and storage-error
   wrapping.

   Sensitive-data redaction lives in
   `graphden.storage.protocol.redaction`; this ns requires it to redact
   context before logging in `wrap-storage-error`."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.redaction :as redaction]))


;; === Storage Error Classification ===

(def storage-error-types
  "Canonical storage error types across all backends.
   Each backend maps its native errors to these types.

   Note on entity/table errors:
   - :entity-not-in-schema - Entity name not defined in DataSchema (application-level check)
   - :table-not-found - Database table doesn't exist (database-level error, e.g., SQL state 42P01)

   Memory storage uses :entity-not-in-schema (checks schema before operation).
   PostgreSQL may return :table-not-found if table is missing (database error).
   Both indicate the entity is not available, but at different levels."
  #{:unique-violation
    :foreign-key-violation
    :not-null-violation
    :check-constraint-violation
    :entity-not-in-schema
    :table-not-found
    :connection-error
    :system-error/query-timeout
    :parse-error
    :unknown-sql-error})


(defn make-error-context
  "Creates a canonical error context map for storage errors.
   Use this helper in backend implementations to ensure consistent error structure.

   Required keys:
   - type: canonical error type keyword (e.g., :unique-violation, :table-not-found)
   - operation: keyword describing the operation (e.g., :create-entity, :update-entity)
   - message: human-readable error message string

   Optional keys (passed in context map):
   - entity-name: keyword of the entity type
   - sql-state: backend-specific error code (e.g., PostgreSQL SQLSTATE)
   - query: the query that failed (for debugging, will be redacted in logs)
   - Any other backend-specific context

   Example:
   (make-error-context :unique-violation :create-entity \"Duplicate key\"
                       {:entity-name :user :sql-state \"23505\"})"
  [error-type operation message context]
  (merge {:type error-type
          :operation operation
          :message message}
         context))


(defn make-storage-error
  "Creates a storage error ex-info with canonical structure.
   Combines make-error-context with ex-info creation.

   Arguments:
   - error-type: canonical error type keyword
   - operation: keyword describing the operation
   - message: human-readable error message
   - context: map of additional context (entity-name, sql-state, etc.)
   - cause: optional root cause exception

   The context is redacted for logging safety but preserved in full in the exception.

   Example:
   (throw (make-storage-error :unique-violation :create-entity
                              \"Duplicate user email\"
                              {:entity-name :user :fields [:email]}
                              sql-exception))"
  ([error-type operation message context]
   (make-storage-error error-type operation message context nil))
  ([error-type operation message context cause]
   (let [error-context (make-error-context error-type operation message context)]
     (if cause
       (ex-info message error-context cause)
       (ex-info message error-context)))))


;; === Extensible Error Registry ===
;;
;; Allows storage backends to register custom error types and
;; provides a unified way to look up error metadata.

(def ^:private error-registry
  "Registry of error types with metadata.
   Key: error keyword (e.g., :unique-violation)
   Value: {:category :constraint|:validation|:config|:connection
           :retryable? boolean
           :severity :error|:warning
           :description string}"
  (atom {}))


(def ^:const error-categories
  "Valid error categories for classification."
  #{:constraint :validation :config :connection :execution :metadata :batch :unknown})


(def ^:const error-severities
  "Valid error severities."
  #{:error :warning :info})


(defn register-error-type!
  "Registers a new error type with metadata.
   Use this to define custom application-level error types.

   Arguments:
   - error-type: keyword identifying the error (e.g., :my-app/custom-error)
   - metadata: map with error metadata
     - :category - one of error-categories (required)
     - :retryable? - boolean, true if operation can be retried
     - :severity - one of error-severities (default :error)
     - :description - human-readable description

   Returns the error-type keyword.

   Example:
     (register-error-type! :my-app/rate-limited
       {:category :connection
        :retryable? true
        :severity :warning
        :description \"Rate limit exceeded, retry after delay\"})"
  [error-type metadata]
  (when-not (keyword? error-type)
    (throw (ex-info "error-type must be a keyword" {:error-type error-type})))
  (when-not (contains? error-categories (:category metadata))
    (throw (ex-info "Invalid error category"
                    {:error-type error-type
                     :category (:category metadata)
                     :valid-categories error-categories})))
  (swap! error-registry assoc error-type
         (merge {:severity :error
                 :retryable? false}
                metadata))
  error-type)


(defn get-error-metadata
  "Returns metadata for an error type, or nil if not registered."
  [error-type]
  (get @error-registry error-type))


(defn error-retryable?
  "Returns true if the error type is marked as retryable.
   Returns false for unknown error types."
  [error-type]
  (boolean (:retryable? (get-error-metadata error-type))))


(defn error-category
  "Returns the category of an error type.
   Returns :unknown for unregistered error types."
  [error-type]
  (or (:category (get-error-metadata error-type)) :unknown))


(defn registered-error-types
  "Returns a set of all registered error type keywords."
  []
  (set (keys @error-registry)))


;; Register standard error types on load
(doseq [[error-type metadata]
        {;; Constraint violations
         :constraint-violation/unique
         {:category :constraint :retryable? false
          :description "Unique constraint violated"}

         :constraint-violation/arg-schema-mismatch
         {:category :constraint :retryable? false
          :description "Arg schema doesn't belong to fn"}

         :constraint-violation/dependency-cycle
         {:category :constraint :retryable? false
          :description "Cycle in arg-value references"}

         :constraint-violation/main-branch-undeletable
         {:category :constraint :retryable? false
          :description "Attempted to delete the main branch"}

         :constraint-violation/branch-has-children
         {:category :constraint :retryable? false
          :description "Attempted to delete a branch that still has child branches"}

         ;; Validation errors
         :validation-error/required-field-missing
         {:category :validation :retryable? false
          :description "Required field not provided"}

         :validation-error/duplicate-ids
         {:category :validation :retryable? false
          :description "Duplicate IDs in batch"}

         :validation-error/constraint-check-failed
         {:category :validation :retryable? false
          :description "Constraint validation query failed"}

         ;; Config errors
         :config-error/invalid-timeout
         {:category :config :retryable? false
          :description "Invalid timeout value"}

         :config-error/missing-jdbc-url
         {:category :config :retryable? false
          :description "JDBC URL not provided"}

         :config-error/credential-too-long
         {:category :config :retryable? false
          :description "Credential exceeds maximum length"}

         :config-error/invalid-credential
         {:category :config :retryable? false
          :description "Credential contains invalid characters"}

         ;; Connection errors
         :connection-error
         {:category :connection :retryable? true
          :description "Database connection failed"}

         :system-error/query-timeout
         {:category :connection :retryable? true
          :description "Query exceeded timeout"}

         ;; Execution errors
         :execution-error/graph-too-large
         {:category :execution :retryable? false
          :description "Graph resolution exceeded max iterations"}

         ;; Metadata errors
         :metadata-error/inconsistency
         {:category :metadata :retryable? false
          :description "Metadata doesn't match DB state"}

         :metadata-error/rollback-failed
         {:category :metadata :retryable? false :severity :error
          :description "Migration rollback failed"}

         ;; Batch errors
         :batch-error/partial-failure
         {:category :batch :retryable? false
          :description "Some operations in batch failed"}

         ;; Storage state
         :storage-not-initialized
         {:category :validation :retryable? false
          :description "CRUD attempted before initialize"}

         :not-found
         {:category :validation :retryable? false
          :description "Entity/record not found by ID"}

         ;; Transient errors (retryable)
         :transient-error/busy
         {:category :connection :retryable? true
          :description "Backend is temporarily busy"}

         :transient-error/unavailable
         {:category :connection :retryable? true
          :description "Backend is temporarily unavailable"}

         :transient-error/interrupted
         {:category :connection :retryable? true
          :description "Operation was interrupted"}

         :transient-error/execution
         {:category :execution :retryable? true
          :description "Execution failed, may succeed on retry"}

         ;; IO and unknown errors
         :io-error
         {:category :connection :retryable? true
          :description "I/O error during database operation"}

         :concurrent-modification
         {:category :constraint :retryable? true
          :description "Concurrent modification detected (CAS failure)"}

         :datomic-error
         {:category :unknown :retryable? false
          :description "Unclassified Datomic error"}

         :unknown-error
         {:category :unknown :retryable? false
          :description "Unclassified error"}}]
  (register-error-type! error-type metadata))


;; ============================================================================
;; Shared Error Wrapping
;; ============================================================================
;;
;; Generic error wrapping pattern shared by all storage backends.
;; Backend-specific wrappers classify the error, then delegate here.

(defn wrap-storage-error
  "Wraps an exception with application context, redacts before logging.
   Shared by all storage backends (postgres, datomic, memory).

   Parameters:
   - error-type: Classified error type keyword
   - exception: The original exception
   - log-prefix: String prefix for log message (e.g., \"Database error\")
   - operation: Keyword describing the operation (e.g., :create-entity)
   - context: Map of additional context (will be redacted for logging)"
  [error-type exception log-prefix operation context]
  (let [message (ex-message exception)
        safe-context (redaction/redact-sensitive-deep context)
        error-data (merge {:type error-type
                           :operation operation
                           :message message}
                          safe-context)]
    (log/warn log-prefix error-data)
    (ex-info (str log-prefix " during " (name operation) ": " message)
             (merge {:type error-type
                     :operation operation
                     :message message}
                    context)
             exception)))


;; ============================================================================
;; Unified Validation Error Factory
;; ============================================================================
;;
;; Provides consistent error creation across all validation code.
;; All validators should use these functions for consistency.

(defn create-validation-error
  "Creates a validation error with consistent structure.
   Use this for all validation failures to ensure consistent error shape.

   Arguments:
   - error-type: Namespaced keyword (e.g., :validation-error/required-field-missing)
   - message: Human-readable error message
   - context: Map with additional context (entity, field, value, etc.)

   The error-type MUST be a namespaced keyword. Common namespaces:
   - :validation-error/* - Input validation failures
   - :schema-error/* - Schema definition errors
   - :constraint-violation/* - Constraint check failures
   - :config-error/* - Configuration errors

   Returns an ex-info exception (does not throw).

   Example:
     (throw (create-validation-error
              :validation-error/required-field-missing
              \"Required field :name is missing\"
              {:entity :user :field :name}))"
  [error-type message context]
  (when-not (and (keyword? error-type) (namespace error-type))
    (throw (ex-info "error-type must be a namespaced keyword"
                    {:type :internal-error/invalid-error-type
                     :error-type error-type
                     :hint "Use namespaced keywords like :validation-error/field-missing"})))
  (ex-info message (assoc context :type error-type)))


(defn throw-validation-error!
  "Creates and throws a validation error with consistent structure.
   Convenience wrapper around create-validation-error.

   Arguments:
   - error-type: Namespaced keyword (e.g., :validation-error/required-field-missing)
   - message: Human-readable error message
   - context: Map with additional context

   Example:
     (throw-validation-error!
       :schema-error/invalid-identifier
       \"Identifier too long\"
       {:value :my-very-long-name :max-length 63})"
  [error-type message context]
  (throw (create-validation-error error-type message context)))


(defmacro with-storage-error-handling
  "Executes body with consistent error handling and wrapping.
   Catches exceptions and wraps them with operation context.

   Arguments:
   - error-type: Default error type if exception is not ExceptionInfo
   - operation: Keyword describing the operation (e.g., :create-entity)
   - context: Map with additional context
   - body: Code to execute

   If body throws ExceptionInfo, re-throws with merged context.
   If body throws other Exception, wraps in ExceptionInfo with error-type.

   Example:
     (with-storage-error-handling :storage-error/query-failed :query-entities
       {:entity-name :user}
       (execute-query sql-query))"
  [error-type operation context & body]
  `(try
     (do ~@body)
     (catch clojure.lang.ExceptionInfo e#
       ;; ExceptionInfo: merge context and re-throw
       (throw (ex-info (ex-message e#)
                       (merge {:operation ~operation} ~context (ex-data e#))
                       (ex-cause e#))))
     (catch Exception e#
       ;; Other exceptions: wrap with error-type
       (throw (ex-info (or (ex-message e#) "Storage operation failed")
                       (merge {:type ~error-type
                               :operation ~operation
                               :cause-type (type e#)}
                              ~context)
                       e#)))))
