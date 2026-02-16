(ns graphden.executor.base-fns.system-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.base-fns.system :as sys]))


;; =============================================================================
;; Helper
;; =============================================================================

(defn- call-impl
  "Helper to call a defbase impl function with delays."
  [def-map arg-map]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays nil)))


;; =============================================================================
;; json-response tests
;; =============================================================================

(deftest json-response-test
  (testing "wraps data in JSON response"
    (let [response (call-impl sys/json-response {:data {:foo "bar"}})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response)))
      (is (= {:foo "bar"} (json/parse-string (:body response) true)))))

  (testing "handles various data types"
    (let [r1 (call-impl sys/json-response {:data [1 2 3]})
          r2 (call-impl sys/json-response {:data "string"})
          r3 (call-impl sys/json-response {:data 42})
          r4 (call-impl sys/json-response {:data nil})]
      (is (= [1 2 3] (json/parse-string (:body r1))))
      (is (= "string" (json/parse-string (:body r2))))
      (is (= 42 (json/parse-string (:body r3))))
      (is (nil? (json/parse-string (:body r4)))))))


;; =============================================================================
;; json-handler tests
;; =============================================================================

(deftest json-handler-test
  (testing "creates handler that returns JSON response"
    (let [handler (call-impl sys/json-handler {:data {:status "ok"}})]
      (is (fn? handler))
      (let [response (handler {:method :get :uri "/"})]
        (is (= 200 (:status response)))
        (is (= "application/json" (get-in response [:headers "Content-Type"])))
        (is (= {:status "ok"} (json/parse-string (:body response) true))))))

  (testing "handler ignores request"
    (let [handler (call-impl sys/json-handler {:data "static"})]
      (is (= (handler {:a 1}) (handler {:b 2}))))))


;; =============================================================================
;; jvm-info tests
;; =============================================================================

(deftest jvm-info-test
  (testing "returns JVM information map"
    (let [info (call-impl sys/jvm-info {})]
      (is (map? info))
      (is (contains? info :jvm))
      (is (contains? info :memory))
      (is (contains? info :threads))
      (is (contains? info :os))))

  (testing "jvm section has expected fields"
    (let [jvm (:jvm (call-impl sys/jvm-info {}))]
      (is (string? (:name jvm)))
      (is (string? (:version jvm)))
      (is (number? (:uptime-ms jvm)))
      (is (pos? (:uptime-ms jvm)))))

  (testing "memory section has expected fields"
    (let [mem (:memory (call-impl sys/jvm-info {}))]
      (is (number? (:heap-used mem)))
      (is (number? (:heap-max mem)))
      (is (number? (:heap-committed mem)))
      (is (number? (:free mem)))
      (is (number? (:total mem)))
      (is (number? (:max mem)))
      (is (pos? (:heap-used mem)))))

  (testing "threads section has expected fields"
    (let [threads (:threads (call-impl sys/jvm-info {}))]
      (is (number? (:count threads)))
      (is (pos? (:count threads)))))

  (testing "os section has expected fields"
    (let [os (:os (call-impl sys/jvm-info {}))]
      (is (string? (:name os)))
      (is (string? (:arch os)))
      (is (number? (:processors os)))
      (is (pos? (:processors os)))
      (is (number? (:load-average os))))))


;; =============================================================================
;; current-time-ms tests
;; =============================================================================

(deftest current-time-ms-test
  (testing "returns current time in milliseconds"
    (let [before (System/currentTimeMillis)
          result (call-impl sys/current-time-ms {})
          after (System/currentTimeMillis)]
      (is (number? result))
      (is (>= result before))
      (is (<= result after))))

  (testing "increases over time"
    (let [t1 (call-impl sys/current-time-ms {})
          _ (Thread/sleep 10)
          t2 (call-impl sys/current-time-ms {})]
      (is (> t2 t1)))))


;; =============================================================================
;; health-status tests
;; =============================================================================

(deftest health-status-test
  (testing "returns health status map"
    (let [status (call-impl sys/health-status {})]
      (is (map? status))
      (is (= "healthy" (:status status)))
      (is (number? (:timestamp status)))))

  (testing "timestamp is current"
    (let [before (System/currentTimeMillis)
          status (call-impl sys/health-status {})
          after (System/currentTimeMillis)]
      (is (>= (:timestamp status) before))
      (is (<= (:timestamp status) after)))))


;; =============================================================================
;; system-defs tests
;; =============================================================================

(deftest system-defs-test
  (testing "contains all expected functions"
    (is (map? sys/system-defs))
    (is (contains? sys/system-defs :json-response))
    (is (contains? sys/system-defs :json-handler))
    (is (contains? sys/system-defs :jvm-info))
    (is (contains? sys/system-defs :current-time-ms))
    (is (contains? sys/system-defs :health-status)))

  (testing "all defs have correct structure"
    (doseq [[k v] sys/system-defs]
      (testing (str "def " k)
        (is (map? v) (str k " should be a map"))
        (is (contains? v :args) (str k " should have :args"))
        (is (contains? v :return-type) (str k " should have :return-type"))
        (is (contains? v :impl) (str k " should have :impl"))
        (is (fn? (:impl v)) (str k " :impl should be a function"))))))


;; =============================================================================
;; Metadata tests
;; =============================================================================

(deftest json-response-metadata-test
  (testing "has correct metadata"
    (is (= {:data :any} (:args sys/json-response)))
    (is (= :jsonb (:return-type sys/json-response)))))


(deftest json-handler-metadata-test
  (testing "has correct metadata"
    (is (= {:data :any} (:args sys/json-handler)))
    (is (= :fn (:return-type sys/json-handler)))))


(deftest jvm-info-metadata-test
  (testing "has correct metadata"
    (is (= {} (:args sys/jvm-info)))
    (is (= :jsonb (:return-type sys/jvm-info)))))


(deftest current-time-ms-metadata-test
  (testing "has correct metadata"
    (is (= {} (:args sys/current-time-ms)))
    (is (= :int (:return-type sys/current-time-ms)))))


(deftest health-status-metadata-test
  (testing "has correct metadata"
    (is (= {} (:args sys/health-status)))
    (is (= :jsonb (:return-type sys/health-status)))))
