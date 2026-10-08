(ns ^:integration graphden.services.queue-lifecycle-test
  "A real worker retries a failed graph, then consumes the exact requeued
   message after the graph is corrected. Stop removes its running instance."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.fn-execution.lookup :as execution]
    [graphden.executor.test-setup :as setup]
    [graphden.layout.data :as data]
    [graphden.layout.graph :as graph]
    [graphden.packages.records :as records]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.wait :as wait]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [test-fn]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (test-fn)))
  (impls/impls-fixture "storage" "queue"))


(deftest dead-letter-requeue-graph-repair-ack-and-stop
  (let [{:keys [ctx storage]} *bootstrap*
        queue (str "lifecycle-" (random-uuid))
        _ (setup/sync-and-invalidate!
            ctx storage
            [{:name :queue-lifecycle-handler :parent :parse-json
              :args {:string "not JSON" :keywordize true}}
             {:name :queue-lifecycle-worker :parent :pg-queue-consumer
              :args {:queue queue :handler :queue-lifecycle-handler}}])
        handler-id (records/fn-id nil :queue-lifecycle-handler)
        service (sp/create-entity storage :service
                                  {:fn-id (records/fn-id nil :queue-lifecycle-worker)
                                   :enabled? true :restart-policy :always :cardinality :singleton})
        message-id ((impls/impl-of :queue-publish) {:queue queue :payload "original" :delay-ms 0} ctx)
        running (atom {})
        instances #(sp/query-entities storage :service-instance {:service-id (:id service)})]
    (try
      (recon/reconcile-once! ctx running)
      (is (= 1 (count (instances))))
      ;; Use production retry/attempt defaults, without rebinding a final
      ;; inherited nack reference merely to make a faster fixture.
      (wait/wait-for 60000 #(= "dead" (:state (sp/read-entity storage :queue-message message-id))))
      (let [dead (sp/read-entity storage :queue-message message-id)]
        (is (= "dead" (:state dead)))
        (is (= 5 (:attempts dead)))
        (is (seq (:error dead))))
      (sp/update-entity storage :service (:id service) {:enabled? false})
      (recon/reconcile-once! ctx running)
      (is (empty? @running))
      (is (empty? (instances)))
      (is (true? ((impls/impl-of :queue-requeue) {:message-id message-id} ctx)))
      (is (= {:id message-id :state "pending" :attempts 0 :error nil :payload "original"}
             (select-keys (sp/read-entity storage :queue-message message-id)
                          [:id :state :attempts :error :payload])))
      (let [literal (first (filter #(= "not JSON" (:value %))
                                   (sp/query-entities storage :binding {:fn-id handler-id})))]
        (is (some? literal))
        (entities/update-entity :binding (:id literal) {:value "{}"} ctx))
      (sp/update-entity storage :service (:id service) {:enabled? true})
      (recon/reconcile-once! ctx running)
      (wait/wait-for 60000 #(nil? (sp/read-entity storage :queue-message message-id)))
      (is (nil? (sp/read-entity storage :queue-message message-id)) "The corrected graph ACKs that same UUID")
      (sp/delete-entity storage :service (:id service))
      (recon/reconcile-once! ctx running)
      (is (empty? @running))
      (is (empty? (instances)) "Deleting desired state stops its actual worker")
      (finally
        (recon/stop-all! running ctx)
        (when (sp/read-entity storage :queue-message message-id)
          (sp/delete-entity storage :queue-message message-id))
        (when (sp/read-entity storage :service (:id service))
          (sp/delete-entity storage :service (:id service)))))))


(deftest exact-message-state-does-not-expose-payload-or-neighbor
  (let [{:keys [ctx storage]} *bootstrap*
        message-id ((impls/impl-of :queue-publish)
                    {:queue (str (random-uuid)) :payload {:private "not in state reply"} :delay-ms 0} ctx)
        response (fn [id]
                   (setup/via-graph *bootstrap* :_partial-queue-message-handler
                                    {:uri "/partials/queues/message" :request-method :get
                                     :query-params {"message-id" (str id)}}))]
    (try
      (is (= {:id (str message-id) :state "pending" :attempts 0}
             (json/parse-string (:body (response message-id)) true)))
      (is (= {} (json/parse-string (:body (response (random-uuid))) true)))
      (is (thrown? Exception (response "not-a-uuid")))
      (finally (sp/delete-entity storage :queue-message message-id)))))


(deftest inherited-queue-consumer-exposes-required-closure-inputs
  (let [{:keys [ctx storage]} *bootstrap*
        _ (setup/sync-and-invalidate!
            ctx storage
            [{:name :surface-queue-child :parent :pg-queue-consumer}
             {:name :surface-queue-handler :parent :const :args {:value true}}])
        child (records/fn-id nil :surface-queue-child)
        entries (execution/free-arg-entries ctx child)
        slots (into {} (map (juxt :name :slot-id)) entries)
        lookups (data/build-lookups (data/load-graph-entities-uncached storage))
        layout (graph/build-graph-elements child {} lookups)
        holes (filter #(get-in % [:data :isPlaceholder]) (:nodes layout))]
    (is (= #{:queue :handler} (set (keys slots))))
    (is (every? (comp not :optional?) entries))
    (is (= #{"queue" "handler"}
           (into #{} (comp (filter #(get-in % [:data :isUnset]))
                           (map #(get-in % [:data :argName])))
                 (:edges layout))))
    (is (= 2 (count holes)))
    (is (= (set (map str (vals slots))) (set (map #(get-in % [:data :slotId]) holes))))
    (is (every? #(= (str child) (get-in % [:data :fnId])) holes)
        "The ordinary binder edits the child, never a package's captured function")
    (is (every? #(nil? (get-in % [:data :bindingId])) holes))
    (entities/create-entity :binding {:fn-id child :slot-id (:queue slots)
                                      :value "surface-isolated-queue" :value-present true} ctx)
    (is (= [:handler] (mapv :name (execution/free-arg-entries ctx child))))
    (entities/create-entity :binding {:fn-id child :slot-id (:handler slots)
                                      :ref-fn-id (records/fn-id nil :surface-queue-handler)} ctx)
    (is (empty? (execution/free-arg-entries ctx child)))
    (is (empty? (filter #(get-in % [:data :isPlaceholder])
                        (:nodes (graph/build-graph-elements
                                  child {} (data/build-lookups
                                             (data/load-graph-entities-uncached storage)))))))))
