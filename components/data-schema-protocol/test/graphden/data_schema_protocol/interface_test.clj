(ns graphden.data-schema-protocol.interface-test
  "Contract tests for DataSchema and DataSchemaBuilder protocols.
   Uses malli-data-schema as reference implementation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]))


;; Helper to generate UUIDs for tests
(defn- uuid
  []
  (random-uuid))


;; === DataSchemaBuilder contract tests ===

(deftest add-enum-test
  (testing "add-enum returns a new builder with the enum"
    (let [builder (-> (mds/create-builder)
                      (ds/add-enum :status (uuid)
                                   [{:uuid (uuid) :value :active}
                                    {:uuid (uuid) :value :inactive}]))]
      (is (some? builder))))

  (testing "duplicate enum name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate enum"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid)
                           [{:uuid (uuid) :value :a}
                            {:uuid (uuid) :value :b}])
              (ds/add-enum :status (uuid)
                           [{:uuid (uuid) :value :c}
                            {:uuid (uuid) :value :d}])))))

  (testing "empty enum values throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"empty"
          (ds/add-enum (mds/create-builder) :empty (uuid) []))))

  (testing "enum with duplicate values throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"duplicate"
          (ds/add-enum (mds/create-builder) :status (uuid)
                       [{:uuid (uuid) :value :a}
                        {:uuid (uuid) :value :b}
                        {:uuid (uuid) :value :a}]))))

  (testing "enum values must be keywords"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a keyword"
          (ds/add-enum (mds/create-builder) :status (uuid)
                       [{:uuid (uuid) :value "active"}
                        {:uuid (uuid) :value "inactive"}])))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a keyword"
          (ds/add-enum (mds/create-builder) :status (uuid)
                       [{:uuid (uuid) :value 1}
                        {:uuid (uuid) :value 2}]))))

  (testing "enum-uuid must be a uuid"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (ds/add-enum (mds/create-builder) :status "not-a-uuid"
                       [{:uuid (uuid) :value :a}]))))

  (testing "enum value uuid must be a uuid"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (ds/add-enum (mds/create-builder) :status (uuid)
                       [{:uuid "not-a-uuid" :value :a}]))))

  (testing "enum value missing :uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :uuid"
          (ds/add-enum (mds/create-builder) :status (uuid)
                       [{:value :a}]))))

  (testing "enum value missing :value throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :value"
          (ds/add-enum (mds/create-builder) :status (uuid)
                       [{:uuid (uuid)}])))))


(deftest add-entity-test
  (testing "add-entity returns a new builder with the entity"
    (let [builder (-> (mds/create-builder)
                      (ds/add-entity :user (uuid)
                                     {:name {:uuid (uuid) :type :text}}))]
      (is (some? builder))))

  (testing "duplicate entity name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate entity"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})))))

  (testing "entity name must be keyword"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a keyword"
          (ds/add-entity (mds/create-builder) "user" (uuid)
                         {:name {:uuid (uuid) :type :text}}))))

  (testing "field name must be keyword"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a keyword"
          (ds/add-entity (mds/create-builder) :user (uuid)
                         {"name" {:uuid (uuid) :type :text}}))))

  (testing "field name :id is reserved"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"reserved"
          (ds/add-entity (mds/create-builder) :user (uuid)
                         {:id {:uuid (uuid) :type :text}}))))

  (testing "entity-uuid must be a uuid"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (ds/add-entity (mds/create-builder) :user "not-a-uuid"
                         {:name {:uuid (uuid) :type :text}}))))

  (testing "field uuid must be a uuid"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (ds/add-entity (mds/create-builder) :user (uuid)
                         {:name {:uuid "not-a-uuid" :type :text}}))))

  (testing "field missing :uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :uuid"
          (ds/add-entity (mds/create-builder) :user (uuid)
                         {:name {:type :text}})))))


