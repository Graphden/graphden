(ns graphden.schema.graph.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.fields.interface :as ft]
    [graphden.schema.graph.interface :as graph]
    [graphden.schema.malli.interface :as mds]
    [graphden.schema.protocol.interface :as ds]))


(def schema
  "Graph schema built with malli implementation."
  (graph/build-schema (mds/create-builder)))


(deftest entities-test
  (testing "schema contains all expected entities"
    (is (= #{:fn-schema :arg-schema :fn :arg-value :fn-arg :fn-usage}
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
  (testing "fn-schema has expected fields"
    (let [fields (ds/entity-fields schema :fn-schema)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :enum (get-in fields [:returned-type :type])))))

  (testing "arg-schema has expected fields including required"
    (let [fields (ds/entity-fields schema :arg-schema)]
      (is (= :ref (get-in fields [:fn-schema-id :type])))
      (is (= :text (get-in fields [:name :type])))
      (is (= :enum (get-in fields [:type :type])))
      (is (= :bool (get-in fields [:required :type])))))

  (testing "fn has expected fields"
    (let [fields (ds/entity-fields schema :fn)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :ref (get-in fields [:fn-schema-id :type])))))

  (testing "arg-value has union type for value"
    (let [fields (ds/entity-fields schema :arg-value)]
      (is (= :union (get-in fields [:value :type])))
      ;; Variants: 1 ref (fn-usage) + :any + :fn + all literal types = 3 + count(supported-types)
      (is (= (+ 3 (count ft/supported-types))
             (count (get-in fields [:value :variants]))))))

  (testing "fn-usage has expected fields"
    (let [fields (ds/entity-fields schema :fn-usage)]
      (is (= :ref (get-in fields [:fn-id :type])))
      (is (= :fn (get-in fields [:fn-id :ref-entity])))))

  (testing "fn-arg has expected fields"
    (let [fields (ds/entity-fields schema :fn-arg)]
      (is (= :ref (get-in fields [:fn-id :type])))
      (is (= :fn (get-in fields [:fn-id :ref-entity])))
      (is (= :ref (get-in fields [:arg-schema-id :type])))
      (is (= :arg-schema (get-in fields [:arg-schema-id :ref-entity])))
      (is (= :ref (get-in fields [:arg-value-id :type])))
      (is (= :arg-value (get-in fields [:arg-value-id :ref-entity]))))))


(deftest validation-test
  (testing "valid fn-schema entity"
    (is (nil? (ds/validate-entity schema :fn-schema
                                  {:id (random-uuid)
                                   :name "add"
                                   :returned-type :int}))))

  (testing "invalid fn-schema - missing required field"
    (let [result (ds/validate-entity schema :fn-schema
                                     {:id (random-uuid)
                                      :returned-type :int})]
      (is (some? result))
      (is (contains? (:errors result) :name))))

  (testing "valid arg-value with literal int (no owner)"
    (is (nil? (ds/validate-entity schema :arg-value
                                  {:id (random-uuid)
                                   :arg-schema-id (random-uuid)
                                   :value 42}))))

  (testing "valid arg-value with fn reference (no owner)"
    (is (nil? (ds/validate-entity schema :arg-value
                                  {:id (random-uuid)
                                   :arg-schema-id (random-uuid)
                                   :value (random-uuid)}))))

  (testing "valid fn-arg binding"
    (is (nil? (ds/validate-entity schema :fn-arg
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :arg-schema-id (random-uuid)
                                   :arg-value-id (random-uuid)}))))

  (testing "valid fn"
    (is (nil? (ds/validate-entity schema :fn
                                  {:id (random-uuid)
                                   :name "my-fn"
                                   :fn-schema-id (random-uuid)}))))

  (testing "valid arg-schema with required true"
    (is (nil? (ds/validate-entity schema :arg-schema
                                  {:id (random-uuid)
                                   :fn-schema-id (random-uuid)
                                   :name "x"
                                   :type :int
                                   :required true
                                   :first-class false}))))

  (testing "valid arg-schema with required false"
    (is (nil? (ds/validate-entity schema :arg-schema
                                  {:id (random-uuid)
                                   :fn-schema-id (random-uuid)
                                   :name "optional-arg"
                                   :type :text
                                   :required false
                                   :first-class false})))))
