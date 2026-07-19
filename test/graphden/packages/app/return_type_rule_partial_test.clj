(ns ^:integration graphden.packages.app.return-type-rule-partial-test
  "Executes the `/partials/return-type-rule` graph chain end-to-end —
   `:_partial-rtr-handler` against a golden-DB bootstrap. Covers the
   three response shapes: rule-owner found (intro + narrative +
   Inputs), no rule-owning ancestor (hidden body), unknown fn name
   (hidden body). The `:fix` rule-owner walk and the narrative lookup
   both run for real here; the sibling EDN-level coverage lives in
   `graphden.packages.app.rule-narratives-test`."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- render-partial
  "Run the handler with `?fn=<name>` and return the response body."
  [fn-name]
  (let [resp (ga/exec-handler :_partial-rtr-handler
                              {:query-params {"fn" fn-name}})]
    (is (= 200 (:status resp)) "handler responds 200")
    (:body resp)))


(deftest rule-owner-found
  ;; `:_fn-row-by-id-hsql` (app/lookups) has `:parent :assoc` — its
  ;; primary-parent chain reaches `:assoc`'s :return-type-rule in one
  ;; hop, so the popover attributes the computed return type to it.
  (let [body (render-partial "_fn-row-by-id-hsql")]
    (testing "header + intro"
      (is (str/includes? body "Type rule"))
      (is (str/includes? body "provenance-popover-intro"))
      (is (str/includes? body ":assoc")
          "owner attribution names the rule-owning base-fn"))
    (testing "owner nav-link carries a resolvable data-fn-id"
      (is (re-find #"data-fn-id=\"[0-9a-f-]{36}\"" body)))
    (testing "narrative prose from :_rtr-narratives"
      (is (str/includes? body "record shape")))
    (testing "Inputs table over :resolved-bindings"
      (is (str/includes? body "Inputs"))
      (is (str/includes? body "type-inline-resolution-row")))))


(deftest no-rule-owning-ancestor
  ;; `:add` is a base-fn — no primary parent at all, so no owner. The
  ;; body collapses to a hidden span; the JS caller probes for
  ;; `.provenance-popover-intro` and skips showing.
  (let [body (render-partial "add")]
    (is (str/includes? body "Type rule") "header still renders")
    (is (not (str/includes? body "provenance-popover-intro"))
        "no intro marker → JS won't show the popover")))


(deftest unknown-fn-name
  (let [body (render-partial "no-such-fn-name-xyz")]
    (is (not (str/includes? body "provenance-popover-intro"))
        "unknown name degrades to the hidden-body shape")))
