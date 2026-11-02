(ns kaocha.plugin.parallel-progress-test
  (:require
    [clojure.test :refer [deftest is]]
    [kaocha.plugin.parallel :as parallel]))


(defn- var-plan
  [id]
  {:kaocha.testable/type :kaocha.type/var
   :kaocha.testable/id id})


(deftest integration-progress-counts-planned-and-completed-vars
  (let [plan {:kaocha.testable/type :kaocha.type/ns
              :kaocha.test-plan/tests [(var-plan :first)
                                       (var-plan :second)
                                       (assoc (var-plan :excluded)
                                              :kaocha.testable/skip true)]}
        result {:kaocha.testable/type :kaocha.type/ns
                :kaocha.result/tests [(assoc (var-plan :first)
                                             :kaocha.result/count 1
                                             :kaocha.result/pass 2)
                                      (assoc (var-plan :second)
                                             :kaocha.result/count 1
                                             :kaocha.result/fail 1)
                                      (var-plan :not-run)]}]
    (is (= 2 (#'parallel/planned-var-count plan)))
    (is (= {:passed 1 :failed 1}
           (#'parallel/completed-var-counts result)))))
