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
          repetitions (atom 1)
          kind (atom :manifest)
          escaped (atom nil)
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
                           (reset! escaped (bound-fn [] (manifest/capture fixed @kind [root] policy
                                                                          (fn [] (swap! reads inc) graph) validate!)))
                           (last (mapv (fn [_]
                                         (manifest/capture fixed @kind [root] policy
                                                           (fn [] (swap! reads inc) graph) validate!))
                                       (range @repetitions))))
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
        (f {:collect collect :controller controller :reads reads :revision revision :context context :graph graph :fail-commit? fail-commit? :repetitions repetitions :escaped escaped :kind kind})))))


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


(deftest snapshot-copy-budget-covers-repeated-cache-hit-thaws
  (exercise
    (fn [{:keys [collect reads repetitions graph]}]
      (collect identity)
      (reset! repetitions 100)
      (let [validated (atom 0)]
        (is (= graph (collect (fn [_] (swap! validated inc)))))
        (is (pos? @validated) "Some bounded hits were used")
        (is (< @validated 100) "Further hits fell back before allocation")
        (is (> @reads 1) "Ordinary collector handles the remaining calls")))))


(deftest escaped-bound-worker-cannot-reuse-an-expired-snapshot
  (exercise
    (fn [{:keys [collect escaped reads controller]}]
      (collect identity)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Source snapshot expired" (@escaped)))
      (is (= 1 @reads))
      (is (zero? (:pending (manifest/stats controller)))))))


(deftest client-and-manifest-collectors-never-share-a-retained-entry
  (exercise
    (fn [{:keys [collect kind reads graph]}]
      (let [validated (atom 0)
            validate! (fn [_] (swap! validated inc))]
        (doseq [mode [:manifest :client :client :manifest]]
          (reset! kind mode)
          (is (= graph (collect validate!))))
        (is (= 2 @reads))
        (is (= 2 @validated))
        (reset! kind :unknown)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown source collector kind"
              (collect validate!)))))))
