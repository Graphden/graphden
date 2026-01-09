(ns graphden.cache-postgres.interface
  "PostgreSQL implementation of CacheStorage protocol.

   This component stores execution graph caches in PostgreSQL using
   tables defined by cache-data-schema:
   - cached_fn, cached_fn_schema, cached_arg_schema: denormalized graph data
   - cached_merged_arg: precomputed merged argument values
   - cache_fn_dep, cache_fn_schema_dep, cache_arg_schema_dep: dependency tracking

   Usage:
   (def cache (create-cache datasource))
   (cache/save-cache! cache fn-id graph deps)
   (cache/get-cached-graph cache fn-id)"
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cache-protocol.value-codec :as codec]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (org.postgresql.util
      PGobject)))


;; === SQL helpers ===

(defn- value->enum
  "Converts keyword to PostgreSQL enum PGobject."
  [v enum-name]
  (doto (PGobject.)
    (PGobject/.setType (sp/kw->snake-case enum-name))
    (PGobject/.setValue (sp/kw->snake-case v))))


(defn- execute!
  "Executes SQL with result-set builder."
  [ds sql-map]
  (jdbc/execute! ds (sql/format sql-map)
                 {:builder-fn rs/as-unqualified-kebab-maps}))


(defn- execute-one!
  "Executes SQL and returns single result."
  [ds sql-map]
  (jdbc/execute-one! ds (sql/format sql-map)
                     {:builder-fn rs/as-unqualified-kebab-maps}))


;; === Graph loading helpers ===

(defn- load-cached-fns
  "Loads cached fn records for a cache-id."
  [ds cache-id]
  (->> (execute! ds {:select [:fn-id :name :fn-schema-id :parent-fn-id]
                     :from [:cached-fn]
                     :where [:= :cache-id cache-id]})
       (map (fn [{:keys [fn-id] :as record}]
              [fn-id (-> record
                         (dissoc :fn-id)
                         (assoc :id fn-id))]))
       (into {})))


(defn- load-cached-fn-schemas
  "Loads cached fn-schema records for a cache-id."
  [ds cache-id]
  (->> (execute! ds {:select [:fn-schema-id :name :base-fn-name :returned-type]
                     :from [:cached-fn-schema]
                     :where [:= :cache-id cache-id]})
       (map (fn [{:keys [fn-schema-id] :as record}]
              [fn-schema-id (-> record
                                (dissoc :fn-schema-id)
                                (assoc :id fn-schema-id)
                                (update :returned-type keyword))]))
       (into {})))


(defn- load-cached-arg-schemas
  "Loads cached arg-schema records for a cache-id."
  [ds cache-id]
  (->> (execute! ds {:select [:arg-schema-id :fn-schema-id :name :type :required]
                     :from [:cached-arg-schema]
                     :where [:= :cache-id cache-id]})
       (map (fn [{:keys [arg-schema-id] :as record}]
              [arg-schema-id (-> record
                                 (dissoc :arg-schema-id)
                                 (assoc :id arg-schema-id)
                                 (update :type keyword))]))
       (into {})))


(defn- parse-value
  "Parses a cached value from JSON storage format."
  [value-json]
  (when value-json
    (let [raw-value (cond
                      (instance? PGobject value-json)
                      (PGobject/.getValue value-json)

                      (string? value-json)
                      value-json

                      :else nil)
          parsed (when raw-value
                   (json/parse-string raw-value true))]
      (codec/parse-cached-value parsed))))


(defn- load-cached-merged-args
  "Loads cached merged argument values for a cache-id.
   Returns {fn-id -> {arg-schema-id -> resolved-value}}."
  [ds cache-id]
  (->> (execute! ds {:select [:fn-id :arg-schema-id :value]
                     :from [:cached-merged-arg]
                     :where [:= :cache-id cache-id]})
       (reduce (fn [acc {:keys [fn-id arg-schema-id value]}]
                 (assoc-in acc [fn-id arg-schema-id] (parse-value value)))
               {})))


(defn- cache-exists-query
  "Returns true if cache exists for fn-id."
  [ds fn-id]
  (some? (execute-one! ds {:select [1]
                           :from [:cached-fn]
                           :where [:and
                                   [:= :cache-id fn-id]
                                   [:= :fn-id fn-id]]
                           :limit 1})))


