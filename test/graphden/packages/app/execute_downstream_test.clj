(ns ^:integration graphden.packages.app.execute-downstream-test
  "The result partial's *Downstream calls* pane — the graph fn-defs that
   render a run's cross-service `:children` (docs/EXECUTION.md § Tracing
   across services): a list with each child's fn name and status, or a
   hidden span when the run called into nothing."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (t))))


(defn- render
  [exec-row]
  (let [{:keys [ctx all-name->id]} *bootstrap*]
    (pr-str (exec/execute-with-named-args ctx (get all-name->id :_er-downstream)
                                          {:exec exec-row}))))


(deftest downstream-pane-test
  (testing "children render as a titled list of fn name + status"
    (let [h (render {:id (random-uuid) :status :succeeded
                     :children [{:id (random-uuid) :fn-name "_orders-ring" :status :succeeded}
                                {:id (random-uuid) :fn-name "_audit" :status :failed}]})]
      (is (str/includes? h "Downstream calls"))
      (is (str/includes? h "_orders-ring"))
      (is (str/includes? h "succeeded"))
      (is (str/includes? h "_audit"))
      (is (str/includes? h "failed"))
      (is (str/includes? h "execute-downstream-pane"))))
  (testing "no children → a hidden span, not an empty pane"
    (let [h (render {:id (random-uuid) :status :succeeded :children []})]
      (is (not (str/includes? h "Downstream calls")))
      (is (str/includes? h "hidden")))
    (let [h (render {:id (random-uuid) :status :succeeded})]
      (is (not (str/includes? h "Downstream calls"))))))
