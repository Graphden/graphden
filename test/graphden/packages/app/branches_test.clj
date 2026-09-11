(ns ^:serial graphden.packages.app.branches-test
  "Unit tests for `app.branches`' request normalisers and its approval
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

   `^:serial`: `may-approve?` reads the org-capability SEAM, which is
   process-global — a parallel sibling would see this test's install."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "branches"))


(defn- priv
  [sym]
  (let [v (ns-resolve 'graphden.packages.app.branches.impls sym)]
    (assert v (str "no such var: " sym))
    @v))


(defn- err-type
  [f & args]
  (:type (ex-data (try (apply f args) nil (catch Exception e e)))))


;; ---------------------------------------------------------------------
;; normalisers — the clean-refusal boundary
;; ---------------------------------------------------------------------

(deftest normalize-write-policy-maps-open-to-nil-and-refuses-junk
  (let [f (priv 'normalize-write-policy)]
    (testing "nil / blank / \"open\" all mean the same thing: no policy row"
      (is (nil? (f nil)))
      (is (nil? (f "")))
      (is (nil? (f "   ")))
      (is (nil? (f "open"))))
    (testing "a known policy passes through, trimmed"
      (is (= "owner" (f "owner")))
      (is (= "owner" (f "  owner  ")))
      (is (= "admins" (f "admins"))))
    (testing "anything else is a clean refusal, not a stored value"
      (is (= :branches/invalid-write-policy (err-type f "everyone")))
      (is (= :branches/invalid-write-policy (err-type f "OWNER"))))))


(deftest normalize-approver-ids-refuses-a-bare-string
  (let [f (priv 'normalize-approver-ids)]
    (testing "nil and an empty list clear the allow-list"
      (is (nil? (f nil)))
      (is (nil? (f []))))
    (testing "a list of ids becomes a vector of strings"
      (is (= ["u1" "u2"] (f ["u1" "u2"])))
      (is (= ["1" "2"] (f [1 2])) "ids are stringified for the JSONB column"))
    (testing "a BARE STRING is refused — `seq` would shred it into characters"
      (is (= :validation-error/approver-ids (err-type f "u1")))
      (is (= :validation-error/approver-ids (err-type f {:u "1"}))))))


(deftest normalize-required-approvals-refuses-negative-and-non-int
  (let [f (priv 'normalize-required-approvals)]
    (testing "nil means the requirement is off"
      (is (nil? (f nil))))
    (testing "a non-negative integer passes through"
      (is (zero? (f 0)))
      (is (= 2 (f 2))))
    (testing "negative and non-integer are refused, not silently treated as off"
      (is (= :validation-error/required-approvals (err-type f -1)))
      (is (= :validation-error/required-approvals (err-type f "2")))
      (is (= :validation-error/required-approvals (err-type f 1.5))))))


;; ---------------------------------------------------------------------
;; may-approve? — who may approve a merge into the target
;; ---------------------------------------------------------------------

(defn- with-caps
  "Run `f` as a TENANT principal whose org capabilities answer `cap-pred`.

   Two seams, both needed. The capability fn is process-global (hence
   `^:serial`). `*current-org*` matters just as much: `platform-tier?`
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
