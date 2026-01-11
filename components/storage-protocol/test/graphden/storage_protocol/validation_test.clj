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


;; === validate-where-clause-fields! tests ===

(deftest validate-where-clause-fields!-test
  (let [fields {:name {:type :text}
                :email {:type :text}
                :status {:type :enum}}]

    (testing "passes for nil where"
      (is (nil? (v/validate-where-clause-fields! :user fields nil))))

    (testing "passes for empty where"
      (is (nil? (v/validate-where-clause-fields! :user fields {}))))

    (testing "passes for known fields"
      (is (nil? (v/validate-where-clause-fields! :user fields {:name "Alice"})))
      (is (nil? (v/validate-where-clause-fields! :user fields {:name "Alice" :status :active}))))

    (testing "passes for :id field even if not in fields"
      (is (nil? (v/validate-where-clause-fields! :user fields {:id (random-uuid)}))))

    (testing "throws for unknown field"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown field 'unknown'"
            (v/validate-where-clause-fields! :user fields {:unknown "value"}))))

    (testing "error contains details"
      (try
        (v/validate-where-clause-fields! :user fields {:bad-field 123})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/unknown-field (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= :bad-field (:field (ex-data e))))
          (is (contains? (set (:known-fields (ex-data e))) :name)))))))


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

  (testing "rejects tab (log injection prevention)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-no-control-chars! "has\ttab" "test"))))

  (testing "rejects newline (log injection prevention)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-no-control-chars! "has\nnewline" "test"))))

  (testing "rejects carriage return (log injection prevention)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid control characters"
          (v/validate-no-control-chars! "has\rcarriage-return" "test"))))

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


;; === validate-where-clause-types! tests ===

(deftest validate-where-clause-types!-test
  (let [fields {:name {:type :text}
                :age {:type :int}
                :active {:type :bool}
                :score {:type :numeric}
                :data {:type :jsonb}
                :status {:type :enum}
                :parent-id {:type :ref}
                :created {:type :timestamptz}
                :content {:type :bytes}
                :meta {:type :union}}]

    (testing "passes for nil where"
      (is (nil? (v/validate-where-clause-types! :user fields nil))))

    (testing "passes for empty where"
      (is (nil? (v/validate-where-clause-types! :user fields {}))))

    (testing "passes for matching types"
      (is (nil? (v/validate-where-clause-types! :user fields {:name "Alice"})))
      (is (nil? (v/validate-where-clause-types! :user fields {:age 25})))
      (is (nil? (v/validate-where-clause-types! :user fields {:active true})))
      (is (nil? (v/validate-where-clause-types! :user fields {:score 99.5})))
      (is (nil? (v/validate-where-clause-types! :user fields {:data {:key "value"}})))
      (is (nil? (v/validate-where-clause-types! :user fields {:status :active})))
      (is (nil? (v/validate-where-clause-types! :user fields {:parent-id (random-uuid)})))
      (is (nil? (v/validate-where-clause-types! :user fields {:created (java.util.Date.)}))))

    (testing "passes for nil values (nullable check is separate)"
      (is (nil? (v/validate-where-clause-types! :user fields {:name nil}))))

    (testing "passes for :id field as UUID"
      (is (nil? (v/validate-where-clause-types! :user fields {:id (random-uuid)}))))

    (testing "allows int for numeric type"
      (is (nil? (v/validate-where-clause-types! :user fields {:score 100}))))

    (testing "rejects text for jsonb type (only maps and vectors)"
      ;; ft/valid-type? for jsonb only accepts maps and vectors, not JSON strings
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (v/validate-where-clause-types! :user fields {:data "{\"key\": \"value\"}"}))))

    (testing "allows any value for union type"
      (is (nil? (v/validate-where-clause-types! :user fields {:meta "string"})))
      (is (nil? (v/validate-where-clause-types! :user fields {:meta 123})))
      (is (nil? (v/validate-where-clause-types! :user fields {:meta {:nested "map"}}))))

    (testing "allows uuid for ref type"
      (is (nil? (v/validate-where-clause-types! :user fields {:parent-id (random-uuid)}))))

    (testing "throws for type mismatch - string for int"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch.*expected int, got text"
            (v/validate-where-clause-types! :user fields {:age "twenty-five"}))))

    (testing "throws for type mismatch - string for bool"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch.*expected bool, got text"
            (v/validate-where-clause-types! :user fields {:active "yes"}))))

    (testing "throws for type mismatch - int for text"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch.*expected text, got int"
            (v/validate-where-clause-types! :user fields {:name 123}))))

    (testing "throws for type mismatch - string for uuid on :id"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch.*expected uuid, got text"
            (v/validate-where-clause-types! :user fields {:id "not-a-uuid"}))))

    (testing "error contains details"
      (try
        (v/validate-where-clause-types! :user fields {:age "string-value"})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/type-mismatch (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= :age (:field (ex-data e))))
          (is (= :int (:expected-type (ex-data e))))
          (is (= :text (:actual-type (ex-data e)))))))))


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


;; === Additional type validation edge case tests ===

(deftest validate-where-clause-types-edge-cases-test
  (let [fields {:float-score {:type :numeric}
                :raw-data {:type :bytes}
                :timestamp {:type :timestamptz}
                :list-data {:type :jsonb}}]

    (testing "passes for decimal value with numeric type"
      (is (nil? (v/validate-where-clause-types! :entity fields {:float-score 3.14M}))))

    (testing "passes for bytes array"
      (is (nil? (v/validate-where-clause-types! :entity fields {:raw-data (byte-array [1 2 3])}))))

    (testing "passes for Instant timestamp"
      (is (nil? (v/validate-where-clause-types! :entity fields {:timestamp (java.time.Instant/now)}))))

    (testing "fails for list as jsonb (only maps and vectors accepted)"
      ;; ft/valid-type? for jsonb only accepts maps and vectors, not lists
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (v/validate-where-clause-types! :entity fields {:list-data (list 1 2 3)}))))

    (testing "passes for vector as jsonb"
      (is (nil? (v/validate-where-clause-types! :entity fields {:list-data [1 2 3]}))))

    (testing "passes for unknown field (no spec to check)"
      (is (nil? (v/validate-where-clause-types! :entity fields {:unknown-field "anything"}))))

    (testing "throws for bool where int expected"
      (let [int-field {:count {:type :int}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Type mismatch"
              (v/validate-where-clause-types! :entity int-field {:count true})))))

    (testing "throws for enum where text expected"
      (let [text-field {:name {:type :text}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Type mismatch"
              (v/validate-where-clause-types! :entity text-field {:name :keyword-value})))))

    (testing "throws for text where timestamptz expected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (v/validate-where-clause-types! :entity fields {:timestamp "2024-01-01"}))))))
