(ns graphden.storage.postgres.source-revision
  "Opt-in transactional source revision. This is a freshness primitive, not
   authorization or proof that a caller supplied an exhaustive source roster.
   Runtime activation additionally requires catalog and snapshot-bound proofs."
  (:require
    [clojure.string :as str]
    [graphden.storage.postgres.util :as pg]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.sql
      Connection)
    (java.util
      UUID)))


(def ^:private revision-table "public.graphden_source_revision")
(def ^:private trigger-name "graphden_source_revision_bump")
(def ^:private function-name "public.graphden_source_revision_bump")
(def ^:private maintenance-key [1196573774 20261113])


(defn claim-snapshot!
  "Protect a live RO/RR snapshot from cooperating source maintenance. Never
   wait on this lock: waiting could establish a snapshot before a migration
   commits. Missing permission or maintenance in progress aborts the snapshot."
  [^Connection connection]
  (when-not (and (not (Connection/.getAutoCommit connection))
                 (Connection/.isReadOnly connection)
                 (= Connection/TRANSACTION_REPEATABLE_READ
                    (Connection/.getTransactionIsolation connection)))
    (throw (ex-info "Owned read-only RR required" {:type ::invalid-snapshot})))
  (let [result (jdbc/execute-one! connection
                                  (into ["SELECT pg_catalog.pg_try_advisory_xact_lock_shared(?::integer,?::integer) AS acquired"] maintenance-key)
                                  {:builder-fn rs/as-unqualified-lower-maps})]
    (when-not (true? (:acquired result))
      (throw (ex-info "Source maintenance in progress" {:type ::maintenance-active})))))


(defn call-with-maintenance
  "The supported source DDL/trigger-repair boundary. Take the exclusive lock
   BEFORE any source DDL, execute repair once and rotate generation atomically.
   Raw trigger disabling/TRUNCATE/schema changes outside this boundary are not
   supported while revision-backed readers are enabled. No privilege escalation."
  [datasource repair]
  (jdbc/with-transaction [tx datasource]
                         (jdbc/execute! tx (into ["SELECT pg_catalog.pg_advisory_xact_lock(?::integer,?::integer)"] maintenance-key))
                         (let [result (repair tx)
                               updated (jdbc/execute-one! tx
                                                          [(str "UPDATE " revision-table
                                                                " SET generation=?,revision=revision+1 WHERE singleton=true")
                                                           (UUID/randomUUID)])]
                           (when-not (= 1 (:next.jdbc/update-count updated))
                             (throw (ex-info "Source revision row missing after maintenance" {:type ::maintenance-failed})))
                           result)))


(def ^:private function-body
  (str " BEGIN UPDATE " revision-table " SET revision=revision+1 WHERE singleton=true; "
       "IF NOT FOUND THEN RAISE EXCEPTION 'Source revision missing'; END IF; RETURN NULL; END "))


(defn- qualified-table
  [table]
  (when-not (string? table)
    (throw (ex-info "Physical source table name required" {:type ::invalid-table})))
  (when (= table "graphden_source_revision")
    (throw (ex-info "Revision table cannot trigger its own update" {:type ::invalid-table})))
  (pg/validate-sql-identifier! table {:operation :source-revision-install})
  (str "public.\"" table "\""))


(defn install!
  "Install statement triggers for explicit physical tables atomically. Call only
   during a coordinated schema migration, never on a request path. Writers need
   UPDATE on the revision row: permissions are deliberately not escalated here.
   BEFORE triggers take the shared revision lock before source tuple writes.
   The caller must include every identity/version/junction/branch source table."
  [datasource tables]
  (let [tables (mapv qualified-table (sort (distinct (seq tables))))]
    (when (empty? tables)
      (throw (ex-info "Source table roster cannot be empty" {:type ::empty-roster})))
    (call-with-maintenance datasource (fn [tx]
                                        ;; Lock in deterministic order before changing any trigger. A failed
                                        ;; migration leaves both the old trigger set and revision intact.
                                        (jdbc/execute! tx [(str "LOCK TABLE " (str/join "," tables) " IN ACCESS EXCLUSIVE MODE")])
                                        (jdbc/execute! tx [(str "CREATE TABLE IF NOT EXISTS " revision-table
                                                                " (singleton boolean PRIMARY KEY CHECK(singleton),"
                                                                " generation uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0))")])
                                        (jdbc/execute! tx [(str "INSERT INTO " revision-table
                                                                " VALUES(true,?,0) ON CONFLICT(singleton) DO NOTHING") (UUID/randomUUID)])
                                        (jdbc/execute! tx [(str "CREATE OR REPLACE FUNCTION " function-name
                                                                "() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$"
                                                                function-body "$$")])
                                        (doseq [table tables]
                                          (jdbc/execute! tx [(str "DROP TRIGGER IF EXISTS " trigger-name " ON " table)])
                                          (jdbc/execute! tx [(str "CREATE TRIGGER " trigger-name
                                                                  " BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON " table
                                                                  " FOR EACH STATEMENT EXECUTE FUNCTION " function-name "()")]))))))


(defn current
  "Read the MVCC-visible revision in the caller's transaction. This pair alone
   is not a trusted cache certificate: trigger coverage and database identity
   must also be established by the eventual runtime integration."
  [connection]
  (jdbc/execute-one! connection
                     [(str "SELECT generation,revision FROM " revision-table " WHERE singleton=true")]
                     {:builder-fn rs/as-unqualified-lower-maps}))


(defn roster-installed?
  "Check the exact trigger implementation on every requested physical table.
   This is a catalog diagnostic, NOT a snapshot/cache certificate: callers
   must prove roster completeness, lock out concurrent DDL, and establish
   trusted trigger history before relying on the revision for freshness."
  [connection tables]
  (let [tables (vec (sort (distinct (seq tables))))]
    (doseq [table tables] (qualified-table table))
    (if (empty? tables)
      false
      (let [query (str "SELECT c.relname FROM pg_catalog.pg_class c "
                       "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                       "JOIN pg_catalog.pg_trigger t ON t.tgrelid=c.oid "
                       "JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid "
                       "JOIN pg_catalog.pg_namespace pn ON pn.oid=p.pronamespace "
                       "JOIN pg_catalog.pg_language l ON l.oid=p.prolang "
                       "WHERE n.nspname='public' AND c.relkind='r' "
                       "AND c.relname IN (" (str/join "," (repeat (count tables) "?")) ") "
                       "AND t.tgname=? AND t.tgenabled='O' AND t.tgtype=62 "
                       "AND NOT t.tgisinternal AND t.tgqual IS NULL AND t.tgnargs=0 "
                       "AND pn.nspname='public' AND p.proname=? "
                       "AND NOT p.prosecdef AND l.lanname='plpgsql' AND p.prosrc=? "
                       "AND p.proconfig=ARRAY['search_path=pg_catalog']::text[]")
            rows (jdbc/execute! connection
                                (into [query] (concat tables [trigger-name "graphden_source_revision_bump" function-body]))
                                {:builder-fn rs/as-unqualified-lower-maps})]
        (= (set tables) (set (map :relname rows)))))))