(deftest build-validation-test
  (testing "build with unknown enum reference throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown enum"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:status {:uuid (uuid) :type :enum :enum-name :undefined}})
              (ds/build)))))

  (testing "build with unknown entity reference throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown entity"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:role-id {:uuid (uuid) :type :ref :ref-entity :undefined}})
              (ds/build)))))

  (testing "build with empty union variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union :variants []}})
              (ds/build)))))

  (testing "build with duplicate union variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"duplicate"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :int}
                                                 {:type :int}]}})
              (ds/build)))))

  (testing "union with duplicate :ref to same entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"duplicate"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :ref :ref-entity :user}
                                                 {:type :ref :ref-entity :user}]}})
              (ds/build)))))

  (testing "union with :ref to different entities is valid"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :role (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :item (uuid)
                                    {:value {:uuid (uuid) :type :union
                                             :variants [{:type :ref :ref-entity :user}
                                                        {:type :ref :ref-entity :role}]}})
                     (ds/build))]
      (is (some? schema))))

  (testing "field missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:name {:uuid (uuid)}})
              (ds/build)))))

  (testing "unknown field type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown field type"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:data {:uuid (uuid) :type :unknown}})
              (ds/build)))))

  (testing ":ref without :ref-entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :ref-entity"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:other-id {:uuid (uuid) :type :ref}})
              (ds/build)))))

  (testing ":ref with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :user (uuid)
                             {:target-id {:uuid (uuid) :type :ref
                                          :ref-entity :target
                                          :ref-field :name}})
              (ds/build)))))

  (testing ":enum with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid)
                           [{:uuid (uuid) :value :active}
                            {:uuid (uuid) :value :inactive}])
              (ds/add-entity :user (uuid)
                             {:status {:uuid (uuid) :type :enum
                                       :enum-name :status
                                       :ref-entity :foo}})
              (ds/build)))))

  (testing ":union with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :int}]
                                      :enum-name :foo}})
              (ds/build)))))

  (testing "base type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:name {:uuid (uuid) :type :text :ref-entity :foo}})
              (ds/build)))))

  (testing ":enum without :enum-name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :enum-name"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:status {:uuid (uuid) :type :enum}})
              (ds/build)))))

  (testing ":union without :variants vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :variants"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:value {:uuid (uuid) :type :union}})
              (ds/build)))))

  (testing "malformed union variant throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"requires :ref-entity"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :ref}]}})
              (ds/build)))))

  (testing "union variant cannot have :nullable?"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"cannot have :nullable"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :int :nullable? true}]}})
              (ds/build)))))

  (testing ":nullable? must be boolean"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a boolean"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:name {:uuid (uuid) :type :text :nullable? "true"}})
              (ds/build))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a boolean"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid)
                             {:name {:uuid (uuid) :type :text :nullable? 1}})
              (ds/build)))))

  (testing "build with valid schema succeeds"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :inactive}])
                     (ds/add-entity :user (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}})
                     (ds/build))]
      (is (some? schema))))

  (testing "duplicate UUID across entity and field throws"
    (let [shared-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :user shared-uuid
                               {:name {:uuid shared-uuid :type :text}}))))))

  (testing "duplicate UUID across entities throws"
    (let [shared-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :user shared-uuid {:name {:uuid (uuid) :type :text}})
                (ds/add-entity :role shared-uuid {:name {:uuid (uuid) :type :text}}))))))

  (testing "duplicate UUID across enum and entity throws"
    (let [shared-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-enum :status shared-uuid
                             [{:uuid (uuid) :value :active}])
                (ds/add-entity :user shared-uuid
                               {:name {:uuid (uuid) :type :text}})))))))


;; === DataSchema contract tests ===

;; Fixed UUIDs for test-schema so we can test entity-uuid and enum-uuid
(def ^:private test-role-uuid #uuid "11111111-1111-1111-1111-111111111111")
(def ^:private test-account-uuid #uuid "22222222-2222-2222-2222-222222222222")
(def ^:private test-profile-uuid #uuid "33333333-3333-3333-3333-333333333333")


(def test-schema
  (-> (mds/create-builder)
      (ds/add-enum :role test-role-uuid
                   [{:uuid (uuid) :value :admin}
                    {:uuid (uuid) :value :user}])
      (ds/add-entity :account test-account-uuid
                     {:name {:uuid (uuid) :type :text :nullable? false}})
      (ds/add-entity :profile test-profile-uuid
                     {:account-id {:uuid (uuid) :type :ref :ref-entity :account}
                      :role {:uuid (uuid) :type :enum :enum-name :role}})
      (ds/build)))


(deftest entities-test
  (testing "entities returns all entity names"
    (let [entities (set (ds/entities test-schema))]
      (is (= #{:account :profile} entities))))

  (testing "entity-uuid returns the entity's UUID"
    (is (= test-account-uuid (ds/entity-uuid test-schema :account)))
    (is (= test-profile-uuid (ds/entity-uuid test-schema :profile))))

  (testing "entity-uuid returns nil for unknown entity"
    (is (nil? (ds/entity-uuid test-schema :unknown)))))


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
      ;; :values is now a map of value->uuid
      (is (= #{:admin :user} (set (keys (:values (get enums :role))))))))

  (testing "enum-uuid returns the enum's UUID"
    (is (= test-role-uuid (ds/enum-uuid test-schema :role))))

  (testing "enum-uuid returns nil for unknown enum"
    (is (nil? (ds/enum-uuid test-schema :unknown)))))


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
                      (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
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
              (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique :fields [:email]})
              (ds/build)))))

  (testing "constraint on :id field is valid"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:id :name]})
                     (ds/build))]
      (is (some? schema))))

  (testing "constraint missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:fields [:email]})))))

  (testing "unknown constraint type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown constraint type"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unknown :fields [:email]})))))

  (testing "constraint :fields must be a vector"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a vector"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique :fields :email}))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must be a vector"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique})))))

  (testing "constraint :fields cannot be empty"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique :fields []})))))

  (testing "constraint :fields must contain keywords"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"must contain only keywords"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique :fields ["email"]})))))

  (testing "duplicate constraint throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate constraint"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique :fields [:email]})
              (ds/add-constraint :user {:type :unique :fields [:email]})))))

  (testing "constraint with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:email {:uuid (uuid) :type :text}})
              (ds/add-constraint :user {:type :unique :fields [:email] :foo :bar}))))))


(deftest entity-constraints-test
  (testing "entity-constraints returns constraints"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid)
                                    {:email {:uuid (uuid) :type :text}
                                     :tenant-id {:uuid (uuid) :type :uuid}})
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
