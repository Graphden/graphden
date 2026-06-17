(ns ^:integration graphden.schema.traits.schema-test
  "Tests for traits schema with 2-entity model.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-ids=nil for base-fn, parent-ids set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   Traits entities:
   - trait: named trait definition
   - arg-trait: assigns trait to arg"
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
  "Creates storage with graph + traits schema. Cleans the database —
   some tests call this multiple times inside one deftest to exercise
   fresh state between `testing` blocks."
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
      (is (contains? (set (ds/entities schema)) :binding-trait)))))


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
      (is (thrown? clojure.lang.ExceptionInfo
            (sp/create-entity storage :trait {:name "unique-trait"})))
      (sp/close storage))))


(deftest binding-trait-crud-test
  (testing "can assign trait to binding"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "test-fn"
                                     :parent-ids []
                                     :impl-hash "test-hash"})
          slot (sp/create-entity storage :slot
                                 {:name "password"
                                  :type-fn-id (:id base-fn)})
          b-row (sp/create-entity storage :binding
                                  {:fn-id (:id base-fn)
                                   :slot-id (:id slot)
                                   :value "secret123"})
          trait (sp/create-entity storage :trait
                                  {:name "secret"
                                   :description "Should be hidden"})
          binding-trait (sp/create-entity storage :binding-trait
                                          {:binding-id (:id b-row)
                                           :trait-id (:id trait)})]
      (is (uuid? (:id binding-trait)))
      (is (= (:id b-row) (:binding-id binding-trait)))
      (is (= (:id trait) (:trait-id binding-trait)))
      (sp/close storage)))

  (testing "same trait cannot be assigned twice to same binding"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "test-fn"
                                     :parent-ids []
                                     :impl-hash "test-hash"})
          slot (sp/create-entity storage :slot
                                 {:name "x"
                                  :type-fn-id (:id base-fn)})
          b-row (sp/create-entity storage :binding
                                  {:fn-id (:id base-fn)
                                   :slot-id (:id slot)
                                   :value "test"})
          trait (sp/create-entity storage :trait {:name "my-trait"})]
      (sp/create-entity storage :binding-trait
                        {:binding-id (:id b-row)
                         :trait-id (:id trait)})
      (is (thrown? clojure.lang.ExceptionInfo
            (sp/create-entity storage :binding-trait
                              {:binding-id (:id b-row)
                               :trait-id (:id trait)})))
      (sp/close storage))))


(deftest query-traits-test
  (testing "can query traits for a binding"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "test-fn"
                                     :parent-ids []
                                     :impl-hash "test-hash"})
          slot (sp/create-entity storage :slot
                                 {:name "val"
                                  :type-fn-id (:id base-fn)})
          b-row (sp/create-entity storage :binding
                                  {:fn-id (:id base-fn)
                                   :slot-id (:id slot)
                                   :value "myval"})
          trait1 (sp/create-entity storage :trait {:name "trait-a"})
          trait2 (sp/create-entity storage :trait {:name "trait-b"})
          _ (sp/create-entity storage :binding-trait
                              {:binding-id (:id b-row)
                               :trait-id (:id trait1)})
          _ (sp/create-entity storage :binding-trait
                              {:binding-id (:id b-row)
                               :trait-id (:id trait2)})
          binding-traits (sp/query-entities storage :binding-trait
                                            {:binding-id (:id b-row)})]
      (is (= 2 (count binding-traits)))
      (is (= #{(:id trait1) (:id trait2)}
             (set (map :trait-id binding-traits))))
      (sp/close storage))))


(deftest well-known-traits-test
  (testing "well-known traits are defined"
    (is (uuid? vts/merge-protected-trait-uuid))
    (is (= vts/merge-protected-trait-uuid
           (:merge-protected vts/well-known-traits)))))


(deftest trait-entities-test
  (testing "trait-entities set is correct"
    (is (= #{:trait :binding-trait} vts/trait-entities))))
