(ns graphden.datomic-storage.schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.interface :as dat]
    [graphden.datomic-storage.schema :as schema]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


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


;; === Tests with Datomic Local ===

(def ^:private test-counter (atom 0))


(defn- unique-db-name
  "Generates a unique database name for each test."
  []
  (str "schema-test-" (swap! test-counter inc) "-" (System/currentTimeMillis)))


(defn- create-test-storage
  "Creates a test storage with a unique database."
  []
  (dat/create-storage {:db-name (unique-db-name)}))


(defn- make-test-schema
  "Creates a test schema with fields and optional constraints."
  [& {:keys [fields constraints]
      :or {fields {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000001"
                                :type :text}
                   :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                               :type :text}
                   :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                           :type :text}}
           constraints []}}]
  (let [builder (-> (mds/create-builder)
                    (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000010" fields))]
    (-> (reduce (fn [b c] (ds/add-constraint b :user c)) builder constraints)
        ds/build)))


;; === warn-multi-field-constraints! tests ===

(deftest warn-multi-field-constraints!-test
  (testing "logs debug info for multi-field constraints (no error)"
    (let [s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])]
      ;; Should not throw, just log
      (is (nil? (schema/warn-multi-field-constraints! s :user)))))

  (testing "does nothing for single-field constraints"
    (let [s (make-test-schema :constraints [{:type :unique :fields [:email]}])]
      (is (nil? (schema/warn-multi-field-constraints! s :user)))))

  (testing "does nothing for empty constraints"
    (let [s (make-test-schema)]
      (is (nil? (schema/warn-multi-field-constraints! s :user))))))


;; === validate-multi-field-unique-constraint! tests ===

(deftest validate-multi-field-unique-constraint!-test
  (testing "passes when no conflict exists"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])]
      (try
        (sp/initialize storage s)
        ;; Create first user
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@example.com"})
        ;; Validate with different values - should pass
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:first-name :last-name]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}}]
          (is (nil? (schema/validate-multi-field-unique-constraint!
                      db :user {:first-name "Jane" :last-name "Doe"} constraint field-specs nil))))
        (finally
          (sp/close storage)))))

  (testing "throws on conflict"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])]
      (try
        (sp/initialize storage s)
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@example.com"})
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:first-name :last-name]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Unique constraint violation"
                (schema/validate-multi-field-unique-constraint!
                  db :user {:first-name "John" :last-name "Doe"} constraint field-specs nil))))
        (finally
          (sp/close storage)))))

  (testing "skips validation when not all fields have values"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])]
      (try
        (sp/initialize storage s)
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@example.com"})
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:first-name :last-name]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}}]
          ;; Only first-name provided - should skip validation
          (is (nil? (schema/validate-multi-field-unique-constraint!
                      db :user {:first-name "John"} constraint field-specs nil))))
        (finally
          (sp/close storage)))))

  (testing "excludes specified id during update"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])
          user-id (random-uuid)]
      (try
        (sp/initialize storage s)
        (sp/create-entity storage :user {:id user-id
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@example.com"})
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:first-name :last-name]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}}]
          ;; Updating same entity with same values - should pass because we exclude its id
          (is (nil? (schema/validate-multi-field-unique-constraint!
                      db :user {:first-name "John" :last-name "Doe"} constraint field-specs user-id))))
        (finally
          (sp/close storage)))))

  (testing "throws when constraint references non-existent field"
    (let [storage (create-test-storage)
          s (make-test-schema)]
      (try
        (sp/initialize storage s)
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:first-name :nonexistent]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Constraint references non-existent field"
                (schema/validate-multi-field-unique-constraint!
                  db :user {:first-name "John" :nonexistent "X"} constraint field-specs nil))))
        (finally
          (sp/close storage))))))


;; === validate-multi-field-constraints! tests ===

(deftest validate-multi-field-constraints!-test
  (testing "validates all multi-field constraints"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}
                                            {:type :unique :fields [:email]}])]
      (try
        (sp/initialize storage s)
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@example.com"})
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}
                           :email {:type :text}}]
          ;; Should throw on multi-field constraint violation
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Unique constraint violation"
                (schema/validate-multi-field-constraints!
                  db s :user {:first-name "John" :last-name "Doe" :email "other@example.com"}
                  field-specs nil))))
        (finally
          (sp/close storage)))))

  (testing "passes when no multi-field constraints violated"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])]
      (try
        (sp/initialize storage s)
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@example.com"})
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}
                           :email {:type :text}}]
          (is (nil? (schema/validate-multi-field-constraints!
                      db s :user {:first-name "Jane" :last-name "Doe" :email "jane@example.com"}
                      field-specs nil))))
        (finally
          (sp/close storage))))))


