(ns graphden.postgres-storage.crud
  "CRUD operations for PostgreSQL storage.
   Generic entity operations for create, read, update, delete, query."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.postgres-storage.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (org.postgresql.util
      PGobject)))


(def ^:private query-timeout-seconds
  "Default timeout for CRUD queries (in seconds)."
  30)


(defn- parse-pgobject
  "Parses PGobject values (like JSONB) to Clojure data."
  [v]
  (if (instance? PGobject v)
    (let [pg-type (PGobject/.getType v)
          pg-value (PGobject/.getValue v)]
      (case pg-type
        "jsonb" (json/parse-string pg-value true)
        "json" (json/parse-string pg-value true)
        pg-value))
    v))


(defn- row->entity
  "Converts a database row to entity map.
   Converts snake_case column names to kebab-case keywords.
   Parses JSONB values to Clojure data."
  [row]
  (when row
    (reduce-kv (fn [acc k v]
                 (let [new-key (keyword (str/replace (name k) "_" "-"))
                       parsed-v (parse-pgobject v)]
                   (assoc acc new-key parsed-v)))
               {}
               row)))


(defn- value->jsonb
  "Wraps a value as JSONB PGobject for PostgreSQL."
  [v]
  (doto (PGobject.)
    (PGobject/.setType "jsonb")
    (PGobject/.setValue (json/generate-string v))))


;; Columns known to be JSONB type
(def ^:private jsonb-columns
  #{:value})


(defn- maybe-wrap-jsonb
  "Wraps value as JSONB if column is known to be JSONB type."
  [col-name v]
  (if (and (contains? jsonb-columns col-name)
           (not (instance? PGobject v)))
    (value->jsonb v)
    v))


(defn- entity->row
  "Converts entity map to database row.
   Converts kebab-case keywords to snake_case column names.
   Wraps JSONB column values appropriately."
  [entity]
  (reduce-kv (fn [acc k v]
               (let [new-key (keyword (str/replace (name k) "-" "_"))
                     wrapped-v (maybe-wrap-jsonb k v)]
                 (assoc acc new-key wrapped-v)))
             {}
             entity))


(defn create-entity
  "Creates a new entity record in the database.
   Returns the created record with generated id if not provided."
  [ds entity-name data]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        id (or (:id data) (random-uuid))
        record (assoc data :id id)
        row (entity->row record)
        columns (keys row)
        values (vals row)
        query (sql/format {:insert-into table-name
                           :columns columns
                           :values [values]
                           :returning [:*]}
                          {:quoted true})]
    (-> (jdbc/execute-one! ds query
                           {:builder-fn rs/as-unqualified-lower-maps
                            :timeout query-timeout-seconds})
        row->entity)))


(defn read-entity
  "Reads an entity by id. Returns nil if not found."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:select [:*]
                           :from [table-name]
                           :where [:= :id id]}
                          {:quoted true})]
    (-> (jdbc/execute-one! ds query
                           {:builder-fn rs/as-unqualified-lower-maps
                            :timeout query-timeout-seconds})
        row->entity)))


(defn update-entity
  "Updates an entity by id. Returns the updated record.
   Throws if entity not found."
  [ds entity-name id data]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        existing (read-entity ds entity-name id)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [updated (merge existing data {:id id})
          row (entity->row (dissoc updated :id))
          set-clause (into {} (map (fn [[k v]] [k v]) row))
          query (sql/format {:update table-name
                             :set set-clause
                             :where [:= :id id]
                             :returning [:*]}
                            {:quoted true})]
      (-> (jdbc/execute-one! ds query
                             {:builder-fn rs/as-unqualified-lower-maps
                              :timeout query-timeout-seconds})
          row->entity))))


(defn delete-entity
  "Deletes an entity by id. Returns true if entity existed and was deleted."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:delete-from table-name
                           :where [:= :id id]}
                          {:quoted true})
        result (jdbc/execute-one! ds query
                                  {:timeout query-timeout-seconds})]
    (pos? (or (:next.jdbc/update-count result) 0))))


(defn query-entities
  "Queries entities by conditions.
   where is a map of field->value for equality matching.
   Returns a sequence of matching entities."
  [ds entity-name where]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        where-clause (when (seq where)
                       (into [:and]
                             (map (fn [[k v]]
                                    [:= (keyword (util/kw->snake-case k)) v])
                                  where)))
        query (sql/format (cond-> {:select [:*]
                                   :from [table-name]}
                            where-clause (assoc :where where-clause))
                          {:quoted true})
        rows (jdbc/execute! ds query
                            {:builder-fn rs/as-unqualified-lower-maps
                             :timeout query-timeout-seconds})]
    (map row->entity rows)))
