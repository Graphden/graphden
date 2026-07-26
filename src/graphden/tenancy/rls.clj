(ns graphden.tenancy.rls
  "Postgres Row-Level Security — the second isolation layer (PLATFORM_PLAN
   §3.0 B5). Belt-and-suspenders behind OrgScopedStorage: even a raw query
   that bypasses the decorator (a `:pg-query` base-fn, a manual JDBC call)
   cannot cross tenants, because Postgres itself filters every row by the
   `graphden.current_org` session variable.

   `enable-rls!` installs an own+public-read / own-write policy on each
   org-scoped table and FORCEs RLS so even the table owner is subject (a
   superuser still bypasses — connect the app as a non-superuser role in
   production). At request time `org-aware-datasource` sets the
   `graphden.current_org` session variable from `*current-org*` on every
   borrowed connection (session-level, per borrow — the live path);
   `set-current-org!` is the transaction-local variant for scoping a
   single explicit transaction (used by the RLS test).

   With the variable unset (admin / single-tenant), the policy is a no-op:
   every row is visible and writable — so installing RLS is safe before the
   request path sets the variable."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.util :as util]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.storage :as ts]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.sql
      Connection
      PreparedStatement)
    (javax.sql
      DataSource)))


(def ^:const org-setting
  "Custom GUC the policy reads. The dot is required — Postgres only allows
   set_config/current_setting on namespaced (dotted) custom settings."
  "graphden.current_org")


(defn- policy-statements
  "DDL for one org-scoped table: enable+force RLS and (re)create the
   org-isolation policy set. `current_setting(…, true)` is missing-ok →
   NULL when unset, which the `unset` clause treats as full access
   (admin / single-tenant / public-org).

   Split by command so READ and WRITE differ:
   - SELECT sees own + public (NULL-org) rows — a tenant must see the
     shared platform graph.
   - INSERT / UPDATE / DELETE are OWN-only. A single `FOR ALL` policy's
     `USING` clause governs both SELECT and the DELETE/UPDATE target
     check, so a combined own+public `USING` let a tenant DELETE a
     public (NULL-org) row — or UPDATE-and-claim it — through a RAW SQL
     path that bypasses OrgScopedStorage's own?-guard. Own-only write
     policies close that."
  [entity]
  (let [t (util/ident->sql entity)
        org-col (util/ident->sql :org-id)
        cur (str "current_setting('" org-setting "', true)")
        unset (str cur " IS NULL OR " cur " = ''")
        own (str "(" unset " OR " org-col " = " cur ")")
        own-or-public (str "(" unset " OR " org-col " IS NULL OR " org-col " = " cur ")")]
    [(str "ALTER TABLE " t " ENABLE ROW LEVEL SECURITY")
     (str "ALTER TABLE " t " FORCE ROW LEVEL SECURITY")
     ;; Drop the legacy combined policy + any prior split policies so
     ;; re-running is idempotent across the naming change.
     (str "DROP POLICY IF EXISTS org_isolation ON " t)
     (str "DROP POLICY IF EXISTS org_isolation_select ON " t)
     (str "DROP POLICY IF EXISTS org_isolation_insert ON " t)
     (str "DROP POLICY IF EXISTS org_isolation_update ON " t)
     (str "DROP POLICY IF EXISTS org_isolation_delete ON " t)
     (str "CREATE POLICY org_isolation_select ON " t " FOR SELECT USING " own-or-public)
     (str "CREATE POLICY org_isolation_insert ON " t " FOR INSERT WITH CHECK " own)
     (str "CREATE POLICY org_isolation_update ON " t " FOR UPDATE USING " own " WITH CHECK " own)
     (str "CREATE POLICY org_isolation_delete ON " t " FOR DELETE USING " own)]))


(defn- table-exists?
  [ds entity]
  (boolean
    (seq (jdbc/execute! ds ["SELECT 1 FROM information_schema.tables WHERE table_name = ?"
                            (str/replace (name entity) "-" "_")]))))


(defn enable-rls!
  "Install the org-isolation policy on each org-scoped table (default:
   `ts/default-scoped-entities`). Idempotent — re-running replaces the
   policy. Entities whose table isn't in the schema are skipped (a deployment
   without, say, the executions schema simply has nothing to protect there).
   `ds` is a datasource/connection."
  ([ds] (enable-rls! ds ts/default-scoped-entities))
  ([ds entities]
   (doseq [entity entities
           :when (table-exists? ds entity)
           stmt (policy-statements entity)]
     (jdbc/execute! ds [stmt]))))


