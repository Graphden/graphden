(ns graphden.malli-data-schema.core-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.field-types.interface :as ft]
    [graphden.malli-data-schema.core :as core]
    [graphden.malli-data-schema.interface :as mds]))


;; Helper to generate UUIDs for tests
(defn- uuid
  []
  (random-uuid))


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
      (ds/add-enum :value-kind (uuid)
                   [{:uuid (uuid) :value :null}
                    {:uuid (uuid) :value :bool}
                    {:uuid (uuid) :value :int}
                    {:uuid (uuid) :value :numeric}
                    {:uuid (uuid) :value :text}
                    {:uuid (uuid) :value :uuid}
                    {:uuid (uuid) :value :timestamptz}
                    {:uuid (uuid) :value :jsonb}
                    {:uuid (uuid) :value :bytes}])

      ;; fn_schema: defines function signatures
      (ds/add-entity :fn-schema (uuid)
                     {:name {:uuid (uuid) :type :text}
                      :returned-type {:uuid (uuid) :type :enum :enum-name :value-kind}})
      (ds/add-constraint :fn-schema {:type :unique :fields [:name]})

      ;; arg_schema: defines function arguments
      (ds/add-entity :arg-schema (uuid)
                     {:fn-schema-id {:uuid (uuid) :type :ref :ref-entity :fn-schema}
                      :name {:uuid (uuid) :type :text}
                      :type {:uuid (uuid) :type :enum :enum-name :value-kind}})

      ;; fn: actual function instances
      (ds/add-entity :fn (uuid)
                     {:name {:uuid (uuid) :type :text}
                      :fn-schema-id {:uuid (uuid) :type :ref :ref-entity :fn-schema}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg_value: argument values for function instances
      ;; value is a union: either a reference to another fn, or a literal value
      (ds/add-entity :arg-value (uuid)
                     {:owner-fn-id {:uuid (uuid) :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid (uuid) :type :ref :ref-entity :arg-schema}
                      :value {:uuid (uuid) :type :union
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


(deftest nullable-fields-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :with-nullable (uuid)
                                  {:required-field {:uuid (uuid) :type :text :nullable? false}
                                   :optional-field {:uuid (uuid) :type :text :nullable? true}})
                   (ds/build))]

    (testing "valid entity with nullable field as nil"
      (is (nil? (ds/validate-entity schema :with-nullable
                                    {:id (random-uuid)
                                     :required-field "value"
                                     :optional-field nil}))))

    (testing "valid entity with nullable field having value"
      (is (nil? (ds/validate-entity schema :with-nullable
                                    {:id (random-uuid)
                                     :required-field "value"
                                     :optional-field "optional"}))))

    (testing "invalid - required field cannot be nil"
      (let [result (ds/validate-entity schema :with-nullable
                                       {:id (random-uuid)
                                        :required-field nil
                                        :optional-field "value"})]
        (is (some? result))
        (is (contains? (:errors result) :required-field))))))


(deftest malli-schema-access-test
  (testing "can access underlying malli schema"
    (let [malli-schema (mds/schema->malli example-schema :fn-schema)]
      (is (some? malli-schema)))))


(deftest type-mapping-completeness-test
  (testing "malli-type-mapping covers all supported field types"
    (is (= ft/supported-types (set (keys core/malli-type-mapping))))))


(deftest validation-at-build-time-test
  (testing "unknown enum reference in union variant throws at build"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown enum"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :enum :enum-name :undefined}]}})
              (ds/build)))))

  (testing "unknown entity reference in union variant throws at build"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown entity"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :ref :ref-entity :undefined}]}})
              (ds/build)))))

  (testing "self-referencing entity is allowed"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :node (uuid)
                                    {:parent-id {:uuid (uuid) :type :ref
                                                 :ref-entity :node
                                                 :nullable? true}})
                     (ds/build))]
      (is (some? schema))))

  (testing "circular references between entities are allowed"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :a (uuid)
                                    {:b-id {:uuid (uuid) :type :ref :ref-entity :b :nullable? true}})
                     (ds/add-entity :b (uuid)
                                    {:a-id {:uuid (uuid) :type :ref :ref-entity :a :nullable? true}})
                     (ds/build))]
      (is (some? schema)))))


;; === Error validation tests for coverage ===

