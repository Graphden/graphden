(ns graphden.executor.core
  "Core implementation of the function executor."
  (:require
    [graphden.storage-protocol.interface :as sp]))


;; === Thunk Protocol ===

(defprotocol IThunk
  "Protocol for lazy values (thunks)."

  (force-value
    [this context]
    "Forces evaluation of the thunk, returning the value."))


;; === Base Functions Registry ===

(defonce ^:private base-fns-registry (atom {}))


(defn register-base-fn!
  [fn-name f]
  (swap! base-fns-registry assoc fn-name f)
  nil)


(defn get-base-fn
  [fn-name]
  (get @base-fns-registry fn-name))


(defn clear-base-fns!
  []
  (reset! base-fns-registry {})
  nil)


;; === Execution Context ===

(defrecord ExecutionContext
  [storage
   execution-graph  ; Cached graph from resolve-execution-graph
   max-depth
   timeout-ms
   start-time
   depth])


(defn create-context
  "Creates initial execution context. Note: execution-graph is populated
   later when execute is called with a root fn-id."
  [{:keys [storage max-depth timeout-ms]
    :or {max-depth 1000
         timeout-ms 30000}}]
  (when-not storage
    (throw (ex-info "Storage is required" {:type :execution-error/invalid-context})))
  (->ExecutionContext storage nil max-depth timeout-ms (System/currentTimeMillis) 0))


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

(defn- validate-provided-arg-type!
  "Validates that a provided argument matches the expected arg-schema type.
   Throws ExceptionInfo if type mismatch detected.
   Note: This is a runtime check for type mismatches."
  [provided-value arg-schema]
  (when-not (and arg-schema (:type arg-schema))
    (throw (ex-info "Invalid arg-schema: missing type"
                    {:type :execution-error/invalid-arg-schema
                     :arg-schema arg-schema})))
  (let [arg-type (:type arg-schema)
        arg-name (:name arg-schema)]
    (cond
      ;; :fn type expects a UUID (function reference)
      (and (= arg-type :fn) (not (uuid? provided-value)))
      (throw (ex-info "Provided arg for :fn type must be a UUID (function reference)"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :fn
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :ref type expects a UUID
      (and (= arg-type :ref) (not (uuid? provided-value)))
      (throw (ex-info "Provided arg for :ref type must be a UUID"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :ref
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :int type expects an integer
      (and (= arg-type :int) (not (int? provided-value)))
      (throw (ex-info "Provided arg for :int type must be an integer"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :int
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :bool type expects a boolean
      (and (= arg-type :bool) (not (boolean? provided-value)))
      (throw (ex-info "Provided arg for :bool type must be a boolean"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :bool
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :text type expects a string
      (and (= arg-type :text) (not (string? provided-value)))
      (throw (ex-info "Provided arg for :text type must be a string"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :text
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :numeric type expects a number (int, float, bigdec, ratio, etc.)
      (and (= arg-type :numeric) (not (number? provided-value)))
      (throw (ex-info "Provided arg for :numeric type must be a number"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :numeric
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :jsonb type expects a map or vector (serializable Clojure data)
      (and (= arg-type :jsonb) (not (or (map? provided-value) (vector? provided-value))))
      (throw (ex-info "Provided arg for :jsonb type must be a map or vector"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :jsonb
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :bytes type expects a byte array
      (and (= arg-type :bytes) (not (bytes? provided-value)))
      (throw (ex-info "Provided arg for :bytes type must be a byte array"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :bytes
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :timestamptz type expects an Instant or Date
      (and (= arg-type :timestamptz)
           (not (or (instance? java.time.Instant provided-value)
                    (instance? java.util.Date provided-value))))
      (throw (ex-info "Provided arg for :timestamptz type must be an Instant or Date"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :timestamptz
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :enum type expects a keyword
      (and (= arg-type :enum) (not (keyword? provided-value)))
      (throw (ex-info "Provided arg for :enum type must be a keyword"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :enum
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :uuid type expects a UUID
      (and (= arg-type :uuid) (not (uuid? provided-value)))
      (throw (ex-info "Provided arg for :uuid type must be a UUID"
                      {:type :execution-error/type-mismatch
                       :arg-name arg-name
                       :expected-type :uuid
                       :provided-value provided-value
                       :provided-type (type provided-value)}))

      ;; :union type - no strict validation, accept any value
      ;; (union types are complex and would need schema-specific validation)
      :else nil)))


(defn- build-thunk
  "Builds a thunk for an arg-value.
   - If value is a UUID and arg-schema type is not :fn -> FnRefThunk
   - If value is a UUID and arg-schema type is :fn -> LazyFnThunk
   - Otherwise -> LiteralThunk"
  [arg-value arg-schema provided-args]
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
   Returns a map of {arg-name -> thunk}."
  [fn-data provided-args]
  (let [{:keys [arg-schemas arg-values]} fn-data]
    (reduce-kv
      (fn [acc arg-schema-id arg-schema]
        (let [arg-value (get arg-values arg-schema-id)
              arg-name (:name arg-schema)]
          (if arg-value
            (assoc acc (keyword arg-name) (build-thunk arg-value arg-schema provided-args))
            ;; No value for this arg - check if required
            (if (:required arg-schema)
              (throw (ex-info (str "Required argument '" arg-name "' not provided")
                              {:type :execution-error/missing-required-arg
                               :arg-schema-id arg-schema-id
                               :arg-name arg-name}))
              acc))))
      {}
      arg-schemas)))


;; === Execution ===

(defn- check-limits!
  "Checks execution limits (depth, timeout). Throws if exceeded."
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
   Uses the cached execution-graph from context."
  [context fn-id provided-args]
  (check-limits! context)
  (let [execution-graph (:execution-graph context)
        fn-data (get-fn-data-from-graph execution-graph fn-id)
        fn-schema (:fn-schema fn-data)
        fn-name (keyword (:name fn-schema))
        base-fn (get-base-fn fn-name)]
    (when-not base-fn
      (let [available-fns (keys @base-fns-registry)]
        (throw (ex-info (str "Base function '" (name fn-name) "' not found in registry. "
                             "Available functions: " (pr-str available-fns))
                        {:type :execution-error/base-fn-not-found
                         :fn-name fn-name
                         :available-fns available-fns}))))
    (let [thunks (build-thunks fn-data provided-args)
          new-context (update context :depth inc)]
      (base-fn thunks new-context))))


(defn execute
  "Public execution entry point.
   Fetches the complete execution graph once, then executes using cached data."
  [context fn-id args]
  (let [storage (:storage context)
        execution-graph (sp/resolve-execution-graph storage fn-id)
        context-with-graph (assoc context :execution-graph execution-graph)]
    (execute-internal context-with-graph fn-id args)))
