(ns graphden.storage-protocol.config
  "Declarative configuration validation and shared runtime settings.

   ## Configuration Schemas
   Provides reusable Malli schemas and validation functions for storage backend
   configurations (PostgreSQL, Datomic).

   ## Runtime Configuration (Dynamic Variables)

   All dynamic variables can be rebound using `binding` or their `with-*` helpers.
   Default values are designed for production use cases.

   ### Query & Execution Limits
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*query-timeout-ms*`            | 30000    | Query timeout (min 1000ms for JDBC)   |
   | `*max-batch-size*`              | 1000     | Max entities per batch operation      |
   | `*max-graph-iterations*`        | 10000    | Max BFS iterations for graph resolve  |

   ### DoS Prevention Limits
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*max-lazy-seq-size*`           | 100000   | Max elements when realizing lazy seq  |
   | `*max-nested-collection-depth*` | 100      | Max recursion depth for collections   |
   | `*max-range-size*`              | 1000000  | Max elements for range function       |
   | `*max-repeat-size*`             | 1000000  | Max elements for repeat function      |

   ### Regex Safety
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*max-regex-length*`            | 100      | Max regex pattern length              |
   | `*max-regex-input-length*`      | 100000   | Max input string length for regex     |
   | `*regex-compile-timeout-ms*`    | 100      | Regex compilation timeout             |

   ### Cache Settings (in cache-protocol)
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*cache-load-timeout-ms*`       | 5000     | Timeout for cache load operations     |

   ### Executor Settings (in executor/context)
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `cache-max-size`                | 10000    | Max result cache entries (context)    |
   | `cache-warning-threshold`       | 1000     | Warning threshold for cache size      |
   | `max-depth`                     | 1000     | Max recursion depth (context)         |

   ## Usage Examples

   ```clojure
   ;; Increase query timeout for slow queries
   (config/with-query-timeout 60000
     #(sp/query-entities storage :large-table {}))

   ;; Increase batch size for bulk import
   (binding [config/*max-batch-size* 5000]
     (sp/create-entities storage :entity large-dataset))

   ;; Stricter regex limits for user input
   (config/with-regex-limits {:max-pattern-length 50}
     #(validate-user-regex pattern))
   ```"
  (:require
    [clojure.string :as str]
    [graphden.field-types.interface :as ft]
    [malli.core :as m]
    [malli.error :as me]))


;; === Common schemas ===

(def non-blank-string
  "Non-empty string that is not just whitespace."
  [:and :string [:fn {:error/message "must not be blank"}
                 (fn [s] (and (string? s) (seq (str/trim s))))]])


(def positive-int
  "Positive integer (> 0)."
  [:and :int [:> 0]])


(def non-negative-int
  "Non-negative integer (>= 0)."
  [:and :int [:>= 0]])


;; === PostgreSQL configuration schema ===

