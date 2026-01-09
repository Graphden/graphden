(ns graphden.datomic-storage.schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.schema :as schema]))


;; === field-type->datomic tests ===

(deftest field-type->datomic-test
  (testing "converts :ref type to :db.type/ref"
    (is (= :db.type/ref (schema/field-type->datomic {:type :ref}))))

  (testing "converts :enum type to :db.type/ref"
    (is (= :db.type/ref (schema/field-type->datomic {:type :enum}))))

  (testing "converts :union type to :db.type/string"
    (is (= :db.type/string (schema/field-type->datomic {:type :union}))))

  (testing "converts standard types via util/type->datomic"
    (is (= :db.type/uuid (schema/field-type->datomic {:type :uuid})))
    (is (= :db.type/string (schema/field-type->datomic {:type :text})))
    (is (= :db.type/long (schema/field-type->datomic {:type :int})))
    (is (= :db.type/boolean (schema/field-type->datomic {:type :bool})))
    (is (= :db.type/bigdec (schema/field-type->datomic {:type :numeric})))
    (is (= :db.type/instant (schema/field-type->datomic {:type :timestamptz})))
    (is (= :db.type/string (schema/field-type->datomic {:type :jsonb})))
    (is (= :db.type/bytes (schema/field-type->datomic {:type :bytes}))))

  (testing "defaults unknown types to :db.type/string"
    (is (= :db.type/string (schema/field-type->datomic {:type :unknown})))))


;; === Constraint helper tests ===

(deftest single-field-unique-constraint?-test
  (testing "returns true for single-field unique constraint"
    (is (true? (schema/single-field-unique-constraint?
                 {:type :unique :fields [:email]}))))

  (testing "returns false for multi-field unique constraint"
    (is (false? (schema/single-field-unique-constraint?
                  {:type :unique :fields [:first-name :last-name]}))))

  (testing "returns false for non-unique constraint"
    (is (false? (schema/single-field-unique-constraint?
                  {:type :index :fields [:email]})))))


(deftest multi-field-unique-constraint?-test
  (testing "returns true for multi-field unique constraint"
    (is (true? (schema/multi-field-unique-constraint?
                 {:type :unique :fields [:first-name :last-name]}))))

  (testing "returns false for single-field unique constraint"
    (is (false? (schema/multi-field-unique-constraint?
                  {:type :unique :fields [:email]}))))

  (testing "returns false for non-unique constraint"
    (is (false? (schema/multi-field-unique-constraint?
                  {:type :index :fields [:a :b]})))))


;; === Schema builder tests ===

(deftest build-id-schema-test
  (testing "creates correct id schema"
    (let [result (schema/build-id-schema :user)]
      (is (= :user/id (:db/ident result)))
      (is (= :db.type/uuid (:db/valueType result)))
      (is (= :db.cardinality/one (:db/cardinality result)))
      (is (= :db.unique/identity (:db/unique result)))))

  (testing "works with different entity names"
    (let [result (schema/build-id-schema :fn-schema)]
      (is (= :fn-schema/id (:db/ident result))))))


(deftest build-enum-value-schema-test
  (testing "creates correct enum value schema"
    (let [result (schema/build-enum-value-schema :status :active)]
      (is (= :status.value/active (:db/ident result)))))

  (testing "works with different enum and values"
    (let [result (schema/build-enum-value-schema :priority :high)]
      (is (= :priority.value/high (:db/ident result))))))


(deftest build-metadata-schema-test
  (testing "creates all required metadata attributes"
    (let [result (schema/build-metadata-schema)
          idents (set (map :db/ident result))]
      (is (contains? idents :graphden.metadata/uuid))
      (is (contains? idents :graphden.metadata/kind))
      (is (contains? idents :graphden.metadata/name))
      (is (contains? idents :graphden.metadata/parent-uuid))
      (is (contains? idents :graphden.metadata/field-type))
      (is (contains? idents :graphden.metadata/field-nullable))
      (is (contains? idents :graphden.metadata/field-enum-name))
      (is (contains? idents :graphden.metadata/field-ref-entity))))

  (testing "uuid attribute has unique identity"
    (let [result (schema/build-metadata-schema)
          uuid-attr (first (filter #(= :graphden.metadata/uuid (:db/ident %)) result))]
      (is (= :db.type/uuid (:db/valueType uuid-attr)))
      (is (= :db.unique/identity (:db/unique uuid-attr))))))


;; === build-field-schema tests ===
;; Note: These tests use a mock schema via reify

(defn- mock-schema
  "Creates a mock schema with given constraints."
  [constraints]
  (reify ds/DataSchema
    (entities [_] #{:user})

    (entity-uuid [_ _] (random-uuid))

    (entity-fields [_ _] {:name {:type :text} :age {:type :int}})

    (entity-constraints [_ _] constraints)

    (enums [_] {})

    (enum-uuid [_ _] nil)

    (validate-entity [_ _ _] nil)))


(deftest build-field-schema-test
  (testing "creates basic field schema without unique constraint"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :name {:type :text})]
      (is (= :user/name (:db/ident result)))
      (is (= :db.type/string (:db/valueType result)))
      (is (= :db.cardinality/one (:db/cardinality result)))
      (is (nil? (:db/unique result)))))

  (testing "adds unique constraint for single-field unique"
    (let [s (mock-schema [{:type :unique :fields [:email]}])
          result (schema/build-field-schema s :user :email {:type :text})]
      (is (= :user/email (:db/ident result)))
      (is (= :db.unique/value (:db/unique result)))))

  (testing "does not add unique for multi-field constraint"
    (let [s (mock-schema [{:type :unique :fields [:first-name :last-name]}])
          result (schema/build-field-schema s :user :first-name {:type :text})]
      (is (nil? (:db/unique result)))))

  (testing "handles ref type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :org {:type :ref :ref-entity :org})]
      (is (= :db.type/ref (:db/valueType result)))))

  (testing "handles enum type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :status {:type :enum :enum-name :status})]
      (is (= :db.type/ref (:db/valueType result))))))


;; === get-single-field-constraints tests ===

(deftest get-single-field-constraints-test
  (testing "returns field names from single-field unique constraints"
    (let [s (mock-schema [{:type :unique :fields [:email]}
                          {:type :unique :fields [:username]}])
          result (schema/get-single-field-constraints s :user)]
      (is (= #{:email :username} result))))

  (testing "excludes multi-field constraints"
    (let [s (mock-schema [{:type :unique :fields [:email]}
                          {:type :unique :fields [:first :last]}])
          result (schema/get-single-field-constraints s :user)]
      (is (= #{:email} result))))

  (testing "returns empty set when no constraints"
    (let [s (mock-schema [])
          result (schema/get-single-field-constraints s :user)]
      (is (= #{} result)))))


;; === get-multi-field-constraints tests ===

(deftest get-multi-field-constraints-test
  (testing "returns multi-field unique constraints"
    (let [s (mock-schema [{:type :unique :fields [:first :last]}
                          {:type :unique :fields [:email]}])
          result (schema/get-multi-field-constraints s :user)]
      (is (= 1 (count result)))
      (is (= [:first :last] (:fields (first result))))))

  (testing "returns empty when only single-field constraints"
    (let [s (mock-schema [{:type :unique :fields [:email]}])
          result (schema/get-multi-field-constraints s :user)]
      (is (empty? result))))

  (testing "returns multiple multi-field constraints"
    (let [s (mock-schema [{:type :unique :fields [:a :b]}
                          {:type :unique :fields [:c :d :e]}])
          result (schema/get-multi-field-constraints s :user)]
      (is (= 2 (count result))))))
