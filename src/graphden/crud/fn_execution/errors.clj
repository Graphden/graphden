(ns graphden.crud.fn-execution.errors
  "Error-visibility reads + acknowledge writes over the existing
   `:fn-execution` audit rows.

   No new storage: failed executions already carry `:error` / `:error-data`,
   org-stamped and TTL-swept (30 d), with the write-side redaction/scrubbing
   applied (`redact-outcome` hides secret-tainted bodies; `scrub-outcome`
   replaces internal error types with an opaque `ref:` on the cloud) — so
   every string this ns returns is already safe for the org that reads it.

   A failure counts as UNRESOLVED for the viewing branch only while all
   of these hold (the Errors badge is actionable, not a permanent scar):

   - the run happened on the viewing branch or one of its ancestors
     (branch-chain) — sibling branches never see each other's failures;
     a NULL `branch_id` (pre-feature row) stays visible everywhere,
   - the failing `fn_version_id` is STILL the version the viewing
     branch resolves for that fn — shipping a fix (new version), an
     override on the child branch, or deleting the fn clears it,
   - no later `succeeded` run of the SAME version exists — a clean
     re-run clears a transient failure (network blip) without an edit,
   - the row hasn't been explicitly acknowledged (dismissed) from the
     panel — `acknowledged_at IS NULL`.

   Raw SQL over the pg pool with an EXPLICIT org filter (same contract as
   `fn-execution.stats`): `:fn-execution` is non-versioned, and the listing
   needs ORDER BY + LIMIT + a display-name join the protocol reads don't
   offer. The `fn` join is display-only (identity-row name)."
  (:require
    [clojure.string :as str]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(defn- viewing-branch-chain
  "`[branch-id parent-id … root-id]` for `storage`'s current branch, or
   nil when the storage isn't versioned (bare test ctx) — nil means
   'no branch filter', the pre-feature behaviour."
  [storage]
  (when (and storage (vs/versioned-storage? storage))
    (when-let [branch-id (vs/current-branch-id storage)]
      (res/collect-branch-chain (vs/unwrap storage) branch-id))))


(defn- branch-scope-sql
  "`[sql-fragment params]` — restrict rows to the viewing chain. NULL
   branch (pre-feature row) passes everywhere. nil/empty chain → no
   restriction (match-all fragment, no params)."
  [chain]
  (if (seq chain)
    [(str " AND (e.branch_id IS NULL OR e.branch_id IN ("
          (str/join "," (repeat (count chain) "?"))
          "))")
     (vec chain)]
    ["" []]))


(def ^:private unresolved-where
  ;; The SQL-expressible half of the unresolved predicate; the
  ;; still-current-version half needs the versioned resolver and is
  ;; applied in Clojure (`still-current-filter`).
  (str " WHERE e.status = 'failed'"
       " AND coalesce(e.org_id, 'public') = ?"
       " AND e.finished_at >= now() - make_interval(days => ?)"
       " AND e.acknowledged_at IS NULL"
       " AND NOT EXISTS (SELECT 1 FROM \"fn_execution\" s"
       "  WHERE s.fn_version_id = e.fn_version_id"
       "  AND s.status = 'succeeded'"
       "  AND s.finished_at > e.finished_at)"))


(defn- candidate-failures
  "SQL stage — failed rows passing the org/window/ack/branch/no-later-
   success filters, newest first, over-fetched (`fetch-n`) so the
   Clojure-side resolution filter can still fill `limit` rows."
  [pool org days fetch-n chain]
  (let [[branch-sql branch-params] (branch-scope-sql chain)]
    (jdbc/execute!
      pool
      (into [(str "SELECT e.id, fv.fn_id, e.fn_version_id, e.branch_id,"
                  " e.finished_at, e.error,"
                  " e.error_data::text AS error_data, f.name AS fn_name"
                  " FROM \"fn_execution\" e"
                  " LEFT JOIN \"fn_version\" fv ON fv.id = e.fn_version_id"
                  " LEFT JOIN \"fn\" f ON f.id = fv.fn_id"
                  unresolved-where
                  branch-sql
                  " ORDER BY e.finished_at DESC"
                  " LIMIT ?")
             (or org "public") (int (or days 7))]
            (conj branch-params (int fetch-n)))
      {:builder-fn rs/as-unqualified-lower-maps})))


(defn- still-current-filter
  "Keep only rows whose failing version is STILL what the viewing
   branch resolves for that fn — a shipped fix, a branch-local
   override, or a deleted fn all clear the failure. Resolution goes
   through `lookup/resolve-fn-version-id` (chain + merge aware); one
   resolve per DISTINCT fn-id, not per row."
  [ctx rows]
  (let [current (into {}
                      (keep (fn [fid]
                              (when-let [vid (lookup/resolve-fn-version-id ctx fid)]
                                [fid vid])))
                      (distinct (keep :fn_id rows)))]
    (filterv (fn [r]
               (and (:fn_id r)
                    (= (get current (:fn_id r)) (:fn_version_id r))))
             rows)))


(defn recent-unresolved-failures
  "The viewing branch's UNRESOLVED recent failures, newest first —
   `[{:execution-id :fn-id :fn-name :finished-at :error :error-data} …]`
   (see the ns docstring for the four-part unresolved predicate).
   `org` nil → \"public\" (platform / single-tenant). `days` default 7,
   `limit` default 50."
  [ctx pool org days limit]
  (when pool
    (let [storage (:storage ctx)
          limit (int (or limit 50))
          chain (viewing-branch-chain storage)
          rows (candidate-failures pool org days (min 400 (* limit 4)) chain)]
      (into []
            (comp (map (fn [r]
                         {:execution-id (:id r)
                          :fn-id (:fn_id r)
                          :fn-name (or (:fn_name r) (some-> (:fn_id r) str))
                          :finished-at (some-> (:finished_at r) str)
                          :error (:error r)
                          :error-data (:error_data r)}))
                  (take limit))
            (still-current-filter ctx rows)))))


;; =============================================================================
;; Acknowledge (dismiss) — the row stays for its TTL (audit trail
;; intact) but stops counting as unresolved.
;; =============================================================================

(defn acknowledge!
  "Dismiss ONE failed row. Org-guarded (a tenant can only ack their
   own rows). Returns true when a row was updated."
  [pool org execution-id]
  (boolean
    (when (and pool execution-id)
      (-> (jdbc/execute-one!
            pool
            [(str "UPDATE \"fn_execution\""
                  " SET acknowledged_at = now()"
                  " WHERE id = ? AND status = 'failed'"
                  " AND acknowledged_at IS NULL"
                  " AND coalesce(org_id, 'public') = ?")
             execution-id (or org "public")])
          :next.jdbc/update-count
          pos?))))


(defn acknowledge-all!
  "Dismiss every failed row the CURRENT branch view lists: same
   org/window/branch-chain scope as the read (rows already resolved by
   a fix or a later success get acked too — harmless, they were hidden
   anyway). Sibling branches' failures are untouched. Returns the
   number of rows acknowledged."
  [ctx pool org days]
  (if-not pool
    0
    (let [chain (viewing-branch-chain (:storage ctx))
          [branch-sql branch-params] (branch-scope-sql chain)]
      (-> (jdbc/execute-one!
            pool
            (into [(str "UPDATE \"fn_execution\" e"
                        " SET acknowledged_at = now()"
                        " WHERE e.status = 'failed'"
                        " AND e.acknowledged_at IS NULL"
                        " AND coalesce(e.org_id, 'public') = ?"
                        " AND e.finished_at >= now() - make_interval(days => ?)"
                        branch-sql)
                   (or org "public") (int (or days 7))]
                  branch-params))
          :next.jdbc/update-count
          (or 0)))))
