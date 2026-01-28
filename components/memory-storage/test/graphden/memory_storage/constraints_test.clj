(ns graphden.memory-storage.constraints-test
  "Tests for memory storage GraphConstraints protocol.

   Covers:
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


(defn- make-cycle-test-schema
  "Creates schema with fn, arg-value (pure), and fn-arg for cycle detection tests.
   Uses normalized schema where arg-value has no owner, and fn-arg binds fn to arg-value."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                     {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
      (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                     {:arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                      :type :uuid}
                      :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                              :type :uuid}})
      (ds/add-entity :fn-arg #uuid "40000000-0000-0000-0000-000000000001"
                     {:fn-id {:uuid #uuid "40000000-0000-0000-0000-000000000002"
                              :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "40000000-0000-0000-0000-000000000003"
                                      :type :uuid}
                      :arg-value-id {:uuid #uuid "40000000-0000-0000-0000-000000000004"
                                     :type :ref :ref-entity :arg-value}})
      ds/build))


(defn- create-arg-value-with-binding!
  "Creates arg-value and fn-arg binding. Returns the arg-value."
  [storage fn-id value]
  (let [arg-schema-id (random-uuid)
        av (sp/create-entity storage :arg-value
                             {:arg-schema-id arg-schema-id
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))


(deftest validate-no-dependency-cycle-test
  (testing "passes when no dependency cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-cycle-test-schema))
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            fn-b (sp/create-entity storage :fn {:name "b"})]
        ;; a references b, no cycle
        (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b)))))

  (testing "throws when self-reference creates cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-cycle-test-schema))
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            _ (create-arg-value-with-binding! storage (:id fn-a) (:id fn-a))]
        ;; a already references itself through arg-value
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-a)))))))

  (testing "throws when indirect cycle detected"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-cycle-test-schema))
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            ;; b -> c (via fn-arg -> arg-value)
            _ (create-arg-value-with-binding! storage (:id fn-b) (:id fn-c))
            ;; c -> a (via fn-arg -> arg-value)
            _ (create-arg-value-with-binding! storage (:id fn-c) (:id fn-a))]
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
  (testing "validate-no-dependency-cycle! passes when value-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil value, returns nil
      (is (nil? (sp/validate-no-dependency-cycle! storage (random-uuid) nil))))))


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
