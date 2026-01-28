(ns graphden.storage-protocol.contract-tests
  "Contract tests for GraphConstraints protocol.
   These tests verify that any storage implementation correctly enforces
   graph integrity constraints. Each storage module should run these tests
   with their specific storage factory function.

   Usage in storage implementation tests:
   ```clojure
   (require '[graphden.storage-protocol.contract-tests :as contract])
   (contract/run-graph-constraints-tests
     (fn [] (my-storage/create-storage ...))
     #(my-storage/close-storage %))
   ```"
  (:require
    [clojure.test :refer [is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


;; === Schema helper ===

(def ^:private graph-schema
  "Schema for graph constraint testing.
   Uses normalized schema where arg-value has no owner, and fn-arg binds fn to arg-value.
   Note: Uses :uuid type for foreign keys instead of :ref to be compatible
   with all storage backends. Datomic's :db.type/ref requires special handling
   that is not yet implemented consistently across all stores."
  (-> (mds/create-builder)
      ;; fn-schema entity
      (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                     {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                      :type :text}
                      :base-fn-name {:uuid #uuid "10000000-0000-0000-0000-000000000004"
                                     :type :text
                                     :nullable? true}})
      (ds/add-constraint :fn-schema {:type :unique :fields [:name]})

      ;; arg-schema entity
      (ds/add-entity :arg-schema #uuid "10000000-0000-0000-0000-000000000010"
                     {:fn-schema-id {:uuid #uuid "10000000-0000-0000-0000-000000000011"
                                     :type :uuid}
                      :name {:uuid #uuid "10000000-0000-0000-0000-000000000012"
                             :type :text}
                      :type {:uuid #uuid "10000000-0000-0000-0000-000000000013"
                             :type :text}
                      :required {:uuid #uuid "10000000-0000-0000-0000-000000000014"
                                 :type :bool}})
      (ds/add-constraint :arg-schema {:type :unique :fields [:fn-schema-id :name]})

      ;; fn entity
      (ds/add-entity :fn #uuid "10000000-0000-0000-0000-000000000020"
                     {:name {:uuid #uuid "10000000-0000-0000-0000-000000000021"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "10000000-0000-0000-0000-000000000022"
                                     :type :uuid}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg-value entity (pure value, no owner-fn-id)
      (ds/add-entity :arg-value #uuid "10000000-0000-0000-0000-000000000030"
                     {:arg-schema-id {:uuid #uuid "10000000-0000-0000-0000-000000000032"
                                      :type :uuid}
                      :value {:uuid #uuid "10000000-0000-0000-0000-000000000033"
                              :type :text}})
      (ds/add-constraint :arg-value {:type :unique :fields [:arg-schema-id :value]})

      ;; fn-arg entity (binding: fn -> arg-value)
      (ds/add-entity :fn-arg #uuid "10000000-0000-0000-0000-000000000040"
                     {:fn-id {:uuid #uuid "10000000-0000-0000-0000-000000000041"
                              :type :uuid}
                      :arg-schema-id {:uuid #uuid "10000000-0000-0000-0000-000000000042"
                                      :type :uuid}
                      :arg-value-id {:uuid #uuid "10000000-0000-0000-0000-000000000043"
                                     :type :uuid}})
      (ds/add-constraint :fn-arg {:type :unique :fields [:fn-id :arg-schema-id]})
      ds/build))


;; === Contract test runner ===

(defn run-graph-constraints-tests
  "Runs all GraphConstraints contract tests against a storage.

   Arguments:
   - create-storage-fn: Zero-arg function that creates and returns a storage instance
   - close-storage-fn: One-arg function that closes the storage

   Example:
   ```clojure
   (run-graph-constraints-tests
     #(pg/create-storage {...})
     sp/close)
   ```"
  [create-storage-fn close-storage-fn]

  (testing "validate-arg-schema-belongs-to-fn! contract"

    (testing "allows arg-schema that belongs to fn's schema"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                arg (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id schema)
                                       :name "x"
                                       :type "int"
                                       :required true})
                fn-rec (sp/create-entity storage :fn
                                         {:name "test-fn"
                                          :fn-schema-id (:id schema)})]
            ;; Should allow arg-schema that belongs to the fn's schema
            (is (nil? (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-rec) (:id arg)))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects arg-schema from different schema"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema-a (sp/create-entity storage :fn-schema
                                           {:name "schema-a" :returned-type "int"})
                schema-b (sp/create-entity storage :fn-schema
                                           {:name "schema-b" :returned-type "text"})
                arg-from-a (sp/create-entity storage :arg-schema
                                             {:fn-schema-id (:id schema-a)
                                              :name "x"
                                              :type "int"
                                              :required true})
                fn-with-b (sp/create-entity storage :fn
                                            {:name "fn-with-b"
                                             :fn-schema-id (:id schema-b)})]
            ;; Should reject arg-schema that doesn't belong to fn's schema
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"does not belong"
                  (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-with-b) (:id arg-from-a)))))
          (finally
            (close-storage-fn storage))))))


  ;; Note: validate-no-dependency-cycle! tests are NOT included here because
  ;; they require specific schema setup for the :value field (JSONB/union type)
  ;; that differs between storage implementations. Each storage has its own
  ;; dependency cycle tests with appropriate schema configuration.

  (testing "validate-no-dependency-cycle! contract - basic tests"

    (testing "allows nil value-fn"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                fn-rec (sp/create-entity storage :fn
                                         {:name "test-fn"
                                          :fn-schema-id (:id schema)})]
            ;; Should allow nil value (literal, not a fn reference)
            (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-rec) nil))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects self-reference as cycle"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                fn-rec (sp/create-entity storage :fn
                                         {:name "test-fn"
                                          :fn-schema-id (:id schema)})]
            ;; Self-reference is a cycle at storage level
            ;; Recursion is handled at executor level via lazy evaluation
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"cycle"
                  (sp/validate-no-dependency-cycle! storage (:id fn-rec) (:id fn-rec)))))
          (finally
            (close-storage-fn storage))))))


  ;; === Schema mismatch tests ===

  (testing "arg-schema from different schema - complex case"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema-a (sp/create-entity storage :fn-schema
                                         {:name "schema-a" :returned-type "int"})
              schema-b (sp/create-entity storage :fn-schema
                                         {:name "schema-b" :returned-type "text"})
              ;; arg belongs to schema-a
              arg-from-a (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id schema-a)
                                            :name "x"
                                            :type "int"
                                            :required true})
              ;; fn uses schema-b
              fn-with-b (sp/create-entity storage :fn
                                          {:name "fn-using-schema-b"
                                           :fn-schema-id (:id schema-b)})]
          ;; Cannot use arg from schema-a in fn with schema-b
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"does not belong"
                (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-with-b) (:id arg-from-a)))))
        (finally
          (close-storage-fn storage)))))


  ;; === Non-existent entity handling ===
  ;; Note: validate-no-dependency-cycle! with non-existent entities is tested
  ;; in storage-specific tests because it requires JSONB/union type for value field
  ;; which differs between storage implementations.
  )


