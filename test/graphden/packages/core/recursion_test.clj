(ns ^:integration graphden.packages.core.recursion-test
  "End-to-end tests for the `:fix` graph-level recursion primitive.

   Verifies that `:fix` synthesises a self-referential callable that
   `:step` can re-enter via `:invoke :func :self :arg <next>` —
   without ever introducing a structural cycle in the fn-graph.
   Factorial is the canonical demo; a separate test exercises the
   `*max-recursion-depth*` guard.

   Setup mirrors `refinements-test`: the full integrant `:dev` system
   runs against the test container so the executor sees a
   VersionedStorage-wrapped backend — same shape production runs
   under, and the only shape under which `binding-list-item` reads
   return their `:value` / `:ref-fn-id` payload (those columns live
   in the version tables; raw-postgres reads return nil-valued
   rows)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*context* (:ctx graph)
                     *storage* (:storage graph)]
             (t))
           (finally (sp/close (:storage graph))))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn- sync!
  [fn-defs]
  ;; Delta-invalidate on just the synced fns (setup helper) rather than a full
  ;; 1-arity clear — a full clear recompiled the whole golden [core web app]
  ;; registry (~30s) on the next execute and dominated this test's runtime.
  (setup/sync-and-invalidate! *context* *storage* fn-defs))


(defn- sync-factorial!
  "Sync the factorial fn-graph against the test storage. Mirrors
   `examples/recursion/fns.edn` so the example file + this test stay
   in lock-step on the canonical `:fix` shape."
  []
  (sync!
    [{:name :ex-factorial
      :parent :fix
      :args {:step :_ex-fact-step
             :input {:as :n}}}

     {:name :_ex-fact-step
      :parent :if
      :args {:test :_ex-fact-base-case?
             :then {:value 1}
             :else :_ex-fact-recurse}}

     {:name :_ex-fact-base-case?
      :parent :equal?
      :args {:a {:as :input} :b {:value 0}}}

     {:name :_ex-fact-recurse
      :parent :mul
      :args {:nums [{:as :input} :_ex-fact-self-call]}}

     {:name :_ex-fact-self-call
      :parent :invoke
      :args {:func {:as :self}
             :arg :_ex-fact-n-minus-1}}

     {:name :_ex-fact-n-minus-1
      :parent :sub
      :args {:nums [{:as :input} {:value 1}]}}]))


(deftest factorial-via-fix-end-to-end
  (testing ":fix-based factorial returns n! for representative inputs"
    (sync-factorial!)
    (let [id (fn-id "ex-factorial")]
      (is (= 1   (exec/execute *context* id {:n 0})) "0! = 1 (base case)")
      (is (= 1   (exec/execute *context* id {:n 1})) "1! = 1")
      (is (= 2   (exec/execute *context* id {:n 2})) "2! = 2 (1 recursion)")
      (is (= 120 (exec/execute *context* id {:n 5})) "5! = 120 (5 recursions)")
      (is (= 3628800 (exec/execute *context* id {:n 10})) "10! = 3628800"))))


(deftest fix-depth-bound-throws-recursion-error
  (testing "*max-recursion-depth* trips on runaway recursion instead of stack overflow"
    (sync-factorial!)
    (let [id (fn-id "ex-factorial")]
      ;; Drive recursion past *max-recursion-depth* with negative input
      ;; (the base case is :input == 0; negatives never reach it).
      (binding [config/*max-recursion-depth* 50]
        (let [thrown (try
                       (exec/execute *context* id {:n -1})
                       ::no-throw
                       (catch clojure.lang.ExceptionInfo e
                         (ex-data e)))]
          (is (= :recursion-error/max-depth-exceeded (:type thrown))
              "runaway recursion hits the depth guard, not a JVM stack overflow"))))))
