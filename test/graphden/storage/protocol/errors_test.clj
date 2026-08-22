(ns graphden.storage.protocol.errors-test
  "Tests for error classification, registry, and sensitive data redaction."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.errors :as errors]
    [graphden.storage.protocol.redaction :as redaction]))


;; === Fixture to isolate sensitive field registry ===

(defn reset-registry-fixture
  [f]
  (redaction/with-sensitive-field-registry
    (f)))


(use-fixtures :each reset-registry-fixture)


;; === Error Context and Storage Error Tests ===

(deftest make-error-context-test
  (testing "creates canonical error context"
    (let [ctx (errors/make-error-context :not-found :read-entity "Entity not found"
                                         {:entity-name :user :id 123})]
      (is (= :not-found (:type ctx)))
      (is (= :read-entity (:operation ctx)))
      (is (= "Entity not found" (:message ctx)))
      (is (= :user (:entity-name ctx)))
      (is (= 123 (:id ctx)))))

  (testing "merges all context keys"
    (let [ctx (errors/make-error-context :system-error/query-timeout :query-entities "Query timed out"
                                         {:sql-state "57014" :query "SELECT *" :timeout 5000})]
      (is (= "57014" (:sql-state ctx)))
      (is (= "SELECT *" (:query ctx)))
      (is (= 5000 (:timeout ctx))))))


(deftest make-storage-error-test
  (testing "creates ExceptionInfo without cause"
    (let [ex (errors/make-storage-error :unique-violation :create-entity
                                        "Duplicate key" {:entity-name :user})]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= "Duplicate key" (ex-message ex)))
      (is (= :unique-violation (:type (ex-data ex))))
      (is (= :create-entity (:operation (ex-data ex))))
      (is (nil? (ex-cause ex)))))

  (testing "creates ExceptionInfo with cause"
    (let [cause (Exception. "Original error")
          ex (errors/make-storage-error :connection-error :create-entity
                                        "Connection failed" {} cause)]
      (is (= cause (ex-cause ex)))))

  (testing "includes all context in exception data"
    (let [ex (errors/make-storage-error :not-found :read-entity
                                        "Not found"
                                        {:entity-name :user :id 123 :custom "data"})]
      (is (= :user (:entity-name (ex-data ex))))
      (is (= 123 (:id (ex-data ex))))
      (is (= "data" (:custom (ex-data ex)))))))


;; === Error Registry Tests ===

(deftest register-error-type-test
  (testing "registers new error type"
    (errors/register-error-type! :test/custom-error
                                 {:category :validation
                                  :retryable? true
                                  :severity :warning
                                  :description "Test custom error"})
    (is (contains? (errors/registered-error-types) :test/custom-error))
    (let [error-meta (errors/get-error-metadata :test/custom-error)]
      (is (= :validation (:category error-meta)))
      (is (true? (:retryable? error-meta)))
      (is (= :warning (:severity error-meta)))
      (is (= "Test custom error" (:description error-meta)))))

  (testing "applies default values"
    (errors/register-error-type! :test/minimal
                                 {:category :execution})
    (let [error-meta (errors/get-error-metadata :test/minimal)]
      (is (= :error (:severity error-meta)))
      (is (false? (:retryable? error-meta)))))

  (testing "rejects non-keyword error type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"error-type must be a keyword"
          (errors/register-error-type! "not-a-keyword" {:category :validation}))))

  (testing "rejects invalid category"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid error category"
          (errors/register-error-type! :test/bad-category {:category :nonexistent})))))


(deftest get-error-metadata-test
  (testing "returns nil for unregistered type"
    (is (nil? (errors/get-error-metadata :definitely/not-registered))))

  (testing "returns metadata for registered type"
    (let [error-meta (errors/get-error-metadata :not-found)]
      (is (some? error-meta))
      (is (= :validation (:category error-meta))))))


(deftest error-retryable-test
  (testing "returns true for retryable errors"
    (is (errors/error-retryable? :connection-error))
    (is (errors/error-retryable? :system-error/query-timeout))
    (is (errors/error-retryable? :transient-error/busy)))

  (testing "returns false for non-retryable errors"
    (is (not (errors/error-retryable? :not-found)))
    (is (not (errors/error-retryable? :constraint-violation/unique))))

  (testing "returns false for unknown errors"
    (is (not (errors/error-retryable? :unknown/type)))))


