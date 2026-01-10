(ns graphden.postgres-storage.constraints-test
  "Integration tests for PostgreSQL constraint validation.
   Tests recursive CTEs and complex constraint queries with real database."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.postgres-storage.constraints :as constraints]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as pth]
    [graphden.storage-protocol.test-helpers :as th]))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(defn- create-test-storage
  []
  (pth/clean-database-fast! *container*)
  (pg/create-storage (pth/get-container-config *container*)))


(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each (pth/create-clean-db-fixture #'*container*))


;; === ConstraintHelpers protocol tests ===

(deftest constraint-helpers-get-fn-schema-id-test
  (testing "get-fn-schema-id-for-fn returns correct schema id"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn-instance" :fn-schema-id (:id fn-schema)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          (is (= (:id fn-schema)
                 (sp/get-fn-schema-id-for-fn helpers (:id fn-record)))))
        (finally
          (sp/close storage))))))


(deftest constraint-helpers-get-fn-schema-id-for-arg-schema-test
  (testing "get-fn-schema-id-for-arg-schema returns correct schema id"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "x"
                                            :type "int"
                                            :required true})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          (is (= (:id fn-schema)
                 (sp/get-fn-schema-id-for-arg-schema helpers (:id arg-schema)))))
        (finally
          (sp/close storage))))))


(deftest constraint-helpers-get-parent-fn-id-test
  (testing "get-parent-fn-id returns parent when exists"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              parent-fn (sp/create-entity storage :fn
                                          {:name "parent" :fn-schema-id (:id fn-schema)})
              child-fn (sp/create-entity storage :fn
                                         {:name "child"
                                          :fn-schema-id (:id fn-schema)
                                          :parent-fn-id (:id parent-fn)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          (is (= (:id parent-fn)
                 (sp/get-parent-fn-id helpers (:id child-fn))))
          (is (nil? (sp/get-parent-fn-id helpers (:id parent-fn)))))
        (finally
          (sp/close storage))))))


;; === collect-parent-chain with recursive CTE tests ===

(deftest collect-parent-chain-empty-test
  (testing "returns empty set for function with no parent"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              fn-record (sp/create-entity storage :fn
                                          {:name "root" :fn-schema-id (:id fn-schema)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          (is (= #{} (sp/collect-parent-chain helpers (:id fn-record)))))
        (finally
          (sp/close storage))))))


(deftest collect-parent-chain-single-parent-test
  (testing "returns set with single parent"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              parent (sp/create-entity storage :fn
                                       {:name "parent" :fn-schema-id (:id fn-schema)})
              child (sp/create-entity storage :fn
                                      {:name "child"
                                       :fn-schema-id (:id fn-schema)
                                       :parent-fn-id (:id parent)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          (is (= #{(:id parent)}
                 (sp/collect-parent-chain helpers (:id child)))))
        (finally
          (sp/close storage))))))


(deftest collect-parent-chain-deep-hierarchy-test
  (testing "collects entire ancestor chain using recursive CTE"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              ;; Create 5-level deep hierarchy
              root (sp/create-entity storage :fn
                                     {:name "root" :fn-schema-id (:id fn-schema)})
              level1 (sp/create-entity storage :fn
                                       {:name "level1"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id root)})
              level2 (sp/create-entity storage :fn
                                       {:name "level2"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id level1)})
              level3 (sp/create-entity storage :fn
                                       {:name "level3"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id level2)})
              level4 (sp/create-entity storage :fn
                                       {:name "level4"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id level3)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          ;; From level4, should see all ancestors
          (is (= #{(:id root) (:id level1) (:id level2) (:id level3)}
                 (sp/collect-parent-chain helpers (:id level4))))
          ;; From level2, should see root and level1
          (is (= #{(:id root) (:id level1)}
                 (sp/collect-parent-chain helpers (:id level2)))))
        (finally
          (sp/close storage))))))


;; === collect-arg-schema-ids-in-chain tests ===

(deftest collect-arg-schema-ids-in-chain-test
  (testing "collects arg-schema-ids defined in parent chain"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "x"
                                            :type "int"
                                            :required true})
              parent (sp/create-entity storage :fn
                                       {:name "parent" :fn-schema-id (:id fn-schema)})
              ;; Define arg-value in parent
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id parent)
                                   :arg-schema-id (:id arg-schema)
                                   :value 42})
              child (sp/create-entity storage :fn
                                      {:name "child"
                                       :fn-schema-id (:id fn-schema)
                                       :parent-fn-id (:id parent)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          ;; Child should see arg-schema defined in parent
          (is (= #{(:id arg-schema)}
                 (sp/collect-arg-schema-ids-in-chain helpers (:id child))))
          ;; Parent should see empty (no grandparent)
          (is (= #{}
                 (sp/collect-arg-schema-ids-in-chain helpers (:id parent)))))
        (finally
          (sp/close storage))))))


