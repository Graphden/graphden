(ns graphden.storage.protocol.crud-validation-test
  "Tests for CRUD validation helpers."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.crud-validation]
    [graphden.storage.protocol.interface :as storage]))


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
            {:name {:required true}}))))

  (testing "passes when field is nullable and missing"
    (is (nil? (storage/standard-crud-validations!
                :user
                {}
                {:name {:nullable? true}}))))

  (testing "passes when field is nullable and nil"
    (is (nil? (storage/standard-crud-validations!
                :user
                {:name nil}
                {:name {:nullable? true}}))))

  (testing "throws when required field is nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field.*missing"
          (storage/standard-crud-validations!
            :user
            {:name nil}
            {:name {:required true}}))))

  (testing "skips :id field validation"
    ;; :id is auto-generated so should not be validated as required
    (is (nil? (storage/standard-crud-validations!
                :user
                {:name "test"}
                {:id {:required true}
                 :name {:required true}})))))


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
        (is (= :int (:expected-type (ex-data e)))))))

  ;; Additional type coverage tests
  (testing "correct type passes - numeric (float)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:balance {:type :numeric}}
                {:balance 123.45}))))

  (testing "correct type passes - numeric (decimal)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:balance {:type :numeric}}
                {:balance 123.45M}))))

  (testing "correct type passes - timestamptz"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:created-at {:type :timestamptz}}
                {:created-at (java.util.Date.)}))))

  (testing "correct type passes - bytes"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:data {:type :bytes}}
                {:data (byte-array [1 2 3])}))))

  (testing "correct type passes - jsonb (map)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:metadata {:type :jsonb}}
                {:metadata {:key "value"}}))))

  (testing "correct type passes - jsonb (vector)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:tags {:type :jsonb}}
                {:tags ["a" "b" "c"]}))))

  (testing "correct type passes - enum (keyword)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:status {:type :enum}}
                {:status :active}))))

  (testing "unknown field in where is ignored for type check"
    ;; If field is not in fields spec, no type check happens
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                {:unknown-field 123})))))


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


;; === infer-actual-type tests ===

