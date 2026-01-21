(ns graphden.executor.argument-resolution
  "Argument resolution logic for the function executor.

   This module handles:
   - Building delays for lazy argument evaluation
   - Resolving argument values from various sources (provided, path-args, DB)
   - Type validation for provided arguments

   ## Why Delays?

   Arguments are wrapped in Clojure `delay` objects for lazy evaluation:
   - Values are only computed when dereferenced with @
   - Enables memoization of fn-result-values across the execution graph

   ## Reference Types

   Resolution is based on the TYPE OF REFERENCE, not arg-schema type:
   - ref<fn> → pass fn-id directly (for HOF, handlers, etc. - don't execute)
   - ref<fn-result-value> → execute fn and use result (with caching)

   This means HOF (map, filter, reduce) receive fn-id and create callables themselves.

   ## Argument Resolution Priority

   1. `provided-args` - Explicitly passed at execute time (highest priority)
   2. `path-args` - Runtime args from context (for root and nested fns)
   3. `arg-values` - Stored values from database (resolved-args in graph)

   ## Error Handling

   - `realize-lazy-value` forces evaluation to catch errors early
   - Type validation happens before creating delays
   - Unknown types in forward-compat mode log warnings but don't throw

   ## Security

   - Lazy sequence size is limited by config/*max-lazy-seq-size* (default 100k)
   - Nested collection depth limited by config/*max-nested-collection-depth*
   - Prevents DoS via infinite sequences like (range)"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.types :as types]
    [graphden.storage-protocol.config :as config]))


;; === Delay Building Infrastructure ===

(defn- realize-lazy-seq-bounded
  "Realizes a lazy sequence with size limit to prevent DoS.
   Throws if sequence exceeds max-size elements."
  [coll max-size]
  (let [result (transient [])
        iter (clojure.lang.RT/iter coll)]
    (loop [n 0]
      (if (java.util.Iterator/.hasNext iter)
        (if (>= n max-size)
          (throw (ex-info "Lazy sequence exceeds maximum allowed size"
                          {:type :execution-error/lazy-seq-too-large
                           :max-size max-size
                           :hint "Reduce sequence size or increase config/*max-lazy-seq-size*"}))
          (let [_ (conj! result (java.util.Iterator/.next iter))]
            (recur (unchecked-inc n))))
        (persistent! result)))))


(defn- realize-lazy-value
  "Forces evaluation of lazy sequences/maps to ensure errors are caught.

   - Lazy sequences are converted to vectors (with size limit)
   - Lazy map values are realized recursively (with depth limit)
   - Other values pass through unchanged

   Security:
   - Limits lazy sequence size to config/*max-lazy-seq-size* (default 100k)
   - Limits recursion depth to config/*max-nested-collection-depth* (default 100)
   - Prevents DoS via infinite sequences like (range)

   This ensures that execution errors in nested lazy computations
   are caught at the point of argument evaluation, not during consumption."
  ([value]
   (realize-lazy-value value 0))
  ([value depth]
   (let [max-size config/*max-lazy-seq-size*
         max-depth config/*max-nested-collection-depth*]
     (when (> depth max-depth)
       (throw (ex-info "Collection nesting exceeds maximum allowed depth"
                       {:type :execution-error/collection-too-deep
                        :max-depth max-depth
                        :hint "Reduce nesting or increase config/*max-nested-collection-depth*"})))
     (cond
       ;; nil passes through
       (nil? value) nil

       ;; Lazy seq -> realize as vector with size limit
       (and (seqable? value)
            (not (string? value))
            (not (map? value))
            (instance? clojure.lang.LazySeq value))
       (realize-lazy-seq-bounded value max-size)

       ;; Map with lazy values -> realize all values recursively
       (map? value)
       (persistent!
         (reduce-kv
           (fn [m k v]
             (assoc! m k (realize-lazy-value v (inc depth))))
           (transient {})
           value))

       ;; Other seqable that might be lazy (like range) -> realize with limit
       (and (seqable? value)
            (not (string? value))
            (not (vector? value))
            (not (set? value)))
       (realize-lazy-seq-bounded value max-size)

       ;; Everything else passes through
       :else value))))


(defn wrap-delay-with-context
  "Wraps a delay body with error context for better diagnostics.
   When the delay fails to evaluate, the error includes arg-name and source info.
   This helps diagnose which argument caused the failure in complex execution graphs.

   Values are realized (lazy seqs -> vectors) to ensure errors occur here,
   not when the value is later consumed by a base function."
  [arg-name source body-fn]
  (delay
    (try
      (realize-lazy-value (body-fn))
      (catch Exception e
        (throw (ex-info (str "Error evaluating argument '" arg-name "': " (ex-message e))
                        {:type :execution-error/arg-evaluation-failed
                         :arg-name arg-name
                         :source source
                         :cause-type (type e)}
                        e))))))


(defn- build-uuid-ref-delay
  "Builds a delay for a UUID reference (fn or fn-result-value).

   Resolution is based on the TYPE OF REFERENCE, not arg-schema type:
   - ref<fn-result-value> → execute fn and use result (with caching)
   - ref<fn> → pass fn-id directly (for HOF, handlers, etc.)

   Parameters:
   - context: Execution context
   - uuid-value: The UUID reference
   - arg-name: Argument name for error context
   - fn-result-values: Map of fn-result-values from execution graph
   - execute-fn-result-value-fn: Function to execute fn-result-values (injected)"
  [context uuid-value arg-name fn-result-values execute-fn-result-value-fn]
  (if (contains? fn-result-values uuid-value)
    ;; ref<fn-result-value>: execute with caching
    (let [frv-context (assoc context :current-frv-id uuid-value)]
      (wrap-delay-with-context arg-name :fn-result-value
                               #(execute-fn-result-value-fn frv-context uuid-value)))
    ;; ref<fn>: pass fn-id directly (don't execute)
    (delay uuid-value)))


(defn build-delay
  "Builds a delay for an arg-value with error context.

   Resolution is based on the TYPE OF REFERENCE, not arg-schema type:
   - UUID in fn-result-values map → execute fn and use result (cached)
   - UUID not in fn-result-values → pass as fn-id (for HOF, handlers, etc.)
   - Non-UUID → literal value

   Parameters:
   - context: Execution context
   - arg-value: The argument value record
   - arg-schema: The argument schema
   - execute-fn-result-value-fn: Injected function to execute fn-result-values

   All delays include error context (arg-name, source) for better diagnostics."
  ^clojure.lang.Delay [context ^clojure.lang.IPersistentMap arg-value
                       ^clojure.lang.IPersistentMap arg-schema
                       execute-fn-result-value-fn]
  (let [value (:value arg-value)
        arg-name (:name arg-schema)]
    (if (uuid? value)
      ;; UUID: reference to fn or fn-result-value
      (let [fn-result-values (-> context :execution-graph :fn-result-values)]
        (build-uuid-ref-delay context value arg-name fn-result-values
                              execute-fn-result-value-fn))
      ;; Literal value
      (delay value))))


;; === Path Argument Resolution ===

(defn get-path-arg
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


;; === Argument Validation Helpers ===

(defn handle-validated-arg
  "Validates and wraps a user-provided argument value in a delay.
   Used for both direct provided args (from HOF) and path-args.
   The source parameter identifies the origin for debugging."
  [value arg-schema strict? max-unknown-types arg-name unknown-type-counter source]
  (types/validate-provided-arg-type! value arg-schema strict? max-unknown-types unknown-type-counter)
  (wrap-delay-with-context arg-name source #(identity value)))


(defn handle-path-arg-with-db-value
  "Handles case when path-arg exists but DB value takes precedence.

   Design Decision: DB values always win over path-args.
   This prevents accidental override of validated stored data.
   If you need to override a stored arg-value:
   1. Use `provided-args` in `execute` call (for HOF callables)
   2. Or update the arg-value in the database first

   Logs warning at WARN level to help debugging when override doesn't work.

   SECURITY: Does NOT log actual values to prevent sensitive data leakage.
   Only logs type information which is safe for debugging."
  [context arg-value arg-schema arg-schema-id arg-name _path-arg-value
   execute-fn-result-value-fn]
  (log/warn "Path-arg ignored: argument already defined in DB (DB value takes precedence)"
            {:arg-schema-id arg-schema-id
             :current-frv-id (:current-frv-id context)
             :arg-name arg-name
             ;; SECURITY: Log types only, not actual values
             :db-value-type (type (:value arg-value))
             :arg-type (:type arg-schema)
             :hint "Use provided-args in execute call or update DB arg-value to override"})
  (build-delay context arg-value arg-schema execute-fn-result-value-fn))


(defn throw-missing-required-arg!
  "Throws error for required arg with no value."
  [arg-schema-id arg-name current-frv-id]
  (throw (ex-info (str "Required argument '" arg-name "' not provided")
                  {:type :execution-error/missing-required-arg
                   :arg-schema-id arg-schema-id
                   :arg-name arg-name
                   :current-frv-id current-frv-id})))


;; === Main Argument Resolution ===

(defn build-arg-delays
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
   5. Optional arg with no value -> delay returning nil

   ## Parameters

   - context: Execution context
   - fn-data: Function data from graph {:arg-schemas {...} :arg-values {...}}
   - provided-args: Map of {arg-schema-id -> value} provided at call time
   - execute-fn-result-value-fn: Injected function to execute fn-result-values"
  [context fn-data provided-args execute-fn-result-value-fn]
  (let [{:keys [arg-schemas arg-values]} fn-data
        current-frv-id (:current-frv-id context)
        strict? (:strict-type-validation? context)
        max-unknown-types (:max-unknown-types context)
        unknown-type-counter (:unknown-type-counter context)]
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
            (assoc acc arg-name-kw (handle-validated-arg provided-value arg-schema strict? max-unknown-types arg-name unknown-type-counter :provided-arg))

            ;; 2. Path-arg value exists
            (some? path-arg-value)
            (assoc acc arg-name-kw
                   (if arg-value
                     ;; DB value takes precedence
                     (handle-path-arg-with-db-value context arg-value arg-schema
                                                    arg-schema-id arg-name path-arg-value
                                                    execute-fn-result-value-fn)
                     ;; No DB value - use path-arg
                     (handle-validated-arg path-arg-value arg-schema strict? max-unknown-types arg-name unknown-type-counter :path-arg)))

            ;; 3. Stored arg-value exists
            arg-value
            (assoc acc arg-name-kw (build-delay context arg-value arg-schema
                                                execute-fn-result-value-fn))

            ;; 4. Required arg with no value -> error
            (:required arg-schema)
            (throw-missing-required-arg! arg-schema-id arg-name current-frv-id)

            ;; 5. Optional arg with no value -> delay returning nil
            :else
            (assoc acc arg-name-kw (delay nil)))))
      {}
      arg-schemas)))
