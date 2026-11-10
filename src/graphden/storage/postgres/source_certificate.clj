(ns graphden.storage.postgres.source-certificate
  "Bounded physical witnesses for the known PostgreSQL source-read adapter.
   No payloads, authorization decisions or cache activation live here. Callers
   own a fresh read-only RR transaction and restart the whole source path on a
   certificate miss; SQL errors require a new transaction before fallback."
  (:require
    [clojure.string :as str]
    [graphden.storage.postgres.source-session :as source-session]
    [graphden.storage.postgres.util :as pg]
    [honey.sql :as sql]
    [next.jdbc.result-set :as rs])
  (:import
    (java.lang.ref
      WeakReference)
    (java.sql
      Connection
      Timestamp)
    (java.time
      Instant
      ZoneId)
    (org.postgresql
      PGConnection)
    (org.postgresql.core
      BaseConnection
      TransactionState)))


(def ^:private max-tuples 131072)
(def ^:private max-operations 2048)
(def ^:private max-tables 32)
(def ^:private max-participants 8)
(def ^:private max-packet-operations 32)
(def ^:private max-packet-bytes 262144)
(def ^:private max-packet-params 60000)
(def ^:private max-plan-bytes (* 8 1024 1024))
(def ^:private max-capture-bytes (* 256 1024 1024))
(def ^:private token-charge 1024)
(def ^:private xid-width 4294967296N)
(def ^:private query-options {:builder-fn rs/as-unqualified-lower-maps :timeout 1})


(deftype ^:private Guard
  [state])


(deftype ^:private OperationKey
  [state]

  Object

  (equals
    [_ other]
    (and (instance? OperationKey other) (= state (.-state ^OperationKey other))))


  (hashCode [_] (hash state))


  (toString [_] "#<source-operation-key>")


  clojure.lang.IHashEq

  (hasheq [_] (hash state)))


(deftype ^:private Operation
  [state])


(deftype ^:private Certificate
  [state])


(defn- miss!
  [reason]
  (throw (ex-info "Physical source certificate is unavailable" {:type ::miss :reason reason})))


