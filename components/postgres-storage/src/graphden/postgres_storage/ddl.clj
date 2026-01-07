(ns graphden.postgres-storage.ddl
  "DDL operations for PostgreSQL.
   CREATE/ALTER for tables, columns, enums, indexes, and constraints."
  (:require
    [clojure.string :as str]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.postgres-storage.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


;; === Error handling ===

;; Use shared macro from util.clj with DDL-specific prefix
(defmacro ^:private with-ddl-error-handling
  "Wraps DDL operation body with SQLException handling.
   Uses 'DDL error' prefix for log messages."
  [operation context & body]
  `(util/with-sql-error-handling "DDL error" ~operation ~context ~@body))


;; === Enum operations ===
;;
;; NOTE on SQL injection safety:
;; These functions use string concatenation for DDL statements because PostgreSQL
;; doesn't support parameterized DDL. All identifiers and values are validated
;; through util.clj functions before inclusion:
;; - util/ident->sql: Validates and quotes identifiers (only lowercase alphanumeric + underscore)
;; - util/enum-value->sql: Validates enum values (same rules as identifiers)
;; - util/validate-sql-identifier!: Called by the above, throws on invalid input
;;
;; This provides defense-in-depth: even if an attacker somehow bypasses higher-level
;; checks, the validation here will reject malicious input.

(defn create-enum!
  "Creates a PostgreSQL enum type.

   Security: enum-name and values are validated via util/ident->sql and util/enum-value->sql
   which only allow lowercase alphanumeric characters and underscores."
  [ds enum-name values]
  (with-ddl-error-handling :create-enum {:enum-name enum-name}
    (let [sql-name (util/ident->sql enum-name)
          vals-sql (str/join ", " (map #(str "'" (util/enum-value->sql %) "'") values))]
      (jdbc/execute! ds [(str "CREATE TYPE " sql-name " AS ENUM (" vals-sql ")")]))))


(defn add-enum-value!
  "Adds a value to an existing PostgreSQL enum type.

   Security: enum-name and value are validated via util/ident->sql and util/enum-value->sql."
  [ds enum-name value]
  (with-ddl-error-handling :add-enum-value {:enum-name enum-name :value value}
    (jdbc/execute! ds [(str "ALTER TYPE " (util/ident->sql enum-name)
                            " ADD VALUE IF NOT EXISTS '" (util/enum-value->sql value) "'")])))


(defn rename-enum!
  "Renames a PostgreSQL enum type.

   Security: old-name and new-name are validated via util/ident->sql."
  [ds old-name new-name]
  (with-ddl-error-handling :rename-enum {:old-name old-name :new-name new-name}
    (jdbc/execute! ds [(str "ALTER TYPE " (util/ident->sql old-name)
                            " RENAME TO " (util/ident->sql new-name))])))


;; === Column specification ===

(defn build-column-spec
  "Builds HoneySQL column specification for CREATE TABLE."
  [field-name field-spec]
  (let [col-name (keyword (util/kw->snake-case field-name))
        pg-type-str (util/field-type->pg field-spec)
        ;; Handle enum types specially - they're quoted identifiers
        col-type (if (str/starts-with? pg-type-str "\"")
                   [:raw pg-type-str]
                   (keyword (str/lower-case pg-type-str)))]
    (if (:nullable? field-spec)
      [col-name col-type]
      [col-name col-type [:not nil]])))


;; === Table operations ===

(defn create-table!
  "Creates a PostgreSQL table with id as primary key."
  [ds table-name fields]
  (with-ddl-error-handling :create-table {:table-name table-name}
    (let [columns (into [[:id :uuid [:primary-key] [:default [:raw "gen_random_uuid()"]]]]
                        (map (fn [[fname fspec]] (build-column-spec fname fspec)) fields))]
      (jdbc/execute! ds
                     (sql/format {:create-table (keyword (util/kw->snake-case table-name))
                                  :with-columns columns}
                                 {:quoted true})))))


(defn rename-table!
  "Renames a PostgreSQL table."
  [ds old-name new-name]
  (with-ddl-error-handling :rename-table {:old-name old-name :new-name new-name}
    (jdbc/execute! ds
                   (sql/format {:alter-table (keyword (util/kw->snake-case old-name))
                                :rename-table (keyword (util/kw->snake-case new-name))}
                               {:quoted true}))))


;; === Column operations ===

(defn add-column!
  "Adds a column to an existing table."
  [ds table-name field-name field-spec]
  (with-ddl-error-handling :add-column {:table-name table-name :field-name field-name}
    (jdbc/execute! ds
                   (sql/format {:alter-table (keyword (util/kw->snake-case table-name))
                                :add-column (build-column-spec field-name field-spec)}
                               {:quoted true}))))


(defn rename-column!
  "Renames a column in a table."
  [ds table-name old-col-name new-col-name]
  (with-ddl-error-handling :rename-column {:table-name table-name :old-col-name old-col-name :new-col-name new-col-name}
    (jdbc/execute! ds
                   (sql/format {:alter-table (keyword (util/kw->snake-case table-name))
                                :rename-column [(keyword (util/kw->snake-case old-col-name))
                                                (keyword (util/kw->snake-case new-col-name))]}
                               {:quoted true}))))


(defn alter-column-type!
  "Changes column type (for safe widening)."
  [ds table-name col-name new-type-sql]
  (util/validate-pg-type! new-type-sql {:table table-name :column col-name})
  (with-ddl-error-handling :alter-column-type {:table-name table-name :col-name col-name :new-type new-type-sql}
    ;; ALTER COLUMN with USING clause needs raw SQL for complex expressions
    (jdbc/execute! ds [(str "ALTER TABLE " (util/ident->sql table-name)
                            " ALTER COLUMN " (util/ident->sql col-name)
                            " TYPE " new-type-sql " USING " (util/ident->sql col-name)
                            "::" new-type-sql)])))


;; === Index operations ===

(defn- ref-index-name
  "Generates index name for a ref field (foreign key)."
  [entity-name field-name]
  (str "idx_" (util/kw->snake-case entity-name) "_" (util/kw->snake-case field-name) "_ref"))


(defn create-ref-index!
  "Creates an index for a ref field to optimize joins."
  [ds entity-name field-name]
  (with-ddl-error-handling :create-index {:entity-name entity-name :field-name field-name}
    (let [table-name (util/ident->sql entity-name)
          index-name (ref-index-name entity-name field-name)
          column-name (util/ident->sql field-name)]
      (jdbc/execute! ds [(str "CREATE INDEX IF NOT EXISTS \"" index-name
                              "\" ON " table-name " (" column-name ")")]))))


(defn create-ref-indexes!
  "Creates indexes for all ref fields in an entity."
  [ds entity-name fields]
  (run! (fn [[field-name field-spec]]
          (when (= :ref (:type field-spec))
            (create-ref-index! ds entity-name field-name)))
        fields))


;; === Constraint operations ===

(defn- constraint-index-name
  "Generates unique index name for a constraint."
  [entity-name constraint]
  (let [fields-str (str/join "_" (map util/kw->snake-case (:fields constraint)))]
    (str "idx_" (util/kw->snake-case entity-name) "_" fields-str "_unique")))


(defn- create-constraint!
  "Creates a unique constraint (as unique index) in PostgreSQL."
  [ds entity-name constraint]
  (with-ddl-error-handling :create-constraint {:entity-name entity-name :constraint constraint}
    (let [table-name (util/ident->sql entity-name)
          index-name (constraint-index-name entity-name constraint)
          columns-sql (str/join ", " (map util/ident->sql (:fields constraint)))]
      (jdbc/execute! ds [(str "CREATE UNIQUE INDEX \"" index-name "\" ON " table-name
                              " (" columns-sql ")")]))))


(defn create-entity-constraints!
  "Creates all constraints for a single entity."
  [ds schema entity-name]
  (run! #(create-constraint! ds entity-name %)
        (ds/entity-constraints schema entity-name)))
