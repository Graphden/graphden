(ns ^:integration graphden.services.service-endpoint-e2e-test
  "Two services talking, end to end through the graph: a producer
   `:http-server` service started by the REAL reconciler (which records
   where it answers), and a consumer fn-def that names the producer
   through `:service-get-json` and gets its JSON back over a real socket.
   Then the producer stops and the consumer is told, honestly, that the
   service is not running."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.net
      ServerSocket)))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (t))))


(defn- free-port
  []
  (with-open [s (ServerSocket. 0)]
    (ServerSocket/.getLocalPort s)))


(deftest consumer-reaches-producer-through-the-recorded-endpoint-test
  (let [{:keys [ctx storage]} *bootstrap*
        port (free-port)
        _ (setup/sync-and-invalidate!
            ctx storage
            [;; Producer: a listener whose every request answers a JSON body.
             ;; The same shape lesson 35 teaches: a JSON response
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
              :args {:service :e2e-orders-service :path "/orders"}}])
        ;; Namespace-less test defs get the deterministic root id.
        svc-fn-id (records/fn-id nil :e2e-orders-service)
        fetch-id (records/fn-id nil :e2e-fetch-orders)
        svc (sp/create-entity storage :service
                              {:fn-id svc-fn-id :enabled? true
                               :restart-policy :always :cardinality :per-pod})
        running (atom {})]
    (try
      (testing "before the reconciler starts the producer, the consumer is told so"
        (try
          (exec/execute ctx fetch-id {})
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :service/not-running (:type (ex-data e)))))))
      (recon/reconcile-once! ctx running)
      (testing "the reconciler recorded where the producer answers"
        (is (= {:host "127.0.0.1" :port port}
               (:endpoint (first (sp/query-entities storage :service {:id (:id svc)}))))))
      (testing "the consumer resolves the producer by naming it and gets its JSON"
        (is (= {:orders [1 2 3]} (exec/execute ctx fetch-id {}))))
      (recon/stop-all! running ctx)
      (testing "after the producer stops, the endpoint is gone and the consumer says so"
        (is (nil? (:endpoint (first (sp/query-entities storage :service {:id (:id svc)})))))
        (try
          (exec/execute ctx fetch-id {})
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :service/not-running (:type (ex-data e)))))))
      (finally
        (recon/stop-all! running ctx)
        (sp/delete-entity storage :service (:id svc))))))
