(ns graphden.storage-protocol.errors
  "Error classification, registry, and sensitive data redaction.

   Contains:
   - Canonical storage error types
   - Extensible error registry with metadata
   - Error context helpers
   - Sensitive data redaction utilities")


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
    :query-timeout
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

         :constraint-violation/parent-schema-mismatch
         {:category :constraint :retryable? false
          :description "Parent fn has different schema"}

         :constraint-violation/arg-already-defined
         {:category :constraint :retryable? false
          :description "Arg already defined in parent chain"}

         :constraint-violation/arg-schema-mismatch
         {:category :constraint :retryable? false
          :description "Arg schema doesn't belong to fn"}

         :constraint-violation/inheritance-cycle
         {:category :constraint :retryable? false
          :description "Cycle in parent-fn-id chain"}

         :constraint-violation/dependency-cycle
         {:category :constraint :retryable? false
          :description "Cycle in arg-value references"}

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

         :query-timeout
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
          :description "Entity/record not found by ID"}}]
  (register-error-type! error-type metadata))


;; === Sensitive Data Redaction ===
;;
;; Shared utilities for redacting sensitive data in logs and exceptions.
;; All storage implementations should use these to ensure consistent security.
;;
;; IMPORTANT: Any new sensitive field patterns should be added here.
;; All storage backends and the executor use these patterns for consistent
;; security across the entire Graphden system.

;; === Extensible Sensitive Field Registry ===
;;
;; Applications can register custom sensitive field names and patterns
;; for domain-specific requirements (PII, HIPAA, GDPR, etc.).

(def ^:private default-sensitive-field-names
  "Default field names that should always be redacted."
  #{:password :secret :token :api-key :auth-token
    :access-key :private-key :jdbc-url :connection-string
    :credentials :passphrase :pin :ssn :credit-card})


(def ^:private default-sensitive-field-patterns
  "Default regex patterns for identifying sensitive field names."
  [#"(?i)pass(word|wd)?"
   #"(?i)secret"
   #"(?i)token"
   #"(?i)api[_-]?key"
   #"(?i)auth"
   #"(?i)credential"
   #"(?i)private[_-]?key"
   #"(?i)access[_-]?key"
   #"(?i)connection[_-]?string"
   #"(?i)jdbc[_-]?url"])


(def ^:private sensitive-field-registry
  "Extensible registry for sensitive field detection.
   Contains:
   - :names - set of explicit field name keywords
   - :patterns - vector of regex patterns
   - :predicates - vector of custom predicate functions"
  (atom {:names default-sensitive-field-names
         :patterns default-sensitive-field-patterns
         :predicates []}))


(defn register-sensitive-field-name!
  "Registers an explicit field name as sensitive.
   The name will be matched exactly (case-insensitive for keywords).

   Arguments:
   - field-name: keyword to treat as sensitive (e.g., :social-security-number)

   Example:
     (register-sensitive-field-name! :employee-id)
     (register-sensitive-field-name! :medical-record-number)"
  [field-name]
  (when-not (keyword? field-name)
    (throw (ex-info "field-name must be a keyword"
                    {:type :invalid-argument
                     :field-name field-name})))
  (swap! sensitive-field-registry update :names conj field-name)
  field-name)


(defn register-sensitive-field-pattern!
  "Registers a regex pattern for matching sensitive field names.
   Pattern will be tested against field name strings.

   Arguments:
   - pattern: compiled regex pattern (java.util.regex.Pattern)

   Example:
     (register-sensitive-field-pattern! #\"(?i)patient[_-]?id\")
     (register-sensitive-field-pattern! #\"(?i)hipaa\")
     (register-sensitive-field-pattern! #\"(?i)gdpr[_-]?data\")"
  [pattern]
  (when-not (instance? java.util.regex.Pattern pattern)
    (throw (ex-info "pattern must be a compiled regex"
                    {:type :invalid-argument
                     :pattern pattern
                     :pattern-type (type pattern)})))
  (swap! sensitive-field-registry update :patterns conj pattern)
  pattern)


(defn register-sensitive-field-predicate!
  "Registers a custom predicate function for sensitive field detection.
   The predicate receives a field name keyword and returns truthy if sensitive.

   Use this for complex matching logic that can't be expressed as regex.

   Arguments:
   - pred-fn: (fn [field-name-keyword] -> boolean)

   Example:
     ;; Mark all fields in specific namespace as sensitive
     (register-sensitive-field-predicate!
       (fn [k] (= \"pii\" (namespace k))))

     ;; Mark fields with certain metadata
     (register-sensitive-field-predicate!
       (fn [k] (some-> k resolve meta :sensitive)))"
  [pred-fn]
  (when-not (fn? pred-fn)
    (throw (ex-info "pred-fn must be a function"
                    {:type :invalid-argument
                     :pred-fn pred-fn})))
  (swap! sensitive-field-registry update :predicates conj pred-fn)
  pred-fn)


(defn reset-sensitive-field-registry!
  "Resets the sensitive field registry to defaults.
   Useful for testing or when reconfiguring the application.

   WARNING: This removes all custom registrations."
  []
  (reset! sensitive-field-registry
          {:names default-sensitive-field-names
           :patterns default-sensitive-field-patterns
           :predicates []})
  nil)


(defn sensitive-field-names
  "Returns the current set of explicitly registered sensitive field names.
   Includes both default and custom registered names."
  []
  (:names @sensitive-field-registry))


(defn sensitive-field-patterns
  "Returns the current vector of sensitive field regex patterns.
   Includes both default and custom registered patterns."
  []
  (:patterns @sensitive-field-registry))


(defn sensitive-field?
  "Returns true if field name matches known sensitive patterns.
   Checks in order:
   1. Explicit field names (fast exact match)
   2. Regex patterns
   3. Custom predicate functions

   Handles keywords, strings, and nil gracefully.

   This function uses the extensible sensitive field registry.
   Use register-sensitive-field-name!, register-sensitive-field-pattern!,
   or register-sensitive-field-predicate! to add custom detection rules."
  [field-name]
  (when field-name
    (let [kw (if (keyword? field-name) field-name (keyword field-name))
          name-str (name kw)
          {:keys [names patterns predicates]} @sensitive-field-registry]
      (when (seq name-str)
        (or
          ;; Check explicit names first (fast exact match)
          (contains? names kw)
          ;; Then check patterns (regex matching)
          (some #(re-find % name-str) patterns)
          ;; Finally check custom predicates
          (some #(% kw) predicates))))))


(defn redact-sensitive-map
  "Redacts values for sensitive keys in a map.
   Non-recursive - only checks top-level keys.
   Use redact-sensitive-deep for nested structures."
  [m]
  (when (map? m)
    (reduce-kv (fn [acc k v]
                 (assoc acc k (if (sensitive-field? k) "[REDACTED]" v)))
               {}
               m)))


(defn redact-sensitive-deep
  "Recursively redacts values for keys matching sensitive patterns.
   Preserves structure but replaces sensitive values with [REDACTED].
   Handles maps, vectors, lists, and sets. Other values pass through unchanged.

   Use this for logging/error messages that may contain nested sensitive data.
   For simple flat maps, redact-sensitive-map is more efficient."
  [data]
  (cond
    (map? data)
    (into {}
          (map (fn [[k v]]
                 [k (if (sensitive-field? k)
                      "[REDACTED]"
                      (redact-sensitive-deep v))])
               data))

    (vector? data)
    (mapv redact-sensitive-deep data)

    (set? data)
    (set (map redact-sensitive-deep data))

    (seq? data)
    (map redact-sensitive-deep data)

    :else data))
