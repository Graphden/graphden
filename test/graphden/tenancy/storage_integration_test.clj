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
    [graphden.tenancy.context :as tc]
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
