(ns graphden.executor.core
  "Core implementation of the function executor."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage-protocol.interface :as sp]))


;; === Thunk Protocol ===

(defprotocol IThunk
  "Protocol for lazy values (thunks)."

  (force-value
    [this context]
    "Forces evaluation of the thunk, returning the value."))


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
   depth])


(def ^:private max-depth-limit
  "Upper limit for max-depth to prevent accidental resource exhaustion.
   100000 is generous but prevents typos like 1000000000."
  100000)


(def ^:private min-timeout-ms
  "Minimum allowed timeout in milliseconds.
   50ms allows for fast test cases while preventing unrealistic timeouts."
  50)


(def ^:private default-timeout-ms
  "Default execution timeout in milliseconds.
   30 seconds is generous for most operations."
  30000)


(def ^:private error-value-truncation-length
  "Maximum length for values in error messages.
   Prevents huge exception data for large values."
  100)


(defn create-context
  "Creates initial execution context. Note: execution-graph is populated
   later when execute is called with a root fn-id.

   Options:
   - :storage - Storage instance (required)
   - :base-fns - Map of fn-name -> fn for base function lookup (optional, uses default registry if not provided)
   - :max-depth - Maximum recursion depth (default 1000)
   - :timeout-ms - Maximum execution time in ms (default 30000)

   Validates:
   - storage is required
   - timeout-ms must be at least 50ms (allows for fast test cases)
   - max-depth must be positive and <= 100000"
  [{:keys [storage base-fns max-depth timeout-ms]
    :or {max-depth sp/default-max-depth
         timeout-ms default-timeout-ms}}]
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
  ;; Use provided base-fns or snapshot the default registry
  (let [fns (or base-fns @default-registry)]
    (->ExecutionContext storage nil fns max-depth timeout-ms (System/currentTimeMillis) 0)))


;; === Thunks ===

(defrecord LiteralThunk
  [value])


(defrecord FnRefThunk
  [fn-id provided-args])


(defrecord LazyFnThunk
  [fn-id])


;; Forward declaration for mutual recursion
(declare execute-internal)


(extend-protocol IThunk
  LiteralThunk
  (force-value [this _context]
    (:value this))

  FnRefThunk
  (force-value [this context]
    (execute-internal context (:fn-id this) (:provided-args this)))

  LazyFnThunk
  (force-value [this _context]
    ;; For HOF: return fn-id, not the result
    (:fn-id this)))


;; === Graph Resolution ===
;; Note: The actual graph resolution is now done by storage's resolve-execution-graph
;; which fetches everything in one call. See ExecutionGraph protocol.


;; === Thunk Building ===

