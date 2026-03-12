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
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


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
  "Collects args from entire parent chain, merging by source-id.
   Child args override parent args they inherit from (via source-id).
   Returns vector of all args needed to execute the base-fn.
   Note: arg names are resolved via arg-roots index at usage time, not here."
  [execution-graph fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain exceeds maximum depth while resolving args"
                    {:type :execution-error/parent-chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fns (sp/get-graph-fns execution-graph)
        fn-rec (get fns fn-id)
        own-args (sp/graph-get-args execution-graph fn-id)
        parent-id (:parent-id fn-rec)]
    (if parent-id
      ;; Merge with parent args - own args override args they inherit from (via source-id)
      (let [parent-args (get-fn-args-with-inheritance execution-graph parent-id (inc depth))
            ;; Collect all source-ids that own args inherit from (transitively)
            ;; This ensures that if route.path inherits from pair.first, pair.first is excluded
            own-source-ids (into #{} (keep :source-id) own-args)
            ;; Keep parent args that are NOT the source of any own arg
            filtered-parent-args (remove #(own-source-ids (:id %)) parent-args)]
        (vec (concat own-args filtered-parent-args)))
      ;; Base fn - just return own args
      (vec own-args))))


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


(defn make-optional-arg-callable
  "Creates a callable for a function with 0 or 1 required arguments.
   - 0 args: callable ignores input, calls fn with no args
   - 1 arg: callable passes input to the single required arg

   Used by response handlers where inner-fn may or may not need request.

   Returns a function: value -> result"
  [context fn-id]
  (let [required-args (get-required-args (:execution-graph context) fn-id)
        count-required (count required-args)]
    (cond
      (= count-required 0)
      (fn [_value]
        (execute-internal context fn-id {}))

      (= count-required 1)
      (let [arg-id (:id (first required-args))]
        (fn [value]
          (execute-internal context fn-id {arg-id value})))

      :else
      (throw (ex-info (str "Function requires 0 or 1 arguments, got " count-required)
                      {:type :execution-error/invalid-handler-function
                       :fn-id fn-id
                       :required-arg-count count-required})))))


;; === Result Caching ===

(defn- evict-cache-entries!
  "Evicts oldest entries from cache when limit is reached."
  [result-cache target-size]
  (let [current @result-cache
        current-size (count current)
        entries-to-remove (- current-size target-size)]
    (when (pos? entries-to-remove)
      ;; Use reduce for O(k) dissoc instead of apply dissoc
      (let [keys-to-remove (take entries-to-remove (keys current))]
        (swap! result-cache (fn [m]
                              (reduce dissoc m keys-to-remove)))
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
      (let [target-size (long (* cache-max-size (- 1.0 sp/cache-eviction-ratio)))
            evicted (evict-cache-entries! result-cache target-size)]
        (log/warn "Result cache reached limit, evicted entries"
                  {:cache-max-size cache-max-size
                   :evicted-count evicted
                   :new-size (count @result-cache)
                   :fn-id fn-id})))))


(defn- trace-source-to-fn
  "Traces source-id chain from an arg to find the ultimate source arg-id in target fn.
   Returns the target arg-id if the chain leads to target-fn-id, nil otherwise."
  [execution-graph arg-id target-fn-id visited depth]
  (when (> depth sp/*max-graph-iterations*)
    nil)
  (when-not (contains? visited arg-id)
    (let [args-by-id (:args-by-id execution-graph)
          arg (get args-by-id arg-id)]
      (when arg
        (let [arg-fn-id (:fn-id arg)]
          (if (= arg-fn-id target-fn-id)
            ;; Found! Return this arg-id
            arg-id
            ;; Keep tracing through source-id
            (when-let [source-id (:source-id arg)]
              (recur execution-graph source-id target-fn-id
                     (conj visited arg-id) (inc depth)))))))))


(defn- collect-propagated-args-for-ref
  "Collects args from caller that should be passed to a referenced fn.
   Returns a map of {target-arg-id -> caller-arg} for args that have
   source-id chains leading into the ref-fn."
  [execution-graph caller-args ref-fn-id]
  (reduce
    (fn [acc caller-arg]
      (if-let [target-arg-id (trace-source-to-fn execution-graph
                                                 (:source-id caller-arg)
                                                 ref-fn-id
                                                 #{} 0)]
        (assoc acc target-arg-id caller-arg)
        acc))
    {}
    caller-args))


(defn- build-arg-delays-by-id
  "Builds a map of {arg-id -> delay} from caller-args and arg-delays.
   Used for propagation lookup by arg-id instead of arg-name.

   Uses pre-built arg-roots index for O(1) root name lookup.
   This ensures inherited args (via source-id) are properly matched."
  [execution-graph caller-args arg-delays]
  (reduce
    (fn [acc arg]
      ;; Use root arg name to match arg-delays keying (O(1) via index)
      (let [root-name (graph/get-root-arg-name execution-graph arg)
            arg-name-kw (keyword root-name)
            delay-val (get arg-delays arg-name-kw)]
        (if delay-val
          (assoc acc (:id arg) delay-val)
          acc)))
    {}
    caller-args))


(defn- execute-ref-fn
  "Executes a referenced function with caching and pass-through args.
   Used when an arg has ref-id set (execute fn and use result).

   Pass-through args mechanism: when caller has args with source-id chains
   leading into ref-fn, their values are passed as provided-args.

   Original caller-args and their delays (by arg-id) are stored in context
   so they propagate through nested ref-fn executions
   (e.g., health-route -> method-map -> assoc-handler)."
  [context ref-fn-id caller-args arg-delays]
  (log/debug "execute-ref-fn called"
             {:ref-fn-id ref-fn-id
              :caller-args-count (count caller-args)})
  (let [result-cache (:result-cache context)
        execution-graph (:execution-graph context)
        ;; Build arg-delays-by-id for current caller
        current-delays-by-id (build-arg-delays-by-id execution-graph caller-args arg-delays)
        ;; Merge with any original delays from parent ref-fn calls
        ;; Key by arg-id to avoid name collisions between different fns
        all-delays-by-id (merge (:propagated-delays-by-id context)
                                current-delays-by-id)
        ;; Merge caller-args with any original ones from parent ref-fn calls
        all-caller-args (into (vec (:propagated-caller-args context))
                              caller-args)
        ;; Collect propagated args that should be passed to ref-fn
        propagated-arg-map (collect-propagated-args-for-ref
                             execution-graph all-caller-args ref-fn-id)
        ;; Create cache key that includes ALL caller arg-ids (not just propagated)
        ;; This ensures different callers (e.g., health-route vs editor-route) get
        ;; separate cache entries, even when source-id chains don't fully connect
        ;; (which happens when intermediate fns have non-free args)
        caller-arg-ids (into #{} (map :id) all-caller-args)
        cache-key (if (empty? caller-arg-ids)
                    ref-fn-id
                    [ref-fn-id caller-arg-ids])
        cached (get @result-cache cache-key)]
    (if (some? cached)
      (do
        (log/debug "ref-fn cache hit" {:ref-fn-id ref-fn-id :cache-key cache-key})
        cached)
      (do
        (check-cache-limit! context ref-fn-id)
        (log/debug "ref-fn cache miss, executing"
                   {:ref-fn-id ref-fn-id
                    :propagated-args-count (count propagated-arg-map)
                    :all-caller-args-count (count all-caller-args)})
        ;; Build provided-args by deref'ing caller's arg delays using arg-id
        (let [provided-args (when (seq propagated-arg-map)
                              (reduce-kv
                                (fn [acc target-arg-id caller-arg]
                                  (let [caller-arg-id (:id caller-arg)
                                        delay-val (get all-delays-by-id caller-arg-id)]
                                    (if delay-val
                                      (assoc acc target-arg-id @delay-val)
                                      acc)))
                                {}
                                propagated-arg-map))
              ;; Store all-caller-args and all-delays-by-id in context for nested ref-fn calls
              context-with-args (assoc context
                                       :propagated-caller-args all-caller-args
                                       :propagated-delays-by-id all-delays-by-id)
              result (execute-internal context-with-args ref-fn-id provided-args)
              new-size (count (swap! result-cache assoc cache-key result))
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
        ;; Reset start-time for each top-level execute call
        context-with-graph (assoc context
                                  :execution-graph execution-graph
                                  :start-time (ctx/current-time-ms context))]
    (execute-internal context-with-graph fn-id args)))


(defn- resolve-named-args
  "Converts a map of {arg-name-keyword -> value} to {arg-id -> value}.
   Uses argument inheritance for composed fns with no own args.
   Uses pre-built arg-roots index for O(1) name resolution."
  [execution-graph fn-id named-args]
  (let [args (or (get-fn-args-with-inheritance execution-graph fn-id 0) [])
        ;; Build name->arg-id map using root name for correct inheritance
        name->arg-id (into {}
                           (map (fn [arg]
                                  (let [root-name (graph/get-root-arg-name execution-graph arg)]
                                    [(keyword root-name) (:id arg)])))
                           args)]
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
          ;; Reset start-time for each top-level execute call
          context-with-graph (assoc context
                                    :execution-graph execution-graph
                                    :start-time (ctx/current-time-ms context))]
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
