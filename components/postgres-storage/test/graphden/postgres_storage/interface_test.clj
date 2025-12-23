(ns graphden.postgres-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc :as jdbc])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(defn- clean-database!
  "Drops all user-created objects in public schema."
  [container]
  (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
        username (PostgreSQLContainer/.getUsername container)
        password (PostgreSQLContainer/.getPassword container)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      ;; Drop all tables
      (let [tables (jdbc/execute! conn ["SELECT tablename FROM pg_tables WHERE schemaname = 'public'"])]
        (doseq [{:pg_tables/keys [tablename]} tables]
          (jdbc/execute! conn [(str "DROP TABLE IF EXISTS \"" tablename "\" CASCADE")])))
      ;; Drop all enum types
      (let [enums (jdbc/execute! conn
                                 ["SELECT t.typname FROM pg_type t
                                    JOIN pg_namespace n ON n.oid = t.typnamespace
                                    WHERE n.nspname = 'public' AND t.typtype = 'e'"])]
        (doseq [{:pg_type/keys [typname]} enums]
          (jdbc/execute! conn [(str "DROP TYPE IF EXISTS \"" typname "\" CASCADE")]))))))


(defn with-postgres-container
  [f]
  (let [container (PostgreSQLContainer. "postgres:16-alpine")]
    (PostgreSQLContainer/.start container)
    (try
      (binding [*container* container]
        (f))
      (finally
        (PostgreSQLContainer/.stop container)))))


(defn with-clean-database
  [f]
  (clean-database! *container*)
  (f))


(use-fixtures :once with-postgres-container)
(use-fixtures :each with-clean-database)


(defn- create-test-storage
  "Creates a test storage with a clean database."
  []
  (clean-database! *container*)
  (pg/create-storage {:jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
                      :username (PostgreSQLContainer/.getUsername *container*)
                      :password (PostgreSQLContainer/.getPassword *container*)
                      :pool-size 2}))


;; === Test helpers ===

(defn- make-schema
  "Creates a simple schema with one entity and one enum for testing."
  [& {:keys [entity-name entity-uuid fields enum-name enum-uuid enum-values]
      :or {entity-name :user
           entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
           fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                          :type :text}}}}]
  (cond-> (mds/create-builder)
    (and enum-name enum-uuid enum-values)
    (ds/add-enum enum-name enum-uuid enum-values)

    true
    (ds/add-entity entity-name entity-uuid fields)

    true
    ds/build))


;; === First-time initialization tests ===

(deftest first-initialization-test
  (testing "initializing empty storage creates entities"
    (let [storage (create-test-storage)
          schema (make-schema)
          changes (sp/initialize storage schema)]
      (try
        (is (= [:user] (:created (:entities changes))))
        (is (= {} (:renamed (:entities changes))))
        (is (= #{{:entity :user :field :name}} (set (:created (:fields changes)))))
        (is (= [] (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "initializing with enum creates enum"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :test-entity
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000000100"
                              :fields {:val {:uuid #uuid "00000000-0000-0000-0000-000000000101"
                                             :type :text}}
                              :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}
                                            {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :value :inactive}])
          changes (sp/initialize storage schema)]
      (try
        (is (= [:status] (:created (:enums changes))))
        (is (= {} (:renamed (:enums changes))))
        (is (= #{{:enum :status :value :active}
                 {:enum :status :value :inactive}}
               (set (:created (:enum-values changes)))))
        (finally
          (sp/close storage))))))


;; === StorageIntrospection tests ===

(deftest introspection-test
  (testing "current-entities returns entity names"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :person
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000000200")]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-entities storage) :person))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns field definitions"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :profile
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000000300"
                              :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000301"
                                              :type :text}
                                       :email {:uuid #uuid "00000000-0000-0000-0000-000000000302"
                                               :type :text
                                               :nullable? true}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :profile)]
          (is (= :text (:type (get fields :name))))
          (is (false? (:nullable? (get fields :name))))
          (is (= :text (:type (get fields :email))))
          (is (true? (:nullable? (get fields :email)))))
        (finally
          (sp/close storage)))))

  (testing "current-enums returns enum names"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :item
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000000400"
                              :fields {:val {:uuid #uuid "00000000-0000-0000-0000-000000000401"
                                             :type :text}}
                              :enum-name :priority
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000410"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000411"
                                             :value :high}])]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-enums storage) :priority))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns uuid mappings"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000500"
          field-uuid #uuid "00000000-0000-0000-0000-000000000501"
          schema (make-schema :entity-name :account
                              :entity-uuid entity-uuid
                              :fields {:name {:uuid field-uuid :type :text}})]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)]
          (is (= :account (get (:entities metadata) entity-uuid)))
          (is (= {:entity :account :field :name :type :text :nullable? false}
                 (get (:fields metadata) field-uuid))))
        (finally
          (sp/close storage))))))


