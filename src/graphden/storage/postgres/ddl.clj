(ns graphden.storage.postgres.ddl
  "DDL operations for PostgreSQL.
   CREATE/ALTER for tables, columns, enums, indexes, and constraints."
  (:require
    [clojure.string :as str]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


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
  (util/with-sql-error-handling "DDL error" :create-enum {:enum-name enum-name}
                                (let [sql-name (util/ident->sql enum-name)
                                      vals-sql (str/join ", " (map #(str "'" (util/enum-value->sql %) "'") values))]
                                  (jdbc/execute! ds [(str "CREATE TYPE " sql-name " AS ENUM (" vals-sql ")")]))))


(defn add-enum-value!
  "Adds a value to an existing PostgreSQL enum type.

   Security: enum-name and value are validated via util/ident->sql and util/enum-value->sql."
  [ds enum-name value]
  (util/with-sql-error-handling "DDL error" :add-enum-value {:enum-name enum-name :value value}
                                (jdbc/execute! ds [(str "ALTER TYPE " (util/ident->sql enum-name)
                                                        " ADD VALUE IF NOT EXISTS '" (util/enum-value->sql value) "'")])))


(defn rename-enum!
  "Renames a PostgreSQL enum type.

   Security: old-name and new-name are validated via util/ident->sql."
  [ds old-name new-name]
  (util/with-sql-error-handling "DDL error" :rename-enum {:old-name old-name :new-name new-name}
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
  "Creates a PostgreSQL table with id as primary key.
   Skips :ref-many fields - those are stored in separate junction tables."
  [ds table-name fields]
  (util/with-sql-error-handling "DDL error" :create-table {:table-name table-name}
                                (let [columnar-fields (remove (fn [[_ fspec]] (= :ref-many (:type fspec))) fields)
                                      columns (into [[:id :uuid [:primary-key] [:default [:raw "gen_random_uuid()"]]]]
                                                    (map (fn [[fname fspec]] (build-column-spec fname fspec)) columnar-fields))]
                                  (jdbc/execute! ds
                                                 (sql/format {:create-table (keyword (util/kw->snake-case table-name))
                                                              :with-columns columns}
                                                             {:quoted true})))))


;; === Junction tables for :ref-many fields ===

(defn junction-table-name
  "Returns the junction table name for a :ref-many field.
   Format: {entity-name}_{field-name}"
  [entity-name field-name]
  (str (util/kw->snake-case entity-name) "_" (util/kw->snake-case field-name)))


(defn create-junction-table!
  "Creates a junction table for a :ref-many relationship.
   Schema: (owner_id UUID, target_id UUID, ord INT)
   - owner_id FK to entity-name
   - target_id FK to ref-entity (from field-spec)
   - ord for ordered membership / priority"
  [ds entity-name field-name _field-spec]
  (let [jt-name (junction-table-name entity-name field-name)
        owner-table (util/kw->snake-case entity-name)]
    (util/with-sql-error-handling "DDL error" :create-junction-table
                                  {:entity-name entity-name :field-name field-name}
                                  (jdbc/execute! ds
                                                 [(str "CREATE TABLE \"" jt-name "\" ("
                                                       "owner_id UUID NOT NULL REFERENCES \"" owner-table "\"(id) ON DELETE CASCADE,"
                                                       "target_id UUID NOT NULL,"
                                                       "ord INT NOT NULL,"
                                                       "PRIMARY KEY (owner_id, ord),"
                                                       "UNIQUE (owner_id, target_id))")])
                                  ;; Index for reverse lookup (find owners pointing at a target)
                                  (jdbc/execute! ds
                                                 [(str "CREATE INDEX \"idx_" jt-name "_target\" "
                                                       "ON \"" jt-name "\" (target_id)")]))))


(defn create-junction-tables!
  "Creates junction tables for all :ref-many fields in an entity."
  [ds entity-name fields]
  (run! (fn [[field-name field-spec]]
          (when (= :ref-many (:type field-spec))
            (create-junction-table! ds entity-name field-name field-spec)))
        fields))


(defn rename-table!
  "Renames a PostgreSQL table."
  [ds old-name new-name]
  (util/with-sql-error-handling "DDL error" :rename-table {:old-name old-name :new-name new-name}
                                (jdbc/execute! ds
                                               (sql/format {:alter-table (keyword (util/kw->snake-case old-name))
                                                            :rename-table (keyword (util/kw->snake-case new-name))}
                                                           {:quoted true}))))


;; === Column operations ===

(defn add-column!
  "Adds a column to an existing table."
  [ds table-name field-name field-spec]
  (util/with-sql-error-handling "DDL error" :add-column {:table-name table-name :field-name field-name}
                                (jdbc/execute! ds
                                               (sql/format {:alter-table (keyword (util/kw->snake-case table-name))
                                                            :add-column (build-column-spec field-name field-spec)}
                                                           {:quoted true}))))


(defn rename-column!
  "Renames a column in a table."
  [ds table-name old-col-name new-col-name]
  (util/with-sql-error-handling "DDL error" :rename-column {:table-name table-name :old-col-name old-col-name :new-col-name new-col-name}
                                (jdbc/execute! ds
                                               (sql/format {:alter-table (keyword (util/kw->snake-case table-name))
                                                            :rename-column [(keyword (util/kw->snake-case old-col-name))
                                                                            (keyword (util/kw->snake-case new-col-name))]}
                                                           {:quoted true}))))


(defn drop-column!
  "Drops a column. `IF EXISTS` makes the call idempotent — re-running
   the same migration after the column is gone is a safe no-op."
  [ds table-name col-name]
  (util/with-sql-error-handling "DDL error" :drop-column
                                {:table-name table-name :col-name col-name}
                                (jdbc/execute! ds [(str "ALTER TABLE " (util/ident->sql table-name)
                                                        " DROP COLUMN IF EXISTS "
                                                        (util/ident->sql col-name))])))


(defn alter-column-drop-not-null!
  "Drop a column's NOT NULL constraint — the schema flipped the field to
   nullable, so the DB must allow nil where it previously didn't."
  [ds table-name col-name]
  (util/with-sql-error-handling "DDL error" :alter-column-nullable {:table-name table-name :col-name col-name}
                                (jdbc/execute! ds [(str "ALTER TABLE " (util/ident->sql table-name)
                                                        " ALTER COLUMN " (util/ident->sql col-name)
                                                        " DROP NOT NULL")])))


(defn alter-column-type!
  "Changes column type (for safe widening)."
  [ds table-name col-name new-type-sql]
  (util/validate-pg-type! new-type-sql {:table table-name :column col-name})
  (let [col (util/ident->sql col-name)
        ;; PostgreSQL has NO `::jsonb` cast from a scalar type (bigint /
        ;; boolean / numeric), yet the widening table blesses scalar→:jsonb
        ;; as safe. `to_jsonb(col)` accepts any source type (and, for text,
        ;; wraps it as a JSON string), so use it for a JSONB target instead
        ;; of the invalid `col::jsonb` that would abort the whole migration.
        using (if (= "JSONB" (str/upper-case (str/trim new-type-sql)))
                (str "to_jsonb(" col ")")
                (str col "::" new-type-sql))]
    (util/with-sql-error-handling "DDL error" :alter-column-type {:table-name table-name :col-name col-name :new-type new-type-sql}
                                  ;; ALTER COLUMN with USING clause needs raw SQL for complex expressions
                                  (jdbc/execute! ds [(str "ALTER TABLE " (util/ident->sql table-name)
                                                          " ALTER COLUMN " col
                                                          " TYPE " new-type-sql " USING " using)]))))


;; === Index operations ===

(defn- ref-index-name
  "Generates index name for a ref field (foreign key)."
  [entity-name field-name]
  (str "idx_" (util/kw->snake-case entity-name) "_" (util/kw->snake-case field-name) "_ref"))


(defn create-ref-index!
  "Creates an index for a ref field to optimize joins."
  [ds entity-name field-name]
  (util/with-sql-error-handling "DDL error" :create-index {:entity-name entity-name :field-name field-name}
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


(defn- field-index-name
  "Generates index name for a plain `:indexed?` field."
  [entity-name field-name]
  (str "idx_" (util/kw->snake-case entity-name) "_" (util/kw->snake-case field-name)))


(defn create-field-index!
  "Creates a plain btree index for a field flagged `:indexed? true`. For
   non-`:ref` columns that still need fast lookups — e.g. version-table
   copies of `:ref-fn-id`, which are `:type :uuid` (not `:ref`) to avoid an
   FK to a possibly-deleted target, yet drive the reverse-ref reverse
   lookup in `:ref-owner-bindings`."
  [ds entity-name field-name]
  (util/with-sql-error-handling "DDL error" :create-index {:entity-name entity-name :field-name field-name}
                                (let [table-name (util/ident->sql entity-name)
                                      index-name (field-index-name entity-name field-name)
                                      column-name (util/ident->sql field-name)]
                                  (jdbc/execute! ds [(str "CREATE INDEX IF NOT EXISTS \"" index-name
                                                          "\" ON " table-name " (" column-name ")")]))))


(defn create-field-indexes!
  "Creates indexes for all `:indexed? true` fields in an entity."
  [ds entity-name fields]
  (run! (fn [[field-name field-spec]]
          (when (:indexed? field-spec)
            (create-field-index! ds entity-name field-name)))
        fields))


;; === Constraint operations ===

(defn- constraint-index-name
  "Generates unique index name for a constraint."
  [entity-name constraint]
  (let [fields-str (str/join "_" (map util/kw->snake-case (:fields constraint)))]
    (str "idx_" (util/kw->snake-case entity-name) "_" fields-str "_unique")))


(defn- jsonb-field?
  "Returns true if the field type maps to JSONB in PostgreSQL.
   These types can have large values that exceed btree index limits."
  [field-spec]
  (#{:union :jsonb :any} (:type field-spec)))


(defn- constraint-column-sql
  "Returns SQL for a constraint column.
   For JSONB fields, uses md5(column::text) to avoid btree size limits.
   For other fields, uses the column name directly."
  [field-name field-spec]
  (let [col-name (util/ident->sql field-name)]
    (if (jsonb-field? field-spec)
      (str "md5(" col-name "::text)")
      col-name)))


(defn- create-constraint!
  "Creates a unique constraint (as unique index) in PostgreSQL.
   For JSONB fields (union, jsonb, any types), uses MD5 hash to avoid
   PostgreSQL btree index size limit (~2704 bytes)."
  [ds schema entity-name constraint]
  (util/with-sql-error-handling "DDL error" :create-constraint {:entity-name entity-name :constraint constraint}
                                (let [table-name (util/ident->sql entity-name)
                                      index-name (constraint-index-name entity-name constraint)
                                      fields (ds/entity-fields schema entity-name)
                                      columns-sql (str/join ", "
                                                            (map (fn [field-name]
                                                                   (constraint-column-sql field-name (get fields field-name)))
                                                                 (:fields constraint)))
                                      ;; `:nulls-not-distinct?` (PG 15+) treats NULL = NULL for uniqueness —
                                      ;; lets a composite key like `(org-id, name)` stay unique when org-id is
                                      ;; NULL (single-tenant), while still allowing distinct orgs the same name.
                                      nulls-sql (if (:nulls-not-distinct? constraint) " NULLS NOT DISTINCT" "")]
                                  (jdbc/execute! ds [(str "CREATE UNIQUE INDEX \"" index-name "\" ON " table-name
                                                          " (" columns-sql ")" nulls-sql)]))))


(defn create-entity-constraints!
  "Creates all constraints for a single entity."
  [ds schema entity-name]
  (run! #(create-constraint! ds schema entity-name %)
        (ds/entity-constraints schema entity-name)))
