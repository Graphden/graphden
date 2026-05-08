(ns graphden.schema.malli.basic-test
  "Basic schema structure tests for malli-data-schema.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.fields.types :as ft]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [example-schema]]
    [graphden.schema.malli.types :as mtypes]
    [graphden.schema.protocol.protocol :as ds]))


(deftest entities-test
  (testing "schema contains only fn and arg entities (2-entity model)"
    (let [entities (set (ds/entities example-schema))]
      (is (= #{:fn :arg} entities)))))


(deftest enums-test
  (testing "schema contains value-kind enum with all types including :any and :fn"
    (let [enums (ds/enums example-schema)]
      (is (contains? enums :value-kind))
      ;; :values is now a map of value->uuid
      ;; Includes :any (polymorphic) and :fn (function ref) for HOF support
      (is (= #{:null :bool :int :numeric :text :uuid :timestamptz :jsonb :bytes :any :fn}
             (set (keys (:values (get enums :value-kind)))))))))


(deftest entity-fields-test
  (testing "fn has expected fields for 2-entity schema"
    (let [fields (ds/entity-fields example-schema :fn)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :ref-many (get-in fields [:parent-ids :type])))
      (is (= :fn (get-in fields [:parent-ids :ref-entity])))
      (is (true? (get-in fields [:parent-ids :nullable?])))
      (is (= :enum (get-in fields [:return-type :type])))
      (is (= :value-kind (get-in fields [:return-type :enum-name])))
      (is (= :text (get-in fields [:impl-hash :type])))))

  (testing "arg has expected fields for 2-entity schema"
    (let [fields (ds/entity-fields example-schema :arg)]
      ;; fn-id is required ref to fn
      (is (= :ref (get-in fields [:fn-id :type])))
      (is (= :fn (get-in fields [:fn-id :ref-entity])))
      ;; name is required text
      (is (= :text (get-in fields [:name :type])))
      ;; type is nullable enum
      (is (= :enum (get-in fields [:type :type])))
      (is (= :value-kind (get-in fields [:type :enum-name])))
      (is (true? (get-in fields [:type :nullable?])))
      ;; source-id is nullable ref to arg (for inheritance)
      (is (= :ref (get-in fields [:source-id :type])))
      (is (= :arg (get-in fields [:source-id :ref-entity])))
      (is (true? (get-in fields [:source-id :nullable?])))
      ;; value is nullable jsonb for literals
      (is (= :jsonb (get-in fields [:value :type])))
      (is (true? (get-in fields [:value :nullable?])))
      ;; ref-id is nullable ref to fn (for fn references)
      (is (= :ref (get-in fields [:ref-id :type])))
      (is (= :fn (get-in fields [:ref-id :ref-entity])))
      (is (true? (get-in fields [:ref-id :nullable?])))
      ;; is-fn was retired in #15b — type=:fn IS the HOF marker now.
      (is (not (contains? fields :is-fn)))
      ;; required is nullable bool
      (is (= :bool (get-in fields [:required :type])))
      (is (true? (get-in fields [:required :nullable?])))))

  (testing "constraints are accessible"
    (is (= [{:type :unique :fields [:name]}]
           (ds/entity-constraints example-schema :fn)))))


(deftest malli-schema-access-test
  (testing "can access underlying malli schema"
    (let [malli-schema (mds/schema->malli example-schema :fn)]
      (is (some? malli-schema)))))


(deftest type-mapping-completeness-test
  (testing "malli-type-mapping covers all supported field types"
    (is (= ft/supported-types (set (keys mtypes/malli-type-mapping))))))