(defn rls-role-status
  "Whether the app's DB role is actually SUBJECT to the RLS policies
   `enable-rls!` installs. A Postgres SUPERUSER — and any role with
   BYPASSRLS — ignores RLS entirely (including FORCE), so the policies
   become a silent no-op: OrgScopedStorage still isolates at the app layer,
   but the database-level backstop is gone.

   `ds` is a datasource/connection. Returns
   `{:role :superuser? :bypassrls? :enforced?}` where `:enforced?` is true
   only when the role is neither a superuser nor BYPASSRLS."
  [ds]
  ;; Single query on ONE connection (matters when `ds` is a tx after SET ROLE
  ;; — the role must hold for the whole read). Unqualified builder so the keys
  ;; are `:role`/`:superuser`/`:bypassrls` regardless of source-table
  ;; qualification (pg_roles would otherwise qualify them).
  (let [row (jdbc/execute-one! ds ["SELECT current_user AS role,
                                           current_setting('is_superuser') = 'on' AS superuser,
                                           rolbypassrls AS bypassrls
                                      FROM pg_roles
                                     WHERE rolname = current_user"]
                               {:builder-fn rs/as-unqualified-lower-maps})
        superuser? (boolean (:superuser row))
        bypassrls? (boolean (:bypassrls row))]
    {:role (:role row)
     :superuser? superuser?
     :bypassrls? bypassrls?
     :enforced? (not (or superuser? bypassrls?))}))


(defn verify-rls-enforcement!
  "Check that the connected role is subject to RLS and react to a role that
   is NOT — a dangerous silent state for a multi-tenant deployment, where the
   org-isolation policies are installed but inert.

   `strict?` true → throw and fail the boot (recommended for a production
   multi-tenant deployment); false → log a prominent WARN and continue
   (OrgScopedStorage still isolates at the app layer — acceptable for a
   trusted single-tenant / dev install, where the DB role is often the
   superuser). Returns the `rls-role-status` map."
  [ds strict?]
  (let [{:keys [role superuser? bypassrls? enforced?] :as status} (rls-role-status ds)]
    (when-not enforced?
      (let [why (if superuser? "a Postgres SUPERUSER" "a BYPASSRLS role")
            msg (str "RLS policies are installed but INERT: the app connects as \"" role
                     "\" — " why ", which ignores Row-Level Security (including FORCE). "
                     "Tenant isolation falls back to OrgScopedStorage (application layer) "
                     "ONLY; the database-level backstop is gone. For a production "
                     "multi-tenant deployment, connect as a non-superuser, non-BYPASSRLS "
                     "role — see docs/DEPLOYMENT.md § non-superuser DB role. "
                     "Set GRAPHDEN_STRICT_RLS=true to make this a hard boot failure.")]
        (if strict?
          (throw (ex-info msg {:type :rls/not-enforced :role role
                               :superuser? superuser? :bypassrls? bypassrls?}))
          (log/warn msg))))
    status))


(defn set-current-org!
  "Set the RLS session variable on `conn` for the CURRENT transaction
   (`set_config(_, _, true)` is transaction-local). Run inside the
   transaction whose queries should be tenant-scoped. nil → cleared."
  [conn org]
  (jdbc/execute! conn ["SELECT set_config(?, ?, true)" org-setting (or org "")]))


(defn- set-session-org!
  "Set the RLS variable at SESSION level on a freshly-borrowed connection
   from the current org. A real tenant → its org; public / admin / unbound
   → '' so the policy's `unset` clause grants full access. Session-level
   (not LOCAL) so it covers every query on the borrow; the value is
   overwritten on every borrow, so nothing leaks between pool checkouts."
  [^Connection conn org]
  (with-open [stmt (Connection/.prepareStatement conn "SELECT set_config(?, ?, false)")]
    (PreparedStatement/.setString stmt 1 org-setting)
    (PreparedStatement/.setString stmt 2 (if (= org tc/public-org) "" org))
    (PreparedStatement/.execute stmt)))


(defn org-aware-datasource
  "Wrap a `DataSource` so every borrowed connection carries
   `graphden.current_org` set from `*current-org*` — making RLS enforce per
   request without threading the org through every query. The wrap belongs
   on the shared `:db/postgres` pool (not just the org-scoped path): every
   borrow must (re)set the variable, or a stale value from a tenant borrow
   would leak into a later admin borrow on the same physical connection."
  ^DataSource [^DataSource ds]
  (reify DataSource
    (getConnection
      [_]
      (doto (DataSource/.getConnection ds) (set-session-org! (tc/current-org))))

    (getConnection
      [_ user password]
      (doto (DataSource/.getConnection ds user password) (set-session-org! (tc/current-org))))

    (getLoginTimeout [_] (DataSource/.getLoginTimeout ds))

    (setLoginTimeout [_ seconds] (DataSource/.setLoginTimeout ds seconds))

    (getLogWriter [_] (DataSource/.getLogWriter ds))

    (setLogWriter [_ writer] (DataSource/.setLogWriter ds writer))

    (getParentLogger [_] (DataSource/.getParentLogger ds))

    (unwrap [_ iface] (DataSource/.unwrap ds iface))

    (isWrapperFor [_ iface] (DataSource/.isWrapperFor ds iface))))
