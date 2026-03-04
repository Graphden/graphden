(ns graphden.schema.malli.basic-test
  "Basic schema structure tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.fields.types :as ft]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [example-schema]]
    [graphden.schema.protocol.protocol :as ds]))


(deftest entities-test
  (testing "schema contains all expected entities"
    (let [entities (set (ds/entities example-schema))]
      (is (= #{:fn-schema :arg-schema :fn :fn-usage :arg-value} entities)))))


(deftest enums-test
  (testing "schema contains value-kind enum"
    (let [enums (ds/enums example-schema)]
      (is (contains? enums :value-kind))
      ;; :values is now a map of value->uuid
      (is (= #{:null :bool :int :numeric :text :uuid :timestamptz :jsonb :bytes}
             (set (keys (:values (get enums :value-kind)))))))))


(deftest entity-fields-test
  (testing "fn-schema has expected fields"
    (let [fields (ds/entity-fields example-schema :fn-schema)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :enum (get-in fields [:returned-type :type])))
      (is (= :value-kind (get-in fields [:returned-type :enum-name])))))

  (testing "arg-schema has reference to fn-schema"
    (let [fields (ds/entity-fields example-schema :arg-schema)]
      (is (= :ref (get-in fields [:fn-schema-id :type])))
      (is (= :fn-schema (get-in fields [:fn-schema-id :ref-entity])))))

  (testing "arg-value has nullable FK fields for references"
    (let [fields (ds/entity-fields example-schema :arg-value)]
      ;; arg-schema-id is required FK
      (is (= :ref (get-in fields [:arg-schema-id :type])))
      (is (= :arg-schema (get-in fields [:arg-schema-id :ref-entity])))
      ;; value is nullable jsonb for literals
      (is (= :jsonb (get-in fields [:value :type])))
      (is (true? (get-in fields [:value :nullable?])))
      ;; fn-usage-id is nullable FK for fn-usage references
      (is (= :ref (get-in fields [:fn-usage-id :type])))
      (is (= :fn-usage (get-in fields [:fn-usage-id :ref-entity])))
      (is (true? (get-in fields [:fn-usage-id :nullable?])))))

  (testing "constraints are accessible"
    (is (= [{:type :unique :fields [:name]}]
           (ds/entity-constraints example-schema :fn-schema)))
    (is (= [{:type :unique :fields [:name]}]
           (ds/entity-constraints example-schema :fn)))))


(deftest malli-schema-access-test
  (testing "can access underlying malli schema"
    (let [malli-schema (mds/schema->malli example-schema :fn-schema)]
      (is (some? malli-schema)))))


(deftest type-mapping-completeness-test
  (testing "malli-type-mapping covers all supported field types"
    (is (= ft/supported-types (set (keys mds/malli-type-mapping))))))
