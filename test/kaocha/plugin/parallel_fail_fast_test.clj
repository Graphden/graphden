(ns ^:serial kaocha.plugin.parallel-fail-fast-test
  (:require
    [clojure.test :refer [deftest is]]
    [kaocha.plugin.priority :as priority]
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
    (is (true? (:failed? (first outputs))))
    (is (every? :kaocha.testable/skip (drop 2 outputs)))))


(deftest parallel-fail-fast-still-runs-the-complete-green-roster
  (let [{:keys [started outputs]} (run-synthetic nil)]
    (is (= (set (range 8)) (set started)))
    (is (not-any? :kaocha.testable/skip outputs))))


(defn- run-manual-cohort
  [failed-ids n]
  (require 'kaocha.plugin.parallel)
  (let [started (atom [])
        ids [:manual-serial :manual-first :manual-second :manual-last :automatic :tail]
        tests (mapv (fn [id] {:kaocha.testable/id id}) ids)
        manual (mapv name (take 4 ids))
        encoded (pr-str {:unit-manual manual :unit-layers [manual ["automatic"]]})
        layers (priority/namespace-layers encoded :unit)
        run-one-var (ns-resolve 'kaocha.plugin.parallel 'run-one)
        scheduler-var (ns-resolve 'kaocha.plugin.parallel 'run-children-layered)
        serial-var (ns-resolve 'kaocha.plugin.parallel 'ns-serial?)
        run-one (fn [test _ _]
                  (let [id (:kaocha.testable/id test)]
                    (swap! started conj [id testable/*fail-fast?*])
                    (assoc test :failed? (contains? failed-ids id))))
        outputs (with-redefs-fn {run-one-var run-one
                                 serial-var #(= :manual-serial (:kaocha.testable/id %))
                                 #'result/failed? :failed?}
                  #(binding [testable/*fail-fast?* true]
                     (scheduler-var tests nil n
                                    layers :unit
                                    (priority/manual-unit-layer? encoded layers))))]
    {:started @started :outputs outputs :ids ids}))


(deftest manual-unit-cohort-collects-independent-failures-before-stopping-tail
  (doseq [n [1 2]]
    (let [{:keys [started outputs ids]}
          (run-manual-cohort #{:manual-serial :manual-first} n)]
      (is (= (set (take 4 ids)) (set (map first started))))
      (is (every? false? (map second started)))
      (is (= #{:manual-serial :manual-first}
             (set (map :kaocha.testable/id (filter :failed? outputs)))))
      (is (every? :kaocha.testable/skip (drop 4 outputs)))
      (is (= ids (mapv :kaocha.testable/id outputs))))))


(deftest green-manual-unit-cohort-restores-fail-fast-and-full-roster
  (let [{:keys [started outputs ids]} (run-manual-cohort #{} 2)]
    (is (= (frequencies ids) (frequencies (map first started))))
    (is (= #{[:automatic true] [:tail true]}
           (set (filter second started))))
    (is (= ids (mapv :kaocha.testable/id outputs)))
    (is (not-any? :kaocha.testable/skip outputs))))
