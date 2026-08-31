(ns ^:serial graphden.tenancy.context-test
  "Unit tests for the core-side tenancy seam (docs/TENANCY_SEAM.md § Context):
   org binding/derivation, the platform-tier predicate, the installable
   admin/capability hooks, and the byo execution-mode memo.

   `^:serial`: the install-*-fn! tests mutate the ns-global seam atoms
   (process-wide state a parallel sibling could observe mid-test). The
   byo tests are already isolated via `*byo-cache-override*`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as ctx]))


;; =============================================================================
;; Org binding — defaults, with-org, principal derivation
;; =============================================================================

(deftest current-org-defaults-to-public
  (is (= "public" ctx/public-org))
  (is (= ctx/public-org (ctx/current-org))
      "unbound = the shared public org — single-tenant needs no org concept"))


(deftest with-org-binds-and-restores
  (ctx/with-org "acme"
                (is (= "acme" (ctx/current-org))))
  (is (= ctx/public-org (ctx/current-org))
      "binding is scoped to the body"))


(deftest with-org-nil-normalises-to-public
  (ctx/with-org nil
                (is (= ctx/public-org (ctx/current-org)))))


(deftest with-org-nests-innermost-wins
  (ctx/with-org "outer"
                (ctx/with-org "inner"
                              (is (= "inner" (ctx/current-org))))
                (is (= "outer" (ctx/current-org)))))


(deftest with-org-returns-body-value
  (is (= 42 (ctx/with-org "acme" 41 42))
      "with-org evaluates the whole body and returns the last form"))


(deftest org-from-principal-reads-org-or-falls-back
  (is (= "acme" (ctx/org-from-principal {:org "acme" :user "u"})))
  (is (= ctx/public-org (ctx/org-from-principal {:user "u"}))
      "single-token mode never sets :org — falls back to public")
  (is (= ctx/public-org (ctx/org-from-principal nil))))


;; =============================================================================
;; Platform tier predicate
;; =============================================================================

(deftest platform-tier-predicate
  (is (true? (ctx/platform-tier? nil)) "nil normalises to the public org")
  (is (true? (ctx/platform-tier? ctx/public-org)))
  (is (false? (ctx/platform-tier? "acme"))))


(deftest current-platform-tier-follows-the-bound-org
  (is (true? (ctx/current-platform-tier?)) "unbound = public = platform tier")
  (ctx/with-org "acme"
                (is (false? (ctx/current-platform-tier?))))
  (ctx/with-org nil
                (is (true? (ctx/current-platform-tier?)))))


;; =============================================================================
;; Installable seams — platform-admin / platform-cap / org-cap
;; =============================================================================

(deftest platform-admin-seam-defaults-deny-and-installs
  (try
    (is (false? (ctx/current-platform-admin?))
        "no tenancy addon → no operator escalation")
    (ctx/install-platform-admin-fn! (constantly true))
    (is (true? (ctx/current-platform-admin?)))
    (ctx/install-platform-admin-fn! (constantly :truthy))
    (is (true? (ctx/current-platform-admin?))
        "predicate result is coerced to a boolean")
    (finally
      (ctx/install-platform-admin-fn! nil)))
  (is (false? (ctx/current-platform-admin?))
      "nil restores the default-deny no-op"))


