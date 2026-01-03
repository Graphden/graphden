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
                                     :type :uuid}
                      :parent-fn-id {:uuid #uuid "10000000-0000-0000-0000-000000000023"
                                     :type :uuid
                                     :nullable? true}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg-value entity
      (ds/add-entity :arg-value #uuid "10000000-0000-0000-0000-000000000030"
                     {:owner-fn-id {:uuid #uuid "10000000-0000-0000-0000-000000000031"
                                    :type :uuid}
                      :arg-schema-id {:uuid #uuid "10000000-0000-0000-0000-000000000032"
                                      :type :uuid}
                      :value {:uuid #uuid "10000000-0000-0000-0000-000000000033"
                              :type :text}})
      (ds/add-constraint :arg-value {:type :unique :fields [:owner-fn-id :arg-schema-id]})
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

  (testing "validate-parent-same-schema! contract"

    (testing "allows nil parent"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                fn-rec (sp/create-entity storage :fn
                                         {:name "fn-no-parent"
                                          :fn-schema-id (:id schema)})]
            ;; Should not throw when parent is nil
            (is (nil? (sp/validate-parent-same-schema! storage (:id fn-rec) nil))))
          (finally
            (close-storage-fn storage)))))

    (testing "allows parent with same schema"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                parent-fn (sp/create-entity storage :fn
                                            {:name "parent-fn"
                                             :fn-schema-id (:id schema)})
                child-fn (sp/create-entity storage :fn
                                           {:name "child-fn"
                                            :fn-schema-id (:id schema)})]
            ;; Should not throw when schemas match
            (is (nil? (sp/validate-parent-same-schema! storage (:id child-fn) (:id parent-fn)))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects parent with different schema"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema-a (sp/create-entity storage :fn-schema
                                           {:name "schema-a" :returned-type "int"})
                schema-b (sp/create-entity storage :fn-schema
                                           {:name "schema-b" :returned-type "text"})
                parent-fn (sp/create-entity storage :fn
                                            {:name "parent-fn"
                                             :fn-schema-id (:id schema-a)})
                child-fn (sp/create-entity storage :fn
                                           {:name "child-fn"
                                            :fn-schema-id (:id schema-b)})]
            ;; Should throw when schemas differ
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"different fn-schema-id"
                  (sp/validate-parent-same-schema! storage (:id child-fn) (:id parent-fn)))))
          (finally
            (close-storage-fn storage))))))


  (testing "validate-no-arg-override! contract"

    (testing "allows arg not in parent chain"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                arg-x (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id schema)
                                         :name "x"
                                         :type "int"
                                         :required true})
                arg-y (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id schema)
                                         :name "y"
                                         :type "int"
                                         :required true})
                parent-fn (sp/create-entity storage :fn
                                            {:name "parent-fn"
                                             :fn-schema-id (:id schema)})
                _ (sp/create-entity storage :arg-value
                                    {:owner-fn-id (:id parent-fn)
                                     :arg-schema-id (:id arg-x)
                                     :value "1"})
                child-fn (sp/create-entity storage :fn
                                           {:name "child-fn"
                                            :fn-schema-id (:id schema)
                                            :parent-fn-id (:id parent-fn)})]
            ;; Should allow setting arg-y on child (not defined in parent)
            (is (nil? (sp/validate-no-arg-override! storage (:id child-fn) (:id arg-y)))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects arg already defined in parent chain"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                arg-x (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id schema)
                                         :name "x"
                                         :type "int"
                                         :required true})
                parent-fn (sp/create-entity storage :fn
                                            {:name "parent-fn"
                                             :fn-schema-id (:id schema)})
                _ (sp/create-entity storage :arg-value
                                    {:owner-fn-id (:id parent-fn)
                                     :arg-schema-id (:id arg-x)
                                     :value "1"})
                child-fn (sp/create-entity storage :fn
                                           {:name "child-fn"
                                            :fn-schema-id (:id schema)
                                            :parent-fn-id (:id parent-fn)})]
            ;; Should reject setting arg-x on child (already defined in parent)
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"already defined"
                  (sp/validate-no-arg-override! storage (:id child-fn) (:id arg-x)))))
          (finally
            (close-storage-fn storage))))))


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


  (testing "validate-no-inheritance-cycle! contract"

    (testing "allows nil parent"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                fn-rec (sp/create-entity storage :fn
                                         {:name "test-fn"
                                          :fn-schema-id (:id schema)})]
            ;; Should allow nil parent
            (is (nil? (sp/validate-no-inheritance-cycle! storage (:id fn-rec) nil))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects self as parent"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                fn-rec (sp/create-entity storage :fn
                                         {:name "test-fn"
                                          :fn-schema-id (:id schema)})]
            ;; Should reject self as parent
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"self as parent|cycle"
                  (sp/validate-no-inheritance-cycle! storage (:id fn-rec) (:id fn-rec)))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects cycle in parent chain"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [schema (sp/create-entity storage :fn-schema
                                         {:name "test-schema" :returned-type "int"})
                ;; Create A -> B -> C chain
                fn-a (sp/create-entity storage :fn
                                       {:name "fn-a"
                                        :fn-schema-id (:id schema)})
                fn-b (sp/create-entity storage :fn
                                       {:name "fn-b"
                                        :fn-schema-id (:id schema)
                                        :parent-fn-id (:id fn-a)})
                fn-c (sp/create-entity storage :fn
                                       {:name "fn-c"
                                        :fn-schema-id (:id schema)
                                        :parent-fn-id (:id fn-b)})]
            ;; Trying to make A's parent = C would create cycle
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"cycle"
                  (sp/validate-no-inheritance-cycle! storage (:id fn-a) (:id fn-c)))))
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
            (close-storage-fn storage)))))))
