(ns graphden.memory-storage.crud-test
  "Tests for memory-storage.crud module."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.memory-storage.crud :as crud]))


;; === Test fixtures ===

(def test-schema
  {:entities {:user {:fields {:id {:type :uuid :nullable? false}
                              :name {:type :text :nullable? false}
                              :email {:type :text :nullable? true}
                              :age {:type :int :nullable? true}}
                     :constraints [{:type :unique :fields [:email]}]}}})


(defn make-test-state
  []
  (merge test-schema {:data {}}))


;; === get-entity-data tests ===

(deftest get-entity-data-test
  (testing "returns empty map when no data"
    (is (= {} (crud/get-entity-data (make-test-state) :user))))

  (testing "returns data when present"
    (let [id (random-uuid)
          state (assoc-in (make-test-state) [:data :user id] {:id id :name "Alice"})]
      (is (= {id {:id id :name "Alice"}} (crud/get-entity-data state :user)))))

  (testing "returns empty map for unknown entity"
    (is (= {} (crud/get-entity-data (make-test-state) :unknown)))))


;; === get-entity-fields tests ===

(deftest get-entity-fields-test
  (testing "returns fields for known entity"
    (let [fields (crud/get-entity-fields (make-test-state) :user)]
      (is (contains? fields :name))
      (is (contains? fields :email))))

  (testing "returns nil for unknown entity"
    (is (nil? (crud/get-entity-fields (make-test-state) :unknown)))))


;; === validate-entity-exists! tests ===

(deftest validate-entity-exists!-test
  (testing "passes for known entity"
    (is (nil? (crud/validate-entity-exists! (make-test-state) :user))))

  (testing "throws for unknown entity"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Entity not found in schema"
          (crud/validate-entity-exists! (make-test-state) :unknown))))

  (testing "error has correct type"
    (try
      (crud/validate-entity-exists! (make-test-state) :bad)
      (is false "should throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :entity-not-in-schema (:type (ex-data e))))
        (is (= :bad (:entity (ex-data e))))))))


;; === get-record tests ===

(deftest get-record-test
  (testing "returns record when exists"
    (let [id (random-uuid)
          record {:id id :name "Alice"}
          state (assoc-in (make-test-state) [:data :user id] record)]
      (is (= record (crud/get-record state :user id)))))

  (testing "returns nil when not exists"
    (is (nil? (crud/get-record (make-test-state) :user (random-uuid))))))


;; === validate-required-fields! tests ===

(deftest validate-required-fields!-test
  (testing "passes when required fields present"
    (is (nil? (crud/validate-required-fields! (make-test-state) :user
                                              {:name "Alice"}))))

  (testing "throws when required field missing"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-required-fields! (make-test-state) :user {})))))


;; === validate-unique-constraints! tests ===