(defn- load-all-cache-data
  "Loads all cache data in parallel. Returns nil if cache doesn't exist.
   Optimizes N+1 by running 4 queries concurrently instead of sequentially."
  [ds cache-id]
  ;; Start all queries in parallel using futures
  (let [fns-future (future (load-cached-fns ds cache-id))
        fn-schemas-future (future (load-cached-fn-schemas ds cache-id))
        arg-schemas-future (future (load-cached-arg-schemas ds cache-id))
        resolved-args-future (future (load-cached-merged-args ds cache-id))
        ;; Wait for all results
        fns @fns-future]
    ;; If no fns found, cache doesn't exist - don't wait for other queries
    (when (seq fns)
      {:fns fns
       :fn-schemas @fn-schemas-future
       :arg-schemas @arg-schemas-future
       :resolved-args @resolved-args-future})))


;; === Graph saving helpers ===

(defn- encode-value
  "Encodes a value for JSON storage."
  [value]
  (when-let [formatted (codec/format-cached-value value)]
    (json/generate-string formatted)))


(defn- save-cached-fns!
  "Saves denormalized fn records to cache using batch insert."
  [ds cache-id fns]
  (when (seq fns)
    (let [values (mapv (fn [[fn-id fn-record]]
                         {:cache-id cache-id
                          :fn-id fn-id
                          :name (:name fn-record)
                          :fn-schema-id (:fn-schema-id fn-record)
                          :parent-fn-id (:parent-fn-id fn-record)})
                       fns)]
      (execute! ds {:insert-into :cached-fn
                    :values values
                    :on-conflict [:cache-id :fn-id]
                    :do-update-set [:name :fn-schema-id :parent-fn-id]}))))


(defn- save-cached-fn-schemas!
  "Saves denormalized fn-schema records to cache."
  [ds cache-id fn-schemas]
  (when (seq fn-schemas)
    (doseq [[schema-id schema-record] fn-schemas]
      (jdbc/execute! ds
                     (sql/format {:insert-into :cached-fn-schema
                                  :values [{:cache-id cache-id
                                            :fn-schema-id schema-id
                                            :name (:name schema-record)
                                            :base-fn-name (:base-fn-name schema-record)
                                            :returned-type :?returned-type}]
                                  :on-conflict [:cache-id :fn-schema-id]
                                  :do-update-set [:name :base-fn-name :returned-type]}
                                 {:params {:returned-type (value->enum (:returned-type schema-record) :value-kind)}})))))


(defn- save-cached-arg-schemas!
  "Saves denormalized arg-schema records to cache."
  [ds cache-id arg-schemas]
  (when (seq arg-schemas)
    (doseq [[arg-schema-id arg-schema-record] arg-schemas]
      (jdbc/execute! ds
                     (sql/format {:insert-into :cached-arg-schema
                                  :values [{:cache-id cache-id
                                            :arg-schema-id arg-schema-id
                                            :fn-schema-id (:fn-schema-id arg-schema-record)
                                            :name (:name arg-schema-record)
                                            :type :?type
                                            :required (:required arg-schema-record)}]
                                  :on-conflict [:cache-id :arg-schema-id]
                                  :do-update-set [:fn-schema-id :name :type :required]}
                                 {:params {:type (value->enum (:type arg-schema-record) :value-kind)}})))))


(defn- save-cached-merged-args!
  "Saves precomputed merged argument values to cache using batch insert."
  [ds cache-id resolved-args]
  (when (seq resolved-args)
    (let [values (vec (for [[fn-id args-map] resolved-args
                            [arg-schema-id value] args-map]
                        {:cache-id cache-id
                         :fn-id fn-id
                         :arg-schema-id arg-schema-id
                         :value [:cast (encode-value value) :jsonb]}))]
      (when (seq values)
        (execute! ds {:insert-into :cached-merged-arg
                      :values values
                      :on-conflict [:cache-id :fn-id :arg-schema-id]
                      :do-update-set {:value :excluded.value}})))))


(defn- save-deps!
  "Saves dependency records with ref-counts using batch insert.
   Parameters:
   - ds: datasource
   - cache-id: UUID of the cache
   - table: target table keyword (e.g., :cache-fn-dep)
   - dep-key: dependency column keyword (e.g., :dep-fn-id)
   - deps: map of {dep-id -> ref-count}"
  [ds cache-id table dep-key deps]
  (when (seq deps)
    (let [values (mapv (fn [[dep-id ref-count]]
                         {:cache-id cache-id
                          dep-key dep-id
                          :ref-count ref-count})
                       deps)]
      (execute! ds {:insert-into table
                    :values values
                    :on-conflict [:cache-id dep-key]
                    :do-update-set [:ref-count]}))))


;; === Cache deletion ===

