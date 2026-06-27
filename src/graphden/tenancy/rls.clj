(ns graphden.tenancy.rls
  "Postgres Row-Level Security — the second isolation layer (PLATFORM_PLAN
   §3.0 B5). Belt-and-suspenders behind OrgScopedStorage: even a raw query
   that bypasses the decorator (a `:pg-query` base-fn, a manual JDBC call)
   cannot cross tenants, because Postgres itself filters every row by the
   `graphden.current_org` session variable.

   `enable-rls!` installs an own+public-read / own-write policy on each
   org-scoped table and FORCEs RLS so even the table owner is subject (a
   superuser still bypasses — connect the app as a non-superuser role in
   production). `set-current-org!` sets the per-transaction session
   variable from `*current-org*`.

   With the variable unset (admin / single-tenant), the policy is a no-op:
   every row is visible and writable — so installing RLS is safe before the
   request path sets the variable."
  (:require
    [graphden.storage.postgres.util :as util]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.storage :as ts]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)
    (javax.sql
      DataSource)))


(def ^:const org-setting
  "Custom GUC the policy reads. The dot is required — Postgres only allows
   set_config/current_setting on namespaced (dotted) custom settings."
  "graphden.current_org")


(defn- policy-statements
  "DDL for one org-scoped table: enable+force RLS and (re)create the
   own+public-read / own-write policy. `current_setting(…, true)` is
   missing-ok → NULL when unset, which the `unset` clause treats as full
   access."
  [entity]
  (let [t (util/ident->sql entity)
        org-col (util/ident->sql :org-id)
        cur (str "current_setting('" org-setting "', true)")
        unset (str cur " IS NULL OR " cur " = ''")]
    [(str "ALTER TABLE " t " ENABLE ROW LEVEL SECURITY")
     (str "ALTER TABLE " t " FORCE ROW LEVEL SECURITY")
     (str "DROP POLICY IF EXISTS org_isolation ON " t)
     (str "CREATE POLICY org_isolation ON " t
          " USING (" unset " OR " org-col " IS NULL OR " org-col " = " cur ")"
          " WITH CHECK (" unset " OR " org-col " = " cur ")")]))


(defn enable-rls!
  "Install the org-isolation policy on each org-scoped table (default:
   `ts/default-scoped-entities`). Idempotent — re-running replaces the
   policy. `ds` is a datasource/connection."
  ([ds] (enable-rls! ds ts/default-scoped-entities))
  ([ds entities]
   (doseq [entity entities
           stmt (policy-statements entity)]
     (jdbc/execute! ds [stmt]))))


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
  (with-open [stmt (.prepareStatement conn "SELECT set_config(?, ?, false)")]
    (.setString stmt 1 org-setting)
    (.setString stmt 2 (if (= org tc/public-org) "" org))
    (.execute stmt)))


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
      (doto (.getConnection ds) (set-session-org! (tc/current-org))))

    (getConnection
      [_ user password]
      (doto (.getConnection ds user password) (set-session-org! (tc/current-org))))

    (getLoginTimeout [_] (.getLoginTimeout ds))

    (setLoginTimeout [_ seconds] (.setLoginTimeout ds seconds))

    (getLogWriter [_] (.getLogWriter ds))

    (setLogWriter [_ writer] (.setLogWriter ds writer))

    (getParentLogger [_] (.getParentLogger ds))

    (unwrap [_ iface] (.unwrap ds iface))

    (isWrapperFor [_ iface] (.isWrapperFor ds iface))))
