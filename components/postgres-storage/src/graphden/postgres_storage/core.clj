(ns graphden.postgres-storage.core
  "PostgreSQL implementation of Storage protocol."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)
    (org.postgresql.util
      PGobject)))


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


(defn- check-snake-case-collisions!
  "Checks that converting keywords to snake_case doesn't create collisions.
   E.g., :foo-bar and :foo_bar would both become 'foo_bar'.
   Throws if collisions detected."
  [context keywords]
  (let [snake-names (map kw->snake-case keywords)
        collisions (for [[snake freq] (frequencies snake-names)
                         :when (> freq 1)
                         :let [originals (filter #(= (kw->snake-case %) snake) keywords)]]
                     {:snake-case snake :originals (vec originals)})]
    (when (seq collisions)
      (throw (ex-info "Snake_case naming collision detected"
                      (merge context {:collisions (vec collisions)}))))))


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
  (when-not jdbc-url
    (throw (ex-info "jdbc-url is required for postgres connection pool"
                    {:provided-keys (cond-> []
                                      username (conj :username)
                                      password (conj :password))})))
  (let [config (HikariConfig.)]
    (HikariConfig/.setJdbcUrl config jdbc-url)
    (when username (HikariConfig/.setUsername config username))
    (when password (HikariConfig/.setPassword config password))
    (HikariConfig/.setMaximumPoolSize config pool-size)
    (HikariConfig/.setMinimumIdle config 2)
    (HikariConfig/.setConnectionTimeout config 30000)
    (HikariConfig/.setIdleTimeout config 600000)
    (HikariConfig/.setMaxLifetime config 1800000)
    (HikariDataSource. config)))


(defn close-pool
  "Closes a HikariCP connection pool. Idempotent - safe to call multiple times.
   Uses locking to prevent race conditions between check and close."
  [^HikariDataSource pool]
  (when pool
    (locking pool
      (when-not (HikariDataSource/.isClosed pool)
        (HikariDataSource/.close pool)))))


;; === Metadata table operations ===

(def ^:private metadata-table-name "_schema_metadata")


(defn- ensure-metadata-table!
  "Creates metadata table if it doesn't exist."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:create-table [(keyword metadata-table-name) :if-not-exists]
                              :with-columns [[:uuid :uuid [:primary-key]]
                                             [:kind :text [:not nil]]
                                             [:name :text [:not nil]]
                                             [:parent_uuid :uuid]
                                             [:extra :jsonb]]}
                             {:quoted true})))


(def ^:private query-timeout-seconds
  "Default timeout for metadata queries (in seconds)."
  30)


(defn- read-metadata-rows
  "Reads raw metadata rows for processing."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:select [:uuid :kind :name :parent_uuid :extra]
                              :from [(keyword metadata-table-name)]}
                             {:quoted true})
                 {:builder-fn rs/as-unqualified-lower-maps
                  :timeout query-timeout-seconds}))


(defn- extra->json
  "Converts extra map to JSON string for PostgreSQL JSONB.
   Keywords are converted to their name strings."
  [extra]
  (when extra
    (json/generate-string
      (into {}
            (map (fn [[k v]]
                   [(name k) (if (keyword? v) (name v) v)])
                 extra)))))


(defn- parse-extra
  "Parses the extra JSONB column. Handles both string and PGobject formats.
   String values are converted back to keywords."
  [extra]
  (when extra
    (let [s (cond
              (string? extra) extra
              (instance? PGobject extra) (PGobject/.getValue ^PGobject extra)
              :else (str extra))]
      (when (and (seq s) (not= s "null") (not= s "{}"))
        (let [parsed (json/parse-string s)]
          (when (map? parsed)
            (into {}
                  (map (fn [[k v]]
                         [(keyword k) (if (string? v) (keyword v) v)])
                       parsed))))))))


