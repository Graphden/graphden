(ns ^:integration graphden.tenancy.storage-integration-test
  "OrgScopedStorage over REAL Postgres (with the B2 :org-id column) — proves
   the decorator's org logic round-trips through the actual storage backend,
   not just the in-memory fake. Stack here is OrgScoped(Postgres); the
   Versioned sandwich is covered by smoke-pass (which now boots with the
   org-id columns)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.authz :as authz]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.storage :as ts]))


(use-fixtures :once (setup/create-container-fixture))


(deftest org-scoped-crud-roundtrips-against-postgres
  (let [s (ts/org-scoped-storage (setup/create-test-storage))
        acme (tc/with-org "acme" (sp/create-entity s :fn {:name "acme-thing"}))
        acme-id (:id acme)]
    (testing "create stamps and Postgres persists the org-id"
      (is (= "acme" (:org-id (tc/with-org "acme" (sp/read-entity s :fn acme-id))))))
    (testing "another org cannot read it"
      (is (nil? (tc/with-org "beta" (sp/read-entity s :fn acme-id)))))
    (testing "query isolates by org"
      (is (some #(= "acme-thing" (:name %))
                (tc/with-org "acme" (sp/query-entities s :fn {})))
          "acme sees its own row")
      (is (not-any? #(= "acme-thing" (:name %))
                    (tc/with-org "beta" (sp/query-entities s :fn {})))
          "beta does not"))
    (testing "boot primitives (inserted with NULL org-id) are public to every org"
      (is (some #(= "int" (:name %))
                (tc/with-org "beta" (sp/query-entities s :fn {})))
          "NULL org-id ≡ public — platform fns stay visible inside any org"))
    (testing "another org cannot delete acme's row"
      (tc/with-org "beta" (sp/delete-entity s :fn acme-id))
      (is (some? (tc/with-org "acme" (sp/read-entity s :fn acme-id)))
          "cross-tenant delete is a no-op"))
    (testing "the owner can delete its own row"
      (tc/with-org "acme" (sp/delete-entity s :fn acme-id))
      (is (nil? (tc/with-org "acme" (sp/read-entity s :fn acme-id)))))))


(deftest per-namespace-write-enforcement-against-postgres
  ;; Per-target-namespace write gate (§4.2): the storage guard resolves the
  ;; fn's namespace path from the real :ns tree and checks the grant.
  (let [base (setup/create-test-storage)
        acme-ns (sp/create-entity base :ns {:name "acme"})
        team-ns (sp/create-entity base :ns {:name "team" :parent-id (:id acme-ns)})
        grants (grant/static-grant-store
                 [{:subject "alice" :capability :write :namespace "acme.team"}])
        s (ts/org-scoped-storage base ts/default-scoped-entities
                                 (authz/authorize-writer grants base))]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "alice"}]
                   (testing "alice (granted on acme.team) can create a fn there"
                     (is (some? (sp/create-entity s :fn {:name "pns-ok" :namespace-id (:id team-ns)}))))
                   (testing "...but NOT in acme (root) — her grant doesn't cover the parent"
                     (is (thrown? clojure.lang.ExceptionInfo
                           (sp/create-entity s :fn {:name "pns-bad" :namespace-id (:id acme-ns)}))))))
    (testing "admin (public org) writes anywhere — guard skips"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "root"}]
                     (is (some? (sp/create-entity s :fn {:name "pns-admin" :namespace-id (:id acme-ns)}))))))))
