(ns graphden.library.base-fns.core.system-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.library.base-fns.core.system :as sys]))


;; =============================================================================
;; Helper
;; =============================================================================

(defn- call-impl
  "Helper to call a defbase impl function with delays."
  [def-map arg-map]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays nil)))


;; NOTE: json-response and json-handler tests removed.
;; These functions were refactored to use primitives:
;; - ring-response for response construction
;; - make-handler for handler creation
;; - to-json-string for JSON serialization


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
    (is (contains? sys/system-defs :ring-response))
    (is (contains? sys/system-defs :make-handler))
    (is (contains? sys/system-defs :to-json-string))
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

(deftest ring-response-metadata-test
  (testing "has correct metadata"
    (is (= {:status :int :headers :jsonb :body :text} (:args sys/ring-response)))
    (is (= :jsonb (:return-type sys/ring-response)))))


(deftest make-handler-metadata-test
  (testing "has correct metadata"
    (is (= {:response :jsonb} (:args sys/make-handler)))
    (is (= :fn (:return-type sys/make-handler)))))


(deftest to-json-string-metadata-test
  (testing "has correct metadata"
    (is (= {:data :any} (:args sys/to-json-string)))
    (is (= :text (:return-type sys/to-json-string)))))


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
