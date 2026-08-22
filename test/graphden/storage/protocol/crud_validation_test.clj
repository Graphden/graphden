(ns graphden.storage.protocol.crud-validation-test
  "Tests for CRUD validation helpers."
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]
    [graphden.storage.protocol.crud-validation]))


;; === Storage Implementation Helpers tests ===

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
      (is false "expected storage/validate-where-clause! to throw")
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
      (is false "expected storage/validate-where-clause-fields! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/unknown-field (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :nonexistent (:field (ex-data e))))))))


;; Exercises the cond where :id is conj'd onto the known-fields set
;; alongside the user-declared fields — :id passes silently while the
;; other key in the same map trips the unknown-field guard.
(deftest validate-where-clause-fields-id-coexists-with-error-test
  (testing ":id valid + unknown field in the SAME where-clause throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown field"
          (storage/validate-where-clause-fields!
            :user
            {:name {:type :text}}
            {:id (random-uuid) :nonexistent "value"})))))


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
      (is false "expected storage/validate-where-clause-types! to throw")
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
                {:unknown-field 123}))))

  ;; ─── IN-clause queries ──────────────────────────────────────────────
  (testing "IN-clause: vector of UUIDs for :uuid field passes"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:fn-id {:type :uuid}}
                {:fn-id [(random-uuid) (random-uuid)]}))))

  (testing "IN-clause: vector of UUIDs for :ref field passes"
    (is (nil? (storage/validate-where-clause-types!
                :binding
                {:slot-id {:type :ref}}
                {:slot-id [(random-uuid)]}))))

  (testing "IN-clause: set of ints for :int field passes"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:age {:type :int}}
                {:age #{10 20 30}}))))

  (testing "IN-clause: an empty collection is vacuously valid (matches nothing;
            the SQL layer renders it as a false predicate, never `IN ()`)"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:fn-id {:type :uuid}}
                {:fn-id []})))
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                {:name #{}}))))

  (testing "IN-clause: heterogeneous vector is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:fn-id {:type :uuid}}
            {:fn-id [(random-uuid) "not-a-uuid"]}))))

  ;; ─── Exotic-type mismatch errors ────────────────────────────────────
  (testing "wrong type throws - non-keyword for :enum"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:status {:type :enum}}
            {:status "active"}))))

  ;; :jsonb / :any / :union / :null are intentionally permissive
  ;; (`(constantly true)` in type-validators), so any non-nil value
  ;; passes — there's no negative-test case to write for them.

  (testing "wrong type throws - text for :numeric"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:balance {:type :numeric}}
            {:balance "12.34"}))))

  (testing "wrong type throws - bool for :timestamptz"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:created-at {:type :timestamptz}}
            {:created-at true})))))


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
      (is false "expected storage/validate-entity-name! to throw")
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

  (testing "jsonb accepts any value including string"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match "any-string" :jsonb)))))


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
      (is false "expected storage/validate-required-fields! to throw")
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
        (is false "expected storage/validate-no-duplicate-ids! to throw")
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
        (is false "expected storage/validate-no-duplicate-ids! to throw")
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
      (is false "expected storage/validate-data-is-map! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-data (:type (ex-data e))))
        (is (= :product (:entity-name (ex-data e))))
        (is (= [1 2 3] (:data (ex-data e))))))))


;; === Additional coverage tests ===

