(ns graphden.input.capture-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.input.capture :as capture])
  (:import
    (clojure.lang
      ChunkedCons
      IChunk)
    (java.util
      Iterator)))


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


(deftest fully-realized-map-results-are-capturable
  (doseq [input [[] ["one" "two"] '("one" "two") (vec (repeat 65 "chunk"))]]
    (let [value (doall (map str/upper-case input))]
      (is (true? (capture/safe-value? value)))
      (is (true? (capture/safe-value? {:nested [value]}))))))


(deftest capture-does-not-realize-the-rest-of-a-sequence
  (doseq [input [(list 0 1) (vec (range 33))]]
    (let [reads (atom [])
          value (map (fn [element] (swap! reads conj element) element) input)]
      (is (zero? (first value)))
      (let [before @reads]
        (is (< (count before) (count input)))
        (is (false? (capture/safe-value? value)))
        (is (= before @reads)))))
  (let [reads (atom 0)
        value (doall (map (fn [element] (delay (swap! reads inc) element)) [1]))]
    (is (false? (capture/safe-value? value)))
    (is (zero? @reads))))


(deftest unknown-sequence-tails-are-rejected-without-reading-them
  (let [reads (atom 0)
        iterator (reify Iterator
                   (hasNext [_] true)

                   (next [_] (swap! reads inc)))
        value (cons :head (iterator-seq iterator))]
    (is (false? (capture/safe-value? value)))
    (is (zero? @reads))))


(deftest unfamiliar-chunks-are-rejected-before-calling-their-methods
  (let [reads (atom 0)
        chunk (reify IChunk
                (count [_] (swap! reads inc))

                (nth [_ _] (swap! reads inc)))
        value (ChunkedCons. chunk '())]
    (is (false? (capture/safe-value? value)))
    (is (zero? @reads))))


(deftest diagnostic-shape-work-is-bounded
  (is (false? (capture/safe-value? (vec (range 10001)))))
  (is (false? (capture/safe-value? (doall (map inc (range 10001)))))))
