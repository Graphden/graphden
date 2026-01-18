(ns graphden.memory-storage.execution-graph-test
  "Tests for memory storage ExecutionGraph protocol.

   Covers:
   - resolve-execution-graph with simple functions
   - resolve-execution-graph with parent inheritance
   - resolve-execution-graph with function references
   - resolve-execution-graph edge cases (not found, non-uuid values, etc.)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


;; === ExecutionGraph tests ===

(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-enum :value-kind #uuid "b79e6e8b-8aff-4188-862b-d8a85ef4fcdf"
                   [{:uuid #uuid "c703ffd9-6401-4c49-9ca3-a280f6aac8ba" :value :null}
                    {:uuid #uuid "cf26384f-d093-461d-9268-b42b8fd6eae6" :value :text}
                    {:uuid #uuid "154d3c4f-8d11-4592-9e24-5c40176cc5a7" :value :int}])
      (ds/add-entity :fn-schema #uuid "dc2df695-6167-4add-9e75-022213c96537"
                     {:name {:uuid #uuid "abe8475e-9130-4647-a2bf-be0cb07099b7" :type :text}
                      :returned-type {:uuid #uuid "5ea6c13d-553c-4d85-8511-38ae88f7f9e5"
                                      :type :enum :enum-name :value-kind}})
      (ds/add-entity :arg-schema #uuid "946c1f9c-30ce-4fab-98ed-dd9a26f6676b"
                     {:fn-schema-id {:uuid #uuid "c100ed37-f3d8-4a93-becc-17ae2b91f64a"
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid #uuid "e68c993e-7840-4541-b55f-cf4b08ba3de7" :type :text}
                      :type {:uuid #uuid "be65f37b-4758-49da-9091-37dee0e28ad1"
                             :type :enum :enum-name :value-kind}
                      :required {:uuid #uuid "a1d4e8c2-5f67-4b3a-9c12-8e0f7d6b5a4c" :type :bool}})
      (ds/add-entity :fn #uuid "986e8a2a-39ba-41ae-8449-d06c31515486"
                     {:name {:uuid #uuid "af336498-6d1e-4879-b2a5-b0d6c1994d12" :type :text}
                      :fn-schema-id {:uuid #uuid "3a685253-07f7-4469-be8b-1a585ba3e7d4"
                                     :type :ref :ref-entity :fn-schema}
                      :parent-fn-id {:uuid #uuid "7c8e2f4a-9b31-4d56-a8e7-3f2c1b5d9a0e"
                                     :type :ref :ref-entity :fn :nullable? true}})
      (ds/add-entity :arg-value #uuid "afb02fb7-0174-496b-9b21-a61063de0c04"
                     {:owner-fn-id {:uuid #uuid "d9331598-36b3-4238-83f8-16558d8b3a7e"
                                    :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "834336b1-b55c-4557-b580-a62799deb729"
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid #uuid "b6780ba3-d050-4162-aba8-5f68ac17bcb8" :type :jsonb}})
      ds/build))


(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      ;; Create fn-schema
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "add" :returned-type :int})
            ;; Create arg-schemas
            arg-a (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "a" :type :int :required true})
            arg-b (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "b" :type :int :required true})
            ;; Create fn instance
            fn-add (sp/create-entity storage :fn
                                     {:name "add-1-2"
                                      :fn-schema-id (:id fn-schema)
                                      :parent-fn-id nil})
            ;; Create arg-values
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-a)
                                 :value 1})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-b)
                                 :value 2})
            ;; Resolve execution graph
            graph (sp/resolve-execution-graph storage (:id fn-add))]
        ;; Check structure
        (is (contains? (:fns graph) (:id fn-add)))
        (is (contains? (:fn-schemas graph) (:id fn-schema)))
        (is (contains? (:arg-schemas graph) (:id arg-a)))
        (is (contains? (:arg-schemas graph) (:id arg-b)))
        (is (contains? (:resolved-args graph) (:id fn-add)))
        ;; Check resolved args
        (let [args (get (:resolved-args graph) (:id fn-add))]
          (is (= 1 (:value (get args (:id arg-a)))))
          (is (= 2 (:value (get args (:id arg-b))))))))))


