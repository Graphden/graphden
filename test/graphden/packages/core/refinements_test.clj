(ns ^:integration graphden.packages.core.refinements-test
  "Behavioural tests for the runtime `:ensure-*` narrowers shipped by
   `core.refinements`. Each one validates a single constraint at
   execute time and either returns its input (narrowed to the refined
   type from the type system's view) or throws `:refinement/violated`
   with the constraint that failed.

   The `:ensure-*` narrowers are now graph fn-defs composing the
   shared `:_refinement-narrow` template + per-refinement `:test`,
   `:refine-name`, and `:constraint` bindings — no defbase impls
   live in `impls.clj` anymore. So these tests drive the fn-defs
   through the executor against a real package-synced graph (same
   fixture as `compile-packages-test`)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
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


(defn- call
  "Invoke `:ensure-X` with `:value` = v through the executor; returns
   the value on pass or rethrows the original ExceptionInfo on fail."
  [ensure-name v]
  (exec/execute *context* (fn-id ensure-name) {:value v}))


(defn- ex-of
  "Return the ex-data :type tag thrown by calling `ensure-name` with
   `v`, or `:no-throw` if it returned cleanly."
  [ensure-name v]
  (try (call ensure-name v) :no-throw
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


;; -----------------------------------------------------------------------------
;; :ensure-positive-int — the surviving narrower (the others were
;; dropped as unreachable production code, see commit message of the
;; cleanup).

(deftest ensure-positive-int-passes-and-rejects
  (testing "positive ints pass through"
    (is (= 1   (call "ensure-positive-int" 1)))
    (is (= 42  (call "ensure-positive-int" 42))))
  (testing "0, negative, and non-int reject"
    (is (= :refinement/violated (ex-of "ensure-positive-int" 0)))
    (is (= :refinement/violated (ex-of "ensure-positive-int" -1)))
    (is (= :refinement/violated (ex-of "ensure-positive-int" 1.5)))))


(deftest ensure-positive-int-ex-data-shape
  (testing "violation names :positive-int and the [:> 0] constraint"
    (let [ex (try (call "ensure-positive-int" -5)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :refinement/violated (:type (ex-data ex))))
      (is (= :positive-int        (:refine-name (ex-data ex))))
      (is (= [:> 0]               (:constraint (ex-data ex))))
      (is (= -5                   (:value (ex-data ex)))))))


;; -----------------------------------------------------------------------------
;; :ensure-non-empty-text — also still reachable in production (per
;; reachability audit).

(deftest ensure-non-empty-text-passes-and-rejects
  (testing "any non-empty string passes (including whitespace-only)"
    (is (= "hi"     (call "ensure-non-empty-text" "hi")))
    (is (= "   "    (call "ensure-non-empty-text" "   "))))
  (testing "empty string and non-strings reject"
    (is (= :refinement/violated (ex-of "ensure-non-empty-text" "")))
    (is (= :refinement/violated (ex-of "ensure-non-empty-text" nil)))
    (is (= :refinement/violated (ex-of "ensure-non-empty-text" 42)))))
