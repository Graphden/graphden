(ns graphden.storage-protocol.validation-test
  "Tests for storage-protocol.validation - CRUD and credential validation."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.validation :as v]))


;; === validate-required-fields! tests ===

(deftest validate-required-fields!-test
  (testing "passes when all required fields present"
    (let [fields {:name {:type :text :nullable? false}
                  :email {:type :text :nullable? false}}
          data {:name "John" :email "john@example.com"}]
      (is (nil? (v/validate-required-fields! :user fields data)))))

  (testing "passes for nullable fields when nil"
    (let [fields {:name {:type :text :nullable? false}
                  :bio {:type :text :nullable? true}}
          data {:name "John" :bio nil}]
      (is (nil? (v/validate-required-fields! :user fields data)))))

  (testing "passes for nullable fields when missing"
    (let [fields {:name {:type :text :nullable? false}
                  :bio {:type :text :nullable? true}}
          data {:name "John"}]
      (is (nil? (v/validate-required-fields! :user fields data)))))

  (testing "ignores :id field (auto-generated)"
    (let [fields {:id {:type :uuid :nullable? false}
                  :name {:type :text :nullable? false}}
          data {:name "John"}]  ; no :id
      (is (nil? (v/validate-required-fields! :user fields data)))))

  (testing "throws for missing required field"
    (let [fields {:name {:type :text :nullable? false}
                  :email {:type :text :nullable? false}}
          data {:name "John"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Required field 'email' is missing"
            (v/validate-required-fields! :user fields data)))))

  (testing "throws for nil required field"
    (let [fields {:name {:type :text :nullable? false}}
          data {:name nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Required field 'name' is missing or nil"
            (v/validate-required-fields! :user fields data)))))

  (testing "error contains entity and field info"
    (let [fields {:email {:type :text :nullable? false}}
          data {}]
      (try
        (v/validate-required-fields! :user fields data)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/required-field-missing (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= :email (:field (ex-data e)))))))))


;; === validate-no-duplicate-ids! tests ===

(deftest validate-no-duplicate-ids!-test
  (testing "passes for unique IDs"
    (let [data [{:id 1 :name "A"} {:id 2 :name "B"} {:id 3 :name "C"}]]
      (is (nil? (v/validate-no-duplicate-ids! :user data)))))

  (testing "passes when no IDs provided"
    (let [data [{:name "A"} {:name "B"}]]
      (is (nil? (v/validate-no-duplicate-ids! :user data)))))

  (testing "passes for mixed (some with ID, some without)"
    (let [data [{:id 1 :name "A"} {:name "B"} {:id 2 :name "C"}]]
      (is (nil? (v/validate-no-duplicate-ids! :user data)))))

  (testing "throws for duplicate IDs"
    (let [data [{:id 1 :name "A"} {:id 1 :name "B"}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs found"
            (v/validate-no-duplicate-ids! :user data)))))

  (testing "error contains duplicate IDs"
    (let [data [{:id 1} {:id 1} {:id 2} {:id 2} {:id 3}]]
      (try
        (v/validate-no-duplicate-ids! :user data)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/duplicate-ids (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= #{1 2} (set (:duplicate-ids (ex-data e))))))))))


;; === validate-data-is-map! tests ===

(deftest validate-data-is-map!-test
  (testing "passes for map"
    (is (nil? (v/validate-data-is-map! :user {:name "John"}))))

  (testing "passes for empty map"
    (is (nil? (v/validate-data-is-map! :user {}))))

  (testing "throws for nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (v/validate-data-is-map! :user nil))))

  (testing "throws for string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (v/validate-data-is-map! :user "not a map"))))

  (testing "throws for vector"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (v/validate-data-is-map! :user [1 2 3]))))

  (testing "error contains data type"
    (try
      (v/validate-data-is-map! :user "string")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-data (:type (ex-data e))))
        (is (= :user (:entity-name (ex-data e))))
        (is (= String (:data-type (ex-data e))))))))


;; === validate-where-clause! tests ===

(deftest validate-where-clause!-test
  (testing "passes for nil"
    (is (nil? (v/validate-where-clause! nil))))

  (testing "passes for map"
    (is (nil? (v/validate-where-clause! {:status :active}))))

  (testing "passes for empty map"
    (is (nil? (v/validate-where-clause! {}))))

  (testing "throws for string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (v/validate-where-clause! "status = 'active'"))))

  (testing "throws for vector"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (v/validate-where-clause! [:= :status :active]))))

  (testing "error contains type info"
    (try
      (v/validate-where-clause! 123)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-where-clause (:type (ex-data e))))
        (is (= Long (:where-type (ex-data e))))))))


;; === validate-entity-name! tests ===

(deftest validate-entity-name!-test
  (testing "passes for valid entity names"
    (is (nil? (v/validate-entity-name! :user "query")))
    (is (nil? (v/validate-entity-name! :user-profile "query")))
    (is (nil? (v/validate-entity-name! :order_item "query")))
    (is (nil? (v/validate-entity-name! :a1 "query"))))

  (testing "throws for non-keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (v/validate-entity-name! "user" "query")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (v/validate-entity-name! nil "query"))))

  (testing "throws for name exceeding 64 chars"
    (let [long-name (keyword (str/join (repeat 65 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (v/validate-entity-name! long-name "query")))))

  (testing "throws for name not starting with letter"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (v/validate-entity-name! :123user "query")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (v/validate-entity-name! :_user "query"))))

  (testing "throws for uppercase letters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (v/validate-entity-name! :User "query"))))

  (testing "throws for special characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (v/validate-entity-name! :user.profile "query")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (v/validate-entity-name! (keyword "user@domain") "query"))))

  (testing "error contains operation"
    (try
      (v/validate-entity-name! "string" "create-entity")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-entity-name (:type (ex-data e))))
        (is (= "create-entity" (:operation (ex-data e))))))))


