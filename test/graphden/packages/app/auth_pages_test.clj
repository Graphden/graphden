(ns graphden.packages.app.auth-pages-test
  "Byte-for-byte parity between the `app.auth-pages` GRAPH fn-defs (the
   primary render path for /login /reset + the transactional email
   bodies) and the built-in Clojure fallbacks in
   `graphden.accounts.{pages,email}` that serve during a graph outage.
   A drifting pair would flip the auth surface's look/copy depending on
   graph health — this pins the two sides together; edit both when
   changing either. /account DIVERGES BY DESIGN (2026-08-15): the graph
   page (editor always present alongside it) redirects into the editor's
   Settings → Account card, while the built-in fallback keeps the full
   standalone page for headless deployments."
  (:require
    [clojure.string :as str]
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


(deftest account-page-redirects-into-editor
  ;; The graph /account page is a redirect into the editor's Settings →
  ;; Account deep link — intentionally NOT parity with the standalone
  ;; fallback (which serves headless deployments).
  (let [html (render "auth-account-page" {})]
    (is (str/includes? html "location.replace('/#@settings/account')"))
    (is (str/includes? html "href='/#@settings/account'")
        "no-JS fallback link present")))


(deftest account-page-api-tokens-panel
  ;; The self-serve API-tokens section: present but hidden by default — the
  ;; page JS reveals it only when GET /api/my-tokens/list answers 200 (the
  ;; routes exist only under the tenancy addon; open-core 404s and the
  ;; section stays invisible).
  (let [html (pages/account-page #{})]
    (is (str/includes? html "id='tok-sec' style='display:none'"))
    (is (str/includes? html "/api/my-tokens/list"))
    (is (str/includes? html "function mintToken"))
    (is (str/includes? html "function revokeToken"))))


(deftest reset-page-parity
  (is (= (pages/reset-page) (render "auth-reset-page" {}))))


(deftest email-body-parity
  (let [base "https://app.example.com"
        token "tok_abc-123"]
    (is (= (email/verification-email-body base token)
           (render "auth-verify-email" {:base-url base :token token})))
    (is (= (email/reset-email-body base token)
           (render "auth-reset-email" {:base-url base :token token})))))