(deftest validate-entity-name!-additional-test
  (testing "throws for integer entity-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (storage/validate-entity-name! 42 "update"))))

  (testing "throws for boolean entity-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (storage/validate-entity-name! true "update"))))

  (testing "throws for vector entity-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (storage/validate-entity-name! [:user] "read"))))

  (testing "throws for map entity-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (storage/validate-entity-name! {:name "user"} "read"))))

  (testing "throws for entity-name starting with hyphen"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! (keyword "-user") "create"))))

  (testing "throws for entity-name starting with underscore"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! (keyword "_user") "create"))))

  (testing "throws for entity-name with special characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! (keyword "user!name") "create"))))

  (testing "throws for entity-name with spaces"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! (keyword "user name") "create"))))

  (testing "throws for entity-name with dots"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! (keyword "user.name") "create"))))

  (testing "throws for entity-name with SQL injection attempt"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (storage/validate-entity-name! (keyword "users; DROP TABLE") "query"))))

  (testing "exactly 64 characters passes (boundary)"
    (let [name-64 (keyword (str "a" (str/join (repeat 63 "b"))))]
      (is (nil? (storage/validate-entity-name! name-64 "create")))))

  (testing "single lowercase letter passes"
    (is (nil? (storage/validate-entity-name! :a "create"))))

  (testing "entity-name with digits in middle passes"
    (is (nil? (storage/validate-entity-name! :table123name "query"))))

  (testing "exception data includes entity-name-type for non-keyword"
    (try
      (storage/validate-entity-name! 42 "query")
      (is false "expected storage/validate-entity-name! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-entity-name (:type (ex-data e))))
        (is (= 42 (:entity-name (ex-data e))))
        (is (= "query" (:operation (ex-data e))))
        (is (some? (:entity-name-type (ex-data e)))))))

  (testing "exception data includes length for too-long name"
    (let [long-name (keyword (str "a" (str/join (repeat 64 "b"))))]
      (try
        (storage/validate-entity-name! long-name "create")
        (is false "expected storage/validate-entity-name! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-entity-name (:type (ex-data e))))
          (is (= 65 (:length (ex-data e))))
          (is (= "create" (:operation (ex-data e)))))))))


