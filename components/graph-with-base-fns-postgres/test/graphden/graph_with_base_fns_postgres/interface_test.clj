(ns graphden.graph-with-base-fns-postgres.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.interface :as bf]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-with-base-fns-postgres.interface :as gwbf]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as pth]))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(defn with-clean-state
  "Combines database cleanup with registry cleanup."
  [f]
  (pth/clean-database-fast! *container*)
  (exec/with-clean-registry f))


(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each with-clean-state)


(defn- create-test-storage
  "Creates a test storage with a clean database."
  []
  (pth/clean-database-fast! *container*)
  (gwbf/create-storage (pth/get-container-config *container*)))


(deftest create-storage-test
  (testing "create-storage creates storage with base functions"
    (let [storage (create-test-storage)]
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
          (sp/close storage))))))


(deftest error-handling-test
  (testing "sync failure propagates error and closes storage"
    (with-redefs [registry/sync-defs-to-storage!
                  (fn [_ _]
                    (throw (ex-info "Simulated sync failure" {:type :test-error})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated sync failure"
            (gwbf/create-storage (pth/get-container-config *container*))))))

  (testing "registration failure propagates error and closes storage"
    (with-redefs [registry/register-base-fns!
                  (fn [_]
                    (throw (ex-info "Simulated registration failure" {:type :test-error})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated registration failure"
            (gwbf/create-storage (pth/get-container-config *container*))))))

  (testing "error includes original exception data"
    (with-redefs [registry/sync-defs-to-storage!
                  (fn [_ _]
                    (throw (ex-info "Sync error" {:type :sync-error :details "test"})))]
      (try
        (gwbf/create-storage (pth/get-container-config *container*))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :sync-error (:type (ex-data e))))
          (is (= "test" (:details (ex-data e)))))))))


(deftest base-functions-integration-test
  (testing "all base function schemas are synced"
    (let [storage (create-test-storage)]
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
    (let [storage (create-test-storage)]
      (try
        (let [add-schema (first (sp/query-entities storage :fn-schema {:name "add"}))
              add-args (sp/query-entities storage :arg-schema {:fn-schema-id (:id add-schema)})]
          ;; add function should have :nums arg (list of numbers)
          (is (= 1 (count add-args)))
          (is (= "nums" (:name (first add-args)))))
        (finally
          (sp/close storage)))))

  (testing "base functions are executable via executor"
    (let [storage (create-test-storage)]
      (try
        ;; The base functions should be callable
        (let [add-fn (exec/get-base-fn :add)]
          (is (some? add-fn) "add function should be registered"))
        (finally
          (sp/close storage))))))
