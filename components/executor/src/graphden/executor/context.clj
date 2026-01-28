(ns graphden.executor.context
  "Execution context for the function executor.

   Contains:
   - ExecutionContext record definition
   - Context creation and validation
   - Execution limits and constants"
  (:require
    [graphden.executor.registry :as registry]
    [graphden.storage-protocol.interface :as sp]))


;; === ExecutionContext Record ===

(defrecord ExecutionContext
  [storage
   execution-graph  ; Cached graph from resolve-execution-graph
   base-fns         ; Map of fn-name -> fn, for base function lookup
   max-depth
   timeout-ms
   start-time
   depth
   call-site-args   ; Map of runtime args: {arg-schema-id -> value} for root fn,
   ;; {[fn-result-value-id arg-schema-id] -> value} for nested fns (call sites)
   current-frv-id   ; Current fn-result-value-id (nil for root function)
   result-cache     ; Atom: {fn-result-value-id -> computed-value} for caching
   ;; LIFECYCLE: result-cache is created in create-context (atom {}), populated
   ;; during execution by execute-fn-result-value (memoizes fn results), and
   ;; becomes eligible for GC when context goes out of scope. Cache is bounded by
   ;; cache-max-size to prevent OOM in unbounded execution graphs.
   strict-type-validation?  ; If true, throw on unknown types; if false, warn and accept
   max-unknown-types        ; Limit for unknown types in forward compat mode (circuit breaker)
   unknown-type-counter     ; Atom: count of unknown types (circuit breaker for forward compat)
   clock                    ; Function returning current time in millis (for testing)
   cache-warning-threshold  ; Threshold for warning about large cache
   cache-max-size])         ; Maximum cache size before error


;; === Execution Limits ===

(def ^:private max-depth-limit
  "Upper limit for max-depth to prevent accidental resource exhaustion.
   100000 is generous but prevents typos like 1000000000."
  100000)


(def ^:private min-timeout-ms
  "Minimum allowed timeout in milliseconds.
   50ms allows for fast test cases while preventing unrealistic timeouts.

   Note: This is different from storage query timeout (min 1000ms in postgres-storage).
   - Executor timeout: overall execution time for a function graph (includes all queries)
   - Storage query timeout: single database query timeout (JDBC setQueryTimeout uses seconds)

   Executor timeout can be lower because it measures in-memory computation,
   while storage queries need network roundtrip and query parsing time."
  50)


;; max-unknown-types is now configurable via context, see create-context
;; Default value is sp/default-max-unknown-types (10)


(def default-cache-warning-threshold
  "Default threshold for result-cache size that triggers a warning log.
   Large caches indicate potentially unbounded execution graphs.
   When exceeded, a warning is logged to help diagnose memory issues.

   Why 1000? Typical function graphs have <100 nodes. 1000 unique fn-result-value
   entries indicates either a very deep recursion, infinite loop, or exponential
   branching. This threshold is high enough to avoid false positives in legitimate
   large graphs while catching runaway executions before they exhaust memory."
  1000)


(def default-cache-max-size
  "Default maximum size for result-cache before rejecting new entries.
   Provides hard limit to prevent OOM in unbounded execution graphs.
   When reached, new fn-result-values will throw an error.

   Why 10000? This is 10x the warning threshold. If you've hit 10K unique
   fn-result-values, the graph is almost certainly unbounded or misconfigured.
   At ~1KB per cached value average, this limits memory to ~10MB per execution."
  10000)


(def timeout-warning-window-ms
  "Window size in milliseconds for timeout warning logs.
   Used to avoid repeated warnings when execution is near timeout threshold.
   Warning fires once when elapsed time enters [threshold, threshold+window) range.

   Why 100ms? This window should be small enough to give timely warning (not too
   far before actual timeout) but large enough to account for timing jitter in
   time checks. 100ms is ~0.3% of the default 30s timeout - negligible but
   sufficient to deduplicate warnings across multiple function calls."
  100)


(def default-timeout-ms
  "Default execution timeout in milliseconds.
   Uses the shared constant from storage-protocol for consistency."
  sp/default-query-timeout-ms)


