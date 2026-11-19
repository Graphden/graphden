(ns graphden.executor.checked-source-data-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.checked-source-data :as data]
    [graphden.types.check.provenance :as provenance]))


(def ^:private limits {:max-bytes 10000000 :max-nodes 100000 :callbacks []})


(defn- nested
  [n value]
  (nth (iterate vector value) n))


(deftest shared-subtrees-still-consume-every-occurrence-budget
  (let [shared (vec (range 10))
        input [shared shared]
        independent [(vec (range 10)) (vec (range 10))]
        exact (assoc limits :max-bytes 4608 :max-nodes 43)]
    (is (= 4608 (data/charge input exact)))
    (is (= (data/charge input limits) (data/charge independent limits)))
    (is (nil? (data/charge input (update exact :max-bytes dec))))
    (is (nil? (data/charge input (update exact :max-nodes dec))))))


(deftest an-admitted-shallow-subtree-does-not-bypass-a-deeper-depth-limit
  (let [shared (vec (range 10))]
    (is (number? (data/charge [shared (nested 62 shared)] limits)))
    (is (nil? (data/charge [shared (nested 63 shared)] limits)))))


(deftest shared-containers-cannot-hide-mutable-or-unsupported-metadata
  (let [mutable (java.util.Date. 0)
        shared (conj (vec (range 10)) mutable)
        tail (with-meta (list 2 3) {:unknown mutable})
        hidden-tail (with-meta (conj tail 1) nil)
        issuer (provenance/capture-marker-stamp {:args {} :return-type :text} :example)
        signature (issuer (provenance/stamp {:fn-id :example :args {:x mutable} :return :text}
                                            {:args {} :return-type :text}))]
    (doseq [value [[shared shared] [hidden-tail hidden-tail] [signature signature]
                   [(subvec [mutable 1 2] 1) (vec (range 10))]]]
      (is (nil? (data/charge value limits))))))


(deftest exhausting-scratch-summaries-preserves-normal-admission
  (let [input (vec (repeatedly 1030 #(vec (range 10))))
        extra (vec (range 10))]
    (is (= 2241536 (data/charge input limits)))
    (is (= (+ 2241536 (* 2 2176)) (data/charge (conj input extra extra) limits)))
    (is (nil? (data/charge (conj input [extra (java.util.Date. 0)]) limits)))))


(deftest exponential-sharing-cannot-overflow-the-occurrence-node-limit
  (let [dag (nth (iterate (fn [value] [value value]) :leaf) 63)
        budget (assoc limits :max-nodes Long/MAX_VALUE
                      :max-bytes 100000000000000000000000000000000000N)]
    (is (nil? (data/charge dag budget)))))


(deftest shared-identity-does-not-hide-changed-neighbors-or-metadata
  (let [shared (vec (range 10))
        metadata (hash-map :source "source.clj" :line 1)
        left (with-meta (array-map :shared shared :tag 1) metadata)]
    (testing "both complete values are separately admitted"
      (is (number? (data/charge left limits)))
      (is (data/same-input? left left)))
    (testing "equal shared children do not authorize another parent"
      (is (not (data/same-input? left (with-meta (array-map :shared shared :tag 2) metadata)))))
    (testing "equivalent reader metadata is still compared by provenance identity"
      (is (not (data/same-input? left (with-meta (array-map :shared shared :tag 1)
                                        (hash-map :source "source.clj" :line 1))))))))


(deftest opaque-or-nested-metadata-cannot-enter-an-immutable-summary
  (let [reads (atom 0)
        foreign (proxy [clojure.lang.APersistentMap] []
                  (seq [] (swap! reads inc) (seq {:source "foreign"}))

                  (count [] (swap! reads inc) 1)

                  (valAt
                    ([_key] (swap! reads inc) nil)
                    ([_key absent] (swap! reads inc) absent)))
        value (with-meta (vec (range 10)) foreign)
        nested-metadata (with-meta (hash-map :source "known") {:hidden (java.util.Date. 0)})]
    (is (nil? (data/charge [value value] limits)))
    (is (zero? @reads))
    (is (nil? (data/charge (with-meta [1 2] nested-metadata) limits)))))
