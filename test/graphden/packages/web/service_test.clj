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


(deftest service-endpoint-primitives-read-safe-instance-facts-test
  (let [storage (setup/create-branch-versioned-test-storage)
        rows-f (impls/impl-of :_service-rows)
        facts-f (impls/impl-of :_service-instance-facts)
        ctx {:storage storage :executor-id "local"}
        now (java.time.Instant/ofEpochMilli (System/currentTimeMillis))
        now-ms (java.time.Instant/.toEpochMilli now)]
    (try
      (let [svc-fn (setup/create-base-fn! storage "se-listener" :any)
            other-fn (setup/create-base-fn! storage "se-other" :any)
            svc (sp/create-entity storage :service
                                  {:fn-id (:id svc-fn) :enabled? true :restart-policy :always
                                   :cardinality :per-pod})
            facts #(facts-f {:rows (rows-f {:service %} ctx)} ctx)]
        (testing "missing service rows and instances yield an empty fact cohort"
          (is (empty? (rows-f {:service (:id other-fn)} ctx)))
          (is (empty? (:instances (facts (:id other-fn)))))
          (is (= 1 (count (rows-f {:service (:id svc-fn)} ctx))))
          (is (empty? (:instances (facts (:id svc-fn))))))
        (let [remote (instance! storage (:id svc) "remote" "remote.svc" 9001 now)]
          (testing "scoped facts expose safe scalar fields and capture trusted clock/executor"
            (let [result (facts (:id svc-fn))]
              (is (= [{:host "remote.svc" :port 9001 :executor-id "remote" :seen-at-ms now-ms}]
                     (:instances result)))
              (is (= "local" (:self-executor-id result)))
              (is (pos-int? (:stale-after-ms result)))
              (is (<= now-ms (:now-ms result)))))
          (instance! storage (:id svc) "local" "127.0.0.1" 9001 now)
          (is (= #{"local" "remote"} (set (map :executor-id (:instances (facts (:id svc-fn)))))))
          (testing "freshness is graph policy; the read preserves stale timestamps"
            (let [stale (java.time.Instant/.minusMillis now 120000)]
              (doseq [row (sp/query-entities storage :service-instance {:service-id (:id svc)})]
                (sp/update-entity storage :service-instance (:id row) {:seen-at stale}))
              (is (= #{(java.time.Instant/.toEpochMilli stale)}
                     (set (map :seen-at-ms (:instances (facts (:id svc-fn)))))))))
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
