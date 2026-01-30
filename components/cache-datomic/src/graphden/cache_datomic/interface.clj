(ns graphden.cache-datomic.interface
  "Datomic implementation of CacheStorage protocol.

   This component stores execution graph caches in Datomic using
   namespaced attributes for denormalized graph data:
   - :graphden.cache/cached-fn, cached-fn-schema, cached-arg-schema
   - :graphden.cache/cached-merged-arg: precomputed merged argument values
   - :graphden.cache/cache-fn-dep, cache-fn-schema-dep, cache-arg-schema-dep,
     cache-call-site-dep: dependency tracking

   Usage:
   (def cache (create-cache conn))
   (cache/save-cache! cache fn-id graph deps)
   (cache/get-cached-graph cache fn-id)"
  (:require
    [clojure.edn :as edn]
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.cache-datomic.schema :as schema]
    [graphden.cache-protocol.interface :as cache]))


(def cache-schema schema/cache-schema)
(def ensure-cache-schema! schema/ensure-cache-schema!)


;; === Graph loading helpers ===

(defn- load-datomic-entities
  "Generic loader for cached entity records from Datomic.
   Returns {id -> record} map.

   Parameters:
   - db: Datomic database value
   - cache-id: cache identifier (UUID)
   - query: Datalog query with ?cache-id input and results as [id-val & field-vals]
   - field-keys: vector of keywords for each field in query result (excluding id)
   - transforms: optional map of {field-key transform-fn} for value transformations

   Example:
     (load-datomic-entities db cache-id
       '[:find ?id ?name ?type :in $ ?cache-id :where ...]
       [:name :type]
       {:type keyword})"
  ([db cache-id query field-keys]
   (load-datomic-entities db cache-id query field-keys {}))
  ([db cache-id query field-keys transforms]
   (let [results (d/q query db cache-id)]
     (->> results
          (map (fn [[id-val & field-vals]]
                 (let [base-record (zipmap (cons :id field-keys)
                                           (cons id-val field-vals))]
                   [id-val (reduce-kv (fn [acc k transform-fn]
                                        (if (contains? acc k)
                                          (update acc k transform-fn)
                                          acc))
                                      base-record
                                      transforms)])))
          (into {})))))


(defn- load-cached-fns
  "Loads cached fn records for a cache-id."
  [db cache-id]
  (load-datomic-entities db cache-id
                         '[:find ?fn-id ?name ?fn-schema-id
                           :in $ ?cache-id
                           :where
                           [?e :graphden.cache/cached-fn-cache-id ?cache-id]
                           [?e :graphden.cache/cached-fn-fn-id ?fn-id]
                           [?e :graphden.cache/cached-fn-name ?name]
                           [?e :graphden.cache/cached-fn-fn-schema-id ?fn-schema-id]]
                         [:name :fn-schema-id]))


(defn- load-cached-fn-schemas
  "Loads cached fn-schema records for a cache-id."
  [db cache-id]
  (load-datomic-entities db cache-id
                         '[:find ?fn-schema-id ?name ?base-fn-name ?returned-type
                           :in $ ?cache-id
                           :where
                           [?e :graphden.cache/cached-fn-schema-cache-id ?cache-id]
                           [?e :graphden.cache/cached-fn-schema-id ?fn-schema-id]
                           [?e :graphden.cache/cached-fn-schema-name ?name]
                           [?e :graphden.cache/cached-fn-schema-base-fn-name ?base-fn-name]
                           [?e :graphden.cache/cached-fn-schema-returned-type ?returned-type]]
                         [:name :base-fn-name :returned-type]))


(defn- load-cached-arg-schemas
  "Loads cached arg-schema records for a cache-id."
  [db cache-id]
  (load-datomic-entities db cache-id
                         '[:find ?arg-schema-id ?fn-schema-id ?name ?type ?required
                           :in $ ?cache-id
                           :where
                           [?e :graphden.cache/cached-arg-schema-cache-id ?cache-id]
                           [?e :graphden.cache/cached-arg-schema-id ?arg-schema-id]
                           [?e :graphden.cache/cached-arg-schema-fn-schema-id ?fn-schema-id]
                           [?e :graphden.cache/cached-arg-schema-name ?name]
                           [?e :graphden.cache/cached-arg-schema-type ?type]
                           [?e :graphden.cache/cached-arg-schema-required ?required]]
                         [:fn-schema-id :name :type :required]))


