(ns graphden.web-server.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web-server.interface :as web-server]))


(deftest fn-defs-test
  (testing "fn-defs is a vector of 4 definitions"
    (is (vector? web-server/fn-defs))
    (is (= 4 (count web-server/fn-defs)))
    (is (= #{:hello-handler-fn :health-handler-fn :router-fn :web-server-fn}
           (set (map :name web-server/fn-defs)))))

  (testing "web-server-fn has correct parent and args"
    (let [ws-def (first (filter #(= :web-server-fn (:name %)) web-server/fn-defs))]
      (is (= :http-server (:parent ws-def)))
      (is (= :router-fn (get-in ws-def [:args :handler])))
      (is (= 8080 (get-in ws-def [:args :port])))))

  (testing "router-fn uses router with inline handlers"
    (let [router-def (first (filter #(= :router-fn (:name %)) web-server/fn-defs))
          routes (get-in router-def [:args :routes])]
      (is (= :router (:parent router-def)))
      (is (vector? routes))
      (is (= 2 (count routes)))
      ;; Handlers are inline in routes
      (is (= :hello-handler-fn (get-in routes [0 1 :get :handler])))
      (is (= :health-handler-fn (get-in routes [1 1 :get :handler])))))

  (testing "handlers use constantly base-fn"
    (let [hello-def (first (filter #(= :hello-handler-fn (:name %)) web-server/fn-defs))
          health-def (first (filter #(= :health-handler-fn (:name %)) web-server/fn-defs))]
      (is (= :constantly (:parent hello-def)))
      (is (= :constantly (:parent health-def)))
      (is (= 200 (get-in hello-def [:args :x :status])))
      (is (= 200 (get-in health-def [:args :x :status]))))))


(deftest startup-fn-name-test
  (testing "startup fn name is web-server-fn"
    (is (= :web-server-fn web-server/startup-fn-name))))