(deftest resolve-execution-graph-with-parent-test
  (testing "resolves function with parent chain - child overrides parent"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type :text})
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type :text :required true})
            arg-greeting (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "greeting" :type :text :required true})
            ;; Parent fn with greeting="Hello"
            parent-fn (sp/create-entity storage :fn
                                        {:name "greet-hello"
                                         :fn-schema-id (:id fn-schema)
                                         :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id parent-fn)
                                 :arg-schema-id (:id arg-greeting)
                                 :value "Hello"})
            ;; Child fn with name="World" - inherits greeting from parent
            child-fn (sp/create-entity storage :fn
                                       {:name "greet-hello-world"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id parent-fn)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id child-fn)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id child-fn))]
        ;; Both fns should be in graph
        (is (contains? (:fns graph) (:id child-fn)))
        ;; Resolved args should have both - name from child, greeting from parent
        (let [args (get (:resolved-args graph) (:id child-fn))]
          (is (= "World" (:value (get args (:id arg-name)))))
          (is (= "Hello" (:value (get args (:id arg-greeting))))))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type :int})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type :int :required true})
            ;; add schema
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type :int})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type :int :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type :int :required true})
            ;; const-3 fn
            const-3 (sp/create-entity storage :fn
                                      {:name "const-3"
                                       :fn-schema-id (:id const-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-3)
                                 :arg-schema-id (:id const-arg)
                                 :value 3})
            ;; const-5 fn
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value 5})
            ;; add-3-5 fn referencing const-3 and const-5
            add-3-5 (sp/create-entity storage :fn
                                      {:name "add-3-5"
                                       :fn-schema-id (:id add-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (:id const-3)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (:id const-5)})
            graph (sp/resolve-execution-graph storage (:id add-3-5))]
        ;; All 3 fns should be in graph
        (is (= 3 (count (:fns graph))))
        (is (contains? (:fns graph) (:id add-3-5)))
        (is (contains? (:fns graph) (:id const-3)))
        (is (contains? (:fns graph) (:id const-5)))
        ;; Both schemas
        (is (= 2 (count (:fn-schemas graph))))
        ;; All arg-schemas
        (is (= 3 (count (:arg-schemas graph))))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
            (sp/resolve-execution-graph storage (random-uuid)))))))


(deftest resolve-execution-graph-with-optional-args-test
  (testing "handles optional (non-required) arguments that are not provided"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type :text})
            ;; Required arg
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type :text :required true})
            ;; Optional arg (not required)
            arg-suffix (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id fn-schema)
                                          :name "suffix" :type :text :required false})
            fn-rec (sp/create-entity storage :fn
                                     {:name "greet-fn"
                                      :fn-schema-id (:id fn-schema)})
            ;; Only provide required arg, not optional
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-rec)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id fn-rec))]
        (is (= 1 (count (:fns graph))))
        (let [args (get (:resolved-args graph) (:id fn-rec))]
          ;; Required arg should be present
          (is (= "World" (:value (get args (:id arg-name)))))
          ;; Optional arg should not be present (no arg-value for it)
          (is (nil? (get args (:id arg-suffix)))))))))


(deftest resolve-execution-graph-with-non-fn-uuid-value-test
  (testing "UUID value that doesn't exist as fn is not followed as reference"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "process" :returned-type :text})
            arg-ref (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "ref" :type :uuid :required true})
            fn-rec (sp/create-entity storage :fn
                                     {:name "my-process"
                                      :fn-schema-id (:id fn-schema)})
            ;; Store a random UUID that doesn't exist as a fn
            non-existent-fn-id (random-uuid)
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-rec)
                                 :arg-schema-id (:id arg-ref)
                                 :value non-existent-fn-id})
            graph (sp/resolve-execution-graph storage (:id fn-rec))]
        ;; Should only have 1 fn (the non-existent UUID is not resolved)
        (is (= 1 (count (:fns graph))))
        (let [args (get (:resolved-args graph) (:id fn-rec))]
          (is (= non-existent-fn-id (:value (get args (:id arg-ref))))))))))


