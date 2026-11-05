(ns ^:integration ^:serial graphden.crud.recent-failure-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.errors :as errors]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!* "recent-failure-graph")]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(defn- display
  [limit]
  (exec/execute-with-named-args (:ctx *graph*) (ids/fn-id "app.execution" :recent-failures)
                                {:days 7 :limit limit}))


(deftest scoped-facts-are-projected-capped-and-formatted-test
  (let [fid (random-uuid) eid (random-uuid)
        instant (java.time.Instant/parse "2026-11-05T12:00:00Z")
        calls (atom [])
        row {:execution-id eid :fn-id fid :fn-name nil :finished-at instant
             :error "redacted" :error-data {:safe true} :internal-field "must not escape"}]
    (with-redefs [errors/recent-unresolved-failure-facts
                  (fn [_ _ _ days limit]
                    (swap! calls conj [days limit])
                    [row (assoc row :fn-name "second" :finished-at nil)])]
      (is (= [{:execution-id eid :fn-id fid :fn-name (str fid)
               :finished-at "2026-11-05T12:00:00Z" :error "redacted" :error-data {:safe true}}]
             (display 1)))
      (is (= [[7 1]] @calls))
      (is (= "second" (:fn-name (second (display 2)))))
      (is (nil? (:finished-at (second (display 2)))))
      (is (= [] (display 0))))))


(deftest absent-pool-has-an-empty-public-result-test
  (with-redefs [errors/recent-unresolved-failure-facts (fn [& _] nil)]
    (is (= [] (display 10)))))


(deftest trace-name-fallback-preserves-all-safe-facts-test
  (let [fid (str (random-uuid))
        row {:seq 3 :fn-id fid :fn-name nil :depth 2 :cache-hit? false
             :duration-ms 4 :hidden "secret" :has-value? true :value nil}
        payload {:found? true :rows [row (assoc row :fn-name "named")]
                 :truncated? true :values-dropped? true}
        calls (atom 0)]
    (with-redefs [fn-exec/trace-read-facts
                  (fn [_ _] (swap! calls inc) payload)]
      (let [result (exec/execute-with-named-args
                     (:ctx *graph*) (ids/fn-id "app.execution" :execute-trace-rows)
                     {:id (random-uuid)})]
        (is (= (assoc payload :rows [(assoc row :fn-name (str (subs fid 0 8) "…"))
                                     (assoc row :fn-name "named")]) result))
        (is (= 1 @calls))))
    (with-redefs [fn-exec/trace-read-facts (fn [_ _] {:found? false :rows []})]
      (is (= {:found? false :rows []}
             (exec/execute-with-named-args
               (:ctx *graph*) (ids/fn-id "app.execution" :execute-trace-rows) {:id nil}))))))
