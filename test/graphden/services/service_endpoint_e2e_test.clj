(ns ^:integration graphden.services.service-endpoint-e2e-test
  "Two services talking, end to end through the graph: a producer
   `:http-server` service started by the REAL reconciler (which records
   where it answers), and a consumer fn-def that names the producer
   through `:service-get-json` and gets its JSON back over a real socket.
   Then the producer stops and the consumer is told, honestly, that the
   service is not running."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.services.endpoint :as endpoint]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.execution-api :as execution-api]
    [graphden.types.check :as types-check])
  (:import
    (java.net
      ServerSocket)))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      ;; Golden bootstraps skip the package type sweep. This test asserts
      ;; inherited effects, so check the service composition before syncing
      ;; the consumer fn; production performs the full sweep at boot.
      (types-check/check-all-defs!
        (filterv #(= "web.service" (:namespace %))
                 (:fn-defs (loader/load-packages ["web"]))))
      (t))))


(defn- free-port
  []
  (with-open [s (ServerSocket. 0)]
    (ServerSocket/.getLocalPort s)))


(deftest endpoint-graph-prefers-publication-before-addon-test
  (let [{:keys [ctx storage]} *bootstrap*
        target-id (records/fn-id "web.response" :text-ok-response)
        fn-id (records/fn-id nil :_e2e-endpoint-source-priority)
        calls (atom [])
        before-temporary @endpoint/temporary-resolver
        before-addon @endpoint/resolver
        temporary {:host "temporary.example" :port 443 :url "https://temporary.example/p/one"}
        addon {:host "app.example" :port 443 :url "https://app.example"}]
    (try
      (setup/sync-and-invalidate!
        ctx storage [{:name :_e2e-endpoint-source-priority
                      :parent :service-endpoint
                      :args {:service :text-ok-response}}])
      (reset! endpoint/temporary-resolver
              (fn [_ctx service]
                (swap! calls conj :temporary)
                (when (= target-id service) temporary)))
      (reset! endpoint/resolver
              (fn [_ctx service]
                (swap! calls conj :addon)
                (when (= target-id service) addon)))
      (let [service-row (sp/create-entity storage :service
                                          {:fn-id target-id :enabled? true
                                           :restart-policy :always :cardinality :per-pod})
            now (java.time.Instant/now)
            instance-row (sp/create-entity storage :service-instance
                                           {:service-id (:id service-row)
                                            :executor-id "local" :host "127.0.0.1"
                                            :port 9001 :started-at now :seen-at now})]
        (try
          (is (= {:host "127.0.0.1" :port 9001 :url "http://127.0.0.1:9001"}
                 (exec/execute ctx fn-id {})))
          (is (empty? @calls) "a live listener must avoid both resolvers")
          (finally
            (sp/delete-entity storage :service-instance (:id instance-row))
            (sp/delete-entity storage :service (:id service-row)))))
      (is (= temporary (exec/execute ctx fn-id {})))
      (is (= [:temporary] @calls) "a publication must avoid the addon lookup")
      (reset! calls [])
      (reset! endpoint/temporary-resolver
              (fn [_ctx _service] (swap! calls conj :temporary) nil))
      (is (= addon (exec/execute ctx fn-id {})))
      (is (= [:temporary :addon] @calls))
      (reset! endpoint/resolver (fn [_ctx _service] nil))
      (try
        (exec/execute ctx fn-id {})
        (is false "a missing endpoint must throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= {:type :service/not-running :fn-id target-id :service-rows 0}
                 (ex-data e)))))
      (finally
        (reset! endpoint/temporary-resolver before-temporary)
        (reset! endpoint/resolver before-addon)))))