(deftest validate-entity-name-test
  (testing "non-keyword entity name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Entity name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-entity "string-name" (uuid) {:field {:uuid (uuid) :type :text}})
              (ds/build)))))

  (testing "nil entity name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Entity name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-entity nil (uuid) {:field {:uuid (uuid) :type :text}})
              (ds/build))))))


(deftest validate-field-names-test
  (testing "non-keyword field name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {"string-field" {:uuid (uuid) :type :text}})
              (ds/build)))))

  (testing "reserved :id field name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field name :id is reserved"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:id {:uuid (uuid) :type :text}})
              (ds/build))))))


(deftest validate-field-spec-test
  (testing "missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field spec missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid)}})
              (ds/build)))))

  (testing "unknown field type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown field type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :unknown-type}})
              (ds/build)))))

  (testing "non-boolean :nullable? throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field :nullable\? must be a boolean"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :text :nullable? "yes"}})
              (ds/build)))))

  (testing ":ref type missing :ref-entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :ref requires :ref-entity"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :ref}})
              (ds/build)))))

  (testing ":ref type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :ref has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :ref
                                                   :ref-entity :target
                                                   :extra-attr "value"}})
              (ds/build)))))

  (testing ":enum type missing :enum-name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :enum requires :enum-name"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :enum}})
              (ds/build)))))

  (testing ":enum type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :enum has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :enum
                                                   :enum-name :status
                                                   :extra-attr "value"}})
              (ds/build)))))

  (testing ":union with non-vector :variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :union requires :variants vector"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants {:type :text}}})
              (ds/build)))))

  (testing ":union variant with :nullable? throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variant cannot have :nullable\? attribute"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :text :nullable? true}]}})
              (ds/build)))))

  (testing ":union with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :union has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :text}]
                                                   :extra-attr "value"}})
              (ds/build)))))

  (testing "base type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :text
                                                   :extra-attr "value"}})
              (ds/build))))))


(deftest validate-uuid-test
  (testing "non-UUID entity uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-entity :item "not-a-uuid" {:field {:uuid (uuid) :type :text}})
              (ds/build)))))

  (testing "non-UUID field uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid "not-a-uuid" :type :text}})
              (ds/build)))))

  (testing "non-UUID enum uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-enum :status "not-a-uuid" [{:uuid (uuid) :value :active}])
              (ds/build)))))

  (testing "non-UUID enum value uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid "not-a-uuid" :value :active}])
              (ds/build))))))


(deftest uuid-uniqueness-test
  (testing "duplicate entity UUIDs throw"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item1 same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/add-entity :item2 same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/build))))))

  (testing "duplicate field UUIDs throw"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:field1 {:uuid same-uuid :type :text}
                                             :field2 {:uuid same-uuid :type :text}})
                (ds/build))))))

  (testing "field UUID same as entity UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item same-uuid {:field {:uuid same-uuid :type :text}})
                (ds/build))))))

  (testing "enum value UUID same as entity UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}])
                (ds/build))))))

  (testing "enum value UUID same as field UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:field {:uuid same-uuid :type :text}})
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}])
                (ds/build))))))

  (testing "enum UUID same as entity UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/add-enum :status same-uuid [{:uuid (uuid) :value :active}])
                (ds/build))))))

  (testing "cross-enum value UUID collision throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}])
                (ds/add-enum :role (uuid) [{:uuid same-uuid :value :admin}])
                (ds/build)))))))


(deftest constraint-validation-test
  (testing "constraint on unknown entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unknown entity"
          (-> (mds/create-builder)
              (ds/add-constraint :unknown-entity {:type :unique :fields [:name]})
              (ds/build)))))

  (testing "constraint on unknown field throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unknown field"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields [:unknown-field]})
              (ds/build)))))

  (testing "constraint with unknown type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown constraint type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unknown-type :fields [:name]})
              (ds/build)))))

  (testing "constraint with empty fields throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint :fields cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields []})
              (ds/build)))))

  (testing "constraint with non-vector fields throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint :fields must be a vector"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields :name})
              (ds/build))))))


