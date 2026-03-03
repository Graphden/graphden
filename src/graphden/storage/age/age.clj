(ns graphden.storage.age.age
  "Apache AGE graph operations.

   This module handles AGE-specific operations:
   - Graph initialization
   - Entity sync to graph
   - Cypher query execution

   ## Connection Initialization

   AGE requires per-connection setup:
   1. LOAD 'age' - loads the AGE extension
   2. SET search_path - makes ag_catalog visible

   We use a connection wrapper that initializes each connection from the pool."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


;; === Graph Entity Types ===

(def graph-entities
  "Entity types that are synced to the AGE graph.
   These entities form the execution graph structure."
  #{:fn :fn-schema :arg-schema :arg-value :fn-usage :fn-arg})


(defn graph-entity?
  "Returns true if entity-name should be synced to AGE graph."
  [entity-name]
  (contains? graph-entities entity-name))


;; === AGE Connection Initialization ===

(defn- init-age-connection!
  "Initializes an AGE connection with required settings.
   Must be called for each new connection from pool."
  [conn]
  (jdbc/execute! conn ["LOAD 'age'"])
  (jdbc/execute! conn ["SET search_path = ag_catalog, \"$user\", public"])
  conn)


(defn with-age-connection
  "Executes f with an AGE-initialized connection.
   Ensures proper AGE setup for each operation."
  [datasource f]
  (jdbc/with-transaction [tx datasource]
                         (init-age-connection! tx)
                         (f tx)))


;; === Cypher Query Helpers ===

(def ^:private valid-graph-name-pattern
  "Pattern for valid AGE graph names: alphanumeric and underscore only."
  #"^[a-zA-Z_][a-zA-Z0-9_]*$")


(defn- validate-graph-name!
  "Validates graph-name to prevent injection attacks.
   Graph names must be alphanumeric with underscores only."
  [graph-name]
  (when-not (and (string? graph-name)
                 (<= (count graph-name) 63)  ; PostgreSQL identifier limit
                 (re-matches valid-graph-name-pattern graph-name))
    (throw (ex-info "Invalid graph name"
                    {:type :security-error/invalid-identifier
                     :graph-name graph-name
                     :pattern (str valid-graph-name-pattern)})))
  graph-name)


(defn- escape-cypher-string
  "Escapes a string for use in Cypher queries.
   Handles backslash, quotes, and control characters."
  [s]
  (when s
    (-> (str s)
        (str/replace "\\" "\\\\")
        (str/replace "'" "\\'")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n")
        (str/replace "\r" "\\r")
        (str/replace "\t" "\\t"))))


(def ^:private valid-col-name-pattern
  "Pattern for valid column names in Cypher query results."
  #"^[a-zA-Z_][a-zA-Z0-9_]*$")


(def ^:private valid-col-type-pattern
  "Pattern for valid AGE column types."
  #"^[a-zA-Z_][a-zA-Z0-9_]*$")


(defn- validate-col-spec!
  "Validates column specification to prevent injection."
  [[col-name col-type]]
  (when-not (and (re-matches valid-col-name-pattern col-name)
                 (re-matches valid-col-type-pattern col-type))
    (throw (ex-info "Invalid column specification"
                    {:type :security-error/invalid-identifier
                     :col-name col-name
                     :col-type col-type})))
  [col-name col-type])


(defn- cypher-query
  "Wraps a Cypher query in AGE SQL syntax."
  [graph-name cypher]
  (validate-graph-name! graph-name)
  (format "SELECT * FROM cypher('%s', $$ %s $$) AS (result agtype)"
          graph-name cypher))


(defn- cypher-query-multi
  "Wraps a Cypher query that returns multiple columns.
   cols is a vector of [col-name col-type] pairs."
  [graph-name cypher cols]
  (validate-graph-name! graph-name)
  (doseq [col cols] (validate-col-spec! col))
  (let [col-defs (str/join ", " (map (fn [[n t]] (str n " " t)) cols))]
    (format "SELECT * FROM cypher('%s', $$ %s $$) AS (%s)"
            graph-name cypher col-defs)))


(defn execute-cypher!
  "Executes a Cypher query and returns results."
  [conn graph-name cypher]
  (let [sql (cypher-query graph-name cypher)]
    (jdbc/execute! conn [sql] {:builder-fn rs/as-unqualified-kebab-maps})))


(defn execute-cypher-multi!
  "Executes a Cypher query with multiple return columns."
  [conn graph-name cypher cols]
  (let [sql (cypher-query-multi graph-name cypher cols)]
    (jdbc/execute! conn [sql] {:builder-fn rs/as-unqualified-kebab-maps})))


;; === AGType Parsing ===

(defn parse-agtype
  "Parses an AGE agtype value to Clojure data."
  [value]
  (when value
    (let [s (str value)]
      (cond
        (str/includes? s "::vertex")
        (json/parse-string (str/replace s #"::vertex$" "") true)

        (str/includes? s "::edge")
        (json/parse-string (str/replace s #"::edge$" "") true)

        (str/includes? s "::agtype")
        (json/parse-string (str/replace s #"::agtype$" "") true)

        (str/starts-with? s "\"")
        (json/parse-string s)

        :else
        (try
          (json/parse-string s true)
          (catch Exception _
            s))))))


;; === Graph Initialization ===

(defn ensure-graph!
  "Ensures the AGE graph exists. Creates it if not present.
   Validates graph-name to prevent injection attacks."
  [datasource graph-name]
  (validate-graph-name! graph-name)
  (with-age-connection datasource
    (fn [conn]
      (try
        ;; Check if graph exists - graph-name is validated above
        (let [result (jdbc/execute-one! conn
                                        [(format "SELECT * FROM ag_catalog.ag_graph WHERE name = '%s'" graph-name)]
                                        {:builder-fn rs/as-unqualified-kebab-maps})]
          (when-not result
            (jdbc/execute! conn [(format "SELECT create_graph('%s')" graph-name)])
            (log/info "Created AGE graph:" graph-name)))
        true
        (catch Exception e
          (log/error e "Failed to initialize AGE graph")
          (throw e))))))


;; === Entity Sync to Graph ===

(defn- uuid->str
  "Converts UUID to string for AGE storage."
  [uuid]
  (when uuid (str uuid)))


(defn- encode-value
  "Encodes a value for storage in AGE node properties."
  [value]
  (escape-cypher-string (json/generate-string value)))


(defn- entity->node-label
  "Converts entity name to AGE node label."
  [entity-name]
  (case entity-name
    :fn "Fn"
    :fn-schema "FnSchema"
    :arg-schema "ArgSchema"
    :arg-value "ArgValue"
    :fn-usage "FnUsage"
    :fn-arg "FnArg"
    (throw (ex-info "Unknown entity for AGE" {:entity-name entity-name}))))


(defn- build-fn-node-cypher
  "Builds Cypher for creating/updating a Fn node."
  [entity]
  (format "MERGE (n:Fn {id: '%s'})
           SET n.name = '%s', n.fn_schema_id = '%s'
           RETURN n"
          (uuid->str (:id entity))
          (escape-cypher-string (:name entity))
          (uuid->str (:fn-schema-id entity))))


(defn- build-fn-schema-node-cypher
  "Builds Cypher for creating/updating a FnSchema node."
  [entity]
  (format "MERGE (n:FnSchema {id: '%s'})
           SET n.name = '%s',
               n.returned_type = '%s',
               n.base_fn_name = %s,
               n.impl_hash = %s
           RETURN n"
          (uuid->str (:id entity))
          (escape-cypher-string (:name entity))
          (escape-cypher-string (name (:returned-type entity)))
          (if (:base-fn-name entity)
            (format "'%s'" (escape-cypher-string (:base-fn-name entity)))
            "null")
          (if (:impl-hash entity)
            (format "'%s'" (escape-cypher-string (:impl-hash entity)))
            "null")))


(defn- build-arg-schema-node-cypher
  "Builds Cypher for creating/updating an ArgSchema node."
  [entity]
  (format "MERGE (n:ArgSchema {id: '%s'})
           SET n.fn_schema_id = '%s',
               n.name = '%s',
               n.type = '%s',
               n.required = %s
           RETURN n"
          (uuid->str (:id entity))
          (uuid->str (:fn-schema-id entity))
          (escape-cypher-string (:name entity))
          (escape-cypher-string (name (:type entity)))
          (if (:required entity) "true" "false")))


(defn- build-arg-value-node-cypher
  "Builds Cypher for creating/updating an ArgValue node."
  [entity]
  (format "MERGE (n:ArgValue {id: '%s'})
           SET n.arg_schema_id = '%s',
               n.value = '%s'
           RETURN n"
          (uuid->str (:id entity))
          (uuid->str (:arg-schema-id entity))
          (encode-value (:value entity))))


(defn- build-fn-usage-node-cypher
  "Builds Cypher for creating/updating a FnUsage node."
  [entity]
  (format "MERGE (n:FnUsage {id: '%s'})
           SET n.fn_id = '%s', n.name = '%s'
           RETURN n"
          (uuid->str (:id entity))
          (uuid->str (:fn-id entity))
          (escape-cypher-string (:name entity))))


(defn- build-fn-arg-edge-cypher
  "Builds Cypher for creating FnArg edge (Fn -[:HAS_ARG]-> ArgValue)."
  [entity]
  (format "MATCH (f:Fn {id: '%s'}), (av:ArgValue {id: '%s'})
           MERGE (f)-[r:HAS_ARG {arg_schema_id: '%s'}]->(av)
           RETURN r"
          (uuid->str (:fn-id entity))
          (uuid->str (:arg-value-id entity))
          (uuid->str (:arg-schema-id entity))))


;; Free arguments at fn-usage now handled by creating local fn with owner-fn-id
;; Keeping this as a comment for historical reference


(defn sync-entity-to-graph!
  "Syncs an entity to the AGE graph.
   Creates or updates the corresponding node/edge."
  [datasource graph-name entity-name entity]
  (with-age-connection datasource
    (fn [conn]
      (when-let [cypher (case entity-name
                          :fn (build-fn-node-cypher entity)
                          :fn-schema (build-fn-schema-node-cypher entity)
                          :arg-schema (build-arg-schema-node-cypher entity)
                          :arg-value (build-arg-value-node-cypher entity)
                          :fn-usage (build-fn-usage-node-cypher entity)
                          :fn-arg (build-fn-arg-edge-cypher entity)
                          nil)]
        (execute-cypher! conn graph-name cypher)))))


(defn delete-entity-from-graph!
  "Deletes an entity from the AGE graph."
  [datasource graph-name entity-name id]
  (with-age-connection datasource
    (fn [conn]
      (let [label (entity->node-label entity-name)
            cypher (format "MATCH (n:%s {id: '%s'}) DETACH DELETE n"
                           label (uuid->str id))]
        (execute-cypher! conn graph-name cypher)))))


;; === Dependency Edge Creation ===

(defn- extract-fn-refs-from-value
  "Extracts fn-id references from an arg-value's value field.
   Returns set of fn-ids that this value references."
  [value]
  (cond
    (uuid? value) #{value}
    (and (map? value) (contains? value :fn-id)) #{(:fn-id value)}
    :else #{}))


(defn sync-dependencies!
  "Creates DEPENDS_ON edges between Fn nodes based on arg-value references.
   Call after syncing all entities to establish the dependency graph."
  [datasource graph-name fn-id arg-values]
  (with-age-connection datasource
    (fn [conn]
      (doseq [av arg-values]
        (let [refs (extract-fn-refs-from-value (:value av))]
          (doseq [ref-id refs]
            ;; Create DEPENDS_ON edge from fn to referenced fn
            (let [cypher (format "MATCH (f1:Fn {id: '%s'}), (f2:Fn {id: '%s'})
                                  MERGE (f1)-[:DEPENDS_ON]->(f2)"
                                 (uuid->str fn-id) (uuid->str ref-id))]
              (execute-cypher! conn graph-name cypher))))))))
