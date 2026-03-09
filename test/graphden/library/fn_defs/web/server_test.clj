(ns graphden.library.fn-defs.web.server-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.library.fn-defs.web.server :as web-server]))


(deftest fn-defs-test
  (testing "fn-defs contains all expected definitions"
    (is (vector? web-server/fn-defs))
    ;; 24 fns: handlers, route maps, route tuples, routes collection, router, server
    ;; hello (5) + health (7: json-body, response, handler, handler-map, method-map, route-path, route)
    ;; + metrics (7) + routes collection (3) + router + server = 24
    (is (= 24 (count web-server/fn-defs)))
    (let [names (set (map :name web-server/fn-defs))]
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
    (let [ws-def (first (filter #(= :web-server (:name %)) web-server/fn-defs))]
      (is (= :http-server (:parent ws-def)))
      ;; handler uses :router-fn (fn reference - behavior determined by is-fn on parent arg)
      (is (= :router-fn (get-in ws-def [:args :handler])))
      (is (= 8080 (get-in ws-def [:args :port])))))

  (testing "router-fn references routes-fn"
    (let [router-def (first (filter #(= :router-fn (:name %)) web-server/fn-defs))]
      (is (= :router (:parent router-def)))
      (is (= :routes-fn (get-in router-def [:args :routes])))))

  (testing "hello handler uses const base-fn"
    (let [hello-def (first (filter #(= :hello-handler-fn (:name %)) web-server/fn-defs))]
      (is (= :const (:parent hello-def)))
      (is (= 200 (get-in hello-def [:args :x :status])))))

  (testing "health/metrics handlers use compositional pattern"
    (let [health-def (first (filter #(= :health-handler-fn (:name %)) web-server/fn-defs))
          metrics-def (first (filter #(= :metrics-handler-fn (:name %)) web-server/fn-defs))]
      ;; Handlers use make-handler base-fn (compositional pattern)
      (is (= :make-handler (:parent health-def)))
      (is (= :make-handler (:parent metrics-def)))
      ;; response arg references the response-fn (which wraps json body)
      (is (= :health-response-fn (get-in health-def [:args :response])))
      (is (= :metrics-response-fn (get-in metrics-def [:args :response])))))

  (testing "json body fns use to-json-string with data sources"
    (let [health-json (first (filter #(= :health-json-body-fn (:name %)) web-server/fn-defs))
          metrics-json (first (filter #(= :metrics-json-body-fn (:name %)) web-server/fn-defs))]
      (is (= :to-json-string (:parent health-json)))
      (is (= :to-json-string (:parent metrics-json)))
      ;; Data sources
      (is (= :health-status (get-in health-json [:args :data])))
      (is (= :jvm-info (get-in metrics-json [:args :data]))))))


(deftest startup-fn-name-test
  (testing "startup fn name is web-server"
    (is (= :web-server web-server/startup-fn-name))))
