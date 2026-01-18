(ns graphden.memory-storage.edge-cases-test
  "Tests for memory storage edge cases.

   Covers:
   - Concurrent operation tests
   - Edge case tests
   - NULL constraint handling tests
   - Delete entities edge cases
   - Batch create edge cases
   - Input validation tests
   - Error handling tests
   - Contract tests for concurrency and deep chains"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.contract-tests :as contract]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


;; === Concurrent operation tests ===

(deftest concurrent-access-test
  (testing "concurrent reads are thread-safe"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000081"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000082"
                                            :type :text}})
                     ds/build)
          errors (atom [])]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      ;; Launch multiple threads reading concurrently
      (let [futures (doall
                      (for [_ (range 10)]
                        (future
                          (try
                            (dotimes [_ 100]
                              (sp/query-entities storage :user {}))
                            (catch Exception e
                              (swap! errors conj e))))))]
        (doseq [f futures]
          (deref f 5000 :timeout)))
      (is (empty? @errors) (str "Errors during concurrent access: " @errors))))

  (testing "concurrent writes are thread-safe"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :counter #uuid "00000000-0000-0000-0000-000000000091"
                                    {:value {:uuid #uuid "00000000-0000-0000-0000-000000000092"
                                             :type :int}})
                     ds/build)
          errors (atom [])]
      (sp/initialize storage schema)
      ;; Launch multiple threads creating entities concurrently
      (let [futures (doall
                      (for [i (range 10)]
                        (future
                          (try
                            (dotimes [j 10]
                              (sp/create-entity storage :counter {:value (+ (* i 10) j)}))
                            (catch Exception e
                              (swap! errors conj e))))))]
        (doseq [f futures]
          (deref f 5000 :timeout)))
      (is (empty? @errors) (str "Errors during concurrent writes: " @errors))
      (is (= 100 (count (sp/query-entities storage :counter {}))))))

  (testing "concurrent writes with unique constraint are atomic"
    ;; This test verifies that validation happens atomically with write.
    ;; Multiple threads try to create records with the same unique value.
    ;; Only ONE should succeed, all others should fail with constraint violation.
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000093"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000094"
                                             :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)
          successes (atom 0)
          constraint-violations (atom 0)
          other-errors (atom [])]
      (sp/initialize storage schema)
      ;; Launch 10 threads all trying to create record with same email
      (let [futures (doall
                      (for [_ (range 10)]
                        (future
                          (try
                            (sp/create-entity storage :user {:email "test@example.com"})
                            (swap! successes inc)
                            (catch clojure.lang.ExceptionInfo e
                              (if (= :constraint-violation/unique (:type (ex-data e)))
                                (swap! constraint-violations inc)
                                (swap! other-errors conj e)))
                            (catch Exception e
                              (swap! other-errors conj e))))))]
        (doseq [f futures]
          (deref f 5000 :timeout)))
      ;; Exactly one should succeed
      (is (= 1 @successes) "Exactly one thread should succeed")
      ;; Rest should get constraint violations
      (is (= 9 @constraint-violations) "Other threads should get constraint violations")
      ;; No other errors
      (is (empty? @other-errors) (str "Unexpected errors: " @other-errors))
      ;; Verify only one record exists
      (is (= 1 (count (sp/query-entities storage :user {})))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    mem/create-storage
    sp/close))


;; === Edge case tests ===

(deftest query-non-existent-entity-test
  (testing "query-entities throws when entity doesn't exist in schema"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Entity not found in schema"
              (sp/query-entities storage :non-existent-entity {})))
        (finally
          (sp/close storage))))))


;; === NULL constraint handling tests ===

(deftest null-in-unique-constraint-test
  (testing "multiple records with NULL in unique constraint field are allowed"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f01"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000f02"
                                             :type :text
                                             :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (try
        ;; First record with NULL email
        (let [r1 (sp/create-entity storage :user {:email nil})]
          (is (some? (:id r1))))
        ;; Second record with NULL email - should be allowed (PostgreSQL NULL semantics)
        (let [r2 (sp/create-entity storage :user {:email nil})]
          (is (some? (:id r2))))
        ;; Third record with actual value
        (let [r3 (sp/create-entity storage :user {:email "test@example.com"})]
          (is (some? (:id r3))))
        ;; Fourth record with same value - should fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :user {:email "test@example.com"})))
        ;; Verify we have 3 records total
        (is (= 3 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage)))))

  (testing "multi-field constraint with partial NULL is allowed"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f11"
                                    {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000f12"
                                                  :type :text
                                                  :nullable? true}
                                     :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000f13"
                                                 :type :text
                                                 :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:first-name :last-name]})
                     ds/build)]
      (sp/initialize storage schema)
      (try
        ;; Record with one NULL - should be allowed
        (sp/create-entity storage :user {:first-name "John" :last-name nil})
        ;; Same first-name, different NULL - should be allowed
        (sp/create-entity storage :user {:first-name "John" :last-name nil})
        ;; Both NULL - should be allowed
        (sp/create-entity storage :user {:first-name nil :last-name nil})
        ;; Both NULL again - should be allowed
        (sp/create-entity storage :user {:first-name nil :last-name nil})
        ;; Non-NULL pair
        (sp/create-entity storage :user {:first-name "John" :last-name "Doe"})
        ;; Same non-NULL pair - should fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :user {:first-name "John" :last-name "Doe"})))
        (finally
          (sp/close storage))))))


