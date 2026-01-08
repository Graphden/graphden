(ns graphden.executor.core
  "Core implementation of the function executor."
  (:require
    [clojure.tools.logging :as log]
    [graphden.field-types.interface :as ft]
    [graphden.storage-protocol.interface :as sp]))


;; === Lazy Arguments ===
;;
;; Arguments are passed to base functions as Clojure `delay` objects.
;; This enables lazy evaluation - values are only computed when dereferenced.
;;
;; For :fn type arguments, the delay contains a callable (a Clojure function)
;; that can be invoked with a map of named arguments.
;;
;; Base functions should use @ (deref) to get values:
;;   (+ @a @b)           ; for regular args
;;   (f {:item x})       ; for :fn args (f is already a callable after deref)
;;
;; The defbase macro in fn-registry handles this automatically.


;; === Base Functions Registry ===

;; Default global registry for convenience.
;; For better testability, use :base-fns in create-context.
(defonce ^:private default-registry (atom {}))


(defn register-base-fn!
  "Registers a base function in the default global registry.
   fn-name - keyword identifying the function (must match fn-schema name)
   f - function taking [thunks context] and returning the result

   For better testability, consider using :base-fns in create-context instead."
  [fn-name f]
  (swap! default-registry assoc fn-name f)
  nil)


(defn get-base-fn
  "Retrieves a registered base function by name from the default global registry.
   Returns the function or nil if not found.

   For context-aware lookup, use get-base-fn-from-context."
  [fn-name]
  (get @default-registry fn-name))


(defn clear-base-fns!
  "Clears all registered base functions from the default global registry.
   Primarily used in tests to reset state between test cases."
  []
  (reset! default-registry {})
  nil)


(defn get-default-registry
  "Returns the current state of the default global registry.
   Useful for passing to create-context."
  []
  @default-registry)


(defn get-base-fn-from-context
  "Retrieves a base function by name from the context's registry.
   Returns the function or nil if not found."
  [context fn-name]
  (get (:base-fns context) fn-name))


;; === Execution Context ===

(defrecord ExecutionContext
  [storage
   execution-graph  ; Cached graph from resolve-execution-graph
   base-fns         ; Map of fn-name -> fn, for base function lookup
   max-depth
   timeout-ms
   start-time
   depth
   path-args        ; Map of runtime args: {arg-schema-id -> value} for root fn,
   ;; {[fn-result-value-id arg-schema-id] -> value} for nested fns
   current-frv-id   ; Current fn-result-value-id (nil for root function)
   result-cache     ; Atom: {fn-result-value-id -> computed-value} for caching
   strict-type-validation?])  ; If true, throw on unknown types; if false, warn and accept


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


(def ^:private default-timeout-ms
  "Default execution timeout in milliseconds.
   Uses the shared constant from storage-protocol for consistency."
  sp/default-query-timeout-ms)


(def ^:private error-value-truncation-length
  "Maximum length for values in error messages.
   Prevents huge exception data for large values."
  100)


(def ^:private log-value-truncation-length
  "Maximum length for values in log messages.
   Shorter than error messages to reduce sensitive data exposure in logs."
  20)


(def ^:private max-path-args-count
  "Maximum number of path-args allowed.
   Prevents potential memory exhaustion from large path-args maps."
  10000)


(defn- valid-path-arg-key?
  "Returns true if key is a valid path-arg key format.
   Valid formats: UUID or [UUID UUID] vector."
  [k]
  (or (uuid? k)
      (and (vector? k)
           (= 2 (count k))
           (every? uuid? k))))


