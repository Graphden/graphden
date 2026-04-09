(ns graphden.executor.queue
  "Trampolined executor that avoids deep stack recursion.

   ## Problem

   The delay-based executor creates stack overflow when delays are dereferenced:
   execute-internal → @delay → execute-ref-fn → execute-internal → ...

   ## Solution: Trampolined Execution

   Instead of recursive calls, we use a trampoline pattern:
   1. SmartDelay.deref() throws a special 'NeedComputation' marker when value not ready
   2. Top-level executor catches this, processes the needed computation, retries
   3. All execution happens in a flat loop at the top level

   This keeps stack depth O(1) regardless of graph depth."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.context :as ctx]
    [graphden.executor.types :as types]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph])
  (:import
    (java.util
      ArrayDeque)))


;; =============================================================================
;; NeedComputation marker - thrown when SmartDelay needs a value computed
;; =============================================================================

(deftype NeedComputation
  [cache-key task]
  ;; Extend Throwable so it can be caught
  Object

  (toString [_] (str "NeedComputation: " cache-key)))


;; Use ex-info wrapper for throwable
(defn throw-need-computation
  [cache-key task]
  (throw (ex-info "NeedComputation" {:type ::need-computation :cache-key cache-key :task task})))


(defn need-computation-ex?
  [e]
  (= ::need-computation (:type (ex-data e))))


;; =============================================================================
;; Execution State
;; =============================================================================

(defrecord ExecutionState
  [value-map        ; atom {cache-key -> value}
   result-cache     ; atom from context
   pending-tasks    ; atom {cache-key -> task}
   context])


;; =============================================================================
;; SmartDelay - throws NeedComputation if value not ready
;; =============================================================================

(deftype SmartDelay
  [cache-key
   exec-state
   task-atom        ; atom containing task (or nil if already computed)
   ^:volatile-mutable cached-value
   ^:volatile-mutable realized-flag]

  clojure.lang.IDeref

  (deref
    [_]
    (if realized-flag
      cached-value
      ;; Check value-map - use contains? to handle nil values with sentinel
      (if (contains? @(:value-map exec-state) cache-key)
        (let [v (get @(:value-map exec-state) cache-key)
              actual (when-not (= v ::nil-sentinel) v)]
          (set! cached-value actual) (set! realized-flag true) actual)
        ;; Check result-cache
        (if (contains? @(:result-cache exec-state) cache-key)
          (let [v (get @(:result-cache exec-state) cache-key)
                actual (when-not (= v ::nil-sentinel) v)]
            (set! cached-value actual) (set! realized-flag true) actual)
          ;; Not computed yet - throw marker
          (throw-need-computation cache-key @task-atom)))))


  clojure.lang.IPending

  (isRealized
    [_]
    (or realized-flag
        (and exec-state (contains? @(:value-map exec-state) cache-key))
        (and exec-state (contains? @(:result-cache exec-state) cache-key)))))


(defn smart-delay
  [cache-key exec-state task]
  (->SmartDelay cache-key exec-state (atom task) nil false))


(defn smart-delay-realized
  [value]
  (->SmartDelay nil nil nil value true))


;; =============================================================================
;; Graph Helpers (same as before)
;; =============================================================================

