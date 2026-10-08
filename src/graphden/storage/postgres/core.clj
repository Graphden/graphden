(ns graphden.storage.postgres.core
  "PostgreSQL implementation of Storage protocol.

   This module provides the main Storage record.
   DDL, migration, and metadata operations are delegated to sub-modules:
   - pool.clj - Connection pool management
   - util.clj - Type mapping and SQL helpers
   - metadata.clj - Metadata table operations
   - introspection.clj - Database introspection
   - ddl.clj - DDL operations (CREATE/ALTER)
   - migration.clj - Schema migration logic
   - crud.clj - CRUD operations"
  (:require
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.crud :as crud]
    [graphden.storage.postgres.graph :as graph]
    [graphden.storage.postgres.graph-epoch :as graph-epoch]
    [graphden.storage.postgres.introspection :as introspection]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.postgres.migration :as migration]
    [graphden.storage.postgres.pool :as pool]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-constraints :as gc]
    [graphden.storage.tx :as tx])
  (:import
    (java.sql
      SQLException)))


;; === Pool re-exports (for API compatibility) ===

(def create-pool
  "Creates a HikariCP connection pool. See pool/create-pool for details."
  pool/create-pool)


(def close-pool
  "Closes a HikariCP connection pool. See pool/close-pool for details."
  pool/close-pool)


;; === Storage record ===

