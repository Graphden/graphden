(ns graphden.malli-data-schema.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [graphden.data-schema-protocol.interface :as ds]
   [graphden.malli-data-schema.interface :as mds]))


(def example-schema
  "Example schema representing a function definition system.
   Based on the following structure:

   Enum value_kind {null, bool, int, numeric, text, uuid, timestamptz, jsonb, bytes}

   Table fn_schema {id, name, returned_type}
   Table arg_schema {id, fn_schema_id, name, type}
   Table fn {id, name, fn_schema_id}
   Table arg_value {id, owner_fn_id, arg_schema_id, value}

   The value field is a union type - it can be either a reference to another
   function (for composition) or a literal value of any supported type."
  (-> (mds/create-builder)

      ;; Define the value_kind enum
      (ds/add-enum :value-kind
                   [:null :bool :int :numeric :text :uuid :timestamptz :jsonb :bytes])

      ;; fn_schema: defines function signatures
      (ds/add-entity :fn-schema
                     {:name {:type :text :nullable? false :unique? true}
                      :returned-type {:type :enum :enum-name :value-kind :nullable? false}})

      ;; arg_schema: defines function arguments
      (ds/add-entity :arg-schema
                     {:fn-schema-id {:type :ref :ref-entity :fn-schema :nullable? false}
                      :name {:type :text :nullable? false}
                      :type {:type :enum :enum-name :value-kind :nullable? false}})

      ;; fn: actual function instances
      (ds/add-entity :fn
                     {:name {:type :text :nullable? false :unique? true}
                      :fn-schema-id {:type :ref :ref-entity :fn-schema :nullable? false}})

      ;; arg_value: argument values for function instances
      ;; value is a union: either a reference to another fn, or a literal value
      (ds/add-entity :arg-value
                     {:owner-fn-id {:type :ref :ref-entity :fn :nullable? false}
                      :arg-schema-id {:type :ref :ref-entity :arg-schema :nullable? false}
                      :value {:type :union
                              :variants [{:type :ref :ref-entity :fn}
                                         {:type :bool}
                                         {:type :int}
                                         {:type :numeric}
                                         {:type :text}
                                         {:type :uuid}
                                         {:type :timestamptz}
                                         {:type :jsonb}
                                         {:type :bytes}]}})

      (ds/build)))


(deftest entities-test
  (testing "schema contains all expected entities"
    (let [entities (set (ds/entities example-schema))]
      (is (= #{:fn-schema :arg-schema :fn :arg-value} entities)))))


(deftest enums-test
  (testing "schema contains value-kind enum"
    (let [enums (ds/enums example-schema)]
      (is (contains? enums :value-kind))
      (is (= #{:null :bool :int :numeric :text :uuid :timestamptz :jsonb :bytes}
             (:values (get enums :value-kind)))))))


(deftest entity-fields-test
  (testing "fn-schema has expected fields"
    (let [fields (ds/entity-fields example-schema :fn-schema)]
      (is (= :text (get-in fields [:name :type])))
      (is (= false (get-in fields [:name :nullable?])))
      (is (= true (get-in fields [:name :unique?])))
      (is (= :enum (get-in fields [:returned-type :type])))
      (is (= :value-kind (get-in fields [:returned-type :enum-name])))))

  (testing "arg-schema has reference to fn-schema"
    (let [fields (ds/entity-fields example-schema :arg-schema)]
      (is (= :ref (get-in fields [:fn-schema-id :type])))
      (is (= :fn-schema (get-in fields [:fn-schema-id :ref-entity])))))

  (testing "arg-value has union type for value"
    (let [fields (ds/entity-fields example-schema :arg-value)]
      (is (= :ref (get-in fields [:owner-fn-id :type])))
      (is (= false (get-in fields [:owner-fn-id :nullable?])))
      (is (= :union (get-in fields [:value :type])))
      (is (= 9 (count (get-in fields [:value :variants])))))))


(deftest validate-entity-test
  (let [valid-fn-schema-id (random-uuid)
        valid-fn-id (random-uuid)
        valid-arg-schema-id (random-uuid)]

    (testing "valid fn-schema entity"
      (is (nil? (ds/validate-entity example-schema :fn-schema
                                    {:id (random-uuid)
                                     :name "add"
                                     :returned-type :int}))))

    (testing "invalid fn-schema - missing required field"
      (let [result (ds/validate-entity example-schema :fn-schema
                                       {:id (random-uuid)
                                        :returned-type :int})]
        (is (some? result))
        (is (contains? (:errors result) :name))))

    (testing "invalid fn-schema - wrong enum value"
      (let [result (ds/validate-entity example-schema :fn-schema
                                       {:id (random-uuid)
                                        :name "add"
                                        :returned-type :unknown-type})]
        (is (some? result))
        (is (contains? (:errors result) :returned-type))))

    (testing "valid arg-schema with reference"
      (is (nil? (ds/validate-entity example-schema :arg-schema
                                    {:id (random-uuid)
                                     :fn-schema-id valid-fn-schema-id
                                     :name "x"
                                     :type :int}))))

    (testing "valid fn entity"
      (is (nil? (ds/validate-entity example-schema :fn
                                    {:id (random-uuid)
                                     :name "add-two"
                                     :fn-schema-id valid-fn-schema-id}))))

    (testing "valid arg-value with literal int value"
      (is (nil? (ds/validate-entity example-schema :arg-value
                                    {:id (random-uuid)
                                     :owner-fn-id valid-fn-id
                                     :arg-schema-id valid-arg-schema-id
                                     :value 42}))))

    (testing "valid arg-value with literal string value"
      (is (nil? (ds/validate-entity example-schema :arg-value
                                    {:id (random-uuid)
                                     :owner-fn-id valid-fn-id
                                     :arg-schema-id valid-arg-schema-id
                                     :value "hello"}))))

    (testing "valid arg-value with fn reference as value"
      (is (nil? (ds/validate-entity example-schema :arg-value
                                    {:id (random-uuid)
                                     :owner-fn-id valid-fn-id
                                     :arg-schema-id valid-arg-schema-id
                                     :value valid-fn-id}))))

    (testing "invalid arg-value - owner-fn-id is required"
      (let [result (ds/validate-entity example-schema :arg-value
                                       {:id (random-uuid)
                                        :owner-fn-id nil
                                        :arg-schema-id valid-arg-schema-id
                                        :value 42})]
        (is (some? result))
        (is (contains? (:errors result) :owner-fn-id))))

    (testing "unknown entity validation"
      (let [result (ds/validate-entity example-schema :unknown-entity {:id (random-uuid)})]
        (is (some? result))
        (is (contains? (:errors result) :entity))))))


(deftest malli-schema-access-test
  (testing "can access underlying malli schema"
    (let [malli-schema (mds/schema->malli example-schema :fn-schema)]
      (is (some? malli-schema)))))
