(ns graphden.tenancy.storage-test
  "OrgScopedStorage isolation logic, proven against an in-memory fake
   StorageCRUD (no DB). Validates the security contract: writes stamp the
   current org, reads see {own, public}, cross-tenant writes don't land,
   tenants can't be reassigned, non-scoped entities pass through."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.storage :as ts]))


;; --- minimal in-memory StorageCRUD; `rows` = atom {entity {id row}} ---
(defn- match?
  [where row]
  (every? (fn [[k v]] (= (get row k) v)) where))


(defrecord FakeStorage
  [rows]

  sp/StorageCRUD

  (create-entity
    [_ en data]
    (let [row (assoc data :id (:id data))]
      (swap! rows assoc-in [en (:id data)] row)
      row))


  (read-entity [_ en id] (get-in @rows [en id]))


  (update-entity
    [_ en id data]
    (let [row (merge (get-in @rows [en id]) data)]
      (swap! rows assoc-in [en id] row)
      row))


  (delete-entity [_ en id] (swap! rows update en dissoc id) nil)


  (query-entities [_ en where] (filterv #(match? where %) (vals (get @rows en))))


  (query-entities [_ en where _opts] (filterv #(match? where %) (vals (get @rows en))))


  (query-latest-per-group [_ en where _gc] (filterv #(match? where %) (vals (get @rows en)))))


(defn- fake
  []
  (->FakeStorage (atom {})))


(deftest write-stamps-current-org
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :fn {:id 1 :name "a"}))
    (is (= "acme" (:org-id (tc/with-org "acme" (sp/read-entity s :fn 1))))
        "create stamps :org-id with the current org")))


(deftest reads-see-own-and-public-only
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :fn {:id 1 :name "acme-fn"}))
    (tc/with-org "beta" (sp/create-entity s :fn {:id 2 :name "beta-fn"}))
    (tc/with-org tc/public-org (sp/create-entity s :fn {:id 3 :name "platform-fn"}))
    (testing "read-entity"
      (is (some? (tc/with-org "acme" (sp/read-entity s :fn 1))) "own row")
      (is (some? (tc/with-org "acme" (sp/read-entity s :fn 3))) "public row")
      (is (nil? (tc/with-org "acme" (sp/read-entity s :fn 2))) "other org's row invisible"))
    (testing "query-entities filters to {own, public}"
      (let [names (tc/with-org "acme"
                               (set (map :name (sp/query-entities s :fn {}))))]
        (is (= #{"acme-fn" "platform-fn"} names))))))


(deftest writes-only-touch-own-rows
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "beta" (sp/create-entity s :fn {:id 2 :name "beta-fn"}))
    (tc/with-org tc/public-org (sp/create-entity s :fn {:id 3 :name "platform-fn"}))
    (testing "update of another org's row is a no-op"
      (tc/with-org "acme" (sp/update-entity s :fn 2 {:name "hijacked"}))
      (is (= "beta-fn" (tc/with-org "beta" (:name (sp/read-entity s :fn 2))))))
    (testing "update of a public row is a no-op (platform rows are read-only)"
      (tc/with-org "acme" (sp/update-entity s :fn 3 {:name "hijacked"}))
      (is (= "platform-fn" (tc/with-org tc/public-org (:name (sp/read-entity s :fn 3))))))
    (testing "delete of another org's row is a no-op"
      (tc/with-org "acme" (sp/delete-entity s :fn 2))
      (is (some? (tc/with-org "beta" (sp/read-entity s :fn 2)))))))


(deftest update-cannot-reassign-tenant
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :fn {:id 1 :name "a"}))
    (tc/with-org "acme" (sp/update-entity s :fn 1 {:name "a2" :org-id "beta"}))
    (let [row (tc/with-org "acme" (sp/read-entity s :fn 1))]
      (is (= "a2" (:name row)) "the legit field updates")
      (is (= "acme" (:org-id row)) "the :org-id reassignment is stripped"))))


(deftest non-scoped-entities-pass-through
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :execution {:id 9 :status "ok"}))
    (testing "no stamp"
      (is (nil? (:org-id (tc/with-org "beta" (sp/read-entity s :execution 9))))))
    (testing "no read filter — visible across orgs"
      (is (some? (tc/with-org "beta" (sp/read-entity s :execution 9)))))))


(deftest nil-org-id-is-public
  ;; Core writes (no decorator) leave :org-id NULL — that row must read as
  ;; public to every org and be writable only in public scope.
  (let [base (fake)
        s (ts/org-scoped-storage base)]
    ;; insert straight into the base, bypassing the decorator's stamp
    (sp/create-entity base :fn {:id 1 :name "legacy"})
    (is (nil? (:org-id (sp/read-entity base :fn 1))) "row has no :org-id")
    (is (some? (tc/with-org "acme" (sp/read-entity s :fn 1)))
        "a NULL-org row is visible to acme (treated as public)")
    (is (= 1 (count (tc/with-org "beta" (sp/query-entities s :fn {}))))
        "...and to beta")
    (testing "acme cannot write a NULL-org (public) row"
      (tc/with-org "acme" (sp/update-entity s :fn 1 {:name "x"}))
      (is (= "legacy" (:name (sp/read-entity base :fn 1)))))))


(deftest own-plus-public-write-isolation-roundtrip
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :fn {:id 1 :name "a"}))
    (tc/with-org "beta" (sp/create-entity s :fn {:id 2 :name "b"}))
    (is (= 1 (count (tc/with-org "acme" (sp/query-entities s :fn {}))))
        "acme sees only its own (no public rows here)")
    (is (= 1 (count (tc/with-org "beta" (sp/query-entities s :fn {}))))
        "beta sees only its own — full isolation")))


(deftest tenants-cannot-write-privileged-entities
  ;; Sandbox invariant: a tenant must not deploy a :service (runs unsandboxed)
  ;; or escalate via :grant / :domain. The platform (public org) may.
  (let [s (ts/org-scoped-storage (fake))]
    (testing "a tenant create of a privileged entity → :authz/forbidden"
      (doseq [en [:service :grant :domain]]
        (let [ex (try (tc/with-org "acme" (sp/create-entity s en {:id 1}))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) (str en " create must be denied for a tenant"))
          (is (= :authz/forbidden (:type (ex-data ex)))))))
    (testing "a tenant update of a privileged entity is denied too"
      (is (thrown? clojure.lang.ExceptionInfo
            (tc/with-org "acme" (sp/update-entity s :service 1 {:enabled? true})))))
    (testing "the platform (public org) writes them freely"
      (doseq [en [:service :grant :domain]]
        (is (some? (tc/with-org tc/public-org (sp/create-entity s en {:id 2}))))))
    (testing "tenants still write their own graph entities"
      (is (some? (tc/with-org "acme" (sp/create-entity s :fn {:id 3 :name "ok"})))))))
