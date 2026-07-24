(ns graphden.tenancy.storage-test
  "OrgScopedStorage isolation logic, proven against an in-memory fake
   StorageCRUD (no DB). Validates the security contract: writes stamp the
   current org, reads see {own, public}, cross-tenant writes don't land,
   tenants can't be reassigned, non-scoped entities pass through."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.persist :as persist]
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


  (query-latest-per-group [_ en where _gc] (filterv #(match? where %) (vals (get @rows en))))


  ;; Only the two batch reads OrgScopedStorage's query-ref-many-owners needs;
  ;; the write-side batch methods aren't exercised by these isolation tests.
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  sp/StorageBatchCRUD

  (read-entities
    [_ en ids]
    (into {} (keep (fn [id] (when-let [row (get-in @rows [en id])] [id row]))) ids))


  (query-ref-many-owners
    [_ en field target-id]
    (into [] (keep (fn [[id row]] (when (some #{target-id} (get row field)) id)))
          (get @rows en))))


(defn- fake
  []
  (->FakeStorage (atom {})))


(deftest write-stamps-current-org
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :fn {:id 1 :name "a"}))
    (is (= "acme" (:org-id (tc/with-org "acme" (sp/read-entity s :fn 1))))
        "create stamps :org-id with the current org")))


(deftest completion-future-conveys-org-so-terminal-update-lands
  ;; `record-completion!` writes the terminal status in a `future`.
  ;; `:fn-execution` is org-scoped, so that UPDATE runs through the
  ;; own-guard and must carry the tenant's `*current-org*`. Clojure's
  ;; `future` conveys dynamic bindings, so the bare future is correct —
  ;; this locks that contract: a completion started under org "acme"
  ;; must land on acme's row (regression guard against ever moving the
  ;; reaper onto a non-conveying executor / raw thread).
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "acme" (sp/create-entity s :fn-execution {:id 1 :status :pending}))
    ;; `record-completion!` is invoked UNDER org "acme" so its future
    ;; captures that binding; the reaper then runs on a pool thread.
    @(tc/with-org "acme"
                  (persist/record-completion! s 1 nil (future 42) (atom #{}) #{}))
    (is (= :succeeded
           (:status (tc/with-org "acme" (sp/read-entity s :fn-execution 1))))
        "terminal status landed on the tenant's own row")))


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


(deftest query-ref-many-owners-filters-to-visible-owners
  ;; The reverse-ref read is the one that used to delegate to base
  ;; UNFILTERED — a tenant could reverse-ref a shared/public row and learn
  ;; owner-ids (and their count) across every org. It must filter to
  ;; {own, public} like every other read.
  (let [s (ts/org-scoped-storage (fake))]
    ;; A shared public base-fn, inherited by fns in three orgs.
    (tc/with-org tc/public-org (sp/create-entity s :fn {:id "base" :name "const"}))
    (tc/with-org "acme"        (sp/create-entity s :fn {:id "a1" :parent-ids ["base"]}))
    (tc/with-org "beta"        (sp/create-entity s :fn {:id "b1" :parent-ids ["base"]}))
    (tc/with-org tc/public-org (sp/create-entity s :fn {:id "p1" :parent-ids ["base"]}))
    (testing "acme sees only its own + public owners, never beta's"
      (let [owners (set (tc/with-org "acme"
                                     (sp/query-ref-many-owners s :fn :parent-ids "base")))]
        (is (contains? owners "a1") "own owner visible")
        (is (contains? owners "p1") "public owner visible")
        (is (not (contains? owners "b1")) "another org's owner is NOT leaked")))
    (testing "beta symmetrically sees only its own + public"
      (let [owners (set (tc/with-org "beta"
                                     (sp/query-ref-many-owners s :fn :parent-ids "base")))]
        (is (= #{"b1" "p1"} owners))))))


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
      (is (some? (tc/with-org "acme" (sp/create-entity s :fn {:id 3 :name "ok"})))))
    (testing "but :branch is now ORG-SCOPED, not forbidden — a tenant gets its own"
      (is (some? (tc/with-org "acme" (sp/create-entity s :branch {:id 4 :name "feat"})))
          "§4: tenants create their own branches (stamped with their org)"))))


(deftest tenants-cannot-read-privileged-entities
  ;; Read mirror: a tenant must not enumerate platform state (:service/:grant/
  ;; :domain) — e.g. the grants panel's :list-grants would leak every org's
  ;; grants. The platform (public org) reads them.
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org tc/public-org
                 (sp/create-entity s :grant {:id (random-uuid) :subject-id "alice"})
                 (sp/create-entity s :service {:id 2 :name "svc"})
                 (sp/create-entity s :domain {:id 3 :hostname "app.acme.com"}))
    (testing "a tenant sees nothing of the privileged entities"
      (tc/with-org "acme"
                   (is (= [] (sp/query-entities s :grant {})))
                   (is (= [] (sp/query-entities s :service {})))
                   (is (nil? (sp/read-entity s :grant 1)))
                   (is (= [] (sp/query-latest-per-group s :grant {} [:subject])))))
    (testing "the platform (public org) sees them"
      (tc/with-org tc/public-org
                   (is (= 1 (count (sp/query-entities s :grant {}))))
                   (is (some? (sp/read-entity s :service 2)))))
    (testing "a tenant still reads its own graph entities"
      (tc/with-org "acme" (sp/create-entity s :fn {:id 9 :name "mine"}))
      (is (some? (tc/with-org "acme" (sp/read-entity s :fn 9)))))))


;; ============================================================================
;; Cross-org graph edges. The org-sharded compiled registry
;; (`executor.compile-runtime/read-graph` + `:executor-orgs`) can only be
;; correct if no edge leaves {own-org, public}: a pod that holds only
;; `acme`'s shard must be able to resolve every ref `acme`'s fns make.
;; ============================================================================

(deftest rejects-a-binding-ref-into-another-org
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "beta" (sp/create-entity s :fn {:id 2 :name "beta-fn"}))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"another org"
          (tc/with-org "acme"
                       (sp/create-entity s :binding {:id 10 :fn-id 1 :slot-id 5 :ref-fn-id 2})))
        "acme may not point a binding at beta's fn")))