(defn- parse-value
  "Parses a cached value from EDN storage format.
   Uses safe EDN parsing with no custom readers to prevent code execution."
  [value-edn]
  (when value-edn
    (let [parsed (if (string? value-edn)
                   ;; Use empty :readers to prevent arbitrary code execution
                   (edn/read-string {:readers {}} value-edn)
                   value-edn)]
      (cache/parse-cached-value parsed))))


(defn- load-cached-merged-args
  "Loads cached merged argument values for a cache-id.
   Returns {fn-id -> {arg-schema-id -> resolved-value}}."
  [db cache-id]
  (let [results (d/q '[:find ?fn-id ?arg-schema-id ?value
                       :in $ ?cache-id
                       :where
                       [?e :graphden.cache/cached-merged-arg-cache-id ?cache-id]
                       [?e :graphden.cache/cached-merged-arg-fn-id ?fn-id]
                       [?e :graphden.cache/cached-merged-arg-arg-schema-id ?arg-schema-id]
                       [?e :graphden.cache/cached-merged-arg-value ?value]]
                     db cache-id)]
    (reduce (fn [acc [fn-id arg-schema-id value-edn]]
              (assoc-in acc [fn-id arg-schema-id] (parse-value value-edn)))
            {}
            results)))


(defn- cache-exists-query
  "Returns true if cache exists for fn-id."
  [db fn-id]
  (let [result (d/q '[:find ?e
                      :in $ ?cache-id ?fn-id
                      :where
                      [?e :graphden.cache/cached-fn-cache-id ?cache-id]
                      [?e :graphden.cache/cached-fn-fn-id ?fn-id]]
                    db fn-id fn-id)]
    (seq result)))


(defn- load-all-cache-data
  "Loads all cache data in parallel with timeout protection.
   Returns nil if cache doesn't exist or any query times out.
   Uses shared utility from cache-protocol for consistent timeout handling."
  [db cache-id]
  (cache/load-cache-data-parallel
    cache-id
    (partial load-cached-fns db)
    (partial load-cached-fn-schemas db)
    (partial load-cached-arg-schemas db)
    (partial load-cached-merged-args db)))


;; === Graph saving helpers ===

(defn- encode-value
  "Encodes a value for EDN storage."
  [value]
  (when-let [formatted (cache/format-cached-value value)]
    (pr-str formatted)))


(defn- build-cached-fn-tx
  "Builds transaction data for a cached fn."
  [cache-id fn-id fn-record]
  {:graphden.cache/cached-fn-cache-id cache-id
   :graphden.cache/cached-fn-fn-id fn-id
   :graphden.cache/cached-fn-name (:name fn-record)
   :graphden.cache/cached-fn-fn-schema-id (:fn-schema-id fn-record)})


(defn- build-cached-fn-schema-tx
  "Builds transaction data for a cached fn-schema."
  [cache-id schema-id schema-record]
  {:graphden.cache/cached-fn-schema-cache-id cache-id
   :graphden.cache/cached-fn-schema-id schema-id
   :graphden.cache/cached-fn-schema-name (:name schema-record)
   :graphden.cache/cached-fn-schema-base-fn-name (:base-fn-name schema-record)
   :graphden.cache/cached-fn-schema-returned-type (:returned-type schema-record)})


(defn- build-cached-arg-schema-tx
  "Builds transaction data for a cached arg-schema."
  [cache-id arg-schema-id arg-schema-record]
  {:graphden.cache/cached-arg-schema-cache-id cache-id
   :graphden.cache/cached-arg-schema-id arg-schema-id
   :graphden.cache/cached-arg-schema-fn-schema-id (:fn-schema-id arg-schema-record)
   :graphden.cache/cached-arg-schema-name (:name arg-schema-record)
   :graphden.cache/cached-arg-schema-type (:type arg-schema-record)
   :graphden.cache/cached-arg-schema-required (:required arg-schema-record)})


