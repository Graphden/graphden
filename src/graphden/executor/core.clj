(ns graphden.executor.core
  "Core implementation of the function executor.

   ## Module Structure

   The executor is split into focused namespaces:
   - core.clj (this file) - Main execution logic
   - argument_resolution.clj - Delay building and argument resolution
   - registry.clj - Base function registry (global and context-based)
   - context.clj - ExecutionContext record and creation
   - types.clj - Type hints and validation

   ## 2-Entity Schema

   The executor works with a simplified schema:
   - fn: function entity (base or composed)
     - parent-id=nil → base-fn (has Clojure implementation)
     - parent-id set → composed fn (inherits from parent)
   - arg: argument entity
     - value → literal JSONB value
     - ref-id → FK to fn (execute and use result)
     - is-fn=true → pass fn-id directly (for HOF)

   ## Lazy Arguments

   Arguments are passed to base functions as Clojure `delay` objects.
   This enables lazy evaluation - values are only computed when dereferenced.

   For :fn type arguments (is-fn=true), the delay contains the fn-id
   that can be used to create a callable.

   Base functions should use @ (deref) to get values:
     (+ @a @b)           ; for regular args
     (f {:item x})       ; for :fn args (f is a callable after wrapping)"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.executor.context :as ctx]
    [graphden.storage.protocol.core :as sp]))


(declare execute-internal)


;; === Graph Data Helpers ===

(defn- resolve-base-fn
  "Resolves a function to its base-fn by following parent chain.
   Returns the base-fn record (fn with parent-id=nil).
   Throws if base-fn not found or chain too deep."
  [fns fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain exceeds maximum depth"
                    {:type :execution-error/parent-chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fn-rec (get fns fn-id)]
    (when-not fn-rec
      (throw (ex-info "Function not found in execution graph"
                      {:type :execution-error/fn-not-found
                       :fn-id fn-id})))
    (if-let [parent-id (:parent-id fn-rec)]
      (recur fns parent-id (inc depth))
      fn-rec)))