(deftest query-entities-with-empty-where-test
  (testing "query-entities with empty where returns all records"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :item #uuid "11111111-1111-1111-1111-111111111111"
                                    {:name {:uuid #uuid "22222222-2222-2222-2222-222222222222"
                                            :type :text}})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :item {:name "item1"})
      (sp/create-entity storage :item {:name "item2"})
      (sp/create-entity storage :item {:name "item3"})
      (let [all-items (sp/query-entities storage :item {})]
        (is (= 3 (count all-items)))
        (is (= #{"item1" "item2" "item3"} (set (map :name all-items))))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type :int})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type :int :required true})
            ;; add schema - both args reference fns
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type :int})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type :int :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type :int :required true})
            ;; const-5 fn - will be referenced TWICE
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value 5})
            ;; add-5-5 fn referencing const-5 for BOTH args
            ;; This creates a shared reference that triggers the "already visited" branch
            add-5-5 (sp/create-entity storage :fn
                                      {:name "add-5-5"
                                       :fn-schema-id (:id add-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (:id const-5)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (:id const-5)}) ; Same fn referenced again!
            graph (sp/resolve-execution-graph storage (:id add-5-5))]
        ;; const-5 should only appear once in the graph despite being referenced twice
        (is (= 2 (count (:fns graph))))
        (is (contains? (:fns graph) (:id add-5-5)))
        (is (contains? (:fns graph) (:id const-5)))
        ;; Both args should reference const-5
        (let [args (get (:resolved-args graph) (:id add-5-5))]
          (is (= (:id const-5) (:value (get args (:id add-arg-a)))))
          (is (= (:id const-5) (:value (get args (:id add-arg-b))))))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; recursive fn-schema with two args
            rec-schema (sp/create-entity storage :fn-schema
                                         {:name "recursive" :returned-type :int})
            ;; 'self' arg will reference the fn itself (for recursion)
            arg-self (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id rec-schema)
                                        :name "self" :type :fn :required true}) ; :fn type
            arg-n (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id rec-schema)
                                     :name "n" :type :int :required true})
            ;; Create fn instance that references itself
            rec-fn (sp/create-entity storage :fn
                                     {:name "factorial"
                                      :fn-schema-id (:id rec-schema)
                                      :parent-fn-id nil})
            ;; Self-reference: arg-value points to the fn itself
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-self)
                                 :value (:id rec-fn)}) ; Self-reference!
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-n)
                                 :value 5})
            graph (sp/resolve-execution-graph storage (:id rec-fn))]
        ;; Should only have 1 fn (self-reference doesn't create duplicate)
        (is (= 1 (count (:fns graph))))
        (is (contains? (:fns graph) (:id rec-fn)))
        ;; Self arg should reference the same fn
        (let [args (get (:resolved-args graph) (:id rec-fn))]
          (is (= (:id rec-fn) (:value (get args (:id arg-self)))))
          (is (= 5 (:value (get args (:id arg-n))))))))))