(defn- identifier?
  [value]
  (and (keyword? value) (nil? (namespace value))
       (boolean (re-matches #"[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)?" (name value)))
       (every? #(<= (count %) 63) (str/split (name value) #"\."))))


(defn- relation
  [value]
  (let [[table table-alias] (if (and (vector? value) (= 2 (count value))) value [value value])]
    (when-not (and (identifier? table) (identifier? table-alias)
                   (not (str/includes? (name table) "."))
                   (not (str/includes? (name table-alias) ".")))
      (miss! :relation-shape))
    {:table table :alias table-alias}))


(def ^:private number-readers
  {Byte #(Byte/valueOf ^String %) Short #(Short/valueOf ^String %)
   Integer #(Integer/valueOf ^String %) Long #(Long/valueOf ^String %)
   Float #(Float/valueOf ^String %) Double #(Double/valueOf ^String %)
   java.math.BigInteger #(java.math.BigInteger. ^String %)
   java.math.BigDecimal #(java.math.BigDecimal. ^String %)
   clojure.lang.BigInt #(bigint (java.math.BigInteger. ^String %))})


(defn- text-bytes
  [^String value]
  (when (> (.length value) max-packet-bytes) (miss! :parameter-byte-limit))
  (alength (.getBytes value "UTF-8")))


(defn- frozen-parameter
  "Typed immutable representation: numeric printing, Timestamp nanos and SQL
   date/time classes cannot collapse under Clojure numeric/Date equality."
  [value]
  (let [kind (class value)]
    (cond
      (or (nil? value) (string? value) (boolean? value) (uuid? value)) [:scalar value]
      (contains? number-readers kind)
      (let [integer (cond
                      (= java.math.BigDecimal kind) (.unscaledValue ^java.math.BigDecimal value)
                      (= java.math.BigInteger kind) value
                      (= clojure.lang.BigInt kind) (biginteger value))]
        ;; Reject oversized precision before decimal string conversion itself
        ;; can allocate an arbitrarily large temporary representation.
        (when (and integer (> (.bitLength ^java.math.BigInteger integer) max-packet-bytes))
          (miss! :parameter-byte-limit))
        (let [printed (str value)]
          (text-bytes printed)
          [:number kind printed]))
      (= Timestamp kind) [:timestamp (.getTime ^Timestamp value) (.getNanos ^Timestamp value)]
      (contains? #{java.util.Date java.sql.Date java.sql.Time} kind) [:date kind (.getTime ^java.util.Date value)]
      (= Instant kind) [:instant (.getEpochSecond ^Instant value) (.getNano ^Instant value)]
      :else (miss! :parameter-type))))


(defn- parameter
  [[kind a b :as frozen]]
  (case kind
    :scalar a
    :number ((get number-readers a) b)
    :timestamp (doto (Timestamp. a) (.setNanos b))
    :date (condp = a
            java.util.Date (java.util.Date. b)
            java.sql.Date (java.sql.Date. b)
            java.sql.Time (java.sql.Time. b))
    :instant (Instant/ofEpochSecond a b)
    (throw (ex-info "Invalid prepared parameter" {:type ::invalid-parameter :kind (first frozen)}))))


(defn- parameter-bytes
  [[kind a b]]
  (+ 32 (text-bytes
          (case kind
            :scalar (if (nil? a) "" (str a))
            :number b
            :timestamp (str a ":" b)
            :date (str b)
            :instant (str a ":" b)))))


(defn- freeze-query
  [query]
  (when-not (and (vector? query) (string? (first query))
                 (< (count query) max-packet-params))
    (miss! :query-shape))
  (let [initial (text-bytes (first query))]
    (when (> initial max-packet-bytes) (miss! :parameter-byte-limit))
    ;; Check each addition before retaining it, not after mapv has allocated
    ;; all typed parameters. This also bounds repeated large numeric leaves.
    (reduce (fn [{params :params query-bytes :bytes :as prepared} value]
              (let [frozen (frozen-parameter value)
                    total (+ query-bytes (parameter-bytes frozen))]
                (when (> total max-packet-bytes) (miss! :parameter-byte-limit))
                (assoc prepared :params (conj params frozen) :bytes total)))
            {:sql (first query) :params [] :bytes initial} (rest query))))


(defn- freeze-ast
  [ast]
  (let [nodes (volatile! 0) ast-bytes (volatile! 0)]
    (letfn [(visit
              [value depth]
              (when (or (> depth 32) (> (vswap! nodes inc) 8192)) (miss! :plan-complexity))
              (cond
                (map? value) (into {} (map (fn [[k v]] [(visit k (inc depth)) (visit v (inc depth))])) value)
                (sequential? value) (mapv #(visit % (inc depth)) value)
                (keyword? value) value
                :else (let [frozen (frozen-parameter value)]
                        (when (> (vswap! ast-bytes + (parameter-bytes frozen)) max-packet-bytes)
                          (miss! :parameter-byte-limit))
                        (parameter frozen))))]
      (let [copied (visit ast 0)] {:ast copied :nodes @nodes}))))


(defn- atom-expression?
  [value]
  (or (identifier? value)
      (and (vector? value) (= 2 (count value)) (= :inline (first value))
           (contains? #{0 1} (second value)))
      (and (not (coll? value)) (not (keyword? value)))))


(defn- predicate?
  [value]
  (and (vector? value)
       (let [[operator & args] value]
         (case operator
           (:and :or) (every? predicate? args)
           (:is := :<> :< :<= :> :>=) (and (= 2 (count args)) (every? atom-expression? args))
           :in (and (= 2 (count args)) (identifier? (first args))
                    (vector? (second args)) (every? atom-expression? (second args)))
           false))))


(defn- projected-column?
  [value]
  (or (= :* value) (identifier? value)
      (and (vector? value) (= 2 (count value)) (every? identifier? value))))


(defn- projection?
  [ast]
  (let [present (filter #(contains? ast %) [:select :select-distinct :select-distinct-on])
        kind (first present) columns (get ast kind)]
    (and (= 1 (count present)) (seq columns)
         ;; ORDER BY may resolve a projected alias, while a window PARTITION BY
         ;; resolves input columns. Do not attest two different sort keys.
         (or (empty? (:order-by ast))
             (not-any? vector? (if (= :select-distinct-on kind) (rest columns) columns)))
         (if (= :select-distinct-on kind)
           (and (vector? (first columns)) (seq (first columns)) (seq (rest columns))
                (every? identifier? (first columns)) (every? projected-column? (rest columns)))
           (every? projected-column? columns)))))


(defn- ordering?
  [ordering]
  (every? #(or (identifier? %)
               (and (vector? %) (= 2 (count %)) (identifier? (first %))
                    (contains? #{:asc :desc :asc-nulls-first :asc-nulls-last
                                 :desc-nulls-first :desc-nulls-last} (second %)))) ordering))


(defn- participants
  [ast locked]
  (when-not (and (map? ast) (projection? ast)
                 (every? #{:select :select-distinct :select-distinct-on :from :where :join :order-by :limit :offset}
                         (keys ast))
                 (= 1 (count (:from ast))) (or (nil? (:where ast)) (predicate? (:where ast)))
                 (even? (count (:join ast))) (ordering? (:order-by ast))
                 (or (not (contains? ast :select-distinct-on)) (seq (:order-by ast)))
                 (zero? (get ast :offset 0))
                 (every? #(or (not (contains? ast %))
                              (and (integer? (get ast %)) (<= 0 (get ast %) Integer/MAX_VALUE)))
                         [:limit :offset]))
    (miss! :unknown-read-plan))
  (let [joins (partition 2 (:join ast))
        parts (mapv relation (concat (:from ast) (map first joins)))]
    (when-not (and (<= (count parts) max-participants)
                   (every? #(contains? locked (:table %)) parts)
                   (= (count parts) (count (set (map :alias parts))))
                   (every? (comp predicate? second) joins))
      (miss! :unlocked-or-unknown-plan))
    parts))


(defn- physical-connection
  [^Connection connection]
  (let [physical (.unwrap connection PGConnection)]
    (when-not (instance? BaseConnection physical) (miss! :driver-unsupported))
    physical))


(defn- check-guard
  [guard]
  (when-not (instance? Guard guard) (miss! :guard-missing))
  (let [{:keys [connection physical active? check!] :as state} (.-state ^Guard guard)]
    (check!)
    (when-not (and @active? (not (.isClosed ^Connection connection))
                   (not (.getAutoCommit ^Connection connection)) (.isReadOnly ^Connection connection)
                   (identical? physical (physical-connection connection))
                   (= TransactionState/OPEN (.getTransactionState ^BaseConnection physical)))
      (miss! :guard-inactive))
    state))


(defn assert-connection!
  "Require the exact connection owned by this active guard before executing or
   replaying source reads. A scope map cannot pair another connection's payload
   with this guard's physical witness."
  [guard connection]
  (when-not (identical? connection (:connection (check-guard guard)))
    (miss! :guard-connection-mismatch))
  nil)


(defn- execute!
  [connection check! query]
  (check!)
  (let [result (pg/exec! connection query query-options)]
    (check!)
    result))


(def ^:private session-query
  (str "SELECT pg_snapshot_xmin(s)::text AS lower,pg_snapshot_xmax(s)::text AS upper,"
       "current_schema() AS schema,current_user AS role,session_user AS session_role,"
       "current_schemas(true)::text AS schemas,current_setting('search_path') AS search_path,"
       "current_setting('transaction_isolation') AS isolation,current_setting('transaction_read_only') AS readonly,"
       "current_setting('TimeZone') AS timezone,current_setting('DateStyle') AS date_style,"
       "current_setting('IntervalStyle') AS interval_style,current_setting('extra_float_digits') AS float_digits,"
       "current_setting('client_encoding') AS encoding,current_setting('bytea_output') AS bytea_output,"
       "current_setting('row_security') AS row_security, "
       "current_setting('is_superuser') AS superuser, "
       "(SELECT rolbypassrls FROM pg_roles WHERE rolname=current_user) AS bypassrls "
       "FROM pg_current_snapshot() s"))


(defn- schema-query
  [locked]
  (into [(str "WITH catalog_rows AS (SELECT c.oid::text AS oid,c.relname,c.relfilenode::text AS filenode,c.relkind,"
              "c.relowner::text,c.relacl::text,c.relrowsecurity,c.relforcerowsecurity,"
              "has_table_privilege(c.oid,'SELECT') AS readable,"
              "EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) AS inherited,"
              "a.attnum,a.attname,a.atttypid::text,a.atttypmod,a.attcollation::text,a.attisdropped,"
              "a.attnotnull,a.attidentity,a.attgenerated,a.atthasmissing,a.attmissingval::text,a.attacl::text,"
              "d.adbin::text,t.typname,t.typtype,tn.nspname AS typnamespace,"
              "co.collprovider,co.collisdeterministic,co.collversion,"
              "pg_collation_actual_version(co.oid) AS actual_collversion,"
              "db.datcollversion,pg_database_collation_actual_version(db.oid) AS actual_db_collversion "
              "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
              "JOIN pg_attribute a ON a.attrelid=c.oid "
              "LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
              "LEFT JOIN pg_type t ON t.oid=a.atttypid LEFT JOIN pg_namespace tn ON tn.oid=t.typnamespace "
              "LEFT JOIN pg_collation co ON co.oid=a.attcollation "
              "JOIN pg_database db ON db.datname=current_database() "
              "WHERE n.nspname='public' AND c.relname IN ("
              (str/join "," (repeat (count locked) "?"))
              ") AND a.attnum>0), bounded AS (SELECT catalog_rows.*, "
              "sum(1024 + coalesce(octet_length(relacl),0) + coalesce(octet_length(attacl),0) + "
              "coalesce(octet_length(attmissingval),0) + coalesce(octet_length(adbin),0) + "
              "coalesce(octet_length(collversion),0) + coalesce(octet_length(actual_collversion),0) + "
              "coalesce(octet_length(datcollversion),0) + coalesce(octet_length(actual_db_collversion),0)) "
              "OVER () AS guard_bytes FROM catalog_rows) "
              "SELECT * FROM bounded WHERE guard_bytes <= 1048576 ORDER BY oid,attnum")]
        (map name (sort locked))))


(def ^:private builtin-types
  #{"bool" "bytea" "int2" "int4" "int8" "text" "varchar" "numeric" "float4" "float8"
    "uuid" "jsonb" "timestamp" "timestamptz" "_uuid" "_text" "_int8" "_int4" "_bool"})


(defn- schema-supported?
  [row]
  (and (= "r" (str (:relkind row))) (false? (:inherited row)) (true? (:readable row))
       (or (:attisdropped row)
           (and (= "pg_catalog" (:typnamespace row)) (= "b" (str (:typtype row)))
                (contains? builtin-types (:typname row))
                (contains? #{"" "s"} (str (:attgenerated row)))))))


(defn- policy-query
  [locked]
  (into [(str "WITH policies AS (SELECT c.relname,p.polname,p.polcmd::text,p.polpermissive,"
              "p.polroles::text AS roles,pg_get_expr(p.polqual,p.polrelid) AS qual,"
              "pg_get_expr(p.polwithcheck,p.polrelid) AS check_expr,"
              "EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_policy'::regclass AND d.objid=p.oid "
              "AND NOT(d.refclassid='pg_class'::regclass AND d.refobjid=p.polrelid)) AS unsupported_dependency "
              "FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid "
              "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ("
              (str/join "," (repeat (count locked) "?"))
              ")), bounded AS (SELECT policies.*,sum(1024 + octet_length(roles) + "
              "coalesce(octet_length(qual),0) + coalesce(octet_length(check_expr),0)) OVER () AS bytes FROM policies) "
              "SELECT relname,polname,polcmd,polpermissive,roles,qual,check_expr,unsupported_dependency,false AS overflow "
              "FROM bounded WHERE bytes<=262144 UNION ALL "
              "SELECT NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,true FROM "
              "(SELECT 1 FROM bounded WHERE bytes>262144 LIMIT 1) AS overflow")]
        (map name (sort locked))))


(defn- policy-fact
  [row]
  {:name (:polname row) :command (:polcmd row) :permissive? (:polpermissive row)
   :roles (mapv parse-long (re-seq #"[0-9]+" (:roles row)))
   :using (:qual row) :check (:check_expr row)})


(defn- policy-contract!
  "Expected policies come only from trusted installation templates. Catalog
   output must NEVER be used to construct an expected contract. Pinned built-in
   operators/functions have no ordinary dependency rows; custom functions and
   cross-table policy reads are rejected. Source authorization remains external."
  [connection locked session schema contract check!]
  (let [tables (into {} (map (fn [row]
                               [(keyword (:relname row))
                                {:enabled? (:relrowsecurity row) :forced? (:relforcerowsecurity row)}])) schema)]
    (if (nil? contract)
      (when (some (comp :enabled? val) tables) (miss! :rls-contract-required))
      (let [{expected :tables gucs :required-gucs} contract]
        (when-not (and (map? expected) (= locked (set (keys expected)))
                       (map? gucs) (<= (count gucs) 8)
                       (every? #(and (string? %) (re-matches #"[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*" %)) (keys gucs))
                       (every? #(or (nil? %) (and (string? %) (<= (text-bytes %) 1024))) (vals gucs))
                       (= "off" (:superuser session)) (false? (:bypassrls session)) (= "on" (:row_security session))
                       (every? (fn [[table flags]]
                                 (and (= flags (select-keys (get expected table) [:enabled? :forced?]))
                                      (or (not (:enabled? flags)) (:forced? flags))
                                      (set? (:policies (get expected table)))
                                      (every? #(= [0] (:roles %)) (:policies (get expected table))))) tables))
          (miss! :rls-contract-shape))
        (let [policies (execute! connection check! (policy-query locked))]
          (when (some #(or (:overflow %) (:unsupported_dependency %)) policies) (miss! :unsupported-policy))
          (doseq [table locked]
            (when-not (= (:policies (get expected table))
                         (set (map policy-fact (filter #(= (name table) (:relname %)) policies))))
              (miss! :policy-mismatch))))
        (doseq [[setting expected-value] (sort-by key gucs)]
          (when-not (= expected-value
                       (:value (first (execute! connection check!
                                                ["SELECT current_setting(?,true) AS value" setting]))))
            (miss! :policy-context-mismatch)))))))


(defn call-with-guard
  "First action in a newly owned read-only RR transaction. The caller must
   have borrowed in autocommit and must not commit/restart inside consume.
   No SELECT precedes SET LOCAL + relation locks. The Guard expires on return.
   Optional :policy-contract supplies trusted installer expectations for every
   locked table plus current request GUC values; missing/unknown RLS is refused.
   Optional :session-marker was registered BEFORE RR by an explicit known-pool
   manager. Its origin is sealed after these locks, outside any source trace."
  ([connection locked check! consume]
   (call-with-guard connection locked {} check! consume))
  ([^Connection connection locked {:keys [policy-contract session-marker]} check! consume]
   (check!)
   (when-not (and (set? locked) (seq locked) (<= (count locked) max-tables)) (miss! :lock-set))
   (doseq [table locked] (relation table))
   (let [physical (physical-connection connection)]
     (when-not (and (not (.getAutoCommit connection)) (.isReadOnly connection)
                    (= TransactionState/IDLE (.getTransactionState ^BaseConnection physical)))
       (miss! :not-fresh-read-transaction))
     (execute! connection check! ["SET LOCAL statement_timeout = '1000ms'"])
     (execute! connection check! [(str "LOCK TABLE "
                                       (str/join ", " (map #(str "public.\"" (name %) "\"") (sort locked)))
                                       " IN ACCESS SHARE MODE")])
     (let [session (first (execute! connection check! [session-query]))
           schema (execute! connection check! (schema-query locked))
           active? (volatile! true)
           state {:connection connection :physical physical :locked locked :check! check! :active? active? :scope (Object.)
                  :bounds {:lower (bigint (:lower session)) :upper (bigint (:upper session))}
                  :session (dissoc session :lower :upper)
                  :jvm-zone (.getRules (ZoneId/systemDefault))
                  :schema schema :relation-oids (set (map :oid schema))
                  :policy-facts (when policy-contract
                                  {:tables (:tables policy-contract)
                                   :settings (set (keys (:required-gucs policy-contract)))})}]
       (when-not (and (= "public" (:schema session)) (= "\"$user\", public" (:search_path session))
                      (= "{pg_catalog,public}" (:schemas session)) (= "UTF8" (:encoding session))
                      (= "repeatable read" (:isolation session)) (= "on" (:readonly session))
                      (= (set (map name locked)) (set (map :relname schema)))
                      (every? schema-supported? schema))
         (miss! :unsupported-schema-or-session))
       (policy-contract! connection locked session schema policy-contract check!)
       (try
         (check!)
         (let [guard (Guard. state)
               origin (when session-marker
                        (source-session/seal-origin session-marker connection #(check-guard guard)))
               answer (consume (if origin (Guard. (assoc state :origin origin)) guard))]
           (check!) answer)
         (finally (vreset! active? false)))))))


(defn prepare-operation
  "Before the actual source SQL, freeze and admit its exact quoted plan and
   typed parameters. Unknown formatting/expressions must poison the capture.
   The caller separately owns replay-key options/builder identity and results."
  [guard ast sql-params]
  (let [{:keys [locked scope]} (check-guard guard)
        {ast :ast nodes :nodes} (freeze-ast ast)
        parts (participants ast locked)
        actual (freeze-query sql-params)
        expected (freeze-query (sql/format ast {:quoted true}))]
    (when-not (= (select-keys actual [:sql :params]) (select-keys expected [:sql :params]))
      (miss! :plan-query-mismatch))
    (check-guard guard)
    (Operation. {:ast ast :parts parts :scope scope
                 :query (select-keys actual [:sql :params])
                 :key (OperationKey. (select-keys actual [:sql :params]))
                 :charge-bytes (+ 4096 (* 16 (:bytes actual)) (* 256 nodes))})))


(defn operation-key
  "Opaque equality/hash key for the exact admitted SQL and immutable typed
   parameters. Canonical SQL equality was proved against the admitted AST.
   Scope identity is excluded; consumers separately compare options/builders."
  [operation]
  (when-not (instance? Operation operation) (miss! :operation-missing))
  (:key (.-state ^Operation operation)))


(defn operation-query
  "Fresh executable query from exactly the immutable values attested during
   prepare-operation. Never execute the caller's mutable Date/Timestamp leaves
   after preparation; a later mutation cannot change this operation's predicate."
  [operation]
  (when-not (instance? Operation operation) (miss! :operation-missing))
  (let [{:keys [sql params]} (:query (.-state ^Operation operation))]
    (into [sql] (map parameter params))))


(defn operation-charge-bytes
  "Conservative allowance for the prepared AST, typed receipt key and their
   immutable parameters. Consumers charge this before retaining an operation;
   certificate capture has its own separate bounded metadata allowance."
  [operation]
  (when-not (instance? Operation operation) (miss! :operation-missing))
  (:charge-bytes (.-state ^Operation operation)))


(defn- witness-member
  "Reject clipped results or ambiguous ordered selection without transferring
   source values. Window counts cover the COMPLETE predicate before LIMIT."
  [ast member]
  (let [ordering (mapv #(if (vector? %) (first %) %) (:order-by ast))
        ties (when (seq ordering)
               [:> [:over [[:count :*] {:partition-by ordering}]] [:inline 1]])
        clipped (when (contains? ast :limit)
                  [:> [:over [[:count :*] {}]] (:limit ast)])]
    (if (or ties clipped)
      (into [:case] (concat (when ties [ties [:inline -1]])
                            (when clipped [clipped [:inline -2]])
                            [:else [:inline member]]))
      [:inline member])))


(defn- operation-statements
  [operation-index operation]
  (when-not (instance? Operation operation) (miss! :operation-missing))
  (let [{:keys [ast parts]} (.-state ^Operation operation)]
    (mapv (fn [member {table-alias :alias}]
            (let [column #(keyword (str (name table-alias) "." %))]
              (freeze-query
                (sql/format
                  (assoc (select-keys ast [:from :join :where])
                         :select [[[:inline operation-index] :operation] [(witness-member ast member) :member]
                                  [[:cast (column "tableoid") :text] :relation]
                                  [[:cast (column "ctid") :text] :location]
                                  [[:cast (column "xmin") :text] :insertion]]
                         :limit (inc max-tuples))
                  {:quoted true}))))
          (range) parts)))


(defn- fits?
  [statements]
  (and (<= (count statements) max-packet-operations)
       (< (reduce + 1 (map #(count (:params %)) statements)) max-packet-params)
       (<= (+ 1024 (reduce + 0 (map :bytes statements))) max-packet-bytes)))


(defn- packet-plans
  [operations charge-budget]
  (when-not (and (vector? operations) (<= (count operations) max-operations)) (miss! :operation-limit))
  (let [total (volatile! 0) charge (volatile! 0)]
    (reduce (fn [groups statement]
              (when (or (not (fits? [statement]))
                        (> (vswap! total + (:bytes statement)) max-plan-bytes)
                        (> (vswap! charge + (+ 1024 (* 8 (:bytes statement)))) charge-budget))
                (miss! :plan-byte-limit))
              (if (and (seq groups) (fits? (conj (peek groups) statement)))
                (conj (pop groups) (conj (peek groups) statement))
                (conj groups [statement])))
            [] (mapcat operation-statements (range) operations))))


(defn- witness
  [guard groups row-limit]
  (let [{:keys [connection check! relation-oids]} (check-guard guard)
        rows (reduce (fn [all group]
                       (check-guard guard)
                       (let [remaining (- row-limit (count all))
                             query (into [(str "SELECT * FROM ("
                                               (str/join " UNION ALL " (map #(str "(" (:sql %) ")") group))
                                               ") AS certificate LIMIT ?")]
                                         (concat (mapcat #(map parameter (:params %)) group) [(inc remaining)]))
                             found (execute! connection check! query)]
                         (when (> (count found) remaining) (miss! :tuple-limit))
                         (when (some #(= -1 (:member %)) found) (miss! :ambiguous-order))
                         (when (some #(= -2 (:member %)) found) (miss! :clipped-result))
                         (when-not (every? #(contains? relation-oids (:relation %)) found)
                           (miss! :unlocked-result-relation))
                         (into all found))) [] groups)]
    (check-guard guard)
    {:tokens (frequencies (mapv #(mapv % [:operation :member :relation :location :insertion]) rows))
     :xmins (set (map #(bigint (:insertion %)) rows)) :rows (count rows)}))


(defn- snapshot-shape?
  [{:keys [lower upper]}]
  (and (integer? lower) (integer? upper) (pos? lower) (<= lower upper)
       (= (quot lower xid-width) (quot upper xid-width))))


(defn- admissible?
  [bounds xmins]
  (and (snapshot-shape? bounds)
       (every? #(and (integer? %) (< 2 % (mod (:lower bounds) xid-width))) xmins)))


(defn- fixed-charge
  [state operation-count]
  ;; Deliberate upper estimates, not serialized byte counts: immutable map/tree
  ;; overhead, UTF-16 backing arrays, JVM zone/session state and one temporary
  ;; packet. The PostgreSQL connection itself is never retained strongly.
  (+ (* 1024 1024)
     (* 4 (or (:guard_bytes (first (:schema state))) 0))
     (if (:policy-facts state) (* 4 262144) 0)
     (if (:origin state) 4096 0)
     (* 256 operation-count)))


(defn- plans-charge
  [plans]
  (reduce + 0 (map #(+ 1024 (* 8 (:bytes %))) (mapcat identity plans))))


(defn capture
  "Capture complete physical membership, including empty predicates. The caller
   reserves :max-bytes BEFORE this call; the same bound covers retained schema,
   immutable packet parameters and tuple data, with conservative object overhead.
   Lower budgets reduce SQL row limits, never clip a successful certificate.
   Source results/prepared source operations remain the caller's separate charge."
  ([guard operations] (capture guard operations {:max-bytes max-capture-bytes}))
  ([guard operations {:keys [max-bytes]}]
   (let [state (check-guard guard)]
     (when-not (and (vector? operations) (<= (count operations) max-operations)
                    (every? #(and (instance? Operation %)
                                  (identical? (:scope state) (:scope (.-state ^Operation %)))) operations))
       (miss! :operation-scope))
     (when-not (and (integer? max-bytes) (<= (* 1024 1024) max-bytes max-capture-bytes))
       (miss! :capture-budget))
     (let [fixed (fixed-charge state (count operations))]
       (when (> (+ fixed token-charge) max-bytes) (miss! :capture-budget))
       (let [plans (packet-plans operations (- max-bytes fixed token-charge))
             base (+ fixed (plans-charge plans))
             row-limit (long (min max-tuples (quot (- max-bytes base token-charge) token-charge)))
             current (witness guard plans row-limit)]
         (when-not (admissible? (:bounds state) (:xmins current)) (miss! :xid-admission))
         (check-guard guard)
         (Certificate.
           (merge (-> (select-keys state [:physical :schema :session :jvm-zone :bounds :locked :policy-facts :origin])
                      (update :physical #(WeakReference. %)))
                  current {:plans plans :operations (count operations)
                           :charge-bytes (+ base (* token-charge (:rows current)))})))))))


(defn- tokens-unchanged?
  [guard old]
  (try
    (= (:tokens old) (:tokens (witness guard (:plans old) (:rows old))))
    (catch clojure.lang.ExceptionInfo error
      ;; One extra physical member already proves inequality. Do not allocate
      ;; the entire new result just to reject a formerly small certificate.
      (if (= {:type ::miss :reason :tuple-limit} (ex-data error))
        false
        (throw error)))))


(defn unchanged?
  "Verify using a NEW active guarded snapshot. Physical connection identity may
   be replaced only by a fresh explicit session-origin witness; every schema,
   session, XID and complete source-membership comparison still applies.
   Current metadata queries always execute. False means restart the whole source
   operation; SQL/deadline failures propagate and cannot reuse partial results."
  [guard certificate]
  (let [current (check-guard guard)
        answer
        (and (instance? Certificate certificate)
             (let [old (.-state ^Certificate certificate)
                   before (:bounds old) after (:bounds current)
                   compatible (and (or (identical? (.get ^WeakReference (:physical old)) (:physical current))
                                       (and (:origin old)
                                            (source-session/origin-live?
                                              (:origin old) (:connection current) #(check-guard guard))))
                                   (= (select-keys old [:schema :session :jvm-zone :locked :policy-facts])
                                      (select-keys current [:schema :session :jvm-zone :locked :policy-facts]))
                                   (admissible? before (:xmins old)) (snapshot-shape? after)
                                   (= (quot (:lower before) xid-width) (quot (:lower after) xid-width))
                                   (<= (:lower before) (:lower after)) (<= (:upper before) (:upper after)))]
               (and compatible (tokens-unchanged? guard old))))]
    (check-guard guard)
    (boolean answer)))


(defn stats
  "Bounded counts only. Never expose stored predicates, tokens or schema data."
  [certificate]
  (when (instance? Certificate certificate)
    (let [{:keys [operations rows plans charge-bytes]} (.-state ^Certificate certificate)]
      {:operations operations :rows rows :charge-bytes charge-bytes :packets (count plans)
       :metadata-selects (reduce + 0 (map count plans))
       :plan-bytes (reduce + 0 (map :bytes (mapcat identity plans)))})))
