(ns ^:integration ^:serial graphden.system.sse-introspection-test
  "The relay-introspection seam (`org-connection-count`) the tenancy
   executor-admin panel reads via requiring-resolve. `^:serial` because the
   seam is a process-global pointer to the ACTIVE relay — a concurrently
   starting relay in another NS would overwrite it mid-assert."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.system.sse :as sse]))


(deftest org-connection-count-reflects-the-active-relay
  (testing "no relay in this process → nil (distinct from 0)"
    (is (nil? (sse/org-connection-count "acme"))))
  (let [relay (sse/start-relay! {:port 0})]
    (try
      (testing "relay up, nobody connected → 0"
        (is (zero? (sse/org-connection-count "acme"))))
      (testing "counts THIS pod's subscribers per org"
        (swap! (:subscribers relay) assoc :ch1 "acme" :ch2 "acme" :ch3 "beta")
        (is (= 2 (sse/org-connection-count "acme")))
        (is (= 1 (sse/org-connection-count "beta")))
        (is (zero? (sse/org-connection-count "ghost"))))
      (finally (sse/stop-relay! relay))))
  (testing "stop clears only its own registration → nil again"
    (is (nil? (sse/org-connection-count "acme")))))