(defn- evict-slot-rows!
  "Forget successful slot mutations, including batch branch reclamation."
  [slot-row-cache entity-name ids]
  (when (= :slot entity-name)
    (swap! slot-row-cache
           (fn [rows]
             (let [{:keys [generation writers]} (meta rows)
                   active (into #{} (filter deref) writers)
                   active (cond-> active
                            tx/*transaction-context* (conj (:active? tx/*transaction-context*)))]
               (with-meta (apply dissoc rows ids)
                 {:generation (inc (or generation 0)) :writers active}))))))


(defn- read-slot-row
  "Cache only reads that cannot overlap a slot mutation or its outer commit."
  [slot-row-cache id read-row]
  (let [snapshot @slot-row-cache
        {:keys [generation writers]} (meta snapshot)
        cacheable? (and (nil? tx/*transaction-context*) (not-any? deref writers))]
    (or (when (nil? tx/*transaction-context*) (get snapshot id))
        (when-some [row (read-row)]
          (when cacheable?
            (swap! slot-row-cache
                   (fn [rows]
                     (if (= generation (:generation (meta rows)))
                       (assoc rows id row)
                       rows))))
          row))))


(defn- build-entity-fields-index
  "Builds an index of entity-name -> field-specs from raw metadata.
   This is O(n) once at cache time instead of O(n) on every CRUD call.
   Returns {entity-name {field-name {:type t :nullable? n :enum-name e}}}."
  [cached-metadata]
  (when cached-metadata
    (->> (:fields cached-metadata)
         vals
         (group-by :entity)
         (reduce-kv
           (fn [acc entity-name fields]
             (assoc acc entity-name
                    (into {}
                          (map (fn [{:keys [field nullable? enum-name] field-type :type}]
                                 [field (cond-> {:type field-type :nullable? nullable?}
                                          enum-name (assoc :enum-name enum-name))])
                               fields))))
           {}))))


(defn- get-cached-metadata
  "Gets metadata from cache or reads from database. Cache is invalidated
   on schema changes (initialize).

   Double-checked: the fast path reads `@metadata-cache` with no lock; a
   miss takes `lock` (the monitor `initialize` / `close` hold while they
   reset the cache) and re-checks before reading the rows, so a populate
   never interleaves with a migration and runs at most once per miss.

   Returns nil WITHOUT caching when the metadata table does not exist yet
   (not initialized) — caching that nil would pin a stale answer through
   the initialization race. The cache is `reset!` only after
   `parse-metadata-lenient` returns; if parsing throws, it stays as it
   was. Adds a `:fields-by-entity` index for O(1) entity field lookups."
  [pool metadata-cache lock]
  (or @metadata-cache
      (locking lock
        (or @metadata-cache
            (try
              (let [raw-metadata (metadata/parse-metadata-lenient (metadata/read-metadata-rows pool))
                    result (when raw-metadata
                             (assoc raw-metadata
                                    :fields-by-entity (build-entity-fields-index raw-metadata)))]
                (reset! metadata-cache result)
                result)
              (catch SQLException e
                ;; Don't cache nil - table might be created soon
                (when-not (util/table-not-found? e)
                  (throw e))))))))


(defn- get-entity-fields
  "Gets field specs for an entity using cached index.
   O(1) lookup via pre-built :fields-by-entity index."
  [pool metadata-cache lock entity-name]
  (when-let [cached-metadata (get-cached-metadata pool metadata-cache lock)]
    (get (:fields-by-entity cached-metadata) entity-name)))


(defrecord PostgresStorage
  [pool metadata-cache lock slot-row-cache]

  writer/GraphWriteAuthorization

  (authorize-graph-write! [_ _entity-name _data _id] nil)


  sp/Storage

  (initialize
    [_this schema]
    (locking lock
      ;; Invalidate cache BEFORE migration to prevent stale reads.
      ;;
      ;; Why invalidate before, not after?
      ;; - If we invalidate after and migration partially fails (DDL succeeds
      ;;   but metadata update fails), concurrent readers during recovery
      ;;   would still see old cached metadata while DB has new schema.
      ;; - By invalidating before, any read during/after migration will
      ;;   fetch fresh state from DB, ensuring consistency.
      ;; - A metadata populate takes the same monitor, so it cannot
      ;;   interleave with the migration.
      ;;
      ;; If migration throws, cache stays nil - next read refreshes from DB.
      ;; This is correct: DB state is authoritative, cache is just optimization.
      (reset! metadata-cache nil)
      ;; Schema (re)init is the one lifecycle point where
      ;; previously-read slot rows can stop being true
      ;; (test fixtures DROP SCHEMA CASCADE, deploy
      ;; truncates) — drop the immutable-row cache with it.
      (reset! slot-row-cache {})
      (migration/do-initialize pool schema)))


  (close
    [_this]
    (locking lock
      ;; Clear cache first to prevent memory leak from stale references
      (reset! metadata-cache nil)
      (pool/close-pool pool))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (into #{} (map util/snake->kw) (introspection/current-tables pool)))


  (current-fields
    [_this entity-name]
    ;; O(1) lookup via pre-built :fields-by-entity index
    ;; Index already contains {field-name {:type t :nullable? n}} format
    ;; get returns nil if entity doesn't exist - no redundant check needed
    (when-let [cached-metadata (get-cached-metadata pool metadata-cache lock)]
      (get (:fields-by-entity cached-metadata) entity-name)))


  (current-enums
    [_this]
    (into #{} (map util/snake->kw) (introspection/current-pg-enums pool)))


  (current-enum-values
    [_this enum-name]
    (let [enum-vals (introspection/current-enum-values-pg pool (util/kw->snake-case enum-name))]
      (when (seq enum-vals) enum-vals)))


  (schema-metadata
    [_this]
    (get-cached-metadata pool metadata-cache lock))


  writer/GraphCreationAuthorization

  (authorize-graph-creation! [_ _fn-data _branch-id] nil)


  sp/StorageCRUD

  (create-entity
    [this entity-name data]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)]
                         (crud/create-entity pool entity-name data
                                             (get-entity-fields pool metadata-cache lock entity-name)))))


  (read-entity
    [_this entity-name id]
    ;; :slot identity rows are immutable during ordinary editing, yet
    ;; branch reclamation can delete unused slots. They are read one-at-
    ;; a-time on every value-form / binding type-check / provenance /
    ;; cross-org-ref guard — 163k single-row lookups on a 1.6k-row table
    ;; in one demo window. Cache the RAW row per storage instance (below
    ;; the org-scoping wrapper, whose own? gate still runs on every
    ;; read). nil results are NOT cached — a slot created later by
    ;; another instance must stay readable.
    (if (= :slot entity-name)
      (read-slot-row slot-row-cache id
                     #(crud/read-entity pool entity-name id
                                        (get-entity-fields pool metadata-cache lock entity-name)))
      (crud/read-entity pool entity-name id
                        (get-entity-fields pool metadata-cache lock entity-name))))


  (update-entity
    [this entity-name id data]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)]
                         ;; No live code path mutates :slot; the eviction is insurance so a
                         ;; future mutation path cannot silently serve a stale cached row.
                         (evict-slot-rows! slot-row-cache entity-name [id])
                         (crud/update-entity pool entity-name id data
                                             (get-entity-fields pool metadata-cache lock entity-name)))))


  (delete-entity
    [this entity-name id]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)]
                         (evict-slot-rows! slot-row-cache entity-name [id])
                         (crud/delete-entity pool entity-name id))))


  (query-entities
    [_this entity-name where]
    (crud/query-entities pool entity-name where
                         (get-entity-fields pool metadata-cache lock entity-name)))


  (query-entities
    [_this entity-name where opts]
    (crud/query-entities pool entity-name where
                         (get-entity-fields pool metadata-cache lock entity-name)
                         opts))


  (query-latest-per-group
    [_this entity-name where group-cols]
    (crud/query-latest-per-group pool entity-name where group-cols
                                 (get-entity-fields pool metadata-cache lock entity-name)))


  sp/StorageBoundedQuery

  (query-identity-candidates
    [_ entity-name where version-source max-candidates]
    (crud/query-identity-candidates pool entity-name where
                                    (get-entity-fields pool metadata-cache lock entity-name)
                                    version-source max-candidates))


  (query-bounded-entities
    [this entity-name where max-candidates]
    (let [candidates (sp/query-identity-candidates this entity-name where nil max-candidates)
          ids (mapv :id candidates)]
      (with-meta (if (seq ids)
                   (vec (sp/query-entities this entity-name (assoc where :id ids)))
                   [])
        (meta candidates))))


  sp/StorageBatchCRUD

  (create-entities
    [this entity-name data-seq]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)
                             rows (crud/create-entities pool entity-name data-seq
                                                        (get-entity-fields pool metadata-cache lock entity-name))]
                         (evict-slot-rows! slot-row-cache entity-name (map :id rows))
                         rows)))


  (read-entities
    [_this entity-name ids]
    (crud/read-entities pool entity-name ids
                        (get-entity-fields pool metadata-cache lock entity-name)))


  (update-entities
    [this entity-name data-seq]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)
                             rows (crud/update-entities pool entity-name data-seq
                                                        (get-entity-fields pool metadata-cache lock entity-name))]
                         (evict-slot-rows! slot-row-cache entity-name (map :id data-seq))
                         rows)))


  (upsert-entities
    [this entity-name data-seq]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)
                             rows (crud/upsert-entities pool entity-name data-seq
                                                        (get-entity-fields pool metadata-cache lock entity-name))]
                         (evict-slot-rows! slot-row-cache entity-name (map :id rows))
                         rows)))


  (delete-entities
    [this entity-name ids]
    (writer/with-write [this entity-name]
                       (let [pool (:pool this)
                             deleted (crud/delete-entities pool entity-name ids)]
                         (evict-slot-rows! slot-row-cache entity-name ids)
                         deleted)))


  (query-ref-many-owners
    [_this entity-name field-name target-id]
    (junction/read-junction-owners pool entity-name field-name target-id))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [this owner-fn-id ref-fn-id]
    (gc/validate-no-dependency-cycle! this owner-fn-id ref-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [this fn-id]
    (graph/resolve-execution-graph pool this fn-id))


  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (sp/classify-error (util/create-error-classifier) exception))


  (wrap-error
    [_this exception operation context]
    (sp/wrap-error (util/create-error-classifier) exception operation context)))


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
   - :max-lifetime - maximum connection lifetime in ms (default 1800000)

   Thread safety:
   - Metadata-cache populate / invalidate serialize on one monitor
     (reads of a warm cache take no lock)
   - CRUD operations use PostgreSQL's own transaction isolation"
  [opts]
  (graph-epoch/attach-state
    (->PostgresStorage (pool/create-pool opts) (atom nil) (Object.) (atom {}))))
