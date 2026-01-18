(ns graphden.storage-protocol.crud-validation-test
  "Tests for CRUD validation helpers."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === Storage Implementation Helpers tests ===

(deftest create-rw-lock-test
  (testing "creates ReentrantReadWriteLock"
    (let [lock (storage/create-rw-lock)]
      (is (instance? java.util.concurrent.locks.ReentrantReadWriteLock lock)))))


(deftest standard-crud-validations!-test
  (testing "passes for valid data"
    (is (nil? (storage/standard-crud-validations! :user {:name "test"} nil)))
    (is (nil? (storage/standard-crud-validations!
                :user
                {:name "test"}
                {:name {:required true}}))))

  (testing "throws for non-map data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/standard-crud-validations! :user "not a map" nil))))

  (testing "throws for missing required fields"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field.*missing"
          (storage/standard-crud-validations!
            :user
            {:other "field"}
            {:name {:required true}})))))


(deftest standard-batch-validations!-test
  (testing "passes for unique IDs"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (is (nil? (storage/standard-batch-validations! :user [{:id id1} {:id id2}])))))

  (testing "throws for duplicate IDs"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/standard-batch-validations! :user [{:id dup-id} {:id dup-id}]))))))


;; === validate-where-clause! tests ===

(deftest validate-where-clause!-test
  (testing "nil where clause is valid"
    (is (nil? (storage/validate-where-clause! nil))))

  (testing "empty map where clause is valid"
    (is (nil? (storage/validate-where-clause! {}))))

  (testing "map with conditions is valid"
    (is (nil? (storage/validate-where-clause! {:name "test" :age 25}))))

  (testing "throws for string where clause"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/validate-where-clause! "invalid"))))

  (testing "throws for vector where clause"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/validate-where-clause! [:field "="]))))

  (testing "exception contains correct data"
    (try
      (storage/validate-where-clause! 123)
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-where-clause (:type (ex-data e))))
        (is (= 123 (:where (ex-data e))))))))


;; === validate-where-clause-fields! tests ===

(deftest validate-where-clause-fields!-test
  (testing "nil where clause passes"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text} :email {:type :text}}
                nil))))

  (testing "known field passes"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text}}
                {:name "test"}))))

  (testing ":id is always valid"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text}}
                {:id (random-uuid)}))))

  (testing "unknown field throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown field"
          (storage/validate-where-clause-fields!
            :user
            {:name {:type :text}}
            {:unknown-field "value"}))))

  (testing "exception contains correct data for unknown field"
    (try
      (storage/validate-where-clause-fields!
        :user
        {:name {:type :text} :email {:type :text}}
        {:nonexistent "value"})
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/unknown-field (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :nonexistent (:field (ex-data e))))))))


;; === validate-where-clause-types! tests ===

(deftest validate-where-clause-types!-test
  (testing "nil where clause passes"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                nil))))

  (testing "correct type passes - text"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                {:name "test"}))))

  (testing "correct type passes - uuid"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:ref-id {:type :uuid}}
                {:ref-id (random-uuid)}))))

  (testing "correct type passes - int"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:age {:type :int}}
                {:age 25}))))

  (testing "correct type passes - bool"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:active {:type :bool}}
                {:active true}))))

  (testing ":id field accepts uuid"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {}
                {:id (random-uuid)}))))

  (testing "nil value passes (handled by nullability)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                {:name nil}))))

  (testing "wrong type throws - string for int"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:age {:type :int}}
            {:age "twenty-five"}))))

  (testing "wrong type throws - int for text"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:name {:type :text}}
            {:name 123}))))

  (testing "exception contains correct data"
    (try
      (storage/validate-where-clause-types!
        :user
        {:age {:type :int}}
        {:age "wrong"})
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/type-mismatch (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :age (:field (ex-data e))))
        (is (= :int (:expected-type (ex-data e))))))))


;; === validate-entity-name! tests ===

(deftest validate-entity-name!-test
  (testing "valid keyword passes"
    (is (nil? (storage/validate-entity-name! :user "create")))
    (is (nil? (storage/validate-entity-name! :user-profile "create")))
    (is (nil? (storage/validate-entity-name! :user_profile "create")))
    (is (nil? (storage/validate-entity-name! :a123 "create"))))

  (testing "throws for nil entity-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (storage/validate-entity-name! nil "create"))))

  (testing "throws for string entity-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (storage/validate-entity-name! "user" "create"))))

  (testing "throws for entity-name starting with number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! :123user "create"))))

  (testing "throws for entity-name with uppercase"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! :User "create"))))

  (testing "throws for entity-name exceeding max length"
    (let [long-name (keyword (str/join (repeat 65 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (storage/validate-entity-name! long-name "create")))))

  (testing "exception contains correct data"
    (try
      (storage/validate-entity-name! "not-keyword" "delete")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-entity-name (:type (ex-data e))))
        (is (= "not-keyword" (:entity-name (ex-data e))))
        (is (= "delete" (:operation (ex-data e))))))))
