(ns graphden.tenancy.auth-test
  "The addon's org-resolving AuthProvider (PLATFORM_PLAN §3.0): a request's
   bearer → {:user :org}, and the full token → org → *current-org* chain."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.addon]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.context :as tc]
    [integrant.core :as ig]))


(defn- req
  [authz]
  (cond-> {:headers {}} authz (assoc-in [:headers "authorization"] authz)))


(def ^:private tokens
  {"acme-tok" {:user "alice" :org "acme"}
   "beta-tok" {:user "bob" :org "beta"}})


(deftest token-map-provider-resolves-user-and-org
  (let [p (tauth/token-map-provider tokens)]
    (testing "a known token authenticates with its user + org"
      (is (= {:authenticated? true :user "alice" :org "acme"}
             (auth/authenticate p (req "Bearer acme-tok"))))
      (is (= "beta" (:org (auth/authenticate p (req "Bearer beta-tok"))))))
    (testing "unknown / missing / non-bearer fails closed"
      (is (= {:authenticated? false} (auth/authenticate p (req "Bearer nope"))))
      (is (= {:authenticated? false} (auth/authenticate p (req nil))))
      (is (= {:authenticated? false} (auth/authenticate p (req "Basic acme-tok")))))))


(deftest org-from-principal-bridges-to-current-org
  (let [p (tauth/token-map-provider tokens)]
    (is (= "acme" (tc/org-from-principal (auth/authenticate p (req "Bearer acme-tok")))))
    (is (= "public" (tc/org-from-principal (auth/authenticate p (req "Bearer nope"))))
        "an unauthenticated request falls back to the public org")))


(deftest init-key-builds-provider
  (let [p (ig/init-key :auth/multi-tenant-provider {:tokens tokens})]
    (is (= "acme" (:org (auth/authenticate p (req "Bearer acme-tok")))))
    (testing "empty/absent tokens → authenticates nothing (safe default)"
      (let [empty-p (ig/init-key :auth/multi-tenant-provider {})]
        (is (false? (:authenticated? (auth/authenticate empty-p (req "Bearer acme-tok")))))))))


(deftest dispatch-scopes-to-the-tokens-org
  ;; The end-to-end chain: bearer → provider resolves :org → B4 request-scope
  ;; binds *current-org* → the handler (and its storage) runs in that org.
  (let [scope (ig/init-key :tenancy/request-scope {})
        provider (tauth/token-map-provider tokens)
        base-ctx {:auth-provider provider :request-scope scope}
        router (br/->BranchRouter base-ctx "main"
                                  (atom {"main" {:handler (fn [_] (tc/current-org))}}) nil)
        call (fn [authz]
               (br/dispatch router {:request-method :get :uri "/x"
                                    :headers (cond-> {} authz (assoc "authorization" authz))
                                    :query-string nil}))]
    (is (= "acme" (call "Bearer acme-tok")) "acme's token scopes to acme")
    (is (= "beta" (call "Bearer beta-tok")) "beta's token scopes to beta")
    (is (= "public" (call nil)) "no token → public")
    (is (= "public" (call "Bearer nope")) "bad token → public")))
