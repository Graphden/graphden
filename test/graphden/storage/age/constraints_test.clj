(ns graphden.storage.age.constraints-test
  "Tests for AGE storage GraphConstraints protocol.

   Covers:
   - validate-arg-schema-belongs-to-fn!
   - validate-no-dependency-cycle!

   Note: Contract tests (contract/run-graph-constraints-tests) are NOT included
   because they use a simplified schema with :text type for returned-type,
   but AGE storage uses graph-data-schema with :value-kind enum type."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.age.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === validate-arg-schema-belongs-to-fn! tests ===

(deftest validate-arg-schema-belongs-to-fn-matching-test
  (testing "allows matching schema"
    (let [storage (setup/create-test-storage)
          fn-schema-id (java.util.UUID/randomUUID)
          fn-id (java.util.UUID/randomUUID)
          arg-schema-id (java.util.UUID/randomUUID)
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum-matching" :returned-type :int})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type :int :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum-matching" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-arg-schema-belongs-to-fn-mismatched-test
  (testing "throws on mismatched schema"
    (let [storage (setup/create-test-storage)
          schema1-id (java.util.UUID/randomUUID)
          schema2-id (java.util.UUID/randomUUID)
          fn-id (java.util.UUID/randomUUID)
          arg-schema-id (java.util.UUID/randomUUID)
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum-mismatch" :returned-type :int})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub-mismatch" :returned-type :int})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema2-id
                                                   :name "x" :type :int :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum-mismatch" :fn-schema-id schema1-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg-schema does not belong to fn's schema"
              (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


;; === validate-no-dependency-cycle! tests ===

(deftest validate-no-dependency-cycle-non-cyclic-test
  (testing "allows non-cyclic reference"
    (let [storage (setup/create-test-storage)
          fn-schema-id (java.util.UUID/randomUUID)
          owner-fn-id (java.util.UUID/randomUUID)
          value-fn-id (java.util.UUID/randomUUID)
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum-noncyclic" :returned-type :int})
          _ (sp/create-entity storage :fn {:id owner-fn-id :name "owner-nc" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id value-fn-id :name "value-nc" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id value-fn-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-dependency-cycle-nil-value-test
  (testing "allows nil value-fn-id"
    (let [storage (setup/create-test-storage)
          fn-schema-id (java.util.UUID/randomUUID)
          owner-fn-id (java.util.UUID/randomUUID)
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum-nil" :returned-type :int})
          _ (sp/create-entity storage :fn {:id owner-fn-id :name "owner-nil" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id nil)))
        (finally
          (sp/close storage))))))


(deftest validate-no-dependency-cycle-throws-test
  (testing "throws when dependency cycle detected"
    (let [storage (setup/create-test-storage)
          fn-schema-id (java.util.UUID/randomUUID)
          fn-a-id (java.util.UUID/randomUUID)
          fn-b-id (java.util.UUID/randomUUID)
          fn-c-id (java.util.UUID/randomUUID)
          arg-schema-id (java.util.UUID/randomUUID)
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test-cycle" :returned-type :int})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type :int :required true})
          _ (sp/create-entity storage :fn {:id fn-a-id :name "fn-a-cycle" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b-id :name "fn-b-cycle" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-c-id :name "fn-c-cycle" :fn-schema-id fn-schema-id})
          ;; Create b -> c reference (b depends on c)
          _ (setup/create-arg-value-with-binding! storage fn-b-id arg-schema-id (str fn-c-id))
          ;; Create c -> a reference (c depends on a)
          _ (setup/create-arg-value-with-binding! storage fn-c-id arg-schema-id (str fn-a-id))]
      (try
        ;; Try to validate a -> b, which would create cycle: a -> b -> c -> a
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage fn-a-id fn-b-id)))
        (finally
          (sp/close storage))))))
