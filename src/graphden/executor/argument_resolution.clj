(ns graphden.executor.argument-resolution
  "Argument resolution logic for the function executor.

   ## 2-Entity Schema

   Arguments are stored directly in the arg entity:
   - arg.value → literal JSONB value
   - arg.ref-id → FK to fn (execute and use result)
   - arg.is-fn → pass fn-id directly (for HOF)

   ## Resolution Priority

   1. Stored values in arg (value or ref-id) - always takes precedence
   2. Provided values (from HOF callable calls) - only if arg has no stored value
   3. Required arg with no value → error
   4. Optional arg with no value → delay returning nil

   ## Lazy Evaluation

   Arguments are wrapped in Clojure `delay` objects for lazy evaluation:
   - Values are only computed when dereferenced with @
   - Enables memoization of referenced fns across the execution graph"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.types :as types]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.graph :as graph]))


;; === Delay Building Infrastructure ===

(defn- realize-lazy-seq-bounded
  "Realizes a lazy sequence with size limit to prevent DoS."
  [coll max-size]
  (let [result (transient [])
        iter (clojure.lang.RT/iter coll)]
    (loop [n 0]
      (if (java.util.Iterator/.hasNext iter)
        (if (>= n max-size)
          (throw (ex-info "Lazy sequence exceeds maximum allowed size"
                          {:type :execution-error/lazy-seq-too-large
                           :max-size max-size}))
          (let [_ (conj! result (java.util.Iterator/.next iter))]
            (recur (unchecked-inc n))))
        (persistent! result)))))


(defn- realize-lazy-value
  "Forces evaluation of lazy sequences/maps to ensure errors are caught."
  ([value]
   (realize-lazy-value value 0))
  ([value depth]
   (let [max-size config/*max-lazy-seq-size*
         max-depth config/*max-nested-collection-depth*]
     (when (> depth max-depth)
       (throw (ex-info "Collection nesting exceeds maximum allowed depth"
                       {:type :execution-error/collection-too-deep
                        :max-depth max-depth})))
     (cond
       (nil? value) nil

       (and (seqable? value)
            (not (string? value))
            (not (map? value))
            (instance? clojure.lang.LazySeq value))
       (realize-lazy-seq-bounded value max-size)

       (map? value)
       (persistent!
         (reduce-kv
           (fn [m k v]
             (assoc! m k (realize-lazy-value v (inc depth))))
           (transient {})
           value))

       (and (seqable? value)
            (not (string? value))
            (not (vector? value))
            (not (set? value)))
       (realize-lazy-seq-bounded value max-size)

       :else value))))


(defn wrap-delay-with-context
  "Wraps a delay body with error context for better diagnostics."
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


(defn- build-ref-delay
  "Builds a delay for a ref-id reference.

   Resolution is based on arg.is-fn flag:
   - is-fn=true → pass fn-id directly (for HOF)
   - is-fn=false → execute fn and use result (with caching)

   Pass-through args: caller-args and arg-delays are passed to execute-ref-fn
   so it can find and forward propagated args to the referenced fn."
  [context ref-fn-id arg-name is-fn? execute-ref-fn caller-args arg-delays-atom]
  (if is-fn?
    (delay ref-fn-id)
    (wrap-delay-with-context arg-name :ref-fn
                             #(execute-ref-fn context ref-fn-id
                                              caller-args @arg-delays-atom))))


(defn- build-value-delay
  "Builds a delay for a literal value.
   If is-fn=true and value is a UUID string, parse it to UUID."
  [arg-name value is-fn?]
  (let [resolved-value (if (and is-fn? (string? value))
                         (try
                           (java.util.UUID/fromString value)
                           (catch IllegalArgumentException _
                             value))
                         value)]
    (wrap-delay-with-context arg-name :db-value
                             #(identity resolved-value))))


(defn build-delay
  "Builds a delay for an arg entity.

   Resolution:
   - ref-id set + is-fn=true: pass fn-id directly (for HOF)
   - ref-id set + is-fn=false: execute fn and use result (cached)
   - value set: literal value

   Pass-through args: caller-args and arg-delays-atom are passed through
   to enable propagating args to referenced fns."
  ^clojure.lang.Delay [context arg execute-ref-fn caller-args arg-delays-atom]
  (let [arg-name (:name arg)
        is-fn? (:is-fn arg)
        ref-fn-id (:ref-id arg)
        value (:value arg)]
    (cond
      (some? ref-fn-id)
      (build-ref-delay context ref-fn-id arg-name is-fn? execute-ref-fn
                       caller-args arg-delays-atom)

      (some? value)
      (build-value-delay arg-name value is-fn?)

      :else
      (delay nil))))


;; === Argument Validation Helpers ===

(defn handle-validated-arg
  "Validates and wraps a user-provided argument value in a delay."
  [value arg strict? max-unknown-types arg-name unknown-type-counter source]
  (types/validate-provided-arg-type! value arg strict? max-unknown-types unknown-type-counter)
  (wrap-delay-with-context arg-name source #(identity value)))


(defn handle-runtime-arg-with-db-value
  "Logs warning when runtime arg exists but DB value takes precedence.
   Returns nil - the actual delay is built by caller using build-delay."
  [arg-id arg-name source]
  (log/warn (str (name source) " ignored: argument already defined in DB")
            {:arg-id arg-id
             :arg-name arg-name
             :source source}))


(defn throw-missing-required-arg!
  "Throws error for required arg with no value."
  [arg-id arg-name]
  (throw (ex-info (str "Required argument '" arg-name "' not provided")
                  {:type :execution-error/missing-required-arg
                   :arg-id arg-id
                   :arg-name arg-name})))


;; === Main Argument Resolution ===

(defn- arg-has-value?
  "Returns true if arg has a bound value (via value or ref-id)."
  [arg]
  (or (some? (:value arg))
      (some? (:ref-id arg))))


(defn build-arg-delays
  "Builds delays for all args.
   Returns a map of {arg-name-keyword -> delay}.

   ## Resolution Priority

   1. Stored value in arg (value or ref-id) - always takes precedence
   2. Provided value (from HOF callable calls) - only if no stored value
   3. Required arg with no value -> error
   4. Optional arg with no value -> delay returning nil

   ## Key Naming

   For inherited args (with source-id), the delay is keyed by the source arg's
   name so the base-fn implementation can find it. This allows args to be
   renamed at composition level (via :as syntax) while still working with
   base-fn implementations that expect original arg names.

   ## Pass-Through Args

   Uses an atom to store built delays so that ref-fn execution can access
   all arg delays for pass-through propagation."
  [context fn-data provided-args execute-ref-fn]
  (let [args (:args fn-data)
        execution-graph (:execution-graph context)
        strict? (:strict-type-validation? context)
        max-unknown-types (:max-unknown-types context)
        unknown-type-counter (:unknown-type-counter context)
        ;; Use atom to allow delays to reference other delays for pass-through
        arg-delays-atom (atom {})]
    (doseq [arg args]
      (let [arg-id (:id arg)
            arg-name (:name arg)
            ;; Key by root arg name so base-fn impl can find it (O(1) lookup)
            ;; For inherited args, this is the base-fn arg name
            key-name (graph/get-root-arg-name execution-graph arg)
            key-name-kw (keyword key-name)
            provided-value (get provided-args arg-id)
            has-stored-value? (arg-has-value? arg)
            delay-val (cond
                        has-stored-value?
                        (do
                          (when (some? provided-value)
                            (handle-runtime-arg-with-db-value arg-id arg-name :provided-arg))
                          (build-delay context arg execute-ref-fn args arg-delays-atom))

                        (some? provided-value)
                        (handle-validated-arg provided-value arg
                                              strict? max-unknown-types
                                              arg-name unknown-type-counter
                                              :provided-arg)

                        (:required arg)
                        (throw-missing-required-arg! arg-id arg-name)

                        :else
                        (delay nil))]
        (swap! arg-delays-atom assoc key-name-kw delay-val)))
    @arg-delays-atom))
