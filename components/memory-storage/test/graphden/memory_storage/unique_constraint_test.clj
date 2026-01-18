(ns graphden.memory-storage.unique-constraint-test
  "Tests for memory storage unique constraint enforcement."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


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
