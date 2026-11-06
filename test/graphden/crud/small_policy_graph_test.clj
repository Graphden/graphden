(ns ^:integration ^:serial graphden.crud.small-policy-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.request :as request]
    [graphden.executor.test-setup :as setup]
    [graphden.services.endpoint :as endpoint]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.graph-harness :as gh]))


(use-fixtures :once (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(deftest text-policies-preserve-boundaries
  (doseq [[s limit expected] [["abcdef" 3 "abc"] ["ab" 4 "ab"] ["ab" 0 ""]
                              ["😀" 1 (String/.substring "😀" 0 1)]]]
    (is (= expected (gh/exec-name :str-clip {:string s :limit limit}))))
  (doseq [[s expected] [[nil false] ["" false] [" \t" false] [" x " true]]]
    (is (= expected (gh/exec-name :non-blank? {:string s}))))
  (doseq [[s width pad left right] [["x" 6 "ab" "ababax" "xababa"]
                                    ["long" 2 "0" "long" "long"]
                                    ["x" 3 "" "  x" "x  "]
                                    ["x" 3 "\t" "  x" "x  "]
                                    ["" 0 "0" "" ""]]]
    (is (= left (gh/exec-name :str-pad-left {:string s :length width :pad pad})))
    (is (= right (gh/exec-name :str-pad-right {:string s :length width :pad pad}))))
  (is (= ["a" "b"] (vec (gh/exec-name :_uri-filtered-segments {:request {:uri "/a//b/"}}))))
  (is (= "  x" (gh/exec-name :str-pad-left {:string "x" :length 3}))))


(deftest http-lookup-and-encoding-policy
  (doseq [[headers key expected] [[nil "X-Test" nil] [{"x-test" "lower" "X-Test" "exact"} "X-Test" "lower"]
                                  [{"x-test" nil "X-Test" "exact"} "X-Test" "exact"]
                                  [{"X-Test" "exact"} "X-Test" "exact"]
                                  [{"X-TEST" "other"} "X-Test" nil]]]
    (is (= expected (gh/exec-name :header-get {:headers headers :header-name key}))))
  (doseq [[header expected] [[nil "identity"] ["BR" "br"] ["Br; q=1.0" "br"]
                             ["GZIP, deflate" "gzip"] ["br;q=0, gzip" "gzip"]
                             ["br;q=0.00, gzip;q=0" "identity"] ["*" "identity"]
                             ["br;q=.5, gzip;q=1" "br"] ["br;q=0, br;q=1" "br"]]]
    (is (= expected (gh/exec-name :pick-encoding {:headers {"accept-encoding" header}})))))


(defn- query-storage
  [query]
  (reify sp/StorageCRUD
    (query-entities [_ entity where] (query entity where))

    (query-entities [_ _ _ _] (throw (ex-info "Unexpected paged read" {})))

    (create-entity [_ _ _] (throw (ex-info "Unexpected write" {})))

    (read-entity [_ _ _] (throw (ex-info "Unexpected read" {})))

    (update-entity [_ _ _ _] (throw (ex-info "Unexpected write" {})))

    (delete-entity [_ _ _] (throw (ex-info "Unexpected write" {})))

    (query-latest-per-group [_ _ _ _] (throw (ex-info "Unexpected grouped read" {})))))


(deftest preferences-read-once-and-project-current-owner-rows
  (let [reads (atom [])
        storage (query-storage (fn [entity where]
                                 (swap! reads conj [entity where])
                                 [{:key "theme" :value "dark" :owner-id "owner"}
                                  {:key "layout" :value nil}
                                  {:key "theme" :value "light"}]))]
    (with-redefs [request/require-storage (constantly storage)]
      (binding [tenancy/*current-principal* {:authenticated? true :user-id "owner"}]
        (is (= {"theme" "light" "layout" nil} (gh/exec-name :ui-prefs-read {})))))
    (is (= [[:ui-pref {:owner-id "owner"}]] @reads))))


(deftest live-endpoint-policy-uses-one-inert-snapshot
  (let [calls (atom 0)
        facts (atom {:now-ms 100000 :stale-after-ms 45000 :self-executor-id "local"
                     :instances [{:host "remote" :port 9001 :executor-id "remote" :seen-at-ms 99999}
                                 {:host "local" :port 9002 :executor-id "local" :seen-at-ms 55000}
                                 {:host "stale" :port 9003 :executor-id "local" :seen-at-ms 54999}
                                 {:host "cron" :port nil :executor-id "local" :seen-at-ms 100000}]})]
    (with-redefs [endpoint/instance-facts (fn [_ rows _]
                                            (is (= [{:id :fixture}] rows))
                                            (swap! calls inc) @facts)]
      (is (= {:host "local" :port 9002 :url "http://local:9002"}
             (gh/exec-name :_live-service-endpoint {:rows [{:id :fixture}]})))
      (is (= 1 @calls))
      (swap! facts assoc :self-executor-id "none")
      (is (= "remote" (:host (gh/exec-name :_live-service-endpoint {:rows [{:id :fixture}]}))))
      (swap! facts update :instances conj {:host nil :port 9004 :executor-id "none" :seen-at-ms 100000})
      (is (nil? (gh/exec-name :_live-service-endpoint {:rows [{:id :fixture}]})))
      (swap! facts assoc :instances [])
      (is (nil? (gh/exec-name :_live-service-endpoint {:rows [{:id :fixture}]}))))))


(deftest instance-facts-batches-only-requested-services-and-decodes-heartbeat
  (let [service-id (random-uuid)
        reads (atom [])
        before (System/currentTimeMillis)
        storage (query-storage (fn [entity where]
                                 (swap! reads conj [entity where])
                                 [{:host "host" :port 9000 :executor-id "copy"
                                   :seen-at (java.util.Date. 1234)}]))
        facts (endpoint/instance-facts storage [{:id service-id}] "self")]
    (is (= [[:service-instance {:service-id [service-id]}]] @reads))
    (is (= [{:host "host" :port 9000 :executor-id "copy" :seen-at-ms 1234}] (:instances facts)))
    (is (= "self" (:self-executor-id facts)))
    (is (<= before (:now-ms facts) (System/currentTimeMillis)))
    (reset! reads [])
    (is (empty? (:instances (endpoint/instance-facts storage [] "self"))))
    (is (empty? @reads))))
