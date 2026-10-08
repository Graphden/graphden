(ns graphden.http-host.policy-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.http-host.policy :as policy]))


(deftest hosted-code-cannot-write-editor-origin-state
  (let [result (policy/response
                 {:status 200 :body "<script>document.cookie</script>"
                  :headers {"Content-Type" "text/plain"
                            "sEt-CoOkIe" "gd_session=attacker"
                            "Location" "/account"
                            "Access-Control-Allow-Origin" "*"}})]
    (is (= 200 (:status result)))
    (is (= policy/response-headers (:headers result)))
    (is (= "<script>document.cookie</script>" (:body result))))
  (doseq [resp [{:status 200 :headers {"content-type" "text/html"} :body "<h1>HTML</h1>"}
                {:status 302 :headers {"location" "/account"} :body ""}
                {:status 200 :body (java.io.StringReader. "stream")}
                {:status 200 :body (str/join (repeat policy/max-body-bytes "я"))}
                {:status 200 :headers {"content-type" "application/json"} :body "not json"}]]
    (is (= 502 (:status (policy/response resp))))))


(deftest json-and-http-request-remain-useful
  (testing "JSON remains JSON, including false and null"
    (doseq [body ["{\"answer\":42}" "false" "null"]]
      (is (= {:status 201
              :headers (assoc policy/response-headers "Content-Type" "application/json; charset=utf-8")
              :body body}
             (policy/response {:status 201 :headers {"Content-Type" "application/json"} :body body})))))
  (testing "method, path, query and body survive without credentials or transport handles"
    (is (= {:request-method :post :uri "/hello"
            :query-string "name=World" :body "payload"
            :async-channel nil
            :headers {"content-type" "text/plain"}}
           (policy/request {:request-method :post :query-string "name=World" :body "payload"
                            :async-channel :privileged :identity {:admin true}
                            :headers {"cookie" "gd_session=secret" "authorization" "Bearer secret"
                                      "x-graphden-branch" "other-branch" "content-type" "text/plain"}}
                           "/hello"))))
  (is (nil? (policy/request {:body (str/join (repeat (inc policy/max-body-bytes) "x"))} "/"))))


(deftest buffered-http-body-is-read-with-a-hard-byte-bound
  (let [body (java.io.ByteArrayInputStream. (String/.getBytes "payload" "UTF-8"))]
    (is (= "payload" (:body (policy/request {:body body} "/")))))
  (let [body (java.io.ByteArrayInputStream. (byte-array (+ policy/max-body-bytes 100)))]
    (is (nil? (policy/request {:body body} "/")))
    (is (= 99 (java.io.ByteArrayInputStream/.available body)) "The transport boundary reads only limit + 1 bytes")))
