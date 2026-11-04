(ns graphden.packages.web.service-test
  "Native read primitives beneath the graph-composed service endpoint
   priority. The storage is a real PG with the services schema."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once
  (setup/create-container-fixture)
  (impls/impls-fixture "web" "service"))


(defn- resolver-atom
  []
  @(requiring-resolve 'graphden.services.endpoint/resolver))


(defn- instance!
  [storage svc-id executor-id host port seen-at]
  (sp/create-entity storage :service-instance
                    {:service-id svc-id :executor-id executor-id :host host :port port
                     :started-at seen-at :seen-at seen-at}))


(deftest service-endpoint-primitives-read-a-live-instance-test
  (let [storage (setup/create-branch-versioned-test-storage)
        rows-f (impls/impl-of :_service-rows)
        live-f (impls/impl-of :_live-service-endpoint)
        ctx {:storage storage}
        now (java.time.Instant/now)]
    (try
      (let [svc-fn (setup/create-base-fn! storage "se-listener" :any)
            other-fn (setup/create-base-fn! storage "se-other" :any)
            svc (sp/create-entity storage :service
                                  {:fn-id (:id svc-fn) :enabled? true :restart-policy :always
                                   :cardinality :per-pod})]
        (testing "no service row gives no listener candidate"
          (let [rows (rows-f {:service (:id other-fn)} ctx)]
            (is (empty? rows))
            (is (nil? (live-f {:rows rows} ctx)))))
        (testing "a service row without a live instance is still unavailable"
          (let [rows (rows-f {:service (:id svc-fn)} ctx)]
            (is (= 1 (count rows)))
            (is (nil? (live-f {:rows rows} ctx)))))
        (let [remote (instance! storage (:id svc) "graphden-2.svc" "graphden-2.svc" 9001 now)]
          (testing "a live remote copy → host + port + a joined origin url"
            (is (= {:host "graphden-2.svc" :port 9001 :url "http://graphden-2.svc:9001"}
                   (live-f {:rows (rows-f {:service (:id svc-fn)} ctx)} ctx))))
          (testing "this pod's own copy wins over a remote one"
            (instance! storage (:id svc) "local" "127.0.0.1" 9001 now)
            (is (= "http://127.0.0.1:9001"
                   (:url (live-f {:rows (rows-f {:service (:id svc-fn)} ctx)} ctx)))))
          (testing "a stale heartbeat is not a live copy"
            (doseq [row (sp/query-entities storage :service-instance {:service-id (:id svc)})]
              (sp/update-entity storage :service-instance (:id row)
                                {:seen-at (java.time.Instant/.minusMillis now 120000)}))
            (is (nil? (live-f {:rows (rows-f {:service (:id svc-fn)} ctx)} ctx))))
          (sp/delete-entity storage :service-instance (:id remote))))
      (finally (sp/close storage)))))


(deftest addon-resolver-primitive-uses-the-installed-resolver-test
  (let [storage (setup/create-branch-versioned-test-storage)
        f (impls/impl-of :_addon-service-endpoint)
        resolver (resolver-atom)
        before @resolver]
    (try
      (let [app-fn (setup/create-base-fn! storage "se-app" :any)
            seen (atom nil)]
        (reset! resolver (fn [_ctx fn-id]
                           (reset! seen fn-id)
                           {:host "shop.graphden.app" :port 443 :url "https://shop.graphden.app"}))
        (is (= {:host "shop.graphden.app" :port 443 :url "https://shop.graphden.app"}
               (f {:service (:id app-fn)} {:storage storage})))
        (is (= (:id app-fn) @seen)))
      (finally
        (reset! resolver before)
        (sp/close storage)))))
