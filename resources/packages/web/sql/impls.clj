(ns graphden.packages.web.sql.impls
  "Raw JDBC base functions. A connection per call — keep it simple,
   tutorial-scale. If pooling is needed later, gate it behind a
   `:sql-with-pool` variant that takes a pool-id and reuses a
   datasource cached in the executor context."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(defn- datasource
  [url user password]
  (jdbc/get-datasource {:jdbcUrl url :user user :password password}))


(defbase sql-exec
  [url user password sql params]
  (cr/record-effect! :db)
  ;; External JDBC connection to a caller-supplied host — a real outbound
  ;; network effect, so the cloud sandbox can gate it (`:db` alone is
  ;; cloud-allowed and would let a restricted graph open arbitrary sockets).
  (cr/record-effect! :network)
  (let [ds (datasource url user password)
        ;; `params` arrives from the fn-graph as a Clojure vector
        ;; (or seq) — JDBC wants a `[sql & params]` vector for the
        ;; first arg to `execute-one!`.
        stmt (into [sql] (or params []))
        result (jdbc/execute-one! ds stmt {:return-keys false})]
    ;; DDL / DML responses both come back as `{:next.jdbc/update-count N}`
    ;; (DDL is 0). Surface the int so downstream graph code can branch
    ;; on it without record-shape acrobatics.
    (long (or (:next.jdbc/update-count result) 0))))


(defbase sql-query
  [url user password sql params]
  (cr/record-effect! :db)
  ;; External JDBC connection — outbound network (see `sql-exec`).
  (cr/record-effect! :network)
  (let [ds (datasource url user password)
        stmt (into [sql] (or params []))]
    ;; `as-unqualified-maps` gives `{:column value}` rather than
    ;; `{:table/column value}` — easier for downstream `:get` calls.
    ;; Realise into a vector so the lazy result-set doesn't outlive
    ;; the connection scope (next.jdbc closes it on return).
    (vec (jdbc/execute! ds stmt {:builder-fn rs/as-unqualified-maps}))))


(def impls
  {:sql-exec sql-exec
   :sql-query sql-query})
