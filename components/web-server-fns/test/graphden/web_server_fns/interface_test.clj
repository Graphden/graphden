(ns graphden.web-server-fns.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web-server-fns.interface :as web-server-fns]))


(deftest fn-defs-test
  (testing "fn-defs contains all expected definitions"
    (is (vector? web-server-fns/fn-defs))
    ;; 14 fns: handlers, route maps, route tuples, routes collection, router, server
    (is (= 14 (count web-server-fns/fn-defs)))
    (let [names (set (map :name web-server-fns/fn-defs))]
      ;; Core fns
      (is (contains? names :web-server-fn))
      (is (contains? names :router-fn))
      (is (contains? names :routes-fn))
      ;; Handlers
      (is (contains? names :hello-handler-fn))
      (is (contains? names :health-handler-fn))
      ;; Route building fns
      (is (contains? names :hello-route-fn))
      (is (contains? names :health-route-fn))))

  (testing "web-server-fn has correct parent and args"
    (let [ws-def (first (filter #(= :web-server-fn (:name %)) web-server-fns/fn-defs))]
      (is (= :http-server (:parent ws-def)))
      ;; handler uses :router-fn> (execute router and use result as handler)
      (is (= :router-fn> (get-in ws-def [:args :handler])))
      (is (= 8080 (get-in ws-def [:args :port])))))

  (testing "router-fn references routes-fn (via fn-result-value)"
    (let [router-def (first (filter #(= :router-fn (:name %)) web-server-fns/fn-defs))]
      (is (= :router (:parent router-def)))
      (is (= :routes-fn> (get-in router-def [:args :routes])))))

  (testing "handlers use const base-fn"
    (let [hello-def (first (filter #(= :hello-handler-fn (:name %)) web-server-fns/fn-defs))
          health-def (first (filter #(= :health-handler-fn (:name %)) web-server-fns/fn-defs))]
      (is (= :const (:parent hello-def)))
      (is (= :const (:parent health-def)))
      (is (= 200 (get-in hello-def [:args :x :status])))
      (is (= 200 (get-in health-def [:args :x :status]))))))


(deftest startup-fn-name-test
  (testing "startup fn name is web-server-fn"
    (is (= :web-server-fn web-server-fns/startup-fn-name))))