(deftest enum-validation-test
  (testing "non-keyword enum name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-enum "string-name" (uuid) [{:uuid (uuid) :value :active}])
              (ds/build)))))

  (testing "duplicate enum names throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate enum name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :pending}])
              (ds/build)))))

  (testing "empty enum values throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum values cannot be empty"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [])
              (ds/build)))))

  (testing "non-keyword enum value throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum value :value must be a keyword"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value "string-value"}])
              (ds/build)))))

  (testing "duplicate enum value keywords throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum has duplicate values"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}
                                           {:uuid (uuid) :value :active}])
              (ds/build)))))

  (testing "enum value missing :value throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum value missing :value"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid)}])
              (ds/build)))))

  (testing "enum value missing :uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum value missing :uuid"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:value :active}])
              (ds/build)))))

  (testing "invalid identifier name in enum value throws"
    ;; Enum value names must be valid SQL identifiers after kebab->snake conversion
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :123-invalid}])
              (ds/build))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :-starts-with-hyphen}])
              (ds/build))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :ends-with-hyphen-}])
              (ds/build)))))

  (testing "valid identifier names in enum values work"
    (is (some?
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}
                                           {:uuid (uuid) :value :in-progress}
                                           {:uuid (uuid) :value :completed123}])
              (ds/build))))))


(deftest union-variants-validation-test
  (testing "empty union variants throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variants cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union :variants []}})
              (ds/build)))))

  (testing "duplicate ref variants throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union has duplicate variants"
          (-> (mds/create-builder)
              (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :ref :ref-entity :target}
                                                              {:type :ref :ref-entity :target}]}})
              (ds/build)))))

  (testing "duplicate base type variants throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union has duplicate variants"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :text}
                                                              {:type :text}]}})
              (ds/build))))))


(deftest jsonb-validation-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :doc (uuid) {:data {:uuid (uuid) :type :jsonb}})
                   (ds/build))]

    (testing "jsonb accepts nil"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data nil}))))

    (testing "jsonb accepts primitives"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data true})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data 42})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data 3.14})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data "text"}))))

    (testing "jsonb accepts arrays"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data [1 2 3]})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data ["a" "b"]}))))

    (testing "jsonb accepts objects with string keys"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data {"key" "value"}})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid)
                                                 :data {"nested" {"deep" [1 2 3]}}}))))

    (testing "jsonb rejects keyword keys in maps"
      (let [result (ds/validate-entity schema :doc {:id (random-uuid) :data {:key "value"}})]
        (is (some? result))))

    (testing "jsonb rejects functions"
      (let [result (ds/validate-entity schema :doc {:id (random-uuid) :data inc})]
        (is (some? result))))))


(deftest enum-uuid-test
  (testing "enum-uuid returns correct UUID"
    (let [status-uuid (uuid)
          schema (-> (mds/create-builder)
                     (ds/add-enum :status status-uuid [{:uuid (uuid) :value :active}])
                     (ds/build))]
      (is (= status-uuid (ds/enum-uuid schema :status)))))

  (testing "enum-uuid returns nil for unknown enum"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
                     (ds/build))]
      (is (nil? (ds/enum-uuid schema :unknown))))))


(deftest bytes-field-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :binary (uuid) {:data {:uuid (uuid) :type :bytes}})
                   (ds/build))]

    (testing "bytes accepts byte arrays"
      (is (nil? (ds/validate-entity schema :binary
                                    {:id (random-uuid)
                                     :data (byte-array [1 2 3 4])}))))

    (testing "bytes rejects strings"
      (let [result (ds/validate-entity schema :binary
                                       {:id (random-uuid)
                                        :data "not bytes"})]
        (is (some? result))
        (is (contains? (:errors result) :data))))))


(deftest numeric-field-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :measurement (uuid)
                                  {:value {:uuid (uuid) :type :numeric}})
                   (ds/build))]

    (testing "numeric accepts integers"
      (is (nil? (ds/validate-entity schema :measurement
                                    {:id (random-uuid) :value 42}))))

    (testing "numeric accepts doubles"
      (is (nil? (ds/validate-entity schema :measurement
                                    {:id (random-uuid) :value 3.14}))))

    (testing "numeric rejects strings"
      (let [result (ds/validate-entity schema :measurement
                                       {:id (random-uuid) :value "not a number"})]
        (is (some? result))
        (is (contains? (:errors result) :value))))))


