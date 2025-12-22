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
          (is (= {:entity :account :field :name} (get (:fields metadata) field-uuid))))
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
