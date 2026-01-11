(ns graphden.storage-protocol.crud-validation-test
  "Tests for crud-validation module - direct tests for type inference and checking."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.crud-validation :as crud])
  (:import
    (java.time
      Instant)))


;; === infer-actual-type coverage via validate-where-clause-types! ===
;; Testing all branches of the private infer-actual-type function through public API

(deftest type-inference-coverage-test
  (testing "all type inference branches via validate-where-clause-types!"
    ;; Create fields that will trigger type checking for each inferred type
    (let [uuid-field {:f {:type :uuid}}
          text-field {:f {:type :text}}
          int-field {:f {:type :int}}
          bool-field {:f {:type :bool}}
          numeric-field {:f {:type :numeric}}
          timestamptz-field {:f {:type :timestamptz}}
          bytes-field {:f {:type :bytes}}
          enum-field {:f {:type :enum}}
          jsonb-field {:f {:type :jsonb}}]

      ;; nil value - should pass for any type (nil branch)
      (testing "nil value passes for any field type"
        (is (nil? (crud/validate-where-clause-types! :e uuid-field {:f nil})))
        (is (nil? (crud/validate-where-clause-types! :e text-field {:f nil})))
        (is (nil? (crud/validate-where-clause-types! :e int-field {:f nil}))))

      ;; uuid value
      (testing "uuid value inferred as :uuid"
        (is (nil? (crud/validate-where-clause-types! :e uuid-field {:f (random-uuid)})))
        ;; uuid doesn't match text
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e text-field {:f (random-uuid)}))))

      ;; string value
      (testing "string value inferred as :text"
        (is (nil? (crud/validate-where-clause-types! :e text-field {:f "hello"})))
        ;; text doesn't match int
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e int-field {:f "hello"}))))

      ;; integer value
      (testing "integer value inferred as :int"
        (is (nil? (crud/validate-where-clause-types! :e int-field {:f 42})))
        ;; int is compatible with numeric
        (is (nil? (crud/validate-where-clause-types! :e numeric-field {:f 42})))
        ;; int doesn't match text
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e text-field {:f 42}))))

      ;; boolean value
      (testing "boolean value inferred as :bool"
        (is (nil? (crud/validate-where-clause-types! :e bool-field {:f true})))
        (is (nil? (crud/validate-where-clause-types! :e bool-field {:f false})))
        ;; bool doesn't match int
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e int-field {:f true}))))

      ;; float value
      (testing "float value inferred as :numeric"
        (is (nil? (crud/validate-where-clause-types! :e numeric-field {:f 3.14})))
        ;; float doesn't match int
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e int-field {:f 3.14}))))

      ;; decimal value
      (testing "decimal value inferred as :numeric"
        (is (nil? (crud/validate-where-clause-types! :e numeric-field {:f 3.14M})))
        ;; decimal doesn't match bool
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e bool-field {:f 3.14M}))))

      ;; inst value (java.util.Date)
      (testing "Date value inferred as :timestamptz"
        (is (nil? (crud/validate-where-clause-types! :e timestamptz-field {:f (java.util.Date.)})))
        ;; date doesn't match text
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e text-field {:f (java.util.Date.)}))))

      ;; Instant value
      (testing "Instant value inferred as :timestamptz"
        (is (nil? (crud/validate-where-clause-types! :e timestamptz-field {:f (java.time.Instant/now)}))))

      ;; bytes value
      (testing "bytes value inferred as :bytes"
        (is (nil? (crud/validate-where-clause-types! :e bytes-field {:f (byte-array [1 2 3])})))
        ;; bytes doesn't match text
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e text-field {:f (byte-array [1 2 3])}))))

      ;; keyword value
      (testing "keyword value inferred as :enum"
        (is (nil? (crud/validate-where-clause-types! :e enum-field {:f :active})))
        ;; keyword doesn't match text
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e text-field {:f :keyword-val}))))

      ;; map value
      (testing "map value inferred as :jsonb"
        (is (nil? (crud/validate-where-clause-types! :e jsonb-field {:f {:key "value"}})))
        ;; map doesn't match text
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e text-field {:f {:key "value"}}))))

      ;; vector value
      (testing "vector value inferred as :jsonb"
        (is (nil? (crud/validate-where-clause-types! :e jsonb-field {:f [1 2 3]})))
        ;; vector doesn't match int
        (is (thrown? clojure.lang.ExceptionInfo
              (crud/validate-where-clause-types! :e int-field {:f [1 2 3]}))))

      ;; unknown type (e.g., custom object) - fails because ft/valid-type? validates strictly
      (testing "unknown type fails validation when field type is known"
        ;; Create a custom object that doesn't match any known type
        (let [custom-obj (Object.)]
          ;; ft/valid-type? for :text expects string, so Object fails
          (is (thrown? clojure.lang.ExceptionInfo
                (crud/validate-where-clause-types! :e text-field {:f custom-obj}))))))))