(deftest duplicate-entity-name-test
  (testing "duplicate entity names throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate entity name"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid) {:other {:uuid (uuid) :type :text}})
              (ds/build))))))


(deftest field-missing-uuid-test
  (testing "field missing uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field missing :uuid"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:type :text}})
              (ds/build))))))


;; === Additional error path tests for coverage ===

(deftest enum-values-format-test
  (testing "enum values not a vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum values must be a vector"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) {:active {:uuid (uuid)}})
              (ds/build)))))

  (testing "enum value entry not a map throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Each enum value must be a map"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [:active])
              (ds/build)))))

  (testing "duplicate enum value UUIDs throw"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Enum has duplicate value UUIDs"
            (-> (mds/create-builder)
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}
                                             {:uuid same-uuid :value :inactive}])
                (ds/build)))))))


(deftest constraint-error-paths-test
  (testing "constraint missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:fields [:name]})
              (ds/build)))))

  (testing "constraint :fields with non-keywords throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint :fields must contain only keywords"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields ["name"]})
              (ds/build)))))

  (testing "constraint with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields [:name] :extra "value"})
              (ds/build)))))

  (testing "duplicate constraint throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate constraint"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields [:name]})
              (ds/add-constraint :item {:type :unique :fields [:name]})
              (ds/build))))))


(deftest duplicate-uuid-within-entity-test
  (testing "duplicate field UUIDs within same entity throws specific error"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID within entity"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:field1 {:uuid same-uuid :type :text}
                                             :field2 {:uuid same-uuid :type :int}})
                (ds/build)))))))


;; === Union type tests ===

(deftest union-with-ref-variant-test
  (testing "union with ref variant validates correctly"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :item (uuid)
                                    {:owner {:uuid (uuid)
                                             :type :union
                                             :variants [{:type :ref :ref-entity :user}
                                                        {:type :text}]}})
                     ds/build)]
      ;; Valid with UUID (ref to user)
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :owner (uuid)})))
      ;; Valid with string (text variant)
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :owner "some text"})))
      ;; Invalid with number
      (let [errors (ds/validate-entity schema :item {:id (uuid) :owner 123})]
        (is (some? (:errors errors)))))))


(deftest union-with-enum-variant-test
  (testing "union with enum variant validates correctly"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :inactive}])
                     (ds/add-entity :item (uuid)
                                    {:state {:uuid (uuid)
                                             :type :union
                                             :variants [{:type :enum :enum-name :status}
                                                        {:type :int}]}})
                     ds/build)]
      ;; Valid with enum value
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :state :active})))
      ;; Valid with int
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :state 42})))
      ;; Invalid with string
      (let [errors (ds/validate-entity schema :item {:id (uuid) :state "invalid"})]
        (is (some? (:errors errors)))))))


(deftest union-variant-nullable-error-test
  (testing "union variant with :nullable? throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variant cannot have :nullable?"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid)
                                      :type :union
                                      :variants [{:type :text :nullable? true}]}})
              ds/build)))))


(deftest union-with-duplicate-variants-test
  (testing "union with duplicate variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union has duplicate variants"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid)
                                      :type :union
                                      :variants [{:type :text} {:type :text}]}})
              ds/build)))))


(deftest union-empty-variants-test
  (testing "union with empty variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variants cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union :variants []}})
              ds/build)))))


;; === More field type validation tests ===

(deftest ref-type-extra-attributes-test
  (testing "ref field with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"ref has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid)
                             {:owner {:uuid (uuid)
                                      :type :ref
                                      :ref-entity :user
                                      :extra-attr "value"}})
              ds/build)))))


(deftest enum-type-extra-attributes-test
  (testing "enum field with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"enum has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
              (ds/add-entity :item (uuid)
                             {:status {:uuid (uuid)
                                       :type :enum
                                       :enum-name :status
                                       :extra-attr "value"}})
              ds/build)))))


(deftest base-type-extra-attributes-test
  (testing "base field type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"text has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:name {:uuid (uuid)
                                     :type :text
                                     :extra-attr "value"}})
              ds/build)))))


(deftest union-extra-attributes-test
  (testing "union field with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"union has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid)
                                      :type :union
                                      :variants [{:type :text}]
                                      :extra-attr "value"}})
              ds/build)))))