(deftest platform-cap-seam-passes-the-capability-through
  (try
    (is (false? (ctx/current-has-platform-cap? :view-all-stats))
        "default-deny with no tenancy addon")
    (ctx/install-platform-cap-fn! #(= :view-all-stats %))
    (is (true? (ctx/current-has-platform-cap? :view-all-stats)))
    (is (false? (ctx/current-has-platform-cap? :manage-orgs))
        "the installed predicate receives the asked-for capability")
    (finally
      (ctx/install-platform-cap-fn! nil)))
  (is (false? (ctx/current-has-platform-cap? :view-all-stats))))


(deftest org-cap-seam-and-addon-active-flag
  (try
    (is (false? (ctx/tenancy-addon-active?))
        "no installed org-cap policy = no tenancy addon")
    (is (false? (ctx/current-has-org-cap? :manage-users)) "default-deny")
    (ctx/install-org-cap-fn! #(= :manage-users %))
    (is (true? (ctx/tenancy-addon-active?))
        "an installed org-cap policy IS the addon-active fact")
    (is (true? (ctx/current-has-org-cap? :manage-users)))
    (is (false? (ctx/current-has-org-cap? :publish-packages)))
    (finally
      (ctx/install-org-cap-fn! nil)))
  (is (false? (ctx/tenancy-addon-active?))
      "nil uninstall flips the addon-active fact back off")
  (is (false? (ctx/current-has-org-cap? :manage-users))))


(deftest current-capabilities-nil-outside-request-scope
  (is (nil? (ctx/current-capabilities))
      "single-tenant / no addon → no list to show")
  (binding [ctx/*current-capabilities* ["edit-graph" "view-all-stats"]]
    (is (= ["edit-graph" "view-all-stats"] (ctx/current-capabilities)))))


;; =============================================================================
;; byo execution mode — memoised :org read
;; =============================================================================

(defn- org-storage
  "Minimal StorageCRUD stub: answers `:org {:name …}` queries from `rows`
   and counts reads in `reads`. Throws when `rows` is `::boom` to model a
   DB blip."
  [reads rows]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify sp/StorageCRUD
    (query-entities
      [_ entity-name where]
      (swap! reads conj [entity-name where])
      (if (= ::boom rows)
        (throw (RuntimeException. "db blip"))
        (filterv #(= (:name where) (:name %)) rows)))))


(deftest byo-org-public-and-nil-never-read-storage
  (binding [ctx/*byo-cache-override* (atom {})]
    (is (false? (ctx/byo-org? nil nil)) "nil org is hosted, storage untouched")
    (is (false? (ctx/byo-org? nil ctx/public-org))
        "the public org is never byo — nil storage proves no read happens")))


(deftest byo-org-reads-execution-mode-and-caches
  (binding [ctx/*byo-cache-override* (atom {})]
    (let [reads (atom [])
          storage (org-storage reads [{:name "byo-co" :execution-mode "byo"}
                                      {:name "cloud-co" :execution-mode nil}])]
      (is (true? (ctx/byo-org? storage "byo-co")))
      (is (= [[:org {:name "byo-co"}]] @reads) "one :org read, by name")
      (is (false? (ctx/byo-org? storage "cloud-co"))
          "anything other than \"byo\" (incl. nil) is hosted")
      (is (false? (ctx/byo-org? storage "unknown-co"))
          "an org row that doesn't exist is hosted")
      (testing "within the TTL the verdicts come from the memo"
        (let [n (count @reads)]
          (is (true? (ctx/byo-org? storage "byo-co")))
          (is (false? (ctx/byo-org? storage "cloud-co")))
          (is (= n (count @reads)) "no further storage reads"))))))


(deftest byo-org-invalidate-drops-the-memo
  (binding [ctx/*byo-cache-override* (atom {})]
    (let [reads (atom [])
          storage (org-storage reads [{:name "a" :execution-mode "byo"}
                                      {:name "b" :execution-mode "byo"}])]
      (ctx/byo-org? storage "a")
      (ctx/byo-org? storage "b")
      (testing "single-org invalidation re-reads only that org"
        (ctx/invalidate-byo-cache! "a")
        (ctx/byo-org? storage "a")
        (ctx/byo-org? storage "b")
        (is (= [[:org {:name "a"}] [:org {:name "b"}] [:org {:name "a"}]]
               @reads)))
      (testing "full invalidation re-reads everything"
        (ctx/invalidate-byo-cache!)
        (ctx/byo-org? storage "a")
        (ctx/byo-org? storage "b")
        (is (= 5 (count @reads)))))))


(deftest byo-org-read-error-fails-hosted-without-caching
  (binding [ctx/*byo-cache-override* (atom {})]
    (let [reads (atom [])
          storage (org-storage reads ::boom)]
      (is (false? (ctx/byo-org? storage "byo-co"))
          "a DB blip fails hosted for THIS request")
      (is (false? (ctx/byo-org? storage "byo-co")))
      (is (= 2 (count @reads))
          "the error verdict is NOT cached — the next request re-reads"))))
