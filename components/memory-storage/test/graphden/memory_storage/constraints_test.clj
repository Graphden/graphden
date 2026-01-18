(ns graphden.memory-storage.constraints-test
  "Tests for memory storage GraphConstraints protocol.

   Covers:
   - validate-parent-same-schema!
   - validate-no-inheritance-cycle!
   - validate-no-arg-override!
   - validate-arg-schema-belongs-to-fn!
   - validate-no-dependency-cycle!
   - GraphConstraints contract tests
   - Required field validation"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.contract-tests :as contract]
    [graphden.storage-protocol.interface :as sp]))


;; === GraphConstraints tests ===

(deftest validate-parent-same-schema-test
  (testing "passes when parent has same fn-schema-id"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [schema-id (random-uuid)
            parent (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema-id :parent-fn-id nil})
            child (sp/create-entity storage :fn {:name "child" :fn-schema-id schema-id :parent-fn-id (:id parent)})]
        ;; Should not throw
        (sp/validate-parent-same-schema! storage (:id child) (:id parent)))))

  (testing "throws when parent has different fn-schema-id"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [schema1-id (random-uuid)
            schema2-id (random-uuid)
            parent (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema1-id :parent-fn-id nil})
            child (sp/create-entity storage :fn {:name "child" :fn-schema-id schema2-id :parent-fn-id (:id parent)})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different fn-schema-id"
              (sp/validate-parent-same-schema! storage (:id child) (:id parent))))))))


(deftest validate-no-inheritance-cycle-test
  (testing "passes when no cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn :nullable? true}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [a (sp/create-entity storage :fn {:name "a" :parent-fn-id nil})
            b (sp/create-entity storage :fn {:name "b" :parent-fn-id (:id a)})]
        ;; c -> b -> a: no cycle
        (sp/validate-no-inheritance-cycle! storage (random-uuid) (:id b)))))

  (testing "throws when self-reference"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [a (sp/create-entity storage :fn {:name "a" :parent-fn-id nil})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot set self as parent"
              (sp/validate-no-inheritance-cycle! storage (:id a) (:id a)))))))

  (testing "throws when cycle detected"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [a (sp/create-entity storage :fn {:name "a" :parent-fn-id nil})
            _ (sp/update-entity storage :fn (:id a) {:parent-fn-id nil})
            b (sp/create-entity storage :fn {:name "b" :parent-fn-id (:id a)})
            c (sp/create-entity storage :fn {:name "c" :parent-fn-id (:id b)})]
        ;; Try to make a -> c, which would create c -> b -> a -> c cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inheritance cycle"
              (sp/validate-no-inheritance-cycle! storage (:id a) (:id c))))))))


(deftest validate-no-arg-override-test
  (testing "passes when arg-schema not in parent chain"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :ref :ref-entity :arg-schema}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :int}})
                                 ds/build))
      (let [schema-id (random-uuid)
            arg-schema-1 (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema-id})
            arg-schema-2 (sp/create-entity storage :arg-schema {:name "y" :fn-schema-id schema-id})
            parent-fn (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema-id})
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id parent-fn)
                                                    :arg-schema-id (:id arg-schema-1)
                                                    :value 42})
            child-fn (sp/create-entity storage :fn {:name "child" :fn-schema-id schema-id
                                                    :parent-fn-id (:id parent-fn)})]
        ;; arg-schema-2 is not in parent chain, should pass
        (sp/validate-no-arg-override! storage (:id child-fn) (:id arg-schema-2)))))

  (testing "throws when arg-schema already in parent chain"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :ref :ref-entity :arg-schema}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :int}})
                                 ds/build))
      (let [schema-id (random-uuid)
            arg-schema (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema-id})
            parent-fn (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema-id})
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id parent-fn)
                                                    :arg-schema-id (:id arg-schema)
                                                    :value 42})
            child-fn (sp/create-entity storage :fn {:name "child" :fn-schema-id schema-id
                                                    :parent-fn-id (:id parent-fn)})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already defined in parent"
              (sp/validate-no-arg-override! storage (:id child-fn) (:id arg-schema))))))))


(deftest validate-arg-schema-belongs-to-fn-test
  (testing "passes when arg-schema belongs to fn's schema"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 ds/build))
      (let [schema-id (random-uuid)
            arg-schema (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema-id})
            fn-rec (sp/create-entity storage :fn {:name "my-fn" :fn-schema-id schema-id})]
        ;; Same schema-id, should pass
        (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-rec) (:id arg-schema)))))

  (testing "throws when arg-schema belongs to different schema"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 ds/build))
      (let [schema1-id (random-uuid)
            schema2-id (random-uuid)
            arg-schema (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema1-id})
            fn-rec (sp/create-entity storage :fn {:name "my-fn" :fn-schema-id schema2-id})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not belong to fn's schema"
              (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-rec) (:id arg-schema))))))))


