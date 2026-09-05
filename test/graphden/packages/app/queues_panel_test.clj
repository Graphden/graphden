(ns graphden.packages.app.queues-panel-test
  "Operate → Queues through the graph: the panel lists every queue with
   its pending / dead counts and the dead letters; Requeue puts a dead
   letter back, Delete drops it — each answering with the refreshed
   panel (the hx-post contract)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.graph-harness :as gh :refer [*graph*]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*)))
  (impls/impls-fixture "storage" "queue"))


(defn- panel
  ([] (panel :_partial-queues-panel-handler :get "/partials/queues-panel" nil))
  ([handler method uri message-id]
   (:body (setup/via-graph *graph* handler
                           (cond-> {:uri uri :request-method method}
                             message-id (assoc :query-string (str "message-id=" message-id)
                                               :query-params {"message-id" (str message-id)}))))))


(deftest queues-panel-test
  (let [storage (:storage *graph*)
        ctx {:storage storage}
        publish! (fn [q] ((impls/impl-of :queue-publish) {:queue q :payload {:x 1} :delay-ms 0} ctx))
        rows (fn [] (sp/query-entities storage :queue-message {}))]
    (try
      (testing "no messages → the empty state"
        (is (str/includes? (panel) "No messages")))
      (let [live (publish! "pt-orders")
            doomed (publish! "pt-orders")
            _ (publish! "pt-mail")]
        ;; Claim and dead-letter one message the way a consumer would.
        ((impls/impl-of :queue-take) {:queue "pt-orders" :batch 10 :visibility-ms 1000 :wait-ms 0} ctx)
        ((impls/impl-of :queue-nack) {:message-id doomed :error "boom" :retry-ms 0 :max-attempts 1} ctx)
        (sp/update-entity storage :queue-message live {:locked-until nil})
        (testing "every queue with its counts, and the dead letter with its actions"
          (let [html (panel)]
            (is (str/includes? html "pt-orders"))
            (is (str/includes? html "pt-mail"))
            (is (str/includes? html "boom") "the dead letter's error")
            (is (str/includes? html (str "/partials/queues/requeue?message-id=" doomed)))
            (is (str/includes? html (str "/partials/queues/delete?message-id=" doomed)))
            (is (not (str/includes? html "No dead letters.")))))
        (testing "Requeue: the dead letter is pending again and the panel says so"
          (let [html (panel :_partial-queues-requeue-handler :post
                            "/partials/queues/requeue" doomed)]
            (is (str/includes? html "No dead letters."))
            (is (= "pending" (:state (sp/read-entity storage :queue-message doomed))))
            (is (zero? (:attempts (sp/read-entity storage :queue-message doomed))))))
        (testing "Delete: the row is gone"
          ((impls/impl-of :queue-take) {:queue "pt-orders" :batch 10 :visibility-ms 1000 :wait-ms 0} ctx)
          ((impls/impl-of :queue-nack) {:message-id doomed :error "boom again" :retry-ms 0 :max-attempts 1} ctx)
          (let [html (panel :_partial-queues-delete-handler :post
                            "/partials/queues/delete" doomed)]
            (is (str/includes? html "No dead letters."))
            (is (nil? (sp/read-entity storage :queue-message doomed))))))
      (finally
        (doseq [r (rows)] (sp/delete-entity storage :queue-message (:id r)))))))