(defn- jdbc-url-has-embedded-credentials?
  "Returns true if JDBC URL contains embedded credentials (user:pass@host pattern).
   This is a security risk - credentials should be passed separately."
  [url]
  (boolean (re-find #"://[^/]*:[^/]*@" url)))


(def postgres-pool-config
  "Schema for PostgreSQL connection pool configuration."
  [:map
   {:closed true}
   [:jdbc-url [:and
               :string
               [:fn {:error/message "must start with 'jdbc:postgresql://'"}
                #(str/starts-with? % "jdbc:postgresql://")]
               [:fn {:error/message "must not contain embedded credentials (use :username/:password instead)"}
                #(not (jdbc-url-has-embedded-credentials? %))]]]
   [:username non-blank-string]
   [:password non-blank-string]
   [:pool-size {:optional true
                :default 10}
    [:and positive-int [:< 101]]]
   [:min-idle {:optional true
               :default 2}
    positive-int]
   [:connection-timeout {:optional true
                         :default 30000}
    positive-int]
   [:idle-timeout {:optional true
                   :default 600000}
    non-negative-int]
   [:max-lifetime {:optional true
                   :default 1800000}
    positive-int]
   [:leak-detection-threshold {:optional true
                               :default 60000}
    non-negative-int]])


;; === Datomic configuration schemas ===

(def datomic-local-config
  "Schema for datomic-local configuration."
  [:map
   {:closed true}
   [:server-type [:= :datomic-local]]
   [:system :string]
   [:storage-dir :string]
   [:db-name :string]])


(def datomic-peer-server-config
  "Schema for datomic peer-server configuration."
  [:map
   {:closed true}
   [:server-type [:= :peer-server]]
   [:endpoint :string]
   [:access-key :string]
   [:secret :string]
   [:db-name :string]])


(def datomic-ion-config
  "Schema for datomic ion configuration."
  [:map
   [:server-type [:= :ion]]
   [:region :string]
   [:system :string]
   [:db-name :string]])


(def datomic-cloud-config
  "Schema for datomic cloud configuration."
  [:map
   [:server-type [:= :cloud]]
   [:region :string]
   [:system :string]
   [:db-name :string]])


(def datomic-config
  "Schema for any Datomic configuration (union of all types)."
  [:or
   datomic-local-config
   datomic-peer-server-config
   datomic-ion-config
   datomic-cloud-config])


;; === Validation functions ===

(defn validate-config!
  "Validates configuration against a Malli schema.
   Throws ex-info with :type :config-error/invalid-config on failure.

   Arguments:
   - config: The configuration map to validate
   - schema: Malli schema to validate against
   - config-name: Human-readable name for error messages (e.g., \"PostgreSQL pool\")"
  [config schema config-name]
  (when-not (m/validate schema config)
    (let [explanation (m/explain schema config)
          errors (me/humanize explanation)]
      (throw (ex-info (str "Invalid " config-name " configuration: " (pr-str errors))
                      {:type :config-error/invalid-config
                       :config-name config-name
                       :errors errors
                       :config config})))))


(defn validate-postgres-config!
  "Validates PostgreSQL pool configuration.
   Throws ex-info on invalid configuration."
  [config]
  (validate-config! config postgres-pool-config "PostgreSQL pool")
  ;; Additional cross-field validations
  (let [{:keys [min-idle pool-size idle-timeout max-lifetime]
         :or {pool-size 10 min-idle 2 idle-timeout 600000 max-lifetime 1800000}} config]
    (when (> min-idle pool-size)
      (throw (ex-info "min-idle cannot exceed pool-size"
                      {:type :config-error/invalid-pool-config
                       :min-idle min-idle
                       :pool-size pool-size})))
    (when (and (pos? idle-timeout) (>= idle-timeout max-lifetime))
      (throw (ex-info "idle-timeout must be less than max-lifetime"
                      {:type :config-error/invalid-pool-config
                       :idle-timeout idle-timeout
                       :max-lifetime max-lifetime})))))


(defn validate-datomic-config!
  "Validates Datomic client configuration.
   Throws ex-info on invalid configuration."
  [config]
  (validate-config! config datomic-config "Datomic"))


(defn apply-defaults
  "Applies default values to a configuration map based on schema.
   Returns config with defaults filled in for missing optional fields."
  [config schema]
  (let [schema-form (m/form schema)]
    (if (and (vector? schema-form) (= :map (first schema-form)))
      (reduce
        (fn [cfg field-def]
          (if (and (vector? field-def) (>= (count field-def) 2))
            (let [field-name (first field-def)
                  field-props (when (map? (second field-def)) (second field-def))
                  default-val (:default field-props)]
              (if (and default-val (not (contains? cfg field-name)))
                (assoc cfg field-name default-val)
                cfg))
            cfg))
        config
        (rest schema-form))
      config)))


;; ============================================================================
;; Query Timeout Infrastructure
;; ============================================================================
;;
;; Shared query timeout handling for all storage backends.
;; Each backend can use these primitives to implement timeout consistently.

(def default-query-timeout-ms
  "Default timeout for storage queries in milliseconds.
   Used by PostgreSQL (via JDBC setQueryTimeout) and Datomic backends.
   Value: 30000ms (30 seconds) - reasonable default for most queries."
  30000)


(def ^:dynamic *query-timeout-ms*
  "Timeout for storage queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use `with-query-timeout` to temporarily change.

   Backend-specific notes:
   - PostgreSQL: Converted to seconds for JDBC setQueryTimeout
   - Datomic: Enforced via future+deref (no native timeout support)
   - Memory: Not applicable (in-memory operations are instant)"
  default-query-timeout-ms)


(def min-query-timeout-ms
  "Minimum allowed query timeout in milliseconds.
   1000ms (1 second) minimum because:
   - JDBC setQueryTimeout uses seconds, sub-second values round to 0
   - SQL queries need time for network roundtrip and query parsing
   - Different from executor timeout (50ms min) which covers overall execution"
  1000)


(defn validate-query-timeout!
  "Validates query timeout value. Throws on invalid timeout.

   Arguments:
   - timeout-ms: Timeout in milliseconds (must be positive integer >= 1000)

   Throws:
   - :config-error/invalid-timeout if timeout is not a positive integer
   - :config-error/invalid-timeout if timeout < min-query-timeout-ms"
  [timeout-ms]
  (when-not (pos-int? timeout-ms)
    (throw (ex-info "Query timeout must be a positive integer (ms)"
                    {:type :config-error/invalid-timeout
                     :timeout-ms timeout-ms})))
  (when (< timeout-ms min-query-timeout-ms)
    (throw (ex-info (str "Query timeout must be at least " min-query-timeout-ms "ms (1 second)")
                    {:type :config-error/invalid-timeout
                     :timeout-ms timeout-ms
                     :min-timeout-ms min-query-timeout-ms}))))


(defn with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).
   Timeout must be a positive integer >= 1000ms.

   Why 1000ms minimum?
   - JDBC setQueryTimeout uses seconds (integer), values <1000ms become 0
   - SQL queries need time for network roundtrip and query parsing
   - Different from executor timeout (50ms min) which covers overall execution

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (validate-query-timeout! timeout-ms)
  (binding [*query-timeout-ms* timeout-ms]
    (f)))


(defn get-query-timeout-seconds
  "Returns the current query timeout in seconds for JDBC calls.
   Reads the dynamic var *query-timeout-ms* and converts to seconds.

   Safety: Throws if timeout is below minimum to prevent silent timeout disabling.
   This catches improper direct binding of *query-timeout-ms*
   (use with-query-timeout instead)."
  []
  (when (< *query-timeout-ms* min-query-timeout-ms)
    (throw (ex-info (str "Query timeout must be at least " min-query-timeout-ms "ms")
                    {:type :config-error/invalid-timeout
                     :min-timeout-ms min-query-timeout-ms
                     :current-timeout-ms *query-timeout-ms*
                     :hint "Use with-query-timeout for safe rebinding"})))
  (quot *query-timeout-ms* 1000))


(defn execute-with-timeout!
  "Executes a query function with timeout enforcement via future+deref.

   Useful for backends that don't support native query timeout (e.g., Datomic).

   Arguments:
   - operation: keyword describing the operation (for error messages)
   - query-fn: zero-arg function that executes the query

   Returns the query result.
   Throws TimeoutException if query exceeds *query-timeout-ms*.
   Re-throws original exception if query fails (unwraps ExecutionException)."
  [operation query-fn]
  (let [timeout-ms *query-timeout-ms*
        fut (future (query-fn))
        result (try
                 (deref fut timeout-ms ::timeout)
                 (catch java.util.concurrent.ExecutionException e
                   ;; Unwrap ExecutionException to preserve original exception
                   (throw (or (Throwable/.getCause e) e))))]
    (if (= result ::timeout)
      (do
        (future-cancel fut)
        (throw (ex-info (str "Query timeout after " timeout-ms "ms")
                        {:type :system-error/query-timeout
                         :operation operation
                         :timeout-ms timeout-ms})))
      result)))


;; ============================================================================
;; Batch Size Configuration
;; ============================================================================
;;
;; Limits for batch operations to prevent OOM from huge batches.

(def ^:dynamic *max-batch-size*
  "Maximum number of entities in a single batch operation.
   Batch operations larger than this will throw an error.
   Default: 1000 entities."
  1000)


(defn validate-batch-size!
  "Validates that batch size is within allowed limits.
   Throws :batch-error/batch-too-large if batch exceeds *max-batch-size*.

   Arguments:
   - batch-size: number of items in the batch
   - operation: keyword describing the operation (for error messages)
   - context: additional context map for the error"
  [batch-size operation context]
  (when (> batch-size *max-batch-size*)
    (throw (ex-info (str "Batch size " batch-size " exceeds maximum allowed " *max-batch-size*)
                    (merge {:type :batch-error/batch-too-large
                            :batch-size batch-size
                            :max-batch-size *max-batch-size*
                            :operation operation}
                           context)))))


;; ============================================================================
;; Regex Safety Configuration
;; ============================================================================
;;
;; Configurable limits for regex operations to prevent ReDoS attacks.
;; These are defaults that can be overridden via dynamic vars.

(def ^:dynamic *max-regex-length*
  "Maximum length of regex pattern to prevent complex pattern attacks.
   Patterns longer than this are rejected.
   Default: 100 characters."
  100)


(def ^:dynamic *max-regex-input-length*
  "Maximum input string length for regex operations.
   Prevents catastrophic backtracking on large inputs.
   Default: 100000 characters (100KB)."
  100000)


(def ^:dynamic *regex-compile-timeout-ms*
  "Timeout for regex compilation in milliseconds.
   Catches patterns that take too long to compile.
   Default: 100ms."
  100)


(defn with-regex-limits
  "Executes f with custom regex safety limits.

   Arguments:
   - opts: map with optional keys:
     - :max-pattern-length - maximum regex pattern length
     - :max-input-length - maximum input string length
     - :compile-timeout-ms - regex compilation timeout
   - f: zero-arg function to execute

   Example:
   (with-regex-limits {:max-pattern-length 50 :max-input-length 10000}
     #(str-split my-string my-pattern))"
  [opts f]
  (binding [*max-regex-length* (get opts :max-pattern-length *max-regex-length*)
            *max-regex-input-length* (get opts :max-input-length *max-regex-input-length*)
            *regex-compile-timeout-ms* (get opts :compile-timeout-ms *regex-compile-timeout-ms*)]
    (f)))


;; ============================================================================
;; Lazy Sequence Safety Configuration
;; ============================================================================
;;
;; Configurable limits for lazy sequence realization to prevent DoS attacks.
;; User functions that return infinite or very large lazy sequences could
;; exhaust memory when realized.

(def ^:dynamic *max-lazy-seq-size*
  "Maximum number of elements allowed when realizing a lazy sequence.
   Sequences larger than this will throw an error.
   Default: 100000 elements.

   This protects against DoS via functions that return (range) or
   other infinite/large lazy sequences."
  100000)


(def ^:dynamic *max-nested-collection-depth*
  "Maximum depth for recursive collection realization.
   Prevents stack overflow from deeply nested structures.
   Default: 100 levels."
  100)


;; ============================================================================
;; Collection Generation Limits
;; ============================================================================
;;
;; Configurable limits for collection-generating functions (range, repeat)
;; to prevent memory exhaustion from large collections.

(def ^:dynamic *max-range-size*
  "Maximum number of elements allowed in range to prevent memory exhaustion.
   Default: 1000000 elements (1 million)."
  1000000)


(def ^:dynamic *max-repeat-size*
  "Maximum number of elements allowed in repeat to prevent memory exhaustion.
   Default: 1000000 elements (1 million)."
  1000000)


;; ============================================================================
;; Centralized Limits Registry
;; ============================================================================
;;
;; All hardcoded limits are defined here for easy discovery and configuration.
;; These are grouped by category and documented with rationale.

;; === Identifier Limits ===

(def max-identifier-length
  "Maximum length for SQL identifiers (entity names, field names, enum values).
   Re-exported from field-types for backwards compatibility.
   PostgreSQL truncates identifiers longer than 63 bytes (NAMEDATALEN - 1)."
  ft/max-identifier-length)


(def ^:const max-fn-name-length
  "Maximum length for function names in fn-registry.
   Matches PostgreSQL identifier limit for consistency."
  63)


;; === Credential Limits ===
;; Note: These are also defined in credential_validation.clj for backwards compatibility.
;; New code should use these directly from config.

(def ^:const max-credential-username-length
  "Maximum length for database username.
   Reasonable limit that covers all common database systems."
  128)


(def ^:const max-credential-password-length
  "Maximum length for database password.
   1024 allows for long generated passwords and passphrases."
  1024)


(def ^:const max-credential-jdbc-url-length
  "Maximum length for JDBC connection URLs.
   Generous limit to handle complex URLs with query parameters."
  4096)


;; === Batch Limits ===

(def ^:const max-sync-batch-size
  "Maximum definitions in a single sync-defs-to-storage! call.
   Prevents memory exhaustion from huge batch operations."
  500)


;; === Graph Traversal Limits ===

(def ^:const default-max-dependency-chain-depth
  "Default maximum depth for dependency chain traversal.
   Prevents infinite loops in graph resolution."
  1000)


;; === Cache Limits ===

(def ^:const default-cache-max-size
  "Default maximum entries in result cache.
   Prevents OOM from unbounded execution graphs."
  10000)


(def ^:const default-cache-warning-threshold
  "Default threshold for cache size warnings.
   Logs warning when cache reaches this size."
  1000)


(def ^:const cache-eviction-ratio
  "Ratio of cache entries to evict when cache is full.
   0.2 means evict 20% of entries (oldest first)."
  0.2)


;; === Execution Limits ===

(def ^:const max-path-args-count
  "Maximum number of path arguments in a request.
   Prevents excessive path segments."
  100)


(def ^:const warning-threshold-ratio
  "Ratio of limit at which to log warnings.
   0.8 means warn at 80% of limit."
  0.8)