;; === All field types validation ===

(deftest all-field-types-validation-test
  (testing "all field types validate correctly"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
                     (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :test-entity (uuid)
                                    {:uuid-field {:uuid (uuid) :type :uuid}
                                     :text-field {:uuid (uuid) :type :text}
                                     :int-field {:uuid (uuid) :type :int}
                                     :bool-field {:uuid (uuid) :type :bool}
                                     :numeric-field {:uuid (uuid) :type :numeric}
                                     :timestamp-field {:uuid (uuid) :type :timestamptz}
                                     :jsonb-field {:uuid (uuid) :type :jsonb}
                                     :bytes-field {:uuid (uuid) :type :bytes}
                                     :ref-field {:uuid (uuid) :type :ref :ref-entity :target}
                                     :enum-field {:uuid (uuid) :type :enum :enum-name :status}})
                     ds/build)]
      ;; Valid data
      (is (nil? (ds/validate-entity schema :test-entity
                                    {:id (uuid)
                                     :uuid-field (uuid)
                                     :text-field "hello"
                                     :int-field 42
                                     :bool-field true
                                     :numeric-field 3.14
                                     :timestamp-field (java.util.Date.)
                                     :jsonb-field {"key" "value"}
                                     :bytes-field (byte-array [1 2 3])
                                     :ref-field (uuid)
                                     :enum-field :active})))
      ;; Invalid uuid-field
      (is (some? (:errors (ds/validate-entity schema :test-entity
                                              {:id (uuid)
                                               :uuid-field "not-uuid"
                                               :text-field "hello"
                                               :int-field 42
                                               :bool-field true
                                               :numeric-field 3.14
                                               :timestamp-field (java.util.Date.)
                                               :jsonb-field nil
                                               :bytes-field (byte-array [])
                                               :ref-field (uuid)
                                               :enum-field :active})))))))


;; =============================================================================
;; Forms Coverage Tests
;; These tests exercise loops and branches multiple times to increase coverage
;; =============================================================================

(deftest many-entities-with-refs-forms-coverage-test
  (testing "schema with many entities referencing each other increases ref validation coverage"
    (let [schema (-> (mds/create-builder)
                     ;; Create a chain of entities referencing each other
                     (ds/add-entity :entity-a (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :entity-b (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}})
                     (ds/add-entity :entity-c (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}
                                     :ref-b {:uuid (uuid) :type :ref :ref-entity :entity-b}})
                     (ds/add-entity :entity-d (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}
                                     :ref-b {:uuid (uuid) :type :ref :ref-entity :entity-b}
                                     :ref-c {:uuid (uuid) :type :ref :ref-entity :entity-c}})
                     (ds/add-entity :entity-e (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}
                                     :ref-b {:uuid (uuid) :type :ref :ref-entity :entity-b}
                                     :ref-c {:uuid (uuid) :type :ref :ref-entity :entity-c}
                                     :ref-d {:uuid (uuid) :type :ref :ref-entity :entity-d}})
                     ds/build)]
      (is (= 5 (count (ds/entities schema))))
      (is (= 5 (count (ds/entity-fields schema :entity-e)))))))


(deftest many-entities-with-enums-forms-coverage-test
  (testing "schema with many enum fields increases enum validation coverage"
    (let [schema (-> (mds/create-builder)
                     ;; Multiple enums
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :inactive}
                                   {:uuid (uuid) :value :pending}])
                     (ds/add-enum :priority (uuid)
                                  [{:uuid (uuid) :value :low}
                                   {:uuid (uuid) :value :medium}
                                   {:uuid (uuid) :value :high}
                                   {:uuid (uuid) :value :critical}])
                     (ds/add-enum :category (uuid)
                                  [{:uuid (uuid) :value :work}
                                   {:uuid (uuid) :value :personal}
                                   {:uuid (uuid) :value :other}])
                     ;; Entities with multiple enum fields
                     (ds/add-entity :task (uuid)
                                    {:title {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}
                                     :priority {:uuid (uuid) :type :enum :enum-name :priority}
                                     :category {:uuid (uuid) :type :enum :enum-name :category}})
                     (ds/add-entity :project (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}
                                     :priority {:uuid (uuid) :type :enum :enum-name :priority}})
                     (ds/add-entity :milestone (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}
                                     :category {:uuid (uuid) :type :enum :enum-name :category}})
                     ds/build)]
      (is (= 3 (count (ds/entities schema))))
      (is (= 3 (count (ds/enums schema)))))))


