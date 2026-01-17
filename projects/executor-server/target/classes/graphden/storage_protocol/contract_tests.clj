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
            (close-storage-fn storage))))))


  ;; === Deep inheritance chain tests (5+ levels) ===

  (testing "deep inheritance chain - 5 level parent chain"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "deep-schema" :returned-type "int"})
              ;; Create chain: fn-1 <- fn-2 <- fn-3 <- fn-4 <- fn-5
              fn-1 (sp/create-entity storage :fn
                                     {:name "fn-level-1"
                                      :fn-schema-id (:id schema)})
              fn-2 (sp/create-entity storage :fn
                                     {:name "fn-level-2"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-1)})
              fn-3 (sp/create-entity storage :fn
                                     {:name "fn-level-3"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-2)})
              fn-4 (sp/create-entity storage :fn
                                     {:name "fn-level-4"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-3)})
              fn-5 (sp/create-entity storage :fn
                                     {:name "fn-level-5"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-4)})]
          ;; Deep chain cycle: fn-1 trying to have fn-5 as parent creates cycle
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"cycle"
                (sp/validate-no-inheritance-cycle! storage (:id fn-1) (:id fn-5))))
          ;; fn-2 trying to have fn-5 as parent also creates cycle
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"cycle"
                (sp/validate-no-inheritance-cycle! storage (:id fn-2) (:id fn-5))))
          ;; fn-3 trying to have fn-5 as parent creates cycle
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"cycle"
                (sp/validate-no-inheritance-cycle! storage (:id fn-3) (:id fn-5))))
          ;; But fn-5 can have a new sibling at any level
          (is (nil? (sp/validate-no-inheritance-cycle! storage (random-uuid) (:id fn-4)))))
        (finally
          (close-storage-fn storage)))))


  (testing "deep inheritance chain - arg override detection at depth 5"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "deep-schema-args" :returned-type "int"})
              arg-x (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id schema)
                                       :name "x"
                                       :type "int"
                                       :required true})
              ;; Create deep chain
              fn-1 (sp/create-entity storage :fn
                                     {:name "fn-depth-1"
                                      :fn-schema-id (:id schema)})
              ;; Define arg-x at root level
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id fn-1)
                                   :arg-schema-id (:id arg-x)
                                   :value "root-value"})
              fn-2 (sp/create-entity storage :fn
                                     {:name "fn-depth-2"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-1)})
              fn-3 (sp/create-entity storage :fn
                                     {:name "fn-depth-3"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-2)})
              fn-4 (sp/create-entity storage :fn
                                     {:name "fn-depth-4"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-3)})
              fn-5 (sp/create-entity storage :fn
                                     {:name "fn-depth-5"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-4)})]
          ;; Trying to define arg-x at level 5 should fail (already defined at level 1)
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"already defined"
                (sp/validate-no-arg-override! storage (:id fn-5) (:id arg-x))))
          ;; Also at level 3
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"already defined"
                (sp/validate-no-arg-override! storage (:id fn-3) (:id arg-x)))))
        (finally
          (close-storage-fn storage)))))


  ;; === Diamond pattern tests ===

  (testing "diamond pattern - two branches from common ancestor"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "diamond-schema" :returned-type "int"})
              arg-a (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id schema)
                                       :name "a"
                                       :type "int"
                                       :required true})
              arg-b (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id schema)
                                       :name "b"
                                       :type "int"
                                       :required true})
              ;; Diamond: root -> (left, right)
              ;;          left -> bottom
              ;;          right -> (not connected to bottom)
              root (sp/create-entity storage :fn
                                     {:name "diamond-root"
                                      :fn-schema-id (:id schema)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id root)
                                   :arg-schema-id (:id arg-a)
                                   :value "root-a"})
              left (sp/create-entity storage :fn
                                     {:name "diamond-left"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id root)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id left)
                                   :arg-schema-id (:id arg-b)
                                   :value "left-b"})
              right (sp/create-entity storage :fn
                                      {:name "diamond-right"
                                       :fn-schema-id (:id schema)
                                       :parent-fn-id (:id root)})]
          ;; Left branch already defines arg-a (from root) and arg-b
          ;; A child of left cannot redefine arg-a or arg-b
          (let [bottom (sp/create-entity storage :fn
                                         {:name "diamond-bottom"
                                          :fn-schema-id (:id schema)
                                          :parent-fn-id (:id left)})]
            ;; Cannot redefine arg-a (defined in root, ancestor of left)
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"already defined"
                  (sp/validate-no-arg-override! storage (:id bottom) (:id arg-a))))
            ;; Cannot redefine arg-b (defined in left, direct parent)
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"already defined"
                  (sp/validate-no-arg-override! storage (:id bottom) (:id arg-b)))))
          ;; Right branch only has arg-a from root, can define arg-b
          (is (nil? (sp/validate-no-arg-override! storage (:id right) (:id arg-b)))))
        (finally
          (close-storage-fn storage)))))


  ;; === Schema mismatch with valid parent chain ===

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


  ;; === Multiple siblings with shared parent ===

  (testing "multiple siblings share parent args correctly"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "sibling-schema" :returned-type "int"})
              arg-shared (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id schema)
                                            :name "shared"
                                            :type "int"
                                            :required true})
              arg-unique (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id schema)
                                            :name "unique"
                                            :type "int"
                                            :required true})
              parent (sp/create-entity storage :fn
                                       {:name "parent-fn"
                                        :fn-schema-id (:id schema)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id parent)
                                   :arg-schema-id (:id arg-shared)
                                   :value "shared-value"})
              sibling-1 (sp/create-entity storage :fn
                                          {:name "sibling-1"
                                           :fn-schema-id (:id schema)
                                           :parent-fn-id (:id parent)})
              sibling-2 (sp/create-entity storage :fn
                                          {:name "sibling-2"
                                           :fn-schema-id (:id schema)
                                           :parent-fn-id (:id parent)})
              sibling-3 (sp/create-entity storage :fn
                                          {:name "sibling-3"
                                           :fn-schema-id (:id schema)
                                           :parent-fn-id (:id parent)})]
          ;; None of the siblings can redefine shared arg
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"already defined"
                (sp/validate-no-arg-override! storage (:id sibling-1) (:id arg-shared))))
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"already defined"
                (sp/validate-no-arg-override! storage (:id sibling-2) (:id arg-shared))))
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"already defined"
                (sp/validate-no-arg-override! storage (:id sibling-3) (:id arg-shared))))
          ;; But all can define the unique arg independently
          (is (nil? (sp/validate-no-arg-override! storage (:id sibling-1) (:id arg-unique))))
          ;; After sibling-1 defines it, sibling-2 can still define it (not in parent chain)
          (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id sibling-1)
                             :arg-schema-id (:id arg-unique)
                             :value "sibling-1-unique"})
          (is (nil? (sp/validate-no-arg-override! storage (:id sibling-2) (:id arg-unique)))))
        (finally
          (close-storage-fn storage)))))


  ;; === Edge cases for cycle detection ===

  (testing "cycle detection - indirect cycle through middle of chain"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "indirect-cycle-schema" :returned-type "int"})
              ;; Chain: a -> b -> c -> d
              fn-a (sp/create-entity storage :fn
                                     {:name "indirect-a"
                                      :fn-schema-id (:id schema)})
              fn-b (sp/create-entity storage :fn
                                     {:name "indirect-b"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-a)})
              fn-c (sp/create-entity storage :fn
                                     {:name "indirect-c"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-b)})
              fn-d (sp/create-entity storage :fn
                                     {:name "indirect-d"
                                      :fn-schema-id (:id schema)
                                      :parent-fn-id (:id fn-c)})]
          ;; Setting b's parent to d would create: a -> b -> c -> d -> b (cycle)
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"cycle"
                (sp/validate-no-inheritance-cycle! storage (:id fn-b) (:id fn-d))))
          ;; Setting a's parent to c would create: a -> b -> c -> a (cycle)
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"cycle"
                (sp/validate-no-inheritance-cycle! storage (:id fn-a) (:id fn-c)))))
        (finally
          (close-storage-fn storage)))))


  ;; === Non-existent entity handling ===

  (testing "validation with non-existent entities"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "exists-schema" :returned-type "int"})
              existing-fn (sp/create-entity storage :fn
                                            {:name "existing-fn"
                                             :fn-schema-id (:id schema)})
              non-existent-id (random-uuid)]
          ;; These should not throw (graceful handling of non-existent entities)
          ;; The validation implementations should handle missing entities
          ;; by either returning nil or treating them as having no constraints
          (is (nil? (sp/validate-no-inheritance-cycle! storage (:id existing-fn) non-existent-id)))
          ;; Note: validate-no-dependency-cycle! with non-existent entities is tested
          ;; in storage-specific tests because it requires JSONB/union type for value field
          )
        (finally
          (close-storage-fn storage))))))


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


