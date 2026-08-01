(ns graphden.packages.app.monitoring-routes-test
  "Regression sentinel: the monitoring endpoints' auth posture.

   /metrics + /metrics/prometheus disclose recon material (JVM version,
   CPU count, live load-average, restart windows, the 5xx counter) and
   are AUTH-REQUIRED when auth is active; /health is const-only and
   stays public for uptime checkers. A quiet flip of the route template
   back to :get-route would re-expose the metrics on every self-hosted
   instance — this pins the posture structurally."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]))


(deftest metrics-routes-are-auth-required
  (let [defs (into {}
                   (keep (fn [fd] (when (:name fd) [(:name fd) fd])))
                   (:fn-defs (loader/load-packages ["app"])))]
    (testing "metrics endpoints ride the auth-required template"
      (is (= :get-auth-required (:parent (get defs :metrics))))
      (is (= :get-auth-required (:parent (get defs :metrics-prometheus)))))
    (testing "/health stays public (const-only; uptime checkers depend on it)"
      (is (= :get-route (:parent (get defs :health)))))))
