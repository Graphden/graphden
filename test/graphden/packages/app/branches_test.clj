(ns graphden.packages.app.branches-test
  "Unit tests for `app.branches`' canonical approval
   predicate — the decisions the branch write-path makes BEFORE it
   touches storage.

   The normalisers are the 400-vs-500 boundary: a bare string where a
   list belongs, a negative approval count, an unknown write policy —
   each has to read as a clean refusal instead of storing garbage or
   throwing deep inside a write. `may-approve?` is the security half:
   who may approve a merge into a target branch, including the two
   properties that are easy to regress — a NIL target fails CLOSED, and
   a non-empty `:approver-ids` is RESTRICTIVE rather than additive (the
   bug where naming reviewers restricted nobody, because the ⚙ menu
   leaves the write policy open).

   `may-approve?` reads the org-capability SEAM; the namespace installs it
   into its own copy (`test-infra.seams`), never the process global."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.seams :as ts]))


(use-fixtures :once ts/isolated-seams-fixture (impls/impls-fixture "app" "branches"))


(defn- priv
  [sym]
  (let [v (ns-resolve 'graphden.packages.app.branches.impls sym)]
    (assert v (str "no such var: " sym))
    @v))


;; ---------------------------------------------------------------------
;; may-approve? — who may approve a merge into the target
;; ---------------------------------------------------------------------

(defn- with-caps
  "Run `f` as a TENANT principal whose org capabilities answer `cap-pred`.

   Two seams, both needed. The capability fn (this namespace's copy — see
   `test-infra.seams`). `*current-org*` matters just as much: `platform-tier?`
   treats an unbound org as the platform tier, i.e. operator authority,
   so without binding it every caller reads as an admin and every
   restriction below would pass vacuously — which is exactly what the
   first run of this test showed."
  [cap-pred f]
  (tc/install-org-cap-fn! cap-pred)
  (try
    (binding [tc/*current-org* "org-under-test"] (f))
    (finally (tc/install-org-cap-fn! nil))))


(deftest may-approve?-fails-closed-without-a-target
  (let [f (priv 'may-approve?)]
    (testing "a nil target row means NO TARGET, never \"open\" — even for an admin"
      ;; A cross-org ref the OrgScoped storage filtered, or a branch with
      ;; no :base-branch-id, both arrive here as nil.
      (is (false? (f nil "u1")))
      (is (false? (f nil nil)))
      (with-caps #(= % :manage-grants)
        (fn [] (is (false? (f nil "admin"))))))))


(deftest may-approve?-treats-approver-ids-as-restrictive
  (let [f (priv 'may-approve?)
        target {:write-policy nil :owner-id "owner" :approver-ids ["r1" "r2"]}]
    (with-caps (constantly false)
      (fn []
        (testing "a named reviewer may approve"
          (is (true? (f target "r1")))
          (is (true? (f target "r2"))))
        (testing "everybody else may NOT — even with an OPEN write policy"
          ;; The regression this pins: an allow-list that merely OR'd with
          ;; the write policy restricted nobody, because the ⚙ menu leaves
          ;; the policy open.
          (is (false? (f target "someone-else")))
          (is (false? (f target nil)))
          (is (false? (f (assoc target :write-policy "open") "someone-else")))
          (is (false? (f (assoc target :owner-id "u9") "u9"))
              "not even the branch owner, when reviewers are named"))))
    (testing "an org-admin escalation still unlocks it"
      (with-caps #(= % :manage-grants)
        (fn [] (is (true? (f target "someone-else"))))))))


(deftest may-approve?-mirrors-the-write-policy-without-an-allow-list
  (let [f (priv 'may-approve?)]
    (with-caps (constantly false)
      (fn []
        (testing "owner policy: only the owner"
          (let [t {:write-policy "owner" :owner-id "o1"}]
            (is (true? (f t "o1")))
            (is (false? (f t "u2")))
            (is (false? (f t nil)))))
        (testing "admins policy: nobody without the capability"
          (let [t {:write-policy "admins" :owner-id "o1"}]
            (is (false? (f t "o1")))
            (is (false? (f t "u2")))))
        (testing "open / nil / empty policy: everyone, the self-host degrade"
          (doseq [p [nil "" "open"]]
            (is (true? (f {:write-policy p :owner-id "o1"} "anyone"))
                (str "policy " (pr-str p) " must admit"))))
        (testing "an unknown policy value fails closed"
          (is (false? (f {:write-policy "nonsense" :owner-id "o1"} "o1"))))))
    (testing "an org-admin passes the owner and admins policies"
      (with-caps #(= % :manage-grants)
        (fn []
          (is (true? (f {:write-policy "owner" :owner-id "o1"} "u2")))
          (is (true? (f {:write-policy "admins"} "u2"))))))))
