(ns graphden.storage.protocol.constraints-test
  "Tests for storage-protocol constraint helpers and implementations.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === Mock ConstraintHelpers for testing shared implementations ===

(defrecord MockConstraintHelpers
  [dependency-chain-map]

  storage/ConstraintHelpers

  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{})))


;; === validate-no-dependency-cycle-impl tests ===

(deftest validate-no-dependency-cycle-impl-test
  (testing "nil ref-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers (random-uuid) nil)))))

  (testing "self-reference is allowed (recursion is intended; depth bounded by executor)"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {})]
      ;; docs/CONSTRAINTS.md § Self-reference carves this case out so
      ;; recursive fn-defs (the only way to express recursion in the
      ;; slot/binding model) stay legal at the storage layer. The
      ;; executor's *max-depth* bounds the runtime cost.
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-id fn-id)))))

  (testing "non-cyclic dependency doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-a depends on fn-b (not a cycle)
          helpers (->MockConstraintHelpers {fn-b #{}})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "cycle through dependency chain throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Dependency chain: fn-c -> fn-b -> fn-a
          ;; Trying to add fn-a -> fn-c (would create cycle)
          helpers (->MockConstraintHelpers {fn-c #{fn-a fn-b}})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Reference would create dependency cycle"
            (storage/validate-no-dependency-cycle-impl helpers fn-a fn-c)))))

  (testing "exception contains data for chain cycle"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-b #{fn-a}})]
      (try
        (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)
        (is false "expected storage/validate-no-dependency-cycle-impl to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= fn-a (:owner-fn-id (ex-data e))))
          (is (= fn-b (:ref-fn-id (ex-data e)))))))))


;; === StorageBatchCRUD protocol tests ===

(deftest storage-batch-crud-protocol-test
  (testing "StorageBatchCRUD protocol is defined"
    (is (some? storage/StorageBatchCRUD))
    (is (contains? (:sigs storage/StorageBatchCRUD) :create-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :read-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :delete-entities))))