(defn- delete-cache-data!
  "Deletes all cache data for a fn-id.
   Returns true if any data was deleted."
  [ds fn-id]
  ;; Delete in reverse dependency order
  ;; (dependencies first, then cached data, due to FK constraints if any)
  (let [deleted-deps (+ (:next.jdbc/update-count
                          (execute-one! ds {:delete-from :cache-fn-dep
                                            :where [:= :cache-id fn-id]}))
                        (:next.jdbc/update-count
                          (execute-one! ds {:delete-from :cache-fn-schema-dep
                                            :where [:= :cache-id fn-id]}))
                        (:next.jdbc/update-count
                          (execute-one! ds {:delete-from :cache-arg-schema-dep
                                            :where [:= :cache-id fn-id]})))
        deleted-merged (:next.jdbc/update-count
                         (execute-one! ds {:delete-from :cached-merged-arg
                                           :where [:= :cache-id fn-id]}))
        deleted-arg-schemas (:next.jdbc/update-count
                              (execute-one! ds {:delete-from :cached-arg-schema
                                                :where [:= :cache-id fn-id]}))
        deleted-fn-schemas (:next.jdbc/update-count
                             (execute-one! ds {:delete-from :cached-fn-schema
                                               :where [:= :cache-id fn-id]}))
        deleted-fns (:next.jdbc/update-count
                      (execute-one! ds {:delete-from :cached-fn
                                        :where [:= :cache-id fn-id]}))]
    (pos? (+ deleted-deps deleted-merged deleted-arg-schemas
             deleted-fn-schemas deleted-fns))))


;; === Dependency lookup ===

(defn- find-caches-by-dep
  "Returns set of cache-ids that depend on a given entity.
   Parameters:
   - ds: datasource
   - table: dependency table keyword (e.g., :cache-fn-dep)
   - dep-key: dependency column keyword (e.g., :dep-fn-id)
   - dep-id: UUID of the dependency"
  [ds table dep-key dep-id]
  (->> (execute! ds {:select [:cache-id]
                     :from [table]
                     :where [:= dep-key dep-id]})
       (map :cache-id)
       (into #{})))


;; === PostgresCache record ===

(defrecord PostgresCache
  [datasource]

  cache/CacheStorage

  (get-cached-graph
    [_ fn-id]
    ;; Load all data in parallel - no separate exists check needed
    (when-let [data (load-all-cache-data datasource fn-id)]
      (cache/build-cached-graph (:fns data) (:fn-schemas data)
                                (:arg-schemas data) (:resolved-args data))))


  (cache-exists?
    [_ fn-id]
    (cache-exists-query datasource fn-id))


  (save-cache!
    [_ fn-id graph dependencies]
    (cache/validate-uuid! fn-id "fn-id")
    (cache/validate-graph! graph)
    (cache/validate-dependencies! dependencies)
    (log/debug "Saving cache for fn-id" fn-id)
    (jdbc/with-transaction [tx datasource]
                           ;; Delete existing cache data first (if any)
                           (delete-cache-data! tx fn-id)
                           ;; Save graph data
                           (save-cached-fns! tx fn-id (:fns graph))
                           (save-cached-fn-schemas! tx fn-id (:fn-schemas graph))
                           (save-cached-arg-schemas! tx fn-id (:arg-schemas graph))
                           (save-cached-merged-args! tx fn-id (:resolved-args graph))
                           ;; Save dependencies
                           (save-deps! tx fn-id :cache-fn-dep :dep-fn-id (:fn-ids dependencies))
                           (save-deps! tx fn-id :cache-fn-schema-dep :dep-fn-schema-id (:fn-schema-ids dependencies))
                           (save-deps! tx fn-id :cache-arg-schema-dep :dep-arg-schema-id (:arg-schema-ids dependencies))))


  (delete-cache!
    [_ fn-id]
    (log/debug "Deleting cache for fn-id" fn-id)
    (jdbc/with-transaction [tx datasource]
                           (delete-cache-data! tx fn-id)))


  (find-caches-by-fn-dep
    [_ dep-fn-id]
    (find-caches-by-dep datasource :cache-fn-dep :dep-fn-id dep-fn-id))


  (find-caches-by-fn-schema-dep
    [_ dep-fn-schema-id]
    (find-caches-by-dep datasource :cache-fn-schema-dep :dep-fn-schema-id dep-fn-schema-id))


  (find-caches-by-arg-schema-dep
    [_ dep-arg-schema-id]
    (find-caches-by-dep datasource :cache-arg-schema-dep :dep-arg-schema-id dep-arg-schema-id)))


(defn create-cache
  "Creates a PostgreSQL cache storage instance.

   Parameters:
   - datasource: A javax.sql.DataSource (e.g., HikariCP pool)

   The datasource should point to a database with cache tables
   already created (via cache-data-schema initialization).

   Example:
     (def cache (create-cache datasource))
     (def cached-storage (cached/wrap-with-cache storage cache))"
  [datasource]
  (->PostgresCache datasource))
