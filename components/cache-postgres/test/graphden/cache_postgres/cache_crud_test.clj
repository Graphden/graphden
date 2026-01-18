(ns graphden.cache-postgres.cache-crud-test
  "Integration tests for basic cache CRUD operations."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-postgres.test-setup :as setup]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(deftest cache-exists-integration-test
  (testing "returns false for non-existent cache"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-id (random-uuid)]
          (is (false? (cache/cache-exists? cache fn-id))))
        (finally
          (sp/close storage))))))


(deftest save-and-get-cache-integration-test
  (testing "saves and retrieves execution graph"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              ;; Create fn-schema first (required by FK constraints)
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-schema"
                                           :returned-type :text
                                           :base-fn-name "base-fn"})
              fn-schema-id (:id fn-schema)
              ;; Create arg-schema
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "arg1"
                                            :type :text
                                            :required true})
              arg-schema-id (:id arg-schema)
              ;; Create fn
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              ;; Build graph
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "test-schema"
                                                :base-fn-name "base-fn"
                                                :returned-type :text}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "arg1"
                                                  :type :text
                                                  :required true}}
                     :resolved-args {fn-id {arg-schema-id "test-value"}}
                     :fn-result-values {}}
              dependencies {:fn-ids {fn-id 1}
                            :fn-schema-ids {fn-schema-id 1}
                            :arg-schema-ids {arg-schema-id 1}}]
          ;; Save cache
          (cache/save-cache! cache fn-id graph dependencies)
          ;; Verify it exists
          (is (true? (cache/cache-exists? cache fn-id)))
          ;; Get cached graph
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (some? cached))
            (is (sp/execution-graph? cached))
            ;; Verify fns
            (is (= 1 (count (:fns cached))))
            (is (= "test-fn" (get-in cached [:fns fn-id :name])))
            ;; Verify fn-schemas
            (is (= 1 (count (:fn-schemas cached))))
            (is (= "test-schema" (get-in cached [:fn-schemas fn-schema-id :name])))
            (is (= :text (get-in cached [:fn-schemas fn-schema-id :returned-type])))
            ;; Verify arg-schemas
            (is (= 1 (count (:arg-schemas cached))))
            (is (= "arg1" (get-in cached [:arg-schemas arg-schema-id :name])))
            (is (= :text (get-in cached [:arg-schemas arg-schema-id :type])))
            ;; Verify resolved-args
            (is (= "test-value" (get-in cached [:resolved-args fn-id arg-schema-id])))))
        (finally
          (sp/close storage))))))


(deftest delete-cache-integration-test
  (testing "deletes cache data"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-schema"
                                           :returned-type :text
                                           :base-fn-name "base-fn"})
              fn-schema-id (:id fn-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "test-schema"
                                                :base-fn-name "base-fn"
                                                :returned-type :text}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              dependencies {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          ;; Save and verify
          (cache/save-cache! cache fn-id graph dependencies)
          (is (true? (cache/cache-exists? cache fn-id)))
          ;; Delete
          (is (true? (cache/delete-cache! cache fn-id)))
          ;; Verify deleted
          (is (false? (cache/cache-exists? cache fn-id)))
          (is (nil? (cache/get-cached-graph cache fn-id)))
          ;; Delete again returns false
          (is (false? (cache/delete-cache! cache fn-id))))
        (finally
          (sp/close storage))))))


(deftest cache-overwrites-existing-integration-test
  (testing "save-cache! overwrites existing cache"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "v1"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              make-graph (fn [fn-name]
                           {:fns {fn-id {:id fn-id
                                         :name fn-name
                                         :fn-schema-id fn-schema-id
                                         :parent-fn-id nil}}
                            :fn-schemas {fn-schema-id {:id fn-schema-id
                                                       :name "schema"
                                                       :base-fn-name "base"
                                                       :returned-type :text}}
                            :arg-schemas {}
                            :resolved-args {}
                            :fn-result-values {}})
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          ;; Save v1
          (cache/save-cache! cache fn-id (make-graph "v1") deps)
          (is (= "v1" (get-in (cache/get-cached-graph cache fn-id) [:fns fn-id :name])))
          ;; Save v2 (should overwrite)
          (cache/save-cache! cache fn-id (make-graph "v2") deps)
          (is (= "v2" (get-in (cache/get-cached-graph cache fn-id) [:fns fn-id :name]))))
        (finally
          (sp/close storage))))))


(deftest get-cached-graph-nonexistent-integration-test
  (testing "get-cached-graph returns nil for non-existent cache"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-id (random-uuid)]
          (is (nil? (cache/get-cached-graph cache fn-id))))
        (finally
          (sp/close storage))))))
