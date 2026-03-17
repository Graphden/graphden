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




(defn- build-ref-delay
  "Builds a delay for a ref-id reference.

   Resolution is based on arg.is-fn flag:
   - is-fn=true → pass fn-id directly (for HOF)
   - is-fn=false → execute fn and use result (with caching)

   Pass-through args: caller-args are passed to execute-ref-fn.
   The arg-delays-atom contains delays built so far for the current fn.
   We read from the atom at delay-force time (not creation time) so that
   all delays are available for pass-through propagation.

   The triggering-arg-id is passed in context to be included in cache keys.
   This ensures that different calls from different args (e.g., item1 vs item2)
   get different cache keys even if the target fn and propagated args are same."
  [context ref-fn-id arg-name is-fn? execute-ref-fn caller-args arg-delays-atom triggering-arg-id]
  (if is-fn?
    (delay ref-fn-id)
    ;; Wrap all captured values in a vector to prevent individual closure clearing
    ;; The JVM may clear individual locals but a vector remains intact
    (let [captured [context ref-fn-id caller-args arg-delays-atom execute-ref-fn arg-name triggering-arg-id]]
      (delay
        (try
          (let [[ctx fn-id args delays-atom exec-fn arg-nm trig-arg-id] captured
                delays-map (if delays-atom @delays-atom {})
                ;; Add triggering-arg-id to context for cache key differentiation
                ctx-with-trigger (assoc ctx :triggering-arg-id trig-arg-id)]
            (when (nil? exec-fn)
              (throw (ex-info "execute-ref-fn became nil in delay closure"
                              {:type :execution-error/closure-capture-issue
                               :arg-name arg-nm
                               :ref-fn-id fn-id})))
            (let [result (exec-fn ctx-with-trigger fn-id args delays-map)]
              (realize-lazy-value result)))
          (catch Exception e
            (let [[_ _ _ _ _ arg-nm _] captured]
              (throw (ex-info (str "Error evaluating argument '" arg-nm "': " (ex-message e))
                              {:type :execution-error/arg-evaluation-failed
                               :arg-name arg-nm
                               :source :ref-fn
                               :cause-type (type e)}
                              e)))))))))


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
    ;; For literal values, use a simple delay without the wrapper
    ;; to avoid potential closure capture issues
    (delay resolved-value)))


(defn build-delay
  "Builds a delay for an arg entity.

   Resolution:
   - ref-id set + is-fn=true: pass fn-id directly (for HOF)
   - ref-id set + is-fn=false: execute fn and use result (cached)
   - value set: literal value

   Pass-through args: caller-args and arg-delays-atom are passed through
   to enable propagating args to referenced fns.

   The arg's id is passed as triggering-arg-id to differentiate cache keys
   when the same fn is called from different args."
  ^clojure.lang.Delay [context arg execute-ref-fn caller-args arg-delays-atom]
  (let [arg-name (:name arg)
        arg-id (:id arg)
        is-fn? (:is-fn arg)
        ref-fn-id (:ref-id arg)
        value (:value arg)]
    (cond
      (some? ref-fn-id)
      (build-ref-delay context ref-fn-id arg-name is-fn? execute-ref-fn
                       caller-args arg-delays-atom arg-id)

      (some? value)
      (build-value-delay arg-name value is-fn?)

      :else
      (delay nil))))


;; === Argument Validation Helpers ===

(defn handle-validated-arg
  "Validates and wraps a user-provided argument value in a delay."
  [value arg strict? max-unknown-types arg-name unknown-type-counter source]
  (types/validate-provided-arg-type! value arg strict? max-unknown-types unknown-type-counter)
  ;; For provided values, use a simple delay without the wrapper
  ;; to avoid potential closure capture issues
  (delay value))


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


(defn- fn-in-parent-chain?
  "Checks if fn-id is in the direct parent chain starting from start-fn-id.
   The parent chain follows parent-id links (inheritance), NOT ref-id links."
  [fns fn-id start-fn-id]
  (let [result
        (loop [current-fn-id start-fn-id
               depth 0]
          (cond
            (> depth 100) false  ; safety limit
            (nil? current-fn-id) false
            (= current-fn-id fn-id) true
            :else
            (let [fn-rec (get fns current-fn-id)]
              (recur (:parent-id fn-rec) (inc depth)))))]
    result))


