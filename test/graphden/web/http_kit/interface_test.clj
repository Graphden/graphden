(ns graphden.web.http-kit.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web.http-kit.interface :as http-kit-fns]
    [org.httpkit.client :as http-client]))


(deftest get-all-defs-test
  (testing "returns http-kit function definitions"
    (let [defs (http-kit-fns/get-all-defs)]
      (is (map? defs))
      (is (contains? defs :http-server))
      (is (contains? defs :http-stop))))

  (testing "http-server has correct metadata"
    (let [http-server-def (get (http-kit-fns/get-all-defs) :http-server)]
      (is (map? http-server-def))
      (is (contains? (:args http-server-def) :handler))
      (is (contains? (:args http-server-def) :port))
      (is (= :any (:handler (:args http-server-def))))
      (is (= :int (:port (:args http-server-def))))
      (is (= :any (:return-type http-server-def)))
      (is (fn? (:impl http-server-def)))))

  (testing "http-stop has correct metadata"
    (let [http-stop-def (get (http-kit-fns/get-all-defs) :http-stop)]
      (is (map? http-stop-def))
      (is (contains? (:args http-stop-def) :server))
      (is (= :any (:server (:args http-stop-def))))
      (is (= :any (:return-type http-stop-def)))
      (is (fn? (:impl http-stop-def))))))


;; === Integration tests for actual server execution ===

(defn- call-impl
  "Helper to call a defbase impl function with delays."
  [def-map arg-map]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays nil)))


(deftest http-server-execution-test
  (testing "http-server starts and responds to requests"
    (let [test-port 18080
          handler (fn [_req]
                    {:status 200
                     :headers {"Content-Type" "text/plain"}
                     :body "Hello Test"})
          http-server-def (get (http-kit-fns/get-all-defs) :http-server)
          server (call-impl http-server-def {:handler handler :port test-port})]
      (try
        ;; Server should be a function (http-kit returns a stop-fn)
        (is (fn? server))

        ;; Make HTTP request to the server
        (let [response @(http-client/get (str "http://localhost:" test-port "/test"))]
          (is (= 200 (:status response)))
          (is (= "Hello Test" (:body response))))

        (finally
          ;; Stop the server
          (when server (server))))))

  (testing "http-server handles request with body"
    (let [test-port 18081
          received-request (atom nil)
          handler (fn [req]
                    (reset! received-request req)
                    {:status 201
                     :headers {"X-Custom" "header"}
                     :body "Created"})
          http-server-def (get (http-kit-fns/get-all-defs) :http-server)
          server (call-impl http-server-def {:handler handler :port test-port})]
      (try
        ;; Make POST request
        (let [response @(http-client/post (str "http://localhost:" test-port "/create")
                                          {:body "request body"
                                           :headers {"Content-Type" "text/plain"}})]
          (is (= 201 (:status response)))
          (is (= "Created" (:body response)))
          ;; Check request was received correctly
          (is (= "/create" (:uri @received-request)))
          (is (= "post" (:method @received-request))))

        (finally
          (when server (server))))))

  (testing "http-server provides default response values"
    (let [test-port 18082
          handler (fn [_req] {})  ; Empty response
          http-server-def (get (http-kit-fns/get-all-defs) :http-server)
          server (call-impl http-server-def {:handler handler :port test-port})]
      (try
        (let [response @(http-client/get (str "http://localhost:" test-port "/empty")
                                         {:as :text})]
          ;; Should have default status 200
          (is (= 200 (:status response)))
          ;; Body should be empty string
          (is (= "" (:body response))))

        (finally
          (when server (server)))))))


(deftest http-stop-execution-test
  (testing "http-stop stops a running server"
    (let [test-port 18083
          handler (fn [_req] {:status 200 :body "Running"})
          http-server-def (get (http-kit-fns/get-all-defs) :http-server)
          http-stop-def (get (http-kit-fns/get-all-defs) :http-stop)
          server (call-impl http-server-def {:handler handler :port test-port})]

      ;; Server should respond before stop
      (let [response @(http-client/get (str "http://localhost:" test-port "/"))]
        (is (= 200 (:status response))))

      ;; Stop server
      (let [result (call-impl http-stop-def {:server server})]
        (is (nil? result)))

      ;; Give server time to shutdown
      (Thread/sleep 100)

      ;; Server should not respond after stop
      (let [response @(http-client/get (str "http://localhost:" test-port "/")
                                       {:timeout 500})]
        (is (some? (:error response))))))

  (testing "http-stop handles nil server gracefully"
    (let [http-stop-def (get (http-kit-fns/get-all-defs) :http-stop)
          result (call-impl http-stop-def {:server nil})]
      (is (nil? result)))))