(defn- validate-context-options!
  "Validates context creation options. Throws on invalid options."
  [storage timeout-ms max-depth path-args]
  (when-not storage
    (throw (ex-info "Storage is required" {:type :execution-error/invalid-context})))
  (when (and timeout-ms (< timeout-ms min-timeout-ms))
    (throw (ex-info (str "timeout-ms must be at least " min-timeout-ms "ms")
                    {:type :execution-error/invalid-context
                     :timeout-ms timeout-ms
                     :min-allowed min-timeout-ms})))
  (when (and max-depth (not (pos-int? max-depth)))
    (throw (ex-info "max-depth must be a positive integer"
                    {:type :execution-error/invalid-context
                     :max-depth max-depth})))
  (when (and max-depth (> max-depth max-depth-limit))
    (throw (ex-info (str "max-depth exceeds maximum allowed value of " max-depth-limit)
                    {:type :execution-error/invalid-context
                     :max-depth max-depth
                     :max-allowed max-depth-limit})))
  (when (and (some? path-args) (not (map? path-args)))
    (throw (ex-info "path-args must be a map"
                    {:type :execution-error/invalid-context
                     :path-args path-args
                     :path-args-type (type path-args)})))
  ;; Validate path-args count to prevent memory exhaustion
  (when (and path-args (> (count path-args) max-path-args-count))
    (throw (ex-info (str "path-args count exceeds maximum allowed value of " max-path-args-count)
                    {:type :execution-error/invalid-context
                     :path-args-count (count path-args)
                     :max-allowed max-path-args-count})))
  ;; Validate path-args keys format for security
  (doseq [[k _v] path-args]
    (when-not (valid-path-arg-key? k)
      (throw (ex-info "path-args keys must be UUID or [UUID UUID] vector"
                      {:type :execution-error/invalid-path-args-key
                       :invalid-key k
                       :key-type (type k)})))))


(defn create-context
  "Creates initial execution context. Note: execution-graph is populated
   later when execute is called with a root fn-id.

   Options:
   - :storage - Storage instance (required)
   - :base-fns - Map of fn-name -> fn for base function lookup (optional, uses default registry if not provided)
   - :max-depth - Maximum recursion depth (default 1000)
   - :timeout-ms - Maximum execution time in ms (default 30000)
   - :path-args - Map of runtime args (optional):
                  For root function: {arg-schema-id -> value}
                  For nested fns via fn-result-value: {[fn-result-value-id arg-schema-id] -> value}
                  IMPORTANT: path-args can only set args that have NO value in DB.
                  If arg-value exists in DB, path-arg is ignored (warning logged).
                  To override DB values, use provided-args in execute call instead.
   - :strict-type-validation? - If true (default), throw on unknown types.
                                If false, warn and accept (forward compatibility mode).

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

   Risks:
   - Type mismatches for new types won't be caught until the base function runs
   - Data integrity relies on the base function's own validation

   Recommendation: Use strict mode (default) in development, consider permissive
   mode for production deployments during schema migrations.

   Validates:
   - storage is required
   - timeout-ms must be at least 50ms (allows for fast test cases)
   - max-depth must be positive and <= 100000
   - path-args must be a map if provided"
  [{:keys [storage base-fns max-depth timeout-ms path-args strict-type-validation?]
    :or {max-depth sp/default-max-depth
         timeout-ms default-timeout-ms
         path-args {}
         strict-type-validation? true}}]
  (validate-context-options! storage timeout-ms max-depth path-args)
  (let [fns (or base-fns @default-registry)]
    (->ExecutionContext storage nil fns max-depth timeout-ms (System/currentTimeMillis) 0
                        (or path-args {}) nil (atom {}) strict-type-validation?)))


;; Forward declaration for mutual recursion
(declare execute-internal)


;; === Graph Resolution ===
;; Note: The actual graph resolution is now done by storage's resolve-execution-graph
;; which fetches everything in one call. See ExecutionGraph protocol.


;; === Thunk Building ===

;; Use sp/redact-sensitive-deep from storage-protocol for consistent sensitive data redaction


