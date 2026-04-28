(ns ^:integration graphden.storage.protocol.generic-graph-test
  "Tests for generic ExecutionGraph resolution via StorageCRUD.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-graph :as gg]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.storage.protocol.test-helpers :as helpers]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a PostgreSQL storage from the current test container. Relies
   on the :each fixture to have cleaned the schema already."
  []
  (let [storage (pg/create-storage (th/get-container-config *container*))]
    (sp/initialize storage (helpers/make-graph-schema))
    storage))


(deftest resolve-simple-fn-test
  (testing "resolves a simple function with no dependencies"
    (let [storage (create-test-storage)
          ;; Create base fn (parent-id=nil)
          base-fn (sp/create-entity storage :fn {:name "add" :parent-ids nil :return-type "int"})
          ;; Create composed fn
          fn-rec (sp/create-entity storage :fn {:name "my-add" :parent-ids [(:id base-fn)] :return-type "int"})
          ;; Create arg with literal value
          _ (sp/create-entity storage :arg {:fn-id (:id fn-rec) :name "x" :type "int" :value 42})
          graph (gg/resolve-execution-graph storage (:id fn-rec))]
      (is (sp/execution-graph? graph))
      (is (= 2 (count (:fns graph)))) ; fn-rec + base-fn
      (is (contains? (:fns graph) (:id fn-rec)))
      (is (contains? (:fns graph) (:id base-fn)))
      (is (= 1 (count (:args graph))))
      (sp/close storage))))


(deftest resolve-fn-chain-test
  (testing "resolves a chain of dependent functions"
    (let [storage (create-test-storage)
          ;; Create base fn
          base-fn (sp/create-entity storage :fn {:name "identity" :parent-ids nil :return-type "int"})
          ;; Create chain of composed functions: fn-a -> fn-b -> fn-c
          fn-c (sp/create-entity storage :fn {:name "fn-c" :parent-ids [(:id base-fn)]})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :parent-ids [(:id base-fn)]})
          fn-a (sp/create-entity storage :fn {:name "fn-a" :parent-ids [(:id base-fn)]})
          ;; fn-c has literal value
          _ (sp/create-entity storage :arg {:fn-id (:id fn-c) :name "x" :type "int" :value 99})
          ;; fn-b references fn-c
          _ (sp/create-entity storage :arg {:fn-id (:id fn-b) :name "x" :ref-id (:id fn-c) :is-fn false})
          ;; fn-a references fn-b
          _ (sp/create-entity storage :arg {:fn-id (:id fn-a) :name "x" :ref-id (:id fn-b) :is-fn false})
          graph (gg/resolve-execution-graph storage (:id fn-a))]
      (is (sp/execution-graph? graph))
      (is (= 4 (count (:fns graph)))) ; fn-a, fn-b, fn-c, base-fn
      (is (contains? (:fns graph) (:id fn-a)))
      (is (contains? (:fns graph) (:id fn-b)))
      (is (contains? (:fns graph) (:id fn-c)))
      (is (contains? (:fns graph) (:id base-fn)))
      (sp/close storage))))


(deftest resolve-fn-no-args-test
  (testing "resolves function with no args"
    (let [storage (create-test-storage)
          ;; Create base fn with no args
          base-fn (sp/create-entity storage :fn {:name "noop" :parent-ids nil :return-type "int"})
          ;; Create composed fn with no args
          fn-rec (sp/create-entity storage :fn {:name "my-noop" :parent-ids [(:id base-fn)]})
          graph (gg/resolve-execution-graph storage (:id fn-rec))]
      (is (sp/execution-graph? graph))
      (is (= 2 (count (:fns graph)))) ; fn-rec + base-fn
      (is (= [] (sp/graph-get-args graph (:id fn-rec))))
      (sp/close storage))))


(deftest resolve-nonexistent-fn-test
  (testing "throws when fn does not exist"
    (let [storage (create-test-storage)]
      (is (thrown? clojure.lang.ExceptionInfo
            (gg/resolve-execution-graph storage (random-uuid))))
      (sp/close storage))))