;; === validate-credential-length! tests ===

(deftest validate-credential-length!-test
  (testing "passes for short strings"
    (is (nil? (v/validate-credential-length! "short" "test" 100))))

  (testing "passes for string at max length"
    (is (nil? (v/validate-credential-length! (str/join (repeat 100 "x")) "test" 100))))

  (testing "throws for string exceeding max"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeds maximum length"
          (v/validate-credential-length! (str/join (repeat 101 "x")) "test" 100))))

  (testing "passes for nil"
    (is (nil? (v/validate-credential-length! nil "test" 100))))

  (testing "passes for non-strings"
    (is (nil? (v/validate-credential-length! 123 "test" 100))))

  (testing "error contains details"
    (try
      (v/validate-credential-length! "too long" "param" 5)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/credential-too-long (:type (ex-data e))))
        (is (= "param" (:param (ex-data e))))
        (is (= 5 (:max-length (ex-data e))))
        (is (= 8 (:actual-length (ex-data e))))))))


;; === validate-no-control-chars! tests ===

(deftest validate-no-control-chars!-test
  (testing "passes for normal strings"
    (is (nil? (v/validate-no-control-chars! "normal text" "test"))))

  (testing "passes for strings with allowed whitespace"
    (is (nil? (v/validate-no-control-chars! "has\ttab" "test")))
    (is (nil? (v/validate-no-control-chars! "has\nnewline" "test")))
    (is (nil? (v/validate-no-control-chars! "has\rcarriage-return" "test"))))

  (testing "throws for null byte"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-no-control-chars! "has\u0000null" "test"))))

  (testing "throws for bell character"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-no-control-chars! "has\u0007bell" "test"))))

  (testing "throws for DEL character"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-no-control-chars! "has\u007Fdel" "test"))))

  (testing "passes for nil"
    (is (nil? (v/validate-no-control-chars! nil "test"))))

  (testing "error contains param name"
    (try
      (v/validate-no-control-chars! "bad\u0000string" "password")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/invalid-credential (:type (ex-data e))))
        (is (= "password" (:param (ex-data e))))))))


;; === validate-credentials! tests ===

(deftest validate-credentials!-test
  (testing "passes for valid credentials"
    (is (nil? (v/validate-credentials! "username" "password"))))

  (testing "throws for username too long"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username exceeds maximum length"
          (v/validate-credentials! (str/join (repeat 200 "x")) "pass"))))

  (testing "throws for password too long"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password exceeds maximum length"
          (v/validate-credentials! "user" (str/join (repeat 2000 "x"))))))

  (testing "throws for username with control chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-credentials! "user\u0000name" "password"))))

  (testing "throws for password with control chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-credentials! "username" "pass\u0000word")))))


;; === validate-jdbc-url! tests ===

(deftest validate-jdbc-url!-test
  (testing "passes for valid JDBC URL"
    (is (nil? (v/validate-jdbc-url! "jdbc:postgresql://localhost:5432/db"))))

  (testing "throws for URL too long"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeds maximum length"
          (v/validate-jdbc-url! (str "jdbc:" (str/join (repeat 5000 "x")))))))

  (testing "throws for URL with control chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-jdbc-url! "jdbc:postgresql://\u0000localhost/db")))))


;; === canonical-type? tests ===

(deftest canonical-type?-test
  (testing "returns true for canonical types"
    (is (true? (v/canonical-type? :uuid)))
    (is (true? (v/canonical-type? :text)))
    (is (true? (v/canonical-type? :int)))
    (is (true? (v/canonical-type? :bool)))
    (is (true? (v/canonical-type? :numeric)))
    (is (true? (v/canonical-type? :timestamptz)))
    (is (true? (v/canonical-type? :jsonb)))
    (is (true? (v/canonical-type? :bytes)))
    (is (true? (v/canonical-type? :ref)))
    (is (true? (v/canonical-type? :enum)))
    (is (true? (v/canonical-type? :union))))

  (testing "returns false for unknown types"
    (is (false? (v/canonical-type? :string)))
    (is (false? (v/canonical-type? :integer)))
    (is (false? (v/canonical-type? :unknown)))))


;; === reference-type? tests ===

(deftest reference-type?-test
  (testing "returns true for ref"
    (is (true? (v/reference-type? :ref))))

  (testing "returns false for non-reference types"
    (is (false? (v/reference-type? :uuid)))
    (is (false? (v/reference-type? :text)))
    (is (false? (v/reference-type? :jsonb)))))


;; === complex-type? tests ===

(deftest complex-type?-test
  (testing "returns true for complex types"
    (is (true? (v/complex-type? :jsonb)))
    (is (true? (v/complex-type? :union))))

  (testing "returns false for primitive types"
    (is (false? (v/complex-type? :uuid)))
    (is (false? (v/complex-type? :text)))
    (is (false? (v/complex-type? :int)))
    (is (false? (v/complex-type? :ref)))))


;; === type-category tests ===

(deftest type-category-test
  (testing "categorizes all canonical types"
    (is (= :primitive (v/type-category :uuid)))
    (is (= :primitive (v/type-category :text)))
    (is (= :primitive (v/type-category :int)))
    (is (= :primitive (v/type-category :bool)))
    (is (= :primitive (v/type-category :numeric)))
    (is (= :primitive (v/type-category :timestamptz)))
    (is (= :primitive (v/type-category :bytes)))
    (is (= :primitive (v/type-category :enum)))
    (is (= :complex (v/type-category :jsonb)))
    (is (= :complex (v/type-category :union)))
    (is (= :reference (v/type-category :ref)))))
