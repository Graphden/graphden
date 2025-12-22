(ns graphden.data-schema-protocol.interface-test
  "Contract tests for DataSchema and DataSchemaBuilder protocols.
   Uses malli-data-schema as reference implementation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]))


;; === DataSchemaBuilder contract tests ===

(deftest add-enum-test
  (testing "add-enum returns a new builder with the enum"
    (let [builder (-> (mds/create-builder)
                      (ds/add-enum :status [:active :inactive]))]
      (is (some? builder))))

  (testing "duplicate enum name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate enum"
          (-> (mds/create-builder)
              (ds/add-enum :status [:a :b])
              (ds/add-enum :status [:c :d])))))

  (testing "empty enum values throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"empty"
          (ds/add-enum (mds/create-builder) :empty []))))

  (testing "enum with duplicate values throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"duplicate"
          (ds/add-enum (mds/create-builder) :status [:a :b :a]))))

  (testing "enum values must be keywords"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be keywords"
          (ds/add-enum (mds/create-builder) :status ["active" "inactive"])))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be keywords"
          (ds/add-enum (mds/create-builder) :status [1 2 3])))))


(deftest add-entity-test
  (testing "add-entity returns a new builder with the entity"
    (let [builder (-> (mds/create-builder)
                      (ds/add-entity :user {:name {:type :text}}))]
      (is (some? builder))))

  (testing "duplicate entity name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate entity"
          (-> (mds/create-builder)
              (ds/add-entity :user {:name {:type :text}})
              (ds/add-entity :user {:email {:type :text}})))))

  (testing "entity name must be keyword"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a keyword"
          (ds/add-entity (mds/create-builder) "user" {:name {:type :text}}))))

  (testing "field name must be keyword"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a keyword"
          (ds/add-entity (mds/create-builder) :user {"name" {:type :text}}))))

  (testing "field name :id is reserved"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"reserved"
          (ds/add-entity (mds/create-builder) :user {:id {:type :text}})))))


(deftest build-validation-test
  (testing "build with unknown enum reference throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown enum"
          (-> (mds/create-builder)
              (ds/add-entity :user {:status {:type :enum :enum-name :undefined}})
              (ds/build)))))

  (testing "build with unknown entity reference throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown entity"
          (-> (mds/create-builder)
              (ds/add-entity :user {:role-id {:type :ref :ref-entity :undefined}})
              (ds/build)))))

  (testing "build with empty union variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"empty"
          (-> (mds/create-builder)
              (ds/add-entity :item {:value {:type :union :variants []}})
              (ds/build)))))

  (testing "build with duplicate union variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"duplicate"
          (-> (mds/create-builder)
              (ds/add-entity :item {:value {:type :union
                                            :variants [{:type :int}
                                                       {:type :int}]}})
              (ds/build)))))

  (testing "union with duplicate :ref to same entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"duplicate"
          (-> (mds/create-builder)
              (ds/add-entity :user {:name {:type :text}})
              (ds/add-entity :item {:value {:type :union
                                            :variants [{:type :ref :ref-entity :user}
                                                       {:type :ref :ref-entity :user}]}})
              (ds/build)))))

  (testing "union with :ref to different entities is valid"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user {:name {:type :text}})
                     (ds/add-entity :role {:name {:type :text}})
                     (ds/add-entity :item {:value {:type :union
                                                   :variants [{:type :ref :ref-entity :user}
                                                              {:type :ref :ref-entity :role}]}})
                     (ds/build))]
      (is (some? schema))))

  (testing "field missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :user {:name {}})
              (ds/build)))))

  (testing "unknown field type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown field type"
          (-> (mds/create-builder)
              (ds/add-entity :user {:data {:type :unknown}})
              (ds/build)))))

  (testing ":ref without :ref-entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :ref-entity"
          (-> (mds/create-builder)
              (ds/add-entity :user {:other-id {:type :ref}})
              (ds/build)))))

  (testing ":ref with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :target {:name {:type :text}})
              (ds/add-entity :user {:target-id {:type :ref
                                                :ref-entity :target
                                                :ref-field :name}})
              (ds/build)))))

  (testing ":enum without :enum-name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :enum-name"
          (-> (mds/create-builder)
              (ds/add-entity :user {:status {:type :enum}})
              (ds/build)))))

  (testing ":union without :variants vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :variants"
          (-> (mds/create-builder)
              (ds/add-entity :user {:value {:type :union}})
              (ds/build)))))

  (testing "malformed union variant throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :ref-entity"
          (-> (mds/create-builder)
              (ds/add-entity :user {:value {:type :union
                                            :variants [{:type :ref}]}})
              (ds/build)))))

  (testing ":nullable? must be boolean"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a boolean"
          (-> (mds/create-builder)
              (ds/add-entity :user {:name {:type :text :nullable? "true"}})
              (ds/build))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a boolean"
          (-> (mds/create-builder)
              (ds/add-entity :user {:name {:type :text :nullable? 1}})
              (ds/build)))))

  (testing "build with valid schema succeeds"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status [:active :inactive])
                     (ds/add-entity :user {:name {:type :text}
                                           :status {:type :enum :enum-name :status}})
                     (ds/build))]
      (is (some? schema)))))


