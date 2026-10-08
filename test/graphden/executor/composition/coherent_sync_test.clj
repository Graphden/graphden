(ns ^:integration graphden.executor.composition.coherent-sync-test
  "A parsed bundle must apply against the graph snapshot used by its parser."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.composition.core :as composition]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as packages]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as versioned]))


(use-fixtures :once (setup/create-container-fixture))


(deftest a-stale-bundle-refuses-before-namespace-or-function-writes
  (let [storage (versioned/wrap-with-versioning (setup/create-test-storage))]
    (try
      (registry/initialize-all!
        storage [{:coherence-base {:args {} :return-type :int :impl (fn [_ _] 1)}}])
      (let [base-id (registry/fn-uuid :coherence-base)
            prepare composition/prepare-sync
            before (count (sp/query-entities storage :fn {}))
            defs [{:namespace "pending-bundle" :name :pending-child :parent :coherence-base}]
            result (with-redefs [composition/prepare-sync
                                 (fn [s definitions]
                                   (let [prepared (prepare s definitions nil)]
                                     ;; This commits through a different connection before apply
                                     ;; acquires its writer. The changed description is part of
                                     ;; the parser basis even though parent UUID stays unchanged.
                                     (sp/update-entity s :fn base-id {:description "changed after prepare"})
                                     prepared))]
                     (try (packages/sync-bundle! storage defs) :unexpected-success
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
        (is (= :constraint-violation/stale-bundle result))
        (is (= before (count (sp/query-entities storage :fn {}))))
        (is (empty? (sp/query-entities storage :ns {:name "pending-bundle"})))
        (is (= "changed after prepare" (:description (sp/read-entity storage :fn base-id)))))
      (finally (sp/close storage)))))


(deftest bundle-row-failure-rolls-back-new-namespaces-too
  (let [storage (versioned/wrap-with-versioning (setup/create-test-storage))]
    (try
      (registry/initialize-all!
        storage [{:coherent-base {:args {} :return-type :int :impl (fn [_ _] 1)}}])
      (let [write-records composition/write-records!
            before (count (sp/query-entities storage :fn {}))
            result (with-redefs [composition/write-records!
                                 (fn [& args]
                                   (apply write-records args)
                                   (throw (ex-info "Failure after graph rows" {:type ::late-failure})))]
                     (try
                       (packages/sync-bundle!
                         storage [{:namespace "rolled-back-bundle" :name :child :parent :coherent-base}])
                       :unexpected-success
                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
        (is (= ::late-failure result))
        (is (= before (count (sp/query-entities storage :fn {}))))
        (is (empty? (sp/query-entities storage :ns {:name "rolled-back-bundle"}))))
      (finally (sp/close storage)))))
