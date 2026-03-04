(ns ^:integration graphden.schema.traits.schema-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates storage with graph + traits schema.
   Cleans the database before creating storage to ensure test isolation."
  []
  (th/clean-database-fast! *container*)
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))
        storage (pg/create-storage (th/get-container-config *container*))]
    (sp/initialize-with-cleanup! storage schema)))


(deftest schema-builds-test
  (testing "schema with traits builds successfully"
    (let [schema (-> (mds/create-builder)
                     (gds/extend-builder)
                     (vts/extend-builder)
                     (ds/build))]
      (is (some? schema))
      (is (contains? (set (ds/entities schema)) :trait))
      (is (contains? (set (ds/entities schema)) :value-trait)))))


(deftest trait-crud-test
  (testing "can create and read traits"
    (let [storage (create-test-storage)
          trait (sp/create-entity storage :trait
                                  {:name "test-trait"
                                   :description "A test trait"})]
      (is (uuid? (:id trait)))
      (is (= "test-trait" (:name trait)))
      (is (= "A test trait" (:description trait)))
      (sp/close storage)))

  (testing "trait name is unique"
    (let [storage (create-test-storage)]
      (sp/create-entity storage :trait {:name "unique-trait"})
      (is (thrown? Exception
            (sp/create-entity storage :trait {:name "unique-trait"})))
      (sp/close storage))))


(deftest value-trait-crud-test
  (testing "can assign trait to arg-value"
    (let [storage (create-test-storage)
          ;; Create fn-schema and arg-schema first
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "test-schema"
                                       :returned-type :text})
          arg-schema (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "password"
                                        :type :text
                                        :required true :first-class false})
          ;; Create arg-value
          arg-value (sp/create-entity storage :arg-value
                                      {:arg-schema-id (:id arg-schema)
                                       :value "secret123"})
          ;; Create trait and assign
          trait (sp/create-entity storage :trait
                                  {:name "secret"
                                   :description "Should be hidden"})
          value-trait (sp/create-entity storage :value-trait
                                        {:arg-value-id (:id arg-value)
                                         :trait-id (:id trait)})]
      (is (uuid? (:id value-trait)))
      (is (= (:id arg-value) (:arg-value-id value-trait)))
      (is (= (:id trait) (:trait-id value-trait)))
      (sp/close storage)))

  (testing "same trait cannot be assigned twice to same value"
    (let [storage (create-test-storage)
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "test-schema"
                                       :returned-type :text})
          arg-schema (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "x"
                                        :type :text
                                        :required true :first-class false})
          arg-value (sp/create-entity storage :arg-value
                                      {:arg-schema-id (:id arg-schema)
                                       :value "test"})
          trait (sp/create-entity storage :trait {:name "my-trait"})]
      (sp/create-entity storage :value-trait
                        {:arg-value-id (:id arg-value)
                         :trait-id (:id trait)})
      (is (thrown? Exception
            (sp/create-entity storage :value-trait
                              {:arg-value-id (:id arg-value)
                               :trait-id (:id trait)})))
      (sp/close storage))))


(deftest query-traits-test
  (testing "can query traits for a value"
    (let [storage (create-test-storage)
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "test-schema"
                                       :returned-type :text})
          arg-schema (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "val"
                                        :type :text
                                        :required true :first-class false})
          arg-value (sp/create-entity storage :arg-value
                                      {:arg-schema-id (:id arg-schema)
                                       :value "myval"})
          trait1 (sp/create-entity storage :trait {:name "trait-a"})
          trait2 (sp/create-entity storage :trait {:name "trait-b"})
          _ (sp/create-entity storage :value-trait
                              {:arg-value-id (:id arg-value)
                               :trait-id (:id trait1)})
          _ (sp/create-entity storage :value-trait
                              {:arg-value-id (:id arg-value)
                               :trait-id (:id trait2)})
          value-traits (sp/query-entities storage :value-trait
                                          {:arg-value-id (:id arg-value)})]
      (is (= 2 (count value-traits)))
      (is (= #{(:id trait1) (:id trait2)}
             (set (map :trait-id value-traits))))
      (sp/close storage))))


(deftest well-known-traits-test
  (testing "well-known traits are defined"
    (is (uuid? vts/merge-protected-trait-uuid))
    (is (= vts/merge-protected-trait-uuid
           (:merge-protected vts/well-known-traits)))))


(deftest trait-entities-test
  (testing "trait-entities set is correct"
    (is (= #{:trait :value-trait} vts/trait-entities))))
