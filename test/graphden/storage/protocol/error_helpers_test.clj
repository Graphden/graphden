(ns graphden.storage.protocol.error-helpers-test
  "Tests for error context and storage error helpers.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.storage.protocol.core :as storage]))


;; === Mock for constraint helpers ===

(defrecord MockConstraintHelpers
  [dependency-chain-map]

  storage/ConstraintHelpers

  (collect-dependency-chain
    [_ fn-id]
    (get dependency-chain-map fn-id #{})))


;; === Error helpers tests ===
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


;; The two constructors belong to `protocol.errors` and are pinned by
;; `errors-test`, which asserts the same shapes with different literals.
;; What is unique here is the ConstraintHelpers-backed dependency-cycle
;; payload, which needs the mock above.
