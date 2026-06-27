(ns graphden.tenancy.subdomain-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.addon]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.subdomain :as sub]
    [integrant.core :as ig]))


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


(deftest request-scope-binds-org-from-subdomain
  (let [rs (ig/init-key :tenancy/request-scope
                        {:org-resolver (sub/static-org-resolver {"acme" "org-acme"})
                         :base-domain "graphden.app"})
        captured (atom nil)
        thunk (fn [] (reset! captured (tc/current-org)) {:status 200 :body "ok"})]
    (testing "a mapped Host subdomain binds *current-org* to its org"
      (rs {} {:headers {"host" "acme.graphden.app"}} thunk)
      (is (= "org-acme" @captured)))
    (testing "the apex host has no subdomain org and no token → public"
      (reset! captured nil)
      (rs {} {:headers {"host" "graphden.app"}} thunk)
      (is (= tc/public-org @captured)))))