;; === validate-multi-field-unique-constraint! with ref fields ===

(deftest validate-multi-field-unique-constraint-with-ref-test
  (testing "validates constraint with ref field"
    (let [storage (create-test-storage)
          org-id (random-uuid)
          s (-> (mds/create-builder)
                (ds/add-entity :org #uuid "00000000-0000-0000-0000-000000000020"
                               {:name {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                                       :type :text}})
                (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000030"
                               {:name {:uuid #uuid "00000000-0000-0000-0000-000000000031"
                                       :type :text}
                                :org {:uuid #uuid "00000000-0000-0000-0000-000000000032"
                                      :type :ref
                                      :ref-entity :org}})
                (ds/add-constraint :user {:type :unique :fields [:name :org]})
                ds/build)]
      (try
        (sp/initialize storage s)
        ;; Create org
        (sp/create-entity storage :org {:id org-id :name "Acme Corp"})
        ;; Create user in that org
        (sp/create-entity storage :user {:id (random-uuid)
                                         :name "John"
                                         :org org-id})
        ;; Try to create another user with same name in same org - should fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :user {:id (random-uuid)
                                               :name "John"
                                               :org org-id})))
        ;; Different name in same org - should work
        (sp/create-entity storage :user {:id (random-uuid)
                                         :name "Jane"
                                         :org org-id})
        (finally
          (sp/close storage))))))


;; === Error path tests for validate-multi-field-unique-constraint! ===

(deftest validate-multi-field-constraint-error-paths-test
  (testing "wraps ExceptionInfo from query with constraint-check-failed"
    ;; This is hard to trigger directly since Datomic queries are well-formed
    ;; But we can test the error structure from a missing field scenario
    (let [storage (create-test-storage)
          s (make-test-schema)]
      (try
        (sp/initialize storage s)
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              ;; Constraint references field not in field-specs
              constraint {:type :unique :fields [:first-name :missing-field]}
              field-specs {:first-name {:type :text}}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Constraint references non-existent field"
                (schema/validate-multi-field-unique-constraint!
                  db :user {:first-name "John" :missing-field "X"} constraint field-specs nil))))
        (finally
          (sp/close storage)))))

  (testing "error includes available fields in message"
    (let [storage (create-test-storage)
          s (make-test-schema)]
      (try
        (sp/initialize storage s)
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:nonexistent]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}
                           :email {:type :text}}]
          (try
            (schema/validate-multi-field-unique-constraint!
              db :user {:nonexistent "X"} constraint field-specs nil)
            (is false "should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :validation-error/constraint-check-failed (:type (ex-data e))))
              (is (= :nonexistent (:missing-field (ex-data e))))
              (is (contains? (set (:available-fields (ex-data e))) :first-name)))))
        (finally
          (sp/close storage))))))


;; === field-type->datomic edge cases ===

(deftest field-type->datomic-edge-cases-test
  (testing "handles :fn type (defaults to string)"
    (is (= :db.type/string (schema/field-type->datomic {:type :fn}))))

  (testing "handles nil type (defaults to string)"
    (is (= :db.type/string (schema/field-type->datomic {:type nil}))))

  (testing "handles :any type (defaults to string)"
    (is (= :db.type/string (schema/field-type->datomic {:type :any})))))


;; === Constraint validation with regular (non-ref) fields ===

(deftest validate-multi-field-constraint-regular-fields-test
  (testing "handles three-field constraint"
    (let [storage (create-test-storage)
          s (-> (mds/create-builder)
                (ds/add-entity :event #uuid "00000000-0000-0000-0000-000000000040"
                               {:year {:uuid #uuid "00000000-0000-0000-0000-000000000041"
                                       :type :int}
                                :month {:uuid #uuid "00000000-0000-0000-0000-000000000042"
                                        :type :int}
                                :day {:uuid #uuid "00000000-0000-0000-0000-000000000043"
                                      :type :int}
                                :name {:uuid #uuid "00000000-0000-0000-0000-000000000044"
                                       :type :text}})
                (ds/add-constraint :event {:type :unique :fields [:year :month :day]})
                ds/build)]
      (try
        (sp/initialize storage s)
        ;; Create first event
        (sp/create-entity storage :event {:id (random-uuid)
                                          :year 2024
                                          :month 1
                                          :day 15
                                          :name "Meeting"})
        ;; Same date should fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :event {:id (random-uuid)
                                                :year 2024
                                                :month 1
                                                :day 15
                                                :name "Other Event"})))
        ;; Different day should work
        (sp/create-entity storage :event {:id (random-uuid)
                                          :year 2024
                                          :month 1
                                          :day 16
                                          :name "Another Meeting"})
        (finally
          (sp/close storage))))))


