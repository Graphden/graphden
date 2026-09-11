(ns graphden.packages.storage.branches-impls-test
  "Unit tests for the `storage/branches` base-fn impls.

   `:current-branch-id` is how every graph-level read learns which
   branch it is answering for. It MUST fail loudly on a context with
   no storage: a silent nil there reads as \"the default branch\" and
   a write meant for a feature branch lands on main.

   `:effective-branch-local?` is the monotonic-OR that keeps
   runtime-config fns (the web-server's port, a vault path, a cron
   schedule) from propagating across branches on merge — true on the
   fn itself OR anywhere in its `:parent-ids` closure. A false
   negative here merges a dev port into prod. The impl also has to
   UNWRAP the VersionedStorage decorator first: the walk runs against
   base storage, not the wrapper."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.storage-double :as double]))


(use-fixtures :once (impls/impls-fixture "storage" "branches"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(deftest current-branch-id-reads-the-wrapper-and-refuses-a-storageless-ctx
  (let [branch (random-uuid)]
    (testing "the active branch id comes off the VersionedStorage wrapper"
      (is (= branch (call :current-branch-id {} {:storage {:branch-id branch}}))))

    (testing "an UNWRAPPED storage has no branch — nil, not a throw"
      ;; Raw base storage is legitimately branchless; the caller's
      ;; `:cond` handles that arm.
      (is (nil? (call :current-branch-id {} {:storage {}}))))

    (testing "a ctx with NO storage throws typed, instead of answering nil"
      ;; nil would be indistinguishable from \"branchless\" and would send
      ;; the write to the default branch.
      (is (= :execution-error/missing-storage
             (try (call :current-branch-id {} nil)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))


(deftest effective-branch-local?-is-a-monotonic-or-over-the-parent-closure
  (let [;; grand → parent → child, with the marker seeded at the top.
        child (random-uuid)
        parent (random-uuid)
        grand (random-uuid)
        base (double/rows-storage
               {:fn {child {:id child :parent-ids [parent]}
                     parent {:id parent :parent-ids [grand]}
                     grand {:id grand :parent-ids [] :branch-local? true}}})
        plain-child (random-uuid)
        plain (double/rows-storage {:fn {plain-child {:id plain-child :parent-ids []}}})
        own (random-uuid)
        own-storage (double/rows-storage
                      {:fn {own {:id own :parent-ids [] :branch-local? true}}})]

    (testing "nil fn-id → false (resolve-entity passes nil for a missing row)"
      (is (false? (call :effective-branch-local? {:fn-id nil} {:storage base}))))

    (testing "the fn's OWN :branch-local? marker wins"
      (is (true? (call :effective-branch-local? {:fn-id own} {:storage own-storage}))))

    (testing "an ANCESTOR's marker propagates down the whole chain"
      ;; Seeds like :http-server / :secret-leaf / :schedule carry the
      ;; flag; every descendant inherits the no-propagate-on-merge rule.
      (is (true? (call :effective-branch-local? {:fn-id child} {:storage base})))
      (is (true? (call :effective-branch-local? {:fn-id parent} {:storage base}))))

    (testing "a chain with no marker anywhere → false, so its rows DO merge"
      (is (false? (call :effective-branch-local? {:fn-id plain-child} {:storage plain}))))

    (testing "an unknown id → false, no throw"
      (is (false? (call :effective-branch-local? {:fn-id (random-uuid)} {:storage plain}))))

    (testing "a VersionedStorage wrapper is UNWRAPPED before the walk"
      ;; The walk's cache and its reads are per BASE storage; handing it
      ;; the wrapper would both miss the cache and fail the protocol call.
      (let [wrapper {:base-storage base :branch-id (random-uuid)}]
        (is (true? (call :effective-branch-local? {:fn-id child} {:storage wrapper})))))))
