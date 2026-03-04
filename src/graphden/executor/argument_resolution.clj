(ns graphden.executor.argument-resolution
  "Argument resolution logic for the function executor.

   This module handles:
   - Building delays for lazy argument evaluation
   - Resolving argument values from various sources (provided args from HOF, DB)
   - Type validation for provided arguments

   ## Why Delays?

   Arguments are wrapped in Clojure `delay` objects for lazy evaluation:
   - Values are only computed when dereferenced with @
   - Enables memoization of fn-usages across the execution graph

   ## Reference Types

   arg-value has two mutually exclusive fields (XOR constraint at DB level):
   - value: JSONB literal value
   - fn-usage-id: FK to fn-usage

   Resolution is based on which field is set AND arg-schema.first-class flag:
   - fn-usage-id set + first-class=true → get fn-id from fn-usage, pass directly (for HOF)
   - fn-usage-id set + first-class=false → execute fn-usage and use result
   - value set → literal value

   ## Argument Resolution Priority

   1. `arg-values` - Stored values from database (always takes precedence)
   2. `provided-args` - Values passed at call time by HOF (only for args without DB value)

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
    [graphden.storage.protocol.config :as config]))


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
  "Builds a delay for a UUID reference to fn-usage.

   Resolution is based on arg-schema.first-class flag:
   - first-class=true → get fn-id from fn-usage, pass directly (for HOF)
   - first-class=false → execute fn-usage and use result (with caching)

   Parameters:
   - context: Execution context
   - uuid-value: The UUID reference (must be fn-usage-id)
   - arg-name: Argument name for error context
   - fn-usages: Map of fn-usages from execution graph
   - first-class?: Whether this arg should receive fn-id directly (HOF)
   - execute-fn-usage-fn: Function to execute fn-usages (injected)"
  [context uuid-value arg-name fn-usages first-class? execute-fn-usage-fn]
  (if-let [fn-usage (get fn-usages uuid-value)]
    (if first-class?
      ;; first-class=true: pass fn-id directly (for HOF, handlers, etc.)
      (delay (:fn-id fn-usage))
      ;; first-class=false: execute fn-usage with caching
      (let [fu-context (assoc context :current-fn-usage-id uuid-value)]
        (wrap-delay-with-context arg-name :fn-usage
                                 #(execute-fn-usage-fn fu-context uuid-value))))
    ;; UUID not in fn-usages - this shouldn't happen with proper validation
    ;; but handle gracefully by passing as-is
    (delay uuid-value)))


(defn- extract-arg-value
  "Extracts the actual value from an arg-value record.
   Returns a map with :type and :value keys:
   - {:type :fn-usage :value <fn-usage-id>}
   - {:type :literal :value <literal-value>}

   Handles arg-value records with FK fields (fn-usage-id, value)
   or direct values (already unwrapped)."
  [arg-value]
  (cond
    ;; fn-usage-id set: reference to fn-usage
    (and (map? arg-value) (some? (:fn-usage-id arg-value)))
    {:type :fn-usage :value (:fn-usage-id arg-value)}

    ;; :value field set: literal value in arg-value record
    (and (map? arg-value) (contains? arg-value :value))
    {:type :literal :value (:value arg-value)}

    ;; Direct value (already unwrapped)
    :else
    {:type :literal :value arg-value}))


(defn build-delay
  "Builds a delay for an arg-value with error context.

   Resolution is based on arg-value type AND arg-schema.first-class flag:
   - :fn-usage + first-class=true: get fn-id from fn-usage, pass directly (for HOF)
   - :fn-usage + first-class=false: execute fn-usage and use result (cached)
   - :literal: literal value (returned as-is)

   NOTE: Nested structures with fn-usage references are NOT resolved.
   Use explicit graph functions (pair, assoc-any, conj-any) to build
   structures containing fn-usage references.

   Parameters:
   - context: Execution context
   - arg-value: The argument value record OR direct value (from cache)
   - arg-schema: The argument schema (includes first-class flag)
   - execute-fn-usage-fn: Injected function to execute fn-usages

   All delays include error context (arg-name, source) for better diagnostics."
  ^clojure.lang.Delay [context arg-value arg-schema execute-fn-usage-fn]
  (let [{value-type :type the-value :value} (extract-arg-value arg-value)
        arg-name (:name arg-schema)
        first-class? (:first-class arg-schema)
        fn-usages (-> context :execution-graph :fn-usages)]
    (case value-type
      ;; fn-usage-id set: reference to fn-usage
      ;; Behavior depends on first-class? flag from arg-schema
      :fn-usage
      (build-uuid-ref-delay context the-value arg-name fn-usages
                            first-class? execute-fn-usage-fn)

      ;; value set: literal value
      :literal
      (wrap-delay-with-context arg-name :db-value
                               #(identity the-value)))))


;; === Argument Validation Helpers ===

(defn handle-validated-arg
  "Validates and wraps a user-provided argument value in a delay.
   Used for provided args (from HOF callable calls).
   The source parameter identifies the origin for debugging."
  [value arg-schema strict? max-unknown-types arg-name unknown-type-counter source]
  (types/validate-provided-arg-type! value arg-schema strict? max-unknown-types unknown-type-counter)
  (wrap-delay-with-context arg-name source #(identity value)))


(defn handle-runtime-arg-with-db-value
  "Handles case when runtime arg (provided-arg from HOF) exists but DB value takes precedence.

   Design Decision: DB values always win over runtime args.
   This prevents accidental override of validated stored data.
   To change an arg value, update the arg-value in the database.

   Logs warning at WARN level to help debugging when override doesn't work.

   SECURITY: Does NOT log actual values to prevent sensitive data leakage.
   Only logs type information which is safe for debugging."
  [context arg-value arg-schema arg-schema-id arg-name source
   execute-fn-usage-fn]
  (log/warn (str (name source) " ignored: argument already defined in DB (DB value takes precedence)")
            {:arg-schema-id arg-schema-id
             :current-fn-usage-id (:current-fn-usage-id context)
             :arg-name arg-name
             :source source
             ;; SECURITY: Log types only, not actual values
             :db-value-type (type (:value arg-value))
             :arg-type (:type arg-schema)
             :hint "Update DB arg-value to change this argument"})
  (build-delay context arg-value arg-schema execute-fn-usage-fn))


(defn throw-missing-required-arg!
  "Throws error for required arg with no value."
  [arg-schema-id arg-name current-fn-usage-id]
  (throw (ex-info (str "Required argument '" arg-name "' not provided")
                  {:type :execution-error/missing-required-arg
                   :arg-schema-id arg-schema-id
                   :arg-name arg-name
                   :current-fn-usage-id current-fn-usage-id})))


;; === Main Argument Resolution ===

(defn build-arg-delays
  "Builds delays for all arg-schemas.
   Returns a map of {arg-name-keyword -> delay}.

   All arguments are wrapped in delay for lazy evaluation.
   Base functions receive delays and use @ (deref) to get values.

   ## Argument Resolution Priority

   1. Stored arg-value from DB (ALWAYS takes precedence, cannot be overridden)
      - If provided-arg also exists -> warning logged, DB value used
   2. Provided value (from HOF callable calls) - only if no DB value
   3. Required arg with no value -> error
   4. Optional arg with no value -> delay returning nil

   ## Parameters

   - context: Execution context
   - fn-data: Function data from graph {:arg-schemas {...} :arg-values {...}}
   - provided-args: Map of {arg-schema-id -> value} provided at call time (only for free args)
   - execute-fn-usage-fn: Injected function to execute fn-usages"
  [context fn-data provided-args execute-fn-usage-fn]
  (let [{:keys [arg-schemas arg-values]} fn-data
        current-fn-usage-id (:current-fn-usage-id context)
        strict? (:strict-type-validation? context)
        max-unknown-types (:max-unknown-types context)
        unknown-type-counter (:unknown-type-counter context)]
    (reduce-kv
      (fn [acc arg-schema-id arg-schema]
        (let [arg-name (:name arg-schema)
              arg-name-kw (keyword arg-name)
              provided-value (get provided-args arg-schema-id)
              arg-value (get arg-values arg-schema-id)]
          (cond
            ;; 1. Stored arg-value exists - DB always takes precedence
            ;;    Runtime args (provided-args) are ignored with warning
            arg-value
            (do
              (when (some? provided-value)
                (handle-runtime-arg-with-db-value context arg-value arg-schema
                                                  arg-schema-id arg-name :provided-arg
                                                  execute-fn-usage-fn))
              (assoc acc arg-name-kw (build-delay context arg-value arg-schema
                                                  execute-fn-usage-fn)))

            ;; 2. No DB value - use provided-arg if available
            (some? provided-value)
            (assoc acc arg-name-kw (handle-validated-arg provided-value arg-schema strict? max-unknown-types arg-name unknown-type-counter :provided-arg))

            ;; 3. Required arg with no value -> error
            (:required arg-schema)
            (throw-missing-required-arg! arg-schema-id arg-name current-fn-usage-id)

            ;; 4. Optional arg with no value -> delay returning nil
            :else
            (assoc acc arg-name-kw (delay nil)))))
      {}
      arg-schemas)))
