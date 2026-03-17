(ns graphden.executor.core
  "Core implementation of the function executor.

   ## Module Structure

   The executor is split into focused namespaces:
   - core.clj (this file) - Main execution logic and public API
   - queue.clj - Work-queue based executor (avoids stack overflow)
   - argument_resolution.clj - Delay building and argument resolution (legacy)
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

   Arguments are passed to base functions as SmartDelay objects (IDeref).
   This enables lazy evaluation - values are only computed when dereferenced.

   For :fn type arguments (is-fn=true), the delay contains the fn-id
   that can be used to create a callable.

   Base functions should use @ (deref) to get values:
     (+ @a @b)           ; for regular args
     (f {:item x})       ; for :fn args (f is a callable after wrapping)

   ## Execution Model

   Uses a work-queue based approach instead of recursive delays:
   1. Queue root function execution
   2. For each arg with ref-id: check cache, or queue dependency first
   3. When all args ready, execute base-fn
   4. Store result and continue

   This avoids StackOverflowError on deep nesting (e.g., list-10 pattern)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.executor.context :as ctx]
    [graphden.executor.queue :as queue]
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
            filtered-parent-args (remove #(own-source-ids (:id %)) parent-args)
]
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
      ;; Reset start-time for each invocation (handlers are long-lived)
      (let [fresh-ctx (assoc context :start-time (ctx/current-time-ms context))]
        (execute-internal fresh-ctx fn-id {arg-id value})))))


