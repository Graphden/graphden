(ns graphden.packages.app.execute-result-tainted-test
  "The execute-popover's `Result hidden` pane keys on the REDACTOR's
   markers — `:tainted?` on the inline `/api/execute` body, or
   `:error-data {:reason :tainted}` on a persisted row — never on the
   audit flag `:touched-secret?`. That flag fires for any effectful run
   of a fn with a secret-CAPABLE slot (`:http-get`'s `:auth-value`), so
   keying the pane on it hid every plain HTTP response as a secret.
   Runs `:_er-body` over the real synced app.execution graph."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- in-tree?
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


(defn- body
  [exec-row]
  (exec/execute-by-name harness/*context* "_er-body" {:exec exec-row}))


(deftest tainted-pane-keys-on-redaction-not-audit-flag-test
  (testing "audit flag alone (secret-capable slot + effects) keeps the result visible"
    (let [f (body {:status "succeeded"
                   :touched-secret? true
                   :runtime-effects ["network"]
                   :result {:status 200 :headers {} :body "{\"frontend\":\"abc\"}"}})]
      (is (not (in-tree? f "Result hidden")))
      (is (in-tree? f "{\"frontend\":\"abc\"}") "the response body renders")))

  (testing "inline redacted outcome hides the result"
    (let [f (body {:status "succeeded" :tainted? true :result nil})]
      (is (in-tree? f "Result hidden"))))

  (testing "persisted redacted row (error-data reason :tainted) hides the result"
    (let [f (body {:status "succeeded" :result nil :error-data {:reason :tainted}})]
      (is (in-tree? f "Result hidden"))))

  (testing "redacted failure hides the error text too"
    (let [f (body {:status "failed"
                   :tainted? true
                   :error "Result hidden: fn return-type carries :secret marker."
                   :error-data {:reason :tainted}})]
      (is (in-tree? f "Result hidden")))))