(def error-value-truncation-length
  "Maximum length for values in error messages.
   Prevents huge exception data for large values.

   Why 100 chars? Long enough to show meaningful context (e.g., first part of
   a string, several collection items) but short enough to keep exception data
   readable in logs and stack traces. Error messages are typically viewed in
   full, so slightly more verbose is acceptable."
  100)


(def max-call-site-args-count
  "Maximum number of call-site-args allowed.
   Prevents potential memory exhaustion from large call-site-args maps.

   Why 1000? Typical execution graphs have <100 arguments. 1000 unique call-site-args
   indicates either a very deep graph or potential abuse. This limit prevents
   DoS attacks via oversized call-site-args while accommodating legitimate large graphs."
  1000)


(def ^:private nested-call-site-arg-key-length
  "Length of call-site-arg key tuple for nested functions.
   Format: [fn-result-value-id arg-schema-id]
   - fn-result-value-id: identifies which call site (fn-result-value) references the function
   - arg-schema-id: identifies which argument within that function"
  2)


(def warning-threshold-ratio
  "Ratio of limit at which to log warning. 0.8 = warn at 80% of limit."
  0.8)


;; === Context Validation ===

(defn- valid-call-site-arg-key?
  "Returns true if key is a valid call-site-arg key format.

   Valid formats:
   - UUID: for root function arguments (looked up by arg-schema-id directly)
   - [UUID UUID]: for nested function arguments via fn-result-value (call site)
     Format is [fn-result-value-id arg-schema-id]"
  [k]
  (or (uuid? k)
      (and (vector? k)
           (= nested-call-site-arg-key-length (count k))
           (every? uuid? k))))


(defn- validate-context-options!
  "Validates context creation options. Throws on invalid options.
   Collects ALL validation errors and reports them together for better UX,
   so users can fix multiple issues in one iteration instead of one at a time."
  [storage timeout-ms max-depth call-site-args]
  (let [errors (cond-> []
                 ;; Required: storage
                 (not storage)
                 (conj {:error "Storage is required"})

                 ;; Storage protocol validation - catch wrong type early with clear error
                 ;; rather than cryptic "No implementation of method" later at first CRUD call
                 (and storage (not (satisfies? sp/ExecutionGraph storage)))
                 (conj {:error "storage must implement ExecutionGraph protocol"
                        :received-type (type storage)})

                 ;; Optional timeout-ms validation
                 (and timeout-ms (< timeout-ms min-timeout-ms))
                 (conj {:error (str "timeout-ms must be at least " min-timeout-ms "ms")
                        :timeout-ms timeout-ms
                        :min-allowed min-timeout-ms})

                 ;; Optional max-depth validation
                 (and max-depth (not (pos-int? max-depth)))
                 (conj {:error "max-depth must be a positive integer"
                        :max-depth max-depth})

                 (and max-depth (pos-int? max-depth) (> max-depth max-depth-limit))
                 (conj {:error (str "max-depth exceeds maximum allowed value of " max-depth-limit)
                        :max-depth max-depth
                        :max-allowed max-depth-limit})

                 ;; Optional call-site-args type validation
                 (and (some? call-site-args) (not (map? call-site-args)))
                 (conj {:error "call-site-args must be a map"
                        :call-site-args-type (type call-site-args)})

                 ;; call-site-args count validation
                 (and (map? call-site-args) (> (count call-site-args) max-call-site-args-count))
                 (conj {:error (str "call-site-args count exceeds maximum allowed value of " max-call-site-args-count)
                        :call-site-args-count (count call-site-args)
                        :max-allowed max-call-site-args-count}))
        ;; Validate call-site-args keys format (collect all invalid keys)
        invalid-keys (when (map? call-site-args)
                       (into []
                             (comp (map first)
                                   (filter (complement valid-call-site-arg-key?)))
                             call-site-args))
        errors (if (seq invalid-keys)
                 (conj errors {:error "call-site-args keys must be UUID or [UUID UUID] vector"
                               :invalid-keys invalid-keys})
                 errors)]
    (when (seq errors)
      (throw (ex-info (if (= 1 (count errors))
                        (:error (first errors))
                        (str "Multiple validation errors (" (count errors) ")"))
                      {:type :execution-error/invalid-context
                       :validation-errors errors})))))


;; === Context Creation ===

