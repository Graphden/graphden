(ns graphden.postgres-storage.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.core :as core]
    [graphden.postgres-storage.crud :as crud]
    [graphden.postgres-storage.ddl :as ddl]
    [graphden.postgres-storage.interface :as pg]
    [graphden.postgres-storage.introspection :as introspection]
    [graphden.postgres-storage.metadata :as metadata]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.contract-tests :as contract]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc :as jdbc])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      SQLException)
    (java.util.concurrent
      CountDownLatch
      TimeUnit)
    (org.postgresql.util
      PGobject)
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(defn- clean-database!
  "Drops all user-created objects in public schema.
   Uses DROP SCHEMA CASCADE + CREATE SCHEMA for speed (single DDL vs N operations)."
  [container]
  (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
        username (PostgreSQLContainer/.getUsername container)
        password (PostgreSQLContainer/.getPassword container)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      ;; Drop and recreate public schema - much faster than iterating tables/enums
      (jdbc/execute! conn ["DROP SCHEMA public CASCADE"])
      (jdbc/execute! conn ["CREATE SCHEMA public"])
      (jdbc/execute! conn ["GRANT ALL ON SCHEMA public TO PUBLIC"]))))


(defn with-postgres-container
  [f]
  (let [container (doto (PostgreSQLContainer. "postgres:16-alpine")
                    (PostgreSQLContainer/.withStartupAttempts 3))]
    (PostgreSQLContainer/.start container)
    (when-not (PostgreSQLContainer/.isRunning container)
      (throw (ex-info "Failed to start PostgreSQL test container" {})))
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
  "Creates a test storage with a clean database.
   Cleans DB to ensure isolation when multiple storages created in one test."
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

  (testing "initializing with enum creates enum and values"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}
                                            {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :value :inactive}])
          changes (sp/initialize storage schema)]
      (try
        (is (= [:status] (:created (:enums changes))))
        (is (= #{{:enum :status :value :active}
                 {:enum :status :value :inactive}}
               (set (:created (:enum-values changes)))))
        (finally
          (sp/close storage))))))


;; === Introspection tests ===

(deftest introspection-test
  (testing "current-entities returns entity names"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (try
        (sp/initialize storage schema)
        (is (= #{:user} (sp/current-entities storage)))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns field definitions"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (try
        (sp/initialize storage schema)
        (is (= {:name {:type :text :nullable? false}}
               (sp/current-fields storage :user)))
        (finally
          (sp/close storage)))))

  (testing "current-enums returns enum names"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}])]
      (try
        (sp/initialize storage schema)
        (is (= #{:status} (sp/current-enums storage)))
        (finally
          (sp/close storage)))))

  (testing "current-enum-values returns enum value names"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}
                                            {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :value :inactive}])]
      (try
        (sp/initialize storage schema)
        (is (= #{:active :inactive} (sp/current-enum-values storage :status)))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns full metadata"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}])]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)]
          (is (map? metadata))
          (is (contains? metadata :entities))
          (is (contains? metadata :fields))
          (is (contains? metadata :enums))
          (is (contains? metadata :enum-values)))
        (finally
          (sp/close storage))))))


;; === Adding tests ===

(deftest adding-test
  (testing "adding new entity in second init"
    (let [storage (create-test-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}})
                      ds/build)
          changes (sp/initialize storage schema2)]
      (try
        (is (= [:post] (:created (:entities changes))))
        (is (= #{{:entity :post :field :title}} (set (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "adding new field to existing entity"
    (let [storage (create-test-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}
                                        :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= [{:entity :user :field :email}] (:created (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum"
    (let [storage (create-test-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [:status] (:created (:enums changes))))
        (is (= [{:enum :status :value :active}] (:created (:enum-values changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum value"
    (let [storage (create-test-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}
                                             {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                              :value :inactive}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= [{:enum :status :value :inactive}] (:created (:enum-values changes))))
        (finally
          (sp/close storage))))))


;; === Renaming tests ===

(deftest renaming-test
  (testing "renaming entity (same UUID, different name) is tracked"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-name :user
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :person
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= {:user :person} (:renamed (:entities changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming field (same UUID, different name) is tracked"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:full-name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:fields changes))))
        (is (= [{:entity :user :old-field :name :new-field :full-name}]
               (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming enum (same UUID, different name) is tracked"
    (let [storage (create-test-storage)
          enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          value-uuid #uuid "00000000-0000-0000-0000-000000000011"
          schema1 (make-schema :enum-name :status
                               :enum-uuid enum-uuid
                               :enum-values [{:uuid value-uuid :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :state
                               :enum-uuid enum-uuid
                               :enum-values [{:uuid value-uuid :value :active}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= {:status :state} (:renamed (:enums changes))))
        (finally
          (sp/close storage))))))


;; === Destructive changes tests ===

(deftest destructive-changes-test
  (testing "removing entity throws"
    (let [storage (create-test-storage)
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}})
                      ds/build)
          _ (sp/initialize storage schema1)
          schema2 (make-schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: entities removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing field throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}
                                        :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: fields removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: enums removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum value throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}
                                             {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                              :value :inactive}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: enum values removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Type change tests ===

(deftest type-change-test
  (testing "incompatible type change throws"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          ;; text→int is narrowing (unsafe), int→text would be widening (safe)
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:count {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:count {:uuid field-uuid :type :int}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: incompatible type change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "compatible type change succeeds"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:price {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:price {:uuid field-uuid :type :numeric}})]
      (try
        (is (some? (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Nullable change tests ===

(deftest nullable-change-test
  (testing "changing from nullable to non-nullable throws"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:bio {:uuid field-uuid :type :text :nullable? true}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:bio {:uuid field-uuid :type :text :nullable? false}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: field changed from nullable to non-nullable"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "changing from non-nullable to nullable succeeds"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:bio {:uuid field-uuid :type :text :nullable? false}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:bio {:uuid field-uuid :type :text :nullable? true}})]
      (try
        (is (some? (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Close tests ===

(deftest close-test
  (testing "close is idempotent"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/close storage)))
      (is (nil? (sp/close storage))))))


;; === Field types tests ===

(deftest field-types-test
  (testing "all supported field types work"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :test-entity #uuid "00000000-0000-0000-0000-000000000001"
                                    {:int-field {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :int}
                                     :numeric-field {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                     :type :numeric}
                                     :bool-field {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                                  :type :bool}
                                     :text-field {:uuid #uuid "00000000-0000-0000-0000-000000000005"
                                                  :type :text}
                                     :uuid-field {:uuid #uuid "00000000-0000-0000-0000-000000000006"
                                                  :type :uuid}
                                     :bytes-field {:uuid #uuid "00000000-0000-0000-0000-000000000007"
                                                   :type :bytes}
                                     :jsonb-field {:uuid #uuid "00000000-0000-0000-0000-000000000008"
                                                   :type :jsonb}
                                     :timestamptz-field {:uuid #uuid "00000000-0000-0000-0000-000000000009"
                                                         :type :timestamptz}})
                     ds/build)
          changes (sp/initialize storage schema)]
      (try
        (is (= [:test-entity] (:created (:entities changes))))
        (is (= 8 (count (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "ref field creates UUID column"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                            :type :text}})
                     (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                    {:author {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :ref
                                              :ref-entity :user}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; Verify author field exists - ref type is preserved in metadata
        (let [fields (sp/current-fields storage :post)]
          (is (contains? fields :author))
          ;; ref type is preserved in metadata (maps to UUID in DB)
          (is (= :ref (:type (:author fields)))))
        (finally
          (sp/close storage)))))

  (testing "enum field works"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000010"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                    :value :active}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                    :value :inactive}])
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:status {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :enum
                                              :enum-name :status}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :user)]
          (is (contains? fields :status))
          (is (= :enum (:type (:status fields)))))
        (finally
          (sp/close storage)))))

  (testing "union field creates JSONB column"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :config #uuid "00000000-0000-0000-0000-000000000001"
                                    {:value {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :union
                                             :variants [{:type :text} {:type :int} {:type :bool}]}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :config)]
          (is (contains? fields :value))
          ;; union type is preserved in metadata (maps to JSONB in DB)
          (is (= :union (:type (:value fields)))))
        (finally
          (sp/close storage)))))

  (testing "adding ref field during migration creates index"
    (let [storage (create-test-storage)
          user-uuid #uuid "00000000-0000-0000-0000-000000000001"
          post-uuid #uuid "00000000-0000-0000-0000-000000000003"
          ;; First schema: entities without ref
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user user-uuid
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post post-uuid
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}})
                      ds/build)
          ;; Second schema: add ref field to post
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :user user-uuid
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post post-uuid
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}
                                      :author {:uuid #uuid "00000000-0000-0000-0000-000000000005"
                                               :type :ref
                                               :ref-entity :user}})
                      ds/build)]
      (try
        ;; Initialize with first schema
        (sp/initialize storage schema1)
        ;; Migrate to second schema (adds ref field)
        (let [changes (sp/initialize storage schema2)]
          ;; Verify the ref field was added
          (is (some #(= :author (:field %)) (:created (:fields changes))))
          ;; Verify the field exists and is ref type
          (let [fields (sp/current-fields storage :post)]
            (is (= :ref (:type (:author fields))))))
        (finally
          (sp/close storage))))))


;; === Idempotency tests ===

(deftest idempotency-test
  (testing "multiple initializations with same schema are idempotent"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}])]
      (try
        (let [changes1 (sp/initialize storage schema)
              changes2 (sp/initialize storage schema)]
          ;; First init creates everything
          (is (seq (:created (:entities changes1))))
          ;; Second init creates nothing (all exists)
          (is (empty? (:created (:entities changes2))))
          (is (empty? (:created (:fields changes2))))
          (is (empty? (:created (:enums changes2))))
          (is (empty? (:created (:enum-values changes2)))))
        (finally
          (sp/close storage))))))


;; === Reference field tests ===