(deftest infer-actual-type-exotic-test
  (testing "infers :jsonb for empty map"
    (is (= :jsonb (#'graphden.storage.protocol.crud-validation/infer-actual-type {}))))

  (testing "infers :jsonb for empty vector"
    (is (= :jsonb (#'graphden.storage.protocol.crud-validation/infer-actual-type []))))

  (testing "infers :unknown for LocalDateTime (inst? only matches java.util.Date)"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type
                     (java.time.LocalDateTime/now)))))

  (testing "infers :timestamptz for Instant"
    (is (= :timestamptz (#'graphden.storage.protocol.crud-validation/infer-actual-type
                         (java.time.Instant/now)))))

  (testing "infers :text for empty string"
    (is (= :text (#'graphden.storage.protocol.crud-validation/infer-actual-type ""))))

  (testing "infers :int for zero"
    (is (= :int (#'graphden.storage.protocol.crud-validation/infer-actual-type 0))))

  (testing "infers :int for negative integer"
    (is (= :int (#'graphden.storage.protocol.crud-validation/infer-actual-type -42))))

  (testing "infers :int for Long/MAX_VALUE"
    (is (= :int (#'graphden.storage.protocol.crud-validation/infer-actual-type Long/MAX_VALUE))))

  (testing "infers :int for BigInteger"
    (is (= :int (#'graphden.storage.protocol.crud-validation/infer-actual-type (bigint 999999999999999999)))))

  (testing "infers :numeric for negative float"
    (is (= :numeric (#'graphden.storage.protocol.crud-validation/infer-actual-type -3.14))))

  (testing "infers :numeric for BigDecimal zero"
    (is (= :numeric (#'graphden.storage.protocol.crud-validation/infer-actual-type 0.0M))))

  (testing "infers :bytes for empty byte array"
    (is (= :bytes (#'graphden.storage.protocol.crud-validation/infer-actual-type (byte-array 0)))))

  (testing "infers :enum for namespaced keyword"
    (is (= :enum (#'graphden.storage.protocol.crud-validation/infer-actual-type :my.ns/thing))))

  (testing "infers :unknown for a list"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type '(1 2 3)))))

  (testing "infers :unknown for a set"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type #{1 2 3}))))

  (testing "infers :unknown for a function"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type inc))))

  (testing "infers :unknown for an atom"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type (atom 42)))))

  (testing "infers :unknown for a character"
    (is (= :unknown (#'graphden.storage.protocol.crud-validation/infer-actual-type \a))))

  (testing "ratio is inferred as :numeric or :unknown"
    (let [result (#'graphden.storage.protocol.crud-validation/infer-actual-type 22/7)]
      (is (contains? #{:numeric :unknown} result)))))


(deftest check-type-match-additional-test
  (testing "ref type accepts UUID"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (random-uuid) :ref))))

  (testing "fn type accepts UUID"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (random-uuid) :fn))))

  (testing "ref type rejects string"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "not-uuid" :ref)]
      (is (map? result))
      (is (= :ref (:expected result)))
      (is (= :text (:actual result)))))

  (testing "fn type rejects integer"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match 42 :fn)]
      (is (map? result))
      (is (= :fn (:expected result)))
      (is (= :int (:actual result)))))

  (testing "unknown/exotic type passes through (forward compat)"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match "anything" :exotic-future-type))))

  (testing "union type accepts anything"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match {:a 1} :union)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match "text" :union)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match 42 :union))))

  (testing "nil value passes for every type"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :bool)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :numeric)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :ref)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :fn)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :jsonb)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :timestamptz)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :bytes)))
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match nil :enum))))

  (testing "jsonb accepts string (any value valid for jsonb)"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match "a string" :jsonb))))

  (testing "jsonb accepts integer"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match 42 :jsonb))))

  (testing "jsonb accepts boolean"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match true :jsonb))))

  (testing "jsonb accepts nested map"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match {:nested {:deep "value"}} :jsonb))))

  (testing "jsonb accepts vector of mixed types"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match [1 "two" true] :jsonb))))

  (testing "numeric accepts integer (widening)"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match 42 :numeric))))

  (testing "numeric rejects string"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "42" :numeric)]
      (is (= :numeric (:expected result)))
      (is (= :text (:actual result)))))

  (testing "bool rejects integer"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match 0 :bool)]
      (is (= :bool (:expected result)))
      (is (= :int (:actual result)))))

  (testing "bool rejects string"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "false" :bool)]
      (is (= :bool (:expected result)))
      (is (= :text (:actual result)))))

  (testing "timestamptz accepts Instant"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (java.time.Instant/now) :timestamptz))))

  (testing "timestamptz accepts LocalDateTime"
    (is (nil? (#'graphden.storage.protocol.crud-validation/check-type-match (java.time.LocalDateTime/now) :timestamptz))))

  (testing "timestamptz rejects string"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "2024-01-01" :timestamptz)]
      (is (= :timestamptz (:expected result)))
      (is (= :text (:actual result)))))

  (testing "enum rejects string"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "active" :enum)]
      (is (= :enum (:expected result)))
      (is (= :text (:actual result)))))

  (testing "uuid rejects string that looks like UUID"
    (let [result (#'graphden.storage.protocol.crud-validation/check-type-match "550e8400-e29b-41d4-a716-446655440000" :uuid)]
      (is (= :uuid (:expected result)))
      (is (= :text (:actual result))))))


(deftest validate-where-clause-types-additional-test
  (testing "ref type field accepts uuid"
    (is (nil? (storage/validate-where-clause-types!
                :arg
                {:fn-id {:type :ref}}
                {:fn-id (random-uuid)}))))

  (testing "ref type field rejects string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :arg
            {:fn-id {:type :ref}}
            {:fn-id "not-uuid"}))))

  (testing "fn type field accepts uuid"
    (is (nil? (storage/validate-where-clause-types!
                :arg
                {:target {:type :fn}}
                {:target (random-uuid)}))))

  (testing "fn type field rejects integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :arg
            {:target {:type :fn}}
            {:target 42}))))

  (testing ":id field rejects string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {}
            {:id "not-a-uuid"}))))

  (testing "multiple fields all valid passes"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}
                 :age {:type :int}
                 :active {:type :bool}
                 :ref-id {:type :uuid}}
                {:name "alice"
                 :age 30
                 :active true
                 :ref-id (random-uuid)}))))

  (testing "empty where clause passes"
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                {}))))

  (testing "non-map where clause is ignored by types check"
    ;; validate-where-clause-types! only processes when where is a map
    (is (nil? (storage/validate-where-clause-types!
                :user
                {:name {:type :text}}
                "not-a-map"))))

  (testing "exception data includes value-type, as a JSON-encodable name"
    (try
      (storage/validate-where-clause-types!
        :user
        {:age {:type :int}}
        {:age "wrong"})
      (is false "expected storage/validate-where-clause-types! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/type-mismatch (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :age (:field (ex-data e))))
        (is (= :int (:expected-type (ex-data e))))
        (is (= :text (:actual-type (ex-data e))))
        (is (= "java.lang.String" (:value-type (ex-data e))))
        ;; This ex-data becomes the JSON `error-data` of a 400. A
        ;; `java.lang.Class` here has no JSON form, and the honest
        ;; type-mismatch message surfaced as a 500 instead.
        (is (string? (cheshire/generate-string (ex-data e)))))))

  (testing "boolean for uuid throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:ref-id {:type :uuid}}
            {:ref-id true}))))

  (testing "map for text throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:name {:type :text}}
            {:name {:invalid "map"}}))))

  (testing "keyword for int throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:age {:type :int}}
            {:age :twenty-five}))))

  (testing "integer for bool throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (storage/validate-where-clause-types!
            :user
            {:active {:type :bool}}
            {:active 1})))))


