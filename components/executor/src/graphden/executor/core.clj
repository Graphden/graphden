(ns graphden.executor.core
  "Core implementation of the function executor."
  (:require
    [clojure.tools.logging :as log]
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


;; Forward declaration for mutual recursion
(declare execute-internal)


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


(defn- make-callable
  "Creates a callable function that executes a graph function with named args.
   Used for :fn type arguments in HOF."
  [context fn-id]
  (fn [named-args]
    (let [{:keys [arg-schemas]} (get-fn-data-from-graph (:execution-graph context) fn-id)
          ;; Convert named-args to schema-id based args
          name->schema-id (reduce-kv
                            (fn [acc schema-id schema]
                              (assoc acc (keyword (:name schema)) schema-id))
                            {}
                            arg-schemas)
          id-based-args (reduce-kv
                          (fn [acc arg-name value]
                            (if-let [schema-id (get name->schema-id arg-name)]
                              (assoc acc schema-id value)
                              (throw (ex-info (str "Unknown argument name: " arg-name)
                                              {:type :execution-error/unknown-arg-name
                                               :arg-name arg-name
                                               :fn-id fn-id
                                               :available-args (keys name->schema-id)}))))
                          {}
                          named-args)]
      (execute-internal context fn-id id-based-args))))


(defn- build-delay
  "Builds a delay for an arg-value.
   - If value is a UUID and arg-schema type is :fn -> delay with callable
   - If value is a UUID and arg-schema type is not :fn -> delay that executes fn
   - Otherwise -> delay with literal value

   NOTE: This function is only called from build-arg-delays when a stored arg-value
   exists AND no provided-arg override was found for this arg-schema-id.
   The provided-args check happens in build-arg-delays before calling this function."
  [context arg-value arg-schema]
  ;; Note: :value can be nil for optional args with null values.
  ;; arg-values from storage are assumed to have :value key present.
  (let [value (:value arg-value)
        arg-type (:type arg-schema)]
    (cond
      ;; UUID value means reference to another fn
      (uuid? value)
      (if (= arg-type :fn)
        ;; For :fn type args, create a callable
        (delay (make-callable context value))
        ;; For other types, execute the referenced fn
        (delay (execute-internal context value {})))

      ;; Literal value - wrap in delay
      :else
      (delay value))))


(defn- build-arg-delays
  "Builds delays for all arg-schemas.
   Returns a map of {arg-name-keyword -> delay}.

   All arguments are wrapped in delay for lazy evaluation.
   Base functions receive delays and use @ (deref) to get values.

   Priority:
   1. If provided-args has a value for this schema-id, wrap in delay
   2. Else if arg-values has a value, use build-delay
   3. Else if required, throw error
   4. Else skip (optional arg with no value)"
  [context fn-data provided-args]
  (let [{:keys [arg-schemas arg-values]} fn-data]
    (reduce-kv
      (fn [acc arg-schema-id arg-schema]
        (let [arg-name (:name arg-schema)
              arg-type (:type arg-schema)
              provided-value (get provided-args arg-schema-id)
              arg-value (get arg-values arg-schema-id)]
          (cond
            ;; 1. Direct provided value - wrap in delay
            (some? provided-value)
            (do
              (validate-provided-arg-type! provided-value arg-schema)
              (if (= arg-type :fn)
                ;; For :fn type, provided-value should be a fn-id, create callable
                (assoc acc (keyword arg-name) (delay (make-callable context provided-value)))
                ;; For other types, just wrap the value
                (assoc acc (keyword arg-name) (delay provided-value))))

            ;; 2. Stored arg-value exists - use build-delay
            arg-value
            (assoc acc (keyword arg-name) (build-delay context arg-value arg-schema))

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
                  :available-fns (keys registry)})
      (throw (ex-info (str "Base function '" (name fn-name) "' not found in registry. "
                           "Available functions: " (pr-str (keys registry)))
                      {:type :execution-error/base-fn-not-found
                       :fn-name fn-name
                       :available-fns (keys registry)})))
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