(deftest consumer-reaches-producer-through-the-recorded-endpoint-test
  (let [{:keys [ctx storage]} *bootstrap*
        port (free-port)
        _ (setup/sync-and-invalidate!
            ctx storage
            [;; Producer: a listener whose every request answers a JSON body.
             ;; The same shape lesson 38 teaches: a JSON response
             ;; template behind the encode/stringify wrap (header keys
             ;; keywordize on the jsonb round-trip; the wrap restores
             ;; the strings http-kit wants), served by a listener.
             {:name :_e2e-orders-ok :parent :json-ok-response
              :args {:body "{\"orders\":[1,2,3]}"}}
             {:name :_e2e-orders-ring :parent :encode-stringify-wrap
              :args {:base-handler :_e2e-orders-ok}}
             {:name :e2e-orders-service :parent :http-server
              :args {:handler :_e2e-orders-ring :port port}}
             ;; Consumer: names the producer, calls its /orders, parses the body.
             {:name :e2e-fetch-orders :parent :service-get-json
              :args {:service :e2e-orders-service :path "/orders"}}
             ;; A listener with a free arg of its own (`:listen-port`) and
             ;; a consumer that only NAMES it — the identity edge must not
             ;; lift the listener's free arg or its `:process` effect.
             {:name :e2e-needy-listener :parent :http-server
              :args {:handler :_e2e-orders-ring :port {:as :listen-port}}}
             {:name :e2e-peek :parent :service-endpoint
              :args {:service :e2e-needy-listener}}])
        ;; Namespace-less test defs get the deterministic root id.
        svc-fn-id (records/fn-id nil :e2e-orders-service)
        fetch-id (records/fn-id nil :e2e-fetch-orders)
        svc (sp/create-entity storage :service
                              {:fn-id svc-fn-id :enabled? true
                               :restart-policy :always :cardinality :per-pod})
        running (atom {})]
    (try
      (testing "naming a service lifts neither its free args nor its effects"
        (let [peek-id (records/fn-id nil :e2e-peek)]
          (is (= {} (lookup/free-arg-slot-map ctx peek-id))
              "the listener's :listen-port is not the consumer's free arg")
          (is (= #{:db} (:effects (registry/rich-type-of-id peek-id)))
              "the consumer declares its own :db read, not the listener's :process")))
      (testing "before the reconciler starts the producer, the consumer is told so"
        (try
          (exec/execute ctx fetch-id {})
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :service/not-running (:type (ex-data e))))
            (is (= 1 (:service-rows (ex-data e)))))))
      (recon/reconcile-once! ctx running)
      (testing "the reconciler recorded where the producer answers"
        (is (= [{:host "127.0.0.1" :port port}]
               (mapv #(select-keys % [:host :port])
                     (sp/query-entities storage :service-instance {:service-id (:id svc)})))))
      (testing "the ⚙ popover lists the running copy under Running copies"
        (let [html (:body (setup/via-graph *bootstrap* :_partial-service-popover-handler
                                           {:uri (str "/partials/service-popover?fn-id=" svc-fn-id)
                                            :request-method :get
                                            :query-params {"fn-id" (str svc-fn-id)}}))]
          (is (str/includes? html "Running copies"))
          (is (str/includes? html (str "127.0.0.1:" port)))
          (is (str/includes? html "local") "the pod's executor id")))
      (testing "the consumer resolves the producer by naming it and gets its JSON"
        (is (= {:orders [1 2 3]} (exec/execute ctx fetch-id {}))))
      (testing "a persisted run's call is traced across the wire: the producer
                persists the request it handled as an execution linked back"
        (let [parent (persist/create-pending-row!
                       storage (lookup/resolve-fn-version-id ctx fetch-id) [] nil nil)
              parent-id (:id parent)]
          (binding [cr/*execution* {:id parent-id :trace-id parent-id}]
            (is (= {:orders [1 2 3]} (exec/execute ctx fetch-id {}))))
          (let [children (:children (execution-api/get-execution ctx parent-id))
                child-row (some->> children first :id (sp/read-entity storage :fn-execution))]
            (is (= 1 (count children)))
            (is (= "_e2e-orders-ring" (:fn-name (first children))) "the listener's handler fn")
            (is (= :succeeded (:status (first children))))
            (is (= parent-id (:trace-id child-row)))
            (is (= parent-id (:parent-execution-id child-row)))
            (is (= 200 (get-in child-row [:result :status]))))
          (testing "an untraced call persists nothing"
            (let [before (count (sp/query-entities storage :fn-execution {:trace-id parent-id}))]
              (exec/execute ctx fetch-id {})
              (is (= before (count (sp/query-entities storage :fn-execution {:trace-id parent-id}))))))))
      (recon/stop-all! running ctx)
      (testing "after the producer stops, the instance is gone and the consumer says so"
        (is (empty? (sp/query-entities storage :service-instance {:service-id (:id svc)})))
        (try
          (exec/execute ctx fetch-id {})
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :service/not-running (:type (ex-data e)))))))
      (finally
        (recon/stop-all! running ctx)
        (sp/delete-entity storage :service (:id svc))))))
