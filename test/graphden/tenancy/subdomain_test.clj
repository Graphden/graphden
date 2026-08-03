(ns graphden.tenancy.subdomain-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.tenancy.addon]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.subdomain :as sub]
    [integrant.core :as ig]))


(defn- token-provider
  "Authenticates `Bearer <tok>` against a `{tok {:user :org}}` map."
  [tok->principal]
  (reify auth/AuthProvider
    (authenticate
      [_ request]
      (let [h (get-in request [:headers "authorization"])]
        (when (and h (str/starts-with? h "Bearer "))
          (get tok->principal (subs h 7)))))))


(deftest extract-subdomain-test
  (testing "single-level subdomain under the base domain"
    (is (= "acme" (sub/extract-subdomain "acme.graphden.app" "graphden.app")))
    (is (= "acme" (sub/extract-subdomain "ACME.Graphden.App" "graphden.app")))
    (is (= "acme" (sub/extract-subdomain "acme.graphden.app:8080" "graphden.app"))))
  (testing "no subdomain → nil"
    (is (nil? (sub/extract-subdomain "graphden.app" "graphden.app")))
    (is (nil? (sub/extract-subdomain "localhost:9002" "graphden.app")))
    (is (nil? (sub/extract-subdomain "example.com" "graphden.app"))))
  (testing "multi-level subdomain doesn't name one org → nil"
    (is (nil? (sub/extract-subdomain "a.b.graphden.app" "graphden.app"))))
  (testing "nil / blank inputs"
    (is (nil? (sub/extract-subdomain nil "graphden.app")))
    (is (nil? (sub/extract-subdomain "acme.graphden.app" nil)))
    (is (nil? (sub/extract-subdomain "" "graphden.app")))))


(deftest static-org-resolver-test
  (let [r (sub/static-org-resolver {"acme" "org-acme" "beta" "org-beta"})]
    (is (= "org-acme" (sub/org-for-subdomain r "acme")))
    (is (= "org-beta" (sub/org-for-subdomain r "beta")))
    (testing "unmapped subdomain → nil"
      (is (nil? (sub/org-for-subdomain r "nope"))))))


(deftest identity-org-resolver-test
  (testing "the subdomain label IS the org-id — no table needed"
    (let [r (sub/identity-org-resolver)]
      (is (= "acme" (sub/org-for-subdomain r "acme")))
      (is (= "beta" (sub/org-for-subdomain r "beta")))
      (is (= "acme" (sub/org-from-request r {:headers {"host" "acme.graphden.app"}} "graphden.app")))
      (testing "apex still has no subdomain → nil → token-org fallback"
        (is (nil? (sub/org-from-request r {:headers {"host" "graphden.app"}} "graphden.app")))))))


(deftest org-from-request-test
  (let [r (sub/static-org-resolver {"acme" "org-acme"})
        req (fn [host] {:headers {"host" host}})]
    (testing "mapped subdomain → its org"
      (is (= "org-acme" (sub/org-from-request r (req "acme.graphden.app") "graphden.app"))))
    (testing "apex / unmapped / no resolver / no base-domain → nil (falls back to token org)"
      (is (nil? (sub/org-from-request r (req "graphden.app") "graphden.app")))
      (is (nil? (sub/org-from-request r (req "other.graphden.app") "graphden.app")))
      (is (nil? (sub/org-from-request nil (req "acme.graphden.app") "graphden.app")))
      (is (nil? (sub/org-from-request r (req "acme.graphden.app") nil))))))


(deftest request-scope-subdomain-is-a-guard-not-a-widener
  ;; The token is the org authority (single-membership). The Host subdomain
  ;; can only DENY a mismatched org, never grant another org's context.
  (let [provider (token-provider {"acme-tok" {:user "alice" :org "acme"}})
        rs (ig/init-key :tenancy/request-scope
                        {:org-resolver (sub/identity-org-resolver)
                         :base-domain "graphden.app"})
        ctx {:auth-provider provider}
        captured (atom nil)
        thunk (fn [] (reset! captured (tc/current-org)) {:status 200 :body "ok"})
        req (fn [host tok]
              {:headers (cond-> {"host" host}
                          tok (assoc "authorization" (str "Bearer " tok)))})]
    (testing "a member on THEIR subdomain → their org"
      (reset! captured nil)
      (let [resp (rs ctx (req "acme.graphden.app" "acme-tok") thunk)]
        (is (= 200 (:status resp)))
        (is (= "acme" @captured))))
    (testing "a member on a FOREIGN subdomain → 403, thunk never runs (no leak)"
      (reset! captured nil)
      (let [resp (rs ctx (req "beta.graphden.app" "acme-tok") thunk)]
        (is (= 403 (:status resp)))
        (is (nil? @captured))))
    (testing "anonymous on a subdomain → public, NOT the subdomain's org (no leak)"
      (reset! captured nil)
      (let [resp (rs ctx (req "acme.graphden.app" nil) thunk)]
        (is (= 200 (:status resp)))
        (is (= tc/public-org @captured))))
    (testing "a member with no subdomain (apex) → their org from the token"
      (reset! captured nil)
      (rs ctx (req "graphden.app" "acme-tok") thunk)
      (is (= "acme" @captured)))))


(deftest reserved-labels-never-resolve-to-a-tenant
  ;; Platform hosts (app.<domain> — the editor/API entry, www, api, …) must
  ;; fall through like the apex, not route to a tenant org named "app".
  (let [r (sub/wrap-reserved (sub/identity-org-resolver)
                             sub/default-reserved-labels)]
    (testing "reserved labels → nil (platform fall-through)"
      (doseq [l ["app" "www" "api" "admin" "demo"]]
        (is (nil? (sub/org-for-subdomain r l)) l)))
    (testing "ordinary org labels resolve as before"
      (is (= "acme" (sub/org-for-subdomain r "acme"))))
    (testing "an empty reserved set disables reservation"
      (is (= "app" (sub/org-for-subdomain
                     (sub/wrap-reserved (sub/identity-org-resolver) [])
                     "app"))))))


(deftest reserved-org-name?-gates-self-serve-creation
  (testing "reserved (case-insensitive) → true"
    (is (sub/reserved-org-name? "app"))
    (is (sub/reserved-org-name? "WWW")))
  (testing "ordinary / nil names pass"
    (is (not (sub/reserved-org-name? "acme")))
    (is (not (sub/reserved-org-name? nil)))
    (is (not (sub/reserved-org-name? "demo-abc123"))
        "generated demo-<rand> org names are NOT the bare reserved label")))
