(ns graphden.executor.advanced-test
  "Advanced tests for executor.

   Covers:
   - Union type tests
   - Large value truncation tests
   - Deep nesting tests
   - Context validation tests
   - Timestamp type tests
   - Timeout validation tests
   - Execute args validation tests
   - Unknown type validation tests"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; === Any Type Tests ===

(deftest any-type-validation-test
  (testing ":any type accepts any value without strict validation"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-any
              (fn [{:keys [data]} _ctx]
                @data))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-any"
                                       :returned-type :any})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :any
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-any"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - test provides values via execute (free arg)
          ctx (exec/create-context {:storage storage})]
      ;; Any type should accept any value (provided at runtime since no DB value)
      (is (= "a string" (exec/execute ctx (:id fn-rec) {(:id data-arg) "a string"})))
      (is (= 12345 (exec/execute ctx (:id fn-rec) {(:id data-arg) 12345})))
      (is (= {:key "value"} (exec/execute ctx (:id fn-rec) {(:id data-arg) {:key "value"}})))
      (is (= [1 2 3] (exec/execute ctx (:id fn-rec) {(:id data-arg) [1 2 3]})))
      (sp/close storage))))


;; Note: Nil guards in build-thunk are for defensive programming.
;; They cannot be triggered through normal execution flow because:
;; - nil arg-value: build-thunks checks for nil before calling build-thunk
;; - nil arg-schema: build-thunks iterates over arg-schemas map, not arg-values
;; The guards protect against future code changes that might bypass these checks.


;; === Large Value Truncation Tests ===

(deftest large-value-truncation-in-error-test
  (testing "large values are truncated in type mismatch errors"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-int
              (fn [{:keys [n]} _ctx]
                @n))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-int"
                                       :returned-type :int})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-int"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free, test provides invalid type via execute
          ctx (exec/create-context {:storage storage})
          ;; Create a very large string (> 100 chars) that will be truncated
          large-string (str/join (repeat 200 "x"))]
      (try
        ;; Providing string to :int type arg should trigger type mismatch error
        (exec/execute ctx (:id fn-rec) {(:id n-arg) large-string})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)
                truncated-value (:provided-value data)]
            ;; The truncated value should end with "..."
            (is (clojure.string/ends-with? truncated-value "..."))
            ;; And should be around 103 chars (100 + "...")
            (is (<= (count truncated-value) 105)))))
      (sp/close storage))))


;; === Deep Nesting Tests ===

(deftest deep-nesting-near-limit-test
  (testing "executes successfully at exactly max-depth"
    (let [storage (setup/create-test-storage)
          ;; Register identity function that forces its arg
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Create identity fn-schema
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true :first-class false})
          ;; Create a chain of 3 functions
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id id-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id id-schema)})
          fn-c (sp/create-entity storage :fn {:name "fn-c" :fn-schema-id (:id id-schema)})
          ;; fn-a -> fn-b -> fn-c -> literal (via fn-usage to trigger execution)
          _ (setup/create-arg-value-with-fn-usage-binding! storage (:id fn-a) (:id id-arg)
                                                           (setup/create-fn-usage! storage (:id fn-b)))
          _ (setup/create-arg-value-with-fn-usage-binding! storage (:id fn-b) (:id id-arg)
                                                           (setup/create-fn-usage! storage (:id fn-c)))
          _ (setup/create-arg-value-with-binding! storage (:id fn-c) (:id id-arg) 42)
          ;; max-depth=3 means: fn-a(0) -> fn-b(1) -> fn-c(2) -> literal
          ;; This should work as 2 < 3
          ctx (exec/create-context {:storage storage :max-depth 3})]
      (is (= 42 (exec/execute ctx (:id fn-a) {})))
      (sp/close storage)))

  (testing "fails when depth exceeds max-depth"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true :first-class false})
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id id-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id id-schema)})
          fn-c (sp/create-entity storage :fn {:name "fn-c" :fn-schema-id (:id id-schema)})
          _ (setup/create-arg-value-with-fn-usage-binding! storage (:id fn-a) (:id id-arg)
                                                           (setup/create-fn-usage! storage (:id fn-b)))
          _ (setup/create-arg-value-with-fn-usage-binding! storage (:id fn-b) (:id id-arg)
                                                           (setup/create-fn-usage! storage (:id fn-c)))
          _ (setup/create-arg-value-with-binding! storage (:id fn-c) (:id id-arg) 42)
          ;; max-depth=1: fn-a(0) ok, fn-b(1) ok, fn-c(2) fails because depth=2 > max-depth=1
          ctx (exec/create-context {:storage storage :max-depth 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Maximum recursion depth exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


;; === Context Validation Tests ===

(deftest context-validation-test
  (testing "throws when max-depth exceeds upper limit"
    (let [storage (setup/create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-depth exceeds maximum allowed value"
            (exec/create-context {:storage storage :max-depth 100001})))
      (sp/close storage)))

  (testing "accepts max-depth at upper limit"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage :max-depth 100000})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "throws when max-depth is not a positive integer"
    (let [storage (setup/create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-depth must be a positive integer"
            (exec/create-context {:storage storage :max-depth 0})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-depth must be a positive integer"
            (exec/create-context {:storage storage :max-depth -1})))
      (sp/close storage))))


;; === Additional Timestamp Type Tests ===

(deftest local-date-time-type-validation-test
  (testing "accepts valid LocalDateTime value for timestamptz"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free for runtime provision
          ctx (exec/create-context {:storage storage})
          test-ldt (java.time.LocalDateTime/of 2024 1 1 12 0 0)]
      (is (= test-ldt (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-ldt})))
      (sp/close storage))))


;; === Timeout Validation Tests ===

(deftest timeout-ms-validation-test
  (testing "throws when timeout-ms is below minimum (50ms)"
    (let [storage (setup/create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"timeout-ms must be at least 50ms"
            (exec/create-context {:storage storage :timeout-ms 10})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"timeout-ms must be at least 50ms"
            (exec/create-context {:storage storage :timeout-ms 49})))
      (sp/close storage)))

  (testing "accepts timeout-ms at minimum (50ms)"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage :timeout-ms 50})]
      (is (some? ctx))
      (sp/close storage))))


;; === Execute Args Validation Tests ===

(deftest execute-args-validation-test
  (testing "throws when args is not nil or a map"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 1)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 2)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id fn-rec) "not a map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id fn-rec) [:a :vector])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id fn-rec) 123)))
      (sp/close storage)))

  (testing "accepts nil args"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 1)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 2)
          ctx (exec/create-context {:storage storage})]
      ;; nil should work fine
      (is (= 3 (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage))))


;; NOTE: Unknown type validation tests were removed because PostgreSQL
;; enforces enum values at the database level. The :type field uses
;; PostgreSQL enum 'value_kind' which only accepts known values.
;; In the old memory-storage, arbitrary keywords could be stored,
;; but PostgreSQL correctly rejects invalid enum values.
;;
;; The executor's strict-type-validation? feature is still useful for
;; validation of runtime-provided argument values against known types,
;; but the database already ensures only valid types can be stored.