;; === DataSchema contract tests ===

(def test-schema
  (-> (mds/create-builder)
      (ds/add-enum :role [:admin :user])
      (ds/add-entity :account {:name {:type :text :nullable? false}})
      (ds/add-entity :profile {:account-id {:type :ref :ref-entity :account}
                               :role {:type :enum :enum-name :role}})
      (ds/build)))


(deftest entities-test
  (testing "entities returns all entity names"
    (let [entities (set (ds/entities test-schema))]
      (is (= #{:account :profile} entities)))))


(deftest entity-fields-test
  (testing "entity-fields returns field definitions"
    (let [fields (ds/entity-fields test-schema :account)]
      (is (map? fields))
      (is (contains? fields :name))
      (is (= :text (get-in fields [:name :type])))))

  (testing "entity-fields returns nil for unknown entity"
    (is (nil? (ds/entity-fields test-schema :unknown)))))


(deftest enums-test
  (testing "enums returns all enum definitions"
    (let [enums (ds/enums test-schema)]
      (is (map? enums))
      (is (contains? enums :role))
      (is (= #{:admin :user} (:values (get enums :role)))))))


(deftest validate-entity-test
  (testing "valid entity returns nil"
    (is (nil? (ds/validate-entity test-schema :account
                                  {:id (random-uuid) :name "test"}))))

  (testing "invalid entity returns errors map"
    (let [result (ds/validate-entity test-schema :account
                                     {:id (random-uuid) :name nil})]
      (is (map? result))
      (is (contains? result :errors))))

  (testing "unknown entity returns error"
    (let [result (ds/validate-entity test-schema :unknown {:id (random-uuid)})]
      (is (some? result))
      (is (contains? (:errors result) :entity))))

  (testing "extra fields are rejected (closed schema)"
    (let [result (ds/validate-entity test-schema :account
                                     {:id (random-uuid)
                                      :name "test"
                                      :extra-field "bad"})]
      (is (some? result)))))


;; === Constraints tests ===

(deftest add-constraint-test
  (testing "add-constraint returns updated builder"
    (let [builder (-> (mds/create-builder)
                      (ds/add-entity :user {:email {:type :text}})
                      (ds/add-constraint :user {:type :unique :fields [:email]}))]
      (is (some? builder))))

  (testing "constraint on unknown entity fails at build"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unknown entity"
          (-> (mds/create-builder)
              (ds/add-constraint :missing {:type :unique :fields [:email]})
              (ds/build)))))

  (testing "constraint on unknown field fails at build"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unknown field"
          (-> (mds/create-builder)
              (ds/add-entity :user {:name {:type :text}})
              (ds/add-constraint :user {:type :unique :fields [:email]})
              (ds/build)))))

  (testing "constraint on :id field is valid"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user {:name {:type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:id :name]})
                     (ds/build))]
      (is (some? schema))))

  (testing "constraint missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:fields [:email]})))))

  (testing "unknown constraint type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown constraint type"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unknown :fields [:email]})))))

  (testing "constraint :fields must be a vector"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a vector"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unique :fields :email}))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a vector"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unique})))))

  (testing "constraint :fields cannot be empty"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unique :fields []})))))

  (testing "constraint :fields must contain keywords"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must contain only keywords"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unique :fields ["email"]})))))

  (testing "duplicate constraint throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate constraint"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unique :fields [:email]})
              (ds/add-constraint :user {:type :unique :fields [:email]})))))

  (testing "constraint with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :user {:email {:type :text}})
              (ds/add-constraint :user {:type :unique :fields [:email] :foo :bar}))))))


(deftest entity-constraints-test
  (testing "entity-constraints returns constraints"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user {:email {:type :text}
                                           :tenant-id {:type :uuid}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     (ds/add-constraint :user {:type :unique :fields [:tenant-id :email]})
                     (ds/build))
          constraints (ds/entity-constraints schema :user)]
      (is (= 2 (count constraints)))
      (is (= [:email] (:fields (first constraints))))
      (is (= [:tenant-id :email] (:fields (second constraints))))))

  (testing "entity-constraints returns empty vector for entity without constraints"
    (is (= [] (ds/entity-constraints test-schema :account))))

  (testing "entity-constraints returns empty vector for unknown entity"
    (is (= [] (ds/entity-constraints test-schema :unknown)))))