(defn- build-cached-merged-arg-tx
  "Builds transaction data for a cached merged arg."
  [cache-id fn-id arg-schema-id value]
  (when-let [encoded (encode-value value)]
    {:graphden.cache/cached-merged-arg-cache-id cache-id
     :graphden.cache/cached-merged-arg-fn-id fn-id
     :graphden.cache/cached-merged-arg-arg-schema-id arg-schema-id
     :graphden.cache/cached-merged-arg-value encoded}))


(defn- build-fn-dep-tx
  "Builds transaction data for a fn dependency."
  [cache-id dep-fn-id ref-count]
  {:graphden.cache/cache-fn-dep-cache-id cache-id
   :graphden.cache/cache-fn-dep-dep-fn-id dep-fn-id
   :graphden.cache/cache-fn-dep-ref-count ref-count})


(defn- build-fn-schema-dep-tx
  "Builds transaction data for a fn-schema dependency."
  [cache-id dep-fn-schema-id ref-count]
  {:graphden.cache/cache-fn-schema-dep-cache-id cache-id
   :graphden.cache/cache-fn-schema-dep-dep-fn-schema-id dep-fn-schema-id
   :graphden.cache/cache-fn-schema-dep-ref-count ref-count})


(defn- build-arg-schema-dep-tx
  "Builds transaction data for an arg-schema dependency."
  [cache-id dep-arg-schema-id ref-count]
  {:graphden.cache/cache-arg-schema-dep-cache-id cache-id
   :graphden.cache/cache-arg-schema-dep-dep-arg-schema-id dep-arg-schema-id
   :graphden.cache/cache-arg-schema-dep-ref-count ref-count})


(defn- build-call-site-dep-tx
  "Builds transaction data for a call-site dependency."
  [cache-id dep-call-site-id ref-count]
  {:graphden.cache/cache-call-site-dep-cache-id cache-id
   :graphden.cache/cache-call-site-dep-dep-call-site-id dep-call-site-id
   :graphden.cache/cache-call-site-dep-ref-count ref-count})


;; === Cache deletion ===

(def ^:private cache-id-attrs
  "All cache-id attributes across entity types.
   Used to find all entities belonging to a cache for retraction."
  [:graphden.cache/cached-fn-cache-id
   :graphden.cache/cached-fn-schema-cache-id
   :graphden.cache/cached-arg-schema-cache-id
   :graphden.cache/cached-merged-arg-cache-id
   :graphden.cache/cache-fn-dep-cache-id
   :graphden.cache/cache-fn-schema-dep-cache-id
   :graphden.cache/cache-arg-schema-dep-cache-id
   :graphden.cache/cache-call-site-dep-cache-id])


(defn- find-entities-to-retract
  "Finds all entity IDs for cache data to retract."
  [db cache-id]
  (mapcat (fn [attr]
            (map first (d/q {:find '[?e]
                             :in '[$ ?cache-id]
                             :where [['?e attr '?cache-id]]}
                            db cache-id)))
          cache-id-attrs))


(defn- delete-cache-data!
  "Deletes all cache data for a fn-id.
   Returns true if any data was deleted."
  [conn cache-id]
  (let [db (d/db conn)
        entity-ids (find-entities-to-retract db cache-id)]
    (when (seq entity-ids)
      (d/transact conn {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) entity-ids)})
      true)))


;; === Dependency lookup ===