(deftest infer-actual-type-test
  (testing "infers :nil for nil"
    (is (= :nil (#'graphden.storage.protocol.crud-validation/infer-actual-type nil))))

  (testing "infers :uuid for UUIDs"
    (is (= :uuid (#'graphden.storage.protocol.crud-validation/infer-actual-type (random-uuid)))))

  (testing "infers :text for strings"
    (is (= :text (#'graphden.storage.protocol.crud-validation/infer-actual-type "hello"))))

  (testing "infers :int for integers"
    (is (= :int (#'graphden.storage.protocol.crud-validation/infer-actual-type 42)))
    (is (= :int (#'graphden.storage.protocol.crud-validation/infer-actual-type (long 42)))))

  (testing "infers :bool for booleans"
    (is (= :bool (#'graphden.storage.protocol.crud-validation/infer-actual-type true)))
    (is (= :bool (#'graphden.storage.protocol.crud-validation/infer-actual-type false))))

  (testing "infers :numeric for floats and decimals"
    (is (= :numeric (#'graphden.storage.protocol.crud-validation/infer-actual-type 3.14)))
    (is (= :numeric (#'graphden.storage.protocol.crud-validation/infer-actual-type 3.14M))))

  (testing "infers :timestamptz for inst"
    (is (= :timestamptz (#'graphden.storage.protocol.crud-validation/infer-actual-type (java.util.Date.)))))

  (testing "infers :bytes for byte arrays"
    (is (= :bytes (#'graphden.storage.protocol.crud-validation/infer-actual-type (byte-array [1 2 3])))))

  (testing "infers :enum for keywords"
    (is (= :enum (#'graphden.storage.protocol.crud-validation/infer-actual-type :active))))

  (testing "infers :jsonb for maps"
    (is (= :jsonb (#'graphden.storage.protocol.crud-validation/infer-actual-type {:a 1}))))

  (testing "infers :jsonb for vectors"
    (is (= :jsonb (#'graphden.storage.protocol.crud-validation/infer-actual-type [1 2 3]))))

  (testing "infers :unknown for other types"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type (Object.))))
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type #"regex")))))


;; === check-type-match tests ===

(deftest check-type-match-test
  (testing "returns nil for matching types"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match "hello" :text)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match 42 :int)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match true :bool)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (random-uuid) :uuid)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match 3.14 :numeric)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match 3.14M :numeric)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (java.util.Date.) :timestamptz)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (byte-array [1 2 3]) :bytes)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match {:a 1} :jsonb)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match [1 2 3] :jsonb)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match :active :enum))))

  (testing "returns nil for nil value (any type)"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :text)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :int)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :uuid))))

  (testing "returns error map for type mismatch"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "string" :int)]
      (is (map? result))
      (is (= :int (:expected result)))
      (is (= :text (:actual result)))))

  (testing "returns error map for int instead of text"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match 123 :text)]
      (is (= :text (:expected result)))
      (is (= :int (:actual result)))))

  (testing "returns error map for string instead of bool"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "true" :bool)]
      (is (= :bool (:expected result)))
      (is (= :text (:actual result)))))

  (testing "returns error map for int instead of uuid"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match 123 :uuid)]
      (is (= :uuid (:expected result)))
      (is (= :int (:actual result)))))

  (testing "returns error map for string instead of jsonb"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "not-json" :jsonb)]
      (is (= :jsonb (:expected result)))
      (is (= :text (:actual result))))))


;; === validate-required-fields! tests ===

(deftest validate-required-fields!-test
  (testing "passes when all required fields present"
    (is (nil? (storage/validate-required-fields!
                :user
                {:name {:type :text}}
                {:name "John"}))))

  (testing "passes when nullable field is missing"
    (is (nil? (storage/validate-required-fields!
                :user
                {:name {:type :text :nullable? true}}
                {}))))

  (testing "passes when nullable field is nil"
    (is (nil? (storage/validate-required-fields!
                :user
                {:name {:type :text :nullable? true}}
                {:name nil}))))

  (testing "throws when required field is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field"
          (storage/validate-required-fields!
            :user
            {:name {:type :text}}
            {}))))

  (testing "throws when required field is nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing or nil"
          (storage/validate-required-fields!
            :user
            {:name {:type :text}}
            {:name nil}))))

  (testing "skips :id field validation"
    (is (nil? (storage/validate-required-fields!
                :user
                {:id {:type :uuid} :name {:type :text}}
                {:name "John"}))))

  (testing "exception contains entity and field info"
    (try
      (storage/validate-required-fields!
        :user
        {:email {:type :text}}
        {})
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/required-field-missing (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :email (:field (ex-data e))))))))


;; === validate-no-duplicate-ids! tests ===

(deftest validate-no-duplicate-ids!-test
  (testing "passes for unique IDs"
    (is (nil? (storage/validate-no-duplicate-ids!
                :user
                [{:id (random-uuid)} {:id (random-uuid)}]))))

  (testing "passes for records without IDs"
    (is (nil? (storage/validate-no-duplicate-ids!
                :user
                [{:name "A"} {:name "B"}]))))

  (testing "passes for mix of IDs and no IDs"
    (is (nil? (storage/validate-no-duplicate-ids!
                :user
                [{:id (random-uuid)} {:name "B"} {:id (random-uuid)}]))))

  (testing "throws for duplicate IDs"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/validate-no-duplicate-ids!
              :user
              [{:id dup-id} {:id dup-id}])))))

  (testing "exception contains duplicate IDs"
    (let [dup-id (random-uuid)]
      (try
        (storage/validate-no-duplicate-ids!
          :user
          [{:id dup-id} {:id dup-id}])
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/duplicate-ids (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= [dup-id] (:duplicate-ids (ex-data e))))))))

  (testing "detects multiple duplicate IDs"
    (let [dup1 (random-uuid)
          dup2 (random-uuid)]
      (try
        (storage/validate-no-duplicate-ids!
          :user
          [{:id dup1} {:id dup1} {:id dup2} {:id dup2}])
        (catch clojure.lang.ExceptionInfo e
          (is (= 2 (count (:duplicate-ids (ex-data e))))))))))


;; === validate-data-is-map! tests ===

(deftest validate-data-is-map!-test
  (testing "passes for map data"
    (is (nil? (storage/validate-data-is-map! :user {})))
    (is (nil? (storage/validate-data-is-map! :user {:name "John"}))))

  (testing "throws for string data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user "not a map"))))

  (testing "throws for vector data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user [1 2 3]))))

  (testing "throws for nil data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user nil))))

  (testing "throws for number data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user 123))))

  (testing "exception contains data info"
    (try
      (storage/validate-data-is-map! :product [1 2 3])
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-data (:type (ex-data e))))
        (is (= :product (:entity-name (ex-data e))))
        (is (= [1 2 3] (:data (ex-data e))))))))
