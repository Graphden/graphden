(ns graphden.packages.app.auth-pages-test
  "Byte-for-byte parity between the `app.auth-pages` GRAPH fn-defs (the
   primary render path for /login /account /reset + the transactional
   email bodies) and the built-in Clojure fallbacks in
   `graphden.accounts.{pages,email}` that serve during a graph outage.
   A drifting pair would flip the auth surface's look/copy depending on
   graph health — this pins the two sides together; edit both when
   changing either."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.accounts.email :as email]
    [graphden.accounts.pages :as pages]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- render
  [fn-name args]
  (exec/execute-with-named-args
    harness/*context* (harness/fn-id fn-name) args))


(defn- provider-map
  [provider-set]
  (into {} (map (fn [p] [p true])) provider-set))


(deftest login-page-parity
  (doseq [[provs tg] [[#{} nil]
                      [#{"github"} nil]
                      [#{"github" "google"} nil]
                      [#{"google"} {:bot-token "123456:abcdef"}]
                      [#{} {:bot-token "123456:abcdef"}]]]
    (testing (str "providers=" provs " telegram=" (some? tg))
      (is (= (pages/login-page provs tg)
             (render "auth-login-page"
                     {:providers (provider-map provs) :telegram tg}))))))


(deftest account-page-parity
  (doseq [provs [#{} #{"github"} #{"github" "google"}]]
    (testing (str "providers=" provs)
      (is (= (pages/account-page provs)
             (render "auth-account-page" {:providers (provider-map provs)}))))))


(deftest reset-page-parity
  (is (= (pages/reset-page) (render "auth-reset-page" {}))))


(deftest email-body-parity
  (let [base "https://app.example.com"
        token "tok_abc-123"]
    (is (= (email/verification-email-body base token)
           (render "auth-verify-email" {:base-url base :token token})))
    (is (= (email/reset-email-body base token)
           (render "auth-reset-email" {:base-url base :token token})))))