(defn make-optional-arg-callable
  "Creates a callable for a function with 0 or 1 required arguments.
   - 0 args: callable ignores input, calls fn with no args
   - 1 arg: callable passes input to the single required arg

   Used by response handlers where inner-fn may or may not need request.

   Returns a function: value -> result"
  [context fn-id]
  (let [required-args (get-required-args (:execution-graph context) fn-id)
        count-required (count required-args)]
    (case count-required
      0 (fn [_value]
          ;; Reset start-time for each invocation (handlers are long-lived)
          (let [fresh-ctx (assoc context :start-time (ctx/current-time-ms context))]
            (execute-internal fresh-ctx fn-id {})))

      1 (let [arg-id (:id (first required-args))]
          (fn [value]
            ;; Reset start-time for each invocation (handlers are long-lived)
            (let [fresh-ctx (assoc context :start-time (ctx/current-time-ms context))]
              (execute-internal fresh-ctx fn-id {arg-id value}))))

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
   Returns {:target-arg-id uuid :depth int} if the chain leads to target-fn-id, nil otherwise.
   The depth indicates how many hops through the source-id chain - useful for preferring
   closer args when multiple args map to the same target."
  [execution-graph arg-id target-fn-id visited depth]
  (when (> depth sp/*max-graph-iterations*)
    nil)
  (when-not (contains? visited arg-id)
    (let [args-by-id (:args-by-id execution-graph)
          arg (get args-by-id arg-id)]
      (when arg
        (let [arg-fn-id (:fn-id arg)]
          (if (= arg-fn-id target-fn-id)
            ;; Found! Return target arg-id and depth
            {:target-arg-id arg-id :depth depth}
            ;; Keep tracing through source-id
            (when-let [source-id (:source-id arg)]
              (recur execution-graph source-id target-fn-id
                     (conj visited arg-id) (inc depth)))))))))


(defn- collect-propagated-args-for-ref
  "Collects args from caller that should be passed to a referenced fn.
   Returns a map of {target-arg-id -> caller-arg} for args that have
   source-id chains leading into the ref-fn.

   Also matches by root-name when caller-arg has a direct value but source-id
   chain doesn't reach the ref-fn. This handles cases like pair/pair-1 where
   the route.path arg needs to propagate to pair-1.item even though the
   source-id chain goes through pair, not directly to pair-1.

   IMPORTANT: For list-10 pattern, multiple caller-args may have source-id chains
   leading to the SAME target arg (e.g., all itemN trace to conj-any.item).
   We prefer args WITH values over args WITHOUT values to ensure the correct
   value is propagated."
  [execution-graph caller-args ref-fn-id]
  (let [fns (sp/get-graph-fns execution-graph)
        args-by-id (:args-by-id execution-graph)
        ref-fn-name (:name (get fns ref-fn-id))
        ;; Build a map of root-name -> arg-id for the ref-fn's args
        ref-fn-args (filter #(= (:fn-id %) ref-fn-id) (vals args-by-id))
        ref-fn-args-by-name (reduce (fn [m arg]
                                      (let [root-name (graph/get-root-arg-name execution-graph arg)]
                                        (if root-name
                                          (assoc m root-name (:id arg))
                                          m)))
                                    {}
                                    ref-fn-args)]
    (reduce
      (fn [acc caller-arg]
        (if-let [trace-result (trace-source-to-fn execution-graph
                                                   (:source-id caller-arg)
                                                   ref-fn-id
                                                   #{} 0)]
          ;; Found via source-id chain
          ;; trace-result is {:target-arg-id uuid :depth int}
          (let [target-arg-id (:target-arg-id trace-result)
                new-depth (:depth trace-result)
                ;; Get existing entry (with metadata including depth)
                existing-entry (get acc target-arg-id)
                existing (when existing-entry (:arg existing-entry))
                existing-depth (when existing-entry (:depth existing-entry))
                ;; CRITICAL: When multiple args map to the same target, use multiple criteria:
                ;; 1. Prefer CLOSER args (shorter source-id chain depth)
                ;; 2. Prefer args with VALUE over REF-ID over NONE
                ;; This handles:
                ;; - list-10 pattern: multiple itemN args with same depth
                ;; - sibling chains: unrelated args at different depths (e.g., route vs script)
                existing-has-value? (and existing (some? (:value existing)))
                existing-has-ref? (and existing (some? (:ref-id existing)))
                new-has-value? (some? (:value caller-arg))
                new-has-ref? (some? (:ref-id caller-arg))
                ;; Replacement logic with depth priority:
                ;; 1. Shorter depth always wins (closer in chain = more relevant)
                ;; 2. Same depth: prefer value > ref-id > none
                should-replace? (cond
                                  ;; No existing - always add
                                  (nil? existing) true
                                  ;; New has shorter depth - always wins (closer = more relevant)
                                  (< new-depth existing-depth) true
                                  ;; New has longer depth - existing wins
                                  (> new-depth existing-depth) false
                                  ;; Same depth - prefer value > ref-id > none
                                  new-has-value? true
                                  new-has-ref? (not existing-has-value?)
                                  :else (not (or existing-has-value? existing-has-ref?)))]
            (if should-replace?
              (assoc acc target-arg-id {:arg caller-arg :depth new-depth})
              acc))
          ;; NOTE: Removed fallback name-matching which was causing over-propagation.
          ;; The fallback matched args by root-name even without source-id chains,
          ;; leading to args being propagated to unrelated ref-fns (e.g., item1-item10
          ;; from editor-routes matching item1-item3 in editor-scripts).
          ;; This caused cache key bloat and excessive executions.
          ;; If needed, add source-id chains explicitly in fn-defs instead.
          acc))
      {}
      caller-args)))


(defn- build-arg-delays-by-id
  "Builds a map of {arg-id -> delay} from caller-args and arg-delays.
   Used for propagation lookup by arg-id instead of arg-name.

   arg-delays may be keyed by:
   - arg-id (UUID) when coming from arg-delays-by-id-atom
   - keyword (arg name) when coming from by-name map

   For each caller-arg, first tries to find delay by arg-id (exact match),
   then falls back to looking up by root name (for backwards compatibility)."
  [execution-graph caller-args arg-delays]
  (reduce
    (fn [acc arg]
      (let [arg-id (:id arg)
            ;; First try direct lookup by arg-id (when arg-delays is keyed by id)
            delay-by-id (get arg-delays arg-id)
            ;; Fallback: lookup by root name (when arg-delays is keyed by name)
            delay-val (or delay-by-id
                          (let [root-name (graph/get-root-arg-name execution-graph arg)]
                            (when root-name
                              (get arg-delays (keyword root-name)))))]
        (if delay-val
          (assoc acc arg-id delay-val)
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
        ;; Include the actual propagated arg values in the cache key.
        ;; This ensures different propagated values get separate cache entries
        ;; (e.g., item1 with path=/health vs item2 with path=/favicon.ico)
        ;; while allowing caching when the same ref is called with identical values.
        ;; IMPORTANT: Include BOTH :value AND :ref-id in cache key, because args may have
        ;; either a literal value or a ref-id (pointer to another fn). Using only :value
        ;; causes cache collisions when different args have ref-ids but nil values.
        ;; NOTE: propagated-arg-map now contains {:arg ... :depth ...} entries
        ;; NOTE: We do NOT include caller-arg-ids in cache key - only propagated values matter.
        ;; Including all caller-arg-ids causes cache key bloat and prevents sharing.
        propagated-values (when (seq propagated-arg-map)
                            (into (sorted-map)
                                  (map (fn [[k entry]]
                                         (let [arg (:arg entry)]
                                           [k [(:value arg) (:ref-id arg)]]))
                                       propagated-arg-map)))
        cache-key (if propagated-values
                    [ref-fn-id propagated-values]
                    ref-fn-id)
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
        ;; NOTE: propagated-arg-map now contains {:arg ... :depth ...} entries
        (let [provided-args (when (seq propagated-arg-map)
                              (reduce-kv
                                (fn [acc target-arg-id entry]
                                  (let [caller-arg (:arg entry)
                                        caller-arg-id (:id caller-arg)
                                        delay-val (get all-delays-by-id caller-arg-id)]
                                    (if delay-val
                                      (assoc acc target-arg-id @delay-val)
                                      acc)))
                                {}
                                propagated-arg-map))
              ;; CRITICAL FIX: Only propagate args that were NOT consumed at this level.
              ;; Args that matched a target in ref-fn are "consumed" - their values are now
              ;; bound to ref-fn's args via provided-args. Deeper functions will get these
              ;; values through the ref-fn's own arg graph, not through propagation.
              ;; This prevents unbounded growth of propagated-caller-args in deep chains
              ;; like list-10 (11 levels: list-10 -> list-10-9 -> ... -> conj-any).
              consumed-arg-ids (into #{} (map (comp :id :arg) (vals propagated-arg-map)))
              remaining-caller-args (remove #(contains? consumed-arg-ids (:id %)) all-caller-args)
              ;; Store remaining (unconsumed) args in context for nested ref-fn calls
              context-with-args (assoc context
                                       :propagated-caller-args (vec remaining-caller-args)
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
   Delegates to queue-based executor for stack-safe execution."
  [context ^java.util.UUID fn-id ^clojure.lang.IPersistentMap provided-args]
  ;; Use queue-based executor to avoid stack overflow
  (queue/execute-with-queue context fn-id provided-args))


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
