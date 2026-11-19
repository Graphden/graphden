(ns graphden.storage.postgres.source-revision-snapshot
  "Owned RR boundary for future source graph provenance. The loader receives
   the exact connection whose MVCC revision was read. This boundary performs
   no caching and grants no access to graph data."
  (:require
    [clojure.string :as str]
    [graphden.storage.postgres.source-revision :as revision]
    [graphden.storage.postgres.util :as pg]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)))


(defn call-with-snapshot
  "Run loader once in a fresh read-only RR transaction after locking the full
   caller-supplied source roster against DDL and checking its trigger coverage.
   Return the loader result and same-snapshot revision. Unknown coverage throws
   BEFORE loader; loader/SQL failures propagate without implicit retries.
   This is not a cache certificate: callers must bind the result to their exact
   graph object and database/pool identity, with fresh authorization on use."
  ([datasource tables loader]
   (call-with-snapshot datasource tables {} loader))
  ([datasource tables {:keys [before-lock with-revision?]} loader]
   (when (instance? Connection datasource)
     (throw (ex-info "Source capture requires an owned transaction" {:type ::connection-backed})))
   (let [tables (vec (sort (distinct (seq tables))))]
     (when (empty? tables)
       (throw (ex-info "Source capture requires a complete roster" {:type ::empty-roster})))
     (doseq [table tables]
       (pg/validate-sql-identifier! table {:operation :source-snapshot}))
     (jdbc/with-transaction [connection datasource {:isolation :repeatable-read :read-only true}]
                            (when before-lock (before-lock connection))
                            ;; LOCK is the first source statement: a later catalog read cannot silently
                            ;; follow a concurrent DROP/recreate of a source relation.
                            (jdbc/execute! connection
                                           [(str "LOCK TABLE public.graphden_source_revision,"
                                                 (str/join "," (map #(str "public.\"" % "\"") tables))
                                                 " IN ACCESS SHARE MODE")])
                            (when-not (revision/claim-snapshot! connection)
                              (throw (ex-info "Source revision maintenance active" {:type ::unavailable})))
                            (when-not (revision/roster-installed? connection tables)
                              (throw (ex-info "Source revision trigger coverage unavailable" {:type ::unavailable})))
                            (let [token (revision/current connection)]
                              (when-not token
                                (throw (ex-info "Source revision row unavailable" {:type ::unavailable})))
                              {:revision token :value (if with-revision? (loader connection token) (loader connection))})))))
