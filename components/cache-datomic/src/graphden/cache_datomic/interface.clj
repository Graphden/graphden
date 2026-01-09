(ns graphden.cache-datomic.interface
  "Datomic implementation of CacheStorage protocol.

   This component stores execution graph caches in Datomic using
   namespaced attributes for denormalized graph data:
   - :graphden.cache/cached-fn, cached-fn-schema, cached-arg-schema
   - :graphden.cache/cached-merged-arg: precomputed merged argument values
   - :graphden.cache/cache-fn-dep, cache-fn-schema-dep, cache-arg-schema-dep

   Usage:
   (def cache (create-cache conn))
   (cache/save-cache! cache fn-id graph deps)
   (cache/get-cached-graph cache fn-id)"
  (:require
    [clojure.edn :as edn]
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cache-protocol.value-codec :as codec]))


;; === Attribute naming ===

(defn- cache-attr
  "Creates a cache attribute ident.
   E.g., :graphden.cache/fn-id"
  [attr-name]
  (keyword "graphden.cache" (name attr-name)))


;; === Schema definition ===

(def cache-schema
  "Datomic schema for cache tables.
   This should be transacted once when setting up the database."
  [;; cached-fn entity
   {:db/ident (cache-attr :cached-fn-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID (root fn-id for this cache)"}
   {:db/ident (cache-attr :cached-fn-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function ID within the cached graph"}
   {:db/ident (cache-attr :cached-fn-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Function name"}
   {:db/ident (cache-attr :cached-fn-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema ID"}
   {:db/ident (cache-attr :cached-fn-parent-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Parent function ID (optional)"}

   ;; cached-fn-schema entity
   {:db/ident (cache-attr :cached-fn-schema-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cached-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema ID"}
   {:db/ident (cache-attr :cached-fn-schema-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema name"}
   {:db/ident (cache-attr :cached-fn-schema-base-fn-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Base function name"}
   {:db/ident (cache-attr :cached-fn-schema-returned-type)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Returned type keyword"}

   ;; cached-arg-schema entity
   {:db/ident (cache-attr :cached-arg-schema-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cached-arg-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Argument schema ID"}
   {:db/ident (cache-attr :cached-arg-schema-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema ID this arg belongs to"}
   {:db/ident (cache-attr :cached-arg-schema-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Argument name"}
   {:db/ident (cache-attr :cached-arg-schema-type)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Argument type keyword"}
   {:db/ident (cache-attr :cached-arg-schema-required)
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether argument is required"}

   ;; cached-merged-arg entity
   {:db/ident (cache-attr :cached-merged-arg-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cached-merged-arg-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function ID"}
   {:db/ident (cache-attr :cached-merged-arg-arg-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Argument schema ID"}
   {:db/ident (cache-attr :cached-merged-arg-value)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized value (EDN string)"}

   ;; cache-fn-dep entity
   {:db/ident (cache-attr :cache-fn-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-fn-dep-dep-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent function ID"}
   {:db/ident (cache-attr :cache-fn-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}

   ;; cache-fn-schema-dep entity
   {:db/ident (cache-attr :cache-fn-schema-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-fn-schema-dep-dep-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent function schema ID"}
   {:db/ident (cache-attr :cache-fn-schema-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}

   ;; cache-arg-schema-dep entity
   {:db/ident (cache-attr :cache-arg-schema-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-arg-schema-dep-dep-arg-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent argument schema ID"}
   {:db/ident (cache-attr :cache-arg-schema-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}])


;; === Schema initialization ===

(defn ensure-cache-schema!
  "Ensures cache schema is transacted to the database.
   Safe to call multiple times - Datomic ignores duplicate schema definitions."
  [conn]
  (d/transact conn {:tx-data cache-schema}))


;; === Graph loading helpers ===

(defn- load-cached-fns
  "Loads cached fn records for a cache-id."
  [db cache-id]
  ;; First get fns without parent-fn-id
  (let [base-results (d/q '[:find ?fn-id ?name ?fn-schema-id
                            :in $ ?cache-id
                            :where
                            [?e :graphden.cache/cached-fn-cache-id ?cache-id]
                            [?e :graphden.cache/cached-fn-fn-id ?fn-id]
                            [?e :graphden.cache/cached-fn-name ?name]
                            [?e :graphden.cache/cached-fn-fn-schema-id ?fn-schema-id]]
                          db cache-id)
        ;; Get parent-fn-ids separately (only for those that have it)
        parent-results (d/q '[:find ?fn-id ?parent-fn-id
                              :in $ ?cache-id
                              :where
                              [?e :graphden.cache/cached-fn-cache-id ?cache-id]
                              [?e :graphden.cache/cached-fn-fn-id ?fn-id]
                              [?e :graphden.cache/cached-fn-parent-fn-id ?parent-fn-id]]
                            db cache-id)
        fn-id->parent (into {} parent-results)]
    (->> base-results
         (map (fn [[fn-id name-str fn-schema-id]]
                [fn-id {:id fn-id
                        :name name-str
                        :fn-schema-id fn-schema-id
                        :parent-fn-id (get fn-id->parent fn-id)}]))
         (into {}))))


(defn- load-cached-fn-schemas
  "Loads cached fn-schema records for a cache-id."
  [db cache-id]
  (let [results (d/q '[:find ?fn-schema-id ?name ?base-fn-name ?returned-type
                       :in $ ?cache-id
                       :where
                       [?e :graphden.cache/cached-fn-schema-cache-id ?cache-id]
                       [?e :graphden.cache/cached-fn-schema-id ?fn-schema-id]
                       [?e :graphden.cache/cached-fn-schema-name ?name]
                       [?e :graphden.cache/cached-fn-schema-base-fn-name ?base-fn-name]
                       [?e :graphden.cache/cached-fn-schema-returned-type ?returned-type]]
                     db cache-id)]
    (->> results
         (map (fn [[fn-schema-id name-str base-fn-name returned-type]]
                [fn-schema-id {:id fn-schema-id
                               :name name-str
                               :base-fn-name base-fn-name
                               :returned-type returned-type}]))
         (into {}))))


(defn- load-cached-arg-schemas
  "Loads cached arg-schema records for a cache-id."
  [db cache-id]
  (let [results (d/q '[:find ?arg-schema-id ?fn-schema-id ?name ?type ?required
                       :in $ ?cache-id
                       :where
                       [?e :graphden.cache/cached-arg-schema-cache-id ?cache-id]
                       [?e :graphden.cache/cached-arg-schema-id ?arg-schema-id]
                       [?e :graphden.cache/cached-arg-schema-fn-schema-id ?fn-schema-id]
                       [?e :graphden.cache/cached-arg-schema-name ?name]
                       [?e :graphden.cache/cached-arg-schema-type ?type]
                       [?e :graphden.cache/cached-arg-schema-required ?required]]
                     db cache-id)]
    (->> results
         (map (fn [[arg-schema-id fn-schema-id name-str type-kw required]]
                [arg-schema-id {:id arg-schema-id
                                :fn-schema-id fn-schema-id
                                :name name-str
                                :type type-kw
                                :required required}]))
         (into {}))))


(defn- parse-value
  "Parses a cached value from EDN storage format."
  [value-edn]
  (when value-edn
    (let [parsed (if (string? value-edn)
                   (edn/read-string value-edn)
                   value-edn)]
      (codec/parse-cached-value parsed))))


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


;; === Graph saving helpers ===

(defn- encode-value
  "Encodes a value for EDN storage."
  [value]
  (when-let [formatted (codec/format-cached-value value)]
    (pr-str formatted)))


(defn- build-cached-fn-tx
  "Builds transaction data for a cached fn."
  [cache-id fn-id fn-record]
  (let [base-tx {:graphden.cache/cached-fn-cache-id cache-id
                 :graphden.cache/cached-fn-fn-id fn-id
                 :graphden.cache/cached-fn-name (:name fn-record)
                 :graphden.cache/cached-fn-fn-schema-id (:fn-schema-id fn-record)}]
    (if (:parent-fn-id fn-record)
      (assoc base-tx :graphden.cache/cached-fn-parent-fn-id (:parent-fn-id fn-record))
      base-tx)))


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


;; === Cache deletion ===

(defn- find-entities-to-retract
  "Finds all entity IDs for cache data to retract."
  [db cache-id]
  (let [cached-fns (d/q '[:find ?e :in $ ?cache-id :where
                          [?e :graphden.cache/cached-fn-cache-id ?cache-id]]
                        db cache-id)
        cached-fn-schemas (d/q '[:find ?e :in $ ?cache-id :where
                                 [?e :graphden.cache/cached-fn-schema-cache-id ?cache-id]]
                               db cache-id)
        cached-arg-schemas (d/q '[:find ?e :in $ ?cache-id :where
                                  [?e :graphden.cache/cached-arg-schema-cache-id ?cache-id]]
                                db cache-id)
        cached-merged-args (d/q '[:find ?e :in $ ?cache-id :where
                                  [?e :graphden.cache/cached-merged-arg-cache-id ?cache-id]]
                                db cache-id)
        fn-deps (d/q '[:find ?e :in $ ?cache-id :where
                       [?e :graphden.cache/cache-fn-dep-cache-id ?cache-id]]
                     db cache-id)
        fn-schema-deps (d/q '[:find ?e :in $ ?cache-id :where
                              [?e :graphden.cache/cache-fn-schema-dep-cache-id ?cache-id]]
                            db cache-id)
        arg-schema-deps (d/q '[:find ?e :in $ ?cache-id :where
                               [?e :graphden.cache/cache-arg-schema-dep-cache-id ?cache-id]]
                             db cache-id)]
    (concat (map first cached-fns)
            (map first cached-fn-schemas)
            (map first cached-arg-schemas)
            (map first cached-merged-args)
            (map first fn-deps)
            (map first fn-schema-deps)
            (map first arg-schema-deps))))


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

(defn- find-caches-by-fn-dep-impl
  "Returns set of cache-ids that depend on dep-fn-id."
  [db dep-fn-id]
  (->> (d/q '[:find ?cache-id
              :in $ ?dep-fn-id
              :where
              [?e :graphden.cache/cache-fn-dep-dep-fn-id ?dep-fn-id]
              [?e :graphden.cache/cache-fn-dep-cache-id ?cache-id]]
            db dep-fn-id)
       (map first)
       (into #{})))


(defn- find-caches-by-fn-schema-dep-impl
  "Returns set of cache-ids that depend on dep-fn-schema-id."
  [db dep-fn-schema-id]
  (->> (d/q '[:find ?cache-id
              :in $ ?dep-fn-schema-id
              :where
              [?e :graphden.cache/cache-fn-schema-dep-dep-fn-schema-id ?dep-fn-schema-id]
              [?e :graphden.cache/cache-fn-schema-dep-cache-id ?cache-id]]
            db dep-fn-schema-id)
       (map first)
       (into #{})))


(defn- find-caches-by-arg-schema-dep-impl
  "Returns set of cache-ids that depend on dep-arg-schema-id."
  [db dep-arg-schema-id]
  (->> (d/q '[:find ?cache-id
              :in $ ?dep-arg-schema-id
              :where
              [?e :graphden.cache/cache-arg-schema-dep-dep-arg-schema-id ?dep-arg-schema-id]
              [?e :graphden.cache/cache-arg-schema-dep-cache-id ?cache-id]]
            db dep-arg-schema-id)
       (map first)
       (into #{})))


;; === DatomicCache record ===

(defrecord DatomicCache
  [conn]

  cache/CacheStorage

  (get-cached-graph
    [_ fn-id]
    (let [db (d/db conn)]
      (when (cache-exists-query db fn-id)
        (let [fns (load-cached-fns db fn-id)
              fn-schemas (load-cached-fn-schemas db fn-id)
              arg-schemas (load-cached-arg-schemas db fn-id)
              resolved-args (load-cached-merged-args db fn-id)]
          (cache/build-cached-graph fns fn-schemas arg-schemas resolved-args)))))


  (cache-exists?
    [_ fn-id]
    (let [db (d/db conn)]
      (boolean (cache-exists-query db fn-id))))


  (save-cache!
    [_ fn-id graph dependencies]
    (cache/validate-uuid! fn-id "fn-id")
    (cache/validate-graph! graph)
    (cache/validate-dependencies! dependencies)
    (log/debug "Saving cache for fn-id" fn-id)
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
          all-txs (concat fn-txs fn-schema-txs arg-schema-txs merged-arg-txs
                          fn-dep-txs fn-schema-dep-txs arg-schema-dep-txs)]
      (when (seq all-txs)
        (d/transact conn {:tx-data (vec all-txs)}))))


  (delete-cache!
    [_ fn-id]
    (log/debug "Deleting cache for fn-id" fn-id)
    (delete-cache-data! conn fn-id))


  (find-caches-by-fn-dep
    [_ dep-fn-id]
    (find-caches-by-fn-dep-impl (d/db conn) dep-fn-id))


  (find-caches-by-fn-schema-dep
    [_ dep-fn-schema-id]
    (find-caches-by-fn-schema-dep-impl (d/db conn) dep-fn-schema-id))


  (find-caches-by-arg-schema-dep
    [_ dep-arg-schema-id]
    (find-caches-by-arg-schema-dep-impl (d/db conn) dep-arg-schema-id)))


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
