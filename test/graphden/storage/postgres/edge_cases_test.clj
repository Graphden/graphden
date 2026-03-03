(ns graphden.storage.postgres.edge-cases-test
  "Tests for PostgreSQL storage edge cases.

   Covers:
   - Query with NULL values in WHERE clause
   - NULL in unique constraints
   - Batch operations edge cases
   - Input validation tests
   - Batch insert count mismatch"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === Query with NULL in WHERE clause ===

(deftest query-with-null-values-test
  (testing "query with nil value generates IS NULL"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f01"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000f02"
                                            :type :text}
                                     :email {:uuid #uuid "00000000-0000-0000-0000-000000000f03"
                                             :type :text
                                             :nullable? true}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; Create records with different email states
        (sp/create-entity storage :user {:name "Alice" :email nil})
        (sp/create-entity storage :user {:name "Bob" :email "bob@example.com"})
        (sp/create-entity storage :user {:name "Carol" :email nil})

        ;; Query for NULL email
        (let [results (sp/query-entities storage :user {:email nil})]
          (is (= 2 (count results)))
          (is (every? #(nil? (:email %)) results)))

        ;; Query for non-NULL email
        (let [results (sp/query-entities storage :user {:email "bob@example.com"})]
          (is (= 1 (count results)))
          (is (= "Bob" (:name (first results)))))

        (finally
          (sp/close storage)))))

  (testing "query with multiple NULL values"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f11"
                                    {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000f12"
                                                  :type :text
                                                  :nullable? true}
                                     :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000f13"
                                                 :type :text
                                                 :nullable? true}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (sp/create-entity storage :user {:first-name nil :last-name nil})
        (sp/create-entity storage :user {:first-name "John" :last-name nil})
        (sp/create-entity storage :user {:first-name nil :last-name "Doe"})
        (sp/create-entity storage :user {:first-name "Jane" :last-name "Smith"})

        ;; Query for both NULL
        (let [results (sp/query-entities storage :user {:first-name nil :last-name nil})]
          (is (= 1 (count results))))

        ;; Query for first-name NULL only
        (let [results (sp/query-entities storage :user {:first-name nil})]
          (is (= 2 (count results))))

        (finally
          (sp/close storage)))))

  (testing "query with empty where map returns all records"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (sp/create-entity storage :user {:name "Alice"})
        (sp/create-entity storage :user {:name "Bob"})
        (let [results (sp/query-entities storage :user {})]
          (is (= 2 (count results))))
        (finally
          (sp/close storage))))))


;; === NULL in unique constraints ===

(deftest null-unique-constraint-test
  (testing "multiple records with NULL in unique constraint field are allowed"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f21"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000f22"
                                             :type :text
                                             :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; First NULL - should succeed
        (let [r1 (sp/create-entity storage :user {:email nil})]
          (is (some? (:id r1))))
        ;; Second NULL - should also succeed (PostgreSQL NULL semantics)
        (let [r2 (sp/create-entity storage :user {:email nil})]
          (is (some? (:id r2))))
        ;; Non-NULL value
        (let [r3 (sp/create-entity storage :user {:email "test@example.com"})]
          (is (some? (:id r3))))
        ;; Duplicate non-NULL - should fail
        (is (thrown? clojure.lang.ExceptionInfo
              (sp/create-entity storage :user {:email "test@example.com"})))
        ;; Verify 3 records exist
        (is (= 3 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage))))))


;; === Batch operations edge cases ===

(deftest batch-delete-edge-cases-test
  (testing "delete-entities with mix of existent and non-existent IDs"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (let [r1 (sp/create-entity storage :user {:name "Alice"})
              r2 (sp/create-entity storage :user {:name "Bob"})
              fake-id (random-uuid)]
          ;; Delete one real and one fake
          (is (= 1 (sp/delete-entities storage :user [(:id r1) fake-id])))
          ;; Verify r1 gone, r2 remains
          (is (nil? (sp/read-entity storage :user (:id r1))))
          (is (some? (sp/read-entity storage :user (:id r2)))))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with only non-existent IDs returns 0"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (zero? (sp/delete-entities storage :user [(random-uuid) (random-uuid)])))
        (finally
          (sp/close storage))))))


;; === Input validation tests ===

(deftest create-entity-invalid-data-test
  (testing "create-entity throws when data is not a map"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"data must be a map"
              (sp/create-entity storage :user "not a map")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"data must be a map"
              (sp/create-entity storage :user [:a :vector])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"data must be a map"
              (sp/create-entity storage :user 123)))
        (finally
          (sp/close storage))))))


(deftest query-entities-invalid-where-test
  (testing "query-entities throws when where is not a map"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"where clause must be nil or a map"
              (sp/query-entities storage :user "not a map")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"where clause must be nil or a map"
              (sp/query-entities storage :user [:a :vector])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"where clause must be nil or a map"
              (sp/query-entities storage :user 123)))
        (finally
          (sp/close storage))))))


;; === Tests with mocks for error handling paths ===

(deftest batch-insert-count-mismatch-test
  (testing "throws batch-insert-mismatch when returned rows don't match input count"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        ;; Mock jdbc/execute! to return fewer rows than expected
        (let [original-execute jdbc/execute!]
          (with-redefs [jdbc/execute! (fn [ds query & args]
                                        (let [result (apply original-execute ds query args)]
                                          ;; If this is an INSERT with RETURNING, drop one row
                                          (if (and (vector? query)
                                                   (string? (first query))
                                                   (str/includes? (first query) "INSERT")
                                                   (str/includes? (first query) "RETURNING"))
                                            (drop-last result)
                                            result)))]
            (let [ex (try
                       (sp/create-entities storage :user
                                           [{:name "Alice"}
                                            {:name "Bob"}
                                            {:name "Carol"}])
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
              (is (some? ex))
              (is (= :batch-insert-mismatch (:type (ex-data ex))))
              (is (= 3 (:expected-count (ex-data ex))))
              (is (= 2 (:actual-count (ex-data ex)))))))
        (finally
          (sp/close storage))))))