(defn- arg-belongs-to-current-fn?
  "Checks if arg 'belongs' to the current fn execution context.

   An arg should be included in by-name mapping only if it's meant for
   this fn's base-fn execution, not if it should propagate to a ref-fn.

   ## Key insight for list-10 pattern:

   When editor-routes (extends list-10) is executed, it has 10 args with same root-name 'item'.
   Each itemN-binding has source-id pointing to a different source:
   - item10-binding.source-id = list-10.item10 (list-10's OWN arg)
   - item9-binding.source-id = list-10.propagated-item9 (propagated from ref)

   The distinction: item10's source (list-10.item10) traces to conj-any via PARENT CHAIN,
   while item9's source traces to list-10-9.item9 which is via REF (not parent).

   Only item10-binding should be in by-name[:item] at this level.
   item9-binding will be passed to list-10-9's execution via propagation.

   ## For own args (fn-id = current-fn-id):

   Include if:
   1. ROOT must belong to BASE-FN
   2. IMMEDIATE source's fn-id must be in DIRECT parent chain (not from refs)

   ## For inherited args (fn-id in parent chain):

   Include if:
   1. ROOT belongs to BASE-FN
   2. Arg is bound (has value or ref-id) OR is primary (has name)"
  [execution-graph arg current-fn-id]
  (let [args-by-id (:args-by-id execution-graph)
        fns (:fns execution-graph)
        current-fn-rec (get fns current-fn-id)
        parent-fn-id (:parent-id current-fn-rec)
        arg-fn-id (:fn-id arg)
        ;; Find the base-fn (end of parent chain)
        base-fn-id (loop [fn-id current-fn-id
                          depth 0]
                     (if (or (nil? fn-id) (> depth 100))
                       nil
                       (let [fn-rec (get fns fn-id)
                             parent-id (:parent-id fn-rec)]
                         (if (nil? parent-id)
                           fn-id  ; This is the base-fn
                           (recur parent-id (inc depth))))))
        ;; Follow source-id chain to find root arg
        root-arg (loop [current-arg arg
                        depth 0]
                   (if (> depth 100)
                     nil  ; safety limit
                     (if-let [source-id (:source-id current-arg)]
                       (if-let [source-arg (get args-by-id source-id)]
                         (recur source-arg (inc depth))
                         current-arg)  ; source not found, use current
                       current-arg)))  ; no source-id, this is root
        ;; Check 1: root arg must belong to base-fn
        root-belongs-to-base? (and (some? root-arg)
                                   (some? base-fn-id)
                                   (= (:fn-id root-arg) base-fn-id))]
    (if-not root-belongs-to-base?
      false  ; Fail check 1: root doesn't belong to base-fn
      ;; Check 2 depends on whether this is an own arg or inherited
      (cond
        ;; Base-fn arg itself (no source-id)
        (nil? (:source-id arg))
        true

        ;; Own arg (from current fn)
        (= arg-fn-id current-fn-id)
        (let [source-arg (get args-by-id (:source-id arg))]
          (if (nil? source-arg)
            true  ; Source not found, include to be safe
            ;; KEY CHECK: source arg's fn-id must be in the DIRECT parent chain
            ;; This excludes propagated args from refs (their source is in ref'd fn, not parent)
            (let [source-fn-id (:fn-id source-arg)
                  source-in-parent-chain? (fn-in-parent-chain? fns source-fn-id parent-fn-id)]
              (if source-in-parent-chain?
                ;; Source is in direct parent chain - include if primary or bound
                (or (some? (:name arg))
                    (some? (:name source-arg))
                    (some? (:value arg))
                    (some? (:ref-id arg)))
                ;; Source is NOT in parent chain (from ref) - exclude from by-name
                ;; This arg will be passed via propagation when the ref-fn executes
                false))))

        ;; Inherited arg (from parent chain)
        ;; Include if bound (has value/ref-id) OR primary (has name)
        :else
        (or (some? (:name arg))
            (some? (:value arg))
            (some? (:ref-id arg)))))))


(defn build-arg-delays
  "Builds delays for all args.
   Returns a map with two keys:
   - :by-name {arg-name-keyword -> delay} - for base-fn implementation lookup
   - :by-id {arg-id -> delay} - for propagation lookup by arg-id

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

   Uses atoms to store built delays so that ref-fn execution can access
   all arg delays for pass-through propagation. The :by-id mapping ensures
   each arg gets its own delay entry even when multiple args share root name."
  [context fn-data provided-args execute-ref-fn]
  (let [args (:args fn-data)
        execution-graph (:execution-graph context)
        fn-rec (:fn fn-data)
        parent-fn-id (:parent-id fn-rec)
        strict? (:strict-type-validation? context)
        max-unknown-types (:max-unknown-types context)
        unknown-type-counter (:unknown-type-counter context)
        ;; Use atoms to allow delays to reference other delays for pass-through
        ;; by-name: keyed by root arg name for base-fn impl
        ;; by-id: keyed by arg-id for propagation (no collision)
        ;; by-name-info: tracks {key -> {:delay delay :has-ref? bool}} to handle conflicts
        arg-delays-by-name-atom (atom {})
        arg-delays-by-id-atom (atom {})
        arg-info-by-name-atom (atom {})]
    (doseq [arg args]
      (let [arg-id (:id arg)
            arg-name (:name arg)
            ;; Key by root arg name so base-fn impl can find it (O(1) lookup)
            ;; For inherited args, this is the base-fn arg name
            key-name (graph/get-root-arg-name execution-graph arg)
            key-name-kw (keyword key-name)
            provided-value (get provided-args arg-id)
            has-stored-value? (arg-has-value? arg)
            include-in-by-name? (arg-belongs-to-current-fn? execution-graph arg (:id fn-rec))
            ;; provided-value may be a delay (from execute-ref-fn) or a plain value.
            ;; If it's a delay, use it directly. If plain value, wrap in delay.
            provided-is-delay? (instance? clojure.lang.Delay provided-value)
            has-provided-value? (and (some? provided-value) (not has-stored-value?))
            delay-val (cond
                        has-stored-value?
                        (do
                          (when (some? provided-value)
                            (handle-runtime-arg-with-db-value arg-id arg-name :provided-arg))
                          (build-delay context arg execute-ref-fn args arg-delays-by-id-atom))

                        ;; If provided value is already a delay, use it directly
                        provided-is-delay?
                        provided-value

                        ;; Plain value - wrap in delay with validation
                        (some? provided-value)
                        (handle-validated-arg provided-value arg
                                              strict? max-unknown-types
                                              arg-name unknown-type-counter
                                              :provided-arg)

                        (:required arg)
                        (throw-missing-required-arg! arg-id arg-name)

                        :else
                        (delay nil))]
        ;; Store by name for base-fn impl.
        ;; Include if:
        ;; 1. arg belongs to current fn (include-in-by-name? = true), OR
        ;; 2. arg has a provided value/delay (from caller) and no stored value
        ;;
        ;; For collision handling: when multiple args share the same root-name, prefer:
        ;; 1. Provided values over stored values (runtime override)
        ;; 2. ref-id args over value args (ref-id = execute fn, more specific)
        ;;
        ;; Example: pair has both item2 (current fn's arg) and item (inherited path).
        ;; - item2: include-in-by-name?=true, gets added
        ;; - item: include-in-by-name?=false, but if it has provided-value, add it
        (when (or include-in-by-name? has-provided-value?)
          (let [existing-info (get @arg-info-by-name-atom key-name-kw)
                existing-has-ref? (:has-ref? existing-info)
                current-has-ref? (some? (:ref-id arg))
                ;; Priority:
                ;; 1. provided value beats everything
                ;; 2. own fn's arg (include-in-by-name?) beats inherited
                ;; 3. ref-id args beat value args
                should-replace? (cond
                                  (nil? existing-info)
                                  true

                                  ;; Provided value always wins
                                  has-provided-value?
                                  true

                                  ;; Own fn's arg beats inherited (when existing doesn't have provided value)
                                  (and include-in-by-name? (not (:has-provided-value? existing-info)))
                                  true

                                  :else
                                  false)]
            (when should-replace?
              (swap! arg-info-by-name-atom assoc key-name-kw {:delay delay-val
                                                              :has-ref? current-has-ref?
                                                              :has-provided-value? has-provided-value?})
              (swap! arg-delays-by-name-atom assoc key-name-kw delay-val))))
        ;; Store by id for propagation (no collision possible)
        (swap! arg-delays-by-id-atom assoc arg-id delay-val)))
    (let [result {:by-name @arg-delays-by-name-atom
                   :by-id @arg-delays-by-id-atom}]
      result)))
