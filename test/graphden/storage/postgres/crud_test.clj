(ns graphden.storage.postgres.crud-test
  "Tests for PostgreSQL storage CRUD operations.

   Covers:
   - StorageCRUD protocol (create, read, update, delete, query)
   - StorageBatchCRUD protocol (batch create, read, update, upsert, delete)
   - Required field validation
   - Where-clause processing (IS NULL, IN clause, equality)
   - Batch edge cases (only-id updates, unique violations, count mismatches)
   - Arg descendant validation on update/delete"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === StorageCRUD tests ===

(deftest crud-create-entity-test
  (testing "create-entity with provided id"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          result (sp/create-entity storage :user {:id id :name "Alice"})]
      (try
        (is (= id (:id result)))
        (is (= "Alice" (:name result)))
        (finally
          (sp/close storage)))))

  (testing "create-entity generates id if not provided"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          result (sp/create-entity storage :user {:name "Bob"})]
      (try
        (is (uuid? (:id result)))
        (is (= "Bob" (:name result)))
        (finally
          (sp/close storage))))))


(deftest crud-read-entity-test
  (testing "read-entity returns entity by id"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/read-entity storage :user id)]
      (try
        (is (= id (:id result)))
        (is (= "Alice" (:name result)))
        (finally
          (sp/close storage)))))

  (testing "read-entity returns nil for non-existent id"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          result (sp/read-entity storage :user #uuid "11111111-1111-1111-1111-111111111111")]
      (try
        (is (nil? result))
        (finally
          (sp/close storage))))))


(deftest crud-update-entity-test
  (testing "update-entity updates existing entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/update-entity storage :user id {:name "Alice Updated"})]
      (try
        (is (= id (:id result)))
        (is (= "Alice Updated" (:name result)))
        ;; Verify persistence
        (is (= "Alice Updated" (:name (sp/read-entity storage :user id))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws for non-existent entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
              (sp/update-entity storage :user
                                #uuid "11111111-1111-1111-1111-111111111111"
                                {:name "Test"})))
        (finally
          (sp/close storage))))))


(deftest crud-delete-entity-test
  (testing "delete-entity returns true for existing entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/delete-entity storage :user id)]
      (try
        (is (true? result))
        (is (nil? (sp/read-entity storage :user id)))
        (finally
          (sp/close storage)))))

  (testing "delete-entity returns false for non-existent entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          result (sp/delete-entity storage :user #uuid "11111111-1111-1111-1111-111111111111")]
      (try
        (is (false? result))
        (finally
          (sp/close storage))))))


(deftest crud-query-entities-test
  (testing "query-entities with empty where returns all"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {})]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Bob"} (set (map :name result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with where filters results"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {:name "Alice"})]
      (try
        (is (= 1 (count result)))
        (is (= "Alice" (:name (first result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with nil value uses IS NULL (not = NULL)"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text
                                                 :nullable? true}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name nil})
          result-with-nil (sp/query-entities storage :user {:name nil})
          result-with-value (sp/query-entities storage :user {:name "Alice"})]
      (try
        ;; This test verifies that WHERE name IS NULL works (SQL = NULL always returns false)
        (is (= 1 (count result-with-nil)) "Should find record with NULL name using IS NULL")
        (is (nil? (:name (first result-with-nil))))
        (is (= 1 (count result-with-value)))
        (is (= "Alice" (:name (first result-with-value))))
        (finally
          (sp/close storage))))))


;; === StorageBatchCRUD tests ===

(deftest batch-create-entities-test
  (testing "create-entities creates multiple entities in single operation"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          data [{:name "Alice"}
                {:name "Bob"}
                {:name "Charlie"}]
          results (sp/create-entities storage :user data)]
      (try
        (is (= 3 (count results)))
        (is (= #{"Alice" "Bob" "Charlie"} (set (map :name results))))
        (is (every? uuid? (map :id results)))
        ;; Verify persistence
        (is (= 3 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage)))))

  (testing "create-entities with provided ids"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          data [{:id id1 :name "Alice"}
                {:id id2 :name "Bob"}]
          results (sp/create-entities storage :user data)]
      (try
        (is (= #{id1 id2} (set (map :id results))))
        (finally
          (sp/close storage)))))

  (testing "create-entities with empty sequence returns empty"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          results (sp/create-entities storage :user [])]
      (try
        (is (empty? results))
        (finally
          (sp/close storage))))))