(defn- resolve-base-fn
  "Resolves the base-fn (leaf with no parents) for a fn-id.
   With multiple inheritance, walks the FIRST parent at each level.
   Assumes all parent chains in multiple inheritance resolve to the same base-fn."
  [fns fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain exceeds maximum depth"
                    {:type :execution-error/parent-chain-too-deep :fn-id fn-id :max-depth sp/*max-graph-iterations*})))
  (let [fn-rec (get fns fn-id)]
    (when-not fn-rec
      (throw (ex-info "Function not found in execution graph"
                      {:type :execution-error/fn-not-found :fn-id fn-id})))
    (if-let [pid (first (:parent-ids fn-rec))]
      (recur fns pid (inc depth))
      fn-rec)))


(defn- get-fn-args-with-inheritance
  [execution-graph fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain exceeds maximum depth"
                    {:type :execution-error/parent-chain-too-deep :fn-id fn-id :max-depth sp/*max-graph-iterations*})))
  (let [fns (sp/get-graph-fns execution-graph)
        fn-rec (get fns fn-id)
        own-args (sp/graph-get-args execution-graph fn-id)
        parent-ids (:parent-ids fn-rec)]
    (if (seq parent-ids)
      ;; Collect args from all parents, deduplicate by id, then filter by source-id chain
      (let [all-parent-args (into []
                                  (mapcat (fn [pid]
                                            (get-fn-args-with-inheritance
                                              execution-graph pid (inc depth))))
                                  parent-ids)
            dedup-parent-args (->> all-parent-args
                                   (reduce (fn [{:keys [seen result]} a]
                                             (if (contains? seen (:id a))
                                               {:seen seen :result result}
                                               {:seen (conj seen (:id a))
                                                :result (conj result a)}))
                                           {:seen #{} :result []})
                                   :result)
            own-source-ids (into #{} (keep :source-id) own-args)]
        (vec (concat own-args (remove #(own-source-ids (:id %)) dedup-parent-args))))
      (vec own-args))))


(defn- get-fn-data-from-graph
  [execution-graph fn-id]
  (let [fns (:fns execution-graph)
        fn-rec (get fns fn-id)]
    (when-not fn-rec
      (throw (ex-info "Function not found in execution graph"
                      {:type :execution-error/fn-not-found :fn-id fn-id :available-fn-ids (vec (keys fns))})))
    {:fn fn-rec
     :base-fn (resolve-base-fn fns fn-id 0)
     :args (or (get-fn-args-with-inheritance execution-graph fn-id 0) [])}))


;; =============================================================================
;; Arg Propagation
;; =============================================================================

(defn- trace-source-to-fn
  [execution-graph arg-id target-fn-id visited depth]
  (when (and (< depth sp/*max-graph-iterations*) (not (contains? visited arg-id)))
    (when-let [arg (get (:args-by-id execution-graph) arg-id)]
      (if (= (:fn-id arg) target-fn-id)
        {:target-arg-id arg-id :depth depth}
        (when-let [sid (:source-id arg)]
          (recur execution-graph sid target-fn-id (conj visited arg-id) (inc depth)))))))


(defn- collect-propagated-args
  [execution-graph all-caller-args ref-fn-id]
  (reduce
    (fn [acc caller-arg]
      ;; Direct match: if caller-arg belongs directly to the target fn AND has a value/ref
      ;; This handles args like tracked-c.x when executing tracked-c
      ;; We don't include pass-through args (no value, no ref) as direct matches
      ;; because they need to get their value from caller args
      (let [direct-match? (and (= (:fn-id caller-arg) ref-fn-id)
                               (or (some? (:value caller-arg))
                                   (some? (:ref-id caller-arg))))
            trace (cond
                    direct-match? {:target-arg-id (:id caller-arg) :depth 0}
                    :else (trace-source-to-fn execution-graph (:source-id caller-arg) ref-fn-id #{} 0))]
        (if trace
          (let [tid (:target-arg-id trace)
                existing (get acc tid)
                ;; Prefer literal values over refs, then shorter depth
                has-literal? (some? (:value caller-arg))
                existing-has-literal? (some? (:value (:arg existing)))
                replace? (or (nil? existing)
                             ;; New has literal, existing has ref -> prefer literal
                             (and has-literal? (not existing-has-literal?))
                             ;; Both have same type -> prefer shorter depth
                             (and (= has-literal? existing-has-literal?)
                                  (< (:depth trace) (:depth existing))))]
            (if replace? (assoc acc tid {:arg caller-arg :depth (:depth trace)}) acc))
          acc)))
    {} all-caller-args))


;; =============================================================================
;; Limits & Cache
;; =============================================================================

(defn- check-limits!
  [context depth]
  (when (> depth (:max-depth context))
    (throw (ex-info "Maximum recursion depth exceeded"
                    {:type :execution-error/max-depth-exceeded :depth depth :max-depth (:max-depth context)})))
  (let [elapsed (- (ctx/current-time-ms context) (:start-time context))]
    (when (> elapsed (:timeout-ms context))
      (throw (ex-info "Execution timeout exceeded"
                      {:type :execution-error/timeout :elapsed-ms elapsed :timeout-ms (:timeout-ms context)})))))


(defn- check-cache-limit!
  [context]
  (let [rc (:result-cache context)
        size (count @rc)
        max-size (:cache-max-size context)]
    (when (>= size max-size)
      (let [target (long (* max-size 0.8))
            to-remove (take (- size target) (keys @rc))]
        (swap! rc #(reduce dissoc % to-remove))))))


;; =============================================================================
;; Lazy Value Realization
;; =============================================================================

(defn- realize-lazy-value
  [value]
  (cond
    (nil? value) nil
    (instance? clojure.lang.LazySeq value)
    (vec (take config/*max-lazy-seq-size* value))
    (map? value)
    (persistent! (reduce-kv (fn [m k v] (assoc! m k (realize-lazy-value v))) (transient {}) value))
    :else value))


;; =============================================================================
;; Arg Filtering
;; =============================================================================

(defn- fn-in-parent-chain?
  "Returns true if fn-id is reachable from start-fn-id via any parent chain.
   With multiple inheritance, walks all parents in BFS order."
  [fns fn-id start-fn-id]
  (loop [queue (if (nil? start-fn-id) [] [start-fn-id])
         visited #{}
         iter 0]
    (cond
      (or (> iter 1000) (empty? queue)) false
      :else
      (let [curr (first queue)
            rest-q (rest queue)]
        (cond
          (= curr fn-id) true
          (contains? visited curr) (recur rest-q visited (inc iter))
          :else
          (let [pids (:parent-ids (get fns curr))]
            (recur (into (vec rest-q) pids) (conj visited curr) (inc iter))))))))


(defn- arg-belongs-to-current-fn?
  "Determines if an arg should be included in the by-name map for base-fn execution.

   Key insight: args with ref-id or value on the CURRENT fn (not inherited) are bindings
   and must be included regardless of their source chain."
  [execution-graph arg current-fn-id]
  (let [args-by-id (:args-by-id execution-graph)
        fns (:fns execution-graph)
        current-fn-rec (get fns current-fn-id)
        ;; For multiple inheritance, we pick the first parent for parent-chain checks.
        ;; The arg-belongs check is fundamentally about whether this arg flows into
        ;; the base-fn execution, so we use the first parent as the primary chain.
        parent-fn-id (first (:parent-ids current-fn-rec))
        arg-fn-id (:fn-id arg)
        ;; Find the base-fn by walking first parents (leaf with no parents)
        base-fn-id (loop [fid current-fn-id, d 0]
                     (when-not (or (nil? fid) (> d 100))
                       (let [fr (get fns fid)
                             pids (:parent-ids fr)]
                         (if (empty? pids) fid (recur (first pids) (inc d))))))
        ;; Follow source-id chain to find root arg
        root-arg (loop [a arg, d 0]
                   (when-not (> d 100)
                     (if-let [sid (:source-id a)]
                       (if-let [sa (get args-by-id sid)] (recur sa (inc d)) a)
                       a)))
        ;; Check 1: root arg must belong to base-fn
        root-belongs-to-base? (and (some? root-arg)
                                   (some? base-fn-id)
                                   (= (:fn-id root-arg) base-fn-id))]
    (if-not root-belongs-to-base?
      false  ; Fail check 1: root doesn't belong to base-fn
      (cond
        ;; Base-fn arg itself (no source-id)
        (nil? (:source-id arg))
        true

        ;; Own arg (from current fn)
        (= arg-fn-id current-fn-id)
        ;; An arg belongs to current fn's execution if its source is in the direct parent chain
        ;; This is critical for cascades: tracked-triple.item1 binds to pair-1.item1 (NOT in triple's chain)
        ;; so it should NOT be included when executing tracked-triple (which inherits from triple)
        (let [sa (get args-by-id (:source-id arg))
              source-in-parent-chain? (or (nil? sa)
                                          (fn-in-parent-chain? fns (:fn-id sa) parent-fn-id))]
          (and source-in-parent-chain?
               ;; Also need: either arg has binding (value/ref-id) or has a name
               (or (some? (:value arg))
                   (some? (:ref-id arg))
                   (:name arg)
                   (:name sa))))

        ;; Inherited arg (from parent chain)
        ;; Include if bound (has value/ref-id) OR primary (has name)
        :else
        (or (some? (:name arg))
            (some? (:value arg))
            (some? (:ref-id arg)))))))


;; =============================================================================
;; Task & Cache Key
;; =============================================================================

(defrecord ExecutionTask
  [cache-key fn-id provided-args caller-args parent-delays depth])


(declare build-deep-cache-key)


(defn- build-deep-cache-key
  "Builds a cache key that recursively includes propagated args for any ref-id args.
   This ensures that when method-map references assoc-handler, and assoc-handler's
   handler arg is bound differently in different contexts, the cache keys will differ.

   The key insight: if an arg has ref-id (meaning it will execute another fn),
   we need to include what args will propagate TO that ref-fn as part of our key."
  [execution-graph all-caller-args ref-fn-id depth visited]
  (if (or (> depth 10) (contains? visited ref-fn-id))
    ;; Prevent infinite recursion or cycle - just return fn-id
    ref-fn-id
    ;; Normal case
    (let [visited' (conj visited ref-fn-id)
          prop-map (collect-propagated-args execution-graph all-caller-args ref-fn-id)
          ;; For each entry in prop-map, if it has a ref-id (not a literal value),
          ;; recursively compute what would propagate to that ref-fn
          deep-entries
          (into (sorted-map)
                (map (fn [[arg-id entry]]
                       (let [arg (:arg entry)
                             literal-val (:value arg)
                             ref-id (:ref-id arg)
                             is-fn? (:is-fn arg)]
                         (cond
                           ;; Literal value - just use it
                           (some? literal-val)
                           [arg-id [:lit literal-val]]

                           ;; is-fn ref - just use ref-id (function passed as value)
                           (and (some? ref-id) is-fn?)
                           [arg-id [:fn ref-id]]

                           ;; Normal ref-id - recursively get cache key for that fn
                           ;; This is the key fix: we include what args will flow INTO that ref-fn
                           (some? ref-id)
                           (let [;; Get the ref-fn's args to find what will propagate to it
                                 ref-fn-args (or (get-fn-args-with-inheritance execution-graph ref-id 0) [])
                                 extended-args (into (vec all-caller-args) ref-fn-args)
                                 sub-key (build-deep-cache-key execution-graph extended-args ref-id (inc depth) visited')]
                             [arg-id [:ref sub-key]])

                           ;; Pass-through (no value, no ref) - shouldn't be in prop-map but handle anyway
                           :else
                           [arg-id [:nil]]))))
                prop-map)
          result (if (seq deep-entries)
                   [ref-fn-id deep-entries]
                   ref-fn-id)]
      result)))


(defn- build-cache-key
  "Simple wrapper that initiates deep cache key building."
  [execution-graph all-caller-args ref-fn-id]
  (let [result (build-deep-cache-key execution-graph all-caller-args ref-fn-id 0 #{})]
    (log/debug "build-cache-key: ref-fn-id=" ref-fn-id " result=" result)
    result))


(defn- arg-has-value?
  [arg]
  (or (some? (:value arg)) (some? (:ref-id arg))))


;; =============================================================================
;; Build Args with Lazy SmartDelays
;; =============================================================================

(defn- build-arg-delays
  "Builds SmartDelays for all args. Uses two passes:
   1. First pass: create delays for all non-ref-id args (literals, provided values)
   2. Second pass: create lazy delays for ref-id args (can access all sibling delays)"
  [exec-state fn-data provided-args caller-args parent-delays depth]
  (let [context (:context exec-state)
        execution-graph (:execution-graph context)
        args-list (:args fn-data)
        fn-rec (:fn fn-data)
        fn-id (:id fn-rec)
        strict? (:strict-type-validation? context)
        max-unknown (:max-unknown-types context)
        unknown-counter (:unknown-type-counter context)
        all-caller-args (into (vec caller-args) args-list)
        delays-by-name (atom {})
        delays-by-id (atom {})

        ;; Helper to create delay for non-ref-id args
        create-simple-delay
        (fn [arg]
          (let [arg-id (:id arg)
                provided-val (get provided-args arg-id)
                has-stored? (arg-has-value? arg)
                literal-val (:value arg)
                is-fn? (:is-fn arg)]
            (cond
              ;; Provided value or delay
              (and (some? provided-val) (not has-stored?))
              (if (instance? SmartDelay provided-val)
                provided-val
                (do (types/validate-provided-arg-type! provided-val arg strict? max-unknown unknown-counter)
                    (smart-delay-realized provided-val)))

              ;; Literal value
              (some? literal-val)
              (smart-delay-realized
                (if (and is-fn? (string? literal-val))
                  (try (java.util.UUID/fromString literal-val) (catch Exception _ literal-val))
                  literal-val))

              ;; Required missing
              (:required arg)
              (throw (ex-info (str "Required argument '" (:name arg) "' not provided")
                              {:type :execution-error/missing-required-arg :arg-id arg-id :arg-name (:name arg)}))

              ;; Optional nil
              :else (smart-delay-realized nil))))]

    ;; PASS 1: Process all non-ref-id args first
    (doseq [arg args-list
            :when (nil? (:ref-id arg))]
      (let [arg-id (:id arg)
            key-name (keyword (graph/get-root-arg-name execution-graph arg))
            ;; Include if: belongs to current fn OR has a provided value
            include? (or (arg-belongs-to-current-fn? execution-graph arg fn-id)
                         (contains? provided-args arg-id))
            sd (create-simple-delay arg)]
        (when include? (swap! delays-by-name assoc key-name sd))
        (swap! delays-by-id assoc arg-id sd)))

    ;; PASS 2: Process ref-id args (now all sibling delays are available)
    (doseq [arg args-list
            :when (some? (:ref-id arg))]
      (let [arg-id (:id arg)
            key-name (keyword (graph/get-root-arg-name execution-graph arg))
            ;; Include if: belongs to current fn OR has a provided value
            include? (or (arg-belongs-to-current-fn? execution-graph arg fn-id)
                         (contains? provided-args arg-id))
            ref-fn-id (:ref-id arg)
            is-fn? (:is-fn arg)
            provided-val (get provided-args arg-id)
            has-stored? (arg-has-value? arg)

            sd (cond
                 ;; Provided value or delay takes precedence
                 (and (some? provided-val) (not has-stored?))
                 (if (instance? SmartDelay provided-val)
                   provided-val
                   (smart-delay-realized provided-val))

                 ;; is-fn ref - return fn-id directly
                 is-fn?
                 (smart-delay-realized ref-fn-id)

                 ;; Normal ref-id - lazy SmartDelay
                 :else
                 (let [;; Include target fn's args for proper cache key building
                       ref-fn-args (or (get-fn-args-with-inheritance execution-graph ref-fn-id 0) [])
                       ;; Combine all sources for propagation lookup:
                       ;; 1. Caller args from parent chain
                       ;; 2. Current fn's args
                       ;; 3. Target fn's own args (critical for routes with literal values)
                       extended-caller-args (into (vec all-caller-args) ref-fn-args)
                       prop-map (collect-propagated-args execution-graph extended-caller-args ref-fn-id)
                       cache-key (build-cache-key execution-graph extended-caller-args ref-fn-id)
                       cached (or (get @(:value-map exec-state) cache-key)
                                  (get @(:result-cache exec-state) cache-key))]
                   (if (some? cached)
                     (smart-delay-realized cached)
                     ;; Build task for lazy execution - NOW delays-by-id has ALL sibling delays!
                     (let [consumed-ids (set (map (comp :id :arg) (vals prop-map)))
                           remaining (vec (remove (fn [a] (consumed-ids (:id a))) all-caller-args))
                           ;; Include ref-fn-args so child functions can access their values
                           ;; This is critical: when executing favicon-route, its args (with path value)
                           ;; must be available to its children (route → pair → pair-1)
                           task-caller-args (into remaining ref-fn-args)
                           ;; Build prop-delays, using realized delays for args with literal values
                           prop-delays (reduce-kv
                                         (fn [acc tid entry]
                                           (let [caller-arg (:arg entry)
                                                 cid (:id caller-arg)
                                                 ;; Try existing delay first, or create realized delay for literal values
                                                 d (or (get @delays-by-id cid)
                                                       (get parent-delays cid)
                                                       ;; If arg has a literal value, create realized delay
                                                       (when (some? (:value caller-arg))
                                                         (smart-delay-realized (:value caller-arg))))]
                                             (if d (assoc acc tid d) acc)))
                                         {} prop-map)
                           task (->ExecutionTask cache-key ref-fn-id prop-delays
                                                 ;; Accumulate delays so bindings from ancestors propagate
                                                 task-caller-args (merge parent-delays @delays-by-id) (inc depth))]
                       (smart-delay cache-key exec-state task)))))]

        (when include? (swap! delays-by-name assoc key-name sd))
        (swap! delays-by-id assoc arg-id sd)))

    {:by-name @delays-by-name :by-id @delays-by-id}))


;; =============================================================================
;; Execute Single Task (may throw NeedComputation)
;; =============================================================================

(defn- execute-task-once
  "Attempts to execute task. May throw NeedComputation if a lazy arg is dereferenced."
  [^ExecutionTask task ^ExecutionState exec-state]
  (let [context (:context exec-state)
        cache-key (:cache-key task)
        fn-id (:fn-id task)
        execution-graph (:execution-graph context)
        fn-data (get-fn-data-from-graph execution-graph fn-id)
        base-fn (:base-fn fn-data)
        fn-name (keyword (:name base-fn))
        base-fn-impl (get (:base-fns context) fn-name)]

    (check-limits! context (:depth task))
    (when-not base-fn-impl
      (throw (ex-info (str "Base function '" (name fn-name) "' not found in registry")
                      {:type :execution-error/base-fn-not-found :fn-name fn-name :registry-size (count (:base-fns context))})))

    (let [{:keys [by-name]} (build-arg-delays exec-state fn-data (:provided-args task)
                                              (:caller-args task) (:parent-delays task) (:depth task))
          ;; Execute - may throw NeedComputation when @delay is called
          result (base-fn-impl by-name (assoc context :depth (:depth task)))
          realized (realize-lazy-value result)]
      ;; Store result using sentinel for nil values
      (swap! (:value-map exec-state) assoc cache-key (if (nil? realized) ::nil-sentinel realized))
      (when (not= cache-key :root)
        (check-cache-limit! context)
        (swap! (:result-cache exec-state) assoc cache-key (if (nil? realized) ::nil-sentinel realized)))
      realized)))


;; =============================================================================
;; Main Trampoline Loop
;; =============================================================================

(defn- try-execute-task
  "Tries to execute a task. Returns {:done result} or {:need task}."
  [task exec-state]
  (try
    {:done (execute-task-once task exec-state)}
    (catch clojure.lang.ExceptionInfo e
      (if (need-computation-ex? e)
        {:need {:current task :dependency (:task (ex-data e))}}
        (throw e)))))


(defn execute-with-queue
  "Executes with trampolined lazy evaluation.
   When a lazy SmartDelay is dereferenced, it throws NeedComputation.
   We catch it, execute the needed task first, then retry."
  [context fn-id args]
  (let [exec-state (->ExecutionState (atom {}) (:result-cache context) (atom {}) context)
        root-task (->ExecutionTask :root fn-id args [] {} 0)
        ;; Stack of tasks to retry after dependencies complete
        retry-stack (ArrayDeque.)]

    (ArrayDeque/.push retry-stack root-task)

    (loop [iterations 0]
      (when (> iterations 100000)
        (throw (ex-info "Too many iterations" {:type :execution-error/infinite-loop})))

      (if (ArrayDeque/.isEmpty retry-stack)
        ;; Done
        (get @(:value-map exec-state) :root)

        (let [task (ArrayDeque/.pop retry-stack)
              result (try-execute-task task exec-state)]
          (if (:done result)
            (recur (inc iterations))
            ;; Need computation - push tasks back
            (let [{:keys [current dependency]} (:need result)]
              (ArrayDeque/.push retry-stack current)
              (ArrayDeque/.push retry-stack dependency)
              (recur (inc iterations)))))))))