(defn- find-caches-by-dep
  "Returns set of cache-ids that depend on a given entity.
   Generic query using parameterized Datomic attributes."
  [db dep-attr cache-id-attr dep-id]
  (->> (d/q {:find '[?cache-id]
             :in '[$ ?dep-id]
             :where [['?e dep-attr '?dep-id]
                     ['?e cache-id-attr '?cache-id]]}
            db dep-id)
       (map first)
       (into #{})))


;; === DatomicCache record ===

(defrecord DatomicCache
  [conn]

  cache/CacheStorage

  (get-cached-graph
    [_ fn-id]
    (let [db (d/db conn)]
      ;; Load all data in parallel - no separate exists check needed
      (when-let [data (load-all-cache-data db fn-id)]
        (cache/build-cached-graph (:fns data) (:fn-schemas data)
                                  (:arg-schemas data) (:resolved-args data)))))


  (cache-exists?
    [_ fn-id]
    (let [db (d/db conn)]
      (boolean (cache-exists-query db fn-id))))


  (save-cache!
    [_ fn-id graph dependencies]
    (cache/validate-save-cache-args! fn-id graph dependencies)
    ;; Delete existing cache data first (if any)
    (delete-cache-data! conn fn-id)
    ;; Build transaction data for all cache entries
    (let [fn-txs (mapv (fn [[fid frec]]
                         (build-cached-fn-tx fn-id fid frec))
                       (:fns graph))
          fn-schema-txs (mapv (fn [[sid srec]]
                                (build-cached-fn-schema-tx fn-id sid srec))
                              (:fn-schemas graph))
          arg-schema-txs (mapv (fn [[asid asrec]]
                                 (build-cached-arg-schema-tx fn-id asid asrec))
                               (:arg-schemas graph))
          merged-arg-txs (->> (:resolved-args graph)
                              (mapcat (fn [[fid args-map]]
                                        (map (fn [[asid value]]
                                               (build-cached-merged-arg-tx fn-id fid asid value))
                                             args-map)))
                              (remove nil?)
                              vec)
          fn-dep-txs (mapv (fn [[dep-fn-id ref-count]]
                             (build-fn-dep-tx fn-id dep-fn-id ref-count))
                           (:fn-ids dependencies))
          fn-schema-dep-txs (mapv (fn [[dep-id ref-count]]
                                    (build-fn-schema-dep-tx fn-id dep-id ref-count))
                                  (:fn-schema-ids dependencies))
          arg-schema-dep-txs (mapv (fn [[dep-id ref-count]]
                                     (build-arg-schema-dep-tx fn-id dep-id ref-count))
                                   (:arg-schema-ids dependencies))
          call-site-dep-txs (mapv (fn [[dep-id ref-count]]
                                    (build-call-site-dep-tx fn-id dep-id ref-count))
                                  (:call-site-ids dependencies))
          all-txs (concat fn-txs fn-schema-txs arg-schema-txs merged-arg-txs
                          fn-dep-txs fn-schema-dep-txs arg-schema-dep-txs call-site-dep-txs)]
      (when (seq all-txs)
        (d/transact conn {:tx-data (vec all-txs)}))))


  (delete-cache!
    [_ fn-id]
    (log/debug "Deleting cache for fn-id" fn-id)
    (delete-cache-data! conn fn-id))


  (find-caches-by-fn-dep
    [_ dep-fn-id]
    (find-caches-by-dep (d/db conn)
                        :graphden.cache/cache-fn-dep-dep-fn-id
                        :graphden.cache/cache-fn-dep-cache-id
                        dep-fn-id))


  (find-caches-by-fn-schema-dep
    [_ dep-fn-schema-id]
    (find-caches-by-dep (d/db conn)
                        :graphden.cache/cache-fn-schema-dep-dep-fn-schema-id
                        :graphden.cache/cache-fn-schema-dep-cache-id
                        dep-fn-schema-id))


  (find-caches-by-arg-schema-dep
    [_ dep-arg-schema-id]
    (find-caches-by-dep (d/db conn)
                        :graphden.cache/cache-arg-schema-dep-dep-arg-schema-id
                        :graphden.cache/cache-arg-schema-dep-cache-id
                        dep-arg-schema-id))


  (find-caches-by-call-site-dep
    [_ dep-call-site-id]
    (find-caches-by-dep (d/db conn)
                        :graphden.cache/cache-call-site-dep-dep-call-site-id
                        :graphden.cache/cache-call-site-dep-cache-id
                        dep-call-site-id)))


(defn create-cache
  "Creates a Datomic cache storage instance.

   Parameters:
   - conn: A Datomic connection (from d/connect)

   The connection should point to a database with cache schema
   already transacted (via ensure-cache-schema!).

   Example:
     (def client (d/client {:server-type :datomic-local :storage-dir :mem :system \"my-sys\"}))
     (d/create-database client {:db-name \"cache-db\"})
     (def conn (d/connect client {:db-name \"cache-db\"}))
     (ensure-cache-schema! conn)
     (def cache (create-cache conn))
     (def cached-storage (cached/wrap-with-cache storage cache))"
  [conn]
  (->DatomicCache conn))
