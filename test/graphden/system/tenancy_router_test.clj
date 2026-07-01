(ns ^:serial graphden.system.tenancy-router-test
  "Unit tests for the tenancy control-plane routing singleton — the runtime
   half of the route-collection seam (PLATFORM_PLAN §6). `br/dispatch` consults
   `dispatch (current-router) request` on EVERY request, so the nil-safety
   contract here is what keeps single-tenant (no addon) a transparent
   pass-through. `^:serial` because it mutates the process-wide `active-router`
   atom (not in the parallel isolation-vars)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.system.tenancy-router :as tr]))


(use-fixtures :each
  (fn [f]
    (tr/clear-active-router!)
    (try (f) (finally (tr/clear-active-router!)))))


(deftest dispatch-is-nil-safe-with-no-router
  (testing "nil router (no addon installed) → dispatch returns nil, never throws
            — this is what makes br/dispatch a transparent pass-through in
            single-tenant"
    (is (nil? (tr/dispatch nil {:uri "/anything" :request-method :get})))))


(deftest dispatch-invokes-the-installed-router-with-the-request
  (testing "a non-nil router is called with the exact request; its response is returned"
    (let [seen (atom nil)
          router (fn [req] (reset! seen req) {:status 200 :body "served"})
          req {:uri "/partials/grants-admin" :request-method :get :headers {}}]
      (is (= {:status 200 :body "served"} (tr/dispatch router req)))
      (is (= req @seen) "router received the exact request map"))))


(deftest dispatch-passes-through-a-nil-router-response
  (testing "router returning nil (reitit no-match on a :router-or-nil) → dispatch
            returns nil so the wrap falls through to the main app router"
    (is (nil? (tr/dispatch (fn [_req] nil) {:uri "/health"})))))


(deftest active-router-set-current-clear-roundtrip
  (testing "set-active-router! / current-router / clear-active-router!"
    (is (nil? (tr/current-router)) "starts nil (fixture cleared it)")
    (let [r (fn [_req] :served)]
      (tr/set-active-router! r)
      (is (identical? r (tr/current-router)) "current-router returns the installed router")
      (is (= :served (tr/dispatch (tr/current-router) {:uri "/x"})) "dispatch via current-router")
      (tr/clear-active-router!)
      (is (nil? (tr/current-router)) "cleared back to nil"))))