(deftest unique-constraint-test
  (testing "single-field unique constraint"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}
                                     :name {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                            :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:email "alice@example.com" :name "Alice"})
      (sp/create-entity storage :user {:email "bob@example.com" :name "Bob"})
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :user {:email "alice@example.com" :name "Charlie"})))))

  (testing "unique constraint on update allows same record"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000011"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (let [user1 (sp/create-entity storage :user {:email "alice@example.com"})
            _user2 (sp/create-entity storage :user {:email "bob@example.com"})]
        (sp/update-entity storage :user (:id user1) {:email "alice@example.com"})
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Unique constraint violation"
              (sp/update-entity storage :user (:id user1) {:email "bob@example.com"}))))))

  (testing "nil values bypass unique constraint"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000021"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000022"
                                             :type :text
                                             :nullable? true}
                                     :name {:uuid #uuid "00000000-0000-0000-0000-000000000023"
                                            :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:email nil :name "Alice"})
      (sp/create-entity storage :user {:email nil :name "Bob"})
      (is (= 2 (count (sp/query-entities storage :user {}))))))

  (testing "multi-field unique constraint"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :order #uuid "00000000-0000-0000-0000-000000000031"
                                    {:user-id {:uuid #uuid "00000000-0000-0000-0000-000000000032"
                                               :type :uuid}
                                     :product-id {:uuid #uuid "00000000-0000-0000-0000-000000000033"
                                                  :type :uuid}
                                     :quantity {:uuid #uuid "00000000-0000-0000-0000-000000000034"
                                                :type :int}})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :product-id]})
                     ds/build)
          user-1 (random-uuid)
          user-2 (random-uuid)
          product-1 (random-uuid)
          product-2 (random-uuid)]
      (sp/initialize storage schema)
      ;; Same user, different products - OK
      (sp/create-entity storage :order {:user-id user-1 :product-id product-1 :quantity 1})
      (sp/create-entity storage :order {:user-id user-1 :product-id product-2 :quantity 2})
      ;; Different user, same product - OK
      (sp/create-entity storage :order {:user-id user-2 :product-id product-1 :quantity 3})
      ;; Same user AND same product - should fail
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :order {:user-id user-1 :product-id product-1 :quantity 5})))))

  (testing "multi-field unique constraint with null values is skipped"
    ;; When one of the fields in the constraint is nil, the constraint check is skipped
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :order #uuid "00000000-0000-0000-0000-000000000041"
                                    {:user-id {:uuid #uuid "00000000-0000-0000-0000-000000000042"
                                               :type :uuid
                                               :nullable? true}
                                     :product-id {:uuid #uuid "00000000-0000-0000-0000-000000000043"
                                                  :type :uuid
                                                  :nullable? true}
                                     :quantity {:uuid #uuid "00000000-0000-0000-0000-000000000044"
                                                :type :int}})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :product-id]})
                     ds/build)
          user-1 (random-uuid)
          product-1 (random-uuid)]
      (sp/initialize storage schema)
      ;; Create with user-id nil - should allow multiple
      (sp/create-entity storage :order {:user-id nil :product-id product-1 :quantity 1})
      (sp/create-entity storage :order {:user-id nil :product-id product-1 :quantity 2})
      ;; Create with product-id nil - should allow multiple
      (sp/create-entity storage :order {:user-id user-1 :product-id nil :quantity 3})
      (sp/create-entity storage :order {:user-id user-1 :product-id nil :quantity 4})
      ;; Create with both nil - should allow multiple
      (sp/create-entity storage :order {:user-id nil :product-id nil :quantity 5})
      (sp/create-entity storage :order {:user-id nil :product-id nil :quantity 6})
      (is (= 6 (count (sp/query-entities storage :order {}))))))

  (testing "multi-field unique constraint violation during update"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :order #uuid "00000000-0000-0000-0000-000000000051"
                                    {:user-id {:uuid #uuid "00000000-0000-0000-0000-000000000052"
                                               :type :uuid}
                                     :product-id {:uuid #uuid "00000000-0000-0000-0000-000000000053"
                                                  :type :uuid}
                                     :quantity {:uuid #uuid "00000000-0000-0000-0000-000000000054"
                                                :type :int}})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :product-id]})
                     ds/build)
          user-1 (random-uuid)
          user-2 (random-uuid)
          product-1 (random-uuid)
          product-2 (random-uuid)]
      (sp/initialize storage schema)
      ;; Create two orders
      (let [order-1 (sp/create-entity storage :order {:user-id user-1 :product-id product-1 :quantity 1})
            _ (sp/create-entity storage :order {:user-id user-2 :product-id product-2 :quantity 2})]
        ;; Update order-1 to have same user-id and product-id as order-2 - should fail
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Unique constraint violation"
              (sp/update-entity storage :order (:id order-1) {:user-id user-2 :product-id product-2})))))))


