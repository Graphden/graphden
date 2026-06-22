(ns ^:integration graphden.system.demo-branches-test
  "Coverage for `system.demo-branches/seed!` — closes the test gap
   noted in [[project_test_audit_2026_06_12]]. Exercised against the
   real versioned storage (testcontainer-backed) so the multimethod
   dispatch + branch-creation paths run end-to-end."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.core :as sys]
    [graphden.system.demo-branches :as db]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)]
         (sys/bootstrap-from-packages! storage ["core" "web" "app"]
                                       {:skip-type-check? false})
         (try
           (binding [*storage* storage] (t))
           (finally (sp/close storage)))))))


(defn- branch-row
  [name]
  (let [base (vs/unwrap *storage*)]
    (first (sp/query-entities base :branch {:name name}))))


(defn- on-branch
  [branch-id]
  (vs/switch-branch *storage* branch-id))


(deftest seed-empty-branches-is-noop
  (testing "seed! on an empty seq does not throw and writes nothing"
    (let [before (count (sp/query-entities (vs/unwrap *storage*) :branch {}))]
      (db/seed! *storage* [])
      (is (= before (count (sp/query-entities (vs/unwrap *storage*) :branch {})))
          "no new branches added"))))


(deftest seed-creates-new-branch
  (testing "seed! creates a missing branch and applies mutations there"
    (let [branch-name (str "demo-create-" (System/currentTimeMillis))]
      (db/seed! *storage*
                [{:name        branch-name
                  :description "smoke"
                  :mutations
                  [{:type :create-fn
                    :name (str branch-name "-fn")
                    :parent "identity"
                    :description "created only on this branch"}]}])
      (let [br (branch-row branch-name)]
        (is (some? br) "branch row exists")
        (let [branch-storage (on-branch (:id br))
              created (fn-lookup/query-fn-by-name
                        branch-storage (str branch-name "-fn") true)]
          (is (some? created)
              "created fn resolves on the new branch")
          (is (= "created only on this branch" (:description created))
              "fn carries the declared description"))
        (testing "mutation did NOT leak onto main"
          (let [main (branch-row "main")
                main-storage (on-branch (:id main))]
            (is (nil? (fn-lookup/query-fn-by-name
                        main-storage (str branch-name "-fn") true))
                "no leak — fn only exists on the demo branch")))))))


(deftest seed-is-idempotent
  (testing "re-seeding the same branch does not create a duplicate"
    (let [branch-name (str "demo-idemp-" (System/currentTimeMillis))
          decl [{:name      branch-name
                 :mutations [{:type :update-fn-description
                              :fn-name "identity"
                              :description "first pass"}]}]]
      (db/seed! *storage* decl)
      (let [first-id (:id (branch-row branch-name))]
        (db/seed! *storage* decl)
        (is (= first-id (:id (branch-row branch-name)))
            "branch row id unchanged after re-seed")))))


(deftest seed-update-fn-description-applies-on-branch-only
  (testing ":update-fn-description writes the new description on the branch"
    (let [branch-name (str "demo-desc-" (System/currentTimeMillis))
          new-desc (str "edited on " branch-name)]
      (db/seed! *storage*
                [{:name branch-name
                  :mutations [{:type :update-fn-description
                               :fn-name "identity"
                               :description new-desc}]}])
      (let [br (branch-row branch-name)
            branch-storage (on-branch (:id br))
            edited (fn-lookup/query-fn-by-name branch-storage "identity" true)]
        (is (= new-desc (:description edited))
            "branch view shows the edited description"))
      (let [main (branch-row "main")
            main-storage (on-branch (:id main))
            on-main (fn-lookup/query-fn-by-name main-storage "identity" true)]
        (is (not= new-desc (:description on-main))
            "main view unchanged")))))


(deftest seed-tolerates-unknown-mutation
  (testing "unknown mutation :type — warning logged, no exception"
    (let [branch-name (str "demo-unk-" (System/currentTimeMillis))]
      (db/seed! *storage*
                [{:name branch-name
                  :mutations [{:type :nope-not-a-real-mutation}]}])
      (is (some? (branch-row branch-name))
          "branch still created — mutation just no-ops"))))


(deftest seed-skips-branch-missing-name
  (testing "decl missing :name → warn + skip without throwing"
    (let [before (count (sp/query-entities (vs/unwrap *storage*) :branch {}))]
      (db/seed! *storage* [{:description "no name set"}])
      (is (= before (count (sp/query-entities (vs/unwrap *storage*) :branch {})))
          "no branch row created"))))


(deftest seed-warns-on-missing-base
  (testing "base branch not found → warn + skip"
    (let [branch-name (str "demo-no-base-" (System/currentTimeMillis))]
      (db/seed! *storage*
                [{:name branch-name
                  :base "ghost-branch-does-not-exist"
                  :mutations [{:type :create-fn
                               :name "ghost-fn"
                               :parent "identity"}]}])
      (is (nil? (branch-row branch-name))
          "no branch created when base is missing"))))


(deftest seed-create-fn-warns-when-parent-missing
  (testing ":create-fn with unknown :parent → warn + skip, branch still created"
    (let [branch-name (str "demo-noparent-" (System/currentTimeMillis))]
      (db/seed! *storage*
                [{:name branch-name
                  :mutations [{:type :create-fn
                               :name (str branch-name "-orphan")
                               :parent "no-such-parent-fn"}]}])
      (let [br (branch-row branch-name)
            branch-storage (on-branch (:id br))]
        (is (some? br) "branch created")
        (is (nil? (fn-lookup/query-fn-by-name
                    branch-storage (str branch-name "-orphan") true))
            "orphan fn not created")))))


(deftest seed-mutation-exception-is-isolated
  (testing "exception inside a mutation handler does not abort other branches"
    (let [bad-name (str "demo-bad-" (System/currentTimeMillis))
          good-name (str "demo-good-" (System/currentTimeMillis))
          good-fn (str good-name "-fn")]
      (db/seed! *storage*
                [{:name bad-name
                  :mutations
                  [{:type :update-fn-description
                    :fn-name "no-such-fn-anywhere"
                    :description "won't apply"}]}
                 {:name good-name
                  :mutations
                  [{:type :create-fn
                    :name good-fn
                    :parent "identity"}]}])
      (is (some? (branch-row good-name))
          "good branch was still created after bad branch's mutation")
      (let [br (branch-row good-name)
            bs (on-branch (:id br))]
        (is (some? (fn-lookup/query-fn-by-name bs good-fn true))
            "good branch's mutation applied")))))