;; === check-type-match coverage via field-types integration ===

(deftest type-matching-with-field-types-test
  (testing "type matching uses field-types validators"
    (let [ref-field {:f {:type :ref}}
          union-field {:f {:type :union}}
          fn-field {:f {:type :fn}}]

      ;; ref accepts uuid (via ft/valid-type?)
      (testing "ref type accepts uuid"
        (is (nil? (crud/validate-where-clause-types! :e ref-field {:f (random-uuid)}))))

      ;; union accepts anything (via ft/valid-type?)
      (testing "union type accepts any value"
        (is (nil? (crud/validate-where-clause-types! :e union-field {:f "string"})))
        (is (nil? (crud/validate-where-clause-types! :e union-field {:f 123})))
        (is (nil? (crud/validate-where-clause-types! :e union-field {:f {:map "value"}})))
        (is (nil? (crud/validate-where-clause-types! :e union-field {:f [1 2 3]}))))

      ;; fn type accepts uuid (functions are referenced by id)
      (testing "fn type accepts uuid"
        (is (nil? (crud/validate-where-clause-types! :e fn-field {:f (random-uuid)})))))))


;; === validate-entity-name! boundary tests ===

(deftest validate-entity-name-boundary-test
  (testing "entity name at exactly 64 chars passes"
    (let [name-64 (keyword (str/join (repeat 64 "a")))]
      (is (nil? (crud/validate-entity-name! name-64 "test")))))

  (testing "entity name at 65 chars fails"
    (let [name-65 (keyword (str/join (repeat 65 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (crud/validate-entity-name! name-65 "test")))))

  (testing "entity name with numbers in middle passes"
    (is (nil? (crud/validate-entity-name! :user123 "test")))
    (is (nil? (crud/validate-entity-name! :a1b2c3 "test"))))

  (testing "single char entity name passes"
    (is (nil? (crud/validate-entity-name! :a "test"))))

  (testing "entity name with only hyphens/underscores after letter"
    (is (nil? (crud/validate-entity-name! :a-_- "test")))
    (is (nil? (crud/validate-entity-name! :a___ "test")))))


;; === validate-required-fields! edge cases ===

(deftest validate-required-fields-edge-cases-test
  (testing "passes with empty fields map"
    (is (nil? (crud/validate-required-fields! :entity {} {:any "data"}))))

  (testing "falsy but valid values pass"
    (let [fields {:count {:type :int :nullable? false}
                  :active {:type :bool :nullable? false}
                  :name {:type :text :nullable? false}}]
      ;; 0 is valid for int
      (is (nil? (crud/validate-required-fields! :entity fields {:count 0 :active false :name ""})))
      ;; false is valid for bool
      (is (nil? (crud/validate-required-fields! :entity fields {:count 1 :active false :name "test"})))
      ;; empty string is valid for text
      (is (nil? (crud/validate-required-fields! :entity fields {:count 1 :active true :name ""})))))

  (testing "fails on first missing field"
    (let [fields {:a {:type :text :nullable? false}
                  :b {:type :text :nullable? false}}]
      (try
        (crud/validate-required-fields! :entity fields {})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          ;; Should fail on either :a or :b (order not guaranteed due to map iteration)
          (is (contains? #{:a :b} (:field (ex-data e)))))))))


;; === validate-no-duplicate-ids! edge cases ===

(deftest validate-no-duplicate-ids-edge-cases-test
  (testing "passes for empty sequence"
    (is (nil? (crud/validate-no-duplicate-ids! :entity []))))

  (testing "passes for single item"
    (is (nil? (crud/validate-no-duplicate-ids! :entity [{:id 1}]))))

  (testing "passes for all nil ids"
    (is (nil? (crud/validate-no-duplicate-ids! :entity [{:name "a"} {:name "b"} {:name "c"}]))))

  (testing "handles UUID ids"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (is (nil? (crud/validate-no-duplicate-ids! :entity [{:id id1} {:id id2}])))
      (is (thrown? clojure.lang.ExceptionInfo
            (crud/validate-no-duplicate-ids! :entity [{:id id1} {:id id1}]))))))


;; === validate-data-is-map! edge cases ===

(deftest validate-data-is-map-edge-cases-test
  (testing "passes for array-map (which is a map)"
    (let [record (array-map :a 1 :b 2)]
      (is (nil? (crud/validate-data-is-map! :entity record)))))

  (testing "fails for list"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-data-is-map! :entity '(1 2 3)))))

  (testing "fails for set"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-data-is-map! :entity #{1 2 3}))))

  (testing "fails for number"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-data-is-map! :entity 42)))))


;; === validate-where-clause! edge cases ===

(deftest validate-where-clause-edge-cases-test
  (testing "fails for keyword"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-where-clause! :active))))

  (testing "fails for list"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-where-clause! '(:status :active)))))

  (testing "fails for boolean"
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-where-clause! true)))))


;; === validate-where-clause-fields! edge cases ===

(deftest validate-where-clause-fields-edge-cases-test
  (let [fields {:name {:type :text} :age {:type :int}}]

    (testing "passes when where is not a map (after validate-where-clause!)"
      ;; This function only validates when where is a map
      ;; Other types should be caught by validate-where-clause! first
      ;; But if called directly with non-map, it returns nil
      (is (nil? (crud/validate-where-clause-fields! :entity fields "string"))))

    (testing "handles multiple unknown fields - throws on first"
      (try
        (crud/validate-where-clause-fields! :entity fields {:unknown1 1 :unknown2 2})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (contains? #{:unknown1 :unknown2} (:field (ex-data e)))))))))


;; === validate-entity-name! comprehensive tests ===

(deftest validate-entity-name-comprehensive-test
  (testing "rejects non-keyword entity name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (crud/validate-entity-name! "string-name" "create")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (crud/validate-entity-name! 123 "update")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"entity-name must be a keyword"
          (crud/validate-entity-name! nil "delete"))))

  (testing "rejects entity name starting with number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (crud/validate-entity-name! (keyword "123user") "test"))))

  (testing "rejects entity name with uppercase"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (crud/validate-entity-name! :User "test"))))

  (testing "rejects entity name with special chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (crud/validate-entity-name! (keyword "user.name") "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (crud/validate-entity-name! (keyword "user@name") "test"))))

  (testing "error data contains operation context"
    (try
      (crud/validate-entity-name! "not-keyword" "my-operation")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-entity-name (:type (ex-data e))))
        (is (= "not-keyword" (:entity-name (ex-data e))))
        (is (= "my-operation" (:operation (ex-data e))))
        (is (= java.lang.String (:entity-name-type (ex-data e))))))))


