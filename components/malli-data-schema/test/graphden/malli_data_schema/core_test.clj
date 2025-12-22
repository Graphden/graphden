(ns graphden.malli-data-schema.core-test
  (:require
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