(deftest diamond-dependency-cycle-detection-test
  (testing "diamond pattern in dependency chain (A->B->D, A->C->D) - no cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      ;; Create diamond: A -> B -> D, A -> C -> D
      ;; When checking from A, both B and C will add D to to-visit
      ;; D will be visited first from one path, then skipped from the other (already-visited)
      (let [fn-d (sp/create-entity storage :fn {:name "d"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            fn-a (sp/create-entity storage :fn {:name "a"})
            ;; B -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-b)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; C -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-c)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; A -> B
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-b)})
            ;; A -> C
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-c)})
            ;; Create X to test diamond traversal
            fn-x (sp/create-entity storage :fn {:name "x"})]
        ;; This should NOT throw - diamond is not a cycle
        ;; Start from X and check adding a reference to A
        ;; This traverses the entire A subgraph including the diamond
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-x) (:id fn-a)))))))

  (testing "diamond pattern with cycle attempt detects correctly"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      ;; Create diamond: A -> B -> D, A -> C -> D, then try D -> A (creates cycle)
      (let [fn-d (sp/create-entity storage :fn {:name "d"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            fn-a (sp/create-entity storage :fn {:name "a"})
            ;; B -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-b)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; C -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-c)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; A -> B
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-b)})
            ;; A -> C
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-c)})]
        ;; Now try to add D -> A, which would create a cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-d) (:id fn-a))))))))


(deftest entity-without-constraints-test
  (testing "CRUD works on entity with no constraints defined"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000000041"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000042"
                                            :type :text}})
                     ;; No constraints added!
                     ds/build)]
      (sp/initialize storage schema)
      ;; Can create multiple entities with same values (no unique constraint)
      (sp/create-entity storage :item {:name "Same Name"})
      (sp/create-entity storage :item {:name "Same Name"})
      (sp/create-entity storage :item {:name "Same Name"})
      (is (= 3 (count (sp/query-entities storage :item {})))))))


(deftest multiple-unique-constraints-test
  (testing "entity with multiple unique constraints iterates through all constraints"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000051"
                                    {:username {:uuid #uuid "00000000-0000-0000-0000-000000000052"
                                                :type :text}
                                     :email {:uuid #uuid "00000000-0000-0000-0000-000000000053"
                                             :type :text}
                                     :phone {:uuid #uuid "00000000-0000-0000-0000-000000000054"
                                             :type :text :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:username]})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     (ds/add-constraint :user {:type :unique :fields [:phone]})
                     ds/build)]
      (sp/initialize storage schema)
      ;; Create first user
      (sp/create-entity storage :user {:username "alice" :email "alice@test.com" :phone "111"})
      (sp/create-entity storage :user {:username "bob" :email "bob@test.com" :phone "222"})
      (sp/create-entity storage :user {:username "charlie" :email "charlie@test.com" :phone nil})
      ;; Duplicate username should fail
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :user {:username "alice" :email "new@test.com" :phone nil})))
      ;; Duplicate email should fail
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :user {:username "david" :email "bob@test.com" :phone nil})))
      ;; Duplicate phone should fail
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :user {:username "eve" :email "eve@test.com" :phone "111"})))
      ;; Null phone is allowed multiple times
      (sp/create-entity storage :user {:username "frank" :email "frank@test.com" :phone nil})
      (is (= 4 (count (sp/query-entities storage :user {}))))))

  (testing "update with multiple unique constraints and multiple existing records"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000000061"
                                    {:code {:uuid #uuid "00000000-0000-0000-0000-000000000062"
                                            :type :text}
                                     :name {:uuid #uuid "00000000-0000-0000-0000-000000000063"
                                            :type :text}})
                     (ds/add-constraint :item {:type :unique :fields [:code]})
                     (ds/add-constraint :item {:type :unique :fields [:name]})
                     ds/build)]
      (sp/initialize storage schema)
      ;; Create multiple items
      (let [item1 (sp/create-entity storage :item {:code "A" :name "Alpha"})
            _item2 (sp/create-entity storage :item {:code "B" :name "Beta"})
            _item3 (sp/create-entity storage :item {:code "C" :name "Gamma"})]
        ;; Update item1 keeping same values - should work (exclude-id)
        (sp/update-entity storage :item (:id item1) {:code "A" :name "Alpha"})
        ;; Update item1 with new unique values
        (sp/update-entity storage :item (:id item1) {:code "A1" :name "Alpha1"})
        ;; Try to update item1 to conflict with item2's code
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Unique constraint violation"
              (sp/update-entity storage :item (:id item1) {:code "B"})))
        ;; Try to update item1 to conflict with item2's name
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Unique constraint violation"
              (sp/update-entity storage :item (:id item1) {:name "Beta"})))))))


