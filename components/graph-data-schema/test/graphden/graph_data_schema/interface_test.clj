(ns graphden.graph-data-schema.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.field-types.interface :as ft]
    [graphden.graph-data-schema.interface :as graph]
    [graphden.malli-data-schema.interface :as mds]))


(def schema
  "Graph schema built with malli implementation."
  (graph/build-schema (mds/create-builder)))


(deftest entities-test
  (testing "schema contains all expected entities"
    (is (= #{:fn-schema :arg-schema :fn :arg-value}
           (set (ds/entities schema))))))


(deftest enums-test
  (testing "schema contains value-kind enum with null + all field types"
    (let [enums (ds/enums schema)]
      (is (contains? enums :value-kind))
      (is (= (conj ft/supported-types :null)
             (:values (get enums :value-kind)))))))


(deftest entity-fields-test
  (testing "fn-schema has expected fields"
    (let [fields (ds/entity-fields schema :fn-schema)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :enum (get-in fields [:returned-type :type])))))

  (testing "arg-value has union type for value"
    (let [fields (ds/entity-fields schema :arg-value)]
      (is (= :union (get-in fields [:value :type])))
      (is (= (inc (count ft/supported-types))  ; 1 ref + all literal types
             (count (get-in fields [:value :variants])))))))


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

  (testing "valid arg-value with literal int"
    (is (nil? (ds/validate-entity schema :arg-value
                                  {:id (random-uuid)
                                   :owner-fn-id (random-uuid)
                                   :arg-schema-id (random-uuid)
                                   :value 42}))))

  (testing "valid arg-value with fn reference"
    (is (nil? (ds/validate-entity schema :arg-value
                                  {:id (random-uuid)
                                   :owner-fn-id (random-uuid)
                                   :arg-schema-id (random-uuid)
                                   :value (random-uuid)})))))
