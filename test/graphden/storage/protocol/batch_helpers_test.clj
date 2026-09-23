(ns graphden.storage.protocol.batch-helpers-test
  "Tests for batch processing helpers."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


(deftest wrap-batch-error-test
  (testing "wraps ExceptionInfo with batch context"
    (let [original (ex-info "Original error" {:type :test-error})
          wrapped (storage/wrap-batch-error original 5 10)]
      (is (instance? clojure.lang.ExceptionInfo wrapped))
      (is (= "Original error" (ex-message wrapped)))
      (is (= :test-error (:type (ex-data wrapped))))
      (is (= 5 (:batch-index (ex-data wrapped))))
      (is (= 10 (:batch-size (ex-data wrapped))))
      (is (= original (ex-cause wrapped)))))

  (testing "wraps regular Exception with batch context"
    (let [original (Exception. "Regular exception")
          wrapped (storage/wrap-batch-error original 2 5)]
      (is (= "Regular exception" (ex-message wrapped)))
      (is (= :batch-error/partial-failure (:type (ex-data wrapped))))
      (is (= 2 (:batch-index (ex-data wrapped))))
      (is (= 5 (:batch-size (ex-data wrapped))))))

  (testing "includes failed-id when provided"
    (let [failed-id (random-uuid)
          original (ex-info "Error" {:type :test})
          wrapped (storage/wrap-batch-error original 0 3 failed-id)]
      (is (= failed-id (:failed-id (ex-data wrapped))))))

  (testing "omits failed-id when nil"
    (let [original (ex-info "Error" {:type :test})
          wrapped (storage/wrap-batch-error original 0 3 nil)]
      (is (not (contains? (ex-data wrapped) :failed-id))))))


;; =============================================================================
;; initialize-with-cleanup! tests
;; =============================================================================

(defrecord MockStorage
  [init-called close-called should-fail]

  storage/Storage

  (initialize
    [this _schema]
    (reset! (:init-called this) true)
    (when @(:should-fail this)
      (throw (ex-info "Init failed" {:type :test-error})))
    this)


  (close
    [this]
    (reset! (:close-called this) true)))


(deftest initialize-with-cleanup!-test
  (testing "returns storage on successful init"
    (let [init-called (atom false)
          close-called (atom false)
          mock (->MockStorage init-called close-called (atom false))
          result (storage/initialize-with-cleanup! mock {})]
      (is (= mock result))
      (is @init-called)
      (is (not @close-called))))

  (testing "closes storage and rethrows on init failure"
    (let [init-called (atom false)
          close-called (atom false)
          mock (->MockStorage init-called close-called (atom true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Init failed"
            (storage/initialize-with-cleanup! mock {})))
      (is @init-called)
      (is @close-called "Storage should be closed on init failure"))))
