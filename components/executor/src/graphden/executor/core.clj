(ns graphden.executor.core
  "Core implementation of the function executor.

   ## Module Structure

   The executor is split into focused namespaces:
   - core.clj (this file) - Main execution logic
   - argument_resolution.clj - Delay building and argument resolution
   - registry.clj - Base function registry (global and context-based)
   - context.clj - ExecutionContext record and creation
   - types.clj - Type hints and validation

   ## Lazy Arguments

   Arguments are passed to base functions as Clojure `delay` objects.
   This enables lazy evaluation - values are only computed when dereferenced.

   For :fn type arguments, the delay contains a callable (a Clojure function)
   that can be invoked with a map of named arguments.

   Base functions should use @ (deref) to get values:
     (+ @a @b)           ; for regular args
     (f {:item x})       ; for :fn args (f is already a callable after deref)

   The defbase macro in fn-registry handles this automatically."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.executor.context :as ctx]
    [graphden.storage-protocol.interface :as sp]))


;; Forward declaration: execute-internal is defined later but referenced by
;; build-delay, execute-fn-result-value, and make-single-arg-callable.
;; The recursion is lazy (via delays) - functions create delays that capture
;; execute-internal for later evaluation during argument resolution.
(declare execute-internal)


;; === Graph Data Helpers ===

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


;; === fn-result-value Execution ===

(def ^:private cache-eviction-ratio
  "Ratio of cache entries to evict when cache is full.
   0.2 means evict 20% of entries (oldest first by insertion order)."
  0.2)


(defn- evict-cache-entries!
  "Evicts oldest entries from cache when limit is reached.
   Uses insertion order (first entries added are evicted first).
   Returns the number of entries evicted."
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
  "Checks if result cache has reached its limit.
   If at limit, evicts oldest entries (by insertion order) to make room.
   This prevents OOM from unbounded execution graphs while allowing
   execution to continue with reduced caching."
  [context fn-result-value-id]
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
                   :fn-result-value-id fn-result-value-id
                   :hint "Large caches may indicate deep recursion or unbounded graphs"})))))


(defn- get-fn-result-value!
  "Gets fn-result-value from execution graph.
   Throws if not found."
  [context fn-result-value-id]
  (let [execution-graph (:execution-graph context)
        fn-result-values (:fn-result-values execution-graph)
        frv (get fn-result-values fn-result-value-id)]
    (when-not frv
      (throw (ex-info "fn-result-value not found in execution graph"
                      {:type :execution-error/fn-result-value-not-found
                       :fn-result-value-id fn-result-value-id})))
    frv))


(defn- execute-and-cache-result!
  "Executes fn-result-value and stores result in cache.
   Logs warning once when cache reaches warning threshold.
   Returns the computed result."
  [context fn-result-value-id frv]
  (let [result-cache (:result-cache context)
        cache-max-size (:cache-max-size context)
        cache-warning-threshold (:cache-warning-threshold context)]
    (log/debug "fn-result-value cache miss, executing"
               {:fn-result-value-id fn-result-value-id
                :fn-id (:fn-id frv)})
    (let [fn-id (:fn-id frv)
          result (execute-internal context fn-id nil)
          new-size (count (swap! result-cache assoc fn-result-value-id result))]
      ;; Warn once when cache crosses threshold (check if we just crossed)
      (when (= new-size cache-warning-threshold)
        (log/warn "Result cache size reached warning threshold - consider limiting graph depth"
                  {:cache-size new-size
                   :threshold cache-warning-threshold
                   :max-size cache-max-size
                   :hint "Large caches may indicate unbounded execution graphs"}))
      result)))


(defn- execute-fn-result-value
  "Executes a fn-result-value, using cache for memoization.
   If the fn-result-value-id is already in cache, returns cached value.
   Otherwise executes the underlying fn, caches the result, and returns it.

   Structure:
   1. Check cache for existing result (fast path)
   2. Check cache limit before adding new entry
   3. Get fn-result-value from graph
   4. Execute and cache the result"
  [context fn-result-value-id]
  (let [result-cache (:result-cache context)
        cached (get @result-cache fn-result-value-id)]
    (if (some? cached)
      ;; Fast path: cache hit
      (do
        (log/debug "fn-result-value cache hit"
                   {:fn-result-value-id fn-result-value-id
                    :cache-size (count @result-cache)})
        cached)
      ;; Slow path: cache miss - check limits, execute, cache
      (do
        (check-cache-limit! context fn-result-value-id)
        (let [frv (get-fn-result-value! context fn-result-value-id)]
          (execute-and-cache-result! context fn-result-value-id frv))))))


;; === Execution ===

(defn- check-depth-limit!
  "Checks recursion depth limit. Throws if exceeded.
   Logs warning when approaching limit (at 80% threshold, exactly once)."
  [context]
  (let [depth (:depth context)
        max-depth (:max-depth context)
        depth-threshold (long (* max-depth ctx/warning-threshold-ratio))]
    (when (= depth depth-threshold)
      (log/warn "Approaching max recursion depth"
                {:depth depth
                 :max-depth max-depth
                 :threshold-ratio ctx/warning-threshold-ratio}))
    (when (> depth max-depth)
      (throw (ex-info "Maximum recursion depth exceeded"
                      {:type :execution-error/max-depth-exceeded
                       :depth depth
                       :max-depth max-depth})))))


(defn- check-timeout-limit!
  "Checks execution timeout. Throws if exceeded.
   Logs warning when approaching timeout (within warning window after 80% threshold).

   IMPORTANT: Timeout is checked at the START of each function call, not during
   execution. This means a long-running base function will complete fully even
   if it exceeds the timeout. The timeout is a best-effort limit, not a hard
   guarantee. For precise timeout control, base functions should implement
   their own timeout logic (e.g., using futures with deref timeout).

   Uses the context's clock for time measurement, allowing deterministic testing."
  [context]
  (let [elapsed (- (ctx/current-time-ms context) (:start-time context))
        timeout-ms (:timeout-ms context)
        timeout-threshold (long (* timeout-ms ctx/warning-threshold-ratio))]
    (when (and (>= elapsed timeout-threshold)
               (< elapsed (+ timeout-threshold ctx/timeout-warning-window-ms)))
      (log/warn "Approaching execution timeout"
                {:elapsed-ms elapsed
                 :timeout-ms timeout-ms
                 :threshold-ratio ctx/warning-threshold-ratio
                 :depth (:depth context)}))
    (when (> elapsed timeout-ms)
      (throw (ex-info "Execution timeout exceeded"
                      {:type :execution-error/timeout
                       :elapsed-ms elapsed
                       :timeout-ms timeout-ms
                       :depth (:depth context)
                       :hint "Consider increasing timeout-ms or optimizing the graph"})))))


(defn- check-limits!
  "Checks all execution limits (depth, timeout). Throws if any exceeded."
  [context]
  (check-depth-limit! context)
  (check-timeout-limit! context))


(defn- execute-internal
  "Internal execution function with context tracking.
   Uses the cached execution-graph and base-fns from context.

   Arguments are passed to base functions as delays for lazy evaluation.
   Base functions should use @ (deref) to get values."
  [context ^java.util.UUID fn-id ^clojure.lang.IPersistentMap provided-args]
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
          ;; Pass execute functions to argument resolution for recursive execution
          arg-delays (arg-res/build-arg-delays new-context fn-data provided-args
                                               execute-fn-result-value execute-internal)]
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
