(ns graphden.packages.web.service-test
  "`:service-endpoint` impl — resolve a service fn (by id, the `:fn-ref`
   slot's value) to `{:host :port :url}`: from the `:service` row the
   reconciler wrote, from the addon-installed resolver seam, or an
   honest `:service/not-running`. The impl is loaded through the
   package loader; the storage is a real PG with the services schema."
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


(deftest service-endpoint-reads-the-recorded-row-test
  (let [storage (setup/create-branch-versioned-test-storage)
        f (impls/impl-of :service-endpoint)]
    (try
      (let [svc-fn (setup/create-base-fn! storage "se-listener" :any)
            other-fn (setup/create-base-fn! storage "se-other" :any)]
        (sp/create-entity storage :service
                          {:fn-id (:id svc-fn) :enabled? true :restart-policy :always
                           :cardinality :per-pod
                           :endpoint {:host "graphden-2.svc" :port 9001}})
        (testing "host + port + a joined origin url"
          (is (= {:host "graphden-2.svc" :port 9001 :url "http://graphden-2.svc:9001"}
                 (f {:service (:id svc-fn)} {:storage storage}))))
        (testing "a fn with no service row is not running"
          (try
            (f {:service (:id other-fn)} {:storage storage})
            (is false "should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :service/not-running (:type (ex-data e))))
              (is (zero? (:service-rows (ex-data e)))))))
        (testing "a row without a recorded endpoint (not started / not a listener) is not running either"
          (sp/update-entity storage :service
                            (:id (first (sp/query-entities storage :service {:fn-id (:id svc-fn)})))
                            {:endpoint nil})
          (try
            (f {:service (:id svc-fn)} {:storage storage})
            (is false "should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :service/not-running (:type (ex-data e))))
              (is (= 1 (:service-rows (ex-data e))))))))
      (finally (sp/close storage)))))


(deftest service-endpoint-falls-back-to-the-installed-resolver-test
  (let [storage (setup/create-branch-versioned-test-storage)
        f (impls/impl-of :service-endpoint)
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