(deftest batch-read-entities-test
  (testing "read-entities returns map of found entities"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          results (sp/read-entities storage :user [id1 id2])]
      (try
        (is (= 2 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (= "Bob" (:name (get results id2))))
        (finally
          (sp/close storage)))))

  (testing "read-entities excludes non-existent ids"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          results (sp/read-entities storage :user [id1 id-nonexistent])]
      (try
        (is (= 1 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (nil? (get results id-nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "read-entities with empty ids returns empty map"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          results (sp/read-entities storage :user [])]
      (try
        (is (= {} results))
        (finally
          (sp/close storage))))))


(deftest batch-delete-entities-test
  (testing "delete-entities deletes multiple entities and returns count"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          id3 #uuid "33333333-3333-3333-3333-333333333333"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          _ (sp/create-entity storage :user {:id id3 :name "Charlie"})
          deleted-count (sp/delete-entities storage :user [id1 id2])]
      (try
        (is (= 2 deleted-count))
        ;; Verify entities are gone
        (is (nil? (sp/read-entity storage :user id1)))
        (is (nil? (sp/read-entity storage :user id2)))
        ;; Charlie should still exist
        (is (= "Charlie" (:name (sp/read-entity storage :user id3))))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with non-existent ids returns count of actually deleted"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          deleted-count (sp/delete-entities storage :user [id1 id-nonexistent])]
      (try
        (is (= 1 deleted-count))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with empty ids returns 0"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          deleted-count (sp/delete-entities storage :user [])]
      (try
        (is (zero? deleted-count))
        (finally
          (sp/close storage))))))


;; === Required field validation tests ===

;; === update-entities batch tests ===

(deftest batch-update-entities-test
  (testing "update-entities updates multiple entities"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          results (sp/update-entities storage :user
                                      [{:id id1 :name "Alice Updated"}
                                       {:id id2 :name "Bob Updated"}])]
      (try
        (is (= 2 (count results)))
        (is (= "Alice Updated" (:name (sp/read-entity storage :user id1))))
        (is (= "Bob Updated" (:name (sp/read-entity storage :user id2))))
        (finally
          (sp/close storage)))))

  (testing "update-entities with empty sequence returns empty"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          results (sp/update-entities storage :user [])]
      (try
        (is (empty? results))
        (finally
          (sp/close storage)))))

  (testing "update-entities throws for record without :id"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Each record must have :id"
              (sp/update-entities storage :user [{:name "No ID"}])))
        (finally
          (sp/close storage)))))

  (testing "update-entities throws for non-existent entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
              (sp/update-entities storage :user [{:id #uuid "11111111-1111-1111-1111-111111111111" :name "Test"}])))
        (finally
          (sp/close storage))))))


;; === upsert-entities batch tests ===

(deftest batch-upsert-entities-test
  (testing "upsert-entities inserts new entities"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          results (sp/upsert-entities storage :user
                                      [{:id id1 :name "Alice"}
                                       {:id id2 :name "Bob"}])]
      (try
        (is (= 2 (count results)))
        (is (= "Alice" (:name (sp/read-entity storage :user id1))))
        (is (= "Bob" (:name (sp/read-entity storage :user id2))))
        (finally
          (sp/close storage)))))

  (testing "upsert-entities updates existing entities"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          results (sp/upsert-entities storage :user [{:id id1 :name "Alice Updated"}])]
      (try
        (is (= 1 (count results)))
        (is (= "Alice Updated" (:name (sp/read-entity storage :user id1))))
        (finally
          (sp/close storage)))))

  (testing "upsert-entities handles mix of inserts and updates"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          results (sp/upsert-entities storage :user
                                      [{:id id1 :name "Alice Updated"}
                                       {:id id2 :name "Bob New"}])]
      (try
        (is (= 2 (count results)))
        (is (= "Alice Updated" (:name (sp/read-entity storage :user id1))))
        (is (= "Bob New" (:name (sp/read-entity storage :user id2))))
        (finally
          (sp/close storage)))))

  (testing "upsert-entities with empty sequence returns empty"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          results (sp/upsert-entities storage :user [])]
      (try
        (is (empty? results))
        (finally
          (sp/close storage)))))

  (testing "upsert-entities throws for record without :id"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Each record must have :id"
              (sp/upsert-entities storage :user [{:name "No ID"}])))
        (finally
          (sp/close storage)))))

  (testing "upsert-entities throws for duplicate ids"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          dup-id #uuid "11111111-1111-1111-1111-111111111111"]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate IDs"
              (sp/upsert-entities storage :user [{:id dup-id :name "First"}
                                                 {:id dup-id :name "Second"}])))
        (finally
          (sp/close storage))))))