;; === build-field-schema additional tests ===

(deftest build-field-schema-additional-test
  (testing "handles union type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :data {:type :union})]
      (is (= :db.type/string (:db/valueType result)))))

  (testing "handles int type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :age {:type :int})]
      (is (= :db.type/long (:db/valueType result)))))

  (testing "handles numeric type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :balance {:type :numeric})]
      (is (= :db.type/bigdec (:db/valueType result)))))

  (testing "handles timestamptz type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :created-at {:type :timestamptz})]
      (is (= :db.type/instant (:db/valueType result)))))

  (testing "handles bytes type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :avatar {:type :bytes})]
      (is (= :db.type/bytes (:db/valueType result)))))

  (testing "handles jsonb type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :metadata {:type :jsonb})]
      (is (= :db.type/string (:db/valueType result)))))

  (testing "handles bool type"
    (let [s (mock-schema [])
          result (schema/build-field-schema s :user :active {:type :bool})]
      (is (= :db.type/boolean (:db/valueType result))))))


;; === Constraint with partial data ===

(deftest constraint-partial-data-test
  (testing "constraint not checked when one field is nil"
    (let [storage (create-test-storage)
          s (make-test-schema :constraints [{:type :unique :fields [:first-name :last-name]}])]
      (try
        (sp/initialize storage s)
        ;; Create user with both fields
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"
                                         :email "john@test.com"})
        ;; Create another user with only first-name (last-name missing)
        ;; This should work because constraint only applies when all fields present
        (let [conn-atom (:conn-atom storage)
              conn @conn-atom
              db (d/db conn)
              constraint {:type :unique :fields [:first-name :last-name]}
              field-specs {:first-name {:type :text}
                           :last-name {:type :text}}]
          ;; Should return nil (pass) because last-name is nil
          (is (nil? (schema/validate-multi-field-unique-constraint!
                      db :user {:first-name "John"} constraint field-specs nil))))
        (finally
          (sp/close storage))))))


;; === Multiple multi-field constraints ===

(deftest multiple-multi-field-constraints-test
  (testing "validates multiple constraints independently"
    (let [storage (create-test-storage)
          s (-> (mds/create-builder)
                (ds/add-entity :product #uuid "00000000-0000-0000-0000-000000000050"
                               {:sku {:uuid #uuid "00000000-0000-0000-0000-000000000051"
                                      :type :text}
                                :name {:uuid #uuid "00000000-0000-0000-0000-000000000052"
                                       :type :text}
                                :category {:uuid #uuid "00000000-0000-0000-0000-000000000053"
                                           :type :text}
                                :brand {:uuid #uuid "00000000-0000-0000-0000-000000000054"
                                        :type :text}})
                (ds/add-constraint :product {:type :unique :fields [:sku :category]})
                (ds/add-constraint :product {:type :unique :fields [:name :brand]})
                ds/build)]
      (try
        (sp/initialize storage s)
        ;; Create first product
        (sp/create-entity storage :product {:id (random-uuid)
                                            :sku "ABC123"
                                            :name "Widget"
                                            :category "Electronics"
                                            :brand "Acme"})
        ;; Same sku+category should fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :product {:id (random-uuid)
                                                  :sku "ABC123"
                                                  :name "Different"
                                                  :category "Electronics"
                                                  :brand "Other"})))
        ;; Same name+brand should also fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :product {:id (random-uuid)
                                                  :sku "XYZ789"
                                                  :name "Widget"
                                                  :category "Different"
                                                  :brand "Acme"})))
        ;; All different should work
        (sp/create-entity storage :product {:id (random-uuid)
                                            :sku "XYZ789"
                                            :name "Gadget"
                                            :category "Electronics"
                                            :brand "BrandX"})
        (finally
          (sp/close storage))))))