;; === Delete entities edge cases ===

(deftest delete-entities-edge-cases-test
  (testing "delete-entities with empty collection returns 0"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (is (zero? (sp/delete-entities storage :user [])))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with non-existent IDs returns 0"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (is (zero? (sp/delete-entities storage :user [(random-uuid) (random-uuid)])))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with mix of existent and non-existent IDs"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (let [r1 (sp/create-entity storage :user {:name "Alice"})
              r2 (sp/create-entity storage :user {:name "Bob"})
              non-existent-id (random-uuid)]
          ;; Delete one real and one fake
          (is (= 1 (sp/delete-entities storage :user [(:id r1) non-existent-id])))
          ;; Verify r1 is gone, r2 remains
          (is (nil? (sp/read-entity storage :user (:id r1))))
          (is (some? (sp/read-entity storage :user (:id r2)))))
        (finally
          (sp/close storage))))))


;; === Batch create edge cases ===

(deftest batch-create-edge-cases-test
  (testing "batch create fails at first record reports index 0"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f21"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000f22"
                                             :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (try
        ;; Create initial record
        (sp/create-entity storage :user {:email "exists@example.com"})
        ;; Batch create where first record violates constraint
        (try
          (sp/create-entities storage :user
                              [{:email "exists@example.com"}
                               {:email "new@example.com"}])
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (zero? (:batch-index (ex-data e))))
            (is (= 2 (:batch-size (ex-data e))))))
        (finally
          (sp/close storage)))))

  (testing "batch create fails at last record reports correct index"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000f31"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000f32"
                                             :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (sp/initialize storage schema)
      (try
        ;; Create initial record
        (sp/create-entity storage :user {:email "exists@example.com"})
        ;; Batch create where last record violates constraint
        (try
          (sp/create-entities storage :user
                              [{:email "new1@example.com"}
                               {:email "new2@example.com"}
                               {:email "exists@example.com"}])
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= 2 (:batch-index (ex-data e))))
            (is (= 3 (:batch-size (ex-data e))))))
        (finally
          (sp/close storage))))))


;; === Input validation tests ===

(deftest create-entity-invalid-data-test
  (testing "create-entity throws when data is not a map"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
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
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
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


(deftest delete-entities-invalid-ids-test
  (testing "delete-entities throws when ids is not sequential"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"ids must be a sequential collection or nil"
              (sp/delete-entities storage :user "not sequential")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"ids must be a sequential collection or nil"
              (sp/delete-entities storage :user #{:a :set})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"ids must be a sequential collection or nil"
              (sp/delete-entities storage :user 123)))
        (finally
          (sp/close storage))))))


;; === Error handling tests ===

(deftest classify-error-test
  (testing "classifies ExceptionInfo with :type in ex-data"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (let [ex (ex-info "Test error" {:type :test-error-type})]
          (is (= :test-error-type (sp/classify-error storage ex))))
        (finally
          (sp/close storage)))))

  (testing "returns :unknown-memory-error for ExceptionInfo without :type"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (let [ex (ex-info "Test error" {:some-other-key "value"})]
          (is (= :unknown-memory-error (sp/classify-error storage ex))))
        (finally
          (sp/close storage)))))

  (testing "returns :unknown-memory-error for non-ExceptionInfo"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (let [ex (Exception. "Test error")]
          (is (= :unknown-memory-error (sp/classify-error storage ex))))
        (finally
          (sp/close storage))))))


(deftest wrap-error-test
  (testing "wraps exception with storage context"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (let [original-ex (ex-info "Original error" {:type :original-error})
              wrapped (sp/wrap-error storage original-ex :create {:entity :user})]
          (is (instance? clojure.lang.ExceptionInfo wrapped))
          (is (re-find #"Memory storage error during create" (ex-message wrapped)))
          (is (= :original-error (:type (ex-data wrapped))))
          (is (= :create (:operation (ex-data wrapped))))
          (is (= :user (:entity (ex-data wrapped))))
          (is (= original-ex (ex-cause wrapped))))
        (finally
          (sp/close storage)))))

  (testing "wraps plain exception"
    (let [storage (mem/create-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (let [original-ex (Exception. "Plain error")
              wrapped (sp/wrap-error storage original-ex :read {:id #uuid "00000000-0000-0000-0000-000000000001"})]
          (is (instance? clojure.lang.ExceptionInfo wrapped))
          (is (re-find #"Memory storage error during read" (ex-message wrapped)))
          (is (= :unknown-memory-error (:type (ex-data wrapped))))
          (is (= :read (:operation (ex-data wrapped))))
          (is (= original-ex (ex-cause wrapped))))
        (finally
          (sp/close storage))))))


;; === Contract Tests for Concurrency and Deep Chains ===

(deftest concurrent-read-write-contract-test
  (contract/concurrent-read-write-test mem/create-storage sp/close))


(deftest deep-inheritance-chain-contract-test
  (contract/deep-inheritance-chain-test mem/create-storage sp/close))