;; === validate-where-clause-types! comprehensive tests ===

(deftest validate-where-clause-types-comprehensive-test
  (testing "passes for nil where clause"
    (is (nil? (crud/validate-where-clause-types! :entity {:f {:type :text}} nil))))

  (testing "passes for empty where clause"
    (is (nil? (crud/validate-where-clause-types! :entity {:f {:type :text}} {}))))

  (testing ":id field always treated as :uuid"
    (is (nil? (crud/validate-where-clause-types! :entity {} {:id (random-uuid)})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Type mismatch"
          (crud/validate-where-clause-types! :entity {} {:id "not-a-uuid"}))))

  (testing "error data contains all context"
    (try
      (crud/validate-where-clause-types! :my-entity {:status {:type :int}} {:status "text"})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/type-mismatch (:type (ex-data e))))
        (is (= :my-entity (:entity (ex-data e))))
        (is (= :status (:field (ex-data e))))
        (is (= :int (:expected-type (ex-data e))))
        (is (= :text (:actual-type (ex-data e))))))))


;; === validate-where-clause! comprehensive tests ===

(deftest validate-where-clause-comprehensive-test
  (testing "passes for nil"
    (is (nil? (crud/validate-where-clause! nil))))

  (testing "passes for empty map"
    (is (nil? (crud/validate-where-clause! {}))))

  (testing "passes for map with values"
    (is (nil? (crud/validate-where-clause! {:name "test" :age 25}))))

  (testing "error data contains where clause info"
    (try
      (crud/validate-where-clause! :not-a-map)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-where-clause (:type (ex-data e))))
        (is (= :not-a-map (:where (ex-data e))))
        (is (= clojure.lang.Keyword (:where-type (ex-data e))))))))


;; === validate-data-is-map! comprehensive tests ===

(deftest validate-data-is-map-comprehensive-test
  (testing "passes for various map types"
    (is (nil? (crud/validate-data-is-map! :entity {})))
    (is (nil? (crud/validate-data-is-map! :entity {:a 1})))
    (is (nil? (crud/validate-data-is-map! :entity (sorted-map :a 1 :b 2)))))

  (testing "error data contains all context"
    (try
      (crud/validate-data-is-map! :my-entity [1 2 3])
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-data (:type (ex-data e))))
        (is (= :my-entity (:entity-name (ex-data e))))
        (is (= [1 2 3] (:data (ex-data e))))
        (is (= clojure.lang.PersistentVector (:data-type (ex-data e))))))))


