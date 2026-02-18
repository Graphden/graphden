(ns graphden.library.base-fns.core.transducer-test
  "Tests for transducer support in HOF base functions.

   These tests verify the underlying Clojure behavior of transducers
   and lazy sequences that the base functions are built on.

   The actual base-fn wrappers (with :fn type args) are tested in hof_test.clj
   using storage and executor integration."
  (:require
    [clojure.test :refer [deftest is testing]]))


;; === Core Transducer Behavior Tests ===
;; These verify Clojure's transducer semantics that graphden relies on

(deftest map-transducer-semantics-test
  (testing "map with coll returns lazy sequence"
    (let [result (map inc [1 2 3])]
      (is (seq? result) "map should return lazy sequence")
      (is (= [2 3 4] (vec result)))))

  (testing "map without coll returns transducer"
    (let [xf (map inc)]
      (is (fn? xf) "map without coll should return transducer (fn)")
      (is (= [2 3 4] (into [] xf [1 2 3]))))))


(deftest filter-transducer-semantics-test
  (testing "filter with coll returns lazy sequence"
    (let [result (filter odd? [1 2 3 4 5])]
      (is (seq? result) "filter should return lazy sequence")
      (is (= [1 3 5] (vec result)))))

  (testing "filter without coll returns transducer"
    (let [xf (filter odd?)]
      (is (fn? xf) "filter without coll should return transducer (fn)")
      (is (= [1 3 5] (into [] xf [1 2 3 4 5]))))))


(deftest comp-transducer-test
  (testing "comp composes multiple transducers"
    (let [filter-xf (filter odd?)
          map-xf (map inc)
          composed (comp filter-xf map-xf)]
      (is (fn? composed))
      ;; filter odd first [1 3 5], then inc -> [2 4 6]
      (is (= [2 4 6] (into [] composed [1 2 3 4 5])))))

  (testing "comp with regular functions"
    (let [composed (comp inc inc inc)]
      (is (= 13 (composed 10)))))

  (testing "comp with empty args returns identity"
    (let [composed (comp)]
      (is (= 42 (composed 42))))))


(deftest transduce-test
  (testing "transduce applies transducer with reducing function"
    (let [xf (filter odd?)]
      (is (= 9 (transduce xf + 0 [1 2 3 4 5])))))

  (testing "transduce with composed transducer"
    (let [filter-xf (filter odd?)
          map-xf (map inc)
          composed (comp filter-xf map-xf)]
      ;; filter odd [1 3 5] -> map inc [2 4 6] -> sum = 12
      (is (= 12 (transduce composed + 0 [1 2 3 4 5])))))

  (testing "transduce with conj builds vector"
    (let [xf (map str)]
      (is (= ["1" "2" "3"] (transduce xf conj [] [1 2 3]))))))


;; === Laziness Behavior Tests ===

(deftest lazy-sequence-behavior-test
  (testing "map produces lazy sequence"
    (let [call-count (atom 0)
          expensive-fn (fn [x]
                         (swap! call-count inc)
                         (* x 2))
          result (map expensive-fn [1 2 3 4 5])]
      ;; Before taking anything, nothing computed
      (is (zero? @call-count) "lazy sequence should not compute until realized")
      ;; Take first 2
      (doall (take 2 result))
      ;; Only computed what we needed (may compute extra due to chunking)
      (is (<= 2 @call-count))))

  (testing "filter produces lazy sequence"
    (let [call-count (atom 0)
          tracking-pred (fn [x]
                          (swap! call-count inc)
                          (odd? x))
          result (filter tracking-pred [1 2 3 4 5])]
      (is (zero? @call-count) "lazy sequence should not compute until realized")
      (is (= 1 (first result)) "first odd number should be 1")
      (is (pos? @call-count)))))


(deftest find-first-laziness-test
  (testing "filter + first is lazy - early termination"
    (let [call-count (atom 0)
          tracking-pred (fn [x]
                          (swap! call-count inc)
                          (> x 10))
          coll (range 1 1000)]
      ;; Using filter + first should stop at first match
      (is (= 11 (first (filter tracking-pred coll))))
      ;; Should have checked only up to 11 (not all 1000)
      ;; Due to chunking, might check a few more, but definitely not all
      (is (< @call-count 100) "Should terminate early due to laziness"))))


;; === Edge Cases ===

(deftest empty-collection-test
  (testing "map on empty collection"
    (is (= [] (vec (map inc [])))))

  (testing "filter on empty collection"
    (is (= [] (filterv odd? []))))

  (testing "transduce on empty collection"
    (let [xf (map inc)]
      (is (zero? (transduce xf + 0 []))))))


(deftest transducer-composition-order-test
  (testing "transducers compose left-to-right for data flow"
    ;; In Clojure, (comp (filter odd?) (map inc)) means:
    ;; data flows through filter first, then map
    (let [filter-xf (filter odd?)
          map-xf (map inc)
          ;; Compose: filter then map
          xf (comp filter-xf map-xf)]
      ;; [1 2 3 4 5] -> filter odd [1 3 5] -> map inc [2 4 6]
      (is (= [2 4 6] (into [] xf [1 2 3 4 5])))))

  (testing "reversed composition order"
    ;; Now map first, then filter
    (let [map-xf (map inc)
          filter-xf (filter odd?)
          ;; Compose: map then filter
          xf (comp map-xf filter-xf)]
      ;; [1 2 3 4 5] -> map inc [2 3 4 5 6] -> filter odd [3 5]
      (is (= [3 5] (into [] xf [1 2 3 4 5]))))))


(deftest transducer-type-verification-test
  (testing "transducer is a function"
    (let [xf (filter odd?)]
      (is (ifn? xf) "transducer should be IFn")))

  (testing "composed transducer is a function"
    (let [xf1 (filter odd?)
          xf2 (map inc)
          composed (comp xf1 xf2)]
      (is (ifn? composed) "composed transducer should be IFn"))))


;; === Real-world Pipeline Test ===

(deftest transducer-pipeline-efficiency-test
  (testing "transducer pipeline is single-pass"
    (let [map-calls (atom 0)
          filter-calls (atom 0)
          counting-map (fn [x]
                         (swap! map-calls inc)
                         (* x 2))
          counting-filter (fn [x]
                            (swap! filter-calls inc)
                            (odd? x))
          xf (comp (filter counting-filter)
                   (map counting-map))
          coll (range 10)
          result (transduce xf conj [] coll)]
      ;; Filter sees all 10 elements
      (is (= 10 @filter-calls))
      ;; Map only sees the 5 odd elements [1 3 5 7 9]
      (is (= 5 @map-calls))
      ;; Result is doubled odd numbers
      (is (= [2 6 10 14 18] result)))))
