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


(deftest package-install-pin-is-org-scoped
  (let [s (ts/org-scoped-storage (setup/create-test-storage))
        branch-id (random-uuid)
        pin-id (:id (tc/with-org "acme"
                                 (sp/create-entity s :package-install
                                                   {:branch-id branch-id
                                                    :package-name "some.pkg"
                                                    :version "1.0.0"})))]
    (testing "a pin is stamped with the owner org"
      (is (= "acme" (:org-id (tc/with-org "acme" (sp/read-entity s :package-install pin-id))))))
    (testing "another org can neither read nor enumerate it"
      (is (nil? (tc/with-org "beta" (sp/read-entity s :package-install pin-id))))
      (is (not-any? #(= "some.pkg" (:package-name %))
                    (tc/with-org "beta" (sp/query-entities s :package-install {})))
          "beta does not see acme's pin"))))


(deftest ns-scoped-crud-roundtrips-against-postgres
  ;; Regression for the cross-tenant :ns isolation gap: namespaces used to be a
  ;; GLOBAL tree (any tenant could enumerate/delete/tamper another org's). :ns
  ;; is now org-scoped like :fn — a tenant sees only its own + public (core)
  ;; namespaces, and cannot read or delete another org's.
  (let [base (setup/create-test-storage)
        s (ts/org-scoped-storage base)
        acme-ns (tc/with-org "acme" (sp/create-entity s :ns {:name "acme-proj"}))
        acme-id (:id acme-ns)
        ;; A core / package namespace — created on BASE storage → NULL org.
        core-ns (sp/create-entity base :ns {:name "core-shared"})]
    (testing "create stamps the org-id"
      (is (= "acme" (:org-id (tc/with-org "acme" (sp/read-entity s :ns acme-id))))))
    (testing "another org cannot read a tenant's namespace"
      (is (nil? (tc/with-org "beta" (sp/read-entity s :ns acme-id)))))
    (testing "query isolates by org"
      (is (some #(= "acme-proj" (:name %))
                (tc/with-org "acme" (sp/query-entities s :ns {}))))
      (is (not-any? #(= "acme-proj" (:name %))
                    (tc/with-org "beta" (sp/query-entities s :ns {})))
          "beta must NOT enumerate acme's namespaces"))
    (testing "a public (NULL-org) core namespace is visible inside any org"
      (is (some? (tc/with-org "gamma" (sp/read-entity s :ns (:id core-ns))))))
    (testing "another org's delete is a no-op; the owner's delete works"
      (tc/with-org "beta" (sp/delete-entity s :ns acme-id))
      (is (some? (tc/with-org "acme" (sp/read-entity s :ns acme-id)))
          "cross-tenant namespace delete is a no-op")
      (tc/with-org "acme" (sp/delete-entity s :ns acme-id))
      (is (nil? (tc/with-org "acme" (sp/read-entity s :ns acme-id)))))))


(deftest per-namespace-write-enforcement-against-postgres
  ;; Per-target-namespace write gate (§4.2): the storage guard resolves the
  ;; fn's namespace path from the real :ns tree and checks the grant.
  (let [base (setup/create-test-storage)
        acme-ns (sp/create-entity base :ns {:name "acme"})
        team-ns (sp/create-entity base :ns {:name "team" :parent-id (:id acme-ns)})
        grants (grant/static-grant-store
                 [{:subject-id "alice" :subject "alice" :capability :write :namespace "acme.team"}])
        s (ts/org-scoped-storage base ts/default-scoped-entities
                                 (authz/authorize-writer grants base))]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "alice" :user-id "alice"}]
                   (testing "alice (granted on acme.team) can create a fn there"
                     (is (some? (sp/create-entity s :fn {:name "pns-ok" :namespace-id (:id team-ns)}))))
                   (testing "...but NOT in acme (root) — her grant doesn't cover the parent"
                     (is (thrown? clojure.lang.ExceptionInfo
                           (sp/create-entity s :fn {:name "pns-bad" :namespace-id (:id acme-ns)}))))))
    (testing "admin (public org) writes anywhere — guard skips"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "root" :user-id "root"}]
                     (is (some? (sp/create-entity s :fn {:name "pns-admin" :namespace-id (:id acme-ns)}))))))))


(deftest per-namespace-binding-edit-enforcement-against-postgres
  ;; §4.3 end-to-end through the REAL OrgScoped storage + sp/* (Risk 1): the
  ;; required capability narrows by the edit. A :bind-args user tweaks a value
  ;; but can't restructure; an :append-list user writes list-items but can't
  ;; edit a value. All resolved via the real :ns tree + binding→fn join.
  (let [base (setup/create-test-storage)
        acme-ns (sp/create-entity base :ns {:name "acme"})
        team-ns (sp/create-entity base :ns {:name "team" :parent-id (:id acme-ns)})
        int-id (get setup/primitive-fn-ids :int)
        grants (grant/static-grant-store
                 [{:subject-id "wendy" :subject "wendy" :capability :write :namespace "acme.team"}
                  {:subject-id "edith" :subject "edith" :capability :bind-args :namespace "acme.team"}
                  {:subject-id "ann" :subject "ann" :capability :append-list :namespace "acme.team"}])
        s (ts/org-scoped-storage base ts/default-scoped-entities
                                 (authz/authorize-writer grants base))
        ;; wendy (:write) builds the fn + slot + binding — all stamped "acme".
        [f b]
        (tc/with-org "acme"
                     (binding [tc/*current-principal* {:user "wendy" :user-id "wendy"}]
                       (let [f (sp/create-entity s :fn {:name "tf" :namespace-id (:id team-ns)})
                             slot (sp/create-entity s :slot {:name "arg" :type-fn-id int-id})
                             b (sp/create-entity s :binding {:fn-id (:id f) :slot-id (:id slot)
                                                             :value 1 :value-present true})]
                         [f b])))]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "edith" :user-id "edith"}]
                   (testing ":bind-args edits the binding VALUE via real sp/update-entity"
                     (is (some? (sp/update-entity s :binding (:id b) {:value 2 :value-present true}))))
                   (testing "...but a ref / structure change is forbidden (needs :write)"
                     (is (thrown? clojure.lang.ExceptionInfo
                           (sp/update-entity s :binding (:id b) {:ref-fn-id (:id f)})))))
                 (binding [tc/*current-principal* {:user "ann" :user-id "ann"}]
                   (testing ":append-list writes a list-item but cannot edit a binding value"
                     (is (some? (sp/create-entity s :binding-list-item
                                                  {:binding-id (:id b) :position 0 :value 5})))
                     (is (thrown? clojure.lang.ExceptionInfo
                           (sp/update-entity s :binding (:id b) {:value 9 :value-present true}))))))))
