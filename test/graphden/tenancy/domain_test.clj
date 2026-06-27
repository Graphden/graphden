(ns graphden.tenancy.domain-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.tenancy.addon]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.domain :as domain]
    [integrant.core :as ig]))


(defn- token-provider
  [tok->principal]
  (reify auth/AuthProvider
    (authenticate
      [_ request]
      (let [h (get-in request [:headers "authorization"])]
        (when (and h (str/starts-with? h "Bearer "))
          (get tok->principal (subs h 7)))))))


(deftest verify-domain-ownership-test
  (testing "TXT record graphden-verify=<token> present → verified"
    (is (true? (domain/verify-domain-ownership
                 "app.acme.com" "tok123"
                 (fn [_] ["some-other=x" "graphden-verify=tok123"])))))
  (testing "missing / wrong token → not verified"
    (is (false? (domain/verify-domain-ownership "app.acme.com" "tok123" (fn [_] []))))
    (is (false? (domain/verify-domain-ownership "app.acme.com" "tok123" (fn [_] ["graphden-verify=NOPE"])))))
  (testing "blank hostname / token → false (never DNS-call on junk)"
    (is (false? (domain/verify-domain-ownership "" "tok" (fn [_] (throw (AssertionError. "no call"))))))
    (is (false? (domain/verify-domain-ownership "app.acme.com" "" (fn [_] (throw (AssertionError. "no call"))))))))


(deftest static-host-resolver-test
  (let [r (domain/static-host-resolver {"app.acme.com" "acme" "beta.io" "beta-org"})]
    (is (= "acme" (domain/org-for-host r "app.acme.com")))
    (is (= "acme" (domain/org-for-host r "APP.acme.com:8443")))
    (is (= "beta-org" (domain/org-for-host r "beta.io")))
    (testing "unmapped host → nil"
      (is (nil? (domain/org-for-host r "evil.com"))))
    (testing "org-from-request reads the Host header"
      (is (= "acme" (domain/org-from-request r {:headers {"host" "app.acme.com"}})))
      (is (nil? (domain/org-from-request nil {:headers {"host" "app.acme.com"}}))))))


(deftest request-scope-custom-domain-is-a-guard
  (let [provider (token-provider {"acme-tok" {:user "alice" :org "acme"}})
        rs (ig/init-key :tenancy/request-scope
                        {:host-resolver (domain/static-host-resolver
                                          {"app.acme.com" "acme" "app.beta.com" "beta"})})
        ctx {:auth-provider provider}
        captured (atom nil)
        thunk (fn [] (reset! captured (tc/current-org)) {:status 200})
        req (fn [host tok]
              {:headers (cond-> {"host" host}
                          tok (assoc "authorization" (str "Bearer " tok)))})]
    (testing "member on THEIR verified custom domain → their org"
      (reset! captured nil)
      (is (= 200 (:status (rs ctx (req "app.acme.com" "acme-tok") thunk))))
      (is (= "acme" @captured)))
    (testing "member on ANOTHER org's custom domain → 403 (no leak)"
      (reset! captured nil)
      (is (= 403 (:status (rs ctx (req "app.beta.com" "acme-tok") thunk))))
      (is (nil? @captured)))
    (testing "anonymous on a custom domain → public, not the domain's org"
      (reset! captured nil)
      (is (= 200 (:status (rs ctx (req "app.acme.com" nil) thunk))))
      (is (= tc/public-org @captured)))))
