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
    [graphden.executor.context :as ctx]
    [graphden.executor.queue :as queue]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


(declare execute-internal)


;; === Graph Data Helpers ===

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
