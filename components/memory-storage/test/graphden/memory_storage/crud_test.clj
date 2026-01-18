(ns graphden.memory-storage.crud-test
  "Tests for memory storage CRUD operations.

   Covers:
   - StorageCRUD protocol (create, read, update, delete, query)
   - StorageBatchCRUD protocol (batch create, read, delete)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


;; === CRUD tests ===

(deftest crud-basic-test
  (testing "create-entity creates record with generated id"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [record (sp/create-entity storage :user {:name "Alice"})]
        (is (uuid? (:id record)))
        (is (= "Alice" (:name record))))))

  (testing "create-entity uses provided id"
    (let [storage (mem/create-storage)
          schema (th/make-schema)
          id (random-uuid)]
      (sp/initialize storage schema)
      (let [record (sp/create-entity storage :user {:id id :name "Bob"})]
        (is (= id (:id record)))
        (is (= "Bob" (:name record))))))

  (testing "read-entity returns record by id"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [created (sp/create-entity storage :user {:name "Charlie"})
            read-result (sp/read-entity storage :user (:id created))]
        (is (= created read-result)))))

  (testing "read-entity returns nil for unknown id"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/read-entity storage :user (random-uuid))))))

  (testing "update-entity updates record"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [created (sp/create-entity storage :user {:name "Dave"})
            updated (sp/update-entity storage :user (:id created) {:name "David"})]
        (is (= "David" (:name updated)))
        (is (= (:id created) (:id updated))))))

  (testing "update-entity throws for unknown id"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
            (sp/update-entity storage :user (random-uuid) {:name "Nobody"})))))

  (testing "delete-entity removes record"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [created (sp/create-entity storage :user {:name "Eve"})]
        (is (true? (sp/delete-entity storage :user (:id created))))
        (is (nil? (sp/read-entity storage :user (:id created)))))))

  (testing "delete-entity returns false for unknown id"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (is (false? (sp/delete-entity storage :user (random-uuid)))))))


(deftest query-entities-test
  (testing "query-entities returns all records when where is empty"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      (sp/create-entity storage :user {:name "Bob"})
      (let [results (sp/query-entities storage :user {})]
        (is (= 2 (count results)))
        (is (= #{"Alice" "Bob"} (set (map :name results)))))))

  (testing "query-entities filters by field"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      (sp/create-entity storage :user {:name "Bob"})
      (let [results (sp/query-entities storage :user {:name "Alice"})]
        (is (= 1 (count results)))
        (is (= "Alice" (:name (first results)))))))

  (testing "query-entities returns empty seq when no match"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      (let [results (sp/query-entities storage :user {:name "Nobody"})]
        (is (empty? results))))))


;; === StorageBatchCRUD tests ===

(deftest batch-create-entities-test
  (testing "create-entities creates multiple entities"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [data [{:name "Alice"} {:name "Bob"} {:name "Charlie"}]
            results (sp/create-entities storage :user data)]
        (is (= 3 (count results)))
        (is (= #{"Alice" "Bob" "Charlie"} (set (map :name results))))
        (is (every? uuid? (map :id results)))
        ;; Verify persistence
        (is (= 3 (count (sp/query-entities storage :user {})))))))

  (testing "create-entities with provided ids"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [id1 #uuid "11111111-1111-1111-1111-111111111111"
            id2 #uuid "22222222-2222-2222-2222-222222222222"
            data [{:id id1 :name "Alice"} {:id id2 :name "Bob"}]
            results (sp/create-entities storage :user data)]
        (is (= #{id1 id2} (set (map :id results)))))))

  (testing "create-entities with empty sequence returns empty"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [results (sp/create-entities storage :user [])]
        (is (empty? results))))))


(deftest batch-read-entities-test
  (testing "read-entities returns map of found entities"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [id1 #uuid "11111111-1111-1111-1111-111111111111"
            id2 #uuid "22222222-2222-2222-2222-222222222222"
            _ (sp/create-entity storage :user {:id id1 :name "Alice"})
            _ (sp/create-entity storage :user {:id id2 :name "Bob"})
            results (sp/read-entities storage :user [id1 id2])]
        (is (= 2 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (= "Bob" (:name (get results id2)))))))

  (testing "read-entities excludes non-existent ids"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [id1 #uuid "11111111-1111-1111-1111-111111111111"
            id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
            _ (sp/create-entity storage :user {:id id1 :name "Alice"})
            results (sp/read-entities storage :user [id1 id-nonexistent])]
        (is (= 1 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (nil? (get results id-nonexistent))))))

  (testing "read-entities with empty ids returns empty map"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [results (sp/read-entities storage :user [])]
        (is (= {} results))))))


(deftest batch-delete-entities-test
  (testing "delete-entities deletes multiple entities and returns count"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [id1 #uuid "11111111-1111-1111-1111-111111111111"
            id2 #uuid "22222222-2222-2222-2222-222222222222"
            id3 #uuid "33333333-3333-3333-3333-333333333333"
            _ (sp/create-entity storage :user {:id id1 :name "Alice"})
            _ (sp/create-entity storage :user {:id id2 :name "Bob"})
            _ (sp/create-entity storage :user {:id id3 :name "Charlie"})
            deleted-count (sp/delete-entities storage :user [id1 id2])]
        (is (= 2 deleted-count))
        ;; Verify entities are gone
        (is (nil? (sp/read-entity storage :user id1)))
        (is (nil? (sp/read-entity storage :user id2)))
        ;; Charlie should still exist
        (is (= "Charlie" (:name (sp/read-entity storage :user id3)))))))

  (testing "delete-entities with non-existent ids returns count of actually deleted"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [id1 #uuid "11111111-1111-1111-1111-111111111111"
            id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
            _ (sp/create-entity storage :user {:id id1 :name "Alice"})
            deleted-count (sp/delete-entities storage :user [id1 id-nonexistent])]
        (is (= 1 deleted-count)))))

  (testing "delete-entities with empty ids returns 0"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (let [deleted-count (sp/delete-entities storage :user [])]
        (is (zero? deleted-count))))))


(deftest batch-create-error-index-test
  (testing "batch create error includes index of failed record"
    (let [storage (mem/create-storage)
          ;; Schema with unique constraint on email
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}
                                     :name {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                            :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      ;; Insert a record first
      (sp/create-entity storage :user {:email "existing@example.com" :name "Existing"})
      ;; Try batch create where 3rd record (index 2) violates unique constraint
      (let [data [{:email "alice@example.com" :name "Alice"}
                  {:email "bob@example.com" :name "Bob"}
                  {:email "existing@example.com" :name "Duplicate"}  ; Will fail
                  {:email "charlie@example.com" :name "Charlie"}]]
        (try
          (sp/create-entities storage :user data)
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :constraint-violation/unique (:type (ex-data e))))
            (is (= 2 (:batch-index (ex-data e)))
                "Should indicate record at index 2 failed")
            (is (= 4 (:batch-size (ex-data e)))
                "Should indicate total batch size"))))))

  (testing "batch create error at first record has index 0"
    (let [storage (mem/create-storage)
          ;; Schema with unique constraint on email
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000011"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:email "existing@example.com"})
      ;; First record in batch violates constraint
      (let [data [{:email "existing@example.com"}  ; Will fail at index 0
                  {:email "new@example.com"}]]
        (try
          (sp/create-entities storage :user data)
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (is (zero? (:batch-index (ex-data e))))
            (is (= 2 (:batch-size (ex-data e))))))))))
