(ns graphden.storage.postgres.introspection-test
  "Integration tests for PostgreSQL introspection functions.
   Tests querying information_schema and pg_catalog with real database."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.postgres.introspection :as introspection]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [next.jdbc :as jdbc]))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)
(def ^:dynamic *datasource* nil)


(defn with-clean-database
  "Cleans database and binds a fresh datasource."
  [f]
  (pth/clean-database-fast! *container*)
  (let [{:keys [jdbc-url username password]} (pth/get-container-config *container*)
        ds (jdbc/get-datasource {:jdbcUrl jdbc-url
                                 :user username
                                 :password password})]
    (binding [*datasource* ds]
      (f))))


(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each with-clean-database)


;; === current-tables tests ===

(deftest current-tables-empty-database-test
  (testing "returns empty set for empty database"
    (is (= #{} (introspection/current-tables *datasource*)))))


(deftest current-tables-with-tables-test
  (testing "returns set of user-created tables"
    ;; Create some tables directly
    (jdbc/execute! *datasource* ["CREATE TABLE users (id UUID PRIMARY KEY)"])
    (jdbc/execute! *datasource* ["CREATE TABLE orders (id UUID PRIMARY KEY)"])
    (jdbc/execute! *datasource* ["CREATE TABLE products (id UUID PRIMARY KEY)"])

    (is (= #{"users" "orders" "products"}
           (introspection/current-tables *datasource*)))))


(deftest current-tables-excludes-metadata-table-test
  (testing "excludes _schema_metadata table from results"
    (jdbc/execute! *datasource* ["CREATE TABLE users (id UUID PRIMARY KEY)"])
    (jdbc/execute! *datasource* ["CREATE TABLE _schema_metadata (id UUID PRIMARY KEY, data TEXT)"])

    (let [tables (introspection/current-tables *datasource*)]
      (is (contains? tables "users"))
      (is (not (contains? tables "_schema_metadata"))))))


;; === current-columns tests ===

(deftest current-columns-basic-types-test
  (testing "correctly identifies basic PostgreSQL types"
    (jdbc/execute! *datasource*
                   ["CREATE TABLE test_types (
                      id UUID PRIMARY KEY,
                      text_col TEXT,
                      int_col BIGINT,
                      bool_col BOOLEAN,
                      num_col NUMERIC,
                      ts_col TIMESTAMPTZ,
                      json_col JSONB,
                      bytes_col BYTEA,
                      uuid_col UUID
                    )"])

    ;; current-columns converts snake_case to kebab-case keywords
    (let [columns (introspection/current-columns *datasource* "test_types")]
      (is (= :text (:type (:text-col columns))))
      (is (= :int (:type (:int-col columns))))
      (is (= :bool (:type (:bool-col columns))))
      (is (= :numeric (:type (:num-col columns))))
      (is (= :timestamptz (:type (:ts-col columns))))
      (is (= :jsonb (:type (:json-col columns))))
      (is (= :bytes (:type (:bytes-col columns))))
      (is (= :uuid (:type (:uuid-col columns)))))))


(deftest current-columns-nullable-test
  (testing "correctly identifies nullable vs non-nullable columns"
    (jdbc/execute! *datasource*
                   ["CREATE TABLE nullable_test (
                      id UUID PRIMARY KEY,
                      required_col TEXT NOT NULL,
                      optional_col TEXT
                    )"])

    (let [columns (introspection/current-columns *datasource* "nullable_test")]
      ;; current-columns converts snake_case to kebab-case keywords
      (is (false? (:nullable? (:required-col columns))))
      (is (true? (:nullable? (:optional-col columns)))))))


(deftest current-columns-enum-type-test
  (testing "identifies enum columns as :enum type"
    (jdbc/execute! *datasource* ["CREATE TYPE status_type AS ENUM ('active', 'inactive')"])
    (jdbc/execute! *datasource*
                   ["CREATE TABLE with_enum (
                      id UUID PRIMARY KEY,
                      status status_type NOT NULL
                    )"])

    (let [columns (introspection/current-columns *datasource* "with_enum")]
      (is (= :enum (:type (:status columns)))))))


(deftest current-columns-excludes-id-test
  (testing "excludes id column from results"
    (jdbc/execute! *datasource*
                   ["CREATE TABLE id_test (
                      id UUID PRIMARY KEY,
                      name TEXT NOT NULL
                    )"])

    (let [columns (introspection/current-columns *datasource* "id_test")]
      (is (nil? (:id columns)))
      (is (some? (:name columns))))))


(deftest current-columns-kebab-case-conversion-test
  (testing "converts snake_case column names to kebab-case keywords"
    (jdbc/execute! *datasource*
                   ["CREATE TABLE case_test (
                      id UUID PRIMARY KEY,
                      user_name TEXT,
                      created_at TIMESTAMPTZ
                    )"])

    (let [columns (introspection/current-columns *datasource* "case_test")]
      (is (contains? columns :user-name))
      (is (contains? columns :created-at))
      (is (not (contains? columns :user_name))))))


;; === current-pg-enums tests ===

(deftest current-pg-enums-empty-test
  (testing "returns empty set when no enums exist"
    (is (= #{} (introspection/current-pg-enums *datasource*)))))


(deftest current-pg-enums-with-enums-test
  (testing "returns set of enum type names"
    (jdbc/execute! *datasource* ["CREATE TYPE order_status AS ENUM ('pending', 'shipped')"])
    (jdbc/execute! *datasource* ["CREATE TYPE user_role AS ENUM ('admin', 'user')"])

    (is (= #{"order_status" "user_role"}
           (introspection/current-pg-enums *datasource*)))))


;; === current-enum-values-pg tests ===

(deftest current-enum-values-pg-test
  (testing "returns set of enum values as keywords"
    (jdbc/execute! *datasource*
                   ["CREATE TYPE color AS ENUM ('red', 'green', 'blue')"])

    (is (= #{:red :green :blue}
           (introspection/current-enum-values-pg *datasource* "color")))))


(deftest current-enum-values-pg-kebab-case-test
  (testing "converts snake_case enum values to kebab-case keywords"
    (jdbc/execute! *datasource*
                   ["CREATE TYPE complex_status AS ENUM ('in_progress', 'on_hold', 'completed_successfully')"])

    (is (= #{:in-progress :on-hold :completed-successfully}
           (introspection/current-enum-values-pg *datasource* "complex_status")))))


(deftest current-enum-values-pg-nonexistent-test
  (testing "returns empty set for non-existent enum"
    (is (= #{} (introspection/current-enum-values-pg *datasource* "nonexistent_enum")))))


;; === Integration with storage ===

(deftest introspection-after-schema-initialization-test
  (testing "introspection correctly sees tables created by schema initialization"
    (let [storage (pg/create-storage (pth/get-container-config *container*))
          ;; Build schema properly using builder pattern (not simplified make-schema)
          schema (-> (mds/create-builder)
                     (ds/add-enum
                       :purchase-status
                       #uuid "00000000-0000-0000-0000-000000000100"
                       [{:uuid #uuid "00000000-0000-0000-0000-000000000101" :value :pending}
                        {:uuid #uuid "00000000-0000-0000-0000-000000000102" :value :shipped}
                        {:uuid #uuid "00000000-0000-0000-0000-000000000103" :value :delivered}])
                     (ds/add-entity
                       :user
                       #uuid "00000000-0000-0000-0000-000000000001"
                       {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                               :type :text}
                        :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                :type :text}
                        :age {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                              :type :int
                              :nullable? true}})
                     (ds/add-entity
                       :purchase
                       #uuid "00000000-0000-0000-0000-000000000010"
                       {:total {:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                :type :numeric}
                        :status {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                 :type :enum
                                 :enum-name :purchase-status}})
                     ds/build)]
      (try
        (sp/initialize storage schema)

        ;; Use storage's pool instead of separate datasource to ensure
        ;; we see the same database state as the storage
        (let [pool (:pool storage)]
          (testing "sees created tables"
            (let [tables (introspection/current-tables pool)]
              (is (contains? tables "user"))
              (is (contains? tables "purchase"))))

          (testing "sees created enums"
            (let [enums (introspection/current-pg-enums pool)]
              (is (contains? enums "purchase_status"))))

          (testing "sees enum values"
            (is (= #{:pending :shipped :delivered}
                   (introspection/current-enum-values-pg pool "purchase_status"))))

          (testing "sees column definitions"
            (let [user-cols (introspection/current-columns pool "user")]
              (is (= :text (:type (:name user-cols))))
              (is (= :text (:type (:email user-cols))))
              (is (= :int (:type (:age user-cols))))
              (is (false? (:nullable? (:name user-cols))))
              (is (true? (:nullable? (:age user-cols)))))))

        (finally
          (sp/close storage))))))
