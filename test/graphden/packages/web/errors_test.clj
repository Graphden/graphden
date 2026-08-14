(ns graphden.packages.web.errors-test
  "Behavioural tests for :json-envelope-response — a GRAPH composition
   over :ring-response (web/errors/fns.edn) since the impls
   decomposition, so it is driven through the executor over a golden
   clone. Pins the envelope contract: :http-status selects (and is
   stripped from) the response, 429 carries Retry-After: 1."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*)) ["core" "web"]))


(defn- respond
  [envelope]
  (exec/execute-with-named-args
    harness/*context* (harness/fn-id "json-envelope-response")
    {:envelope envelope}))


(defn- header
  [response nm]
  (some (fn [[k v]] (when (= nm (name k)) v)) (:headers response)))


(deftest json-envelope-response-contract
  (testing ":http-status selects the status and is stripped from the body"
    (let [r (respond {:ok false :http-status 404})]
      (is (= 404 (:status r)))
      (is (= "{\"ok\":false}" (:body r)))
      (is (= "application/json" (header r "Content-Type")))))
  (testing "no :http-status → 200"
    (let [r (respond {:ok true})]
      (is (= 200 (:status r)))
      (is (= "{\"ok\":true}" (:body r)))))
  (testing "429 additionally carries Retry-After: 1"
    (let [r (respond {:ok false :http-status 429})]
      (is (= 429 (:status r)))
      (is (= "1" (header r "Retry-After")))))
  (testing "non-429 has no Retry-After"
    (is (nil? (header (respond {:http-status 404}) "Retry-After"))))
  (testing "nil envelope → empty 200 body"
    (let [r (respond nil)]
      (is (= 200 (:status r)))
      (is (= "{}" (:body r))))))