(deftest unions-with-refs-and-enums-forms-coverage-test
  (testing "unions with both ref and enum variants cover both case branches"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :type-a (uuid)
                                  [{:uuid (uuid) :value :opt1}
                                   {:uuid (uuid) :value :opt2}])
                     (ds/add-enum :type-b (uuid)
                                  [{:uuid (uuid) :value :val1}
                                   {:uuid (uuid) :value :val2}])
                     (ds/add-entity :target-x (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :target-y (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :target-z (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     ;; Entity with union containing both refs and enums
                     (ds/add-entity :mixed-union (uuid)
                                    {:field1 {:uuid (uuid)
                                              :type :union
                                              :variants [{:type :ref :ref-entity :target-x}
                                                         {:type :ref :ref-entity :target-y}
                                                         {:type :enum :enum-name :type-a}
                                                         {:type :text}]}
                                     :field2 {:uuid (uuid)
                                              :type :union
                                              :variants [{:type :ref :ref-entity :target-z}
                                                         {:type :enum :enum-name :type-b}
                                                         {:type :int}]}})
                     ;; Another entity with different union combinations
                     (ds/add-entity :another-mixed (uuid)
                                    {:data {:uuid (uuid)
                                            :type :union
                                            :variants [{:type :ref :ref-entity :target-x}
                                                       {:type :ref :ref-entity :target-y}
                                                       {:type :ref :ref-entity :target-z}
                                                       {:type :enum :enum-name :type-a}
                                                       {:type :enum :enum-name :type-b}]}})
                     ds/build)]
      (is (= 5 (count (ds/entities schema))))
      (is (= 4 (count (:variants (:field1 (ds/entity-fields schema :mixed-union))))))
      (is (= 5 (count (:variants (:data (ds/entity-fields schema :another-mixed)))))))))


(deftest many-fields-per-entity-forms-coverage-test
  (testing "entities with many fields increase field validation loop coverage"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :wide-entity (uuid)
                                    {:field01 {:uuid (uuid) :type :text}
                                     :field02 {:uuid (uuid) :type :text}
                                     :field03 {:uuid (uuid) :type :int}
                                     :field04 {:uuid (uuid) :type :int}
                                     :field05 {:uuid (uuid) :type :bool}
                                     :field06 {:uuid (uuid) :type :bool}
                                     :field07 {:uuid (uuid) :type :uuid}
                                     :field08 {:uuid (uuid) :type :uuid}
                                     :field09 {:uuid (uuid) :type :numeric}
                                     :field10 {:uuid (uuid) :type :numeric}
                                     :field11 {:uuid (uuid) :type :timestamptz}
                                     :field12 {:uuid (uuid) :type :jsonb}
                                     :field13 {:uuid (uuid) :type :bytes}
                                     :field14 {:uuid (uuid) :type :text :nullable? true}
                                     :field15 {:uuid (uuid) :type :int :nullable? true}})
                     ds/build)]
      (is (= 15 (count (ds/entity-fields schema :wide-entity)))))))


(deftest many-constraints-forms-coverage-test
  (testing "multiple constraints on multiple entities increase constraint validation coverage"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid)
                                    {:username {:uuid (uuid) :type :text}
                                     :email {:uuid (uuid) :type :text}
                                     :phone {:uuid (uuid) :type :text :nullable? true}
                                     :external-id {:uuid (uuid) :type :text :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:username]})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     (ds/add-constraint :user {:type :unique :fields [:phone]})
                     (ds/add-constraint :user {:type :unique :fields [:external-id]})
                     (ds/add-constraint :user {:type :unique :fields [:username :email]})
                     (ds/add-entity :order (uuid)
                                    {:order-num {:uuid (uuid) :type :text}
                                     :user-id {:uuid (uuid) :type :ref :ref-entity :user}
                                     :status {:uuid (uuid) :type :text}
                                     :date {:uuid (uuid) :type :timestamptz}})
                     (ds/add-constraint :order {:type :unique :fields [:order-num]})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :order-num]})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :date]})
                     ds/build)]
      (is (= 5 (count (ds/entity-constraints schema :user))))
      (is (= 3 (count (ds/entity-constraints schema :order)))))))


