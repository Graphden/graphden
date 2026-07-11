(ns graphden.util.backoff-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.util.backoff :as backoff]))


(deftest next-ms-doubles-then-caps
  (testing "doubles each step from the initial backoff"
    (is (= 2000 (backoff/next-ms backoff/initial-ms)))
    (is (= 4000 (backoff/next-ms 2000)))
    (is (= 8000 (backoff/next-ms 4000))))
  (testing "caps at max-ms and stays there"
    (is (= backoff/max-ms (backoff/next-ms 20000)))
    (is (= backoff/max-ms (backoff/next-ms backoff/max-ms))))
  (testing "the policy constants are the documented 1s → 30s"
    (is (= 1000 backoff/initial-ms))
    (is (= 30000 backoff/max-ms))))