;; === collect-dependency-chain tests ===

(deftest collect-dependency-chain-no-deps-test
  (testing "returns only owner function when no dependencies"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              fn-record (sp/create-entity storage :fn
                                          {:name "standalone" :fn-schema-id (:id fn-schema)})
              ds (:pool storage)
              helpers (constraints/create-helpers ds)]
          (is (= #{(:id fn-record)}
                 (sp/collect-dependency-chain helpers (:id fn-record)))))
        (finally
          (sp/close storage))))))


;; === Constraint validation integration tests ===

(deftest validate-parent-same-schema-success-test
  (testing "allows parent with same schema"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              parent (sp/create-entity storage :fn
                                       {:name "parent" :fn-schema-id (:id fn-schema)})
              child (sp/create-entity storage :fn
                                      {:name "child" :fn-schema-id (:id fn-schema)})
              ds (:pool storage)]
          ;; Should not throw
          (is (nil? (constraints/validate-parent-same-schema! ds (:id child) (:id parent)))))
        (finally
          (sp/close storage))))))


(deftest validate-parent-same-schema-violation-test
  (testing "throws on parent with different schema"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [schema1 (sp/create-entity storage :fn-schema
                                        {:name "fn1" :returned-type "int"})
              schema2 (sp/create-entity storage :fn-schema
                                        {:name "fn2" :returned-type "float"})
              parent (sp/create-entity storage :fn
                                       {:name "parent" :fn-schema-id (:id schema1)})
              child (sp/create-entity storage :fn
                                      {:name "child" :fn-schema-id (:id schema2)})
              ds (:pool storage)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"different fn-schema-id"
                (constraints/validate-parent-same-schema! ds (:id child) (:id parent)))))
        (finally
          (sp/close storage))))))


(deftest validate-no-inheritance-cycle-success-test
  (testing "allows valid parent assignment"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              fn-a (sp/create-entity storage :fn
                                     {:name "a" :fn-schema-id (:id fn-schema)})
              fn-b (sp/create-entity storage :fn
                                     {:name "b" :fn-schema-id (:id fn-schema)})
              ds (:pool storage)]
          ;; a -> b is valid
          (is (nil? (constraints/validate-no-inheritance-cycle! ds (:id fn-a) (:id fn-b)))))
        (finally
          (sp/close storage))))))


(deftest validate-no-inheritance-cycle-violation-test
  (testing "throws when setting parent would create cycle"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              ;; Create chain: a -> b -> c
              fn-a (sp/create-entity storage :fn
                                     {:name "a" :fn-schema-id (:id fn-schema)})
              fn-b (sp/create-entity storage :fn
                                     {:name "b"
                                      :fn-schema-id (:id fn-schema)
                                      :parent-fn-id (:id fn-a)})
              fn-c (sp/create-entity storage :fn
                                     {:name "c"
                                      :fn-schema-id (:id fn-schema)
                                      :parent-fn-id (:id fn-b)})
              ds (:pool storage)]
          ;; Trying to make a's parent be c would create cycle: c -> b -> a -> c
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"create inheritance cycle"
                (constraints/validate-no-inheritance-cycle! ds (:id fn-a) (:id fn-c)))))
        (finally
          (sp/close storage))))))


(deftest validate-no-arg-override-success-test
  (testing "allows defining new arg not in parent chain"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "x"
                                            :type "int"
                                            :required true})
              parent (sp/create-entity storage :fn
                                       {:name "parent" :fn-schema-id (:id fn-schema)})
              child (sp/create-entity storage :fn
                                      {:name "child"
                                       :fn-schema-id (:id fn-schema)
                                       :parent-fn-id (:id parent)})
              ds (:pool storage)]
          ;; Parent doesn't define x, so child can
          (is (nil? (constraints/validate-no-arg-override! ds (:id child) (:id arg-schema)))))
        (finally
          (sp/close storage))))))


(deftest validate-no-arg-override-violation-test
  (testing "throws when arg already defined in parent chain"
    (let [storage (create-test-storage)
          schema (th/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-fn" :returned-type "int"})
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "x"
                                            :type "int"
                                            :required true})
              parent (sp/create-entity storage :fn
                                       {:name "parent" :fn-schema-id (:id fn-schema)})
              ;; Parent defines x
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id parent)
                                   :arg-schema-id (:id arg-schema)
                                   :value 42})
              child (sp/create-entity storage :fn
                                      {:name "child"
                                       :fn-schema-id (:id fn-schema)
                                       :parent-fn-id (:id parent)})
              ds (:pool storage)]
          ;; Child can't override x
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"already defined in parent"
                (constraints/validate-no-arg-override! ds (:id child) (:id arg-schema)))))
        (finally
          (sp/close storage))))))
