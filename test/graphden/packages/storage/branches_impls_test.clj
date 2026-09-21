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
    [graphden.test-infra.impls :as impls]))


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