(defn- truncate-value
  "Truncates large values for error messages to avoid huge exception data.
   Returns a shortened representation for display purposes.
   Redacts sensitive data (passwords, tokens, etc.) before truncation.

   Uses pr-str for consistent Clojure-readable output. The max-len parameter
   controls truncation; callers typically use 100 chars for error context."
  [value max-len]
  (let [redacted (sp/redact-sensitive-deep value)
        s (pr-str redacted)]
    (if (> (count s) max-len)
      (str (subs s 0 max-len) "...")
      s)))


(def ^:private type-hints
  "Human-readable hints for expected Clojure types."
  {:fn "UUID (function reference)"
   :ref "UUID (entity reference)"
   :int "integer (e.g., 42, -1)"
   :bool "boolean (true or false)"
   :text "string (e.g., \"hello\")"
   :numeric "number (int, float, bigdec, ratio)"
   :jsonb "map or vector"
   :bytes "byte array (byte-array)"
   :timestamptz "java.time.Instant, java.time.LocalDateTime, or java.util.Date"
   :enum "keyword (e.g., :active, :pending)"
   :uuid "UUID"})


(defn- throw-type-mismatch!
  "Throws a type mismatch error with detailed context."
  [arg-schema provided-value]
  (let [arg-type (:type arg-schema)
        arg-name (:name arg-schema)
        arg-schema-id (:id arg-schema)
        hint (get type-hints arg-type (name arg-type))]
    (throw (ex-info (str "Type mismatch for argument '" arg-name "': "
                         "expected " (name arg-type) " (" hint "), "
                         "got " (-> provided-value class .getSimpleName))
                    {:type :execution-error/type-mismatch
                     :arg-name arg-name
                     :arg-schema-id arg-schema-id
                     :expected-type arg-type
                     :expected-hint hint
                     :provided-value (truncate-value provided-value error-value-truncation-length)
                     :provided-type (type provided-value)}))))


(defn- type-mismatch?
  "Returns true if provided-value doesn't match the expected arg-type.

   Type validation rules:
   - Known types: strict validation based on Clojure predicates (from field-types)
   - :union type: accepts any value (validation happens at schema level,
     where union variants are checked against allowed types)
   - Unknown types: behavior depends on strict? flag:
     - strict?=true (default): throws exception to catch schema mismatches early
     - strict?=false: returns false (permissive) for forward compatibility

   This is a runtime check for user-provided arguments only. Values from
   the execution graph (arg-values) are assumed to be already validated."
  [arg-type provided-value strict?]
  (if (contains? ft/type-validators arg-type)
    (not (ft/valid-type? arg-type provided-value))
    ;; Unknown type - behavior depends on strict mode
    (if strict?
      (throw (ex-info "Unknown argument type encountered"
                      {:type :execution-error/unknown-arg-type
                       :arg-type arg-type
                       :value-type (type provided-value)
                       :hint "Set :strict-type-validation? false in context for forward compatibility"}))
      ;; Forward compatibility mode: accept unknown types with warning
      ;; This allows newer schema versions to introduce new types without
      ;; breaking existing deployments. The warning helps identify potential issues.
      (do
        (log/warn "Unknown argument type encountered in forward compatibility mode"
                  {:arg-type arg-type
                   :value-type (type provided-value)
                   :value-preview (truncate-value provided-value log-value-truncation-length)
                   :action :accepting-without-validation
                   :hint "Consider upgrading to support this type natively"})
        false))))


(defn- validate-provided-arg-type!
  "Validates that a provided argument matches the expected arg-schema type.
   Throws ExceptionInfo with detailed context if type mismatch detected.

   Called from build-thunk when a user provides an argument that overrides
   a stored arg-value. This ensures user-provided values match the expected
   type before creating a LiteralThunk.

   Note: Stored arg-values from the execution graph are not validated here;
   they are assumed to be valid from schema-level checks during creation."
  [provided-value arg-schema strict?]
  (when-not (and arg-schema (:type arg-schema))
    (throw (ex-info "Invalid arg-schema: missing type"
                    {:type :execution-error/invalid-arg-schema
                     :arg-schema arg-schema})))
  (when (type-mismatch? (:type arg-schema) provided-value strict?)
    (throw-type-mismatch! arg-schema provided-value)))