(deftest error-category-test
  (testing "returns correct category for registered errors"
    (is (= :constraint (:category (errors/get-error-metadata :constraint-violation/unique))))
    (is (= :validation (:category (errors/get-error-metadata :not-found))))
    (is (= :connection (:category (errors/get-error-metadata :connection-error))))
    (is (= :execution (:category (errors/get-error-metadata :execution-error/graph-too-large)))))

  (testing "returns :unknown for unregistered errors"
    (is (= :unknown (errors/error-category :definitely/not-registered)))))


(deftest registered-error-types-test
  (testing "includes standard error types"
    (let [types (errors/registered-error-types)]
      (is (contains? types :not-found))
      (is (contains? types :connection-error))
      (is (contains? types :constraint-violation/unique))
      (is (contains? types :system-error/query-timeout)))))


(deftest error-categories-test
  (testing "contains all expected categories"
    (is (contains? errors/error-categories :constraint))
    (is (contains? errors/error-categories :validation))
    (is (contains? errors/error-categories :config))
    (is (contains? errors/error-categories :connection))
    (is (contains? errors/error-categories :execution))
    (is (contains? errors/error-categories :metadata))
    (is (contains? errors/error-categories :batch))
    (is (contains? errors/error-categories :unknown))))


(deftest error-severities-test
  (testing "contains all expected severities"
    (is (contains? errors/error-severities :error))
    (is (contains? errors/error-severities :warning))
    (is (contains? errors/error-severities :info))))


;; === Sensitive Field Registry Tests ===

(deftest register-sensitive-field-name-test
  (testing "registers custom sensitive field name"
    (redaction/register-sensitive-field-name! :employee-ssn)
    (is (contains? (redaction/sensitive-field-names) :employee-ssn))
    (is (redaction/sensitive-field? :employee-ssn)))

  (testing "rejects non-keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"field-name must be a keyword"
          (redaction/register-sensitive-field-name! "string-name")))))


(deftest register-sensitive-field-pattern-test
  (testing "registers custom pattern"
    (redaction/register-sensitive-field-pattern! #"(?i)patient[_-]?id")
    (is (redaction/sensitive-field? :patient-id))
    (is (redaction/sensitive-field? :patient_id))
    (is (redaction/sensitive-field? :patientid)))

  (testing "rejects non-pattern"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pattern must be a compiled regex"
          (redaction/register-sensitive-field-pattern! "not-a-pattern")))))


(deftest register-sensitive-field-predicate-test
  (testing "registers custom predicate"
    (redaction/register-sensitive-field-predicate!
      (fn [k] (= "pii" (namespace k))))
    (is (redaction/sensitive-field? :pii/name))
    (is (redaction/sensitive-field? :pii/address))
    (is (not (redaction/sensitive-field? :public/name))))

  (testing "rejects non-function"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pred-fn must be a function"
          (redaction/register-sensitive-field-predicate! :not-a-function)))))


(deftest reset-sensitive-field-registry-test
  (testing "resets to defaults"
    ;; Add custom registration
    (redaction/register-sensitive-field-name! :custom-sensitive)
    (is (redaction/sensitive-field? :custom-sensitive))

    ;; Reset
    (redaction/reset-sensitive-field-registry!)

    ;; Custom should be gone, defaults should remain
    (is (not (contains? (redaction/sensitive-field-names) :custom-sensitive)))
    (is (redaction/sensitive-field? :password))))


(deftest get-and-set-sensitive-field-registry-test
  (testing "get returns current state"
    (let [state (redaction/get-sensitive-field-registry)]
      (is (set? (:names state)))
      (is (vector? (:patterns state)))
      (is (vector? (:predicates state)))))

  (testing "set restores previous state"
    (let [original (redaction/get-sensitive-field-registry)]
      (redaction/register-sensitive-field-name! :temp-field)
      (is (redaction/sensitive-field? :temp-field))
      (redaction/set-sensitive-field-registry! original)
      (is (not (contains? (redaction/sensitive-field-names) :temp-field))))))


(deftest with-sensitive-field-registry-test
  (testing "isolates registry modifications"
    (let [before-count (count (redaction/sensitive-field-names))]
      (redaction/with-sensitive-field-registry
        (redaction/register-sensitive-field-name! :isolated-field)
        (is (redaction/sensitive-field? :isolated-field)))
      ;; After macro, registration should be rolled back
      (is (= before-count (count (redaction/sensitive-field-names))))
      (is (not (contains? (redaction/sensitive-field-names) :isolated-field))))))


(deftest sensitive-field-names-test
  (testing "returns set of names"
    (let [names (redaction/sensitive-field-names)]
      (is (set? names))
      (is (contains? names :password))
      (is (contains? names :api-key)))))