(deftest unique-constraint-partial-fields-test
  (testing "unique constraint check skips when not all constraint fields are present"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :record #uuid "00000000-0000-0000-0000-000000000051"
                                    {:field-a {:uuid #uuid "00000000-0000-0000-0000-000000000052"
                                               :type :text
                                               :nullable? true}
                                     :field-b {:uuid #uuid "00000000-0000-0000-0000-000000000053"
                                               :type :text
                                               :nullable? true}
                                     :field-c {:uuid #uuid "00000000-0000-0000-0000-000000000054"
                                               :type :text
                                               :nullable? true}})
                     (ds/add-constraint :record {:type :unique :fields [:field-a :field-b]})
                     ds/build)]
      (sp/initialize storage schema)
      ;; First record: has both fields
      (sp/create-entity storage :record {:field-a "a1" :field-b "b1" :field-c "c1"})
      ;; Second record: only has field-a (field-b is nil) - bypasses constraint check
      (sp/create-entity storage :record {:field-a "a1" :field-b nil :field-c "c2"})
      ;; Third record: only has field-b (field-a is nil) - bypasses constraint check
      (sp/create-entity storage :record {:field-a nil :field-b "b1" :field-c "c3"})
      ;; All three records should be created successfully
      (is (= 3 (count (sp/query-entities storage :record {}))))))

  (testing "unique constraint check still enforces when all fields present"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :record #uuid "00000000-0000-0000-0000-000000000061"
                                    {:field-a {:uuid #uuid "00000000-0000-0000-0000-000000000062"
                                               :type :text}
                                     :field-b {:uuid #uuid "00000000-0000-0000-0000-000000000063"
                                               :type :text}})
                     (ds/add-constraint :record {:type :unique :fields [:field-a :field-b]})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :record {:field-a "a1" :field-b "b1"})
      ;; Different field-b, same field-a - OK
      (sp/create-entity storage :record {:field-a "a1" :field-b "b2"})
      ;; Same field-a AND field-b - should fail
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :record {:field-a "a1" :field-b "b1"})))))

  (testing "multiple records with varied constraint checks"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :product #uuid "00000000-0000-0000-0000-000000000071"
                                    {:sku {:uuid #uuid "00000000-0000-0000-0000-000000000072"
                                           :type :text}
                                     :name {:uuid #uuid "00000000-0000-0000-0000-000000000073"
                                            :type :text}})
                     (ds/add-constraint :product {:type :unique :fields [:sku]})
                     ds/build)]
      (sp/initialize storage schema)
      ;; Create multiple records with unique SKUs
      (sp/create-entity storage :product {:sku "SKU-001" :name "Product 1"})
      (sp/create-entity storage :product {:sku "SKU-002" :name "Product 2"})
      (sp/create-entity storage :product {:sku "SKU-003" :name "Product 3"})
      (sp/create-entity storage :product {:sku "SKU-004" :name "Product 4"})
      (sp/create-entity storage :product {:sku "SKU-005" :name "Product 5"})
      ;; All 5 products should be created
      (is (= 5 (count (sp/query-entities storage :product {}))))
      ;; Try to create duplicate SKU
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unique constraint violation"
            (sp/create-entity storage :product {:sku "SKU-003" :name "Duplicate"}))))))
