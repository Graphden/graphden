(ns graphden.postgres-storage.core
  "PostgreSQL implementation of Storage protocol."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)))


;; === Type mapping ===

(def type->pg
  "Maps our field types to PostgreSQL types."
  {:uuid        "UUID"
   :text        "TEXT"
   :int         "BIGINT"
   :bool        "BOOLEAN"
   :numeric     "NUMERIC"
   :timestamptz "TIMESTAMPTZ"
   :jsonb       "JSONB"
   :bytes       "BYTEA"})


(defn- kw->snake-case
  "Converts a keyword to snake_case string (unquoted)."
  [k]
  (str/replace (name k) "-" "_"))


(defn- ident->sql
  "Converts a keyword identifier to quoted SQL-safe name (snake_case).
   Uses double quotes to handle reserved words like 'order', 'user', etc."
  [k]
  (str "\"" (kw->snake-case k) "\""))


(defn- field-type->pg
  "Converts a field type to PostgreSQL type string.
   Handles basic types, refs (as UUID), enums, and unions (as JSONB)."
  [field-spec]
  (let [t (:type field-spec)]
    (case t
      :ref "UUID"
      :enum (ident->sql (:enum-name field-spec))
      :union "JSONB"
      (get type->pg t "TEXT"))))


;; === Connection pool ===

(defn create-pool
  "Creates a HikariCP connection pool."
  [{:keys [jdbc-url username password pool-size]
    :or {pool-size 10}}]
  (let [config (HikariConfig.)]
    (HikariConfig/.setJdbcUrl config jdbc-url)
    (HikariConfig/.setUsername config username)
    (HikariConfig/.setPassword config password)
    (HikariConfig/.setMaximumPoolSize config pool-size)
    (HikariConfig/.setMinimumIdle config 2)
    (HikariConfig/.setConnectionTimeout config 30000)
    (HikariConfig/.setIdleTimeout config 600000)
    (HikariConfig/.setMaxLifetime config 1800000)
    (HikariDataSource. config)))


(defn close-pool
  "Closes a HikariCP connection pool."
  [^HikariDataSource pool]
  (when pool
    (HikariDataSource/.close pool)))


;; === Metadata table operations ===

(def ^:private metadata-table-name "_schema_metadata")


(defn- ensure-metadata-table!
  "Creates metadata table if it doesn't exist."
  [ds]
  (jdbc/execute! ds
                 [(str "CREATE TABLE IF NOT EXISTS " metadata-table-name " ("
                       "uuid UUID PRIMARY KEY, "
                       "kind TEXT NOT NULL, "
                       "name TEXT NOT NULL, "
                       "parent_uuid UUID, "
                       "extra JSONB"
                       ")")]))


(defn- read-metadata-rows
  "Reads raw metadata rows for processing."
  [ds]
  (jdbc/execute! ds
                 [(str "SELECT uuid, kind, name, parent_uuid FROM " metadata-table-name)]
                 {:builder-fn rs/as-unqualified-lower-maps}))


(defn- parse-metadata
  "Parses metadata rows into structured format."
  [rows]
  (when (seq rows)
    (let [uuid->row (into {} (map (fn [r] [(:uuid r) r]) rows))]
      (reduce
        (fn [acc row]
          (let [uuid (:uuid row)
                kind (keyword (:kind row))
                n (keyword (:name row))
                parent-uuid (:parent_uuid row)
                parent-row (get uuid->row parent-uuid)]
            (case kind
              :entity (assoc-in acc [:entities uuid] n)
              :field (assoc-in acc [:fields uuid]
                               {:entity (keyword (:name parent-row))
                                :field n})
              :enum (assoc-in acc [:enums uuid] n)
              :enum-value (assoc-in acc [:enum-values uuid]
                                    {:enum (keyword (:name parent-row))
                                     :value n})
              acc)))
        {:entities {} :fields {} :enums {} :enum-values {}}
        rows))))


(defn- upsert-metadata!
  "Inserts or updates a metadata row."
  [ds uuid kind meta-name parent-uuid]
  (jdbc/execute! ds
                 [(str "INSERT INTO " metadata-table-name " (uuid, kind, name, parent_uuid) "
                       "VALUES (?, ?, ?, ?) "
                       "ON CONFLICT (uuid) DO UPDATE SET name = EXCLUDED.name, parent_uuid = EXCLUDED.parent_uuid")
                  uuid (name kind) (name meta-name) parent-uuid]))


