(ns graphden.postgres-storage.introspection
  "Database introspection functions for PostgreSQL.
   Reads current tables, columns, and enum types from information_schema."
  (:require
    [clojure.string :as str]
    [graphden.postgres-storage.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def ^:private metadata-table-name "_schema_metadata")


;; Use shared utilities from util.clj
(def ^:private get-query-timeout util/get-query-timeout-seconds)


(defmacro ^:private with-introspection-error-handling
  "Wraps introspection operation body with SQLException handling.
   Uses 'Introspection error' prefix for log messages."
  [operation context & body]
  `(util/with-sql-error-handling "Introspection error" ~operation ~context ~@body))


(defn current-tables
  "Returns set of table names in public schema (excluding metadata table)."
  [ds]
  (with-introspection-error-handling :current-tables {}
    (let [rows (jdbc/execute! ds
                              (sql/format {:select [:table_name]
                                           :from [:information_schema.tables]
                                           :where [:and
                                                   [:= :table_schema "public"]
                                                   [:= :table_type "BASE TABLE"]]}
                                          {:quoted true})
                              {:builder-fn rs/as-unqualified-lower-maps
                               :timeout (get-query-timeout)})]
      (set (remove #(= % metadata-table-name)
                   (map :table_name rows))))))


(defn current-columns
  "Returns map of column definitions for a table."
  [ds table-name]
  (with-introspection-error-handling :current-columns {:table-name table-name}
    (let [rows (jdbc/execute! ds
                              (sql/format {:select [:column_name :data_type :udt_name :is_nullable]
                                           :from [:information_schema.columns]
                                           :where [:and
                                                   [:= :table_schema "public"]
                                                   [:= :table_name table-name]
                                                   [:<> :column_name "id"]]}
                                          {:quoted true})
                              {:builder-fn rs/as-unqualified-lower-maps
                               :timeout (get-query-timeout)})]
      (into {}
            (map (fn [row]
                   (let [col-name (util/snake->kw (:column_name row))
                         data-type (:data_type row)
                         pg-type (cond
                                   (= data-type "USER-DEFINED") :enum
                                   (= data-type "timestamp with time zone") :timestamptz
                                   (= data-type "timestamp without time zone") :timestamp
                                   :else (keyword (str/lower-case data-type)))
                         nullable? (= (:is_nullable row) "YES")]
                     [col-name {:type (case pg-type
                                        :bigint :int
                                        :boolean :bool
                                        :numeric :numeric
                                        :text :text
                                        :uuid :uuid ; Note: :ref also maps to UUID
                                        :bytea :bytes
                                        :jsonb :jsonb
                                        :timestamptz :timestamptz
                                        :timestamp :timestamptz
                                        :enum :enum
                                        pg-type)
                                :nullable? nullable?}]))
                 rows)))))


(defn current-pg-enums
  "Returns set of enum type names in public schema."
  [ds]
  (with-introspection-error-handling :current-pg-enums {}
    (let [rows (jdbc/execute! ds
                              (sql/format {:select [:t.typname]
                                           :from [[:pg_type :t]]
                                           :join [[:pg_catalog.pg_namespace :n]
                                                  [:= :n.oid :t.typnamespace]]
                                           :where [:and
                                                   [:= :n.nspname "public"]
                                                   [:= :t.typtype "e"]]}
                                          {:quoted true})
                              {:builder-fn rs/as-unqualified-lower-maps
                               :timeout (get-query-timeout)})]
      (set (map :typname rows)))))


(defn current-enum-values-pg
  "Returns set of values for a PostgreSQL enum type.
   Converts SQL snake_case back to kebab-case keywords."
  [ds enum-name]
  (with-introspection-error-handling :current-enum-values-pg {:enum-name enum-name}
    (let [rows (jdbc/execute! ds
                              (sql/format {:select [:e.enumlabel]
                                           :from [[:pg_type :t]]
                                           :join [[:pg_catalog.pg_namespace :n]
                                                  [:= :n.oid :t.typnamespace]
                                                  [:pg_enum :e]
                                                  [:= :e.enumtypid :t.oid]]
                                           :where [:and
                                                   [:= :n.nspname "public"]
                                                   [:= :t.typname enum-name]]}
                                          {:quoted true})
                              {:builder-fn rs/as-unqualified-lower-maps
                               :timeout (get-query-timeout)})]
      (set (map (comp util/sql->enum-value :enumlabel) rows)))))
