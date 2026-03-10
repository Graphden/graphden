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


;; === Arg descendants constraint tests ===


(deftest arg-descendants-update-test
  ;; Note: Uses "integer" as type value to avoid codec bug where "int" gets
  ;; converted to keyword :int during decode (matches known-value-kind-values)
  ;; and then fails on re-encode during update.
  (testing "allows updating arg with no descendants"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          base-fn (setup/create-base-fn! storage "my-fn" :int)
          arg (setup/create-arg! storage (:id base-fn)
                                 {:name "x" :type :integer :required true :is-fn false
                                  :value 42})]
      (try
        ;; Should succeed - no descendants
        (let [updated (sp/update-entity storage :arg (:id arg) {:value 100})]
          (is (= 100 (:value updated))))
        (finally
          (sp/close storage)))))

  (testing "rejects updating arg with descendants"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          base-fn (setup/create-base-fn! storage "base" :int)
          parent-arg (setup/create-arg! storage (:id base-fn)
                                        {:name "x" :type :integer :required true :is-fn false
                                         :value 10})
          child-fn (setup/create-composed-fn! storage "child" (:id base-fn))
          ;; Child arg has source-id pointing to parent-arg
          _child-arg (setup/create-arg! storage (:id child-fn)
                                        {:name "x" :type :integer :required true :is-fn false
                                         :source-id (:id parent-arg) :value 20})]
      (try
        ;; Should fail - parent-arg has descendants
        (let [ex (try
                   (sp/update-entity storage :arg (:id parent-arg) {:value 999})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :constraint-violation/has-descendants (:type (ex-data ex))))
          (is (= 1 (:descendant-count (ex-data ex)))))
        (finally
          (sp/close storage))))))


(deftest arg-descendants-delete-test
  ;; Note: Delete test doesn't trigger the codec bug since we don't re-encode,
  ;; but we use :integer for consistency with the update test.
  (testing "allows deleting arg with no descendants"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          base-fn (setup/create-base-fn! storage "my-fn" :int)
          arg (setup/create-arg! storage (:id base-fn)
                                 {:name "x" :type :integer :required true :is-fn false
                                  :value 42})]
      (try
        ;; Should succeed - no descendants
        (is (true? (sp/delete-entity storage :arg (:id arg))))
        (finally
          (sp/close storage)))))

  (testing "rejects deleting arg with descendants"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          base-fn (setup/create-base-fn! storage "base" :int)
          parent-arg (setup/create-arg! storage (:id base-fn)
                                        {:name "x" :type :integer :required true :is-fn false
                                         :value 10})
          child-fn (setup/create-composed-fn! storage "child" (:id base-fn))
          ;; Child arg has source-id pointing to parent-arg
          _child-arg (setup/create-arg! storage (:id child-fn)
                                        {:name "x" :type :integer :required true :is-fn false
                                         :source-id (:id parent-arg) :value 20})]
      (try
        ;; Should fail - parent-arg has descendants
        (let [ex (try
                   (sp/delete-entity storage :arg (:id parent-arg))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :constraint-violation/has-descendants (:type (ex-data ex))))
          (is (= :delete (:operation (ex-data ex)))))
        (finally
          (sp/close storage))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    setup/create-test-storage
    sp/close))
