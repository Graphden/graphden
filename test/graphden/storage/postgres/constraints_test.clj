(ns graphden.storage.postgres.constraints-test
  "Tests for PostgreSQL storage GraphConstraints protocol.

   Covers:
   - validate-no-dependency-cycle!
   - GraphConstraints contract tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.contract-tests :as contract]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === GraphConstraints tests ===


^:integration
(deftest ^:integration validate-no-dependency-cycle-test
  (testing "allows non-cyclic reference"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          fn-a (setup/create-base-fn! storage "fn-a")
          fn-b (setup/create-base-fn! storage "fn-b")]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b))))
        (finally
          (sp/close storage)))))

  (testing "allows nil ref-fn-id"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          fn-a (setup/create-base-fn! storage "fn-a")]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) nil)))
        (finally
          (sp/close storage)))))

  (testing "allows self-reference (recursion is intended; depth bounded by executor)"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          fn-a (setup/create-base-fn! storage "fn-a")]
      (try
        ;; docs/CONSTRAINTS.md § Self-reference carves this out so
        ;; recursive fn-defs (the only way to express recursion in
        ;; the slot/binding model) stay legal. The executor's
        ;; *max-depth* bounds the runtime cost.
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-a))))
        (finally
          (sp/close storage)))))

  (testing "throws when dependency cycle detected through bindings"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          ;; A base fn carrying one slot 'x' typed against itself —
          ;; that's enough type-fn-id, since the cycle walker only
          ;; follows binding.ref-fn-id.
          base-fn (setup/create-base-fn! storage "base-fn")
          slot-x (setup/create-slot! storage "x" (:id base-fn))
          _ (setup/create-fn-slot! storage (:id base-fn) (:id slot-x) 0)
          ;; Three composed fns each inheriting from base-fn.
          fn-a (setup/create-composed-fn! storage "fn-a" (:id base-fn))
          fn-b (setup/create-composed-fn! storage "fn-b" (:id base-fn))
          fn-c (setup/create-composed-fn! storage "fn-c" (:id base-fn))
          ;; fn-b's x → fn-c (b depends on c)
          _ (setup/create-binding! storage (:id fn-b) (:id slot-x)
                                   :ref-fn-id (:id fn-c))
          ;; fn-c's x → fn-a (c depends on a)
          _ (setup/create-binding! storage (:id fn-c) (:id slot-x)
                                   :ref-fn-id (:id fn-a))]
      (try
        ;; Try to validate a → b, which would close the cycle a→b→c→a.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b))))
        (finally
          (sp/close storage))))))


;; === GraphConstraints contract tests ===

^:integration
(deftest ^:integration graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    setup/create-test-storage
    sp/close))