(defn create-context
  "Creates initial execution context. Note: execution-graph is populated
   later when execute is called with a root fn-id.

   Options:
   - :storage - Storage instance (required)
   - :base-fns - Map of fn-name -> fn for base function lookup (optional, uses default registry if not provided)
   - :max-depth - Maximum recursion depth (default 1000)
   - :timeout-ms - Maximum execution time in ms (default 30000)
   - :call-site-args - Map of runtime args for specific call sites (optional):
                       For root function: {arg-schema-id -> value}
                       For nested fns via fn-result-value: {[fn-result-value-id arg-schema-id] -> value}
                       IMPORTANT: call-site-args can only set args that have NO value in DB.
                       If arg-value exists in DB, call-site-arg is ignored (warning logged).
                       To override DB values, use provided-args in execute call instead.
   - :strict-type-validation? - If true (default), throw on unknown types.
                                If false, warn and accept (forward compatibility mode).
   - :max-unknown-types - Maximum unknown types allowed in forward compat mode (default 10).
                          Acts as circuit breaker to detect schema version mismatches.
   - :clock - Function returning current time in milliseconds (default: System/currentTimeMillis).
              Inject a custom clock for deterministic timeout testing.
   - :cache-warning-threshold - Threshold for result-cache size warning (default: 1000).
   - :cache-max-size - Maximum result-cache size before error (default: 10000).

   ## Forward Compatibility Mode (:strict-type-validation? false)

   When enabled, the executor will accept arguments with unknown types instead
   of throwing an exception. This is useful for:

   1. **Rolling deployments**: When updating schema, old executor instances can
      continue processing requests with new types until they're upgraded.

   2. **API versioning**: Clients may send data with types that your version
      doesn't recognize yet.

   3. **Schema evolution**: Allows gradual migration without breaking existing
      functionality.

   Behavior:
   - Unknown types are accepted without validation (any value passes)
   - A warning is logged with type info and value preview
   - Known types are still validated strictly
   - Circuit breaker throws if unknown types exceed :max-unknown-types

   Risks:
   - Type mismatches for new types won't be caught until the base function runs
   - Data integrity relies on the base function's own validation

   Recommendation: Use strict mode (default) in development, consider permissive
   mode for production deployments during schema migrations.

   Validates:
   - storage is required
   - timeout-ms must be at least 50ms (allows for fast test cases)
   - max-depth must be positive and <= 100000
   - call-site-args must be a map if provided"
  [{:keys [storage base-fns max-depth timeout-ms call-site-args strict-type-validation?
           max-unknown-types clock cache-warning-threshold cache-max-size]
    :or {max-depth sp/default-max-depth
         timeout-ms default-timeout-ms
         call-site-args {}
         strict-type-validation? true
         max-unknown-types sp/default-max-unknown-types
         cache-warning-threshold default-cache-warning-threshold
         cache-max-size default-cache-max-size}}]
  (validate-context-options! storage timeout-ms max-depth call-site-args)
  (let [fns (or base-fns (registry/get-default-registry))
        clock-fn (or clock #(System/currentTimeMillis))]
    (->ExecutionContext storage nil fns max-depth timeout-ms (clock-fn) 0
                        (or call-site-args {}) nil (atom {}) strict-type-validation?
                        max-unknown-types (atom 0) clock-fn
                        cache-warning-threshold cache-max-size)))


(defn current-time-ms
  "Returns current time in milliseconds using the context's clock.
   This allows for deterministic testing of timeout behavior."
  [context]
  ((:clock context)))


(defn clear-result-cache!
  "Clears the result cache in the given context.
   Useful for long-running applications that reuse contexts across multiple executions.

   The result cache stores computed fn-result-values to avoid recomputation within
   a single execution. When reusing a context across multiple independent executions,
   call this between executions to:
   - Free memory from previous execution results
   - Ensure fresh computation for the next execution

   Returns the number of entries that were cleared.

   Example:
   (let [ctx (create-context {:storage s})]
     (execute ctx fn-id-1 nil)
     (clear-result-cache! ctx)  ; Clear before next independent execution
     (execute ctx fn-id-2 nil))"
  [context]
  (let [cache (:result-cache context)
        count-before (count @cache)]
    (reset! cache {})
    count-before))
