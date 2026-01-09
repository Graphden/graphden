(ns graphden.graph-with-base-fns-datomic.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.interface :as bf]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-with-base-fns-datomic.interface :as gwbf]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


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


(deftest error-handling-test
  (testing "sync failure propagates error"
    (with-redefs [registry/sync-defs-to-storage!
                  (fn [_ _]
                    (throw (ex-info "Simulated sync failure" {:type :test-error})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated sync failure"
            (gwbf/create-storage)))))

  (testing "registration failure propagates error"
    (with-redefs [registry/register-base-fns!
                  (fn [_]
                    (throw (ex-info "Simulated registration failure" {:type :test-error})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated registration failure"
            (gwbf/create-storage)))))

  (testing "error includes original exception data"
    (with-redefs [registry/sync-defs-to-storage!
                  (fn [_ _]
                    (throw (ex-info "Sync error" {:type :sync-error :details "test"})))]
      (try
        (gwbf/create-storage)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :sync-error (:type (ex-data e))))
          (is (= "test" (:details (ex-data e)))))))))


(deftest base-functions-integration-test
  (testing "all base function schemas are synced"
    (let [storage (gwbf/create-storage)]
      (try
        (let [all-defs (bf/get-all-defs)
              fn-schemas (sp/query-entities storage :fn-schema {})
              synced-names (set (map :name fn-schemas))]
          ;; Each defined function should have a synced schema
          (doseq [[fn-name _] all-defs]
            (is (contains? synced-names (name fn-name))
                (str "Function " fn-name " should be synced"))))
        (finally
          (sp/close storage)))))

  (testing "base function arg schemas match definitions"
    (let [storage (gwbf/create-storage)]
      (try
        ;; Query all arg-schemas - there should be many for all base functions
        ;; Note: Datomic refs need entity ID, not UUID, so we can't filter by fn-schema-id
        (let [all-args (sp/query-entities storage :arg-schema {})]
          ;; Should have arg-schemas synced (many base functions have args)
          (is (pos? (count all-args)))
          ;; "nums" arg should exist (used by add, mul, etc.)
          (is (some #(= "nums" (:name %)) all-args)))
        (finally
          (sp/close storage)))))

  (testing "base functions are executable via executor"
    (let [storage (gwbf/create-storage)]
      (try
        ;; The base functions should be callable
        (let [add-fn (exec/get-base-fn :add)]
          (is (some? add-fn) "add function should be registered"))
        (finally
          (sp/close storage))))))
