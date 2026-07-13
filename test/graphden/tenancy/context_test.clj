(ns graphden.tenancy.context-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(defn- org-storage
  "Fake storage: `query-entities :org {:name n}` → a row with `:execution-mode`
   from `name->mode`, else []. `reads` counts calls so caching is observable."
  [name->mode reads]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (swap! reads inc)
      (when (= en :org)
        (when-let [mode (get name->mode (:name where))]
          [{:name (:name where) :execution-mode mode}])))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(defn- throwing-storage
  []
  (reify sp/StorageCRUD
    (query-entities [_ _ _] (throw (ex-info "db down" {})))

    (query-entities [_ _ _ _] (throw (ex-info "db down" {})))

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest defaults-to-public
  (is (= "public" tc/public-org))
  (is (= "public" (tc/current-org)) "unbound → shared public org (single-tenant)"))


(deftest with-org-binds-dynamically
  (testing "binds the thread-local"
    (is (= "acme" (tc/with-org "acme" (tc/current-org)))))
  (testing "nil org → public (never scopes to a nil tenant)"
    (is (= "public" (tc/with-org nil (tc/current-org)))))
  (testing "scope is restored after the form"
    (tc/with-org "acme" :ignored)
    (is (= "public" (tc/current-org))))
  (testing "nests"
    (is (= "inner"
           (tc/with-org "outer"
                        (tc/with-org "inner" (tc/current-org)))))
    (is (= "outer"
           (tc/with-org "outer"
                        (tc/with-org "inner" (tc/current-org))
                        (tc/current-org))))))


(deftest org-from-principal-defaults-public
  (is (= "acme" (tc/org-from-principal {:org "acme" :authenticated? true})))
  (is (= "public" (tc/org-from-principal {:authenticated? true}))
      "principal without :org (single-token mode) → public")
  (is (= "public" (tc/org-from-principal nil)) "nil principal → public"))


(deftest byo-org?-classification
  (tc/invalidate-byo-cache!)
  (let [reads (atom 0)
        storage (org-storage {"acme" "byo" "beta" "hosted"} reads)]
    (testing "an org flagged :execution-mode byo → true"
      (is (true? (tc/byo-org? storage "acme"))))
    (testing "a hosted org (or unset mode) → false"
      (is (false? (tc/byo-org? storage "beta"))))
    (testing "the public org is never byo, and no read is issued"
      (let [before @reads]
        (is (false? (tc/byo-org? storage tc/public-org)))
        (is (false? (tc/byo-org? storage nil)))
        (is (= before @reads) "short-circuited before touching storage")))
    (testing "the verdict is memoised within the TTL (no second read)"
      (let [before @reads]
        (is (true? (tc/byo-org? storage "acme")))
        (is (= before @reads) "served from cache")))))


(deftest byo-org?-fails-hosted-on-storage-error
  ;; Availability property: a DB blip must NOT 421 every tenant — a read error
  ;; classifies the org as hosted (false), not byo.
  (tc/invalidate-byo-cache!)
  (is (false? (tc/byo-org? (throwing-storage) "acme"))))