(defn concurrent-read-write-test
  "Tests that concurrent reads and writes don't produce stale or corrupt data.
   This is a contract test - implementations must handle concurrency safely."
  [create-storage-fn close-storage-fn]
  (testing "concurrent reads during write don't see partial state"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "concurrent-schema" :returned-type "int"})
              ;; Create initial fn
              fn-record (sp/create-entity storage :fn
                                          {:name "concurrent-fn"
                                           :fn-schema-id (:id schema)})
              fn-id (:id fn-record)
              ;; Run concurrent reads while updating
              read-results (atom [])
              update-done (promise)
              num-readers 5]
          ;; Start readers
          (dotimes [_ num-readers]
            (future
              (dotimes [_ 10]
                (when-let [result (sp/read-entity storage :fn fn-id)]
                  (swap! read-results conj result))
                (Thread/sleep 1))))
          ;; Perform update
          (sp/update-entity storage :fn fn-id {:name "updated-concurrent-fn"})
          (deliver update-done true)
          ;; Wait for readers
          (Thread/sleep 100)
          ;; All reads should be valid (either old or new name, never partial)
          (doseq [result @read-results]
            (is (contains? #{"concurrent-fn" "updated-concurrent-fn"} (:name result))
                "Read should return complete record, not partial state")))
        (finally
          (close-storage-fn storage)))))

  (testing "batch create maintains consistency under concurrent access"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "batch-schema" :returned-type "int"})
              ;; Create fns concurrently from multiple threads
              results (atom [])
              num-threads 3
              fns-per-thread 5
              latch (java.util.concurrent.CountDownLatch. num-threads)]
          (dotimes [t num-threads]
            (future
              (try
                (let [fns (for [i (range fns-per-thread)]
                            {:name (str "batch-fn-" t "-" i)
                             :fn-schema-id (:id schema)})]
                  (swap! results concat (sp/create-entities storage :fn fns)))
                (finally
                  (java.util.concurrent.CountDownLatch/.countDown latch)))))
          (java.util.concurrent.CountDownLatch/.await latch 5000 java.util.concurrent.TimeUnit/MILLISECONDS)
          ;; All fns should be created with unique IDs
          (is (= (* num-threads fns-per-thread) (count @results))
              "All batch creates should succeed")
          (is (= (count @results) (count (set (map :id @results))))
              "All IDs should be unique"))
        (finally
          (close-storage-fn storage))))))
