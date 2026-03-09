(ns graphden.storage.postgres.constraints-test
  "Tests for PostgreSQL storage GraphConstraints protocol.

   Covers:
   - validate-no-dependency-cycle!
   - GraphConstraints contract tests

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.contract-tests :as contract]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === GraphConstraints tests ===


(deftest validate-no-dependency-cycle-test
  (testing "allows non-cyclic reference"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          ;; Create two independent fns
          fn-a (setup/create-base-fn! storage "fn-a" :int)
          fn-b (setup/create-base-fn! storage "fn-b" :int)]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b))))
        (finally
          (sp/close storage)))))

  (testing "allows nil ref-id"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          fn-a (setup/create-base-fn! storage "fn-a" :int)]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) nil)))
        (finally
          (sp/close storage)))))

  (testing "rejects self-reference as cycle"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          fn-a (setup/create-base-fn! storage "fn-a" :int)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-a))))
        (finally
          (sp/close storage)))))

  (testing "throws when dependency cycle detected"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          ;; Create base fn
          base-fn (setup/create-base-fn! storage "base-fn" :int)
          base-arg (setup/create-arg! storage (:id base-fn)
                                      {:name "x" :type :int :required true :is-fn false})
          ;; Create fn-a, fn-b, fn-c
          fn-a (setup/create-composed-fn! storage "fn-a" (:id base-fn))
          fn-b (setup/create-composed-fn! storage "fn-b" (:id base-fn))
          fn-c (setup/create-composed-fn! storage "fn-c" (:id base-fn))
          ;; fn-b's x -> fn-c via ref-id (b depends on c)
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id base-arg) :ref-id (:id fn-c)})
          ;; fn-c's x -> fn-a via ref-id (c depends on a)
          _ (setup/create-arg! storage (:id fn-c)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id base-arg) :ref-id (:id fn-a)})]
      (try
        ;; Try to validate a -> b, which would create cycle: a -> b -> c -> a
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b))))
        (finally
          (sp/close storage))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    setup/create-test-storage
    sp/close))
