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
     - parent-ids=nil/[] → base-fn (has Clojure implementation)
     - parent-ids=[id] → single inheritance (most common)
     - parent-ids=[id1 id2 ...] → multiple inheritance
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
    [graphden.executor.context :as ctx]
    [graphden.executor.queue :as queue]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


(declare execute-internal)


;; === Graph Data Helpers ===

(defn- get-fn-args-with-inheritance
  "Collects args from entire parent graph (supports multiple inheritance).
   Child args override parent args they inherit from (via source-id).
   Returns vector of all args needed to execute the base-fn.
   Note: arg names are resolved via arg-roots index at usage time, not here.

   With multiple inheritance, walks all parents in order (first parent has
   highest precedence for arg name conflicts). Args are merged by source-id
   chain: if an arg is inherited from a parent arg, that parent arg is excluded."
  [execution-graph fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain exceeds maximum depth while resolving args"
                    {:type :execution-error/parent-chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fns (sp/get-graph-fns execution-graph)
        fn-rec (get fns fn-id)
        own-args (sp/graph-get-args execution-graph fn-id)
        parent-ids (:parent-ids fn-rec)]
    (if (seq parent-ids)
      ;; Merge with args from ALL parents - own args override args they inherit from (via source-id)
      ;; Walk each parent in order; deduplicate resulting args by id
      (let [all-parent-args (into []
                                  (mapcat (fn [pid]
                                            (get-fn-args-with-inheritance
                                              execution-graph pid (inc depth))))
                                  parent-ids)
            ;; Deduplicate parent args by id (in case same arg appears via multiple parents)
            dedup-parent-args (->> all-parent-args
                                   (reduce (fn [{:keys [seen result]} a]
                                             (if (contains? seen (:id a))
                                               {:seen seen :result result}
                                               {:seen (conj seen (:id a))
                                                :result (conj result a)}))
                                           {:seen #{} :result []})
                                   :result)
            ;; Collect all source-ids that own args inherit from
            ;; This ensures that if route.path inherits from pair.first, pair.first is excluded
            own-source-ids (into #{} (keep :source-id) own-args)
            ;; Keep parent args that are NOT the source of any own arg
            filtered-parent-args (remove #(own-source-ids (:id %)) dedup-parent-args)]
        (vec (concat own-args filtered-parent-args)))
      ;; Base fn - just return own args
      (vec own-args))))


(defn- get-required-args
  "Returns a sequence of required args for a function.
   For HOF: if fn has no args, checks parent fns' args (via parent-ids).
   This allows composed fns with 'free' args (no value/ref-id) to be used in HOF.
   With multiple inheritance, collects required args from all parents in order."
  [execution-graph fn-id]
  (let [args (sp/graph-get-args execution-graph fn-id)]
    (if (seq args)
      (filter #(:required % true) args)
      ;; If no args on this fn, check parent fns (collect from all in order)
      (let [fn-rec (get (sp/get-graph-fns execution-graph) fn-id)
            parent-ids (:parent-ids fn-rec)]
        (when (seq parent-ids)
          (mapcat #(get-required-args execution-graph %) parent-ids))))))


(defn- get-deep-free-args
  "Finds free args reachable through ref-id chains (BFS by depth).
   When all top-level args are bound (have value/ref-id), walks into
   the ref-id targets to find ultimately-unbound args.
   Uses BFS to find the SHALLOWEST free args first — this prevents
   finding args from deeply nested fns (e.g., route handlers) when
   a shallower fn has the same arg name.

   Returns a seq of arg records with :id :name :type :fn-id :source-id."
  [execution-graph fn-id visited]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [fn-id 0])
         visited (or visited #{})
         found []]
    (if (empty? queue)
      ;; Deduplicate by name, keeping first (shallowest due to BFS)
      (let [seen (atom #{})
            unique (filterv (fn [a]
                              (let [n (:name a)]
                                (when-not (@seen n)
                                  (swap! seen conj n)
                                  true)))
                            found)]
        unique)
      (let [[fid depth] (peek queue)
            queue (pop queue)]
        (if (or (visited fid) (> depth 20))
          (recur queue visited found)
          (let [visited (conj visited fid)
                args (sp/graph-get-args execution-graph fid)
                own-free (filterv (fn [a]
                                    (and (some? (:name a))
                                         (not (false? (:required a)))
                                         (nil? (:value a))
                                         (nil? (:ref-id a))))
                                  args)]
            (if (seq own-free)
              (recur queue visited (into found own-free))
              (let [ref-ids (keep :ref-id args)]
                (recur (into queue (mapv #(vector % (inc depth)) ref-ids)) visited found)))))))))


(defn get-single-required-arg
  "Gets the single required arg for a function.
   Used by HOF (map, filter, etc.) to find the target argument.

   First checks top-level required args. If none found, searches
   through ref-id chains for deep free args (enables fn-def compositions
   where the free arg is buried inside nested fn-refs).

   Returns {:id arg-id :name arg-name :type arg-type}

   Throws if the function doesn't have exactly one required argument."
  [context fn-id]
  (let [execution-graph (:execution-graph context)
        required-args (get-required-args execution-graph fn-id)
        count-required (count required-args)
        ;; Fallback: try deep free args when top-level shows 0
        effective-args (if (zero? count-required)
                         (get-deep-free-args execution-graph fn-id nil)
                         required-args)
        effective-count (count effective-args)]
    (when (not= effective-count 1)
      (throw (ex-info (str "HOF function requires exactly 1 required argument, got " effective-count)
                      {:type :execution-error/invalid-hof-function
                       :fn-id fn-id
                       :required-arg-count effective-count
                       :required-args (mapv #(select-keys % [:id :name :type]) effective-args)})))
    (let [arg (first effective-args)]
      {:id (:id arg)
       :name (:name arg)
       :type (:type arg)
       :fn-id (:fn-id arg)
       :source-id (:source-id arg)})))


(defn make-single-arg-callable
  "Creates a callable for a function with exactly one required argument.
   The callable accepts a single value (not a map) and passes it to that argument.

   Supports deep free args: if the fn has 0 top-level required args but
   has free args buried in fn-ref chains, finds and maps to them.
   Passes the deep arg record via context so the executor can propagate it.

   Used by HOF (map, filter, etc.) to call user functions without requiring
   specific argument names.

   Returns a function: value -> result"
  [context fn-id]
  ;; Resolve execution graph for THIS fn (not parent's graph which may
  ;; not include HOF target fns like _final-response)
  (let [storage (:storage context)
        fn-graph (sp/resolve-execution-graph storage fn-id)
        fn-ctx (assoc context :execution-graph fn-graph)
        arg-info (get-single-required-arg fn-ctx fn-id)
        arg-id (:id arg-info)]
    (fn [value]
      ;; Fresh result-cache per invocation — each call has a different `value`
      ;; (e.g. the ring request), and the cache keys don't include runtime values,
      ;; so sharing the cache across calls would return stale first-call results.
      (let [fresh-ctx (assoc fn-ctx
                             :start-time (ctx/current-time-ms context)
                             :deep-provided-arg arg-info
                             :result-cache (atom {}))]
        (execute-internal fresh-ctx fn-id {arg-id value})))))


(defn- find-deep-free-arg-by-name
  "BFS through ref-id chains to find a free arg with the given name.
   Complement to get-deep-free-args — when the caller knows which named
   input it wants (e.g. `make-request-handler` always needs the `request`
   free arg), this cuts through the ambiguity created when multiple deep
   free args are reachable from a composed fn.

   At each fn we use inheritance-aware args so that refs a composed child
   inherits from its parent are still walked (e.g. a thin wrapper that
   binds one arg at the top level but inherits the primary chain from
   `:if` needs those inherited refs to find the input deep underneath).
   Own refs at the top fn-id are skipped because they denote values the
   caller already chose to plug in (like a concrete router), not paths
   leading toward the function's input slot."
  [execution-graph fn-id target-name]
  (let [top-own-ref-ids (set (keep :ref-id (sp/graph-get-args execution-graph fn-id)))]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [fn-id 0])
           visited #{}]
      (when (seq queue)
        (let [[fid depth] (peek queue)
              queue (pop queue)]
          (cond
            (or (visited fid) (> depth 20))
            (recur queue visited)

            :else
            (let [visited (conj visited fid)
                  args (get-fn-args-with-inheritance execution-graph fid 0)
                  match (some (fn [a]
                                (when (and (= (:name a) target-name)
                                           (nil? (:value a))
                                           (nil? (:ref-id a)))
                                  a))
                              args)]
              (or match
                  (let [ref-ids (cond->> (keep :ref-id args)
                                  (= fid fn-id) (remove top-own-ref-ids))]
                    (recur (into queue (mapv #(vector % (inc depth)) ref-ids))
                           visited))))))))))


(defn make-named-arg-callable
  "Creates a callable that routes the incoming value to a specific deep
   free arg identified by name. Preferred over `make-single-arg-callable`
   when the caller knows the semantic name of the input (e.g. a Ring
   request handler always takes a `request` arg). Avoids the ambiguity
   when multiple unrelated deep free args are reachable."
  [context fn-id arg-name]
  (let [storage (:storage context)
        fn-graph (sp/resolve-execution-graph storage fn-id)
        fn-ctx (assoc context :execution-graph fn-graph)
        arg (find-deep-free-arg-by-name fn-graph fn-id arg-name)
        _ (when-not arg
            (throw (ex-info (str "No free arg named '" arg-name "' reachable from fn")
                            {:type :execution-error/missing-named-arg
                             :fn-id fn-id
                             :arg-name arg-name})))
        arg-info {:id (:id arg)
                  :name (:name arg)
                  :type (:type arg)
                  :fn-id (:fn-id arg)
                  :source-id (:source-id arg)}
        arg-id (:id arg)]
    (fn [value]
      (let [fresh-ctx (assoc fn-ctx
                             :start-time (ctx/current-time-ms context)
                             :deep-provided-arg arg-info
                             :result-cache (atom {}))]
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
          ;; Fresh cache + start-time per invocation (handlers are long-lived)
          (let [fresh-ctx (assoc context
                                 :start-time (ctx/current-time-ms context)
                                 :result-cache (atom {}))]
            (execute-internal fresh-ctx fn-id {})))

      1 (let [arg-id (:id (first required-args))]
          (fn [value]
            ;; Fresh cache + start-time per invocation (handlers are long-lived)
            (let [fresh-ctx (assoc context
                                   :start-time (ctx/current-time-ms context)
                                   :result-cache (atom {}))]
              (execute-internal fresh-ctx fn-id {arg-id value}))))

      (throw (ex-info (str "Function requires 0 or 1 arguments, got " count-required)
                      {:type :execution-error/invalid-handler-function
                       :fn-id fn-id
                       :required-arg-count count-required})))))


;; === Execution ===

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
