(ns graphden.executor.types-test
  "Unit tests for executor type validation module.

   Tests:
   - truncate-value function
   - Type hint registration and retrieval
   - Type mismatch detection and error throwing
   - Circuit breaker for unknown types
   - Argument type validation"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.context :as ctx]
    [graphden.executor.types :as types]))


;; Reset custom type hints after each test
(use-fixtures :each
  (fn [f]
    (reset! types/custom-type-hints {})
    (f)
    (reset! types/custom-type-hints {})))


;; === truncate-value Tests ===

(deftest truncate-value-test
  (testing "returns short values unchanged"
    (is (= "\"hello\"" (types/truncate-value "hello" 100)))
    (is (= "42" (types/truncate-value 42 100)))
    (is (= "true" (types/truncate-value true 100))))

  (testing "truncates long strings"
    (let [long-str (str/join (repeat 200 "x"))
          result (types/truncate-value long-str 50)]
      (is (= 53 (count result)))  ; 50 chars + "..."
      (is (str/ends-with? result "..."))))

  (testing "truncates large maps"
    (let [large-map (zipmap (map #(str "key" %) (range 100)) (range 100))
          result (types/truncate-value large-map 50)]
      (is (str/ends-with? result "..."))
      (is (<= (count result) 53))))

  (testing "redacts sensitive data before truncation"
    ;; sp/redact-sensitive-deep should replace password values
    (let [sensitive {:password "secret123" :data "visible"}
          result (types/truncate-value sensitive 200)]
      ;; Password should be redacted, not visible in output
      (is (not (str/includes? result "secret123"))))))


;; === Type Hints Tests ===

(deftest get-type-hint-test
  (testing "returns default hint for built-in types"
    (is (= "integer (e.g., 42, -1)" (types/get-type-hint :int)))
    (is (= "boolean (true or false)" (types/get-type-hint :bool)))
    (is (= "string (e.g., \"hello\")" (types/get-type-hint :text)))
    (is (= "UUID (function reference)" (types/get-type-hint :fn)))
    (is (= "UUID (entity reference)" (types/get-type-hint :ref))))

  (testing "returns type name for unknown types"
    (is (= "unknown-type-xyz" (types/get-type-hint :unknown-type-xyz)))
    (is (= "custom" (types/get-type-hint :custom))))

  (testing "returns custom hint when registered"
    (types/register-type-hint! :email "email address format")
    (is (= "email address format" (types/get-type-hint :email))))

  (testing "custom hints take precedence over defaults"
    (types/register-type-hint! :int "custom int hint")
    (is (= "custom int hint" (types/get-type-hint :int)))))


(deftest register-type-hint-validation-test
  (testing "rejects non-keyword type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"type-keyword must be a keyword"
          (types/register-type-hint! "not-keyword" "hint")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"type-keyword must be a keyword"
          (types/register-type-hint! 123 "hint"))))

  (testing "rejects non-string hint"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hint-string must be a string"
          (types/register-type-hint! :valid :not-string)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hint-string must be a string"
          (types/register-type-hint! :valid 42)))))


;; === throw-type-mismatch! Tests ===

(deftest throw-type-mismatch-test
  (testing "throws with detailed context"
    (let [arg-schema {:id #uuid "00000000-0000-0000-0000-000000000001"
                      :name "count"
                      :type :int}]
      (try
        (types/throw-type-mismatch! arg-schema "not-an-int")
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :execution-error/type-mismatch (:type data)))
            (is (= "count" (:arg-name data)))
            (is (= :int (:expected-type data)))
            (is (= String (:provided-type data)))
            (is (str/includes? (ex-message e) "count"))
            (is (str/includes? (ex-message e) "int")))))))

  (testing "truncates large values in error message"
    (let [arg-schema {:id #uuid "00000000-0000-0000-0000-000000000002"
                      :name "data"
                      :type :int}
          large-value (str/join (repeat 500 "x"))]
      (try
        (types/throw-type-mismatch! arg-schema large-value)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [provided-val (:provided-value (ex-data e))]
            ;; Should be truncated to ctx/error-value-truncation-length + "..."
            (is (<= (count provided-val) (+ ctx/error-value-truncation-length 3)))))))))


;; === type-mismatch? Tests ===

(deftest type-mismatch-known-types-test
  (testing "returns false for valid values"
    (let [counter (atom 0)]
      ;; int
      (is (false? (types/type-mismatch? :int 42 true 10 counter)))
      (is (false? (types/type-mismatch? :int -1 true 10 counter)))
      (is (false? (types/type-mismatch? :int 0 true 10 counter)))
      ;; bool
      (is (false? (types/type-mismatch? :bool true true 10 counter)))
      (is (false? (types/type-mismatch? :bool false true 10 counter)))
      ;; text
      (is (false? (types/type-mismatch? :text "hello" true 10 counter)))
      (is (false? (types/type-mismatch? :text "" true 10 counter)))
      ;; uuid
      (is (false? (types/type-mismatch? :uuid (java.util.UUID/randomUUID) true 10 counter)))
      ;; jsonb
      (is (false? (types/type-mismatch? :jsonb {:a 1} true 10 counter)))
      (is (false? (types/type-mismatch? :jsonb [1 2 3] true 10 counter)))))

  (testing "returns true for invalid values"
    (let [counter (atom 0)]
      ;; int expects integer
      (is (true? (types/type-mismatch? :int "42" true 10 counter)))
      (is (true? (types/type-mismatch? :int 3.14 true 10 counter)))
      ;; bool expects boolean
      (is (true? (types/type-mismatch? :bool 1 true 10 counter)))
      (is (true? (types/type-mismatch? :bool "true" true 10 counter)))
      ;; text expects string
      (is (true? (types/type-mismatch? :text 123 true 10 counter)))
      (is (true? (types/type-mismatch? :text :keyword true 10 counter)))
      ;; uuid expects UUID
      (is (true? (types/type-mismatch? :uuid "not-uuid" true 10 counter)))
      ;; Note: jsonb now accepts any JSON-serializable value (strings, numbers, etc.)
      ;; See src/graphden/schema/fields/types.clj for rationale
      )))