(deftest validate-no-dependency-cycle-test
  (testing "passes when no dependency cycle"
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
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            fn-b (sp/create-entity storage :fn {:name "b"})]
        ;; a references b, no cycle
        (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b)))))

  (testing "throws when self-reference creates cycle"
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
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-a)})]
        ;; a already references itself through arg-value
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-a)))))))

  (testing "throws when indirect cycle detected"
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
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            ;; b -> c
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-b)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-c)})
            ;; c -> a
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-c)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-a)})]
        ;; Try to add a -> b, which would create a -> b -> c -> a cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b))))))))


(deftest migration-with-data-test
  (testing "data is preserved during entity rename"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema-v1 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)
          schema-v2 (-> (mds/create-builder)
                        (ds/add-entity :person entity-uuid  ; renamed entity
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)]
      (sp/initialize storage schema-v1)
      (sp/create-entity storage :user {:name "Alice"})
      (sp/create-entity storage :user {:name "Bob"})

      ;; Migrate to schema-v2
      (sp/initialize storage schema-v2)

      ;; Data should be accessible under new entity name
      (let [results (sp/query-entities storage :person {})]
        (is (= 2 (count results)))
        (is (= #{"Alice" "Bob"} (set (map :name results)))))))

  (testing "data is preserved during field rename"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema-v1 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)
          schema-v2 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:full-name {:uuid field-uuid :type :text}})  ; renamed field
                        ds/build)]
      (sp/initialize storage schema-v1)
      (let [alice (sp/create-entity storage :user {:name "Alice"})
            bob (sp/create-entity storage :user {:name "Bob"})]

        ;; Migrate to schema-v2
        (sp/initialize storage schema-v2)

        ;; Data should be accessible under new field name
        (let [alice-new (sp/read-entity storage :user (:id alice))
              bob-new (sp/read-entity storage :user (:id bob))]
          (is (= "Alice" (:full-name alice-new)))
          (is (= "Bob" (:full-name bob-new)))
          ;; Old field name should not exist
          (is (nil? (:name alice-new)))))))

  (testing "data is preserved during both entity and field rename"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema-v1 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)
          schema-v2 (-> (mds/create-builder)
                        (ds/add-entity :person entity-uuid      ; renamed entity
                                       {:full-name {:uuid field-uuid :type :text}})  ; renamed field
                        ds/build)]
      (sp/initialize storage schema-v1)
      (let [alice (sp/create-entity storage :user {:name "Alice"})]

        ;; Migrate to schema-v2
        (sp/initialize storage schema-v2)

        ;; Data should be accessible under new entity and field names
        (let [alice-new (sp/read-entity storage :person (:id alice))]
          (is (= "Alice" (:full-name alice-new))))))))


(deftest graphconstraints-edge-cases-test
  (testing "validate-parent-same-schema! passes when parent-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil parent, returns nil
      (is (nil? (sp/validate-parent-same-schema! storage (random-uuid) nil)))))

  (testing "validate-no-inheritance-cycle! passes when parent-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil parent, returns nil
      (is (nil? (sp/validate-no-inheritance-cycle! storage (random-uuid) nil)))))

  (testing "validate-no-dependency-cycle! passes when value-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil value, returns nil
      (is (nil? (sp/validate-no-dependency-cycle! storage (random-uuid) nil)))))

  (testing "validate-no-arg-override! passes when no parent chain"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [fn-rec (sp/create-entity storage :fn {:name "orphan" :parent-fn-id nil})]
        ;; Should not throw when fn has no parent, returns nil
        (is (nil? (sp/validate-no-arg-override! storage (:id fn-rec) (random-uuid))))))))


(deftest required-field-validation-test
  (testing "create-entity throws when required field is missing"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :email {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                         :type :text}})
                                 ds/build))
      ;; Missing :email field
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'email' is missing or nil"
            (sp/create-entity storage :user {:name "Alice"})))))

  (testing "create-entity throws when required field is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}})
                                 ds/build))
      ;; :name is nil
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
            (sp/create-entity storage :user {:name nil})))))

  (testing "create-entity allows nil for nullable field"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :bio {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                       :type :text :nullable? true}})
                                 ds/build))
      ;; :bio is nullable, so nil is allowed
      (let [user (sp/create-entity storage :user {:name "Alice" :bio nil})]
        (is (= "Alice" (:name user)))
        (is (nil? (:bio user))))))

  (testing "create-entity allows missing nullable field"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :bio {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                       :type :text :nullable? true}})
                                 ds/build))
      ;; :bio is not provided at all
      (let [user (sp/create-entity storage :user {:name "Alice"})]
        (is (= "Alice" (:name user))))))

  (testing "update-entity throws when setting required field to nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}})
                                 ds/build))
      (let [user (sp/create-entity storage :user {:name "Alice"})]
        ;; Try to set :name to nil
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
              (sp/update-entity storage :user (:id user) {:name nil}))))))

  (testing "update-entity allows setting nullable field to nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :bio {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                       :type :text :nullable? true}})
                                 ds/build))
      (let [user (sp/create-entity storage :user {:name "Alice" :bio "Developer"})
            ;; Set :bio to nil
            updated (sp/update-entity storage :user (:id user) {:bio nil})]
        (is (= "Alice" (:name updated)))
        (is (nil? (:bio updated)))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    mem/create-storage
    sp/close))