(defn- parse-metadata-impl
  "Parses metadata rows into structured format.
   When strict? is true, throws if orphaned entries are detected.
   When strict? is false, skips orphaned entries silently."
  [rows strict?]
  (when (seq rows)
    (let [uuid->row (into {} (map (fn [r] [(:uuid r) r]) rows))]
      (reduce
        (fn [acc row]
          (let [uuid (:uuid row)
                kind (keyword (:kind row))
                n (keyword (:name row))
                parent-uuid (:parent_uuid row)
                parent-row (get uuid->row parent-uuid)
                extra (parse-extra (:extra row))]
            (case kind
              :entity (assoc-in acc [:entities uuid] n)
              :field (if parent-row
                       (assoc-in acc [:fields uuid]
                                 (merge {:entity (keyword (:name parent-row))
                                         :field n}
                                        (when extra
                                          {:type (:type extra)
                                           :nullable? (:nullable? extra)})))
                       (if strict?
                         (throw (ex-info "Orphaned field entry in metadata"
                                         {:type :metadata-corruption
                                          :field-uuid uuid
                                          :field-name n
                                          :missing-parent-uuid parent-uuid}))
                         acc)) ; Skip orphaned entry in lenient mode
              :enum (assoc-in acc [:enums uuid] n)
              :enum-value (if parent-row
                            (assoc-in acc [:enum-values uuid]
                                      {:enum (keyword (:name parent-row))
                                       :value n})
                            (if strict?
                              (throw (ex-info "Orphaned enum-value entry in metadata"
                                              {:type :metadata-corruption
                                               :enum-value-uuid uuid
                                               :value-name n
                                               :missing-parent-uuid parent-uuid}))
                              acc)) ; Skip orphaned entry in lenient mode
              acc)))
        {:entities {} :fields {} :enums {} :enum-values {}}
        rows))))


(defn- parse-metadata
  "Parses metadata rows strictly. Throws on orphaned entries."
  [rows]
  (parse-metadata-impl rows true))


(defn- parse-metadata-lenient
  "Parses metadata rows leniently. Skips orphaned entries."
  [rows]
  (parse-metadata-impl rows false))


(defn- upsert-metadata!
  "Inserts or updates a metadata row."
  ([ds uuid kind meta-name parent-uuid]
   (upsert-metadata! ds uuid kind meta-name parent-uuid nil))
  ([ds uuid kind meta-name parent-uuid extra]
   (jdbc/execute! ds
                  (sql/format {:insert-into (keyword metadata-table-name)
                               :values [{:uuid uuid
                                         :kind (name kind)
                                         :name (name meta-name)
                                         :parent_uuid parent-uuid
                                         :extra [:cast (extra->json extra) :jsonb]}]
                               :on-conflict [:uuid]
                               :do-update-set [:name :parent_uuid :extra]}
                              {:quoted true}))))


(defn- save-entity-field-metadata!
  "Saves metadata for a single field."
  [tx entity-uuid field-name field-spec]
  (upsert-metadata! tx (:uuid field-spec) :field field-name entity-uuid
                    {:type (:type field-spec)
                     :nullable? (get field-spec :nullable? false)}))


(defn- save-entity-metadata!
  "Saves metadata for a single entity and its fields."
  [tx schema entity-name]
  (let [entity-uuid (ds/entity-uuid schema entity-name)]
    (upsert-metadata! tx entity-uuid :entity entity-name nil)
    (run! (fn [[field-name field-spec]]
            (save-entity-field-metadata! tx entity-uuid field-name field-spec))
          (ds/entity-fields schema entity-name))))


(defn- save-enum-value-metadata!
  "Saves metadata for a single enum value."
  [tx enum-uuid value-kw value-uuid]
  (upsert-metadata! tx value-uuid :enum-value value-kw enum-uuid))


(defn- save-enum-metadata!
  "Saves metadata for a single enum and its values."
  [tx enum-name {:keys [uuid values]}]
  (upsert-metadata! tx uuid :enum enum-name nil)
  (run! (fn [[value-kw value-uuid]]
          (save-enum-value-metadata! tx uuid value-kw value-uuid))
        values))