(deftest rejects-a-parent-id-into-another-org
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "beta" (sp/create-entity s :fn {:id 2 :name "beta-fn"}))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"another org"
          (tc/with-org "acme"
                       (sp/create-entity s :fn {:id 1 :name "acme-fn" :parent-ids [2]})))
        "inheritance is an edge too — it must not cross orgs")))


(deftest allows-refs-to-public-and-own-rows
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org tc/public-org (sp/create-entity s :fn {:id 3 :name "platform-fn"}))
    (tc/with-org "acme" (sp/create-entity s :fn {:id 1 :name "acme-base"}))
    (testing "a tenant inherits from the platform graph — the whole point of own-plus-public"
      (is (tc/with-org "acme"
                       (sp/create-entity s :fn {:id 4 :name "acme-child" :parent-ids [3]}))))
    (testing "and from its own fns"
      (is (tc/with-org "acme"
                       (sp/create-entity s :binding {:id 11 :fn-id 4 :slot-id 5 :ref-fn-id 1}))))
    (testing "an un-owned (NULL org) row is the shared platform graph too"
      (sp/create-entity (fake) :fn {:id 7 :name "unowned"})
      (is (tc/with-org "acme"
                       (sp/create-entity s :fn {:id 8 :name "dangling-ok" :parent-ids [999]}))
          "a target that doesn't exist is not this guard's business"))))


(deftest rejects-a-type-override-into-another-org
  (let [s (ts/org-scoped-storage (fake))]
    (tc/with-org "beta" (sp/create-entity s :fn {:id 2 :name "beta-type"}))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"another org"
          (tc/with-org "acme"
                       (sp/update-entity s :binding 10 {:type-override-fn-id 2})))
        "a type-override is compiled, so it is a shard edge like any other")))
