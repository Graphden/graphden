(ns ^:serial graphden.executor.revision-manifest-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.revision-manifest :as manifest]
    [graphden.executor.source-recipe-storage :as storage]
    [graphden.storage.postgres.source-certificate :as certificate]
    [graphden.storage.postgres.source-revision-snapshot :as snapshot]
    [graphden.tenancy.context :as tenancy])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)))


(defn- exercise
  [f]
  (with-open [pool (HikariDataSource.)]
    (let [controller (manifest/create-controller pool ["fn"]
                                                 {:max-bytes 100000 :max-entries 2 :max-pending 1
                                                  :max-capture-bytes 100000 :max-rows 100 :ttl-ms 10000})
          source (Object.)
          registration {:generation (Object.)}
          revision (atom 1)
          fail-commit? (atom false)
          context (atom "a")
          authorize! (fn [_] true)
          graph {:fns [{:id (random-uuid) :parent-ids #{}}]
                 :slots [] :fn-slots [] :bindings [] :list-items [] :identity-target-ids #{}}
          reads (atom 0)
          checks (atom 0)
          root (random-uuid)
          collect (fn [validate!]
                    (manifest/call-with-controller
                      controller
                      #(manifest/with-snapshot
                         source (fn [] (swap! checks inc)) nil
                         (fn [fixed policy]
                           (manifest/capture fixed [root] policy
                                             (fn [] (swap! reads inc) graph) validate!))
                         (fn [_] (throw (ex-info "Unexpected fallback" {}))))))]
      (with-redefs [tenancy/source-replay-registration (constantly registration)
                    tenancy/source-replay-policy-info (constantly registration)
                    tenancy/browser-source-policy (constantly authorize!)
                    storage/describe (fn [_ _] {:pool pool :datasource pool :branch-id root})
                    storage/fresh-storage (fn [_ _] source)
                    snapshot/call-with-snapshot (fn [_ _ _ loader]
                                                  (let [result (loader nil @revision)]
                                                    (when @fail-commit? (throw (ex-info "Aborted snapshot" {})))
                                                    {:value result}))
                    certificate/verify-policy-context! (fn [& _] {"app.org" @context})]
        (f {:collect collect :controller controller :reads reads :revision revision :context context :graph graph :fail-commit? fail-commit?})))))


(deftest fresh-validation-on-every-hit-and-revision-and-context-misses
  (exercise
    (fn [{:keys [collect reads revision context graph controller]}]
      (let [validations (atom 0)
            validate! (fn [actual] (is (= graph actual)) (swap! validations inc))]
        (is (= graph (collect validate!)))
        (is (= graph (collect validate!)))
        (is (= 1 @reads))
        (is (= 1 @validations))
        (swap! revision inc)
        (collect validate!)
        (reset! context "b")
        (collect validate!)
        (is (= 3 @reads))
        (is (= 2 (:entries (manifest/stats controller))))))))


(deftest denied-consumer-is-never-retried-and-retirement-clears-retained-source
  (exercise
    (fn [{:keys [collect controller reads]}]
      (collect identity)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Denied"
            (collect (fn [_] (throw (ex-info "Denied" {}))))))
      (is (= 1 @reads))
      (is (= 1 (:entries (manifest/stats controller))))
      (manifest/retire-controller! controller)
      (is (= {:entries 0 :bytes 0 :pending 0 :closed? true} (manifest/stats controller))))))


(deftest aborted-transaction-does-not-publish-and-reservation-is-released
  (exercise
    (fn [{:keys [collect controller fail-commit?]}]
      (reset! fail-commit? true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Aborted snapshot" (collect identity)))
      (is (zero? (:entries (manifest/stats controller))))
      (is (zero? (:pending (manifest/stats controller))))
      (reset! fail-commit? false)
      (collect identity)
      (is (= 1 (:entries (manifest/stats controller)))))))
