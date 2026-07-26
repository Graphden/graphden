(ns ^:integration graphden.tenancy.rls-test
  "Postgres RLS enforces org isolation even for RAW queries that bypass
   OrgScopedStorage (PLATFORM_PLAN §3.0 B5). The test connects as a
   non-superuser role (via SET ROLE) because a superuser bypasses RLS."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.addon]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.rls :as rls]
    [graphden.tenancy.storage :as ts]
    [integrant.core :as ig]
    [next.jdbc :as jdbc])
  (:import
    (javax.sql
      DataSource)))


(use-fixtures :once (setup/create-container-fixture))


(defn- names-visible
  "fn names a raw SELECT returns when scoped to `org` — run as the
   non-superuser tenant role so the policy actually applies."
  [ds org]
  (jdbc/with-transaction [tx ds]
                         (jdbc/execute! tx ["SET ROLE graphden_tenant"])
                         (rls/set-current-org! tx org)
                         (let [rows (jdbc/execute! tx ["SELECT name FROM \"fn\" WHERE name LIKE 'rls-%'"])]
                           (jdbc/execute! tx ["RESET ROLE"])
                           (set (map :fn/name rows)))))


(deftest rls-isolates-raw-queries-by-org
  (let [storage (setup/create-test-storage)
        ds (:pool storage)]
    ;; Seed as superuser (RLS bypassed) — acme, beta, and a NULL-org public row.
    (sp/create-entity storage :fn {:name "rls-acme" :org-id "acme"})
    (sp/create-entity storage :fn {:name "rls-beta" :org-id "beta"})
    (sp/create-entity storage :fn {:name "rls-public"})
    ;; A non-superuser role that IS subject to RLS, plus the policy.
    (jdbc/execute! ds ["DROP ROLE IF EXISTS graphden_tenant"])
    (jdbc/execute! ds ["CREATE ROLE graphden_tenant"])
    (jdbc/execute! ds ["GRANT SELECT ON \"fn\" TO graphden_tenant"])
    (rls/enable-rls! ds [:fn])
    (testing "a tenant sees its own rows + public, never another tenant's"
      (is (= #{"rls-acme" "rls-public"} (names-visible ds "acme")))
      (is (= #{"rls-beta" "rls-public"} (names-visible ds "beta"))))
    (testing "unset org (admin / single-tenant) sees everything — policy is a no-op"
      (is (= #{"rls-acme" "rls-beta" "rls-public"} (names-visible ds ""))))
    ;; leave the table as we found it for any container-sharing sibling ns
    (jdbc/execute! ds ["ALTER TABLE \"fn\" NO FORCE ROW LEVEL SECURITY"])
    (jdbc/execute! ds ["ALTER TABLE \"fn\" DISABLE ROW LEVEL SECURITY"])))


(deftest rls-blocks-tenant-write-to-public-rows
  ;; A single FOR ALL policy's own+public `USING` let a tenant DELETE — or
  ;; UPDATE-and-claim — a public (NULL-org) row through a RAW SQL path that
  ;; bypasses OrgScopedStorage. The split write policies are OWN-only.
  (let [storage (setup/create-test-storage)
        ds (:pool storage)]
    (sp/create-entity storage :fn {:name "rls-pub-target"}) ; NULL org ≡ public
    (jdbc/execute! ds ["DROP ROLE IF EXISTS graphden_tenant"])
    (jdbc/execute! ds ["CREATE ROLE graphden_tenant"])
    (jdbc/execute! ds ["GRANT SELECT, INSERT, UPDATE, DELETE ON \"fn\" TO graphden_tenant"])
    (rls/enable-rls! ds [:fn])
    (letfn [(as-tenant
              [org f]
              (jdbc/with-transaction [tx ds]
                                     (jdbc/execute! tx ["SET ROLE graphden_tenant"])
                                     (rls/set-current-org! tx org)
                                     (f tx)
                                     (jdbc/execute! tx ["RESET ROLE"])))
            (public-row-survives?
              []
              (boolean
                (seq (jdbc/execute!
                       ds ["SELECT 1 FROM \"fn\" WHERE name = 'rls-pub-target' AND org_id IS NULL"]))))]
      (testing "a tenant's raw DELETE of a public row affects 0 rows"
        (as-tenant "acme" (fn [tx] (jdbc/execute! tx ["DELETE FROM \"fn\" WHERE name = 'rls-pub-target'"])))
        (is (public-row-survives?) "the public row survives a tenant's raw DELETE"))
      (testing "a tenant's raw UPDATE-and-claim of a public row affects 0 rows"
        (as-tenant "acme" (fn [tx] (jdbc/execute! tx ["UPDATE \"fn\" SET org_id = 'acme' WHERE name = 'rls-pub-target'"])))
        (is (public-row-survives?) "the public row is not stolen into the tenant's org"))
      (testing "admin (unset org) is unrestricted — policy is a no-op"
        (as-tenant "" (fn [tx] (jdbc/execute! tx ["DELETE FROM \"fn\" WHERE name = 'rls-pub-target'"])))
        (is (not (public-row-survives?)) "admin deletes the public row freely")))
    (jdbc/execute! ds ["ALTER TABLE \"fn\" NO FORCE ROW LEVEL SECURITY"])
    (jdbc/execute! ds ["ALTER TABLE \"fn\" DISABLE ROW LEVEL SECURITY"])))


(deftest org-aware-datasource-sets-session-var-from-current-org
  ;; The ops wiring (B5): the wrapped pool carries *current-org* onto every
  ;; borrowed connection as graphden.current_org, which is what RLS reads.
  (let [storage (setup/create-test-storage)
        ^DataSource wrapped (rls/org-aware-datasource (:pool storage))
        org-on-borrow (fn []
                        (with-open [c (DataSource/.getConnection wrapped)]
                          (:o (jdbc/execute-one!
                                c ["SELECT current_setting('graphden.current_org', true) AS o"]))))]
    (testing "a tenant borrow sets the variable to the org"
      (is (= "acme" (tc/with-org "acme" (org-on-borrow)))))
    (testing "public / admin / unbound borrow clears it (policy grants full access)"
      (is (= "" (tc/with-org tc/public-org (org-on-borrow))))
      (is (= "" (org-on-borrow))))))


(deftest verify-rls-enforcement-detects-role-subjection
  ;; The prod prereq: RLS policies are installed but INERT under a superuser /
  ;; BYPASSRLS role. `verify-rls-enforcement!` is the boot guard that surfaces
  ;; that silent state — WARN by default, hard-fail under GRAPHDEN_STRICT_RLS.
  (let [storage (setup/create-test-storage)
        ds (:pool storage)]
    (testing "the default pool role is a superuser → RLS inert, not enforced"
      (let [{:keys [superuser? enforced?]} (rls/rls-role-status ds)]
        (is superuser? "the testcontainers default DB role is a superuser")
        (is (not enforced?) "a superuser is not subject to RLS")))
    (testing "strict? throws on a non-enforcing role"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"INERT"
            (rls/verify-rls-enforcement! ds true))))
    (testing "lenient mode warns and returns the status instead of throwing"
      (is (false? (:enforced? (rls/verify-rls-enforcement! ds false)))))
    (testing "a non-superuser, non-BYPASSRLS role IS subject to RLS"
      (jdbc/execute! ds ["DROP ROLE IF EXISTS graphden_tenant"])
      (jdbc/execute! ds ["CREATE ROLE graphden_tenant"])
      (jdbc/with-transaction [tx ds]
                             (jdbc/execute! tx ["SET ROLE graphden_tenant"])
                             (let [{:keys [role superuser? bypassrls? enforced?]} (rls/rls-role-status tx)]
                               (is (= "graphden_tenant" role))
                               (is (not superuser?))
                               (is (not bypassrls?))
                               (is enforced? "a plain role is subject to RLS"))
                             (is (:enforced? (rls/verify-rls-enforcement! tx true))
                                 "strict verify does not throw for an enforcing role")
                             (jdbc/execute! tx ["RESET ROLE"])))))


(deftest rls-enabler-init-key-installs-policies-on-every-scoped-table
  (let [storage (setup/create-test-storage)
        ds (:pool storage)
        ;; Derived from the source of truth so the test tracks the scoped-set
        ;; automatically (create-test-storage now builds the full schema, so
        ;; every scoped entity's table exists — incl. branch / fn_execution /
        ;; package_install, not just the graph tables).
        tables (mapv #(str/replace (name %) "-" "_") ts/default-scoped-entities)]
    (is (= :enabled (ig/init-key :tenancy/rls-enabler {:storage storage}))
        "the addon component runs enable-rls! at boot")
    (let [installed (->> (jdbc/execute! ds ["SELECT tablename FROM pg_policies WHERE policyname = 'org_isolation_select'"])
                         (map :pg_policies/tablename)
                         set)]
      (is (= (set tables) installed)
          "org_isolation policy installed on every org-scoped table"))
    ;; clean up so a container-sharing sibling ns isn't left with FORCE RLS
    (doseq [t tables]
      (jdbc/execute! ds [(str "ALTER TABLE \"" t "\" NO FORCE ROW LEVEL SECURITY")])
      (jdbc/execute! ds [(str "ALTER TABLE \"" t "\" DISABLE ROW LEVEL SECURITY")]))))
