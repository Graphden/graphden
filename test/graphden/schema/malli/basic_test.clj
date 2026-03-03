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
      (is (= #{:fn-schema :arg-schema :fn :arg-value} entities)))))


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

  (testing "arg-value has union type for value"
    (let [fields (ds/entity-fields example-schema :arg-value)]
      (is (= :ref (get-in fields [:owner-fn-id :type])))
      (is (= :union (get-in fields [:value :type])))
      (is (= 9 (count (get-in fields [:value :variants]))))))

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