;; === Adding new entities/fields tests ===

(deftest adding-test
  (testing "adding new entity"
    (let [storage (create-test-storage)
          schema1 (make-schema :entity-name :customer
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000000600"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000601"
                                               :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :customer #uuid "00000000-0000-0000-0000-000000000600"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000601"
                                             :type :text}})
                      (ds/add-entity :order #uuid "00000000-0000-0000-0000-000000000620"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000621"
                                              :type :text}})
                      ds/build)
          changes (sp/initialize storage schema2)]
      (try
        (is (= [:order] (:created (:entities changes))))
        (is (= #{{:entity :order :field :title}} (set (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "adding new field to existing entity"
    (let [storage (create-test-storage)
          schema1 (make-schema :entity-name :product
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000000700"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000701"
                                               :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :product
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000000700"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000701"
                                               :type :text}
                                        :price {:uuid #uuid "00000000-0000-0000-0000-000000000702"
                                                :type :numeric}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= [{:entity :product :field :price}] (:created (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum value"
    (let [storage (create-test-storage)
          schema1 (make-schema :entity-name :task
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000000800"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000801"
                                               :type :text}}
                               :enum-name :task-status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000810"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000811"
                                              :value :pending}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :task
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000000800"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000801"
                                               :type :text}}
                               :enum-name :task-status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000810"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000811"
                                              :value :pending}
                                             {:uuid #uuid "00000000-0000-0000-0000-000000000812"
                                              :value :done}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= [{:enum :task-status :value :done}] (:created (:enum-values changes))))
        (finally
          (sp/close storage))))))


;; === Renaming tests ===

(deftest renaming-test
  (testing "renaming entity (same UUID, different name)"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000900"
          field-uuid #uuid "00000000-0000-0000-0000-000000000901"
          schema1 (make-schema :entity-name :old-name
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :new-name
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= {:old-name :new-name} (:renamed (:entities changes))))
        (is (contains? (sp/current-entities storage) :new-name))
        (finally
          (sp/close storage)))))

  (testing "renaming field (same UUID, different name)"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000001000"
          field-uuid #uuid "00000000-0000-0000-0000-000000001001"
          schema1 (make-schema :entity-name :record
                               :entity-uuid entity-uuid
                               :fields {:old-field {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :record
                               :entity-uuid entity-uuid
                               :fields {:new-field {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:fields changes))))
        (is (= [{:entity :record :old-field :old-field :new-field :new-field}]
               (:renamed (:fields changes))))
        (is (contains? (sp/current-fields storage :record) :new-field))
        (finally
          (sp/close storage))))))


;; === Destructive changes tests (should throw) ===

(deftest destructive-changes-test
  (testing "removing entity throws"
    (let [storage (create-test-storage)
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :keep #uuid "00000000-0000-0000-0000-000000001100"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000001101"
                                             :type :text}})
                      (ds/add-entity :remove-me #uuid "00000000-0000-0000-0000-000000001120"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000001121"
                                              :type :text}})
                      ds/build)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :keep
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001100"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001101"
                                               :type :text}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing field throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :entity-name :doc
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001200"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001201"
                                               :type :text}
                                        :content {:uuid #uuid "00000000-0000-0000-0000-000000001202"
                                                  :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :doc
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001200"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001201"
                                               :type :text}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Type change tests ===