(defn deep-inheritance-chain-test
  "Tests that deep inheritance chains (100+ levels) are handled correctly.
   This is a contract test - implementations must handle deep chains."
  [create-storage-fn close-storage-fn]
  (testing "deep inheritance chain resolution"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [schema (sp/create-entity storage :fn-schema
                                       {:name "deep-schema" :returned-type "int"})
              chain-depth 100
              ;; Create chain: fn-0 <- fn-1 <- fn-2 <- ... <- fn-99
              fn-ids (reduce
                       (fn [ids i]
                         (let [parent-id (last ids)
                               new-fn (sp/create-entity storage :fn
                                                        (cond-> {:name (str "chain-fn-" i)
                                                                 :fn-schema-id (:id schema)}
                                                          parent-id (assoc :parent-fn-id parent-id)))]
                           (conj ids (:id new-fn))))
                       []
                       (range chain-depth))
              ;; Read the deepest fn
              deepest-fn (sp/read-entity storage :fn (last fn-ids))]
          (is (some? deepest-fn) "Should read deepest fn in chain")
          (is (= (str "chain-fn-" (dec chain-depth)) (:name deepest-fn)))
          ;; Validate no cycle in deep chain
          (is (nil? (sp/validate-no-inheritance-cycle!
                      storage (last fn-ids) (first fn-ids)))
              "Deep chain should not be detected as cycle"))
        (finally
          (close-storage-fn storage))))))