(defn- truncate-value
  "Truncates large values for error messages to avoid huge exception data.
   Returns a shortened representation for display purposes.

   Uses pr-str for consistent Clojure-readable output. The max-len parameter
   controls truncation; callers typically use 100 chars for error context."
  [value max-len]
  (let [s (pr-str value)]
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


(def ^:private known-types
  "Set of known types for logging purposes."
  #{:fn :ref :int :bool :text :numeric :jsonb :bytes :timestamptz :enum :uuid :union})


(defn- type-mismatch?
  "Returns true if provided-value doesn't match the expected arg-type.

   Type validation rules:
   - Known types: strict validation based on Clojure predicates
   - :union type: accepts any value (validation happens at schema level,
     where union variants are checked against allowed types)
   - Unknown types: returns false (permissive) to allow forward compatibility
     with new types added to the schema. Logs a warning for debugging.

   This is a runtime check for user-provided arguments only. Values from
   the execution graph (arg-values) are assumed to be already validated."
  [arg-type provided-value]
  (case arg-type
    :fn          (not (uuid? provided-value))
    :ref         (not (uuid? provided-value))
    :int         (not (int? provided-value))
    :bool        (not (boolean? provided-value))
    :text        (not (string? provided-value))
    :numeric     (not (number? provided-value))
    :jsonb       (not (or (map? provided-value) (vector? provided-value)))
    :bytes       (not (bytes? provided-value))
    :timestamptz (not (or (instance? java.time.Instant provided-value)
                          (instance? java.time.LocalDateTime provided-value)
                          (instance? java.util.Date provided-value)))
    :enum        (not (keyword? provided-value))
    :uuid        (not (uuid? provided-value))
    :union       false  ; Union types accept any value
    ;; Unknown type - log warning and accept (forward compatibility)
    (do
      (when-not (contains? known-types arg-type)
        (log/warn "Unknown argument type encountered, skipping validation"
                  {:type arg-type :value-type (type provided-value)}))
      false)))


(defn- validate-provided-arg-type!
  "Validates that a provided argument matches the expected arg-schema type.
   Throws ExceptionInfo with detailed context if type mismatch detected.

   Called from build-thunk when a user provides an argument that overrides
   a stored arg-value. This ensures user-provided values match the expected
   type before creating a LiteralThunk.

   Note: Stored arg-values from the execution graph are not validated here;
   they are assumed to be valid from schema-level checks during creation."
  [provided-value arg-schema]
  (when-not (and arg-schema (:type arg-schema))
    (throw (ex-info "Invalid arg-schema: missing type"
                    {:type :execution-error/invalid-arg-schema
                     :arg-schema arg-schema})))
  (when (type-mismatch? (:type arg-schema) provided-value)
    (throw-type-mismatch! arg-schema provided-value)))


(defn- build-thunk
  "Builds a thunk for an arg-value.
   - If value is a UUID and arg-schema type is not :fn -> FnRefThunk
   - If value is a UUID and arg-schema type is :fn -> LazyFnThunk
   - Otherwise -> LiteralThunk

   Validates that arg-value and arg-schema are not nil."
  [arg-value arg-schema provided-args]
  (when-not arg-value
    (throw (ex-info "arg-value cannot be nil"
                    {:type :execution-error/nil-arg-value})))
  (when-not arg-schema
    (throw (ex-info "arg-schema cannot be nil"
                    {:type :execution-error/nil-arg-schema})))
  ;; Note: :value can be nil for optional args with null values.
  ;; arg-values from storage are assumed to have :value key present.
  (let [value (:value arg-value)
        arg-type (:type arg-schema)
        arg-schema-id (:id arg-schema)]
    ;; Check if there's a provided arg that overrides this
    (if-let [provided-value (get provided-args arg-schema-id)]
      (do
        (validate-provided-arg-type! provided-value arg-schema)
        (->LiteralThunk provided-value))
      ;; No override, use the stored value
      (cond
        ;; UUID value means reference to another fn
        (uuid? value)
        (if (= arg-type :fn)
          ;; For :fn type args, don't execute, just pass fn-id
          (->LazyFnThunk value)
          ;; For other types, execute the referenced fn
          (->FnRefThunk value {}))

        ;; Literal value
        :else
        (->LiteralThunk value)))))


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


(defn- build-thunks
  "Builds thunks for all arg-schemas.
   Returns a map of {arg-name -> thunk}.

   Priority:
   1. If provided-args has a value for this schema-id, use it directly
   2. Else if arg-values has a value, use build-thunk (which may also check provided-args)
   3. Else if required, throw error
   4. Else skip (optional arg with no value)"
  [fn-data provided-args]
  (let [{:keys [arg-schemas arg-values]} fn-data]
    (reduce-kv
      (fn [acc arg-schema-id arg-schema]
        (let [arg-name (:name arg-schema)
              provided-value (get provided-args arg-schema-id)
              arg-value (get arg-values arg-schema-id)]
          (cond
            ;; 1. Direct provided value - create LiteralThunk
            (some? provided-value)
            (do
              (validate-provided-arg-type! provided-value arg-schema)
              (assoc acc (keyword arg-name) (->LiteralThunk provided-value)))

            ;; 2. Stored arg-value exists - use build-thunk
            arg-value
            (assoc acc (keyword arg-name) (build-thunk arg-value arg-schema provided-args))

            ;; 3. Required arg with no value - error
            (:required arg-schema)
            (throw (ex-info (str "Required argument '" arg-name "' not provided")
                            {:type :execution-error/missing-required-arg
                             :arg-schema-id arg-schema-id
                             :arg-name arg-name}))

            ;; 4. Optional arg with no value - skip
            :else acc)))
      {}
      arg-schemas)))


;; === Execution ===

(defn- check-limits!
  "Checks execution limits (depth, timeout). Throws if exceeded.

   IMPORTANT: Timeout is checked at the START of each function call, not during
   execution. This means a long-running base function will complete fully even
   if it exceeds the timeout. The timeout is a best-effort limit, not a hard
   guarantee. For precise timeout control, base functions should implement
   their own timeout logic (e.g., using futures with deref timeout)."
  [context]
  (when (> (:depth context) (:max-depth context))
    (throw (ex-info "Maximum recursion depth exceeded"
                    {:type :execution-error/max-depth-exceeded
                     :depth (:depth context)
                     :max-depth (:max-depth context)})))
  (let [elapsed (- (System/currentTimeMillis) (:start-time context))]
    (when (> elapsed (:timeout-ms context))
      (throw (ex-info "Execution timeout exceeded"
                      {:type :execution-error/timeout
                       :elapsed-ms elapsed
                       :timeout-ms (:timeout-ms context)})))))


(defn- execute-internal
  "Internal execution function with context tracking.
   Uses the cached execution-graph and base-fns from context."
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
      (log/warn "Base function not found in registry"
                {:fn-name fn-name
                 :fn-id fn-id
                 :available-fns (keys registry)})
      (throw (ex-info (str "Base function '" (name fn-name) "' not found in registry. "
                           "Available functions: " (pr-str (keys registry)))
                      {:type :execution-error/base-fn-not-found
                       :fn-name fn-name
                       :available-fns (keys registry)})))
    (let [thunks (build-thunks fn-data provided-args)
          new-context (update context :depth inc)]
      (base-fn thunks new-context))))


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
