(ns ^:integration graphden.packages.app.secrets-guards-test
  "Negative-path coverage for the secrets guard chain — the shared
   `:_secret-path-blank?` / `:_secret-value-missing?` predicates and
   their rejection envelopes. The browser secrets tests are happy-path
   only, so before this the guards' rejection arms (the branches the
   2026-07 structural dedup actually touched) had no assertion. All
   three cases short-circuit in `:cond` BEFORE any vault call — no
   vault needed in the fixture."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- create-secret!
  [body-map]
  (let [resp (ga/exec-handler :create-secret-handler
                              {:headers {"content-type" "application/json"}
                               :body (json/generate-string body-map)})]
    (json/parse-string (:body resp) true)))


(deftest create-rejects-blank-name
  (let [r (create-secret! {:path "kv/x" :value "v"})]
    (is (false? (:ok r)))
    (is (re-find #"(?i)name" (str (:error r))))))


(deftest create-rejects-blank-path
  (let [r (create-secret! {:name "my-secret" :value "v"})]
    (is (false? (:ok r)))
    (is (= "Required field ':path' is missing" (:error r)))))


(deftest create-rejects-missing-or-non-string-value
  (testing "value absent"
    (let [r (create-secret! {:name "my-secret" :path "kv/x"})]
      (is (false? (:ok r)))
      (is (= "Required field ':value' (string) is missing" (:error r)))))
  (testing "value present but not a string"
    (let [r (create-secret! {:name "my-secret" :path "kv/x" :value 42})]
      (is (false? (:ok r)))
      (is (= "Required field ':value' (string) is missing" (:error r))))))
