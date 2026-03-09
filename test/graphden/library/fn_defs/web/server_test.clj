(ns graphden.library.fn-defs.web.server-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.library.fn-defs.web.server :as web-server]))


(deftest fn-defs-test
  (testing "fn-defs contains all expected definitions"
    (is (vector? web-server/fn-defs))
    ;; 24 fns total:
    ;; - Building blocks: 3 (assoc-empty, assoc-handler, assoc-get)
    ;; - Hello: 4 (handler-fn, handler-map, method-map, route)
    ;; - Health: 7 (json-body, response, handler, handler-map, method-map, route)
    ;; - Metrics: 7 (same as health)
    ;; - Routes collection: 3 (routes-with-hello, routes-with-health, routes-fn)
    ;; - Router + Server: 2 (router-fn, web-server)
    (is (= 24 (count web-server/fn-defs)))
    (let [names (set (map :name web-server/fn-defs))]
      ;; Building blocks (multi-level inheritance)
      (is (contains? names :assoc-empty))
      (is (contains? names :assoc-handler))
      (is (contains? names :assoc-get))
      ;; Core fns
      (is (contains? names :web-server))
      (is (contains? names :router-fn))
      (is (contains? names :routes-fn))
      ;; Handlers
      (is (contains? names :hello-handler-fn))
      (is (contains? names :health-handler-fn))
      (is (contains? names :metrics-handler-fn))
      ;; Route definitions (using pair)
      (is (contains? names :hello-route))
      (is (contains? names :health-route))
      (is (contains? names :metrics-route))))

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
      (is (= :jvm-info (get-in metrics-json [:args :data])))))

  (testing "building blocks create multi-level inheritance"
    (let [assoc-empty (first (filter #(= :assoc-empty (:name %)) web-server/fn-defs))
          assoc-handler (first (filter #(= :assoc-handler (:name %)) web-server/fn-defs))
          assoc-get (first (filter #(= :assoc-get (:name %)) web-server/fn-defs))]
      ;; assoc-empty inherits from assoc-any with m={}
      (is (= :assoc-any (:parent assoc-empty)))
      (is (= {} (get-in assoc-empty [:args :m])))
      ;; assoc-handler inherits from assoc-empty with k="handler"
      (is (= :assoc-empty (:parent assoc-handler)))
      (is (= "handler" (get-in assoc-handler [:args :k])))
      ;; assoc-get inherits from assoc-empty with k="get"
      (is (= :assoc-empty (:parent assoc-get)))
      (is (= "get" (get-in assoc-get [:args :k])))))

  (testing "routes use pair for clean [path method-map] structure"
    (let [hello-route (first (filter #(= :hello-route (:name %)) web-server/fn-defs))
          health-route (first (filter #(= :health-route (:name %)) web-server/fn-defs))
          metrics-route (first (filter #(= :metrics-route (:name %)) web-server/fn-defs))]
      (is (= :pair (:parent hello-route)))
      (is (= "/" (get-in hello-route [:args :a])))
      (is (= :pair (:parent health-route)))
      (is (= "/health" (get-in health-route [:args :a])))
      (is (= :pair (:parent metrics-route)))
      (is (= "/metrics" (get-in metrics-route [:args :a]))))))


(deftest startup-fn-name-test
  (testing "startup fn name is web-server"
    (is (= :web-server web-server/startup-fn-name))))
