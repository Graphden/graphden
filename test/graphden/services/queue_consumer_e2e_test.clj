(ns ^:integration graphden.services.queue-consumer-e2e-test
  "The queue end to end through the graph: a `:pg-queue-consumer`
   service started by the REAL reconciler drains what `:queue-publish`
   put on its queue, acking each message a handler returns from and
   nacking the one it throws on — retry counted, error kept."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-runtime :as cr]
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
          (is (= [bad] (mapv :id (rows))) "only the dead letter remains"))
        (testing "a message published under a persisted run is handled as a traced hop:
                  the handler's execution is a child of the publisher's"
          (let [parent (persist/create-pending-row!
                         storage (lookup/resolve-fn-version-id ctx worker-id) [] nil nil)
                parent-id (:id parent)
                traced (binding [cr/*execution* {:id parent-id :trace-id parent-id}]
                         (publish! "{\"order\":42}"))]
            (wait/wait-for 10000 #(nil? (sp/read-entity storage :queue-message traced)))
            (wait/wait-for 10000 #(seq (:children (fn-exec/get-execution ctx parent-id))))
            (let [children (:children (fn-exec/get-execution ctx parent-id))
                  child-row (some->> children first :id (sp/read-entity storage :fn-execution))]
              (is (= 1 (count children)))
              (is (= "qe-handle" (:fn-name (first children))) "the consumer's handler fn")
              (is (= :succeeded (:status (first children))))
              (is (= parent-id (:trace-id child-row)))
              (is (= parent-id (:parent-execution-id child-row)))
              (is (= {:order 42} (:result child-row)))
              (let [args (:args (fn-exec/get-execution ctx (:id child-row)))]
                (is (= 1 (count args)) "one persisted arg — the handler's `message`")
                (is (= (str traced) (str (get-in (first args) [:value :id])))
                    "the message is the persisted argument"))))
          (testing "an untraced publish persists nothing"
            (let [n (count (sp/query-entities storage :fn-execution {}))
                  id (publish! "{\"order\":43}")]
              (wait/wait-for 10000 #(nil? (sp/read-entity storage :queue-message id)))
              (is (= n (count (sp/query-entities storage :fn-execution {}))))))))
      (finally
        (recon/stop-all! running ctx)
        (doseq [r (rows)] (sp/delete-entity storage :queue-message (:id r)))
        (sp/delete-entity storage :service (:id svc))))))


(deftest slow-handler-keeps-its-claim-test
  ;; A handler slower than the visibility timeout used to be re-delivered
  ;; to another taker mid-flight (at-least-once, but twice for nothing).
  ;; The consumer now renews the claim every `:lease-every-ms` while the
  ;; handler runs, so the message is handled once and acked.
  (let [{:keys [ctx storage]} *bootstrap*
        _ (setup/sync-and-invalidate!
            ctx storage
            [{:name :_qs-payload :parent :get
              :args {:coll {:as :message} :key {:value :payload} :default nil}}
             {:name :_qs-report :parent :queue-publish
              :args {:queue "qs-done" :payload :_qs-payload}}
             ;; 1.5 s of work against a 600 ms claim.
             {:name :qs-slow-handle :parent :do
              :args {:steps [{:parent :sleep :args {:ms 1500}} :_qs-report]}}
             {:name :_qs-take :parent :queue-take
              :args {:batch 1 :visibility-ms 600 :wait-ms 200}}
             {:name :_qs-extend :parent :queue-extend
              :args {:visibility-ms 600}}
             {:name :qs-worker :parent :pg-queue-consumer
              :args {:queue "qs-orders" :handler :qs-slow-handle
                     :take :_qs-take :extend :_qs-extend :lease-every-ms 200}}])
        worker-id (records/fn-id nil :qs-worker)
        svc (sp/create-entity storage :service
                              {:fn-id worker-id :enabled? true
                               :restart-policy :always :cardinality :singleton})
        running (atom {})
        done (fn [] (sp/query-entities storage :queue-message {:queue "qs-done"}))]
    (try
      (let [id ((impls/impl-of :queue-publish)
                {:queue "qs-orders" :payload {:n 1} :delay-ms 0} ctx)]
        (recon/reconcile-once! ctx running)
        (wait/wait-for 15000 #(nil? (sp/read-entity storage :queue-message id)))
        (Thread/sleep 1500)
        (is (nil? (sp/read-entity storage :queue-message id)) "handled and acked")
        (is (= 1 (count (done))) "handled exactly once — the lease outlived the claim"))
      (finally
        (recon/stop-all! running ctx)
        (doseq [r (concat (done) (sp/query-entities storage :queue-message {:queue "qs-orders"}))]
          (sp/delete-entity storage :queue-message (:id r)))
        (sp/delete-entity storage :service (:id svc))))))