;; === validate-required-fields! comprehensive tests ===

(deftest validate-required-fields-comprehensive-test
  (testing "skips :id field (auto-generated)"
    (let [fields {:id {:type :uuid :nullable? false}
                  :name {:type :text :nullable? false}}]
      ;; :id is skipped, so only :name is required
      (is (nil? (crud/validate-required-fields! :entity fields {:name "test"})))))

  (testing "skips nullable fields"
    (let [fields {:required {:type :text :nullable? false}
                  :optional {:type :text :nullable? true}}]
      (is (nil? (crud/validate-required-fields! :entity fields {:required "value"})))))

  (testing "error data contains field info"
    (let [fields {:name {:type :text :nullable? false}}]
      (try
        (crud/validate-required-fields! :my-entity fields {})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/required-field-missing (:type (ex-data e))))
          (is (= :my-entity (:entity (ex-data e))))
          (is (= :name (:field (ex-data e)))))))))


;; === validate-no-duplicate-ids! comprehensive tests ===

(deftest validate-no-duplicate-ids-comprehensive-test
  (testing "filters out nil ids before checking duplicates"
    (is (nil? (crud/validate-no-duplicate-ids! :entity
                                               [{:id nil} {:id nil} {:name "a"}]))))

  (testing "error data contains duplicate ids"
    (let [dup-id (random-uuid)]
      (try
        (crud/validate-no-duplicate-ids! :my-entity
                                         [{:id dup-id} {:id (random-uuid)} {:id dup-id}])
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/duplicate-ids (:type (ex-data e))))
          (is (= :my-entity (:entity (ex-data e))))
          (is (= [dup-id] (:duplicate-ids (ex-data e)))))))))


;; === validate-where-clause-fields! comprehensive tests ===

(deftest validate-where-clause-fields-comprehensive-test
  (testing "passes for nil where clause"
    (is (nil? (crud/validate-where-clause-fields! :entity {:f {:type :text}} nil))))

  (testing ":id is always valid even if not in fields"
    (is (nil? (crud/validate-where-clause-fields! :entity {} {:id (random-uuid)}))))

  (testing "error data contains known fields list"
    (try
      (crud/validate-where-clause-fields! :my-entity {:name {:type :text}} {:unknown 1})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/unknown-field (:type (ex-data e))))
        (is (= :my-entity (:entity (ex-data e))))
        (is (= :unknown (:field (ex-data e))))
        (is (= [:name] (:known-fields (ex-data e))))))))


;; === Additional type inference tests for complete coverage ===

