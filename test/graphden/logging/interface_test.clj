(ns graphden.logging.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.logging.interface :as log])
  (:import
    (org.slf4j
      MDC)))


;; =============================================================================
;; Correlation ID tests
;; =============================================================================

(deftest generate-correlation-id-test
  (testing "generates 8-character hex string"
    (let [id (log/generate-correlation-id)]
      (is (string? id))
      (is (= 8 (count id)))
      (is (re-matches #"[a-f0-9]{8}" id))))

  (testing "generates unique IDs"
    (let [ids (repeatedly 100 log/generate-correlation-id)]
      (is (= 100 (count (set ids)))))))


(deftest set-get-correlation-id-test
  (testing "sets and gets correlation ID"
    (try
      (log/set-correlation-id! "test1234")
      (is (= "test1234" (log/get-correlation-id)))
      (finally
        (log/clear-correlation-id!))))

  (testing "returns nil when not set"
    (log/clear-correlation-id!)
    (is (nil? (log/get-correlation-id)))))


(deftest clear-correlation-id-test
  (testing "clears correlation ID"
    (log/set-correlation-id! "to-clear")
    (is (= "to-clear" (log/get-correlation-id)))
    (log/clear-correlation-id!)
    (is (nil? (log/get-correlation-id)))))


(deftest with-correlation-id-test
  (testing "sets correlation ID within scope"
    (is (nil? (log/get-correlation-id)))
    (log/with-correlation-id "scoped-id"
                             (is (= "scoped-id" (log/get-correlation-id))))
    (is (nil? (log/get-correlation-id))))

  (testing "clears ID even on exception"
    (try
      (log/with-correlation-id "error-id"
                               (is (= "error-id" (log/get-correlation-id)))
                               (throw (ex-info "Test error" {})))
      (catch Exception _))
    (is (nil? (log/get-correlation-id))))

  (testing "returns body value"
    (is (= 42 (log/with-correlation-id "x" 42)))))


;; =============================================================================
;; Context helpers tests
;; =============================================================================

(deftest set-context-test
  (testing "sets multiple MDC values"
    (try
      (log/set-context! {:user-id "u123" :fn-name "my-fn"})
      (is (= "u123" (MDC/get "user-id")))
      (is (= "my-fn" (MDC/get "fn-name")))
      (finally
        (MDC/remove "user-id")
        (MDC/remove "fn-name"))))

  (testing "converts values to strings"
    (try
      (log/set-context! {:count 42 :flag true})
      (is (= "42" (MDC/get "count")))
      (is (= "true" (MDC/get "flag")))
      (finally
        (MDC/remove "count")
        (MDC/remove "flag")))))


(deftest clear-context-test
  (testing "clears specified keys"
    (MDC/put "key1" "val1")
    (MDC/put "key2" "val2")
    (log/clear-context! [:key1 :key2])
    (is (nil? (MDC/get "key1")))
    (is (nil? (MDC/get "key2")))))


(deftest with-context-test
  (testing "sets context within scope"
    (is (nil? (MDC/get "temp-key")))
    (log/with-context {:temp-key "temp-value"}
                      (is (= "temp-value" (MDC/get "temp-key"))))
    (is (nil? (MDC/get "temp-key"))))

  (testing "clears context even on exception"
    (try
      (log/with-context {:error-key "error-val"}
                        (is (= "error-val" (MDC/get "error-key")))
                        (throw (ex-info "Test" {})))
      (catch Exception _))
    (is (nil? (MDC/get "error-key"))))

  (testing "returns body value"
    (is (= "result" (log/with-context {:x 1} "result")))))


;; =============================================================================
;; Middleware tests
;; =============================================================================

(deftest wrap-correlation-id-test
  (testing "generates correlation ID when not in headers"
    (let [captured-id (atom nil)
          handler (fn [req]
                    (reset! captured-id (:correlation-id req))
                    {:status 200})
          wrapped (log/wrap-correlation-id handler)
          response (wrapped {:headers {}})]
      (is (= 200 (:status response)))
      (is (string? @captured-id))
      (is (= 8 (count @captured-id)))
      (is (= @captured-id (get-in response [:headers log/correlation-id-header])))))

  (testing "uses correlation ID from request header"
    (let [captured-id (atom nil)
          handler (fn [req]
                    (reset! captured-id (:correlation-id req))
                    {:status 200})
          wrapped (log/wrap-correlation-id handler)
          response (wrapped {:headers {"x-request-id" "custom-id"}})]
      (is (= "custom-id" @captured-id))
      (is (= "custom-id" (get-in response [:headers log/correlation-id-header])))))

  (testing "sets MDC during handler execution"
    (let [mdc-id (atom nil)
          handler (fn [_req]
                    (reset! mdc-id (log/get-correlation-id))
                    {:status 200})
          wrapped (log/wrap-correlation-id handler)]
      (wrapped {:headers {}})
      (is (string? @mdc-id))
      ;; MDC should be cleared after
      (is (nil? (log/get-correlation-id))))))


;; =============================================================================
;; Constants tests
;; =============================================================================

(deftest constants-test
  (testing "correlation-id-key is defined"
    (is (= "correlation-id" log/correlation-id-key)))

  (testing "request-id-header is defined"
    (is (= "X-Request-ID" log/request-id-header)))

  (testing "correlation-id-header is defined"
    (is (= "X-Correlation-ID" log/correlation-id-header))))
