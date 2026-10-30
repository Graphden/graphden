(ns ^:serial kaocha.plugin.parallel-fail-fast-test
  (:require
    [clojure.test :refer [deftest is]]
    [kaocha.result :as result]
    [kaocha.testable :as testable]))


(defn- run-synthetic
  [failed-id]
  (require 'kaocha.plugin.parallel)
  (let [started (atom [])
        tests (mapv (fn [id] {:id id}) (range 8))
        run-one-var (ns-resolve 'kaocha.plugin.parallel 'run-one)
        scheduler-var (ns-resolve 'kaocha.plugin.parallel 'run-testables-parallel)
        run-one (fn [test _ _]
                  (swap! started conj (:id test))
                  (when (= 1 (:id test)) (Thread/sleep 40))
                  (assoc test :failed? (= failed-id (:id test))))
        outputs (with-redefs-fn {run-one-var run-one
                                 #'result/failed? :failed?}
                  #(binding [testable/*fail-fast?* true]
                     (scheduler-var tests nil 2 false)))]
    {:started @started :outputs outputs}))


(deftest parallel-fail-fast-admits-only-in-flight-namespaces
  (let [{:keys [started outputs]} (run-synthetic 0)]
    (is (= #{0 1} (set started)))
    (is (= true (:failed? (first outputs))))
    (is (every? :kaocha.testable/skip (drop 2 outputs)))))


(deftest parallel-fail-fast-still-runs-the-complete-green-roster
  (let [{:keys [started outputs]} (run-synthetic nil)]
    (is (= (set (range 8)) (set started)))
    (is (not-any? :kaocha.testable/skip outputs))))