(defn- save-metadata!
  "Saves complete metadata to table (truncate + insert all).
   Uses a transaction to ensure atomicity."
  [ds schema]
  (jdbc/with-transaction [tx ds]
                         (jdbc/execute! tx (sql/format {:truncate (keyword metadata-table-name)}
                                                       {:quoted true}))
                         (run! #(save-entity-metadata! tx schema %) (ds/entities schema))
                         (run! (fn [[enum-name enum-def]] (save-enum-metadata! tx enum-name enum-def))
                               (ds/enums schema))))


;; === Introspection ===

(defn- current-tables
  "Returns set of table names in public schema (excluding metadata table)."
  [ds]
  (let [rows (jdbc/execute! ds
                            (sql/format {:select [:table_name]
                                         :from [:information_schema.tables]
                                         :where [:and
                                                 [:= :table_schema "public"]
                                                 [:= :table_type "BASE TABLE"]]}
                                        {:quoted true})
                            {:builder-fn rs/as-unqualified-lower-maps
                             :timeout query-timeout-seconds})]
    (set (remove #(= % metadata-table-name)
                 (map :table_name rows)))))


(defn- current-columns
  "Returns map of column definitions for a table."
  [ds table-name]
  (let [rows (jdbc/execute! ds
                            (sql/format {:select [:column_name :data_type :udt_name :is_nullable]
                                         :from [:information_schema.columns]
                                         :where [:and
                                                 [:= :table_schema "public"]
                                                 [:= :table_name table-name]
                                                 [:<> :column_name "id"]]}
                                        {:quoted true})
                            {:builder-fn rs/as-unqualified-lower-maps
                             :timeout query-timeout-seconds})]
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
                            (sql/format {:select [:t.typname]
                                         :from [[:pg_type :t]]
                                         :join [[:pg_catalog.pg_namespace :n]
                                                [:= :n.oid :t.typnamespace]]
                                         :where [:and
                                                 [:= :n.nspname "public"]
                                                 [:= :t.typtype "e"]]}
                                        {:quoted true})
                            {:builder-fn rs/as-unqualified-lower-maps
                             :timeout query-timeout-seconds})]
    (set (map :typname rows))))


(defn- sql->enum-value
  "Converts SQL enum value back to keyword (reverses enum-value->sql)."
  [s]
  (keyword (str/replace s "_" "-")))


(defn- current-enum-values-pg
  "Returns set of values for a PostgreSQL enum type.
   Converts SQL snake_case back to kebab-case keywords."
  [ds enum-name]
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
                             :timeout query-timeout-seconds})]
    (set (map (comp sql->enum-value :enumlabel) rows))))


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


(defn- build-column-spec
  "Builds HoneySQL column specification for CREATE TABLE."
  [field-name field-spec]
  (let [col-name (keyword (kw->snake-case field-name))
        pg-type-str (field-type->pg field-spec)
        ;; Handle enum types specially - they're quoted identifiers
        col-type (if (str/starts-with? pg-type-str "\"")
                   [:raw pg-type-str]
                   (keyword (str/lower-case pg-type-str)))]
    (if (:nullable? field-spec)
      [col-name col-type]
      [col-name col-type [:not nil]])))


(defn- create-table!
  "Creates a PostgreSQL table with id as primary key."
  [ds table-name fields]
  (let [columns (into [[:id :uuid [:primary-key] [:default [:raw "gen_random_uuid()"]]]]
                      (map (fn [[fname fspec]] (build-column-spec fname fspec)) fields))]
    (jdbc/execute! ds
                   (sql/format {:create-table (keyword (kw->snake-case table-name))
                                :with-columns columns}
                               {:quoted true}))))


(defn- add-column!
  "Adds a column to an existing table."
  [ds table-name field-name field-spec]
  (jdbc/execute! ds
                 (sql/format {:alter-table (keyword (kw->snake-case table-name))
                              :add-column (build-column-spec field-name field-spec)}
                             {:quoted true})))


(defn- rename-table!
  "Renames a PostgreSQL table."
  [ds old-name new-name]
  (jdbc/execute! ds
                 (sql/format {:alter-table (keyword (kw->snake-case old-name))
                              :rename-table (keyword (kw->snake-case new-name))}
                             {:quoted true})))


(defn- rename-column!
  "Renames a column in a table."
  [ds table-name old-col-name new-col-name]
  (jdbc/execute! ds
                 (sql/format {:alter-table (keyword (kw->snake-case table-name))
                              :rename-column [(keyword (kw->snake-case old-col-name))
                                              (keyword (kw->snake-case new-col-name))]}
                             {:quoted true})))


