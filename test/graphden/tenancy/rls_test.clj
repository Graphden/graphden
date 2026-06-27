(ns ^:integration graphden.tenancy.rls-test
  "Postgres RLS enforces org isolation even for RAW queries that bypass
   OrgScopedStorage (PLATFORM_PLAN §3.0 B5). The test connects as a
   non-superuser role (via SET ROLE) because a superuser bypasses RLS."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.rls :as rls]
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


(deftest org-aware-datasource-sets-session-var-from-current-org
  ;; The ops wiring (B5): the wrapped pool carries *current-org* onto every
  ;; borrowed connection as graphden.current_org, which is what RLS reads.
  (let [storage (setup/create-test-storage)
        ^DataSource wrapped (rls/org-aware-datasource (:pool storage))
        org-on-borrow (fn []
                        (with-open [c (.getConnection wrapped)]
                          (:o (jdbc/execute-one!
                                c ["SELECT current_setting('graphden.current_org', true) AS o"]))))]
    (testing "a tenant borrow sets the variable to the org"
      (is (= "acme" (tc/with-org "acme" (org-on-borrow)))))
    (testing "public / admin / unbound borrow clears it (policy grants full access)"
      (is (= "" (tc/with-org tc/public-org (org-on-borrow))))
      (is (= "" (org-on-borrow))))))
