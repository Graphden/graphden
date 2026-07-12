(ns graphden.fleet.discovery-test
  "Executor-set discovery (`graphden.fleet.discovery`, docs/FLEET_RFC.md §6.2).
   The pure parsers — env-list + SRV-target — are unit-tested; the JNDI resolve
   itself is a thin wrapper exercised in a real cluster."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.discovery :as discovery]))


(deftest parse-executor-list-trims-and-drops-blanks
  (is (= ["a" "b" "c"] (discovery/parse-executor-list "a,b,c")))
  (is (= ["a" "b"] (discovery/parse-executor-list " a , , b ")) "trims + drops empties")
  (testing "nil / blank / all-blank → nil so the caller falls through to DNS"
    (is (nil? (discovery/parse-executor-list nil)))
    (is (nil? (discovery/parse-executor-list "")))
    (is (nil? (discovery/parse-executor-list " , , ")))))


(deftest parse-srv-target-extracts-host
  (testing "the 4th field (host) is returned, trailing dot stripped"
    (is (= "graphden-0.graphden-headless.default.svc.cluster.local"
           (discovery/parse-srv-target
             "0 50 8080 graphden-0.graphden-headless.default.svc.cluster.local.")))
    (is (= "pod-1.hl" (discovery/parse-srv-target "10 5 8080 pod-1.hl"))
        "no trailing dot is fine too"))
  (testing "unparseable → nil"
    (is (nil? (discovery/parse-srv-target nil)))
    (is (nil? (discovery/parse-srv-target "")))))


(deftest fleet-executors-reads-without-throwing
  ;; With neither env var set (the test JVM), discovery yields an empty set
  ;; rather than crashing — the controller's "nowhere to place" safe default.
  (is (vector? (discovery/fleet-executors))))