(deftest validate-unique-constraints!-test
  (testing "passes when no conflicts"
    (is (nil? (crud/validate-unique-constraints! (make-test-state) :user
                                                 {:email "new@test.com"} nil))))

  (testing "passes when value is nil (NULL handling)"
    (let [id1 (random-uuid)
          state (assoc-in (make-test-state) [:data :user id1]
                          {:id id1 :name "Alice" :email nil})]
      ;; Both records have nil email - should not conflict
      (is (nil? (crud/validate-unique-constraints! state :user
                                                   {:email nil} nil)))))

  (testing "throws when email conflicts"
    (let [id1 (random-uuid)
          state (assoc-in (make-test-state) [:data :user id1]
                          {:id id1 :name "Alice" :email "test@test.com"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unique constraint violation"
            (crud/validate-unique-constraints! state :user
                                               {:email "test@test.com"} nil)))))

  (testing "passes when conflict is excluded by id (for updates)"
    (let [id1 (random-uuid)
          state (assoc-in (make-test-state) [:data :user id1]
                          {:id id1 :name "Alice" :email "test@test.com"})]
      ;; Same email is ok when updating the same record
      (is (nil? (crud/validate-unique-constraints! state :user
                                                   {:email "test@test.com"} id1))))))


;; === create-record-atomic! tests ===

(deftest create-record-atomic!-test
  (testing "creates record successfully"
    (let [state-atom (atom (make-test-state))
          id (random-uuid)
          record {:id id :name "Alice" :email "alice@test.com"}
          result (crud/create-record-atomic! state-atom :user record)]
      (is (= record result))
      (is (= record (get-in @state-atom [:data :user id])))))

  (testing "throws on required field missing"
    (let [state-atom (atom (make-test-state))
          id (random-uuid)]
      (is (thrown? clojure.lang.ExceptionInfo
            (crud/create-record-atomic! state-atom :user {:id id})))))

  (testing "throws on unique constraint violation"
    (let [state-atom (atom (make-test-state))
          id1 (random-uuid)
          id2 (random-uuid)]
      (crud/create-record-atomic! state-atom :user {:id id1 :name "Alice" :email "test@test.com"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unique constraint violation"
            (crud/create-record-atomic! state-atom :user {:id id2 :name "Bob" :email "test@test.com"}))))))


;; === update-record-atomic! tests ===

(deftest update-record-atomic!-test
  (testing "updates record successfully"
    (let [state-atom (atom (make-test-state))
          id (random-uuid)
          _ (crud/create-record-atomic! state-atom :user {:id id :name "Alice" :email "alice@test.com"})
          result (crud/update-record-atomic! state-atom :user id {:name "Alicia"})]
      (is (= "Alicia" (:name result)))
      (is (= "alice@test.com" (:email result)))))

  (testing "throws when record not found"
    (let [state-atom (atom (make-test-state))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Entity not found"
            (crud/update-record-atomic! state-atom :user (random-uuid) {:name "Bob"})))))

  (testing "error data has correct type"
    (let [state-atom (atom (make-test-state))
          id (random-uuid)]
      (try
        (crud/update-record-atomic! state-atom :user id {:name "Bob"})
        (is false "should throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :not-found (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= id (:id (ex-data e)))))))))


;; === remove-record! tests ===

(deftest remove-record!-test
  (testing "returns true when record existed"
    (let [state-atom (atom (make-test-state))
          id (random-uuid)]
      (crud/create-record-atomic! state-atom :user {:id id :name "Alice"})
      (is (true? (crud/remove-record! state-atom :user id)))
      (is (nil? (get-in @state-atom [:data :user id])))))

  (testing "returns false when record did not exist"
    (let [state-atom (atom (make-test-state))]
      (is (false? (crud/remove-record! state-atom :user (random-uuid)))))))


;; === create-records-atomic! tests ===

(deftest create-records-atomic!-test
  (testing "creates multiple records"
    (let [state-atom (atom (make-test-state))
          id1 (random-uuid)
          id2 (random-uuid)
          records [{:id id1 :name "Alice"} {:id id2 :name "Bob"}]
          result (crud/create-records-atomic! state-atom :user records)]
      (is (= 2 (count result)))
      (is (some? (get-in @state-atom [:data :user id1])))
      (is (some? (get-in @state-atom [:data :user id2])))))

  (testing "wraps error with batch context on failure"
    (let [state-atom (atom (make-test-state))
          id1 (random-uuid)
          id2 (random-uuid)
          records [{:id id1 :name "Alice"}
                   {:id id2}]]  ; missing required field
      (try
        (crud/create-records-atomic! state-atom :user records)
        (is false "should throw")
        (catch clojure.lang.ExceptionInfo e
          ;; The outer type is the original error type, but batch info is added
          (is (= 1 (:batch-index (ex-data e))))
          (is (= 2 (:batch-size (ex-data e))))
          (is (= id2 (:failed-id (ex-data e)))))))))


;; === read-records tests ===

(deftest read-records-test
  (testing "returns found records"
    (let [id1 (random-uuid)
          id2 (random-uuid)
          id3 (random-uuid)
          state (-> (make-test-state)
                    (assoc-in [:data :user id1] {:id id1 :name "Alice"})
                    (assoc-in [:data :user id2] {:id id2 :name "Bob"}))
          result (crud/read-records state :user [id1 id2 id3])]
      (is (= 2 (count result)))
      (is (contains? result id1))
      (is (contains? result id2))
      (is (not (contains? result id3)))))

  (testing "returns empty map for no matches"
    (is (= {} (crud/read-records (make-test-state) :user [(random-uuid)]))))

  (testing "handles empty ids list"
    (is (= {} (crud/read-records (make-test-state) :user [])))))


;; === remove-records! tests ===

(deftest remove-records!-test
  (testing "removes multiple records and returns count"
    (let [state-atom (atom (make-test-state))
          id1 (random-uuid)
          id2 (random-uuid)
          id3 (random-uuid)]
      (crud/create-record-atomic! state-atom :user {:id id1 :name "Alice"})
      (crud/create-record-atomic! state-atom :user {:id id2 :name "Bob"})
      (let [removed (crud/remove-records! state-atom :user [id1 id2 id3])]
        (is (= 2 removed))
        (is (nil? (get-in @state-atom [:data :user id1])))
        (is (nil? (get-in @state-atom [:data :user id2]))))))

  (testing "returns 0 for empty ids"
    (let [state-atom (atom (make-test-state))]
      (is (zero? (crud/remove-records! state-atom :user [])))))

  (testing "returns 0 for nil ids"
    (let [state-atom (atom (make-test-state))]
      (is (zero? (crud/remove-records! state-atom :user nil)))))

  (testing "throws for non-sequential ids"
    (let [state-atom (atom (make-test-state))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"ids must be a sequential collection"
            (crud/remove-records! state-atom :user #{1 2 3})))))  ; set is not sequential

  (testing "error has correct type for non-sequential"
    (let [state-atom (atom (make-test-state))]
      (try
        (crud/remove-records! state-atom :user {:a 1})
        (is false "should throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-data (:type (ex-data e))))
          (is (= :user (:entity-name (ex-data e)))))))))
