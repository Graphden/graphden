(ns graphden.web.server.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web.server.interface :as web-server-fns]))


(deftest fn-defs-test
  (testing "fn-defs contains all expected definitions"
    (is (vector? web-server-fns/fn-defs))
    ;; 20 fns: handlers, route maps, route tuples, routes collection, router, server
    ;; hello (5) + health (5) + metrics (5) + routes collection (3) + router + server = 20
    (is (= 20 (count web-server-fns/fn-defs)))
    (let [names (set (map :name web-server-fns/fn-defs))]
      ;; Core fns
      (is (contains? names :web-server))
      (is (contains? names :router-fn))
      (is (contains? names :routes-fn))
      ;; Handlers
      (is (contains? names :hello-handler-fn))
      (is (contains? names :health-handler-fn))
      (is (contains? names :metrics-handler-fn))
      ;; Route building fns
      (is (contains? names :hello-route-fn))
      (is (contains? names :health-route-fn))
      (is (contains? names :metrics-route-fn))))

  (testing "web-server-fn has correct parent and args"
    (let [ws-def (first (filter #(= :web-server (:name %)) web-server-fns/fn-defs))]
      (is (= :http-server (:parent ws-def)))
      ;; handler uses :router-fn> (execute router and use result as handler)
      (is (= :router-fn> (get-in ws-def [:args :handler])))
      (is (= 8080 (get-in ws-def [:args :port])))))

  (testing "router-fn references routes-fn (via call-site)"
    (let [router-def (first (filter #(= :router-fn (:name %)) web-server-fns/fn-defs))]
      (is (= :router (:parent router-def)))
      (is (= :routes-fn> (get-in router-def [:args :routes])))))

  (testing "hello handler uses const base-fn"
    (let [hello-def (first (filter #(= :hello-handler-fn (:name %)) web-server-fns/fn-defs))]
      (is (= :const (:parent hello-def)))
      (is (= 200 (get-in hello-def [:args :x :status])))))

  (testing "health/metrics handlers use json-handler base-fn"
    (let [health-def (first (filter #(= :health-handler-fn (:name %)) web-server-fns/fn-defs))
          metrics-def (first (filter #(= :metrics-handler-fn (:name %)) web-server-fns/fn-defs))]
      ;; json-handler wraps data from another fn
      (is (= :json-handler (:parent health-def)))
      (is (= :json-handler (:parent metrics-def)))
      ;; health-status> and jvm-info> are the data sources
      (is (= :health-status> (get-in health-def [:args :data])))
      (is (= :jvm-info> (get-in metrics-def [:args :data]))))))


(deftest startup-fn-name-test
  (testing "startup fn name is web-server-fn"
    (is (= :web-server web-server-fns/startup-fn-name))))
