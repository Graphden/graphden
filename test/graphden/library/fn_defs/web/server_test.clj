(ns graphden.library.fn-defs.web.server-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.library.fn-defs.web.server :as web-server]))


(deftest fn-defs-test
  (testing "fn-defs contains all expected definitions"
    (is (vector? web-server/fn-defs))
    ;; 34 fns total:
    ;; - Route building blocks: 4 (assoc-empty, assoc-handler, method-map, route)
    ;; - HTTP method routes: 5 (get, post, put, delete, patch)
    ;; - Response status hierarchy: 3 (ok-response, not-found-response, error-response)
    ;; - Content-type responses: 6 (json-ok, html-ok, text-ok, text-not-found, text-error)
    ;; - Hello: 4 (body, response-fn, handler-fn, route)
    ;; - Health: 4 (json-body, response, handler, route)
    ;; - Metrics: 4 (json-body, response, handler, route)
    ;; - Routes collection: 3 (routes-with-hello, routes-with-health, routes-fn)
    ;; - Health status composition: 4 (assoc-status, assoc-timestamp, health-status-base, health-status)
    ;; - Router + Server: 2 (router-fn, web-server)
    (is (= 38 (count web-server/fn-defs)))
    (let [names (set (map :name web-server/fn-defs))]
      ;; Building blocks
      (is (contains? names :assoc-empty))
      (is (contains? names :assoc-handler))
      (is (contains? names :method-map))
      (is (contains? names :route))
      ;; Layer 1: HTTP method routes (inherit from :route fn-def)
      (is (contains? names :get-route))
      (is (contains? names :post-route))
      (is (contains? names :put-route))
      (is (contains? names :delete-route))
      (is (contains? names :patch-route))
      ;; Response building blocks
      (is (contains? names :ok-response))
      (is (contains? names :json-ok-response))
      ;; Core fns
      (is (contains? names :web-server))
      (is (contains? names :router-fn))
      (is (contains? names :routes-fn))
      ;; Handlers
      (is (contains? names :hello-handler-fn))
      (is (contains? names :health-handler-fn))
      (is (contains? names :metrics-handler-fn))
      ;; Route definitions (using pass-through args pattern)
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

  (testing "hello handler uses compositional pattern"
    (let [hello-body (first (filter #(= :hello-body (:name %)) web-server/fn-defs))
          hello-response (first (filter #(= :hello-response-fn (:name %)) web-server/fn-defs))
          hello-handler (first (filter #(= :hello-handler-fn (:name %)) web-server/fn-defs))]
      ;; hello-body is a const with HTML content
      (is (= :const (:parent hello-body)))
      (is (string? (get-in hello-body [:args :x])))
      ;; hello-response-fn inherits from html-ok-response
      (is (= :html-ok-response (:parent hello-response)))
      (is (= :hello-body (get-in hello-response [:args :body])))
      ;; hello-handler-fn uses make-handler with response
      (is (= :make-handler (:parent hello-handler)))
      (is (= :hello-response-fn (get-in hello-handler [:args :response])))))

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

  (testing "building blocks create route composition hierarchy"
    (let [assoc-empty (first (filter #(= :assoc-empty (:name %)) web-server/fn-defs))
          assoc-handler (first (filter #(= :assoc-handler (:name %)) web-server/fn-defs))
          method-map (first (filter #(= :method-map (:name %)) web-server/fn-defs))
          route-def (first (filter #(= :route (:name %)) web-server/fn-defs))]
      ;; assoc-empty: assoc-any with m={}
      (is (= :assoc-any (:parent assoc-empty)))
      (is (= {} (get-in assoc-empty [:args :m])))
      ;; assoc-handler: assoc-empty with k="handler"
      (is (= :assoc-empty (:parent assoc-handler)))
      (is (= "handler" (get-in assoc-handler [:args :k])))
      ;; method-map: assoc-empty with v=:assoc-handler (pass-through!)
      (is (= :assoc-empty (:parent method-map)))
      (is (= :assoc-handler (get-in method-map [:args :v])))
      ;; route: pair with b=:method-map (pass-through!)
      (is (= :pair (:parent route-def)))
      (is (= :method-map (get-in route-def [:args :b])))))

  (testing "HTTP method routes inherit from route fn-def"
    (let [get-route (first (filter #(= :get-route (:name %)) web-server/fn-defs))
          post-route (first (filter #(= :post-route (:name %)) web-server/fn-defs))
          delete-route (first (filter #(= :delete-route (:name %)) web-server/fn-defs))]
      ;; All HTTP method routes inherit from :route fn-def (not base-fn!)
      (is (= :route (:parent get-route)))
      (is (= :route (:parent post-route)))
      (is (= :route (:parent delete-route)))
      ;; Each sets k arg (HTTP method) via pass-through
      (is (= "get" (get-in get-route [:args :k])))
      (is (= "post" (get-in post-route [:args :k])))
      (is (= "delete" (get-in delete-route [:args :k])))))

  (testing "response building blocks create reusable abstractions"
    (let [ok-response (first (filter #(= :ok-response (:name %)) web-server/fn-defs))
          json-ok-response (first (filter #(= :json-ok-response (:name %)) web-server/fn-defs))]
      ;; ok-response: ring-response with status=200
      (is (= :ring-response (:parent ok-response)))
      (is (= 200 (get-in ok-response [:args :status])))
      ;; json-ok-response: ok-response with JSON headers
      (is (= :ok-response (:parent json-ok-response)))
      (is (= {"Content-Type" "application/json"} (get-in json-ok-response [:args :headers])))))

  (testing "entity routes use pass-through args pattern"
    (let [hello-route (first (filter #(= :hello-route (:name %)) web-server/fn-defs))
          health-route (first (filter #(= :health-route (:name %)) web-server/fn-defs))
          metrics-route (first (filter #(= :metrics-route (:name %)) web-server/fn-defs))]
      ;; All entity routes inherit from :get-route
      (is (= :get-route (:parent hello-route)))
      (is (= :get-route (:parent health-route)))
      (is (= :get-route (:parent metrics-route)))
      ;; Each sets a (path) and v (handler) via pass-through args
      ;; a is the path from pair
      ;; v is the handler from assoc-handler (via method-map -> route -> get-route)
      (is (= "/" (get-in hello-route [:args :a])))
      (is (= :hello-handler-fn (get-in hello-route [:args :v])))
      (is (= "/health" (get-in health-route [:args :a])))
      (is (= :health-handler-fn (get-in health-route [:args :v])))
      (is (= "/metrics" (get-in metrics-route [:args :a])))
      (is (= :metrics-handler-fn (get-in metrics-route [:args :v]))))))


(deftest startup-fn-name-test
  (testing "startup fn name is web-server"
    (is (= :web-server web-server/startup-fn-name))))
