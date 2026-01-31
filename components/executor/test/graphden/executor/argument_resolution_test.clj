(ns graphden.executor.argument-resolution-test
  "Tests for argument resolution edge cases: lazy sequences, depth limits."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.storage-protocol.config :as config]))


(deftest realize-lazy-seq-bounded-test
  (testing "realizes lazy sequence within limit"
    (let [result (#'arg-res/realize-lazy-seq-bounded (map inc [1 2 3]) 10)]
      (is (= [2 3 4] result))))

  (testing "throws when lazy sequence exceeds max-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Lazy sequence exceeds maximum allowed size"
          (#'arg-res/realize-lazy-seq-bounded (range 100) 10))))

  (testing "realizes exactly at max-size"
    (let [result (#'arg-res/realize-lazy-seq-bounded (range 5) 5)]
      (is (= [0 1 2 3 4] result))))

  (testing "handles empty sequence"
    (let [result (#'arg-res/realize-lazy-seq-bounded (map inc []) 10)]
      (is (= [] result)))))


(deftest realize-lazy-value-test
  (testing "passes through nil"
    (is (nil? (#'arg-res/realize-lazy-value nil))))

  (testing "passes through non-lazy values"
    (is (= 42 (#'arg-res/realize-lazy-value 42)))
    (is (= "hello" (#'arg-res/realize-lazy-value "hello")))
    (is (= [1 2 3] (#'arg-res/realize-lazy-value [1 2 3])))
    (is (= #{1 2} (#'arg-res/realize-lazy-value #{1 2}))))

  (testing "realizes lazy sequences"
    (let [result (#'arg-res/realize-lazy-value (map inc [1 2 3]))]
      (is (= [2 3 4] result))
      (is (vector? result))))

  (testing "realizes maps with lazy values recursively"
    (let [result (#'arg-res/realize-lazy-value {:a (map inc [1 2]) :b 42})]
      (is (= {:a [2 3] :b 42} result))))

  (testing "realizes other seqable types like range"
    (let [result (#'arg-res/realize-lazy-value (range 5))]
      (is (= [0 1 2 3 4] result))))

  (testing "throws on collection depth exceeding limit"
    (binding [config/*max-nested-collection-depth* 3]
      ;; Build nested map 4 levels deep
      (let [deep-map {:a {:b {:c {:d 1}}}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Collection nesting exceeds maximum allowed depth"
              (#'arg-res/realize-lazy-value deep-map))))))

  (testing "throws on lazy sequence exceeding size limit"
    (binding [config/*max-lazy-seq-size* 5]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Lazy sequence exceeds maximum allowed size"
            (#'arg-res/realize-lazy-value (map identity (range 100))))))))