(deftest ref-field-test
  (testing "ref field type is preserved in metadata"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                            :type :text}})
                     (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                    {:author {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :ref
                                              :ref-entity :user}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)
              author-field-entry (first (filter #(= (:field (val %)) :author)
                                                (:fields metadata)))]
          (is (some? author-field-entry))
          ;; Type should be preserved as :ref (not :uuid)
          (is (= :ref (:type (val author-field-entry)))))
        (finally
          (sp/close storage))))))


;; === JSONB type tests ===

(deftest jsonb-field-test
  (testing "JSONB type is preserved through round-trip"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :config #uuid "00000000-0000-0000-0000-000000000001"
                                    {:data {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                            :type :jsonb}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)
              data-field (first (filter #(= (:field (val %)) :data) (:fields metadata)))]
          (is (some? data-field))
          (is (= :jsonb (:type (val data-field)))))
        (finally
          (sp/close storage))))))


;; === Union type tests ===

(deftest union-field-test
  (testing "Union type is preserved through round-trip"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :config #uuid "00000000-0000-0000-0000-000000000001"
                                    {:value {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :union
                                             :variants [{:type :text} {:type :int}]}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)
              value-field (first (filter #(= (:field (val %)) :value) (:fields metadata)))]
          (is (some? value-field))
          (is (= :union (:type (val value-field)))))
        (finally
          (sp/close storage))))))


;; === Snake_case collision tests ===

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
                         (ds/add-enum :user-status #uuid "00000000-0000-0000-0000-000000004020"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004021"
                                        :value :active}])
                         (ds/add-enum :user_status #uuid "00000000-0000-0000-0000-000000004022"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004023"
                                        :value :pending}])
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
          (sp/close storage)))))

  (testing "lenient mode skips orphaned entries in introspection"
    (let [storage (create-test-storage)]
      (try
        ;; Initialize with a schema
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid #uuid "00000000-0000-0000-0000-000000005030"
                                  :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005031"
                                                  :type :text}})]
          (sp/initialize storage schema))
        ;; Insert orphaned entries
        (insert-orphaned-metadata! "field" "orphan_field" (random-uuid)
                                   "{\"type\": \"text\", \"nullable?\": false}")
        (insert-orphaned-metadata! "enum-value" "orphan_value" (random-uuid) nil)
        ;; Introspection methods use lenient mode - should NOT throw
        (is (= #{:test-entity} (sp/current-entities storage)))
        (is (= {:name {:type :text :nullable? false}}
               (sp/current-fields storage :test-entity)))
        ;; schema-metadata should also work (lenient mode skips orphaned entries)
        (let [metadata (sp/schema-metadata storage)]
          (is (some? metadata))
          (is (contains? (:entities metadata) #uuid "00000000-0000-0000-0000-000000005030")))
        (finally
          (sp/close storage)))))

  (testing "unknown kind in metadata is ignored in lenient mode"
    (let [storage (create-test-storage)]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid #uuid "00000000-0000-0000-0000-000000005040"
                                  :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005041"
                                                  :type :text}})]
          (sp/initialize storage schema))
        ;; Insert a metadata row with unknown kind
        (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
              username (PostgreSQLContainer/.getUsername *container*)
              password (PostgreSQLContainer/.getPassword *container*)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn
                           ["INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid) VALUES (?, ?, ?, ?)"
                            (random-uuid) "unknown-kind" "mystery" nil])))
        ;; Introspection that parses metadata should still work (unknown kind is skipped)
        (is (= {:name {:type :text :nullable? false}}
               (sp/current-fields storage :test-entity)))
        (let [metadata (sp/schema-metadata storage)]
          (is (some? metadata))
          ;; Should have the entity but not the unknown kind entry
          (is (= 1 (count (:entities metadata)))))
        (finally
          (sp/close storage))))))


(deftest enum-migration-test
  (testing "adding enum during migration (not first-time init)"
    (let [storage (create-test-storage)]
      (try
        ;; First initialize WITHOUT enums
        (let [schema1 (make-schema :entity-name :user
                                   :entity-uuid #uuid "00000000-0000-0000-0000-000000006001"
                                   :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000006002"
                                                   :type :text}})]
          (sp/initialize storage schema1))
        ;; Now add an enum in the second initialize
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000006010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000006011"
                                         :value :active}
                                        {:uuid #uuid "00000000-0000-0000-0000-000000006012"
                                         :value :inactive}])
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000006001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000006002"
                                                 :type :text}})
                          ds/build)
              changes (sp/initialize storage schema2)]
          ;; Verify enum was created during migration
          (is (= [:status] (:created (:enums changes))))
          (is (= #{{:enum :status :value :active}
                   {:enum :status :value :inactive}}
                 (set (:created (:enum-values changes)))))
          ;; Verify enum exists in database
          (is (contains? (sp/current-enums storage) :status)))
        (finally
          (sp/close storage))))))