(defn- alter-column-type!
  "Changes column type (for safe widening)."
  [ds table-name col-name new-type-sql]
  ;; ALTER COLUMN with USING clause needs raw SQL for complex expressions
  (jdbc/execute! ds [(str "ALTER TABLE " (ident->sql table-name)
                          " ALTER COLUMN " (ident->sql col-name)
                          " TYPE " new-type-sql " USING " (ident->sql col-name)
                          "::" new-type-sql)]))


;; === Migration logic ===


(defn- check-entity-field-collisions!
  "Checks snake_case collisions for a single entity's fields."
  [schema entity-name]
  (check-snake-case-collisions! {:context "fields" :entity entity-name}
                                (keys (ds/entity-fields schema entity-name))))


(defn- create-single-enum!
  "Creates a single enum during first-time initialization."
  [ds enum-name {:keys [values]}]
  (create-enum! ds enum-name (keys values)))


(defn- create-single-entity!
  "Creates a single entity table during first-time initialization."
  [ds schema entity-name]
  (create-table! ds entity-name (ds/entity-fields schema entity-name)))


(defn- check-single-field-type!
  "Checks type compatibility for a single field."
  [entity-name old-db-fields old-metadata field-name field-spec]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (let [old-field-name (:field old-field-info)
            old-db-field (get old-db-fields old-field-name)]
        ;; Verify column exists in database
        (when (and (seq old-db-fields) (nil? old-db-field))
          (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                          {:type :metadata-inconsistency
                           :entity entity-name
                           :field field-name
                           :expected-column (kw->snake-case old-field-name)})))
        ;; Use type from metadata (preserves original types correctly)
        (let [old-type (:type old-field-info)
              new-type (:type field-spec)
              old-nullable? (:nullable? old-field-info)
              new-nullable? (get field-spec :nullable? false)]
          (sp/check-type-change! entity-name field-name old-type new-type)
          (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?))))))


(defn- check-entity-fields-type!
  "Checks type compatibility for all fields of a single entity."
  [ds schema old-metadata entity-name]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)
        old-db-fields (when old-entity-name
                        (current-columns ds (kw->snake-case old-entity-name)))]
    (when old-entity-name
      (run! (fn [[field-name field-spec]]
              (check-single-field-type! entity-name old-db-fields old-metadata field-name field-spec))
            (ds/entity-fields schema entity-name)))))


(defn- process-existing-enum-value!
  "Adds a new value to existing enum if not present in old metadata."
  [ds old-metadata enum-name value-kw value-uuid created-enum-values]
  (when-not (get (:enum-values old-metadata) value-uuid)
    (add-enum-value! ds enum-name value-kw)
    (swap! created-enum-values conj {:enum enum-name :value value-kw})))


(defn- process-single-enum!
  "Processes a single enum during migration (rename or create)."
  [ds old-metadata enum-name {:keys [uuid values]} created-enums renamed-enums created-enum-values]
  (if-let [old-enum-name (get (:enums old-metadata) uuid)]
    (do
      ;; Existing enum - check for rename
      (when (not= old-enum-name enum-name)
        (rename-enum! ds old-enum-name enum-name)
        (swap! renamed-enums assoc old-enum-name enum-name))
      ;; Add new values
      (run! (fn [[value-kw value-uuid]]
              (process-existing-enum-value! ds old-metadata enum-name value-kw value-uuid created-enum-values))
            values))
    ;; New enum
    (do
      (create-enum! ds enum-name (keys values))
      (swap! created-enums conj enum-name)
      (run! (fn [[v _]] (swap! created-enum-values conj {:enum enum-name :value v}))
            values))))


(defn- process-existing-field!
  "Processes an existing field during migration (rename or type widening)."
  [ds entity-name field-name field-spec old-field-info renamed-fields]
  ;; Check for rename
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


(defn- process-single-field!
  "Processes a single field during migration."
  [ds old-metadata entity-name field-name field-spec created-fields renamed-fields]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (if old-field-info
      (process-existing-field! ds entity-name field-name field-spec old-field-info renamed-fields)
      ;; New field
      (do
        (add-column! ds entity-name field-name field-spec)
        (swap! created-fields conj {:entity entity-name :field field-name})))))