(deftest sensitive-field-patterns-test
  (testing "returns vector of patterns"
    (let [patterns (redaction/sensitive-field-patterns)]
      (is (vector? patterns))
      (is (every? #(instance? java.util.regex.Pattern %) patterns)))))


(deftest sensitive-field-test
  (testing "detects explicit field names"
    (is (redaction/sensitive-field? :password))
    (is (redaction/sensitive-field? :secret))
    (is (redaction/sensitive-field? :token))
    (is (redaction/sensitive-field? :api-key))
    (is (redaction/sensitive-field? :credentials)))

  (testing "detects via patterns"
    (is (redaction/sensitive-field? :user-password))
    (is (redaction/sensitive-field? :access-token))
    (is (redaction/sensitive-field? :api_key))
    (is (redaction/sensitive-field? :auth-header)))

  (testing "handles strings"
    (is (redaction/sensitive-field? "password"))
    (is (redaction/sensitive-field? "api-key")))

  (testing "handles nil gracefully"
    (is (nil? (redaction/sensitive-field? nil))))

  (testing "returns falsy for non-sensitive fields"
    (is (not (redaction/sensitive-field? :username)))
    (is (not (redaction/sensitive-field? :email)))
    (is (not (redaction/sensitive-field? :id)))))


(deftest critical-sensitive-patterns-test
  (testing "all critical patterns are matched"
    (doseq [pattern redaction/critical-sensitive-patterns]
      (is (redaction/sensitive-field? pattern)
          (str "Critical pattern not matched: " pattern)))))


(deftest validate-sensitive-field-coverage-test
  (testing "passes with default registry"
    (is (nil? (redaction/validate-sensitive-field-coverage!))))

  (testing "fails when critical patterns not covered"
    ;; Reset to empty registry
    (redaction/set-sensitive-field-registry! {:names #{} :patterns [] :predicates []})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Critical sensitive patterns not covered"
          (redaction/validate-sensitive-field-coverage!)))))


;; === Redaction Tests ===

(deftest redact-sensitive-map-test
  (testing "redacts sensitive keys in map"
    (let [result (redaction/redact-sensitive-map {:password "secret" :username "john"})]
      (is (= "[REDACTED]" (:password result)))
      (is (= "john" (:username result)))))

  (testing "handles empty map"
    (is (= {} (redaction/redact-sensitive-map {}))))

  (testing "handles nil"
    (is (nil? (redaction/redact-sensitive-map nil))))

  (testing "redacts multiple sensitive fields"
    (let [result (redaction/redact-sensitive-map {:password "x" :api-key "y" :token "z" :name "john"})]
      (is (= "[REDACTED]" (:password result)))
      (is (= "[REDACTED]" (:api-key result)))
      (is (= "[REDACTED]" (:token result)))
      (is (= "john" (:name result))))))


(deftest redact-sensitive-deep-test
  (testing "redacts nested sensitive values"
    (let [data {:config {:database {:password "secret"
                                    :host "localhost"}}}
          result (redaction/redact-sensitive-deep data)]
      (is (= "[REDACTED]" (get-in result [:config :database :password])))
      (is (= "localhost" (get-in result [:config :database :host])))))

  (testing "handles vectors with maps"
    (let [data {:users [{:name "john" :api-key "key1"}
                        {:name "jane" :api-key "key2"}]}
          result (redaction/redact-sensitive-deep data)]
      (is (= "john" (get-in result [:users 0 :name])))
      (is (= "[REDACTED]" (get-in result [:users 0 :api-key])))
      (is (= "[REDACTED]" (get-in result [:users 1 :api-key])))))

  (testing "handles primitives"
    (is (= "hello" (redaction/redact-sensitive-deep "hello")))
    (is (= 123 (redaction/redact-sensitive-deep 123)))
    (is (nil? (redaction/redact-sensitive-deep nil))))

  (testing "handles sets"
    (let [result (redaction/redact-sensitive-deep #{1 2 3})]
      (is (set? result))
      (is (= #{1 2 3} result))))

  (testing "handles mixed collections"
    (let [data {:items [1 "two" {:secret "hidden"}]}
          result (redaction/redact-sensitive-deep data)]
      (is (= 1 (get-in result [:items 0])))
      (is (= "two" (get-in result [:items 1])))
      (is (= "[REDACTED]" (get-in result [:items 2 :secret]))))))


;; === Storage Error Types Tests ===

(deftest storage-error-types-test
  (testing "contains expected error types"
    (is (contains? errors/storage-error-types :unique-violation))
    (is (contains? errors/storage-error-types :foreign-key-violation))
    (is (contains? errors/storage-error-types :not-null-violation))
    (is (contains? errors/storage-error-types :connection-error))
    (is (contains? errors/storage-error-types :system-error/query-timeout))
    (is (contains? errors/storage-error-types :table-not-found))))


;; === Validation Error Factory Tests ===

(deftest create-validation-error-test
  (testing "creates ExceptionInfo with correct structure"
    (let [ex (errors/create-validation-error
               :validation-error/field-missing
               "Field :name is required"
               {:entity :user :field :name})]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= "Field :name is required" (ex-message ex)))
      (is (= :validation-error/field-missing (:type (ex-data ex))))
      (is (= :user (:entity (ex-data ex))))
      (is (= :name (:field (ex-data ex))))))

  (testing "requires namespaced keyword for error-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"error-type must be a namespaced keyword"
          (errors/create-validation-error :not-namespaced "message" {}))))

  (testing "rejects non-keyword error-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"error-type must be a namespaced keyword"
          (errors/create-validation-error "string" "message" {})))))


(deftest throw-validation-error-test
  (testing "throws validation error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Test error"
          (errors/throw-validation-error!
            :validation-error/test
            "Test error"
            {:context "value"}))))

  (testing "exception contains correct data"
    (try
      (errors/throw-validation-error!
        :schema-error/invalid-type
        "Invalid type"
        {:expected :int :actual :string})
      (is false "expected errors/throw-validation-error! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :schema-error/invalid-type (:type (ex-data e))))
        (is (= :int (:expected (ex-data e))))
        (is (= :string (:actual (ex-data e))))))))


(deftest with-storage-error-handling-test
  (testing "returns body result on success"
    (let [result (errors/with-storage-error-handling
                   :storage-error/test :test-op {:ctx "value"}
                   :success-result)]
      (is (= :success-result result))))

  (testing "wraps ExceptionInfo with merged context"
    (try
      (errors/with-storage-error-handling
        :storage-error/test :query-entities {:entity-name :user}
        (throw (ex-info "Original error" {:original true})))
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= "Original error" (ex-message e)))
          (is (= :query-entities (:operation data)))
          (is (= :user (:entity-name data)))
          (is (true? (:original data)))))))

  (testing "wraps non-ExceptionInfo with error-type"
    (try
      (errors/with-storage-error-handling
        :storage-error/io-failed :write-entity {:file "test.txt"}
        (throw (RuntimeException. "IO failed")))
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :storage-error/io-failed (:type data)))
          (is (= :write-entity (:operation data)))
          (is (= "test.txt" (:file data)))
          (is (instance? RuntimeException (ex-cause e)))))))

  (testing "handles exception with nil message"
    (try
      (errors/with-storage-error-handling
        :storage-error/test :op {}
        (throw (RuntimeException.)))
      (catch clojure.lang.ExceptionInfo e
        (is (= "Storage operation failed" (ex-message e)))))))