;; === batch size validation tests ===

(deftest batch-size-validation-test
  (testing "create-entities throws for duplicate ids"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          dup-id #uuid "11111111-1111-1111-1111-111111111111"]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate IDs"
              (sp/create-entities storage :user [{:id dup-id :name "First"}
                                                 {:id dup-id :name "Second"}])))
        (finally
          (sp/close storage)))))

  (testing "update-entities throws for duplicate ids"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          dup-id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id dup-id :name "Test"})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate IDs"
              (sp/update-entities storage :user [{:id dup-id :name "First"}
                                                 {:id dup-id :name "Second"}])))
        (finally
          (sp/close storage))))))


;; === Required field validation tests ===

(deftest crud-required-field-validation-test
  (testing "create-entity throws when required field is missing"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                  :type :text}})]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'email' is missing or nil"
              (sp/create-entity storage :user {:name "Alice"})))
        (finally
          (sp/close storage)))))

  (testing "create-entity throws when required field is nil"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
              (sp/create-entity storage :user {:name nil})))
        (finally
          (sp/close storage)))))

  (testing "create-entity allows nil for nullable field"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice" :bio nil})]
          (is (= "Alice" (:name user)))
          (is (nil? (:bio user))))
        (finally
          (sp/close storage)))))

  (testing "create-entity allows missing nullable field"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        ;; :bio is not provided at all
        (let [user (sp/create-entity storage :user {:name "Alice"})]
          (is (= "Alice" (:name user))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws when setting required field to nil"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
                (sp/update-entity storage :user (:id user) {:name nil}))))
        (finally
          (sp/close storage)))))

  (testing "update-entity allows setting nullable field to nil"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice" :bio "Hello"})
              updated (sp/update-entity storage :user (:id user) {:bio nil})]
          (is (= "Alice" (:name updated)))
          (is (nil? (:bio updated))))
        (finally
          (sp/close storage))))))


;; === Query where-clause IN clause tests ===

(deftest query-entities-in-clause-test
  (testing "query-entities with vector value generates IN clause"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          _ (sp/create-entity storage :user {:id #uuid "33333333-3333-3333-3333-333333333333" :name "Charlie"})
          result (sp/query-entities storage :user {:name ["Alice" "Charlie"]})]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Charlie"} (set (map :name result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with set value generates IN clause"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {:name #{"Alice" "Bob"}})]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Bob"} (set (map :name result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with single-element vector IN clause"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {:name ["Alice"]})]
      (try
        (is (= 1 (count result)))
        (is (= "Alice" (:name (first result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with IN clause matching no records returns empty"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          result (sp/query-entities storage :user {:name ["Nonexistent" "Missing"]})]
      (try
        (is (empty? result))
        (finally
          (sp/close storage)))))

  (testing "query-entities with IN clause on UUID field"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          id3 #uuid "33333333-3333-3333-3333-333333333333"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          _ (sp/create-entity storage :user {:id id3 :name "Charlie"})
          result (sp/query-entities storage :user {:id [id1 id3]})]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Charlie"} (set (map :name result))))
        (finally
          (sp/close storage))))))


;; === Query with nil where returns all ===

(deftest query-entities-nil-where-test
  (testing "query-entities with nil where returns all entities"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user nil)]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Bob"} (set (map :name result))))
        (finally
          (sp/close storage))))))


;; === Update-entities: only-id records (no update columns) ===

