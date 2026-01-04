(ns graphden.graph-with-base-fns-datomic.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.graph-with-base-fns-datomic.interface :as gwbf]
    [graphden.storage-protocol.interface :as sp]))


(defn with-clean-registry
  [f]
  (exec/clear-base-fns!)
  (try
    (f)
    (finally
      (exec/clear-base-fns!))))


(use-fixtures :each with-clean-registry)


(deftest create-storage-test
  (testing "create-storage creates storage with base functions"
    (let [storage (gwbf/create-storage)]
      (try
        ;; Check storage is initialized
        (is (some? storage))

        ;; Check graph entities are available
        (is (contains? (sp/current-entities storage) :fn-schema))
        (is (contains? (sp/current-entities storage) :arg-schema))
        (is (contains? (sp/current-entities storage) :fn))
        (is (contains? (sp/current-entities storage) :arg-value))

        ;; Check base functions are registered in executor
        (is (some? (exec/get-base-fn :add)))
        (is (some? (exec/get-base-fn :map)))
        (is (some? (exec/get-base-fn :if)))

        ;; Check fn-schemas are synced to storage
        (let [fn-schemas (sp/query-entities storage :fn-schema {})]
          (is (pos? (count fn-schemas)))
          (is (some #(= "add" (:name %)) fn-schemas))
          (is (some #(= "map" (:name %)) fn-schemas)))

        ;; Check arg-schemas are synced to storage
        (let [arg-schemas (sp/query-entities storage :arg-schema {})]
          (is (pos? (count arg-schemas))))

        (finally
          (sp/close storage)))))

  (testing "create-storage with explicit db-name"
    (let [storage (gwbf/create-storage {:db-name "test-with-base-fns"})]
      (try
        (is (some? storage))
        (is (some? (exec/get-base-fn :add)))
        (is (pos? (count (sp/query-entities storage :fn-schema {}))))
        (finally
          (sp/close storage)))))

  (testing "create-storage is idempotent for registration"
    ;; Creating multiple storages should not cause issues
    (let [storage1 (gwbf/create-storage)
          storage2 (gwbf/create-storage)]
      (try
        ;; Both should work
        (is (some? (exec/get-base-fn :add)))
        (is (pos? (count (sp/query-entities storage1 :fn-schema {}))))
        (is (pos? (count (sp/query-entities storage2 :fn-schema {}))))
        (finally
          (sp/close storage1)
          (sp/close storage2))))))
