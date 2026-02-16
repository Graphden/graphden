(ns graphden.storage.protocol.batch-helpers-test
  "Tests for batch processing helpers."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.interface :as storage]))


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


(deftest process-batch-with-index-test
  (testing "processes items and returns results"
    (let [items [{:id 1} {:id 2} {:id 3}]
          results (doall (storage/process-batch-with-index
                           items
                           :id
                           (fn [item _idx] (:id item))))]
      (is (= [1 2 3] results))))

  (testing "wraps exceptions with batch context"
    (let [items [{:id 1} {:id 2} {:id 3}]
          fail-on-2 (fn [item _idx]
                      (if (= 2 (:id item))
                        (throw (ex-info "Failed on 2" {:type :test-failure}))
                        (:id item)))]
      (try
        (doall (storage/process-batch-with-index items :id fail-on-2))
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Failed on 2" (ex-message e)))
          (is (= 1 (:batch-index (ex-data e))))
          (is (= 3 (:batch-size (ex-data e))))
          (is (= 2 (:failed-id (ex-data e))))))))

  (testing "handles regular exceptions"
    (let [items [{:id 1}]
          fail-fn (fn [_item _idx]
                    (throw (Exception. "Regular error")))]
      (try
        (doall (storage/process-batch-with-index items :id fail-fn))
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Regular error" (ex-message e)))
          (is (= :batch-error/partial-failure (:type (ex-data e))))))))

  (testing "works without get-id-fn"
    (let [items [{:x 1} {:x 2}]
          results (doall (storage/process-batch-with-index
                           items
                           nil
                           (fn [item _idx] (:x item))))]
      (is (= [1 2] results)))))