(deftest batch-update-entities-only-id-test
  (testing "update-entities with only :id verifies existence and returns current records"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          results (sp/update-entities storage :user [{:id id1} {:id id2}])]
      (try
        (is (= 2 (count results)))
        ;; Records should be returned unchanged
        (is (= "Alice" (:name (first (filter #(= id1 (:id %)) results)))))
        (is (= "Bob" (:name (first (filter #(= id2 (:id %)) results)))))
        (finally
          (sp/close storage)))))

  (testing "update-entities with only :id throws for non-existent entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          missing-id #uuid "99999999-9999-9999-9999-999999999999"]
      (try
        (let [ex (try
                   (sp/update-entities storage :user [{:id id1} {:id missing-id}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :not-found (:type (ex-data ex))))
          (is (= [missing-id] (:missing-ids (ex-data ex)))))
        (finally
          (sp/close storage))))))


;; === Update-entities with boolean field (type inference) ===

(deftest batch-update-entities-boolean-field-test
  (testing "update-entities correctly handles boolean fields"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :active {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                   :type :bool}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice" :active true})
          _ (sp/create-entity storage :user {:id id2 :name "Bob" :active true})
          results (sp/update-entities storage :user
                                      [{:id id1 :active false}
                                       {:id id2 :active false}])]
      (try
        (is (= 2 (count results)))
        (is (false? (:active (sp/read-entity storage :user id1))))
        (is (false? (:active (sp/read-entity storage :user id2))))
        (finally
          (sp/close storage))))))


;; === Create-entities unique violation wrapping ===

(deftest batch-create-entities-unique-violation-test
  (testing "create-entities wraps unique violation with batch context"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}}
                                 :constraints [{:type :unique :fields [:name]}])
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:name "Alice"})]
      (try
        (let [ex (try
                   (sp/create-entities storage :user
                                       [{:name "Bob"}
                                        {:name "Alice"}])  ; duplicate
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :unique-violation (:type (ex-data ex))))
          ;; Should have batch context
          (is (contains? (ex-data ex) :batch-size)))
        (finally
          (sp/close storage))))))


;; === Update-entities unique violation wrapping ===

(deftest batch-update-entities-unique-violation-test
  (testing "update-entities wraps unique violation with batch context"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}}
                                 :constraints [{:type :unique :fields [:name]}])
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})]
      (try
        (let [ex (try
                   (sp/update-entities storage :user
                                       [{:id id2 :name "Alice"}])  ; conflicts with id1
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :unique-violation (:type (ex-data ex))))
          (is (contains? (ex-data ex) :batch-size)))
        (finally
          (sp/close storage))))))


;; === Upsert-entities count mismatch ===

(deftest batch-upsert-count-mismatch-test
  (testing "throws batch-upsert-mismatch when returned rows don't match input count"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          _ (sp/initialize storage schema)]
      (try
        (let [original-execute jdbc/execute!]
          (with-redefs [jdbc/execute! (fn [ds query & args]
                                        (let [result (apply original-execute ds query args)]
                                          ;; If this is an INSERT ON CONFLICT (upsert), drop one row
                                          (if (and (vector? query)
                                                   (string? (first query))
                                                   (str/includes? (first query) "ON CONFLICT"))
                                            (drop-last result)
                                            result)))]
            (let [ex (try
                       (sp/upsert-entities storage :user
                                           [{:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"}
                                            {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"}
                                            {:id #uuid "33333333-3333-3333-3333-333333333333" :name "Charlie"}])
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
              (is (some? ex))
              (is (= :batch-upsert-mismatch (:type (ex-data ex))))
              (is (= 3 (:expected-count (ex-data ex))))
              (is (= 2 (:actual-count (ex-data ex)))))))
        (finally
          (sp/close storage))))))


;; === Upsert-entities unique violation wrapping ===

(deftest batch-upsert-entities-sql-error-wrapping-test
  (testing "upsert-entities wraps SQL errors with batch context"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})]
      (try
        (sp/initialize storage schema)
        ;; Force a SQL error by using with-redefs to throw a SQLException
        (let [original-execute jdbc/execute!]
          (with-redefs [jdbc/execute! (fn [ds query & args]
                                        (if (and (vector? query)
                                                 (string? (first query))
                                                 (str/includes? (first query) "ON CONFLICT"))
                                          (throw (java.sql.SQLException. "simulated error" "23505"))
                                          (apply original-execute ds query args)))]
            (let [ex (try
                       (sp/upsert-entities storage :user
                                           [{:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"}])
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
              (is (some? ex))
              (is (contains? (ex-data ex) :batch-size)))))
        (finally
          (sp/close storage))))))


;; === Arg descendant validation on update/delete ===

(deftest arg-descendant-validation-test
  (testing "update-entity on arg with descendants throws constraint-violation"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)]
      (try
        ;; Create base fn and arg
        (let [base-fn (setup/create-base-fn! storage "base-fn" :int)
              parent-arg (setup/create-arg! storage (:id base-fn)
                                            {:name "x" :type :integer :required true :is-fn false
                                             :value 10})
              composed-fn (setup/create-composed-fn! storage "composed" (:id base-fn))
              _child-arg (setup/create-arg! storage (:id composed-fn)
                                            {:name "x" :type :integer :required true :is-fn false
                                             :source-id (:id parent-arg)
                                             :value 42})
              ex (try
                   (sp/update-entity storage :arg (:id parent-arg) {:value 999})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :constraint-violation/has-descendants (:type (ex-data ex)))))
        (finally
          (sp/close storage)))))

  (testing "delete-entity on arg with descendants throws constraint-violation"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)]
      (try
        (let [base-fn (setup/create-base-fn! storage "base-fn2" :int)
              parent-arg (setup/create-arg! storage (:id base-fn)
                                            {:name "x" :type :integer :required true :is-fn false
                                             :value 10})
              composed-fn (setup/create-composed-fn! storage "composed2" (:id base-fn))
              _child-arg (setup/create-arg! storage (:id composed-fn)
                                            {:name "x" :type :integer :required true :is-fn false
                                             :source-id (:id parent-arg)
                                             :value 42})
              ex (try
                   (sp/delete-entity storage :arg (:id parent-arg))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :constraint-violation/has-descendants (:type (ex-data ex)))))
        (finally
          (sp/close storage)))))

  (testing "update-entity on arg without descendants succeeds"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)]
      (try
        (let [base-fn (setup/create-base-fn! storage "base-fn3" :int)
              arg (setup/create-arg! storage (:id base-fn)
                                     {:name "x" :type :integer :required true :is-fn false
                                      :value 42})
              updated (sp/update-entity storage :arg (:id arg) {:value 99})]
          (is (= 99 (:value updated))))
        (finally
          (sp/close storage)))))

  (testing "delete-entity on non-arg entity skips descendant validation"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)]
      (try
        (let [base-fn (setup/create-base-fn! storage "deletable-fn" :int)
              result (sp/delete-entity storage :fn (:id base-fn))]
          (is (true? result)))
        (finally
          (sp/close storage))))))