(defn- get-fn-data-from-graph
  "Gets function data from the cached execution graph.
   Returns {:fn fn-rec :fn-schema fn-schema-rec :arg-schemas {...} :arg-values {...}}"
  [execution-graph fn-id]
  (let [{:keys [fns fn-schemas arg-schemas resolved-args]} execution-graph
        fn-rec (get fns fn-id)]
    (when-not fn-rec
      (throw (ex-info "Function not found in execution graph"
                      {:type :execution-error/fn-not-found
                       :fn-id fn-id})))
    (let [fn-schema-id (:fn-schema-id fn-rec)
          fn-schema (get fn-schemas fn-schema-id)]
      (when-not fn-schema
        (throw (ex-info "Function schema not found in execution graph"
                        {:type :execution-error/fn-schema-not-found
                         :fn-id fn-id
                         :fn-schema-id fn-schema-id})))
      ;; Filter arg-schemas to only those belonging to this fn-schema
      (let [fn-arg-schemas (->> arg-schemas
                                (filter (fn [[_ as]] (= (:fn-schema-id as) fn-schema-id)))
                                (into {}))
            arg-values (get resolved-args fn-id {})]
        {:fn fn-rec
         :fn-schema fn-schema
         :arg-schemas fn-arg-schemas
         :arg-values arg-values}))))