(defn- get-fn-args-with-inheritance
  "Gets args for a function, checking parent chain if fn has no own args.
   This allows composed fns with 'free' args to be used in HOF."
  [execution-graph fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain exceeds maximum depth while resolving args"
                    {:type :execution-error/parent-chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [args (sp/graph-get-args execution-graph fn-id)]
    (if (seq args)
      args
      ;; If no args on this fn, check parent fn
      (let [fn-rec (get (sp/get-graph-fns execution-graph) fn-id)]
        (when-let [parent-id (:parent-id fn-rec)]
          (recur execution-graph parent-id (inc depth)))))))


(defn- get-fn-data-from-graph
  "Gets function data from the cached execution graph.
   Returns {:fn fn-rec :base-fn base-fn-rec :args [arg-records]}
   For composed fns with no own args, inherits args from parent (for HOF support)."
  [execution-graph fn-id]
  (let [fns (:fns execution-graph)
        fn-rec (get fns fn-id)]
    (when-not fn-rec
      (throw (ex-info "Function not found in execution graph"
                      {:type :execution-error/fn-not-found
                       :fn-id fn-id
                       :available-fn-ids (vec (keys fns))})))
    (let [base-fn (resolve-base-fn fns fn-id 0)
          args (or (get-fn-args-with-inheritance execution-graph fn-id 0) [])]
      {:fn fn-rec
       :base-fn base-fn
       :args args})))


(defn- get-required-args
  "Returns a sequence of required args for a function.
   For HOF: if fn has no args, checks parent fn's args (via parent-id).
   This allows composed fns with 'free' args (no value/ref-id) to be used in HOF."
  [execution-graph fn-id]
  (let [args (sp/graph-get-args execution-graph fn-id)]
    (if (seq args)
      (filter #(:required % true) args)
      ;; If no args on this fn, check parent fn
      (let [fn-rec (get (sp/get-graph-fns execution-graph) fn-id)]
        (when-let [parent-id (:parent-id fn-rec)]
          (recur execution-graph parent-id))))))


(defn get-single-required-arg
  "Gets the single required arg for a function.
   Used by HOF (map, filter, etc.) to find the target argument.

   Returns {:id arg-id :name arg-name :type arg-type}

   Throws if the function doesn't have exactly one required argument."
  [context fn-id]
  (let [required-args (get-required-args (:execution-graph context) fn-id)
        count-required (count required-args)]
    (when (not= count-required 1)
      (throw (ex-info (str "HOF function requires exactly 1 required argument, got " count-required)
                      {:type :execution-error/invalid-hof-function
                       :fn-id fn-id
                       :required-arg-count count-required
                       :required-args (mapv #(select-keys % [:id :name :type]) required-args)})))
    (let [arg (first required-args)]
      {:id (:id arg)
       :name (:name arg)
       :type (:type arg)})))


(defn make-single-arg-callable
  "Creates a callable for a function with exactly one required argument.
   The callable accepts a single value (not a map) and passes it to that argument.

   Used by HOF (map, filter, etc.) to call user functions without requiring
   specific argument names.

   Returns a function: value -> result"
  [context fn-id]
  (let [arg-id (:id (get-single-required-arg context fn-id))]
    (fn [value]
      (execute-internal context fn-id {arg-id value}))))


;; === Result Caching ===

(def ^:private cache-eviction-ratio sp/cache-eviction-ratio)


(defn- evict-cache-entries!
  "Evicts oldest entries from cache when limit is reached."
  [result-cache target-size]
  (let [current @result-cache
        current-size (count current)
        entries-to-remove (- current-size target-size)]
    (when (pos? entries-to-remove)
      (let [keys-to-remove (take entries-to-remove (keys current))]
        (swap! result-cache #(apply dissoc % keys-to-remove))
        (log/debug "Evicted cache entries"
                   {:evicted-count entries-to-remove
                    :new-size (count @result-cache)})
        entries-to-remove))))


(defn- check-cache-limit!
  "Checks if result cache has reached its limit."
  [context fn-id]
  (let [result-cache (:result-cache context)
        current-size (count @result-cache)
        cache-max-size (:cache-max-size context)]
    (when (>= current-size cache-max-size)
      (let [target-size (long (* cache-max-size (- 1.0 cache-eviction-ratio)))
            evicted (evict-cache-entries! result-cache target-size)]
        (log/warn "Result cache reached limit, evicted entries"
                  {:cache-max-size cache-max-size
                   :evicted-count evicted
                   :new-size (count @result-cache)
                   :fn-id fn-id})))))


(defn- execute-ref-fn
  "Executes a referenced function with caching.
   Used when an arg has ref-id set (execute fn and use result)."
  [context ref-fn-id]
  (let [result-cache (:result-cache context)
        cached (get @result-cache ref-fn-id)]
    (if (some? cached)
      (do
        (log/debug "ref-fn cache hit" {:ref-fn-id ref-fn-id})
        cached)
      (do
        (check-cache-limit! context ref-fn-id)
        (log/debug "ref-fn cache miss, executing" {:ref-fn-id ref-fn-id})
        (let [result (execute-internal context ref-fn-id nil)
              new-size (count (swap! result-cache assoc ref-fn-id result))
              cache-warning-threshold (:cache-warning-threshold context)]
          (when (= new-size cache-warning-threshold)
            (log/warn "Result cache size reached warning threshold"
                      {:cache-size new-size
                       :threshold cache-warning-threshold}))
          result)))))


;; === Execution ===

(defn- check-depth-limit!
  "Checks recursion depth limit. Throws if exceeded."
  [context]
  (let [depth (:depth context)
        max-depth (:max-depth context)
        depth-threshold (long (* max-depth ctx/warning-threshold-ratio))]
    (when (= depth depth-threshold)
      (log/warn "Approaching max recursion depth"
                {:depth depth
                 :max-depth max-depth}))
    (when (> depth max-depth)
      (throw (ex-info "Maximum recursion depth exceeded"
                      {:type :execution-error/max-depth-exceeded
                       :depth depth
                       :max-depth max-depth})))))


(defn- check-timeout-limit!
  "Checks execution timeout. Throws if exceeded."
  [context]
  (let [elapsed (- (ctx/current-time-ms context) (:start-time context))
        timeout-ms (:timeout-ms context)
        timeout-threshold (long (* timeout-ms ctx/warning-threshold-ratio))]
    (when (and (>= elapsed timeout-threshold)
               (< elapsed (+ timeout-threshold ctx/timeout-warning-window-ms)))
      (log/warn "Approaching execution timeout"
                {:elapsed-ms elapsed
                 :timeout-ms timeout-ms
                 :depth (:depth context)}))
    (when (> elapsed timeout-ms)
      (throw (ex-info "Execution timeout exceeded"
                      {:type :execution-error/timeout
                       :elapsed-ms elapsed
                       :timeout-ms timeout-ms
                       :depth (:depth context)})))))


(defn- check-limits!
  "Checks all execution limits (depth, timeout). Throws if any exceeded."
  [context]
  (check-depth-limit! context)
  (check-timeout-limit! context))


(defn- execute-internal
  "Internal execution function with context tracking.
   Uses the cached execution-graph and base-fns from context."
  [context ^java.util.UUID fn-id ^clojure.lang.IPersistentMap provided-args]
  (check-limits! context)
  (let [execution-graph (:execution-graph context)
        fn-data (get-fn-data-from-graph execution-graph fn-id)
        base-fn (:base-fn fn-data)
        fn-name (keyword (:name base-fn))
        registry (:base-fns context)
        base-fn-impl (get registry fn-name)]
    (when-not base-fn-impl
      (log/error "Base function not found in registry"
                 {:fn-name fn-name
                  :fn-id fn-id
                  :registry-size (count registry)})
      (throw (ex-info (str "Base function '" (name fn-name) "' not found in registry")
                      {:type :execution-error/base-fn-not-found
                       :fn-name fn-name
                       :registry-size (count registry)})))
    (let [new-context (update context :depth inc)
          arg-delays (arg-res/build-arg-delays new-context fn-data provided-args
                                               execute-ref-fn)]
      (base-fn-impl arg-delays new-context))))


(defn execute
  "Public execution entry point.
   Fetches the complete execution graph once, then executes using cached data.
   args must be nil or a map of {arg-id -> value}."
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
  "Converts a map of {arg-name-keyword -> value} to {arg-id -> value}.
   Uses argument inheritance for composed fns with no own args."
  [execution-graph fn-id named-args]
  (let [args (or (get-fn-args-with-inheritance execution-graph fn-id 0) [])
        name->arg-id (into {}
                           (map (fn [arg]
                                  [(keyword (:name arg)) (:id arg)])
                                args))]
    (reduce-kv
      (fn [acc arg-name value]
        (if-let [arg-id (get name->arg-id arg-name)]
          (assoc acc arg-id value)
          (throw (ex-info (str "Unknown argument name: " arg-name)
                          {:type :execution-error/unknown-arg-name
                           :arg-name arg-name
                           :fn-id fn-id
                           :available-args (keys name->arg-id)}))))
      {}
      named-args)))


(defn execute-with-named-args
  "Executes a function with arguments passed by name instead of by id."
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
  "Executes a function by its name (string)."
  [context fn-name named-args]
  (when-not (string? fn-name)
    (throw (ex-info "fn-name must be a string"
                    {:type :execution-error/invalid-fn-name
                     :fn-name fn-name
                     :fn-name-type (type fn-name)})))
  (let [storage (:storage context)
        fns (sp/query-entities storage :fn {:name fn-name})]
    (if (empty? fns)
      (throw (ex-info (str "Function '" fn-name "' not found")
                      {:type :execution-error/fn-not-found
                       :fn-name fn-name}))
      (execute-with-named-args context (:id (first fns)) named-args))))