(defn- process-existing-entity!
  "Processes an existing entity during migration."
  [ds schema old-metadata entity-name old-entity-name
   renamed-entities created-fields renamed-fields]
  ;; Check for rename
  (when (not= old-entity-name entity-name)
    (rename-table! ds old-entity-name entity-name)
    (swap! renamed-entities assoc old-entity-name entity-name))
  ;; Process fields
  (run! (fn [[field-name field-spec]]
          (process-single-field! ds old-metadata entity-name field-name field-spec
                                 created-fields renamed-fields))
        (ds/entity-fields schema entity-name)))


(defn- process-single-entity!
  "Processes a single entity during migration (existing or new)."
  [ds schema old-metadata entity-name created-entities renamed-entities created-fields renamed-fields]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)]
    (if old-entity-name
      (process-existing-entity! ds schema old-metadata entity-name old-entity-name
                                renamed-entities created-fields renamed-fields)
      ;; New entity
      (do
        (create-table! ds entity-name (ds/entity-fields schema entity-name))
        (swap! created-entities conj entity-name)
        (run! (fn [[f _]] (swap! created-fields conj {:entity entity-name :field f}))
              (ds/entity-fields schema entity-name))))))


(defn- do-initialize
  "Performs schema initialization/migration."
  [ds schema]
  ;; Check for snake_case naming collisions before any DDL
  (check-snake-case-collisions! {:context "entities"} (ds/entities schema))
  (run! #(check-entity-field-collisions! schema %) (ds/entities schema))
  (check-snake-case-collisions! {:context "enums"} (keys (ds/enums schema)))

  (ensure-metadata-table! ds)
  (let [metadata-rows (read-metadata-rows ds)
        old-metadata (parse-metadata metadata-rows)]
    (if (nil? old-metadata)
      ;; First-time initialization
      (do
        ;; Create enums first (tables may reference them)
        (run! (fn [[enum-name enum-def]] (create-single-enum! ds enum-name enum-def))
              (ds/enums schema))
        ;; Create tables
        (run! #(create-single-entity! ds schema %) (ds/entities schema))
        ;; Save metadata
        (save-metadata! ds schema)
        ;; Return changes
        (sp/build-first-init-changes schema))

      ;; Migration
      (do
        ;; Check for destructive changes (removals)
        (sp/check-all-removals! old-metadata schema)

        ;; Check type compatibility
        (run! #(check-entity-fields-type! ds schema old-metadata %) (ds/entities schema))

        ;; Apply enum changes
        (let [created-enums (atom [])
              renamed-enums (atom {})
              created-enum-values (atom [])]
          (run! (fn [[enum-name enum-def]]
                  (process-single-enum! ds old-metadata enum-name enum-def
                                        created-enums renamed-enums created-enum-values))
                (ds/enums schema))

          ;; Apply entity/field changes
          (let [created-entities (atom [])
                renamed-entities (atom {})
                created-fields (atom [])
                renamed-fields (atom [])]
            (run! #(process-single-entity! ds schema old-metadata %
                                           created-entities renamed-entities
                                           created-fields renamed-fields)
                  (ds/entities schema))

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
    (let [metadata (try
                     (parse-metadata-lenient (read-metadata-rows pool))
                     (catch Exception _ nil))
          ;; Check if entity exists in metadata
          entity-exists? (some #(= % entity-name) (vals (:entities metadata)))]
      (when entity-exists?
        (let [entity-fields (->> (:fields metadata)
                                 (vals)
                                 (filter #(= (:entity %) entity-name)))]
          (into {}
                (map (fn [{:keys [field nullable?] :as f}]
                       [field {:type (:type f) :nullable? nullable?}])
                     entity-fields))))))


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
    (try
      (parse-metadata-lenient (read-metadata-rows pool))
      (catch Exception _
        ;; Table doesn't exist or other error - storage not initialized
        nil))))


(defn create-storage
  "Creates a new PostgreSQL storage instance.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required)
   - :password - database password (required)
   - :pool-size - connection pool size (default 10)"
  [opts]
  (->PostgresStorage (create-pool opts)))