(defn- save-metadata!
  "Saves complete metadata to table (truncate + insert all)."
  [ds schema]
  (jdbc/execute! ds [(str "TRUNCATE TABLE " metadata-table-name)])
  (doseq [entity-name (ds/entities schema)]
    (let [entity-uuid (ds/entity-uuid schema entity-name)]
      (upsert-metadata! ds entity-uuid :entity entity-name nil)
      (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
        (upsert-metadata! ds (:uuid field-spec) :field field-name entity-uuid))))
  (doseq [[enum-name {:keys [uuid values]}] (ds/enums schema)]
    (upsert-metadata! ds uuid :enum enum-name nil)
    (doseq [[value-kw value-uuid] values]
      (upsert-metadata! ds value-uuid :enum-value value-kw uuid))))


;; === Introspection ===

(defn- current-tables
  "Returns set of table names in public schema (excluding metadata table)."
  [ds]
  (let [rows (jdbc/execute! ds
                            ["SELECT table_name FROM information_schema.tables
                              WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (set (remove #(= % metadata-table-name)
                 (map :table_name rows)))))


(defn- current-columns
  "Returns map of column definitions for a table."
  [ds table-name]
  (let [rows (jdbc/execute! ds
                            ["SELECT column_name, data_type, udt_name, is_nullable
                              FROM information_schema.columns
                              WHERE table_schema = 'public' AND table_name = ?
                              AND column_name != 'id'"
                             table-name]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (into {}
          (map (fn [row]
                 (let [col-name (keyword (str/replace (:column_name row) "_" "-"))
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
               rows))))


(defn- current-pg-enums
  "Returns set of enum type names in public schema."
  [ds]
  (let [rows (jdbc/execute! ds
                            ["SELECT t.typname
                              FROM pg_type t
                              JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                              WHERE n.nspname = 'public' AND t.typtype = 'e'"]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (set (map :typname rows))))


(defn- current-enum-values-pg
  "Returns set of values for a PostgreSQL enum type."
  [ds enum-name]
  (let [rows (jdbc/execute! ds
                            ["SELECT e.enumlabel
                              FROM pg_type t
                              JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                              JOIN pg_enum e ON e.enumtypid = t.oid
                              WHERE n.nspname = 'public' AND t.typname = ?"
                             enum-name]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (set (map (comp keyword :enumlabel) rows))))


;; === DDL operations ===

(defn- enum-value->sql
  "Converts an enum value keyword to SQL string (snake_case, no quotes wrapper)."
  [k]
  (str/replace (name k) "-" "_"))


(defn- create-enum!
  "Creates a PostgreSQL enum type."
  [ds enum-name values]
  (let [sql-name (ident->sql enum-name)
        vals-sql (str/join ", " (map #(str "'" (enum-value->sql %) "'") values))]
    (jdbc/execute! ds [(str "CREATE TYPE " sql-name " AS ENUM (" vals-sql ")")])))


(defn- add-enum-value!
  "Adds a value to an existing PostgreSQL enum type."
  [ds enum-name value]
  (jdbc/execute! ds [(str "ALTER TYPE " (ident->sql enum-name)
                          " ADD VALUE IF NOT EXISTS '" (enum-value->sql value) "'")]))


(defn- rename-enum!
  "Renames a PostgreSQL enum type."
  [ds old-name new-name]
  (jdbc/execute! ds [(str "ALTER TYPE " (ident->sql old-name)
                          " RENAME TO " (ident->sql new-name))]))


(defn- column-def
  "Generates column definition SQL."
  [field-name field-spec]
  (let [pg-type (field-type->pg field-spec)
        nullable (if (:nullable? field-spec) "" " NOT NULL")]
    (str (ident->sql field-name) " " pg-type nullable)))


(defn- create-table!
  "Creates a PostgreSQL table with id as primary key."
  [ds table-name fields]
  (let [cols (str/join ", "
                       (cons "id UUID PRIMARY KEY DEFAULT gen_random_uuid()"
                             (map (fn [[fname fspec]] (column-def fname fspec))
                                  fields)))]
    (jdbc/execute! ds [(str "CREATE TABLE " (ident->sql table-name) " (" cols ")")])))


(defn- add-column!
  "Adds a column to an existing table."
  [ds table-name field-name field-spec]
  (jdbc/execute! ds [(str "ALTER TABLE " (ident->sql table-name)
                          " ADD COLUMN " (column-def field-name field-spec))]))


(defn- rename-table!
  "Renames a PostgreSQL table."
  [ds old-name new-name]
  (jdbc/execute! ds [(str "ALTER TABLE " (ident->sql old-name)
                          " RENAME TO " (ident->sql new-name))]))


(defn- rename-column!
  "Renames a column in a table."
  [ds table-name old-col-name new-col-name]
  (jdbc/execute! ds [(str "ALTER TABLE " (ident->sql table-name)
                          " RENAME COLUMN " (ident->sql old-col-name)
                          " TO " (ident->sql new-col-name))]))


(defn- alter-column-type!
  "Changes column type (for safe widening)."
  [ds table-name col-name new-type-sql]
  (jdbc/execute! ds [(str "ALTER TABLE " (ident->sql table-name)
                          " ALTER COLUMN " (ident->sql col-name)
                          " TYPE " new-type-sql " USING " (ident->sql col-name)
                          "::" new-type-sql)]))


;; === Migration logic ===

(defn- check-removed!
  "Throws if any items in old-set are missing from new-set."
  [old-set new-set item-type get-name]
  (let [removed (set/difference old-set new-set)]
    (when (seq removed)
      (throw (ex-info (str "Destructive change: " item-type " removed")
                      {:type :destructive-change
                       (keyword (str "removed-" item-type))
                       (vec (map get-name removed))})))))


(defn- do-initialize
  "Performs schema initialization/migration."
  [ds schema]
  (ensure-metadata-table! ds)
  (let [metadata-rows (read-metadata-rows ds)
        old-metadata (parse-metadata metadata-rows)]
    (if (nil? old-metadata)
      ;; First-time initialization
      (do
        ;; Create enums first (tables may reference them)
        (doseq [[enum-name {:keys [values]}] (ds/enums schema)]
          (create-enum! ds enum-name (keys values)))
        ;; Create tables
        (doseq [entity-name (ds/entities schema)]
          (create-table! ds entity-name (ds/entity-fields schema entity-name)))
        ;; Save metadata
        (save-metadata! ds schema)
        ;; Return changes
        {:entities {:created (vec (ds/entities schema)) :renamed {}}
         :fields {:created (vec (for [e (ds/entities schema)
                                      [f _] (ds/entity-fields schema e)]
                                  {:entity e :field f}))
                  :renamed []}
         :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
         :enum-values {:created (vec (for [[enum-name {:keys [values]}] (ds/enums schema)
                                           [v _] values]
                                       {:enum enum-name :value v}))}})

      ;; Migration
      (let [old-entity-uuids (set (keys (:entities old-metadata)))
            new-entity-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))
            old-field-uuids (set (keys (:fields old-metadata)))
            new-field-uuids (set (for [e (ds/entities schema)
                                       [_ f] (ds/entity-fields schema e)]
                                   (:uuid f)))
            old-enum-uuids (set (keys (:enums old-metadata)))
            new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))
            old-enum-value-uuids (set (keys (:enum-values old-metadata)))
            new-enum-value-uuids (set (for [[_ {:keys [values]}] (ds/enums schema)
                                            [_ v] values] v))]

        ;; Check for destructive changes
        (check-removed! old-entity-uuids new-entity-uuids "entities"
                        #(get (:entities old-metadata) %))
        (check-removed! old-field-uuids new-field-uuids "fields"
                        #(get (:fields old-metadata) %))
        (check-removed! old-enum-uuids new-enum-uuids "enums"
                        #(get (:enums old-metadata) %))
        (check-removed! old-enum-value-uuids new-enum-value-uuids "enum values"
                        #(get (:enum-values old-metadata) %))

        ;; Check type compatibility
        (doseq [entity-name (ds/entities schema)]
          (let [entity-uuid (ds/entity-uuid schema entity-name)
                old-entity-name (get (:entities old-metadata) entity-uuid)
                old-fields (when old-entity-name
                             (current-columns ds (kw->snake-case old-entity-name)))]
            (when old-fields
              (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
                (let [field-uuid (:uuid field-spec)
                      old-field-info (get (:fields old-metadata) field-uuid)]
                  (when old-field-info
                    (let [old-field-name (:field old-field-info)
                          old-type (:type (get old-fields old-field-name))
                          new-type (:type field-spec)]
                      (when (and old-type (not (sp/safe-type-change? old-type new-type)))
                        (throw (ex-info "Destructive change: incompatible type change"
                                        {:type :destructive-change
                                         :entity entity-name
                                         :field field-name
                                         :old-type old-type
                                         :new-type new-type}))))))))))

        ;; Apply enum changes
        (let [created-enums (atom [])
              renamed-enums (atom {})
              created-enum-values (atom [])]
          (doseq [[enum-name {:keys [uuid values]}] (ds/enums schema)]
            (if-let [old-enum-name (get (:enums old-metadata) uuid)]
              (do
                ;; Existing enum - check for rename
                (when (not= old-enum-name enum-name)
                  (rename-enum! ds old-enum-name enum-name)
                  (swap! renamed-enums assoc old-enum-name enum-name))
                ;; Add new values
                (doseq [[value-kw value-uuid] values]
                  (when-not (get (:enum-values old-metadata) value-uuid)
                    (add-enum-value! ds enum-name value-kw)
                    (swap! created-enum-values conj {:enum enum-name :value value-kw}))))
              ;; New enum
              (do
                (create-enum! ds enum-name (keys values))
                (swap! created-enums conj enum-name)
                (doseq [[v _] values]
                  (swap! created-enum-values conj {:enum enum-name :value v})))))

          ;; Apply entity/field changes
          (let [created-entities (atom [])
                renamed-entities (atom {})
                created-fields (atom [])
                renamed-fields (atom [])]
            (doseq [entity-name (ds/entities schema)]
              (let [entity-uuid (ds/entity-uuid schema entity-name)
                    old-entity-name (get (:entities old-metadata) entity-uuid)]
                (if old-entity-name
                  (do
                    ;; Existing entity - check for rename
                    (when (not= old-entity-name entity-name)
                      (rename-table! ds old-entity-name entity-name)
                      (swap! renamed-entities assoc old-entity-name entity-name))
                    ;; Process fields
                    (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
                      (let [field-uuid (:uuid field-spec)
                            old-field-info (get (:fields old-metadata) field-uuid)]
                        (if old-field-info
                          (do
                            ;; Existing field - check for rename
                            (when (not= (:field old-field-info) field-name)
                              (rename-column! ds entity-name (:field old-field-info) field-name)
                              (swap! renamed-fields conj {:entity entity-name
                                                          :old-field (:field old-field-info)
                                                          :new-field field-name}))
                            ;; Check for type widening
                            (let [old-fields (current-columns ds (kw->snake-case entity-name))
                                  old-type (:type (get old-fields field-name))
                                  new-type (:type field-spec)]
                              (when (and old-type
                                         (not= old-type new-type)
                                         (sp/safe-type-change? old-type new-type))
                                (alter-column-type! ds entity-name field-name
                                                    (field-type->pg field-spec)))))
                          ;; New field
                          (do
                            (add-column! ds entity-name field-name field-spec)
                            (swap! created-fields conj {:entity entity-name :field field-name}))))))
                  ;; New entity
                  (do
                    (create-table! ds entity-name (ds/entity-fields schema entity-name))
                    (swap! created-entities conj entity-name)
                    (doseq [[f _] (ds/entity-fields schema entity-name)]
                      (swap! created-fields conj {:entity entity-name :field f}))))))

            ;; Save metadata
            (save-metadata! ds schema)

            ;; Return changes
            {:entities {:created @created-entities :renamed @renamed-entities}
             :fields {:created @created-fields :renamed @renamed-fields}
             :enums {:created @created-enums :renamed @renamed-enums}
             :enum-values {:created @created-enum-values}}))))))


;; === Storage record ===

(defrecord PostgresStorage
  [pool]

  sp/Storage

  (initialize
    [_this schema]
    (do-initialize pool schema))


  (close
    [_this]
    (close-pool pool)
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (set (map (comp keyword #(str/replace % "_" "-"))
              (current-tables pool))))


  (current-fields
    [_this entity-name]
    (let [cols (current-columns pool (kw->snake-case entity-name))]
      (when (seq cols) cols)))


  (current-enums
    [_this]
    (set (map (comp keyword #(str/replace % "_" "-"))
              (current-pg-enums pool))))


  (current-enum-values
    [_this enum-name]
    (let [enum-vals (current-enum-values-pg pool (kw->snake-case enum-name))]
      (when (seq enum-vals) enum-vals)))


  (schema-metadata
    [_this]
    (parse-metadata (read-metadata-rows pool))))


(defn create-storage
  "Creates a new PostgreSQL storage instance.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required)
   - :password - database password (required)
   - :pool-size - connection pool size (default 10)"
  [opts]
  (->PostgresStorage (create-pool opts)))