;; === Warn on Suspicious Field Tests ===

(deftest warn-on-suspicious-field-test
  (testing "returns nil for nil input"
    (is (nil? (redaction/warn-on-suspicious-field nil))))

  (testing "returns nil for non-suspicious registered field"
    (is (nil? (redaction/warn-on-suspicious-field :password))))  ; registered, not suspicious

  (testing "returns nil for clearly non-sensitive field"
    (is (nil? (redaction/warn-on-suspicious-field :username))))

  (testing "returns true for suspicious unregistered field"
    ;; This field looks suspicious (has 'key' in name) but isn't registered
    ;; Note: may actually be caught by patterns - test different field
    (redaction/with-sensitive-field-registry
      (redaction/reset-sensitive-field-registry!)
      ;; With empty registry, 'api-key-backup' should be suspicious
      (redaction/set-sensitive-field-registry! {:names #{} :patterns [] :predicates []})
      (is (true? (redaction/warn-on-suspicious-field :api-key-backup))))))


;; === Redact deep with sequences ===

(deftest redact-sensitive-deep-sequences-test
  (testing "handles lists (sequences)"
    (let [data {:items (list {:password "secret"} {:name "john"})}
          result (redaction/redact-sensitive-deep data)]
      ;; Result should have redacted password
      (is (= "[REDACTED]" (:password (first (:items result))))))))


;; === Empty name string edge case ===

(deftest sensitive-field-empty-name-test
  (testing "handles keyword with empty name"
    ;; This is an edge case - empty keyword name
    (is (not (redaction/sensitive-field? (keyword ""))))))
