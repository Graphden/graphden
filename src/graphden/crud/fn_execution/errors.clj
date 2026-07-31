(ns graphden.crud.fn-execution.errors
  "Recent-failures listing (Phase C2) — the error-visibility READ over the
   existing `:fn-execution` audit rows.

   No new storage: failed executions already carry `:error` / `:error-data`,
   org-stamped and TTL-swept (30 d), with the write-side redaction/scrubbing
   applied (`redact-outcome` hides secret-tainted bodies; `scrub-outcome`
   replaces internal error types with an opaque `ref:` on the cloud) — so
   every string this ns returns is already safe for the org that reads it.

   Raw SQL over the pg pool with an EXPLICIT org filter (same contract as
   `fn-execution.stats`): `:fn-execution` is non-versioned, and the listing
   needs ORDER BY + LIMIT + a display-name join the protocol reads don't
   offer. The `fn` join is display-only (identity-row name)."
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(defn recent-failures
  "The org's most recent failed executions, newest first — `[{:execution-id
   :fn-id :fn-name :finished-at :error :error-data} …]`. `org` nil →
   \"public\" (platform / single-tenant). `days` default 7, `limit` default
   50. Rows whose fn was since deleted still list (name falls back to the
   id) — the failure happened and stays visible for its TTL."
  [pool org days limit]
  (when pool
    (mapv (fn [r]
            {:execution-id (:id r)
             :fn-id (:fn_id r)
             :fn-name (or (:fn_name r) (some-> (:fn_id r) str))
             :finished-at (some-> (:finished_at r) str)
             :error (:error r)
             :error-data (:error_data r)})
          (jdbc/execute!
            pool
            [(str "SELECT e.id, fv.fn_id, e.finished_at, e.error,"
                  " e.error_data::text AS error_data, f.name AS fn_name"
                  " FROM \"fn_execution\" e"
                  " LEFT JOIN \"fn_version\" fv ON fv.id = e.fn_version_id"
                  " LEFT JOIN \"fn\" f ON f.id = fv.fn_id"
                  " WHERE e.status = 'failed'"
                  " AND coalesce(e.org_id, 'public') = ?"
                  " AND e.finished_at >= now() - make_interval(days => ?)"
                  " ORDER BY e.finished_at DESC"
                  " LIMIT ?")
             (or org "public") (int (or days 7)) (int (or limit 50))]
            {:builder-fn rs/as-unqualified-lower-maps}))))