(defn- get-required-arg-schemas
  "Returns a sequence of required arg-schemas for a function.
   Used by HOF helpers to find function's required arguments."
  [execution-graph fn-id]
  (let [{:keys [arg-schemas]} (get-fn-data-from-graph execution-graph fn-id)]
    (->> arg-schemas
         vals
         (filter #(:required % true)))))  ; default required=true


(defn get-single-required-arg
  "Gets the single required arg-schema for a function.
   Used by HOF (map, filter, etc.) to find the target argument.

   Returns {:id arg-schema-id :name arg-name :type arg-type}

   Throws if the function doesn't have exactly one required argument."
  [context fn-id]
  (let [required-args (get-required-arg-schemas (:execution-graph context) fn-id)
        count-required (count required-args)]
    (when (not= count-required 1)
      (throw (ex-info (str "HOF function requires exactly 1 required argument, got " count-required)
                      {:type :execution-error/invalid-hof-function
                       :fn-id fn-id
                       :required-arg-count count-required
                       :required-args (mapv #(select-keys % [:id :name :type]) required-args)})))
    (let [arg-schema (first required-args)]
      {:id (:id arg-schema)
       :name (:name arg-schema)
       :type (:type arg-schema)})))


(defn make-single-arg-callable
  "Creates a callable for a function with exactly one required argument.
   The callable accepts a single value (not a map) and passes it to that argument.

   Used by HOF (map, filter, etc.) to call user functions without requiring
   specific argument names.

   Example:
   ;; User function 'double' has one arg :x
   ;; (callable 5) calls double with {:x-schema-id 5}

   Returns a function: value -> result"
  [context fn-id]
  (let [arg-schema-id (:id (get-single-required-arg context fn-id))]
    (fn [value]
      (execute-internal context fn-id {arg-schema-id value}))))


(defn- execute-fn-result-value
  "Executes a fn-result-value, using cache for memoization.
   If the fn-result-value-id is already in cache, returns cached value.
   Otherwise executes the underlying fn, caches the result, and returns it.
   Logs debug info for cache performance monitoring."
  [context fn-result-value-id]
  (let [result-cache (:result-cache context)
        cached (get @result-cache fn-result-value-id)]
    (if (some? cached)
      ;; Cache hit - return cached value
      (do
        (log/debug "fn-result-value cache hit"
                   {:fn-result-value-id fn-result-value-id
                    :cache-size (count @result-cache)})
        cached)
      ;; Cache miss - execute and cache
      (let [execution-graph (:execution-graph context)
            fn-result-values (:fn-result-values execution-graph)
            frv (get fn-result-values fn-result-value-id)]
        (when-not frv
          (throw (ex-info "fn-result-value not found in execution graph"
                          {:type :execution-error/fn-result-value-not-found
                           :fn-result-value-id fn-result-value-id})))
        (log/debug "fn-result-value cache miss, executing"
                   {:fn-result-value-id fn-result-value-id
                    :fn-id (:fn-id frv)})
        (let [fn-id (:fn-id frv)
              result (execute-internal context fn-id nil)]
          (swap! result-cache assoc fn-result-value-id result)
          result)))))


(defn- build-delay
  "Builds a delay for an arg-value.
   - If value is a UUID referencing fn-result-value -> delay with cached execution
   - If value is a UUID referencing fn and arg-schema type is :fn -> delay with callable (HOF, no path-args)
   - If value is a UUID referencing fn and arg-schema type is not :fn -> delay that executes fn
   - Otherwise -> delay with literal value

   NOTE: This function is only called from build-arg-delays when a stored arg-value
   exists AND no provided-arg override was found for this arg-schema-id.
   The provided-args check happens in build-arg-delays before calling this function.

   IMPORTANT: Direct fn refs (HOF, type=:fn) do NOT receive path-args - they are
   'black boxes' controlled by map/reduce/etc. Only fn-result-value refs can have
   their free args set via path-args.

   Type hints for hot-path performance."
  ^clojure.lang.Delay [^ExecutionContext context ^clojure.lang.IPersistentMap arg-value ^clojure.lang.IPersistentMap arg-schema]
  ;; Note: :value can be nil for optional args with null values.
  ;; arg-values from storage are assumed to have :value key present.
  (let [value (:value arg-value)
        arg-type (:type arg-schema)
        ;; Check if this UUID is a fn-result-value
        execution-graph (:execution-graph context)
        fn-result-values (:fn-result-values execution-graph)]
    (cond
      ;; UUID value means reference to fn or fn-result-value
      (uuid? value)
      (cond
        ;; Check if it's a fn-result-value reference
        (contains? fn-result-values value)
        ;; fn-result-value: execute with caching, set current-frv-id for path-args lookup
        (let [frv-context (assoc context :current-frv-id value)]
          (delay (execute-fn-result-value frv-context value)))

        ;; It's a direct fn reference with :fn type -> HOF
        (= arg-type :fn)
        ;; HOF: return fn-id directly (not callable)
        ;; HOF functions use get-single-required-arg or make-single-arg-callable
        ;; to create appropriate callables based on their needs
        (delay value)

        :else
        ;; Direct fn ref with non-:fn type: execute immediately
        (delay (execute-internal context value nil)))

      ;; Literal value - wrap in delay
      :else
      (delay value))))


(defn- get-path-arg
  "Gets a runtime argument value from path-args.

   For root function (current-frv-id is nil): looks up by arg-schema-id directly
   For nested fns via fn-result-value: looks up by [fn-result-value-id arg-schema-id]

   Returns the value or nil if not found."
  [context arg-schema-id]
  (let [current-frv-id (:current-frv-id context)
        path-args (:path-args context)]
    (if current-frv-id
      ;; Nested fn via fn-result-value: use [frv-id arg-schema-id] as key
      (get path-args [current-frv-id arg-schema-id])
      ;; Root function: use arg-schema-id directly
      (get path-args arg-schema-id))))


;; === Argument resolution helpers ===
;; These helpers handle specific cases in build-arg-delays.
;; They are intentionally separate (not inlined) for:
;; - Readability: descriptive names document business logic better than inline code
;; - Single responsibility: each function handles one argument source scenario
;; - Testability: individual functions can be tested in isolation if needed

(defn- handle-provided-arg
  "Handles case when direct provided value exists (from HOF callable)."
  [provided-value arg-schema strict?]
  (validate-provided-arg-type! provided-value arg-schema strict?)
  (delay provided-value))


(defn- handle-path-arg-with-db-value
  "Handles case when path-arg exists but DB value takes precedence.

   Design Decision: DB values always win over path-args.
   This prevents accidental override of validated stored data.
   If you need to override a stored arg-value:
   1. Use `provided-args` in `execute` call (for HOF callables)
   2. Or update the arg-value in the database first

   Logs warning at WARN level to help debugging when override doesn't work."
  [context arg-value arg-schema arg-schema-id arg-name path-arg-value]
  (log/warn "Path-arg ignored: argument already defined in DB (DB value takes precedence)"
            {:arg-schema-id arg-schema-id
             :current-frv-id (:current-frv-id context)
             :arg-name arg-name
             :db-value (truncate-value (:value arg-value) log-value-truncation-length)
             :provided-value (truncate-value path-arg-value log-value-truncation-length)
             :hint "Use provided-args in execute call or update DB arg-value to override"})
  (build-delay context arg-value arg-schema))


(defn- handle-path-arg-only
  "Handles case when path-arg exists and no DB value."
  [path-arg-value arg-schema strict?]
  (validate-provided-arg-type! path-arg-value arg-schema strict?)
  (delay path-arg-value))


(defn- throw-missing-required-arg!
  "Throws error for required arg with no value."
  [arg-schema-id arg-name current-frv-id]
  (throw (ex-info (str "Required argument '" arg-name "' not provided")
                  {:type :execution-error/missing-required-arg
                   :arg-schema-id arg-schema-id
                   :arg-name arg-name
                   :current-frv-id current-frv-id})))


(defn- build-arg-delays
  "Builds delays for all arg-schemas.
   Returns a map of {arg-name-keyword -> delay}.

   All arguments are wrapped in delay for lazy evaluation.
   Base functions receive delays and use @ (deref) to get values.

   ## Argument Resolution Priority

   1. Direct provided value (from HOF callable calls)
   2. Path-arg value (only if no DB value exists):
      - For root fn: looked up by arg-schema-id
      - For nested fn via fn-result-value: looked up by [frv-id arg-schema-id]
      - If arg-value also exists in DB -> warning, use DB value (no override)
   3. Stored arg-value from DB
   4. Required arg with no value -> error
   5. Optional arg with no value -> skip (NOT included in map)

   ## Optional Arguments Convention

   Optional args (`:required false` in arg-schema) are handled as follows:
   - If no value is provided, the argument is NOT included in the delays map
   - Base functions should check for the key's presence or provide defaults:
     ```clojure
     (defbase my-fn
       {:args {:x :int, :y {:type :int :required false}}
        :return-type :int}
       (+ @x (or (some-> y deref) 0)))  ; y may not be present
     ```

   IMPORTANT: Optional args with no value will NOT have a key in the map,
   so `(get args :optional-arg)` returns nil, and `@optional-arg` will fail
   if the arg is not present. Always check for presence first."
  [context fn-data provided-args]
  (let [{:keys [arg-schemas arg-values]} fn-data
        current-frv-id (:current-frv-id context)
        strict? (:strict-type-validation? context)]
    (reduce-kv
      (fn [acc arg-schema-id arg-schema]
        (let [arg-name (:name arg-schema)
              arg-name-kw (keyword arg-name)
              provided-value (get provided-args arg-schema-id)
              path-arg-value (get-path-arg context arg-schema-id)
              arg-value (get arg-values arg-schema-id)]
          (cond
            ;; 1. Direct provided value (from HOF callable)
            (some? provided-value)
            (assoc acc arg-name-kw (handle-provided-arg provided-value arg-schema strict?))

            ;; 2. Path-arg value exists
            (some? path-arg-value)
            (assoc acc arg-name-kw
                   (if arg-value
                     ;; DB value takes precedence
                     (handle-path-arg-with-db-value context arg-value arg-schema
                                                    arg-schema-id arg-name path-arg-value)
                     ;; No DB value - use path-arg
                     (handle-path-arg-only path-arg-value arg-schema strict?)))

            ;; 3. Stored arg-value exists
            arg-value
            (assoc acc arg-name-kw (build-delay context arg-value arg-schema))

            ;; 4. Required arg with no value -> error
            (:required arg-schema)
            (throw-missing-required-arg! arg-schema-id arg-name current-frv-id)

            ;; 5. Optional arg with no value -> skip
            :else acc)))
      {}
      arg-schemas)))


;; === Execution ===

(def ^:private warning-threshold-ratio
  "Ratio of limit at which to log warning. 0.8 = warn at 80% of limit."
  0.8)


(defn- check-limits!
  "Checks execution limits (depth, timeout). Throws if exceeded.
   Logs warning when approaching limits (80% threshold).

   IMPORTANT: Timeout is checked at the START of each function call, not during
   execution. This means a long-running base function will complete fully even
   if it exceeds the timeout. The timeout is a best-effort limit, not a hard
   guarantee. For precise timeout control, base functions should implement
   their own timeout logic (e.g., using futures with deref timeout)."
  [context]
  (let [depth (:depth context)
        max-depth (:max-depth context)
        depth-threshold (long (* max-depth warning-threshold-ratio))]
    ;; Warn when approaching depth limit (only once at threshold)
    (when (= depth depth-threshold)
      (log/warn "Approaching max recursion depth"
                {:depth depth
                 :max-depth max-depth
                 :threshold-ratio warning-threshold-ratio}))
    (when (> depth max-depth)
      (throw (ex-info "Maximum recursion depth exceeded"
                      {:type :execution-error/max-depth-exceeded
                       :depth depth
                       :max-depth max-depth}))))
  (let [elapsed (- (System/currentTimeMillis) (:start-time context))
        timeout-ms (:timeout-ms context)
        timeout-threshold (long (* timeout-ms warning-threshold-ratio))]
    ;; Warn when approaching timeout (check range to avoid repeated warnings)
    (when (and (>= elapsed timeout-threshold)
               (< elapsed (+ timeout-threshold 100)))  ; 100ms window to avoid spam
      (log/warn "Approaching execution timeout"
                {:elapsed-ms elapsed
                 :timeout-ms timeout-ms
                 :threshold-ratio warning-threshold-ratio
                 :depth (:depth context)}))
    (when (> elapsed timeout-ms)
      (throw (ex-info "Execution timeout exceeded"
                      {:type :execution-error/timeout
                       :elapsed-ms elapsed
                       :timeout-ms timeout-ms
                       :depth (:depth context)
                       :hint "Consider increasing timeout-ms or optimizing the graph"})))))


(defn- execute-internal
  "Internal execution function with context tracking.
   Uses the cached execution-graph and base-fns from context.

   Arguments are passed to base functions as delays for lazy evaluation.
   Base functions should use @ (deref) to get values."
  [context fn-id provided-args]
  (check-limits! context)
  (let [execution-graph (:execution-graph context)
        fn-data (get-fn-data-from-graph execution-graph fn-id)
        fn-schema (:fn-schema fn-data)
        fn-name (keyword (:name fn-schema))
        ;; Use base-fns from context
        registry (:base-fns context)
        base-fn (get registry fn-name)]
    (when-not base-fn
      (log/error "Base function not found in registry"
                 {:fn-name fn-name
                  :fn-id fn-id
                  :registry-size (count registry)})
      (throw (ex-info (str "Base function '" (name fn-name) "' not found in registry")
                      {:type :execution-error/base-fn-not-found
                       :fn-name fn-name
                       :registry-size (count registry)})))
    (let [new-context (update context :depth inc)
          arg-delays (build-arg-delays new-context fn-data provided-args)]
      (base-fn arg-delays new-context))))


(defn execute
  "Public execution entry point.
   Fetches the complete execution graph once, then executes using cached data.
   args must be nil or a map of {arg-schema-id -> value}."
  [context fn-id args]
  (when (and (some? args) (not (map? args)))
    (throw (ex-info "args must be nil or a map"
                    {:type :execution-error/invalid-args
                     :args args
                     :args-type (type args)})))
  (let [storage (:storage context)
        execution-graph (sp/resolve-execution-graph storage fn-id)
        context-with-graph (assoc context :execution-graph execution-graph)]
    (execute-internal context-with-graph fn-id args)))


(defn- resolve-named-args
  "Converts a map of {arg-name-keyword -> value} to {arg-schema-id -> value}.
   Used by execute-with-named-args for HOF functions that pass args by name."
  [execution-graph fn-id named-args]
  (let [{:keys [arg-schemas]} (get-fn-data-from-graph execution-graph fn-id)
        name->schema-id (reduce-kv
                          (fn [acc schema-id schema]
                            (assoc acc (keyword (:name schema)) schema-id))
                          {}
                          arg-schemas)]
    (reduce-kv
      (fn [acc arg-name value]
        (if-let [schema-id (get name->schema-id arg-name)]
          (assoc acc schema-id value)
          (throw (ex-info (str "Unknown argument name: " arg-name)
                          {:type :execution-error/unknown-arg-name
                           :arg-name arg-name
                           :fn-id fn-id
                           :available-args (keys name->schema-id)}))))
      {}
      named-args)))


(defn execute-with-named-args
  "Executes a function with arguments passed by name instead of by schema-id.
   Useful for HOF functions that need to call child functions with dynamic args.

   Example:
   (execute-with-named-args ctx fn-id {:item 42 :acc 0})

   This resolves :item and :acc to their respective arg-schema-ids and calls execute."
  [context fn-id named-args]
  (when (and (some? named-args) (not (map? named-args)))
    (throw (ex-info "named-args must be nil or a map"
                    {:type :execution-error/invalid-args
                     :args named-args
                     :args-type (type named-args)})))
  (if (or (nil? named-args) (empty? named-args))
    (execute context fn-id nil)
    (let [storage (:storage context)
          execution-graph (sp/resolve-execution-graph storage fn-id)
          id-based-args (resolve-named-args execution-graph fn-id named-args)
          context-with-graph (assoc context :execution-graph execution-graph)]
      (execute-internal context-with-graph fn-id id-based-args))))


(defn execute-by-name
  "Executes a function by its name (string).
   Convenience function that looks up the fn entity by name and executes it.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-name: String name of the function to execute
   - named-args: Map of {arg-name-keyword -> value} (optional, can be nil or {})

   Returns the result of the function execution.

   Throws:
   - :execution-error/fn-not-found if no function with the given name exists
   - :execution-error/invalid-fn-name if fn-name is not a string
   - All errors from execute-with-named-args"
  [context fn-name named-args]
  (when-not (string? fn-name)
    (throw (ex-info "fn-name must be a string"
                    {:type :execution-error/invalid-fn-name
                     :fn-name fn-name
                     :fn-name-type (type fn-name)})))
  (let [storage (:storage context)
        ;; NOTE: :fn entity has UNIQUE constraint on :name, so query returns 0 or 1 result
        fns (sp/query-entities storage :fn {:name fn-name})]
    (if (empty? fns)
      (throw (ex-info (str "Function '" fn-name "' not found")
                      {:type :execution-error/fn-not-found
                       :fn-name fn-name}))
      (execute-with-named-args context (:id (first fns)) named-args))))
