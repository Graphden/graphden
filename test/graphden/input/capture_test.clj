(ns graphden.input.capture-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.capture :as capture]))


(deftest plain-eager-data-remains-capturable
  (doseq [value [nil false {:a [1 "text" :keyword] :b #{1 2}}
                 '(1 2 3) (subvec [0 1 2] 1) (sorted-map :a 1)
                 (sorted-set 1 2) (random-uuid) (java.util.Date.)]]
    (is (true? (capture/safe-value? value)))))


(deftest deferred-values-are-not-read-by-capture-admission
  (let [reads (atom 0)
        lazy-value (lazy-seq (swap! reads inc) (list 1))
        delayed (delay (swap! reads inc))]
    (is (false? (capture/safe-value? lazy-value)))
    (is (false? (capture/safe-value? {:nested [lazy-value]})))
    (is (false? (capture/safe-value? delayed)))
    (is (false? (capture/safe-value? {:nested delayed})))
    (is (zero? @reads))))


(deftest diagnostic-shape-work-is-bounded
  (is (false? (capture/safe-value? (vec (range 10001))))))
