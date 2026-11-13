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
    (java.util
      UUID)))


(def ^:private revision-table "public.graphden_source_revision")
(def ^:private trigger-name "graphden_source_revision_bump")
(def ^:private function-name "public.graphden_source_revision_bump")


(defn- qualified-table
  [table]
  (when-not (string? table)
    (throw (ex-info "Physical source table name required" {:type ::invalid-table})))
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
    (jdbc/with-transaction [tx datasource]
                           ;; Lock in deterministic order before changing any trigger. A failed
                           ;; migration leaves both the old trigger set and revision intact.
                           (jdbc/execute! tx [(str "LOCK TABLE " (str/join "," tables) " IN ACCESS EXCLUSIVE MODE")])
                           (jdbc/execute! tx [(str "CREATE TABLE IF NOT EXISTS " revision-table
                                                   " (singleton boolean PRIMARY KEY CHECK(singleton),"
                                                   " generation uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0))")])
                           (jdbc/execute! tx [(str "INSERT INTO " revision-table
                                                   " VALUES(true,?,0) ON CONFLICT(singleton) DO NOTHING") (UUID/randomUUID)])
                           (jdbc/execute! tx [(str "CREATE OR REPLACE FUNCTION " function-name
                                                   "() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN "
                                                   "UPDATE " revision-table " SET revision=revision+1 WHERE singleton=true; "
                                                   "IF NOT FOUND THEN RAISE EXCEPTION 'Source revision missing'; END IF; "
                                                   "RETURN NULL; END $$")])
                           (doseq [table tables]
                             (jdbc/execute! tx [(str "DROP TRIGGER IF EXISTS " trigger-name " ON " table)])
                             (jdbc/execute! tx [(str "CREATE TRIGGER " trigger-name
                                                     " BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON " table
                                                     " FOR EACH STATEMENT EXECUTE FUNCTION " function-name "()")])))))


(defn current
  "Read the MVCC-visible revision in the caller's transaction. This pair alone
   is not a trusted cache certificate: trigger coverage and database identity
   must also be established by the eventual runtime integration."
  [connection]
  (jdbc/execute-one! connection
                     [(str "SELECT generation,revision FROM " revision-table " WHERE singleton=true")]
                     {:builder-fn rs/as-unqualified-lower-maps}))