(deftest type-mismatch-unknown-types-strict-test
  (testing "strict mode throws on unknown type"
    (let [counter (atom 0)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown argument type"
            (types/type-mismatch? :unknown-type-xyz "value" true 10 counter)))
      ;; Check error details
      (try
        (types/type-mismatch? :totally-new-type {:data 1} true 10 counter)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :execution-error/unknown-arg-type (:type data)))
            (is (= :totally-new-type (:arg-type data)))))))))


(deftest type-mismatch-unknown-types-permissive-test
  (testing "permissive mode accepts unknown types with warning"
    (let [counter (atom 0)]
      ;; Should return false (no mismatch = accept the value)
      (is (false? (types/type-mismatch? :unknown-type-xyz "value" false 10 counter)))
      ;; Counter should be incremented
      (is (= 1 @counter))))

  (testing "circuit breaker triggers when limit reached"
    (let [counter (atom 9)]  ; Just below limit of 10
      ;; First call should succeed but hit the limit
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Too many unknown types"
            (types/type-mismatch? :unknown-type "value" false 10 counter)))
      ;; Check error details
      (try
        (reset! counter 15)  ; Above limit
        (types/type-mismatch? :another-unknown "value" false 10 counter)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :execution-error/unknown-type-limit-exceeded (:type data)))))))))


(deftest type-mismatch-special-types-test
  (testing "union type accepts any value"
    (let [counter (atom 0)]
      (is (false? (types/type-mismatch? :union "string" true 10 counter)))
      (is (false? (types/type-mismatch? :union 42 true 10 counter)))
      (is (false? (types/type-mismatch? :union {:map true} true 10 counter)))
      (is (false? (types/type-mismatch? :union nil true 10 counter)))))

  (testing "any type accepts any value"
    (let [counter (atom 0)]
      (is (false? (types/type-mismatch? :any "anything" true 10 counter)))
      (is (false? (types/type-mismatch? :any 123 true 10 counter)))))

  (testing "enum type expects keyword"
    (let [counter (atom 0)]
      (is (false? (types/type-mismatch? :enum :active true 10 counter)))
      (is (false? (types/type-mismatch? :enum :pending true 10 counter)))
      (is (true? (types/type-mismatch? :enum "active" true 10 counter)))))

  (testing "numeric type accepts various number types"
    (let [counter (atom 0)]
      (is (false? (types/type-mismatch? :numeric 42 true 10 counter)))
      (is (false? (types/type-mismatch? :numeric 3.14 true 10 counter)))
      (is (false? (types/type-mismatch? :numeric 22/7 true 10 counter)))
      (is (false? (types/type-mismatch? :numeric (bigdec 99.99) true 10 counter)))
      (is (true? (types/type-mismatch? :numeric "42" true 10 counter))))))


;; === validate-provided-arg-type! Tests ===

(deftest validate-provided-arg-type-test
  (testing "passes for valid types"
    (let [counter (atom 0)]
      ;; Should not throw
      (types/validate-provided-arg-type! 42 {:type :int :name "n"} true 10 counter)
      (types/validate-provided-arg-type! "hello" {:type :text :name "s"} true 10 counter)
      (types/validate-provided-arg-type! true {:type :bool :name "b"} true 10 counter)
      (is true "No exception thrown")))

  (testing "throws for type mismatch"
    (let [counter (atom 0)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (types/validate-provided-arg-type!
              "not-an-int"
              {:type :int :name "count" :id (java.util.UUID/randomUUID)}
              true 10 counter)))))

  (testing "throws for missing type in arg-schema"
    (let [counter (atom 0)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (types/validate-provided-arg-type! 42 {:name "x"} true 10 counter)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (types/validate-provided-arg-type! 42 nil true 10 counter)))))

  (testing "permissive mode allows unknown types"
    (let [counter (atom 0)]
      ;; Should not throw
      (types/validate-provided-arg-type!
        "any-value"
        {:type :future-type :name "x" :id (java.util.UUID/randomUUID)}
        false 10 counter)
      (is (= 1 @counter) "Counter incremented for unknown type"))))


;; === Circuit Breaker Integration Tests ===

(deftest circuit-breaker-integration-test
  (testing "circuit breaker counts accumulate across multiple calls"
    (let [counter (atom 0)]
      ;; Multiple unknown type validations
      (types/type-mismatch? :unknown1 "v" false 5 counter)
      (types/type-mismatch? :unknown2 "v" false 5 counter)
      (types/type-mismatch? :unknown3 "v" false 5 counter)
      (types/type-mismatch? :unknown4 "v" false 5 counter)
      (is (= 4 @counter))
      ;; 5th call should trigger circuit breaker
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Too many unknown types"
            (types/type-mismatch? :unknown5 "v" false 5 counter))))))
