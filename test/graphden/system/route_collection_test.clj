(ns ^:serial graphden.system.route-collection-test
  "Unit tests for the route-collection seam (PLATFORM_PLAN §6) — the JVM-wide
   ORDERED COLLECTION of fall-through routers. `br/dispatch` calls
   `dispatch-first` on EVERY request, so the nil-safety contract here is what
   keeps single-tenant (no optional package / addon) a transparent
   pass-through. `^:serial` because it mutates the process-wide collection atom
   (isolated per-NS-thread only under the parallel plugin)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.system.route-collection :as rc]))


(use-fixtures :each
  (fn [f]
    (rc/remove-router! :a)
    (rc/remove-router! :b)
    (try (f)
         (finally (rc/remove-router! :a) (rc/remove-router! :b)))))


(deftest dispatch-first-nil-safe-with-empty-collection
  (testing "no routers installed → dispatch-first returns nil, never throws
            — this is what makes br/dispatch a transparent pass-through in
            single-tenant"
    (is (= {} (rc/current-collection)) "starts empty (fixture drained it)")
    (is (nil? (rc/dispatch-first {:uri "/anything" :request-method :get})))))


(deftest dispatch-first-invokes-installed-router-with-the-request
  (testing "a single installed router is called with the exact request; its
            response is returned"
    (let [seen (atom nil)
          router (fn [req] (reset! seen req) {:status 200 :body "served"})
          req {:uri "/partials/grants-admin" :request-method :get :headers {}}]
      (rc/install-router! :a router)
      (is (= {:status 200 :body "served"} (rc/dispatch-first req)))
      (is (= req @seen) "router received the exact request map"))))


(deftest dispatch-first-passes-through-a-nil-router-response
  (testing "an installed router returning nil (reitit no-match on a
            :router-or-nil) → dispatch-first returns nil so the caller falls
            through to the main app router"
    (rc/install-router! :a (fn [_req] nil))
    (is (nil? (rc/dispatch-first {:uri "/health"})))))


(deftest dispatch-first-returns-first-non-nil-across-routers
  (testing "with multiple routers, the FIRST non-nil response wins; a router
            that returns nil is skipped so a later one can serve"
    (let [a-hit (atom false)
          b-hit (atom false)]
      ;; :a matches nothing (returns nil), :b serves — proves fall-through
      ;; across the collection, not just a single slot.
      (rc/install-router! :a (fn [_req] (reset! a-hit true) nil))
      (rc/install-router! :b (fn [_req] (reset! b-hit true) {:status 201 :body "b"}))
      (is (= {:status 201 :body "b"} (rc/dispatch-first {:uri "/mcp"})))
      (is (true? @a-hit) ":a was consulted")
      (is (true? @b-hit) ":b served after :a returned nil"))))


(deftest install-remove-current-roundtrip
  (testing "install-router! / current-collection / remove-router!"
    (is (= {} (rc/current-collection)) "starts empty")
    (let [r (fn [_req] :served)]
      (rc/install-router! :a r)
      (is (identical? r (get (rc/current-collection) :a))
          "current-collection exposes the installed router by key")
      (is (= :served (rc/dispatch-first {:uri "/x"})) "dispatch-first routes to it")
      (rc/remove-router! :a)
      (is (= {} (rc/current-collection)) "removed back to empty")
      (is (nil? (rc/dispatch-first {:uri "/x"})) "no router → nil again"))))
