(ns graphden.executor.argument-resolution
  "Argument resolution logic for the function executor.

   This module handles:
   - Building delays for lazy argument evaluation
   - Resolving argument values from various sources (provided, call-site-args, DB)
   - Type validation for provided arguments

   ## Why Delays?

   Arguments are wrapped in Clojure `delay` objects for lazy evaluation:
   - Values are only computed when dereferenced with @
   - Enables memoization of call-sites across the execution graph

   ## Reference Types

   Resolution is based on the TYPE OF REFERENCE, not arg-schema type:
   - ref<fn> → pass fn-id directly (for HOF, handlers, etc. - don't execute)
   - ref<call-site> → execute fn and use result (with caching)

   This means HOF (map, filter, reduce) receive fn-id and create callables themselves.

   ## Argument Resolution Priority

   1. `provided-args` - Explicitly passed at execute time (highest priority)
   2. `call-site-args` - Runtime args from context for specific call sites
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
  "Builds a delay for a UUID reference (fn or call-site).

   Resolution is based on the TYPE OF REFERENCE, not arg-schema type:
   - ref<call-site> → execute fn and use result (with caching)
   - ref<fn> → pass fn-id directly (for HOF, handlers, etc.)

   Parameters:
   - context: Execution context
   - uuid-value: The UUID reference
   - arg-name: Argument name for error context
   - call-sites: Map of call-sites from execution graph
   - execute-call-site-fn: Function to execute call-sites (injected)"
  [context uuid-value arg-name call-sites execute-call-site-fn]
  (if (contains? call-sites uuid-value)
    ;; ref<call-site>: execute with caching
    (let [cs-context (assoc context :current-call-site-id uuid-value)]
      (wrap-delay-with-context arg-name :call-site
                               #(execute-call-site-fn cs-context uuid-value)))
    ;; ref<fn>: pass fn-id directly (don't execute)
    (delay uuid-value)))


(defn- try-parse-uuid
  "Attempts to parse value as UUID.
   Returns UUID if value is already a UUID or a valid UUID string.
   Returns nil for non-UUID values.

   This is needed because PostgreSQL JSONB stores UUIDs as strings,
   so when reading arg-values back, UUIDs appear as strings."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try
                  (java.util.UUID/fromString v)
                  (catch IllegalArgumentException _ nil))
    :else nil))


(defn- extract-arg-value
  "Extracts the actual value from an arg-value.
   Handles multiple formats:
   1. Union format from cache with fn-ref: {:kind :fn-ref :fn-id <uuid>}
   2. Union format from cache with literal: {:kind :literal :value <value>}
   3. arg-value record with :value key (from direct resolution or tests)
   4. Direct value (already unwrapped)

   The cache stores values in union format (see cache-protocol.value-codec),
   while direct resolution stores arg-value records with :id and :value."
  [arg-value]
  (cond
    ;; Union format: {:kind :fn-ref :fn-id <uuid>}
    (and (map? arg-value) (= :fn-ref (:kind arg-value)))
    (:fn-id arg-value)

    ;; Union format with :value OR arg-value record/test format with :value key
    (or (and (map? arg-value) (= :literal (:kind arg-value)))
        (and (map? arg-value)
             (contains? arg-value :value)
             (not (contains? arg-value :kind))))
    (:value arg-value)

    ;; Direct value (already unwrapped)
    :else
    arg-value))


(defn build-delay
  "Builds a delay for an arg-value with error context.

   Resolution is based on the TYPE OF REFERENCE, not arg-schema type:
   - UUID in call-sites map → execute fn and use result (cached)
   - UUID not in call-sites → pass as fn-id (for HOF, handlers, etc.)
   - Non-UUID → literal value

   Parameters:
   - context: Execution context
   - arg-value: The argument value record OR direct value (from cache)
   - arg-schema: The argument schema
   - execute-call-site-fn: Injected function to execute call-sites

   All delays include error context (arg-name, source) for better diagnostics.

   Note: UUID references may come as strings from PostgreSQL JSONB storage,
   so we parse them with try-parse-uuid. The arg-value format varies between
   direct resolution (map with :value) and cached resolution (direct value)."
  ^clojure.lang.Delay [context arg-value arg-schema execute-call-site-fn]
  (let [raw-value (extract-arg-value arg-value)
        arg-name (:name arg-schema)
        ;; Try to parse as UUID - handles both native UUIDs and UUID strings
        ;; from PostgreSQL JSONB storage
        parsed-uuid (try-parse-uuid raw-value)]
    (if parsed-uuid
      ;; UUID: reference to fn or call-site
      (let [call-sites (-> context :execution-graph :call-sites)]
        (build-uuid-ref-delay context parsed-uuid arg-name call-sites
                              execute-call-site-fn))
      ;; Literal value
      (delay raw-value))))


;; === Call Site Argument Resolution ===

(defn get-call-site-arg
  "Gets a runtime argument value from call-site-args.

   For root function (current-call-site-id is nil): looks up by arg-schema-id directly
   For nested fns via call-site: looks up by [call-site-id arg-schema-id]

   Returns the value or nil if not found."
  [context arg-schema-id]
  (let [current-call-site-id (:current-call-site-id context)
        call-site-args (:call-site-args context)]
    (if current-call-site-id
      ;; Nested fn via call-site: use [call-site-id arg-schema-id] as key
      (get call-site-args [current-call-site-id arg-schema-id])
      ;; Root function: use arg-schema-id directly
      (get call-site-args arg-schema-id))))


;; === Argument Validation Helpers ===

(defn handle-validated-arg
  "Validates and wraps a user-provided argument value in a delay.
   Used for both direct provided args (from HOF) and call-site-args.
   The source parameter identifies the origin for debugging."
  [value arg-schema strict? max-unknown-types arg-name unknown-type-counter source]
  (types/validate-provided-arg-type! value arg-schema strict? max-unknown-types unknown-type-counter)
  (wrap-delay-with-context arg-name source #(identity value)))


(defn handle-runtime-arg-with-db-value
  "Handles case when runtime arg (provided-arg or call-site-arg) exists but DB value takes precedence.

   Design Decision: DB values always win over runtime args.
   This prevents accidental override of validated stored data.
   To change an arg value, update the arg-value in the database.

   Logs warning at WARN level to help debugging when override doesn't work.

   SECURITY: Does NOT log actual values to prevent sensitive data leakage.
   Only logs type information which is safe for debugging."
  [context arg-value arg-schema arg-schema-id arg-name source
   execute-call-site-fn]
  (log/warn (str (name source) " ignored: argument already defined in DB (DB value takes precedence)")
            {:arg-schema-id arg-schema-id
             :current-call-site-id (:current-call-site-id context)
             :arg-name arg-name
             :source source
             ;; SECURITY: Log types only, not actual values
             :db-value-type (type (:value arg-value))
             :arg-type (:type arg-schema)
             :hint "Update DB arg-value to change this argument"})
  (build-delay context arg-value arg-schema execute-call-site-fn))


(defn throw-missing-required-arg!
  "Throws error for required arg with no value."
  [arg-schema-id arg-name current-call-site-id]
  (throw (ex-info (str "Required argument '" arg-name "' not provided")
                  {:type :execution-error/missing-required-arg
                   :arg-schema-id arg-schema-id
                   :arg-name arg-name
                   :current-call-site-id current-call-site-id})))


;; === Main Argument Resolution ===

(defn build-arg-delays
  "Builds delays for all arg-schemas.
   Returns a map of {arg-name-keyword -> delay}.

   All arguments are wrapped in delay for lazy evaluation.
   Base functions receive delays and use @ (deref) to get values.

   ## Argument Resolution Priority

   1. Stored arg-value from DB (ALWAYS takes precedence, cannot be overridden)
      - If provided-arg or call-site-arg also exists -> warning logged, DB value used
   2. Provided value (from HOF callable calls) - only if no DB value
   3. Call-site-arg value - only if no DB value and no provided-arg:
      - For root fn: looked up by arg-schema-id
      - For nested fn via call-site: looked up by [call-site-id arg-schema-id]
   4. Required arg with no value -> error
   5. Optional arg with no value -> delay returning nil

   ## Parameters

   - context: Execution context
   - fn-data: Function data from graph {:arg-schemas {...} :arg-values {...}}
   - provided-args: Map of {arg-schema-id -> value} provided at call time (only for free args)
   - execute-call-site-fn: Injected function to execute call-sites"
  [context fn-data provided-args execute-call-site-fn]
  (let [{:keys [arg-schemas arg-values]} fn-data
        current-call-site-id (:current-call-site-id context)
        strict? (:strict-type-validation? context)
        max-unknown-types (:max-unknown-types context)
        unknown-type-counter (:unknown-type-counter context)]
    (reduce-kv
      (fn [acc arg-schema-id arg-schema]
        (let [arg-name (:name arg-schema)
              arg-name-kw (keyword arg-name)
              provided-value (get provided-args arg-schema-id)
              call-site-arg-value (get-call-site-arg context arg-schema-id)
              arg-value (get arg-values arg-schema-id)]
          (cond
            ;; 1. Stored arg-value exists - DB always takes precedence
            ;;    Runtime args (provided-args, call-site-args) are ignored with warning
            arg-value
            (do
              (when (some? provided-value)
                (handle-runtime-arg-with-db-value context arg-value arg-schema
                                                  arg-schema-id arg-name :provided-arg
                                                  execute-call-site-fn))
              (when (some? call-site-arg-value)
                (handle-runtime-arg-with-db-value context arg-value arg-schema
                                                  arg-schema-id arg-name :call-site-arg
                                                  execute-call-site-fn))
              (assoc acc arg-name-kw (build-delay context arg-value arg-schema
                                                  execute-call-site-fn)))

            ;; 2. No DB value - use provided-arg if available
            (some? provided-value)
            (assoc acc arg-name-kw (handle-validated-arg provided-value arg-schema strict? max-unknown-types arg-name unknown-type-counter :provided-arg))

            ;; 3. No DB value, no provided-arg - use call-site-arg if available
            (some? call-site-arg-value)
            (assoc acc arg-name-kw (handle-validated-arg call-site-arg-value arg-schema strict? max-unknown-types arg-name unknown-type-counter :call-site-arg))

            ;; 4. Required arg with no value -> error
            (:required arg-schema)
            (throw-missing-required-arg! arg-schema-id arg-name current-call-site-id)

            ;; 5. Optional arg with no value -> delay returning nil
            :else
            (assoc acc arg-name-kw (delay nil)))))
      {}
      arg-schemas)))
