(ns graphden.schema.graph.schema-test
  "Tests for graph schema with 2-entity model.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.fields.types :as ft]
    [graphden.schema.graph.schema :as graph]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]))


(def schema
  "Graph schema built with malli implementation."
  (graph/build-schema (mds/create-builder)))


(deftest entities-test
  (testing "schema contains ns, fn, and arg entities"
    (is (= #{:ns :fn :arg}
           (set (ds/entities schema))))))


(deftest enums-test
  (testing "schema contains value-kind enum with null + any + fn + all field types"
    (let [enums (ds/enums schema)]
      (is (contains? enums :value-kind))
      ;; :values is now a map of value->uuid
      ;; Includes: :null (void), :any (polymorphic), :fn (function ref), + all storage types
      (is (= (into #{:null :any :fn} ft/supported-types)
             (set (keys (:values (get enums :value-kind)))))))))


(deftest entity-fields-test
  (testing "fn has expected fields"
    (let [fields (ds/entity-fields schema :fn)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :ref-many (get-in fields [:parent-ids :type])))
      (is (= :fn (get-in fields [:parent-ids :ref-entity])))
      (is (true? (get-in fields [:parent-ids :nullable?])))
      (is (= :enum (get-in fields [:return-type :type])))
      (is (= :text (get-in fields [:impl-hash :type])))))

  (testing "arg has expected fields"
    (let [fields (ds/entity-fields schema :arg)]
      (is (= :ref (get-in fields [:fn-id :type])))
      (is (= :fn (get-in fields [:fn-id :ref-entity])))
      (is (= :text (get-in fields [:name :type])))
      (is (= :enum (get-in fields [:type :type])))
      (is (= :ref (get-in fields [:source-id :type])))
      (is (= :arg (get-in fields [:source-id :ref-entity])))
      (is (true? (get-in fields [:source-id :nullable?])))
      (is (= :jsonb (get-in fields [:value :type])))
      (is (true? (get-in fields [:value :nullable?])))
      (is (= :ref (get-in fields [:ref-id :type])))
      (is (= :fn (get-in fields [:ref-id :ref-entity])))
      (is (true? (get-in fields [:ref-id :nullable?])))
      (is (= :bool (get-in fields [:is-fn :type])))
      (is (= :bool (get-in fields [:required :type]))))))


(deftest validation-test
  (testing "valid base fn (parent-id=nil)"
    (is (nil? (ds/validate-entity schema :fn
                                  {:id (random-uuid)
                                   :name "add"
                                   :parent-ids nil
                                   :return-type :int}))))

  (testing "valid composed fn (parent-id set)"
    (is (nil? (ds/validate-entity schema :fn
                                  {:id (random-uuid)
                                   :name "my-add"
                                   :parent-ids [(random-uuid)]
                                   :return-type :int}))))

  ;; Note: fn.name is nullable (for local fns), so missing name is valid
  ;; Test that fn-id is required for arg entity instead
  (testing "invalid arg - missing required fn-id"
    (let [result (ds/validate-entity schema :arg
                                     {:id (random-uuid)
                                      :name "x"
                                      :type :int})]
      (is (some? result))
      (is (contains? (:errors result) :fn-id))))

  (testing "valid arg with literal value"
    (is (nil? (ds/validate-entity schema :arg
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :name "x"
                                   :type :int
                                   :value 42}))))

  (testing "valid arg with fn reference (execute result)"
    (is (nil? (ds/validate-entity schema :arg
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :name "result"
                                   :ref-id (random-uuid)
                                   :is-fn false}))))

  (testing "valid arg with fn reference (HOF, pass fn as value)"
    (is (nil? (ds/validate-entity schema :arg
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :name "f"
                                   :ref-id (random-uuid)
                                   :is-fn true}))))

  (testing "valid arg with source-id (inheritance)"
    (is (nil? (ds/validate-entity schema :arg
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :name "x"
                                   :source-id (random-uuid)})))))