;; === Create-entities with heterogeneous fields (different records have different fields) ===

(deftest batch-create-entities-heterogeneous-fields-test
  (testing "create-entities handles records with different field sets"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})
          _ (sp/initialize storage schema)]
      (try
        ;; One record has bio, the other doesn't
        (let [results (sp/create-entities storage :user
                                          [{:name "Alice" :bio "Hello"}
                                           {:name "Bob"}])]
          (is (= 2 (count results)))
          (let [alice (first (filter #(= "Alice" (:name %)) results))
                bob (first (filter #(= "Bob" (:name %)) results))]
            (is (= "Hello" (:bio alice)))
            (is (nil? (:bio bob)))))
        (finally
          (sp/close storage))))))


;; === Update-entities with heterogeneous fields ===

(deftest batch-update-entities-heterogeneous-fields-test
  (testing "update-entities handles records updating same nullable field"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice" :bio "old1"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob" :bio "old2"})]
      (try
        ;; Both update bio field
        (let [results (sp/update-entities storage :user
                                          [{:id id1 :bio "new1"}
                                           {:id id2 :bio "new2"}])]
          (is (= 2 (count results)))
          (is (= "new1" (:bio (sp/read-entity storage :user id1))))
          (is (= "new2" (:bio (sp/read-entity storage :user id2)))))
        (finally
          (sp/close storage))))))


;; === Query with multiple where conditions ===

(deftest query-entities-multiple-conditions-test
  (testing "query-entities with multiple where conditions uses AND"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:name "Alice" :bio "dev"})
          _ (sp/create-entity storage :user {:name "Bob" :bio "dev"})
          _ (sp/create-entity storage :user {:name "Alice" :bio "mgr"})]
      (try
        ;; Both conditions must match
        (let [result (sp/query-entities storage :user {:name "Alice" :bio "dev"})]
          (is (= 1 (count result)))
          (is (= "Alice" (:name (first result))))
          (is (= "dev" (:bio (first result)))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with equality and nil conditions combined"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text :nullable? true}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:name "Alice" :bio nil})
          _ (sp/create-entity storage :user {:name "Bob" :bio "dev"})
          _ (sp/create-entity storage :user {:name "Charlie" :bio nil})]
      (try
        (let [result (sp/query-entities storage :user {:name "Alice" :bio nil})]
          (is (= 1 (count result)))
          (is (= "Alice" (:name (first result)))))
        (finally
          (sp/close storage))))))
