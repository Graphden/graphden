(ns graphden.reitit-fns.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.reitit-fns.interface :as reitit-fns]))


(deftest all-defs-test
  (testing "returns reitit function definitions"
    (is (map? reitit-fns/all-defs))
    (is (contains? reitit-fns/all-defs :reitit-matcher)))

  (testing "reitit-matcher has correct metadata"
    (let [matcher-def (get reitit-fns/all-defs :reitit-matcher)]
      (is (map? matcher-def))
      (is (contains? (:args matcher-def) :routes))
      (is (= :jsonb (:routes (:args matcher-def))))
      (is (= :fn (:return-type matcher-def)))
      (is (fn? (:impl matcher-def))))))


(deftest matcher-test
  (testing "can create matcher and match routes"
    (let [matcher-def (get reitit-fns/all-defs :reitit-matcher)
          routes [["/" {:get {:handler :home}}]
                  ["/users" {:get {:handler :list-users}
                             :post {:handler :create-user}}]
                  ["/users/:id" {:get {:handler :get-user}}]]
          ;; Create matcher
          matcher ((:impl matcher-def) {:routes (delay routes)} nil)]

      (testing "matcher is a function"
        (is (fn? matcher)))

      (testing "matches GET /"
        (let [match (matcher {:method "GET" :uri "/"})]
          (is (= :home (:handler match)))
          (is (= {} (:path-params match)))
          (is (= :get (:method match)))))

      (testing "matches GET /users/:id with path params"
        (let [match (matcher {:method "GET" :uri "/users/123"})]
          (is (= :get-user (:handler match)))
          (is (= {:id "123"} (:path-params match)))))

      (testing "matches POST /users"
        (let [match (matcher {:method "POST" :uri "/users"})]
          (is (= :create-user (:handler match)))))

      (testing "accepts keyword method"
        (let [match (matcher {:method :get :uri "/"})]
          (is (= :home (:handler match)))))

      (testing "returns nil for non-matching method"
        (let [match (matcher {:method "DELETE" :uri "/users"})]
          (is (nil? match))))

      (testing "returns nil for non-matching path"
        (let [match (matcher {:method "GET" :uri "/not-found"})]
          (is (nil? match)))))))
