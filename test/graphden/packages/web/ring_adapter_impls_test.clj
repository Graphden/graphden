(ns graphden.packages.web.ring-adapter-impls-test
  "Unit tests for the `web/ring-adapter` auth seam
   (docs/TENANCY_SEAM.md § Auth seam).

   These two base-fns are the whole switch between \"this deployment
   has login\" and \"this deployment is open\". `:auth-active?` is what
   the auth-required middleware reads, and `:authenticate-request` is
   the only place the pluggable provider is consulted. The security
   property worth a sentinel: a context with NO provider wired must
   fail CLOSED on authentication (nobody is authenticated) while
   `:auth-active?` reports auth OFF — an inversion of either half
   either locks a self-hosted instance out of its own editor, or hands
   an unauthenticated request a truthy principal."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "ring-adapter"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(deftest authenticate-request-fails-closed-without-a-provider
  (testing "a half-configured ctx authenticates NOTHING instead of throwing"
    ;; A throw here would 500 every request; a truthy answer would be a
    ;; full auth bypass. The only safe answer is an explicit refusal.
    (is (= {:authenticated? false}
           (call :authenticate-request {:request {:headers {}}} nil)))
    (is (= {:authenticated? false}
           (call :authenticate-request {:request {:headers {"authorization" "Bearer x"}}}
                 {:storage ::irrelevant}))))

  (testing "the decision is DELEGATED to the wired provider, verbatim"
    ;; The tenancy addon's provider returns :user / :org alongside
    ;; :authenticated?; the base-fn must pass the whole principal
    ;; through, not re-derive a boolean from it.
    (let [principal {:authenticated? true :user :u1 :org :o1}
          provider (reify auth/AuthProvider
                     (authenticate [_ _req] principal))]
      (is (= principal
             (call :authenticate-request {:request {}} {:auth-provider provider})))))

  (testing "the shipped single-token provider is reached through the seam"
    (let [ctx {:auth-provider (auth/single-token-provider "s3cret")}]
      (is (true? (:authenticated?
                   (call :authenticate-request
                         {:request {:headers {"authorization" "Bearer s3cret"}}} ctx))))
      (is (false? (:authenticated?
                    (call :authenticate-request
                          {:request {:headers {"authorization" "Bearer wrong"}}} ctx))))
      (is (false? (:authenticated?
                    (call :authenticate-request {:request {:headers {}}} ctx)))))))


(deftest auth-active?-is-exactly-provider-presence
  (testing "no provider ⇒ auth is OFF (self-hosted open instance)"
    (is (false? (call :auth-active? {} nil)))
    (is (false? (call :auth-active? {} {:storage ::irrelevant})))
    (is (false? (call :auth-active? {} {:auth-provider nil}))))

  (testing "a wired provider ⇒ auth is ON, so auth-required routes gate"
    (is (true? (call :auth-active? {} {:auth-provider (auth/single-token-provider "t")}))))

  (testing "a provider with a BLANK token still counts as auth ON"
    ;; The switch is presence, not usefulness: a misconfigured token must
    ;; not silently reopen the gated routes.
    (is (true? (call :auth-active? {} {:auth-provider (auth/single-token-provider "")})))))