(deftest large-enum-forms-coverage-test
  (testing "enum with many values increases enum value validation coverage"
    (let [enum-values (vec (for [i (range 15)]
                             {:uuid (uuid) :value (keyword (str "value-" i))}))
          schema (-> (mds/create-builder)
                     (ds/add-enum :large-enum (uuid) enum-values)
                     (ds/add-entity :item (uuid)
                                    {:category {:uuid (uuid) :type :enum :enum-name :large-enum}})
                     ds/build)]
      (is (= 15 (count (:values (get (ds/enums schema) :large-enum))))))))


(deftest complex-schema-forms-coverage-test
  (testing "complex schema with all features exercises all validation paths"
    (let [schema (-> (mds/create-builder)
                     ;; Multiple enums with multiple values
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :draft}
                                   {:uuid (uuid) :value :pending}
                                   {:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :archived}])
                     (ds/add-enum :role (uuid)
                                  [{:uuid (uuid) :value :admin}
                                   {:uuid (uuid) :value :editor}
                                   {:uuid (uuid) :value :viewer}])
                     ;; Base entities
                     (ds/add-entity :user (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :email {:uuid (uuid) :type :text}
                                     :role {:uuid (uuid) :type :enum :enum-name :role}
                                     :metadata {:uuid (uuid) :type :jsonb :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ;; Entity with refs
                     (ds/add-entity :document (uuid)
                                    {:title {:uuid (uuid) :type :text}
                                     :content {:uuid (uuid) :type :text}
                                     :author-id {:uuid (uuid) :type :ref :ref-entity :user}
                                     :reviewer-id {:uuid (uuid) :type :ref :ref-entity :user :nullable? true}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}})
                     (ds/add-constraint :document {:type :unique :fields [:title :author-id]})
                     ;; Entity with union
                     (ds/add-entity :comment (uuid)
                                    {:text {:uuid (uuid) :type :text}
                                     :author-id {:uuid (uuid) :type :ref :ref-entity :user}
                                     :target {:uuid (uuid)
                                              :type :union
                                              :variants [{:type :ref :ref-entity :document}
                                                         {:type :ref :ref-entity :user}]}})
                     ;; Entity with self-reference
                     (ds/add-entity :folder (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :parent-id {:uuid (uuid) :type :ref :ref-entity :folder :nullable? true}
                                     :owner-id {:uuid (uuid) :type :ref :ref-entity :user}})
                     (ds/add-constraint :folder {:type :unique :fields [:name :parent-id]})
                     ds/build)]
      (is (= 4 (count (ds/entities schema))))
      (is (= 2 (count (ds/enums schema))))
      (is (= 1 (count (ds/entity-constraints schema :user))))
      (is (= 1 (count (ds/entity-constraints schema :document))))
      (is (= 1 (count (ds/entity-constraints schema :folder)))))))


(deftest unknown-enum-reference-test
  (testing "field referencing non-existent enum throws descriptive error"
    ;; Exception thrown by validate-refs during build
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown enum reference"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:status {:uuid (uuid)
                                       :type :enum
                                       :enum-name :nonexistent-enum}})
              ds/build)))))


(deftest validate-single-ref-unknown-type-test
  (testing "validate-single-ref with unknown ref-type throws"
    (let [validate-single-ref-fn #'core/validate-single-ref]
      ;; case without default throws IllegalArgumentException for unknown keys
      (is (thrown? IllegalArgumentException
            (validate-single-ref-fn :entity :field :unknown-type :ref-name {} {}))))))


(deftest identifier-length-validation-test
  (testing "enum value name too long throws"
    (let [long-value (keyword (str/join (repeat 64 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Identifier name too long"
            (-> (mds/create-builder)
                (ds/add-enum :status (uuid) [{:uuid (uuid) :value long-value}])
                ds/build)))))

  (testing "enum value exactly 63 chars is valid"
    (let [max-value (keyword (str/join (repeat 63 "a")))]
      (is (some? (-> (mds/create-builder)
                     (ds/add-enum :status (uuid) [{:uuid (uuid) :value max-value}])
                     ds/build))))))
