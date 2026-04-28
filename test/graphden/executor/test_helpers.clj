(ns graphden.executor.test-helpers
  "Test utilities for executor component.

   Provides:
   - MockStorage: minimal storage implementation for unit tests
   - create-test-context: execute-context with sensible test defaults
   - with-test-context: macro for context lifecycle management
   - create-controllable-clock: deterministic clock for time tests"
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

(defn create-test-context
  "Creates an execution context for tests.

   Options:
   - :storage          Storage instance (default: MockStorage).
   - :execution-graph  Execution graph atom for MockStorage.
   - :base-fns         Map of base function name → impl.
   - :clock            Zero-arg fn returning current time in ms
                       (default: constant 0 — tests tick instantly unless
                       they explicitly advance the clock)."
  ([]
   (create-test-context {}))
  ([{:keys [storage execution-graph base-fns clock]}]
   (let [test-storage (or storage
                          (create-mock-storage
                            (when execution-graph
                              {:execution-graph execution-graph})))
         test-clock (or clock (constantly 0))]
     (ctx/create-context
       (cond-> {:storage test-storage :clock test-clock}
         base-fns (assoc :base-fns base-fns))))))


(defmacro with-test-context
  "Executes body with a test context bound to sym.

   Example:
     (with-test-context [ctx {:base-fns {:add add-fn}}]
       (execute ctx some-fn-id nil))"
  [[sym opts] & body]
  `(let [~sym (create-test-context ~opts)]
     ~@body))


;; === Controllable Clock ===

(defn create-controllable-clock
  "Creates a controllable clock for deterministic time-based tests.

   Returns `{:clock :time-atom :advance!}` — plug `:clock` into a
   context, read/reset `:time-atom` directly, or call `:advance!` to
   bump the current time by a number of ms."
  []
  (let [time-atom (atom 0)]
    {:clock #(deref time-atom)
     :time-atom time-atom
     :advance! (fn [ms] (swap! time-atom + ms))}))
