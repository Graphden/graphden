(ns graphden.storage.postgres.crud-test
  "Tests for PostgreSQL storage CRUD operations.

   Covers:
   - StorageCRUD protocol (create, read, update, delete, query)
   - StorageBatchCRUD protocol (batch create, read, delete)
   - Required field validation"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]))


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
