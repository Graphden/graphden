(ns graphden.postgres-storage.core
  "PostgreSQL implementation of Storage protocol.

   This module provides connection pool management and the main Storage record.
   DDL, migration, and metadata operations are delegated to sub-modules:
   - util.clj - Type mapping and SQL helpers
   - metadata.clj - Metadata table operations
   - introspection.clj - Database introspection
   - ddl.clj - DDL operations (CREATE/ALTER)
   - migration.clj - Schema migration logic
   - crud.clj - CRUD operations
   - constraints.clj - Graph constraints validation"
  (:require
    [clojure.string :as str]
    [graphden.postgres-storage.constraints :as constraints]
    [graphden.postgres-storage.crud :as crud]
    [graphden.postgres-storage.introspection :as introspection]
    [graphden.postgres-storage.metadata :as metadata]
    [graphden.postgres-storage.migration :as migration]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)
    (java.sql
      SQLException)))


;; === Connection pool ===

(defn create-pool
  "Creates a HikariCP connection pool.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required, non-empty)
   - :password - database password (required, non-empty)
   - :pool-size - maximum pool size (default 10)
   - :min-idle - minimum idle connections (default 2)
   - :connection-timeout - connection timeout in ms (default 30000)
   - :idle-timeout - idle connection timeout in ms (default 600000)
   - :max-lifetime - maximum connection lifetime in ms (default 1800000)
   - :leak-detection-threshold - connection leak detection in ms (default 60000)"
  [{:keys [jdbc-url username password pool-size min-idle
           connection-timeout idle-timeout max-lifetime leak-detection-threshold]
    :or {pool-size 10
         min-idle 2
         connection-timeout 30000
         idle-timeout 600000
         max-lifetime 1800000
         leak-detection-threshold 60000}}]
  (when-not jdbc-url
    (throw (ex-info "jdbc-url is required for postgres connection pool"
                    {:reason :missing-jdbc-url})))
  (when-not (and username (seq (str/trim username)))
    (throw (ex-info "username is required and cannot be empty"
                    {:reason :missing-username})))
  (when-not (and password (seq (str/trim password)))
    (throw (ex-info "password is required and cannot be empty"
                    {:reason :missing-password})))
  (let [config (HikariConfig.)]
    (HikariConfig/.setJdbcUrl config jdbc-url)
    (HikariConfig/.setUsername config username)
    (HikariConfig/.setPassword config password)
    (HikariConfig/.setMaximumPoolSize config pool-size)
    (HikariConfig/.setMinimumIdle config min-idle)
    (HikariConfig/.setConnectionTimeout config connection-timeout)
    (HikariConfig/.setIdleTimeout config idle-timeout)
    (HikariConfig/.setMaxLifetime config max-lifetime)
    (HikariConfig/.setLeakDetectionThreshold config leak-detection-threshold)
    (HikariDataSource. config)))


(defn close-pool
  "Closes a HikariCP connection pool. Idempotent - safe to call multiple times.
   Uses locking to prevent race conditions between check and close."
  [^HikariDataSource pool]
  (when pool
    (locking pool
      (when-not (HikariDataSource/.isClosed pool)
        (HikariDataSource/.close pool)))))


;; === Storage record ===

(defn- get-cached-metadata
  "Gets metadata from cache or reads from database.
   Thread-safe: uses locking to prevent concurrent database reads.
   Cache is invalidated on schema changes (initialize)."
  [pool metadata-cache lock]
  (or @metadata-cache
      (locking lock
        ;; Double-check after acquiring lock
        (or @metadata-cache
            (let [result (try
                           (metadata/parse-metadata-lenient (metadata/read-metadata-rows pool))
                           (catch SQLException e
                             (when-not (util/table-not-found? e) (throw e))
                             nil))]
              (reset! metadata-cache result)
              result)))))


(defn- extract-entity-fields
  "Extracts field specs for an entity from cached metadata.
   Returns a map of field-name -> {:type type :nullable? nullable?}."
  [cached-metadata entity-name]
  (when cached-metadata
    (->> (:fields cached-metadata)
         (vals)
         (filter #(= (:entity %) entity-name))
         (map (fn [{:keys [field nullable?] field-type :type}]
                [field {:type field-type :nullable? nullable?}]))
         (into {}))))


(defrecord PostgresStorage
  [pool metadata-cache lock]

  sp/Storage

  (initialize
    [_this schema]
    (locking lock
      ;; Invalidate cache on schema changes
      (reset! metadata-cache nil)
      (migration/do-initialize pool schema)))


  (close
    [_this]
    (locking lock
      (close-pool pool))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (set (map (comp keyword #(str/replace % "_" "-"))
              (introspection/current-tables pool))))


  (current-fields
    [_this entity-name]
    (when-let [cached-metadata (get-cached-metadata pool metadata-cache lock)]
      (when (some #(= % entity-name) (vals (:entities cached-metadata)))
        (let [entity-fields (->> (:fields cached-metadata)
                                 (vals)
                                 (filter #(= (:entity %) entity-name)))]
          (into {}
                (map (fn [{:keys [field nullable?] :as f}]
                       [field {:type (:type f) :nullable? nullable?}])
                     entity-fields))))))


  (current-enums
    [_this]
    (set (map (comp keyword #(str/replace % "_" "-"))
              (introspection/current-pg-enums pool))))


  (current-enum-values
    [_this enum-name]
    (let [enum-vals (introspection/current-enum-values-pg pool (util/kw->snake-case enum-name))]
      (when (seq enum-vals) enum-vals)))


  (schema-metadata
    [_this]
    (get-cached-metadata pool metadata-cache lock))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    (let [cached-metadata (get-cached-metadata pool metadata-cache lock)
          fields (extract-entity-fields cached-metadata entity-name)]
      (crud/create-entity pool entity-name data fields)))


  (read-entity
    [_this entity-name id]
    (crud/read-entity pool entity-name id))


  (update-entity
    [_this entity-name id data]
    (let [cached-metadata (get-cached-metadata pool metadata-cache lock)
          fields (extract-entity-fields cached-metadata entity-name)]
      (crud/update-entity pool entity-name id data fields)))


  (delete-entity
    [_this entity-name id]
    (crud/delete-entity pool entity-name id))


  (query-entities
    [_this entity-name where]
    (crud/query-entities pool entity-name where))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (constraints/validate-parent-same-schema! pool fn-id parent-fn-id))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (constraints/validate-no-arg-override! pool fn-id arg-schema-id))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (constraints/validate-arg-schema-belongs-to-fn! pool fn-id arg-schema-id))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (constraints/validate-no-inheritance-cycle! pool fn-id parent-fn-id))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (constraints/validate-no-dependency-cycle! pool owner-fn-id value-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (crud/resolve-execution-graph pool fn-id)))


(defn create-storage
  "Creates a new PostgreSQL storage instance.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username
   - :password - database password
   - :pool-size - maximum pool size (default 10)
   - :min-idle - minimum idle connections (default 2)
   - :connection-timeout - connection timeout in ms (default 30000)
   - :idle-timeout - idle connection timeout in ms (default 600000)
   - :max-lifetime - maximum connection lifetime in ms (default 1800000)"
  [opts]
  (->PostgresStorage (create-pool opts) (atom nil) (Object.)))
