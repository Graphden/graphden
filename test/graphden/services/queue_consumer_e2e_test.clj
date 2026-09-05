(ns ^:integration graphden.services.queue-consumer-e2e-test
  "The queue end to end through the graph: a `:pg-queue-consumer`
   service started by the REAL reconciler drains what `:queue-publish`
   put on its queue, acking each message a handler returns from and
   nacking the one it throws on — retry counted, error kept."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.wait :as wait]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (t)))
  (impls/impls-fixture "storage" "queue"))


(deftest consumer-drains-acks-and-nacks-test
  (let [{:keys [ctx storage]} *bootstrap*
        _ (setup/sync-and-invalidate!
            ctx storage
            [;; The handler parses the payload as JSON: a JSON string returns
             ;; (→ ack), anything else throws (→ nack). The message arrives
             ;; under `message`; `:to-str` keeps the slot type honest.
             {:name :_qe-payload-text :parent :to-str
              :args {:value {:parent :get :args {:coll {:as :message} :key {:value :payload} :default nil}}}}
             {:name :qe-handle :parent :parse-json
              :args {:string :_qe-payload-text :keywordize true}}
             ;; A fast-failing nack so the test sees the retry at once.
             {:name :_qe-nack :parent :queue-nack
              :args {:retry-ms 0 :max-attempts 2}}
             {:name :_qe-take :parent :queue-take
              :args {:batch 10 :visibility-ms 30000 :wait-ms 300}}
             {:name :qe-worker :parent :pg-queue-consumer
              :args {:queue "qe-orders" :handler :qe-handle
                     :take :_qe-take :nack :_qe-nack}}])
        worker-id (records/fn-id nil :qe-worker)
        svc (sp/create-entity storage :service
                              {:fn-id worker-id :enabled? true
                               :restart-policy :always :cardinality :singleton})
        running (atom {})
        publish! (fn [payload]
                   ((impls/impl-of :queue-publish)
                    {:queue "qe-orders" :payload payload :delay-ms 0} ctx))
        rows (fn [] (sp/query-entities storage :queue-message {:queue "qe-orders"}))]
    (try
      (let [good (mapv #(publish! (str "{\"order\":" % "}")) [1 2 3])
            bad (publish! "not json at all")]
        (recon/reconcile-once! ctx running)
        (testing "the consumer is running with an instance row"
          (is (map? (get @running (:id svc))))
          (is (= 1 (count (sp/query-entities storage :service-instance {:service-id (:id svc)})))))
        (testing "handled messages are acked away"
          (wait/wait-for 10000 #(every? (fn [id] (nil? (sp/read-entity storage :queue-message id))) good))
          (is (every? #(nil? (sp/read-entity storage :queue-message %)) good)))
        (testing "the throwing one is retried, then dead-lettered with the error"
          (wait/wait-for 10000 #(= "dead" (:state (sp/read-entity storage :queue-message bad))))
          (let [row (sp/read-entity storage :queue-message bad)]
            (is (= "dead" (:state row)))
            (is (= 2 (:attempts row)))
            (is (some? (:error row))))
          (is (= [bad] (mapv :id (rows))) "only the dead letter remains")))
      (finally
        (recon/stop-all! running ctx)
        (doseq [r (rows)] (sp/delete-entity storage :queue-message (:id r)))
        (sp/delete-entity storage :service (:id svc))))))
