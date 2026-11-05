(ns ^:integration ^:serial graphden.crud.test-api-policy-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.test-runs :as runs]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.test-api :as api]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "test-api-policy" ["core" "web" "app" "examples"])]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(deftest graph-selection-and-summary-test
  (let [a (random-uuid) b (random-uuid) c (random-uuid)
        rows [{:id a :name "a" :namespace-id nil :platform? false}
              {:id b :name "b" :namespace-id nil :platform? true}
              {:id c :name "c" :namespace-id nil :platform? false}]
        calls (atom [])]
    (with-redefs [runs/test-facts (fn [_] rows)
                  runs/run-selected-tests!
                  (fn [_ ids timeout]
                    (swap! calls conj [ids timeout])
                    (mapv (fn [id] {:fn-id (str id) :status :succeeded}) ids))]
      (doseq [[opts expected] [[{} [a c]]
                               [{:fn-ids []} [a c]]
                               [{:fn-ids ["invalid"]} []]
                               [{:fn-ids [(str b) (str b)]} [b]]
                               [{:platform? true} [a b c]]
                               [{:fn-ids [(str c) (str a)] :timeout-ms 73} [a c]]]]
        (reset! calls [])
        (let [out (api/run-tests! (:ctx *graph*) opts)]
          (is (= [[expected (:timeout-ms opts)]] @calls)
              "selection preserves discovered order and invokes one native batch")
          (is (= {:total (count expected) :passed (count expected) :failed 0 :other 0}
                 (dissoc out :results))))))
    (testing "all nonterminal/non-runnable statuses belong to other"
      (let [results (mapv #(hash-map :status %) [:succeeded :failed :pending :rejected :not-runnable])]
        (with-redefs [runs/test-facts (fn [_] rows)
                      runs/run-selected-tests! (fn [_ _ _] results)]
          (is (= {:total 5 :passed 1 :failed 1 :other 3 :results results}
                 (api/run-tests! (:ctx *graph*) {}))))))))


(deftest graph-status-display-join-test
  (let [a (random-uuid) b (random-uuid) ns-id (random-uuid) calls (atom 0)
        status {:status "failed" :execution-id "execution" :error "assertion"}]
    (with-redefs [runs/status-facts
                  (fn [_]
                    (swap! calls inc)
                    {:tests [{:id a :name "a" :namespace-id ns-id :platform? true}
                             {:id b :name "b" :namespace-id nil :platform? false}]
                     :statuses {a status}})]
      (is (= [(merge {:fn-id (str a) :fn-name "a" :namespace-id (str ns-id) :platform? true} status)
              {:fn-id (str b) :fn-name "b" :namespace-id nil :platform? false}]
             (api/tests-with-statuses (:ctx *graph*))))
      (is (= 1 @calls) "identities and statuses come from one scoped batch"))))
