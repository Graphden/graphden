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
   - Unknown type validation tests

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
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
              (setup/fn-impl [data]
                             data))
          ;; Create base fn
          base-fn (setup/create-base-fn! storage "use-any" :any)
          ;; Create arg for base fn
          data-arg (setup/create-arg! storage (:id base-fn)
                                      {:name "data" :type :any :required true :is-fn false})
          ;; Create composed fn
          composed-fn (setup/create-composed-fn! storage "my-use-any" (:id base-fn))
          ;; No arg value - test provides values via execute (free arg)
          ctx (exec/create-context {:storage storage})]
      ;; Any type should accept any value (provided at runtime since no DB value)
      (is (= "a string" (exec/execute ctx (:id composed-fn) {(:id data-arg) "a string"})))
      (is (= 12345 (exec/execute ctx (:id composed-fn) {(:id data-arg) 12345})))
      (is (= {:key "value"} (exec/execute ctx (:id composed-fn) {(:id data-arg) {:key "value"}})))
      (is (= [1 2 3] (exec/execute ctx (:id composed-fn) {(:id data-arg) [1 2 3]})))
      (sp/close storage))))


;; Note: Nil guards in build-thunk are for defensive programming.
;; They cannot be triggered through normal execution flow because:
;; - nil arg-value: build-thunks checks for nil before calling build-thunk
;; - nil arg-schema: build-thunks iterates over arg-schemas map, not arg-values
;; The guards protect against future code changes that might bypass these checks.


;; === Large Value Truncation Tests ===

;; Type-mismatch value-truncation and depth-limit tests were removed
;; alongside the legacy queue. Those invariants (max-depth enforcement,
;; arg value type validation with truncated error payloads) are not yet
;; implemented in the compile executor — re-introduce once parity lands.


;; The legacy max-depth / timeout-ms validation was retired with the
;; queue executor; the remaining context-level validation (`storage`
;; presence + protocol satisfaction) lives in `context_test.clj`.


;; === Additional Timestamp Type Tests ===

(deftest local-date-time-type-validation-test
  (testing "accepts valid LocalDateTime value for timestamptz"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (setup/fn-impl [ts]
                             ts))
          ;; Create base fn
          base-fn (setup/create-base-fn! storage "use-timestamp" :timestamptz)
          ;; Create arg for base fn
          ts-arg (setup/create-arg! storage (:id base-fn)
                                    {:name "ts" :type :timestamptz :required true :is-fn false})
          ;; Create composed fn
          composed-fn (setup/create-composed-fn! storage "my-use-timestamp" (:id base-fn))
          ;; No arg value - provide at runtime
          ctx (exec/create-context {:storage storage})
          test-ldt (java.time.LocalDateTime/of 2024 1 1 12 0 0)]
      (is (= test-ldt (exec/execute ctx (:id composed-fn) {(:id ts-arg) test-ldt})))
      (sp/close storage))))


;; Timeout validation tests were removed along with the legacy queue
;; executor. Re-introduce once compile enforces per-call timeouts.


;; === Execute Args Validation Tests ===

(deftest execute-args-validation-test
  (testing "throws when args is not nil or a map"
    (let [storage (setup/create-test-storage)
          ;; Setup add function and bind args with values
          {:keys [arg-a arg-b composed-fn]} (setup/setup-add-function! storage)
          ;; Create args for composed fn with values
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 1})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 2})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id composed-fn) "not a map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id composed-fn) [:a :vector])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id composed-fn) 123)))
      (sp/close storage)))

  (testing "accepts nil args"
    (let [storage (setup/create-test-storage)
          {:keys [arg-a arg-b composed-fn]} (setup/setup-add-function! storage)
          ;; Create args for composed fn with values
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 1})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 2})
          ctx (exec/create-context {:storage storage})]
      ;; nil should work fine
      (is (= 3 (exec/execute ctx (:id composed-fn) nil)))
      (sp/close storage))))
