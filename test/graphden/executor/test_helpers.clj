(ns graphden.executor.test-helpers
  "Test utilities for executor component.

   Provides:
   - MockStorage: Minimal storage implementation for unit tests
   - create-test-context: Create execution context with sensible test defaults
   - with-test-context: Macro for context lifecycle management"
  (:require
    [graphden.executor.context :as ctx]
    [graphden.storage.protocol.core :as sp]))


;; === Mock Storage ===
;;
;; Minimal implementation that satisfies ExecutionGraph protocol.
;; Used for unit tests that don't need actual database operations.

(defrecord MockStorage
  [execution-graph-atom]

  sp/ExecutionGraph

  (resolve-execution-graph
    [_this _fn-id]
    (when execution-graph-atom
      @execution-graph-atom)))


(defn create-mock-storage
  "Creates a mock storage for testing.

   Options:
   - :execution-graph - Atom containing the execution graph to return
                        from resolve-execution-graph (optional)"
  ([]
   (->MockStorage nil))
  ([{:keys [execution-graph]}]
   (->MockStorage execution-graph)))


;; === Test Context ===
;;
;; Simplified context creation with sensible test defaults.
;; Reduces boilerplate in tests while keeping full control when needed.

(def default-test-timeout-ms
  "Default timeout for tests. Short timeout (100ms) catches infinite loops quickly."
  100)


(def default-test-max-depth
  "Default max depth for tests. Low depth (100) catches runaway recursion quickly."
  100)


(defn create-test-context
  "Creates an execution context with sensible test defaults.

   This helper reduces boilerplate when writing executor tests:
   - Uses MockStorage by default (no database needed)
   - Short timeout (100ms) to catch infinite loops quickly
   - Low max-depth (100) to catch runaway recursion
   - Controllable clock for deterministic timeout testing

   Options (all optional):
   - :storage - Storage instance (default: MockStorage)
   - :execution-graph - Execution graph for MockStorage
   - :base-fns - Map of base functions
   - :timeout-ms - Timeout in ms (default: 100ms)
   - :max-depth - Max recursion depth (default: 100)
   - :strict-type-validation? - Type validation strictness (default: true)
   - :max-unknown-types - Unknown type limit (default: 10)
   - :clock - Clock function for time (default: constant 0)
   - :cache-warning-threshold - Cache warning threshold
   - :cache-max-size - Maximum cache size

   Examples:
     ;; Minimal context for type validation tests
     (create-test-context)

     ;; Context with custom base functions
     (create-test-context {:base-fns {:add add-impl}})

     ;; Context with predetermined execution graph
     (create-test-context {:execution-graph (atom my-graph)})

     ;; Context with controllable clock for timeout testing
     (let [time-atom (atom 0)]
       (create-test-context {:clock #(deref time-atom)}))"
  ([]
   (create-test-context {}))
  ([{:keys [storage execution-graph base-fns timeout-ms max-depth
            strict-type-validation? max-unknown-types clock
            cache-warning-threshold cache-max-size]
     :or {timeout-ms default-test-timeout-ms
          max-depth default-test-max-depth
          strict-type-validation? true
          max-unknown-types 10}}]
   (let [test-storage (or storage
                          (create-mock-storage
                            (when execution-graph
                              {:execution-graph execution-graph})))
         ;; Default clock that always returns 0 - tests run "instantly"
         ;; unless they explicitly advance time
         test-clock (or clock (constantly 0))]
     (ctx/create-context
       (cond-> {:storage test-storage
                :timeout-ms timeout-ms
                :max-depth max-depth
                :strict-type-validation? strict-type-validation?
                :max-unknown-types max-unknown-types
                :clock test-clock}
         base-fns (assoc :base-fns base-fns)
         cache-warning-threshold (assoc :cache-warning-threshold cache-warning-threshold)
         cache-max-size (assoc :cache-max-size cache-max-size))))))


(defmacro with-test-context
  "Executes body with a test context bound to sym.

   Options are passed to create-test-context.

   Example:
     (with-test-context [ctx {:base-fns {:add add-fn}}]
       (execute ctx some-fn-id nil))"
  [[sym opts] & body]
  `(let [~sym (create-test-context ~opts)]
     ~@body))


;; === Controllable Clock ===
;;
;; Utilities for deterministic timeout testing.

(defn create-controllable-clock
  "Creates a controllable clock for timeout testing.

   Returns a map with:
   - :clock - Function to use as context clock
   - :time-atom - Atom holding current time value
   - :advance! - Function to advance time by ms

   Example:
     (let [{:keys [clock time-atom advance!]} (create-controllable-clock)
           ctx (create-test-context {:clock clock :timeout-ms 100})]
       ;; Initially at time 0
       (is (= 0 @time-atom))
       ;; Advance time by 50ms
       (advance! 50)
       (is (= 50 @time-atom))
       ;; Advance past timeout
       (advance! 60)
       ;; Now execution would timeout)"
  []
  (let [time-atom (atom 0)]
    {:clock #(deref time-atom)
     :time-atom time-atom
     :advance! (fn [ms] (swap! time-atom + ms))}))