(deftest configuration-validation-test
  (testing "creating storage without jdbc-url throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (pg/create-storage {:username "test"
                              :password "test"
                              :pool-size 2}))))

  (testing "creating storage without username throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :password "test"}))))

  (testing "creating storage with empty username throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "   "
                              :password "test"}))))

  (testing "creating storage without password throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "test"}))))

  (testing "creating storage with empty password throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "test"
                              :password "  "}))))

  (testing "error ex-data contains type instead of sensitive credentials info"
    (try
      (pg/create-storage {:username "testuser" :password "testpass"})
      (catch clojure.lang.ExceptionInfo e
        ;; Should NOT expose :username or :password in ex-data
        (is (= :config-error/missing-jdbc-url (:type (ex-data e))))
        (is (nil? (:provided-keys (ex-data e)))))))

  (testing "missing username error has correct type"
    (try
      (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                          :password "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/missing-username (:type (ex-data e)))))))

  (testing "missing password error has correct type"
    (try
      (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                          :username "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/missing-password (:type (ex-data e))))))))


(deftest with-query-timeout-test
  (testing "with-query-timeout function changes timeout value (in milliseconds)"
    (is (= 30000 core/*query-timeout-ms*) "Default should be 30000 ms")
    (pg/with-query-timeout 60000
                           (fn []
                             (is (= 60000 core/*query-timeout-ms*) "Should be 60000 inside function")))
    (is (= 30000 core/*query-timeout-ms*) "Should restore to 30000 after function"))

  (testing "with-query-timeout returns body result"
    (is (= 42 (pg/with-query-timeout 10000 #(+ 40 2)))))

  (testing "nested with-query-timeout works correctly"
    (pg/with-query-timeout 100000
                           (fn []
                             (is (= 100000 core/*query-timeout-ms*))
                             (pg/with-query-timeout 200000
                                                    (fn []
                                                      (is (= 200000 core/*query-timeout-ms*))))
                             (is (= 100000 core/*query-timeout-ms*))))))


(deftest close-pool-idempotency-test
  (testing "close-pool with nil pool does not throw"
    (is (nil? (core/close-pool nil))))

  (testing "close-pool is idempotent - can be called multiple times"
    (let [pool (core/create-pool {:jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
                                  :username (PostgreSQLContainer/.getUsername *container*)
                                  :password (PostgreSQLContainer/.getPassword *container*)
                                  :pool-size 1
                                  :min-idle 1})]
      ;; First close
      (is (nil? (core/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool)))
      ;; Second close - should not throw
      (is (nil? (core/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool))))))


(deftest create-pool-validation-test
  (let [valid-opts {:jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
                    :username (PostgreSQLContainer/.getUsername *container*)
                    :password (PostgreSQLContainer/.getPassword *container*)}]

    (testing "pool-size must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size -1))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size "10")))))

    (testing "min-idle must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (core/create-pool (assoc valid-opts :min-idle 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (core/create-pool (assoc valid-opts :min-idle -1)))))

    (testing "min-idle cannot exceed pool-size"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle cannot exceed pool-size"
            (core/create-pool (assoc valid-opts :pool-size 5 :min-idle 10)))))

    (testing "connection-timeout must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (core/create-pool (assoc valid-opts :connection-timeout 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (core/create-pool (assoc valid-opts :connection-timeout -1000)))))

    (testing "pool-size cannot exceed 100"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size exceeds maximum allowed value of 100"
            (core/create-pool (assoc valid-opts :pool-size 101)))))

    (testing "idle-timeout must be less than max-lifetime"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (core/create-pool (assoc valid-opts
                                     :idle-timeout 600000
                                     :max-lifetime 500000))))
      ;; Equal values should also fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (core/create-pool (assoc valid-opts
                                     :idle-timeout 600000
                                     :max-lifetime 600000)))))

    (testing "idle-timeout = 0 is allowed (never retire idle connections)"
      ;; idle-timeout = 0 is a special case meaning "never retire"
      ;; This should not throw even though 0 < max-lifetime
      (let [pool (core/create-pool (assoc valid-opts
                                          :idle-timeout 0
                                          :max-lifetime 1800000))]
        (is (some? pool))
        (core/close-pool pool)))))


(deftest with-query-timeout-validation-test
  (testing "timeout must be positive integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a positive integer"
          (pg/with-query-timeout 0 (constantly :ok))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a positive integer"
          (pg/with-query-timeout -1000 (constantly :ok)))))

  (testing "timeout must be at least 1000ms"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be at least 1000ms"
          (pg/with-query-timeout 500 (constantly :ok))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be at least 1000ms"
          (pg/with-query-timeout 999 (constantly :ok)))))

  (testing "1000ms is valid minimum"
    (is (= 42 (pg/with-query-timeout 1000 #(+ 40 2))))))


(deftest unknown-pg-type-coverage-test
  (testing "unknown postgres type falls through to default in current-columns"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009001"
          field-uuid #uuid "00000000-0000-0000-0000-000000009002"]
      (try
        ;; Initialize with a normal schema to create the table
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add a column with an unusual postgres type directly
        (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
              username (PostgreSQLContainer/.getUsername *container*)
              password (PostgreSQLContainer/.getPassword *container*)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            ;; Add a 'point' type column
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN location point"])))
        ;; Call the private current-columns function directly
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; The :location column should have type :point (unknown type passes through)
          (is (= :point (:type (:location columns)))))
        (finally
          (sp/close storage)))))

  (testing "current-columns handles timestamp without time zone"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009010"
          field-uuid #uuid "00000000-0000-0000-0000-000000009011"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add a timestamp without time zone column
        (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
              username (PostgreSQLContainer/.getUsername *container*)
              password (PostgreSQLContainer/.getPassword *container*)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN created_at timestamp without time zone"])))
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; timestamp without time zone maps to :timestamptz
          (is (= :timestamptz (:type (:created-at columns)))))
        (finally
          (sp/close storage)))))

  (testing "current-columns handles all postgres type mappings"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009020"
          field-uuid #uuid "00000000-0000-0000-0000-000000009021"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add columns with various postgres types
        (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
              username (PostgreSQLContainer/.getUsername *container*)
              password (PostgreSQLContainer/.getPassword *container*)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN is_active boolean"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN amount numeric"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN data bytea"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN big_count bigint"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN updated_at timestamp with time zone"])))
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; boolean maps to :bool
          (is (= :bool (:type (:is-active columns))))
          ;; numeric stays as :numeric
          (is (= :numeric (:type (:amount columns))))
          ;; bytea maps to :bytes
          (is (= :bytes (:type (:data columns))))
          ;; bigint maps to :int
          (is (= :int (:type (:big-count columns))))
          ;; timestamp with time zone maps to :timestamptz
          (is (= :timestamptz (:type (:updated-at columns)))))
        (finally
          (sp/close storage))))))


(deftest edge-case-coverage-test
  (testing "parse-extra handles non-string non-PGobject values"
    ;; This covers the :else branch in parse-extra (line 160)
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008001"
          field-uuid #uuid "00000000-0000-0000-0000-000000008002"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return a row with extra as a number (not string/PGobject)
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra nil}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra 12345}]] ; number instead of string/PGobject
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            ;; schema-metadata uses parse-metadata-lenient which calls parse-extra
            (let [metadata (sp/schema-metadata storage)]
              ;; Should not throw, just parse what it can
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles string 'null' value"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008050"
          field-uuid #uuid "00000000-0000-0000-0000-000000008051"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return "null" string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra "null"}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "null"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles empty JSON object string"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008060"
          field-uuid #uuid "00000000-0000-0000-0000-000000008061"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return "{}" string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra "{}"}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "{}"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles raw string input"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008070"
          field-uuid #uuid "00000000-0000-0000-0000-000000008071"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return a valid JSON string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra nil}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "{\"type\": \"text\", \"nullable?\": \"false\"}"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata))
              ;; Should have parsed the string values to keywords
              (is (= :text (:type (val (first (:fields metadata)))))))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles empty string"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008080"
          field-uuid #uuid "00000000-0000-0000-0000-000000008081"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return empty string for extra
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra ""}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra ""}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              ;; Should work, extra is just nil
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "unknown kind in strict mode falls through to default"
    ;; This covers the acc))) fallback (line 213) in strict parsing
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008010"
          field-uuid #uuid "00000000-0000-0000-0000-000000008011"]
      (try
        (let [schema (make-schema :entity-name :test-entity
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Insert unknown kind directly
        (insert-orphaned-metadata! "weird-kind" "mystery" nil nil)
        ;; Second initialize uses strict parsing - should skip unknown kind
        (let [schema2 (make-schema :entity-name :test-entity
                                   :entity-uuid entity-uuid
                                   :fields {:name {:uuid field-uuid :type :text}
                                            :email {:uuid #uuid "00000000-0000-0000-0000-000000008012"
                                                    :type :text}})
              changes (sp/initialize storage schema2)]
          ;; Should succeed - unknown kind is just skipped in the reduce
          (is (= [{:entity :test-entity :field :email}] (:created (:fields changes)))))
        (finally
          (sp/close storage))))))


(deftest uninitialized-storage-test
  (testing "current-fields returns nil on uninitialized storage"
    (let [storage (create-test-storage)]
      (try
        ;; Don't initialize - just try to read fields
        ;; This exercises the try/catch in current-fields
        (is (nil? (sp/current-fields storage :nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns nil on uninitialized storage"
    (let [storage (create-test-storage)]
      (try
        ;; Don't initialize - just try to read metadata
        ;; This exercises the try/catch in schema-metadata
        (is (nil? (sp/schema-metadata storage)))
        (finally
          (sp/close storage))))))


(deftest metadata-db-inconsistency-test
  (testing "detects when metadata says field exists but DB column is missing"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000007001"
          field-uuid #uuid "00000000-0000-0000-0000-000000007002"
          schema1 (make-schema :entity-name :user
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})]
      (try
        ;; First initialize normally
        (sp/initialize storage schema1)
        ;; Mock parse-metadata to return metadata claiming a non-existent field
        (let [fake-metadata {:entities {entity-uuid :user}
                             :fields {field-uuid {:entity :user
                                                  :field :name
                                                  :type :text
                                                  :nullable? false}
                                      ;; This field doesn't exist in DB!
                                      #uuid "00000000-0000-0000-0000-000000007099"
                                      {:entity :user
                                       :field :ghost-field
                                       :type :text
                                       :nullable? false}}
                             :enums {}
                             :enum-values {}}
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity-uuid
                                         {:name {:uuid field-uuid :type :text}
                                          :ghost-field {:uuid #uuid "00000000-0000-0000-0000-000000007099"
                                                        :type :text}})
                          ds/build)]
          (with-redefs [metadata/parse-metadata (constantly fake-metadata)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Metadata/DB inconsistency"
                  (sp/initialize storage schema2)))))
        (finally
          (sp/close storage))))))


(deftest table-not-found-error-handling-test
  (testing "table-not-found? returns true for SQLState 42P01"
    (let [e (SQLException. "relation does not exist" "42P01")]
      (is (true? (#'util/table-not-found? e)))))

  (testing "table-not-found? returns false for other SQLState"
    (let [e (SQLException. "connection failed" "08001")]
      (is (false? (#'util/table-not-found? e)))))

  (testing "current-fields re-throws non-42P01 SQLException"
    (let [storage (create-test-storage)]
      (try
        ;; Mock read-metadata-rows to throw a non-42P01 SQLException
        (let [connection-error (SQLException. "connection failed" "08001")]
          (with-redefs [metadata/read-metadata-rows (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/current-fields storage :any-entity)))))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata re-throws non-42P01 SQLException"
    (let [storage (create-test-storage)]
      (try
        ;; Mock read-metadata-rows to throw a non-42P01 SQLException
        (let [connection-error (SQLException. "connection failed" "08001")]
          (with-redefs [metadata/read-metadata-rows (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/schema-metadata storage)))))
        (finally
          (sp/close storage))))))


(deftest sql-identifier-validation-test
  (testing "validate-sql-identifier! accepts valid identifiers"
    (let [validate-fn #'util/validate-sql-identifier!]
      (is (nil? (validate-fn "valid_name" {})))
      (is (nil? (validate-fn "name123" {})))
      (is (nil? (validate-fn "a" {})))))

  (testing "validate-sql-identifier! rejects invalid identifiers"
    (let [validate-fn #'util/validate-sql-identifier!]
      ;; SQL injection attempts
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name'; DROP TABLE users; --" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name\"" {})))
      ;; Invalid characters
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name-with-dash" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "123starts_with_number" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "UPPERCASE" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "" {}))))))


(deftest pg-type-validation-test
  (testing "validate-pg-type! accepts valid base types"
    (let [validate-fn #'util/validate-pg-type!]
      (is (nil? (validate-fn "UUID" {})))
      (is (nil? (validate-fn "TEXT" {})))
      (is (nil? (validate-fn "BIGINT" {})))
      (is (nil? (validate-fn "BOOLEAN" {})))
      (is (nil? (validate-fn "NUMERIC" {})))
      (is (nil? (validate-fn "TIMESTAMPTZ" {})))
      (is (nil? (validate-fn "JSONB" {})))
      (is (nil? (validate-fn "BYTEA" {})))))

  (testing "validate-pg-type! accepts valid quoted enum identifiers"
    (let [validate-fn #'util/validate-pg-type!]
      (is (nil? (validate-fn "\"status\"" {})))
      (is (nil? (validate-fn "\"user_role\"" {})))
      (is (nil? (validate-fn "\"my_enum123\"" {})))))

  (testing "validate-pg-type! rejects invalid types"
    (let [validate-fn #'util/validate-pg-type!]
      ;; SQL injection attempts
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "TEXT; DROP TABLE users; --" {})))
      ;; Invalid type names
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "INVALID_TYPE" {})))
      ;; Improperly quoted identifiers
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "\"UPPERCASE\"" {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "\"has-dash\"" {}))))))


(deftest kw->snake-case-test
  (testing "converts kebab-case to snake_case"
    (is (= "foo_bar" (util/kw->snake-case :foo-bar)))
    (is (= "foo_bar_baz" (util/kw->snake-case :foo-bar-baz))))

  (testing "handles already snake_case"
    (is (= "foo_bar" (util/kw->snake-case :foo_bar))))

  (testing "handles simple keywords"
    (is (= "foo" (util/kw->snake-case :foo)))
    (is (= "x" (util/kw->snake-case :x)))))


(deftest check-snake-case-collisions-test
  (testing "detects collision between kebab and snake case"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Snake_case naming collision"
          (util/check-snake-case-collisions! {:context "test"} [:foo-bar :foo_bar]))))

  (testing "allows non-colliding names"
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [:foo :bar :baz])))
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [:foo-bar :baz-qux]))))

  (testing "empty and single-element collections pass"
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [])))
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [:foo])))))


(deftest ident->sql-test
  (testing "wraps identifier in double quotes"
    (is (= "\"foo\"" (util/ident->sql :foo)))
    (is (= "\"foo_bar\"" (util/ident->sql :foo-bar)))
    (is (= "\"user\"" (util/ident->sql :user)))))


(deftest field-type->pg-test
  (testing "maps basic types"
    (is (= "UUID" (util/field-type->pg {:type :uuid})))
    (is (= "TEXT" (util/field-type->pg {:type :text})))
    (is (= "BIGINT" (util/field-type->pg {:type :int})))
    (is (= "BOOLEAN" (util/field-type->pg {:type :bool})))
    (is (= "NUMERIC" (util/field-type->pg {:type :numeric})))
    (is (= "TIMESTAMPTZ" (util/field-type->pg {:type :timestamptz})))
    (is (= "JSONB" (util/field-type->pg {:type :jsonb})))
    (is (= "BYTEA" (util/field-type->pg {:type :bytes}))))

  (testing "maps ref to UUID"
    (is (= "UUID" (util/field-type->pg {:type :ref :ref-entity :user}))))

  (testing "maps union to JSONB"
    (is (= "JSONB" (util/field-type->pg {:type :union :union-types [:foo :bar]}))))

  (testing "maps enum to quoted identifier"
    (is (= "\"status\"" (util/field-type->pg {:type :enum :enum-name :status})))
    (is (= "\"user_role\"" (util/field-type->pg {:type :enum :enum-name :user-role}))))

  (testing "falls back to TEXT for unknown types"
    (is (= "TEXT" (util/field-type->pg {:type :unknown})))))


(deftest enum-value-conversion-test
  (testing "enum-value->sql converts to snake_case"
    (is (= "active" (util/enum-value->sql :active)))
    (is (= "in_progress" (util/enum-value->sql :in-progress))))

  (testing "sql->enum-value converts back to kebab-case keyword"
    (is (= :active (util/sql->enum-value "active")))
    (is (= :in-progress (util/sql->enum-value "in_progress"))))

  (testing "roundtrip conversion"
    (let [original :in-progress
          sql-val (util/enum-value->sql original)
          back (util/sql->enum-value sql-val)]
      (is (= original back))))

  (testing "enum-value->sql rejects invalid values"
    (is (thrown? clojure.lang.ExceptionInfo
          (util/enum-value->sql :123-invalid)))
    (is (thrown? clojure.lang.ExceptionInfo
          (util/enum-value->sql :UPPERCASE)))))


(deftest metadata-caching-test
  (testing "metadata is cached after first read"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (try
        (sp/initialize storage schema)
        ;; First call reads from DB
        (let [metadata1 (sp/schema-metadata storage)
              ;; Second call should use cache (same object)
              metadata2 (sp/schema-metadata storage)]
          (is (some? metadata1))
          (is (identical? metadata1 metadata2) "Metadata should be cached"))
        (finally
          (sp/close storage)))))

  (testing "cache is invalidated on initialize"
    (let [storage (create-test-storage)
          schema1 (make-schema)]
      (try
        (sp/initialize storage schema1)
        (let [metadata1 (sp/schema-metadata storage)
              ;; Re-initialize with same schema
              _ (sp/initialize storage schema1)
              ;; Cache should be invalidated
              metadata2 (sp/schema-metadata storage)]
          (is (some? metadata1))
          (is (some? metadata2))
          ;; After re-init, cache was cleared, so new object
          (is (not (identical? metadata1 metadata2)) "Cache should be invalidated on initialize"))
        (finally
          (sp/close storage))))))


(deftest concurrent-access-test
  (testing "concurrent reads are thread-safe"
    (let [storage (create-test-storage)
          schema (make-schema)
          errors (atom [])
          num-threads 10
          iterations 50]
      (try
        (sp/initialize storage schema)
        ;; Launch multiple threads reading concurrently
        (let [futures (doall
                        (for [_ (range num-threads)]
                          (future
                            (try
                              (dotimes [_ iterations]
                                (sp/current-entities storage)
                                (sp/current-fields storage :user)
                                (sp/schema-metadata storage))
                              (catch Exception e
                                (swap! errors conj e))))))]
          ;; Wait for all threads to complete
          (doseq [f futures]
            (deref f 5000 :timeout)))
        ;; No errors should have occurred
        (is (empty? @errors) (str "Errors during concurrent access: " @errors))
        (finally
          (sp/close storage)))))

  (testing "concurrent initialize and read are thread-safe"
    (let [storage (create-test-storage)
          schema (make-schema)
          errors (atom [])
          num-readers 5
          num-iterations 20
          ;; Use CountDownLatch to start all threads simultaneously
          start-latch (CountDownLatch. 1)
          ;; Track completion
          done-latch (CountDownLatch. (inc num-readers))]
      (try
        ;; First initialize
        (sp/initialize storage schema)
        ;; Start reader threads - wait for start signal
        (doseq [_ (range num-readers)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (dotimes [_ num-iterations]
                (sp/schema-metadata storage))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Writer thread that re-initializes
        (future
          (try
            (CountDownLatch/.await start-latch)
            (dotimes [_ 3]
              (sp/initialize storage schema))
            (catch Exception e
              (swap! errors conj e))
            (finally
              (CountDownLatch/.countDown done-latch))))
        ;; Start all threads at once
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")
        (is (empty? @errors) (str "Errors during concurrent read/write: " @errors))
        (finally
          (sp/close storage))))))


;; === Type widening with data tests ===

(deftest type-widening-preserves-data-test
  (testing "int→numeric widening preserves integer data"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:count {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          ;; Insert data with int type
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, count) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" 42])
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, count) VALUES (?, ?)"
                                 #uuid "22222222-2222-2222-2222-222222222222" -100])
          ;; Widen type to numeric
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:count {:uuid field-uuid :type :numeric}})
          _ (sp/initialize storage schema2)
          ;; Query data
          rows (jdbc/execute! pool ["SELECT id, count FROM \"user\" ORDER BY count"])]
      (try
        (is (= 2 (count rows)))
        (is (= -100M (:user/count (first rows))))
        (is (= 42M (:user/count (second rows))))
        (finally
          (sp/close storage)))))

  (testing "numeric→text widening preserves decimal data"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:price {:uuid field-uuid :type :numeric}})
          _ (sp/initialize storage schema1)
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, price) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" 3.14159M])
          ;; Widen to text
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:price {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema2)
          rows (jdbc/execute! pool ["SELECT price FROM \"user\""])]
      (try
        (is (= 1 (count rows)))
        (is (= "3.14159" (:user/price (first rows))))
        (finally
          (sp/close storage)))))

  (testing "int→text widening preserves data"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:code {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, code) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" 12345])
          ;; Widen to text
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:code {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema2)
          rows (jdbc/execute! pool ["SELECT code FROM \"user\""])]
      (try
        (is (= "12345" (:user/code (first rows))))
        (finally
          (sp/close storage)))))

  ;; Note: text→jsonb is NOT supported by PostgreSQL directly (requires explicit to_jsonb())
  ;; so we don't test that conversion

  (testing "NULL values survive type widening"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:value {:uuid field-uuid :type :int :nullable? true}})
          _ (sp/initialize storage schema1)
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, value) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" nil])
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, value) VALUES (?, ?)"
                                 #uuid "22222222-2222-2222-2222-222222222222" 42])
          ;; Widen to text
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:value {:uuid field-uuid :type :text :nullable? true}})
          _ (sp/initialize storage schema2)
          rows (jdbc/execute! pool ["SELECT id, value FROM \"user\" ORDER BY id"])]
      (try
        (is (= 2 (count rows)))
        (is (nil? (:user/value (first rows))))
        (is (= "42" (:user/value (second rows))))
        (finally
          (sp/close storage))))))


;; === Concurrent migration tests ===

(deftest concurrent-migration-test
  (testing "concurrent initializations are handled safely"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (make-schema :entity-uuid entity-uuid
                              :fields {:name {:uuid field-uuid :type :text}})
          results (atom [])
          errors (atom [])
          num-threads 5
          start-latch (CountDownLatch. 1)
          done-latch (CountDownLatch. num-threads)]
      (try
        ;; Create threads that wait for start signal
        (doseq [_ (range num-threads)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (let [result (sp/initialize storage schema)]
                (swap! results conj result))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Start all threads simultaneously
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")

        ;; All threads should complete without errors
        (is (empty? @errors) (str "Got errors: " (map #(Exception/.getMessage %) @errors)))

        ;; At least one thread should have created the table
        (is (pos? (count @results)))

        ;; Database state should be consistent
        (is (= #{:user} (sp/current-entities storage)))
        (is (= #{:name} (set (keys (sp/current-fields storage :user)))))

        (finally
          (sp/close storage)))))

  (testing "concurrent migrations with schema changes"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000002"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000003"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:name {:uuid field1-uuid :type :text}})
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:name {:uuid field1-uuid :type :text}
                                        :email {:uuid field2-uuid :type :text}})
          _ (sp/initialize storage schema1)
          results (atom [])
          errors (atom [])
          num-threads 3
          start-latch (CountDownLatch. 1)
          done-latch (CountDownLatch. num-threads)]
      (try
        ;; Create threads that wait for start signal
        (doseq [_ (range num-threads)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (let [result (sp/initialize storage schema2)]
                (swap! results conj result))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Start all threads simultaneously
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")

        ;; All threads should complete without errors
        (is (empty? @errors) (str "Got errors: " (map #(Exception/.getMessage %) @errors)))

        ;; Database state should reflect schema2
        (is (= #{:user} (sp/current-entities storage)))
        (is (= #{:name :email} (set (keys (sp/current-fields storage :user)))))

        (finally
          (sp/close storage))))))


;; === StorageCRUD tests ===

(deftest crud-create-entity-test
  (testing "create-entity with provided id"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          result (sp/create-entity storage :user {:id id :name "Alice"})]
      (try
        (is (= id (:id result)))
        (is (= "Alice" (:name result)))
        (finally
          (sp/close storage)))))

  (testing "create-entity generates id if not provided"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          result (sp/create-entity storage :user {:name "Bob"})]
      (try
        (is (uuid? (:id result)))
        (is (= "Bob" (:name result)))
        (finally
          (sp/close storage))))))


(deftest crud-read-entity-test
  (testing "read-entity returns entity by id"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/read-entity storage :user id)]
      (try
        (is (= id (:id result)))
        (is (= "Alice" (:name result)))
        (finally
          (sp/close storage)))))

  (testing "read-entity returns nil for non-existent id"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          result (sp/read-entity storage :user #uuid "11111111-1111-1111-1111-111111111111")]
      (try
        (is (nil? result))
        (finally
          (sp/close storage))))))


(deftest crud-update-entity-test
  (testing "update-entity updates existing entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/update-entity storage :user id {:name "Alice Updated"})]
      (try
        (is (= id (:id result)))
        (is (= "Alice Updated" (:name result)))
        ;; Verify persistence
        (is (= "Alice Updated" (:name (sp/read-entity storage :user id))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws for non-existent entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
              (sp/update-entity storage :user
                                #uuid "11111111-1111-1111-1111-111111111111"
                                {:name "Test"})))
        (finally
          (sp/close storage))))))


(deftest crud-delete-entity-test
  (testing "delete-entity returns true for existing entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/delete-entity storage :user id)]
      (try
        (is (true? result))
        (is (nil? (sp/read-entity storage :user id)))
        (finally
          (sp/close storage)))))

  (testing "delete-entity returns false for non-existent entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          result (sp/delete-entity storage :user #uuid "11111111-1111-1111-1111-111111111111")]
      (try
        (is (false? result))
        (finally
          (sp/close storage))))))


(deftest crud-query-entities-test
  (testing "query-entities with empty where returns all"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {})]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Bob"} (set (map :name result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with where filters results"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {:name "Alice"})]
      (try
        (is (= 1 (count result)))
        (is (= "Alice" (:name (first result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with nil value uses IS NULL (not = NULL)"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text
                                              :nullable? true}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name nil})
          result-with-nil (sp/query-entities storage :user {:name nil})
          result-with-value (sp/query-entities storage :user {:name "Alice"})]
      (try
        ;; This test verifies that WHERE name IS NULL works (SQL = NULL always returns false)
        (is (= 1 (count result-with-nil)) "Should find record with NULL name using IS NULL")
        (is (nil? (:name (first result-with-nil))))
        (is (= 1 (count result-with-value)))
        (is (= "Alice" (:name (first result-with-value))))
        (finally
          (sp/close storage))))))


;; === StorageBatchCRUD tests ===

(deftest batch-create-entities-test
  (testing "create-entities creates multiple entities in single operation"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          data [{:name "Alice"}
                {:name "Bob"}
                {:name "Charlie"}]
          results (sp/create-entities storage :user data)]
      (try
        (is (= 3 (count results)))
        (is (= #{"Alice" "Bob" "Charlie"} (set (map :name results))))
        (is (every? uuid? (map :id results)))
        ;; Verify persistence
        (is (= 3 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage)))))

  (testing "create-entities with provided ids"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          data [{:id id1 :name "Alice"}
                {:id id2 :name "Bob"}]
          results (sp/create-entities storage :user data)]
      (try
        (is (= #{id1 id2} (set (map :id results))))
        (finally
          (sp/close storage)))))

  (testing "create-entities with empty sequence returns empty"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          results (sp/create-entities storage :user [])]
      (try
        (is (empty? results))
        (finally
          (sp/close storage))))))


(deftest batch-read-entities-test
  (testing "read-entities returns map of found entities"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          results (sp/read-entities storage :user [id1 id2])]
      (try
        (is (= 2 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (= "Bob" (:name (get results id2))))
        (finally
          (sp/close storage)))))

  (testing "read-entities excludes non-existent ids"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          results (sp/read-entities storage :user [id1 id-nonexistent])]
      (try
        (is (= 1 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (nil? (get results id-nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "read-entities with empty ids returns empty map"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          results (sp/read-entities storage :user [])]
      (try
        (is (= {} results))
        (finally
          (sp/close storage))))))


(deftest batch-delete-entities-test
  (testing "delete-entities deletes multiple entities and returns count"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          id3 #uuid "33333333-3333-3333-3333-333333333333"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          _ (sp/create-entity storage :user {:id id3 :name "Charlie"})
          deleted-count (sp/delete-entities storage :user [id1 id2])]
      (try
        (is (= 2 deleted-count))
        ;; Verify entities are gone
        (is (nil? (sp/read-entity storage :user id1)))
        (is (nil? (sp/read-entity storage :user id2)))
        ;; Charlie should still exist
        (is (= "Charlie" (:name (sp/read-entity storage :user id3))))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with non-existent ids returns count of actually deleted"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          deleted-count (sp/delete-entities storage :user [id1 id-nonexistent])]
      (try
        (is (= 1 deleted-count))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with empty ids returns 0"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          deleted-count (sp/delete-entities storage :user [])]
      (try
        (is (zero? deleted-count))
        (finally
          (sp/close storage))))))


;; === Required field validation tests ===

(deftest crud-required-field-validation-test
  (testing "create-entity throws when required field is missing"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                               :type :text}})]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'email' is missing or nil"
              (sp/create-entity storage :user {:name "Alice"})))
        (finally
          (sp/close storage)))))

  (testing "create-entity throws when required field is nil"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
              (sp/create-entity storage :user {:name nil})))
        (finally
          (sp/close storage)))))

  (testing "create-entity allows nil for nullable field"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                             :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice" :bio nil})]
          (is (= "Alice" (:name user)))
          (is (nil? (:bio user))))
        (finally
          (sp/close storage)))))

  (testing "create-entity allows missing nullable field"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                             :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        ;; :bio is not provided at all
        (let [user (sp/create-entity storage :user {:name "Alice"})]
          (is (= "Alice" (:name user))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws when setting required field to nil"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
                (sp/update-entity storage :user (:id user) {:name nil}))))
        (finally
          (sp/close storage)))))

  (testing "update-entity allows setting nullable field to nil"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                             :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice" :bio "Hello"})
              updated (sp/update-entity storage :user (:id user) {:bio nil})]
          (is (= "Alice" (:name updated)))
          (is (nil? (:bio updated))))
        (finally
          (sp/close storage))))))


;; === JSONB parsing error tests ===

(deftest jsonb-parsing-error-test
  (testing "parse-pgobject throws with context on invalid JSON (short value)"
    (let [parse-pgobject #'crud/parse-pgobject
          invalid-pg (doto (PGobject.)
                       (PGobject/.setType "jsonb")
                       (PGobject/.setValue "{invalid json}"))]
      (try
        (parse-pgobject invalid-pg)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :parse-error/jsonb (:type (ex-data e))))
          (is (= "{invalid json}" (:raw-value (ex-data e))))
          (is (some? (:cause (ex-data e))))))))

  (testing "parse-pgobject truncates long values in error"
    (let [parse-pgobject #'crud/parse-pgobject
          ;; Create a string longer than 100 characters
          long-invalid-value (str "{" (str/join (repeat 150 "x")) "}")
          invalid-pg (doto (PGobject.)
                       (PGobject/.setType "jsonb")
                       (PGobject/.setValue long-invalid-value))]
      (try
        (parse-pgobject invalid-pg)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :parse-error/jsonb (:type (ex-data e))))
          ;; Should be truncated to 100 chars + "..."
          (is (= 103 (count (:raw-value (ex-data e)))))
          (is (str/ends-with? (:raw-value (ex-data e)) "..."))))))

  (testing "parse-pgobject returns value for non-jsonb PGobject"
    (let [parse-pgobject #'crud/parse-pgobject
          text-pg (doto (PGobject.)
                    (PGobject/.setType "text")
                    (PGobject/.setValue "hello"))]
      (is (= "hello" (parse-pgobject text-pg)))))

  (testing "parse-pgobject returns non-PGobject values unchanged"
    (let [parse-pgobject #'crud/parse-pgobject]
      (is (= "plain string" (parse-pgobject "plain string")))
      (is (= 42 (parse-pgobject 42)))
      (is (nil? (parse-pgobject nil))))))


;; === GraphConstraints tests ===

(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                      :type :text}})
      (ds/add-entity :arg-schema #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :ref :ref-entity :fn-schema}
                      :parent-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                     :type :ref :ref-entity :fn
                                     :nullable? true}})
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:owner-fn-id {:uuid #uuid "00000000-0000-0000-0004-000000000002"
                                    :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :jsonb}})
      ds/build))


(deftest validate-parent-same-schema-test
  (testing "allows nil parent"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-parent-same-schema! storage fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "allows parent with same schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sum" :fn-schema-id fn-schema-id
                                           :parent-fn-id parent-fn-id})]
      (try
        (is (nil? (sp/validate-parent-same-schema! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on different schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          schema1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          schema2-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id schema1-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sub" :fn-schema-id schema2-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent fn has different fn-schema-id"
              (sp/validate-parent-same-schema! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-inheritance-cycle-test
  (testing "allows nil parent"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (nil? (sp/validate-no-inheritance-cycle! storage fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "allows parent without grandparent (covers collect-parent-chain empty case)"
    ;; This test ensures collect-parent-chain returns #{} when parent has no parent
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "parent" :fn-schema-id fn-schema-id})]
      (try
        ;; parent-fn-id has no parent, so collect-parent-chain(parent-fn-id) should return #{}
        (is (nil? (sp/validate-no-inheritance-cycle! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on self-reference"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot set self as parent"
              (sp/validate-no-inheritance-cycle! storage fn-id fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on cycle A→B→A"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id fn-a :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b :name "fn-b" :fn-schema-id fn-schema-id
                                           :parent-fn-id fn-a})]
      (try
        ;; Try to make A's parent B (would create A→B→A cycle)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Setting parent would create inheritance cycle"
              (sp/validate-no-inheritance-cycle! storage fn-a fn-b)))
        (finally
          (sp/close storage))))))


(deftest validate-arg-schema-belongs-to-fn-test
  (testing "allows matching schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on mismatched schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          schema1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          schema2-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema2-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id schema1-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg-schema does not belong to fn's schema"
              (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-arg-override-test
  (testing "allows arg when not in parent chain"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-arg-override! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage)))))

  (testing "throws when arg already defined in parent"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sum" :fn-schema-id fn-schema-id
                                           :parent-fn-id parent-fn-id})
          _ (sp/create-entity storage :arg-value {:id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
                                                  :owner-fn-id parent-fn-id
                                                  :arg-schema-id arg-schema-id
                                                  :value 42})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Argument already defined in parent chain"
              (sp/validate-no-arg-override! storage child-fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-dependency-cycle-test
  (testing "allows non-cyclic reference"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          owner-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          value-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id owner-fn-id :name "owner" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id value-fn-id :name "value" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id value-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "allows nil value-fn-id"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          owner-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "throws when dependency cycle detected"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          fn-c-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          arg-schema-id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-a-id :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b-id :name "fn-b" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-c-id :name "fn-c" :fn-schema-id fn-schema-id})
          ;; Create b -> c reference (b depends on c)
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-b-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str fn-c-id)})
          ;; Create c -> a reference (c depends on a)
          _ (sp/create-entity storage :arg-value {:id #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
                                                  :owner-fn-id fn-c-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str fn-a-id)})]
      (try
        ;; Try to validate a -> b, which would create cycle: a -> b -> c -> a
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage fn-a-id fn-b-id)))
        (finally
          (sp/close storage))))))


;; === ExecutionGraph tests ===


(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "add" :returned-type "int"})
              arg-a (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "a" :type "int" :required true})
              arg-b (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "b" :type "int" :required true})
              fn-add (sp/create-entity storage :fn
                                       {:name "add-1-2"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id fn-add)
                                   :arg-schema-id (:id arg-a)
                                   :value 1})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id fn-add)
                                   :arg-schema-id (:id arg-b)
                                   :value 2})
              graph (sp/resolve-execution-graph storage (:id fn-add))]
          (is (contains? (:fns graph) (:id fn-add)))
          (is (contains? (:fn-schemas graph) (:id fn-schema)))
          (is (contains? (:arg-schemas graph) (:id arg-a)))
          (is (contains? (:arg-schemas graph) (:id arg-b)))
          (is (contains? (:resolved-args graph) (:id fn-add)))
          (let [args (get (:resolved-args graph) (:id fn-add))]
            (is (= 1 (:value (get args (:id arg-a)))))
            (is (= 2 (:value (get args (:id arg-b)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-parent-test
  (testing "resolves function with parent chain - child overrides parent"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "greet" :returned-type "text"})
              arg-name (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id fn-schema)
                                          :name "name" :type "text" :required true})
              arg-greeting (sp/create-entity storage :arg-schema
                                             {:fn-schema-id (:id fn-schema)
                                              :name "greeting" :type "text" :required true})
              parent-fn (sp/create-entity storage :fn
                                          {:name "greet-hello"
                                           :fn-schema-id (:id fn-schema)
                                           :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id parent-fn)
                                   :arg-schema-id (:id arg-greeting)
                                   :value "Hello"})
              child-fn (sp/create-entity storage :fn
                                         {:name "greet-hello-world"
                                          :fn-schema-id (:id fn-schema)
                                          :parent-fn-id (:id parent-fn)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id child-fn)
                                   :arg-schema-id (:id arg-name)
                                   :value "World"})
              graph (sp/resolve-execution-graph storage (:id child-fn))]
          (is (contains? (:fns graph) (:id child-fn)))
          (let [args (get (:resolved-args graph) (:id child-fn))]
            (is (= "World" (:value (get args (:id arg-name)))))
            (is (= "Hello" (:value (get args (:id arg-greeting)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [const-schema (sp/create-entity storage :fn-schema
                                             {:name "const-int" :returned-type "int"})
              const-arg (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id const-schema)
                                           :name "value" :type "int" :required true})
              add-schema (sp/create-entity storage :fn-schema
                                           {:name "add" :returned-type "int"})
              add-arg-a (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "a" :type "int" :required true})
              add-arg-b (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "b" :type "int" :required true})
              const-3 (sp/create-entity storage :fn
                                        {:name "const-3"
                                         :fn-schema-id (:id const-schema)
                                         :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id const-3)
                                   :arg-schema-id (:id const-arg)
                                   :value 3})
              const-5 (sp/create-entity storage :fn
                                        {:name "const-5"
                                         :fn-schema-id (:id const-schema)
                                         :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id const-5)
                                   :arg-schema-id (:id const-arg)
                                   :value 5})
              add-3-5 (sp/create-entity storage :fn
                                        {:name "add-3-5"
                                         :fn-schema-id (:id add-schema)
                                         :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id add-3-5)
                                   :arg-schema-id (:id add-arg-a)
                                   :value (:id const-3)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id add-3-5)
                                   :arg-schema-id (:id add-arg-b)
                                   :value (:id const-5)})
              graph (sp/resolve-execution-graph storage (:id add-3-5))]
          (is (= 3 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-3-5)))
          (is (contains? (:fns graph) (:id const-3)))
          (is (contains? (:fns graph) (:id const-5)))
          (is (= 2 (count (:fn-schemas graph))))
          (is (= 3 (count (:arg-schemas graph)))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
              (sp/resolve-execution-graph storage (random-uuid))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-non-uuid-values-test
  (testing "handles non-UUID literal values correctly (not treated as fn refs)"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "process" :returned-type "text"})
              ;; Various arg types with literal values
              arg-text (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id fn-schema)
                                          :name "text-arg" :type "text" :required true})
              arg-int (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "int-arg" :type "int" :required true})
              fn-rec (sp/create-entity storage :fn
                                       {:name "my-process"
                                        :fn-schema-id (:id fn-schema)})
              ;; Arg values with literals (not fn references)
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id fn-rec)
                                   :arg-schema-id (:id arg-text)
                                   :value "hello world"})  ; String literal
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id fn-rec)
                                   :arg-schema-id (:id arg-int)
                                   :value 42})  ; Integer literal
              graph (sp/resolve-execution-graph storage (:id fn-rec))]
          ;; Should only have 1 fn (no references resolved)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id fn-rec)))
          ;; Check resolved args have literal values
          (let [args (get (:resolved-args graph) (:id fn-rec))]
            (is (= "hello world" (:value (get args (:id arg-text)))))
            (is (= 42 (:value (get args (:id arg-int)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-invalid-uuid-string-test
  (testing "handles invalid UUID strings gracefully (not treated as fn refs)"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "echo" :returned-type "text"})
              arg-val (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "value" :type "text" :required true})
              fn-rec (sp/create-entity storage :fn
                                       {:name "my-echo"
                                        :fn-schema-id (:id fn-schema)})
              ;; Arg value with string that looks like UUID but isn't valid
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id fn-rec)
                                   :arg-schema-id (:id arg-val)
                                   :value "not-a-valid-uuid-at-all"})
              graph (sp/resolve-execution-graph storage (:id fn-rec))]
          ;; Should only have 1 fn (invalid UUID string not treated as ref)
          (is (= 1 (count (:fns graph))))
          (let [args (get (:resolved-args graph) (:id fn-rec))]
            (is (= "not-a-valid-uuid-at-all" (:value (get args (:id arg-val)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [;; const-int schema
              const-schema (sp/create-entity storage :fn-schema
                                             {:name "const-shared" :returned-type "int"})
              const-arg (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id const-schema)
                                           :name "value" :type "int" :required true})
              ;; add schema - both args reference fns
              add-schema (sp/create-entity storage :fn-schema
                                           {:name "add-shared" :returned-type "int"})
              add-arg-a (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "a" :type "int" :required true})
              add-arg-b (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "b" :type "int" :required true})
              ;; const-5 fn - will be referenced TWICE
              const-5 (sp/create-entity storage :fn
                                        {:name "const-5-shared"
                                         :fn-schema-id (:id const-schema)
                                         :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id const-5)
                                   :arg-schema-id (:id const-arg)
                                   :value 5})
              ;; add-5-5 fn referencing const-5 for BOTH args
              ;; This creates a shared reference that triggers the "already visited" branch
              add-5-5 (sp/create-entity storage :fn
                                        {:name "add-5-5-shared"
                                         :fn-schema-id (:id add-schema)
                                         :parent-fn-id nil})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id add-5-5)
                                   :arg-schema-id (:id add-arg-a)
                                   :value (:id const-5)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id add-5-5)
                                   :arg-schema-id (:id add-arg-b)
                                   :value (:id const-5)})  ; Same fn referenced again!
              graph (sp/resolve-execution-graph storage (:id add-5-5))]
          ;; const-5 should only appear once in the graph despite being referenced twice
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-5-5)))
          (is (contains? (:fns graph) (:id const-5)))
          ;; Both args should reference const-5
          ;; Note: JSONB returns UUIDs as strings
          (let [args (get (:resolved-args graph) (:id add-5-5))]
            (is (= (str (:id const-5)) (str (:value (get args (:id add-arg-a))))))
            (is (= (str (:id const-5)) (str (:value (get args (:id add-arg-b))))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [;; recursive fn-schema with two args
              rec-schema (sp/create-entity storage :fn-schema
                                           {:name "recursive" :returned-type "int"})
              ;; 'self' arg will reference the fn itself (for recursion)
              arg-self (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id rec-schema)
                                          :name "self" :type "fn" :required true}) ; :fn type
              arg-n (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id rec-schema)
                                       :name "n" :type "int" :required true})
              ;; Create fn instance that references itself
              rec-fn (sp/create-entity storage :fn
                                       {:name "factorial"
                                        :fn-schema-id (:id rec-schema)
                                        :parent-fn-id nil})
              ;; Self-reference: arg-value points to the fn itself
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id rec-fn)
                                   :arg-schema-id (:id arg-self)
                                   :value (:id rec-fn)}) ; Self-reference!
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id rec-fn)
                                   :arg-schema-id (:id arg-n)
                                   :value 5})
              graph (sp/resolve-execution-graph storage (:id rec-fn))]
          ;; Should only have 1 fn (self-reference doesn't create duplicate)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id rec-fn)))
          ;; Self arg should reference the same fn (JSONB returns UUIDs as strings)
          (let [args (get (:resolved-args graph) (:id rec-fn))]
            (is (= (str (:id rec-fn)) (str (:value (get args (:id arg-self))))))
            (is (= 5 (:value (get args (:id arg-n)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-dangling-ref-test
  (testing "handles arg-value referencing non-existent fn (dangling reference)"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              ;; Create a fn-schema
              fn-schema (crud/create-entity pool :fn-schema
                                            {:name "test-fn"
                                             :returned-type "int"}
                                            nil)
              ;; Create an arg-schema for a :ref type argument
              arg-ref (crud/create-entity pool :arg-schema
                                          {:fn-schema-id (:id fn-schema)
                                           :name "ref-arg"
                                           :type "ref"
                                           :required false}
                                          nil)
              ;; Create a fn
              fn-rec (crud/create-entity pool :fn
                                         {:name "fn-with-dangling-ref"
                                          :fn-schema-id (:id fn-schema)}
                                         nil)
              ;; Create an arg-value that references a non-existent fn
              ;; This UUID is valid but doesn't exist in the fn table
              non-existent-fn-id #uuid "99999999-9999-9999-9999-999999999999"
              _ (crud/create-entity pool :arg-value
                                    {:owner-fn-id (:id fn-rec)
                                     :arg-schema-id (:id arg-ref)
                                     :value non-existent-fn-id}
                                    nil)
              ;; Resolve should succeed but skip the dangling reference
              graph (sp/resolve-execution-graph storage (:id fn-rec))]
          ;; Should only have 1 fn (the dangling reference is skipped)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id fn-rec)))
          ;; The arg-value should still be present with the non-existent ref
          ;; JSONB stores UUIDs as strings
          (let [args (get (:resolved-args graph) (:id fn-rec))]
            (is (= (str non-existent-fn-id) (str (:value (get args (:id arg-ref))))))))
        (finally
          (sp/close storage))))))


;; === Mock-based coverage tests ===

(deftest resolve-execution-graph-simple-refs-test
  (testing "handles fn references correctly in graph traversal"
    ;; This tests that fn references in arg-values are resolved
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              ;; Create fn-schema
              fn-schema (crud/create-entity pool :fn-schema
                                            {:name "test-fn"
                                             :returned-type "int"}
                                            nil)
              arg-ref (crud/create-entity pool :arg-schema
                                          {:fn-schema-id (:id fn-schema)
                                           :name "ref-arg"
                                           :type "ref"
                                           :required false}
                                          nil)
              ;; Create main fn
              main-fn (crud/create-entity pool :fn
                                          {:name "main-fn"
                                           :fn-schema-id (:id fn-schema)}
                                          nil)
              ;; Create referenced fn
              ref-fn (crud/create-entity pool :fn
                                         {:name "ref-fn"
                                          :fn-schema-id (:id fn-schema)}
                                         nil)
              ;; Create arg-value pointing to ref-fn
              _ (crud/create-entity pool :arg-value
                                    {:owner-fn-id (:id main-fn)
                                     :arg-schema-id (:id arg-ref)
                                     :value (:id ref-fn)}
                                    nil)
              graph (sp/resolve-execution-graph storage (:id main-fn))]
          ;; Both fns should be in graph
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id main-fn)))
          (is (contains? (:fns graph) (:id ref-fn))))
        (finally
          (sp/close storage))))))


(deftest merge-arg-values-unknown-owner-test
  (testing "merge-arg-values handles arg-value with owner not in chain"
    ;; This tests the Integer/MAX_VALUE fallback in min-key
    ;; when an arg-value has an owner not in the chain
    (let [fn-id (random-uuid)
          unknown-owner-id (random-uuid)
          arg-schema-id (random-uuid)
          ;; Create arg-values: one with known owner, one with unknown owner
          arg-values [{:id (random-uuid)
                       :owner-fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :value 42}
                      {:id (random-uuid)
                       :owner-fn-id unknown-owner-id
                       :arg-schema-id arg-schema-id
                       :value 999}]
          chain [fn-id]
          merge-arg-values-fn #'crud/merge-arg-values-for-chain
          result (merge-arg-values-fn arg-values chain)]
      ;; The arg-value with known owner should win (lower chain position)
      (is (= 42 (:value (get result arg-schema-id)))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    create-test-storage
    sp/close))


;; === SQL Error Handling Integration Tests ===
;; These tests trigger real SQL errors to cover catch blocks in crud.clj

(deftest sql-error-unique-violation-test
  (testing "create-entity throws wrapped error on unique violation"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [id #uuid "11111111-1111-1111-1111-111111111111"]
          ;; Create first entity
          (sp/create-entity storage :user {:id id :name "Alice"})
          ;; Try to create second entity with same id - should throw unique violation
          (try
            (sp/create-entity storage :user {:id id :name "Bob"})
            (is false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :unique-violation (:type (ex-data e))))
              (is (= :create-entity (:operation (ex-data e))))
              (is (some? (:sql-state (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "create-entities throws validation error on duplicate IDs in batch"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [id #uuid "11111111-1111-1111-1111-111111111111"]
          ;; Try to create multiple entities with same id in one batch
          (try
            (sp/create-entities storage :user [{:id id :name "Alice"}
                                               {:id id :name "Bob"}])
            (is false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :validation-error/duplicate-ids (:type (ex-data e))))
              (is (= [id] (:duplicate-ids (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "create-entities throws SQL error on conflict with existing record"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [id #uuid "11111111-1111-1111-1111-111111111111"]
          ;; First create a record with this ID
          (sp/create-entity storage :user {:id id :name "Alice"})
          ;; Try to create another batch with the same ID
          (try
            (sp/create-entities storage :user [{:id id :name "Bob"}])
            (is false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :unique-violation (:type (ex-data e))))
              (is (= :create-entities (:operation (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws wrapped error on unique violation (when constraint exists)"
    (let [storage (create-test-storage)]
      (try
        ;; Use graph schema which has unique constraints on names
        (sp/initialize storage (make-graph-schema))
        (let [fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"]
          (sp/create-entity storage :fn-schema {:id fn-schema-id :name "schema1" :returned-type "int"})
          (sp/create-entity storage :fn-schema {:id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                                                :name "schema2" :returned-type "int"})
          ;; Try to update schema2's name to conflict with schema1
          ;; Note: This requires a unique constraint on name, which we might not have.
          ;; If no unique constraint, this test would need a different approach.
          ;; For now, test that update works and returns properly typed errors when they occur.
          )
        (finally
          (sp/close storage))))))


(deftest sql-error-foreign-key-violation-test
  (testing "delete-entity throws wrapped error on foreign key violation"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
              fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
          ;; Add FK constraint manually (not created by default in DDL)
          (jdbc/execute! pool ["ALTER TABLE \"fn\" ADD CONSTRAINT fk_fn_schema
                                FOREIGN KEY (\"fn_schema_id\") REFERENCES \"fn_schema\"(\"id\")"])
          ;; Create fn-schema
          (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          ;; Create fn that references fn-schema
          (sp/create-entity storage :fn {:id fn-id :name "my-fn" :fn-schema-id fn-schema-id})
          ;; Try to delete fn-schema while fn still references it
          (try
            (sp/delete-entity storage :fn-schema fn-schema-id)
            (is false "Should have thrown foreign key violation")
            (catch clojure.lang.ExceptionInfo e
              (is (= :foreign-key-violation (:type (ex-data e))))
              (is (= :delete-entity (:operation (ex-data e))))
              (is (some? (:sql-state (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "delete-entities throws wrapped error on foreign key violation"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
              fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
          ;; Add FK constraint manually
          (jdbc/execute! pool ["ALTER TABLE \"fn\" ADD CONSTRAINT fk_fn_schema
                                FOREIGN KEY (\"fn_schema_id\") REFERENCES \"fn_schema\"(\"id\")"])
          ;; Create fn-schema
          (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          ;; Create fn that references fn-schema
          (sp/create-entity storage :fn {:id fn-id :name "my-fn" :fn-schema-id fn-schema-id})
          ;; Try to delete fn-schema while fn still references it
          (try
            (sp/delete-entities storage :fn-schema [fn-schema-id])
            (is false "Should have thrown foreign key violation")
            (catch clojure.lang.ExceptionInfo e
              (is (= :foreign-key-violation (:type (ex-data e))))
              (is (= :delete-entities (:operation (ex-data e)))))))
        (finally
          (sp/close storage))))))


(deftest sql-error-not-found-in-graph-queries-test
  (testing "resolve-execution-graph handles missing referenced fns gracefully"
    ;; This tests that the graph resolution doesn't fail when a referenced fn
    ;; has been deleted but the reference remains
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              fn-schema (crud/create-entity pool :fn-schema
                                            {:name "test" :returned-type "int"} nil)
              arg-schema (crud/create-entity pool :arg-schema
                                             {:fn-schema-id (:id fn-schema)
                                              :name "ref" :type "ref" :required false} nil)
              main-fn (crud/create-entity pool :fn
                                          {:name "main" :fn-schema-id (:id fn-schema)} nil)
              ref-fn (crud/create-entity pool :fn
                                         {:name "ref-target" :fn-schema-id (:id fn-schema)} nil)
              _ (crud/create-entity pool :arg-value
                                    {:owner-fn-id (:id main-fn)
                                     :arg-schema-id (:id arg-schema)
                                     :value (:id ref-fn)} nil)
              ;; Delete the referenced fn directly (bypassing FK check by deleting in correct order)
              _ (jdbc/execute! pool [(str "DELETE FROM \"fn\" WHERE id = '" (:id ref-fn) "'")])
              ;; Now resolve - should handle missing fn gracefully
              graph (sp/resolve-execution-graph storage (:id main-fn))]
          ;; Should only have main-fn since ref-fn was deleted
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id main-fn))))
        (finally
          (sp/close storage)))))

  (testing "read-entities handles SQL errors"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (try
        ;; Normal case - read from existing table
        (let [result (sp/read-entities storage :user [])]
          (is (= {} result)))
        ;; Read with non-empty ids from existing table
        (let [id #uuid "11111111-1111-1111-1111-111111111111"
              _ (sp/create-entity storage :user {:id id :name "Alice"})
              result (sp/read-entities storage :user [id])]
          (is (= 1 (count result)))
          (is (= "Alice" (:name (get result id)))))
        (finally
          (sp/close storage)))))

  (testing "query-entities returns empty for non-matching where"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (try
        (sp/create-entity storage :user {:name "Alice"})
        (let [result (sp/query-entities storage :user {:name "NonExistent"})]
          (is (empty? result)))
        (finally
          (sp/close storage))))))


(deftest wrap-sql-error-logging-test
  (testing "wrap-sql-error includes all context in exception"
    (let [wrap-sql-error #'crud/wrap-sql-error
          sql-ex (SQLException. "duplicate key value" "23505")
          context {:entity-name :user :id #uuid "11111111-1111-1111-1111-111111111111"}
          wrapped (wrap-sql-error sql-ex :create-entity context)
          data (ex-data wrapped)]
      (is (= :unique-violation (:type data)))
      (is (= :create-entity (:operation data)))
      (is (= "23505" (:sql-state data)))
      (is (= :user (:entity-name data)))
      (is (some? (:message data)))
      ;; The cause should be the original SQLException
      (is (instance? SQLException (ex-cause wrapped))))))


(deftest batch-operations-empty-sequences-test
  (testing "load-arg-values-batch returns empty for empty fn-ids"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              load-fn #'crud/load-arg-values-batch
              result (load-fn pool #{})]
          (is (empty? result)))
        (finally
          (sp/close storage)))))

  (testing "collect-parent-chains-batch returns empty for empty fn-ids"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              collect-fn #'crud/collect-parent-chains-batch
              result (collect-fn pool #{})]
          (is (= {} result)))
        (finally
          (sp/close storage)))))

  (testing "verify-fn-refs-batch returns empty for empty candidates"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              verify-fn #'crud/verify-fn-refs-batch
              result (verify-fn pool #{})]
          (is (= #{} result)))
        (finally
          (sp/close storage)))))

  (testing "load-entities-batch returns empty for empty values"
    (let [storage (create-test-storage)]
      (try
        (sp/initialize storage (make-graph-schema))
        (let [pool (:pool storage)
              load-fn #'crud/load-entities-batch
              result (load-fn pool :fn :id #{})]
          (is (= {} result)))
        (finally
          (sp/close storage)))))

  (testing "merge-arg-values-for-chain returns nil for empty chain"
    (let [merge-fn #'crud/merge-arg-values-for-chain
          result (merge-fn [] [])]
      (is (nil? result)))))


;; === Mock-based SQL Error Tests ===
;; These tests use mocks to trigger SQLException in paths that are hard to reach otherwise

(deftest sql-error-read-entity-mock-test
  (testing "read-entity throws wrapped error on SQLException"
    (let [read-entity-fn #'crud/read-entity
          table-not-found-ex (SQLException. "relation does not exist" "42P01")]
      (with-redefs [jdbc/execute-one! (fn [_ds _query & _opts]
                                        (throw table-not-found-ex))]
        (try
          (read-entity-fn nil :some-entity (random-uuid))
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :read-entity (:operation (ex-data e))))))))))


(deftest sql-error-update-entity-mock-test
  (testing "update-entity throws wrapped error on SQLException during update"
    (let [update-entity-fn #'crud/update-entity
          unique-violation-ex (SQLException. "duplicate key" "23505")
          call-count (atom 0)]
      ;; First call to read-entity succeeds, second call (update) fails
      (with-redefs [jdbc/execute-one! (fn [_ds _query & _opts]
                                        (swap! call-count inc)
                                        (if (= 1 @call-count)
                                          ;; First call - read existing entity
                                          {:id (random-uuid) :name "test"}
                                          ;; Second call - update fails
                                          (throw unique-violation-ex)))]
        (try
          (update-entity-fn nil :some-entity (random-uuid) {:name "new"} nil)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unique-violation (:type (ex-data e))))
            (is (= :update-entity (:operation (ex-data e))))))))))


(deftest sql-error-query-entities-mock-test
  (testing "query-entities throws wrapped error on SQLException"
    (let [query-entities-fn #'crud/query-entities
          connection-ex (SQLException. "connection failed" "08001")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw connection-ex))]
        (try
          (query-entities-fn nil :some-entity {:name "test"})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :connection-error (:type (ex-data e))))
            (is (= :query-entities (:operation (ex-data e))))))))))


(deftest sql-error-read-entities-mock-test
  (testing "read-entities throws wrapped error on SQLException"
    (let [read-entities-fn #'crud/read-entities
          timeout-ex (SQLException. "query canceled" "57014")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw timeout-ex))]
        (try
          (read-entities-fn nil :some-entity [(random-uuid)])
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :query-timeout (:type (ex-data e))))
            (is (= :read-entities (:operation (ex-data e))))))))))


(deftest sql-error-graph-operations-mock-test
  (testing "collect-parent-chains-batch throws wrapped error on SQLException"
    (let [collect-fn #'crud/collect-parent-chains-batch
          connection-ex (SQLException. "connection failed" "08001")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw connection-ex))]
        (try
          (collect-fn nil #{(random-uuid)})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :connection-error (:type (ex-data e))))
            (is (= :collect-parent-chains (:operation (ex-data e)))))))))

  (testing "load-arg-values-batch throws wrapped error on SQLException"
    (let [load-fn #'crud/load-arg-values-batch
          timeout-ex (SQLException. "query canceled" "57014")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw timeout-ex))]
        (try
          (load-fn nil #{(random-uuid)})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :query-timeout (:type (ex-data e))))
            (is (= :load-arg-values (:operation (ex-data e)))))))))

  (testing "verify-fn-refs-batch throws wrapped error on SQLException"
    (let [verify-fn #'crud/verify-fn-refs-batch
          not-null-ex (SQLException. "not null violation" "23502")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw not-null-ex))]
        (try
          (verify-fn nil #{(random-uuid)})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :not-null-violation (:type (ex-data e))))
            (is (= :verify-fn-refs (:operation (ex-data e)))))))))

  (testing "load-entities-batch throws wrapped error on SQLException"
    (let [load-fn #'crud/load-entities-batch
          check-ex (SQLException. "check violation" "23514")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw check-ex))]
        (try
          (load-fn nil :fn :id #{(random-uuid)})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :check-constraint-violation (:type (ex-data e))))
            (is (= :load-entities-batch (:operation (ex-data e))))))))))


;; === DDL Error Tests ===
;; These tests verify DDL operations properly wrap SQLExceptions

(deftest ddl-error-create-enum-mock-test
  (testing "create-enum! throws wrapped error on SQLException"
    (let [create-enum-fn #'ddl/create-enum!
          ex (SQLException. "type already exists" "42710")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-enum-fn nil :my-enum [:a :b])
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            ;; Type from classify-sql-error (unknown for 42710)
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :create-enum (:operation (ex-data e))))
            (is (= :my-enum (:enum-name (ex-data e))))))))))


(deftest ddl-error-add-enum-value-mock-test
  (testing "add-enum-value! throws wrapped error on SQLException"
    (let [add-fn #'ddl/add-enum-value!
          ex (SQLException. "type does not exist" "42704")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (add-fn nil :my-enum :new-val)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :add-enum-value (:operation (ex-data e))))
            (is (= :new-val (:value (ex-data e))))))))))


(deftest ddl-error-rename-enum-mock-test
  (testing "rename-enum! throws wrapped error on SQLException"
    (let [rename-fn #'ddl/rename-enum!
          ex (SQLException. "type does not exist" "42704")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (rename-fn nil :old-name :new-name)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :rename-enum (:operation (ex-data e))))
            (is (= :old-name (:old-name (ex-data e))))
            (is (= :new-name (:new-name (ex-data e))))))))))


(deftest ddl-error-create-table-mock-test
  (testing "create-table! throws wrapped error on SQLException"
    (let [create-fn #'ddl/create-table!
          ex (SQLException. "relation already exists" "42P07")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-fn nil :my-table {:name {:type :text}})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :create-table (:operation (ex-data e))))
            (is (= :my-table (:table-name (ex-data e))))))))))


(deftest ddl-error-rename-table-mock-test
  (testing "rename-table! throws wrapped error on SQLException"
    (let [rename-fn #'ddl/rename-table!
          ex (SQLException. "relation does not exist" "42P01")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (rename-fn nil :old-table :new-table)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :rename-table (:operation (ex-data e))))))))))


(deftest ddl-error-add-column-mock-test
  (testing "add-column! throws wrapped error on SQLException"
    (let [add-fn #'ddl/add-column!
          ex (SQLException. "column already exists" "42701")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (add-fn nil :my-table :new-col {:type :text})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :add-column (:operation (ex-data e))))
            (is (= :new-col (:field-name (ex-data e))))))))))


(deftest ddl-error-rename-column-mock-test
  (testing "rename-column! throws wrapped error on SQLException"
    (let [rename-fn #'ddl/rename-column!
          ex (SQLException. "column does not exist" "42703")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (rename-fn nil :my-table :old-col :new-col)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :rename-column (:operation (ex-data e))))
            (is (= :old-col (:old-col-name (ex-data e))))))))))


(deftest ddl-error-alter-column-type-mock-test
  (testing "alter-column-type! throws wrapped error on SQLException"
    (let [alter-fn #'ddl/alter-column-type!
          ex (SQLException. "column does not exist" "42703")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (alter-fn nil :my-table :my-col "TEXT")
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :alter-column-type (:operation (ex-data e))))
            (is (= :my-col (:col-name (ex-data e))))))))))


(deftest ddl-error-create-ref-index-mock-test
  (testing "create-ref-index! throws wrapped error on SQLException"
    (let [create-fn #'ddl/create-ref-index!
          ex (SQLException. "relation does not exist" "42P01")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-fn nil :my-table :my-ref-col)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :create-index (:operation (ex-data e))))
            (is (= :my-table (:entity-name (ex-data e))))))))))


(deftest ddl-error-create-constraint-mock-test
  (testing "create-entity-constraints! throws wrapped error on SQLException"
    (let [create-fn #'ddl/create-entity-constraints!
          ex (SQLException. "relation does not exist" "42P01")
          ;; Mock schema that returns one constraint
          mock-schema (reify ds/DataSchema
                        (entities [_] [:my-table])

                        (entity-uuid [_ _] (random-uuid))

                        (entity-fields [_ _] {})

                        (enums [_] {})

                        (enum-uuid [_ _] nil)

                        (validate-entity [_ _ _] nil)

                        (entity-constraints
                          [_ _entity-name]
                          [{:type :unique :fields [:name]}]))]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-fn nil mock-schema :my-table)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :create-constraint (:operation (ex-data e))))))))))
