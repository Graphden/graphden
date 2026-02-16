(ns graphden.web.reitit.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web.reitit.interface :as reitit-fns]))


(deftest all-defs-test
  (testing "returns reitit function definitions"
    (is (map? reitit-fns/all-defs))
    (is (contains? reitit-fns/all-defs :router)))

  (testing "router has correct metadata"
    (let [router-def (get reitit-fns/all-defs :router)]
      (is (map? router-def))
      (is (contains? (:args router-def) :routes))
      (is (= :jsonb (:routes (:args router-def))))
      (is (= :fn (:return-type router-def)))
      (is (fn? (:impl router-def))))))


(deftest router-test
  (testing "can create router and handle requests"
    (let [router-def (get reitit-fns/all-defs :router)
          ;; Handler functions inline in routes
          home-handler (fn [_req] {:status 200 :body "home"})
          get-user-handler (fn [req] {:status 200 :body (str "user:" (get-in req [:path-params :id]))})
          create-handler (fn [_req] {:status 201 :body "created"})
          routes [["/" {:get {:handler home-handler}}]
                  ["/users" {:post {:handler create-handler}}]
                  ["/users/:id" {:get {:handler get-user-handler}}]]
          ;; Create router (handlers are in routes, wrapped in delay)
          router ((:impl router-def) {:routes (delay routes)} nil)]

      (testing "router is a function"
        (is (fn? router)))

      (testing "handles GET /"
        (let [response (router {:method "GET" :uri "/"})]
          (is (= 200 (:status response)))
          (is (= "home" (:body response)))))

      (testing "handles GET /users/:id with path params"
        (let [response (router {:method "GET" :uri "/users/123"})]
          (is (= 200 (:status response)))
          (is (= "user:123" (:body response)))))

      (testing "handles POST /users"
        (let [response (router {:method "POST" :uri "/users"})]
          (is (= 201 (:status response)))
          (is (= "created" (:body response)))))

      (testing "accepts keyword method"
        (let [response (router {:method :get :uri "/"})]
          (is (= 200 (:status response)))))

      (testing "returns 405 for non-matching method"
        (let [response (router {:method "DELETE" :uri "/users"})]
          (is (= 405 (:status response)))))

      (testing "returns 404 for non-matching path"
        (let [response (router {:method "GET" :uri "/not-found"})]
          (is (= 404 (:status response))))))))
