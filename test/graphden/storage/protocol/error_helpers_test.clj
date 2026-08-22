(ns graphden.storage.protocol.error-helpers-test
  "Tests for error context and storage error helpers.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === Mock for constraint helpers ===

(defrecord MockConstraintHelpers
  [dependency-chain-map]

  storage/ConstraintHelpers

  (collect-dependency-chain
    [_ fn-id]
    (get dependency-chain-map fn-id #{})))


;; === Error helpers tests ===

(deftest make-error-context-test
  (testing "creates error context with required fields"
    (let [ctx (storage/make-error-context :test-error :create "Test message" {:entity :user})]
      (is (= :test-error (:type ctx)))
      (is (= :create (:operation ctx)))
      (is (= "Test message" (:message ctx)))
      (is (= :user (:entity ctx)))))

  (testing "merges additional context"
    (let [ctx (storage/make-error-context :error-type :read "msg" {:id 123 :extra "data"})]
      (is (= :error-type (:type ctx)))
      (is (= :read (:operation ctx)))
      (is (= 123 (:id ctx)))
      (is (= "data" (:extra ctx))))))


(deftest make-storage-error-test
  (testing "creates storage error without cause"
    (let [err (storage/make-storage-error :test-error :create "Test message" {:entity :user})]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "Test message" (ex-message err)))
      (is (= :test-error (:type (ex-data err))))
      (is (= :create (:operation (ex-data err))))
      (is (= :user (:entity (ex-data err))))
      (is (nil? (ex-cause err)))))

  (testing "creates storage error with cause"
    (let [cause (ex-info "Original error" {:original true})
          err (storage/make-storage-error :wrapped-error :update "Wrapped" {:id 42} cause)]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "Wrapped" (ex-message err)))
      (is (= :wrapped-error (:type (ex-data err))))
      (is (= :update (:operation (ex-data err))))
      (is (= 42 (:id (ex-data err))))
      (is (= cause (ex-cause err)))
      (is (= "Original error" (ex-message (ex-cause err)))))))


(deftest dependency-cycle-exception-payload-test
  ;; The cycle CONTRACT (nil ref, self-reference, chain detection) is
  ;; `constraints-test`'s subject and was duplicated here; what belongs
  ;; with the error helpers is the shape of what it throws.
  (let [fn-a (random-uuid)
        fn-b (random-uuid)
        helpers (->MockConstraintHelpers {fn-b #{fn-a fn-b}})]
    (try
      (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)
      (is false "expected a cycle rejection")
      (catch clojure.lang.ExceptionInfo e
        (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
        (is (= fn-a (:owner-fn-id (ex-data e))))
        (is (= fn-b (:ref-fn-id (ex-data e))))))))