(deftest type-change-test
  (testing "safe type widening (int->numeric) is allowed"
    (let [storage (create-test-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000001301"
          schema1 (make-schema :entity-name :counter
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001300"
                               :fields {:count {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :counter
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001300"
                               :fields {:count {:uuid field-uuid :type :numeric}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:fields changes))))
        (is (= :numeric (:type (get (sp/current-fields storage :counter) :count))))
        (finally
          (sp/close storage)))))

  (testing "unsafe type narrowing (text->int) throws"
    (let [storage (create-test-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000001401"
          schema1 (make-schema :entity-name :data
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001400"
                               :fields {:value {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :data
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001400"
                               :fields {:value {:uuid field-uuid :type :int}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: incompatible type change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === More destructive changes tests ===

(deftest destructive-enum-changes-test
  (testing "removing enum throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :entity-name :item
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001600"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001601"
                                               :type :text}}
                               :enum-name :color
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000001610"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000001611"
                                              :value :red}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :item
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001600"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001601"
                                               :type :text}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum value throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :entity-name :widget
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001700"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001701"
                                               :type :text}}
                               :enum-name :size
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000001710"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000001711"
                                              :value :small}
                                             {:uuid #uuid "00000000-0000-0000-0000-000000001712"
                                              :value :large}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :widget
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001700"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001701"
                                               :type :text}}
                               :enum-name :size
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000001710"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000001711"
                                              :value :small}])]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


(deftest enum-renaming-test
  (testing "renaming enum (same UUID, different name)"
    (let [storage (create-test-storage)
          enum-uuid #uuid "00000000-0000-0000-0000-000000001810"
          value-uuid #uuid "00000000-0000-0000-0000-000000001811"
          schema1 (make-schema :entity-name :thing
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001800"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001801"
                                               :type :text}}
                               :enum-name :old-enum
                               :enum-uuid enum-uuid
                               :enum-values [{:uuid value-uuid :value :val1}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :thing
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000001800"
                               :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001801"
                                               :type :text}}
                               :enum-name :new-enum
                               :enum-uuid enum-uuid
                               :enum-values [{:uuid value-uuid :value :val1}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= {:old-enum :new-enum} (:renamed (:enums changes))))
        (is (contains? (sp/current-enums storage) :new-enum))
        (finally
          (sp/close storage))))))


(deftest introspection-edge-cases-test
  (testing "current-enum-values returns nil for unknown enum"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :obj
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000001900"
                              :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001901"
                                              :type :text}})]
      (try
        (sp/initialize storage schema)
        (is (nil? (sp/current-enum-values storage :nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns nil for unknown entity"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :existing
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002000"
                              :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000002001"
                                              :type :text}})]
      (try
        (sp/initialize storage schema)
        (is (nil? (sp/current-fields storage :nonexistent)))
        (finally
          (sp/close storage))))))


;; === Additional field types tests ===

(deftest field-types-test
  (testing "bytes field type"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :binary-data
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002100"
                              :fields {:data {:uuid #uuid "00000000-0000-0000-0000-000000002101"
                                              :type :bytes}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :binary-data)]
          (is (= :bytes (:type (get fields :data)))))
        (finally
          (sp/close storage)))))

  (testing "bool field type"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :flags
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002200"
                              :fields {:active {:uuid #uuid "00000000-0000-0000-0000-000000002201"
                                                :type :bool}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :flags)]
          (is (= :bool (:type (get fields :active)))))
        (finally
          (sp/close storage)))))

  (testing "timestamptz field type"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :events
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002300"
                              :fields {:created-at {:uuid #uuid "00000000-0000-0000-0000-000000002301"
                                                    :type :timestamptz}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :events)]
          (is (= :timestamptz (:type (get fields :created-at)))))
        (finally
          (sp/close storage)))))

  (testing "jsonb field type"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :documents
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002400"
                              :fields {:payload {:uuid #uuid "00000000-0000-0000-0000-000000002401"
                                                 :type :jsonb}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :documents)]
          (is (= :jsonb (:type (get fields :payload)))))
        (finally
          (sp/close storage)))))

  (testing "uuid field type"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :refs
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002500"
                              :fields {:external-id {:uuid #uuid "00000000-0000-0000-0000-000000002501"
                                                     :type :uuid}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :refs)]
          (is (= :uuid (:type (get fields :external-id)))))
        (finally
          (sp/close storage)))))

  (testing "int field type"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :counters
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000002600"
                              :fields {:count {:uuid #uuid "00000000-0000-0000-0000-000000002601"
                                               :type :int}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :counters)]
          (is (= :int (:type (get fields :count)))))
        (finally
          (sp/close storage)))))

  (testing "ref field type (stored as UUID)"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :parent #uuid "00000000-0000-0000-0000-000000002700"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000002701"
                                            :type :text}})
                     (ds/add-entity :child #uuid "00000000-0000-0000-0000-000000002710"
                                    {:parent-id {:uuid #uuid "00000000-0000-0000-0000-000000002711"
                                                 :type :ref
                                                 :ref-entity :parent}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :child)]
          ;; ref is stored as UUID in PostgreSQL, but returns logical type
          (is (= :ref (:type (get fields :parent-id)))))
        (finally
          (sp/close storage)))))

  (testing "enum field type"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000002810"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000002811"
                                    :value :pending}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000002812"
                                    :value :done}])
                     (ds/add-entity :task #uuid "00000000-0000-0000-0000-000000002800"
                                    {:status {:uuid #uuid "00000000-0000-0000-0000-000000002801"
                                              :type :enum
                                              :enum-name :status}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :task)]
          (is (= :enum (:type (get fields :status)))))
        (finally
          (sp/close storage)))))

  (testing "union field type (stored as JSONB)"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :container #uuid "00000000-0000-0000-0000-000000002900"
                                    {:value {:uuid #uuid "00000000-0000-0000-0000-000000002901"
                                             :type :union
                                             :variants [{:type :text}
                                                        {:type :int}]}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :container)]
          ;; union is stored as JSONB in PostgreSQL, but returns logical type
          (is (= :union (:type (get fields :value)))))
        (finally
          (sp/close storage))))))