(deftest type-inference-additional-coverage-test
  (testing "nil values are allowed for any field type"
    ;; nil value should pass validation regardless of field type
    (doseq [field-type [:uuid :text :int :bool :numeric :timestamptz :bytes :enum :jsonb]]
      (is (nil? (crud/validate-where-clause-types!
                  :entity {:f {:type field-type}} {:f nil})))))

  (testing "field not in fields map is skipped"
    ;; When field is not defined, validation is skipped
    (is (nil? (crud/validate-where-clause-types!
                :entity {} {:unknown-field "any-value"}))))

  (testing "Instant values are valid for timestamptz"
    (is (nil? (crud/validate-where-clause-types!
                :entity {:ts {:type :timestamptz}} {:ts (Instant/now)}))))

  (testing "list value is not inferred as :jsonb (only vector/map)"
    ;; Lists are not recognized as jsonb, they become :unknown
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-where-clause-types!
            :entity {:data {:type :jsonb}} {:data '(1 2 3)}))))

  (testing "set value fails - only vector/map are jsonb"
    ;; Sets are not recognized as jsonb, they become :unknown
    (is (thrown? clojure.lang.ExceptionInfo
          (crud/validate-where-clause-types!
            :entity {:data {:type :jsonb}} {:data #{1 2 3}}))))

  (testing "custom object infers as :unknown"
    ;; Objects that don't match any type are inferred as :unknown
    (let [custom (Object.)]
      (is (thrown? clojure.lang.ExceptionInfo
            (crud/validate-where-clause-types!
              :entity {:data {:type :text}} {:data custom})))))

  (testing "ratio value infers as :numeric"
    ;; Clojure ratios are numeric
    (is (nil? (crud/validate-where-clause-types!
                :entity {:n {:type :numeric}} {:n 1/3})))))


;; === validate-data-is-map! additional tests ===

(deftest validate-data-is-map-additional-test
  (testing "fails for string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (crud/validate-data-is-map! :entity "not a map"))))

  (testing "fails for nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (crud/validate-data-is-map! :entity nil))))

  (testing "passes for hash-map"
    (is (nil? (crud/validate-data-is-map! :entity (hash-map :a 1 :b 2))))))


;; === validate-entity-name! additional tests ===

(deftest validate-entity-name-additional-test
  (testing "rejects entity name starting with hyphen"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (crud/validate-entity-name! (keyword "-starts-with-hyphen") "test"))))

  (testing "rejects entity name starting with underscore"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (crud/validate-entity-name! (keyword "_starts-with-underscore") "test"))))

  (testing "accepts entity name with all allowed chars"
    (is (nil? (crud/validate-entity-name! :abc123-def_ghi "test"))))

  (testing "error message includes operation"
    (try
      (crud/validate-entity-name! :UPPER "my-op")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (str/includes? (ex-message e) "my-op"))))))


;; === validate-required-fields! additional tests ===

(deftest validate-required-fields-additional-test
  (testing "multiple required fields - first missing throws"
    (let [fields {:a {:type :text :nullable? false}
                  :b {:type :text :nullable? false}
                  :c {:type :text :nullable? false}}]
      ;; Only provide :a, :b and :c are missing
      (is (thrown? clojure.lang.ExceptionInfo
            (crud/validate-required-fields! :entity fields {:a "value"})))))

  (testing "all nullable fields pass with no data"
    (let [fields {:a {:type :text :nullable? true}
                  :b {:type :int :nullable? true}}]
      (is (nil? (crud/validate-required-fields! :entity fields {})))))

  (testing ":id field is always skipped"
    (let [fields {:id {:type :uuid :nullable? false}}]
      (is (nil? (crud/validate-required-fields! :entity fields {}))))))


;; === validate-no-duplicate-ids! additional tests ===

(deftest validate-no-duplicate-ids-additional-test
  (testing "multiple different ids pass"
    (let [ids (repeatedly 10 random-uuid)
          data (mapv (fn [id] {:id id :name "test"}) ids)]
      (is (nil? (crud/validate-no-duplicate-ids! :entity data)))))

  (testing "mixed explicit and implicit ids (nil) pass"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (is (nil? (crud/validate-no-duplicate-ids! :entity
                                                 [{:id id1}
                                                  {:name "no-id"}
                                                  {:id id2}
                                                  {:name "also-no-id"}])))))

  (testing "multiple duplicates reported"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (try
        (crud/validate-no-duplicate-ids! :entity
                                         [{:id id1} {:id id1}
                                          {:id id2} {:id id2}])
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          ;; Both ids should be in duplicates
          (is (= 2 (count (:duplicate-ids (ex-data e))))))))))


;; === validate-where-clause! additional tests ===

(deftest validate-where-clause-additional-test
  (testing "passes for nested map"
    (is (nil? (crud/validate-where-clause! {:nested {:key "value"}}))))

  (testing "error includes actual type"
    (try
      (crud/validate-where-clause! 42)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= java.lang.Long (:where-type (ex-data e))))))))


;; === validate-where-clause-types! with :any type ===

(deftest validate-where-clause-types-any-test
  (testing ":any type accepts any value"
    (let [any-field {:f {:type :any}}]
      (is (nil? (crud/validate-where-clause-types! :e any-field {:f "string"})))
      (is (nil? (crud/validate-where-clause-types! :e any-field {:f 123})))
      (is (nil? (crud/validate-where-clause-types! :e any-field {:f true})))
      (is (nil? (crud/validate-where-clause-types! :e any-field {:f {:nested "map"}})))
      (is (nil? (crud/validate-where-clause-types! :e any-field {:f [1 2 3]}))))))


;; === Edge cases for field type matching ===

(deftest field-type-edge-cases-test
  (testing "long value matches :int"
    (is (nil? (crud/validate-where-clause-types!
                :entity {:n {:type :int}} {:n (long 42)}))))

  (testing "BigDecimal matches :numeric"
    (is (nil? (crud/validate-where-clause-types!
                :entity {:n {:type :numeric}} {:n (bigdec "123.456")}))))

  (testing "empty string is valid :text"
    (is (nil? (crud/validate-where-clause-types!
                :entity {:s {:type :text}} {:s ""}))))

  (testing "empty vector is valid :jsonb"
    (is (nil? (crud/validate-where-clause-types!
                :entity {:j {:type :jsonb}} {:j []}))))

  (testing "empty map is valid :jsonb"
    (is (nil? (crud/validate-where-clause-types!
                :entity {:j {:type :jsonb}} {:j {}})))))
