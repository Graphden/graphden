(ns ^:integration graphden.integration.renamed-call-inputs-test
  "POST /api/execute preserves independent calls renamed from one base slot."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- execute-json
  [fn-name args]
  (let [response (ga/exec-handler
                   :execute-handler
                   {:request-method :post :uri "/api/execute"
                    :headers {"content-type" "application/json"}
                    :body (json/generate-string
                            {:fn-id (str (records/fn-id "test.call-inputs" fn-name))
                             :args args :timeout-ms 10000})})]
    {:status (:status response) :body (json/parse-string (:body response) true)}))


(deftest http-execute-keeps-separate-renamed-readers-and-their-bindings
  (let [{:keys [ctx storage]} ga/*bootstrap*]
    (setup/sync-and-invalidate!
      ctx storage
      (mapv #(assoc % :namespace "test.call-inputs")
            [{:name :left :parent :get
              :args {:coll {:as :left} :key {:value :value} :default nil}}
             {:name :right :parent :get
              :args {:coll {:as :right} :key {:value :value} :default nil}}
             {:name :bare :parent :get :args {:key {:value :value} :default nil}}
             {:name :pair :parent :list :args {:items [:left :right]}}
             {:name :bound-left :parent :pair :args {:left {:value {:value 3}}}}
             {:name :with-bare :parent :list :args {:items [:bare :left :right]}}]))
    (doseq [[fn-name args expected]
            [[:pair {:left {:value 7} :right {:value 9}} [7 9]]
             [:bound-left {:right {:value 9}} [3 9]]
             [:with-bare {:coll {:value 6} :left {:value 7} :right {:value 9}} [6 7 9]]]]
      (testing (str fn-name " accepts each independent input and delivers its own value")
        (let [{:keys [status body]} (execute-json fn-name args)]
          (is (= 200 status) body)
          (is (= "succeeded" (:status body)) body)
          (is (= expected (:result body))))))
    (testing "the unknown-argument guard still rejects an unrelated name"
      (let [{:keys [status body]} (execute-json :pair {:left {} :right {} :unrelated 1})]
        (is (= 400 status))
        (is (= ["unrelated"] (get-in body [:error-data :unknown])))))))