;; === Close tests ===

(deftest close-test
  (testing "close is idempotent"
    (let [storage (create-test-storage)
          schema (make-schema :entity-name :temp
                              :entity-uuid #uuid "00000000-0000-0000-0000-000000001500"
                              :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000001501"
                                              :type :text}})]
      (sp/initialize storage schema)
      ;; close should be idempotent - calling twice should not throw
      (is (nil? (sp/close storage)))
      (is (nil? (sp/close storage))))))


;; === Error path tests ===

(deftest create-storage-errors-test
  (testing "create-storage without jdbc-url throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (pg/create-storage {:username "user" :password "pass"})))))


(deftest nullable-change-test
  (testing "changing from nullable to non-nullable throws"
    (let [storage (create-test-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000003001"
          schema1 (make-schema :entity-name :nullable-test
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000003000"
                               :fields {:optional {:uuid field-uuid
                                                   :type :text
                                                   :nullable? true}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :nullable-test
                               :entity-uuid #uuid "00000000-0000-0000-0000-000000003000"
                               :fields {:optional {:uuid field-uuid
                                                   :type :text
                                                   :nullable? false}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"field changed from nullable to non-nullable"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


(deftest introspection-uninitialized-test
  (testing "introspection on uninitialized storage returns correct defaults"
    (let [storage (create-test-storage)]
      (try
        ;; Before initialize - should return empty/nil appropriately
        (is (set? (sp/current-entities storage)))
        (is (nil? (sp/schema-metadata storage)))
        (finally
          (sp/close storage))))))


(deftest kebab-case-enum-values-test
  (testing "enum values with hyphens are handled correctly"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-enum :my-status #uuid "00000000-0000-0000-0000-000000003110"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000003111"
                                    :value :in-progress}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000003112"
                                    :value :on-hold}])
                     (ds/add-entity :ticket #uuid "00000000-0000-0000-0000-000000003100"
                                    {:title {:uuid #uuid "00000000-0000-0000-0000-000000003101"
                                             :type :text}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; Verify kebab-case enum values are preserved through round-trip
        (let [values (sp/current-enum-values storage :my-status)]
          (is (contains? values :in-progress))
          (is (contains? values :on-hold)))
        (finally
          (sp/close storage))))))


(deftest snake-case-collision-test
  (testing "snake_case naming collision is detected for entities"
    (let [storage (create-test-storage)]
      (try
        ;; :user-name and :user_name both become user_name
        (let [schema (-> (mds/create-builder)
                         (ds/add-entity :user-name #uuid "00000000-0000-0000-0000-000000004001"
                                        {:field1 {:uuid #uuid "00000000-0000-0000-0000-000000004002"
                                                  :type :text}})
                         (ds/add-entity :user_name #uuid "00000000-0000-0000-0000-000000004003"
                                        {:field2 {:uuid #uuid "00000000-0000-0000-0000-000000004004"
                                                  :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage)))))

  (testing "snake_case naming collision is detected for fields"
    (let [storage (create-test-storage)]
      (try
        ;; :first-name and :first_name both become first_name
        (let [schema (-> (mds/create-builder)
                         (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000004010"
                                        {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000004011"
                                                      :type :text}
                                         :first_name {:uuid #uuid "00000000-0000-0000-0000-000000004012"
                                                      :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage)))))

  (testing "snake_case naming collision is detected for enums"
    (let [storage (create-test-storage)]
      (try
        (let [schema (-> (mds/create-builder)
                         (ds/add-enum :my-status #uuid "00000000-0000-0000-0000-000000004020"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004021"
                                        :value :active}])
                         (ds/add-enum :my_status #uuid "00000000-0000-0000-0000-000000004022"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004023"
                                        :value :inactive}])
                         (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000004030"
                                        {:name {:uuid #uuid "00000000-0000-0000-0000-000000004031"
                                                :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage))))))


(defn- insert-orphaned-metadata!
  "Insert an orphaned metadata entry directly into the database.
   Uses a fresh connection to ensure the insert is committed."
  [kind entry-name parent-uuid extra]
  (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
        username (PostgreSQLContainer/.getUsername *container*)
        password (PostgreSQLContainer/.getPassword *container*)
        orphan-uuid (random-uuid)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      (if extra
        (jdbc/execute! conn
                       [(str "INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid, extra) "
                             "VALUES (?, ?, ?, ?, ?::jsonb)")
                        orphan-uuid kind entry-name parent-uuid extra])
        (jdbc/execute! conn
                       ["INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid) VALUES (?, ?, ?, ?)"
                        orphan-uuid kind entry-name parent-uuid])))))


(deftest metadata-corruption-test
  (testing "orphaned field metadata throws in strict mode"
    (let [storage (create-test-storage)]
      (try
        ;; First initialize normally
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid #uuid "00000000-0000-0000-0000-000000005001"
                                  :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005002"
                                                  :type :text}})
              result (sp/initialize storage schema)]
          ;; Verify tables were created
          (is (some? result) "sp/initialize should return changes"))
        ;; Verify metadata table exists
        (is (contains? (sp/current-entities storage) :test-entity)
            "test-entity should exist after initialize")
        ;; Now insert an orphaned field entry (field with non-existent parent)
        (insert-orphaned-metadata! "field" "orphan_field" (random-uuid)
                                   "{\"type\": \"text\", \"nullable?\": false}")
        ;; Now try to initialize again - should throw because of orphaned entry
        (let [schema2 (make-schema :entity-name :test-entity
                                   :entity-uuid #uuid "00000000-0000-0000-0000-000000005001"
                                   :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005002"
                                                   :type :text}
                                            :email {:uuid #uuid "00000000-0000-0000-0000-000000005003"
                                                    :type :text}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Orphaned field entry"
                (sp/initialize storage schema2))))
        (finally
          (sp/close storage)))))

  (testing "orphaned enum-value metadata throws in strict mode"
    (let [storage (create-test-storage)]
      (try
        ;; First initialize with an enum
        (let [schema (-> (mds/create-builder)
                         (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000005010"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000005011"
                                        :value :active}])
                         (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000005020"
                                        {:name {:uuid #uuid "00000000-0000-0000-0000-000000005021"
                                                :type :text}})
                         ds/build)]
          (sp/initialize storage schema))
        ;; Insert an orphaned enum-value entry
        (insert-orphaned-metadata! "enum-value" "orphan_value" (random-uuid) nil)
        ;; Try to initialize again - should throw
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000005010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000005011"
                                         :value :active}
                                        {:uuid #uuid "00000000-0000-0000-0000-000000005012"
                                         :value :inactive}])
                          (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000005020"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000005021"
                                                 :type :text}})
                          ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Orphaned enum-value entry"
                (sp/initialize storage schema2))))
        (finally
          (sp/close storage))))))