(deftest validate-where-clause-fields-additional-test
  (testing "empty where clause passes"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text}}
                {}))))

  (testing "non-map where clause is ignored"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text}}
                "not-a-map"))))

  (testing "multiple known fields pass"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text} :age {:type :int} :active {:type :bool}}
                {:name "alice" :age 30 :active true}))))

  (testing ":id with other known fields passes"
    (is (nil? (storage/validate-where-clause-fields!
                :user
                {:name {:type :text}}
                {:id (random-uuid) :name "alice"}))))

  (testing "exception known-fields are sorted"
    (try
      (storage/validate-where-clause-fields!
        :user
        {:z-field {:type :text} :a-field {:type :int}}
        {:bad-field "value"})
      (is false "expected storage/validate-where-clause-fields! to throw")
      (catch clojure.lang.ExceptionInfo e
        (let [known (:known-fields (ex-data e))]
          (is (= [:a-field :z-field] known)))))))


(deftest validate-where-clause-edge-cases-test
  (testing "throws for keyword where clause"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/validate-where-clause! :invalid))))

  (testing "throws for boolean where clause"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/validate-where-clause! true))))

  (testing "throws for set where clause"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/validate-where-clause! #{:a :b}))))

  (testing "exception includes where-type"
    (try
      (storage/validate-where-clause! [1 2 3])
      (is false "expected storage/validate-where-clause! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (some? (:where-type (ex-data e))))))))


(deftest validate-required-fields-edge-cases-test
  (testing "nullable? explicitly false is required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field"
          (storage/validate-required-fields!
            :user
            {:name {:type :text :nullable? false}}
            {}))))

  (testing "multiple required fields - first missing throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field"
          (storage/validate-required-fields!
            :user
            {:name {:type :text} :email {:type :text}}
            {:email "test@test.com"}))))

  (testing "all required fields present passes"
    (is (nil? (storage/validate-required-fields!
                :user
                {:name {:type :text} :email {:type :text}}
                {:name "John" :email "john@test.com"}))))

  (testing "empty fields map passes for any data"
    (is (nil? (storage/validate-required-fields!
                :user
                {}
                {:anything "goes"}))))

  (testing "extra data fields beyond spec are ignored"
    (is (nil? (storage/validate-required-fields!
                :user
                {:name {:type :text}}
                {:name "John" :extra "ignored"})))))


(deftest validate-no-duplicate-ids-edge-cases-test
  (testing "passes for empty sequence"
    (is (nil? (storage/validate-no-duplicate-ids! :user []))))

  (testing "passes for single record with ID"
    (is (nil? (storage/validate-no-duplicate-ids! :user [{:id (random-uuid)}]))))

  (testing "passes for single record without ID"
    (is (nil? (storage/validate-no-duplicate-ids! :user [{:name "A"}]))))

  (testing "duplicate at end of sequence after unique IDs"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/validate-no-duplicate-ids!
              :user
              [{:id (random-uuid)} {:id (random-uuid)} {:id dup-id} {:id dup-id}])))))

  (testing "duplicate with nil-id records interspersed"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/validate-no-duplicate-ids!
              :user
              [{:id dup-id} {:name "no-id"} {:id dup-id}])))))

  (testing "three occurrences of same ID"
    (let [dup-id (random-uuid)]
      (try
        (storage/validate-no-duplicate-ids!
          :user
          [{:id dup-id} {:id dup-id} {:id dup-id}])
        (is false "expected storage/validate-no-duplicate-ids! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= 1 (count (:duplicate-ids (ex-data e)))))
          (is (= dup-id (first (:duplicate-ids (ex-data e))))))))))


(deftest validate-data-is-map-additional-test
  (testing "throws for keyword data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user :keyword-data))))

  (testing "throws for boolean data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user true))))

  (testing "throws for set data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/validate-data-is-map! :user #{:a :b}))))

  (testing "passes for sorted-map"
    (is (nil? (storage/validate-data-is-map! :user (sorted-map :a 1 :b 2)))))

  (testing "exception includes data-type"
    (try
      (storage/validate-data-is-map! :user "string")
      (is false "expected storage/validate-data-is-map! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= String (:data-type (ex-data e))))))))
