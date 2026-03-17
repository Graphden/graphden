(ns graphden.executor.argument-resolution-test
  "Tests for argument resolution edge cases: lazy sequences, depth limits.

   ## 2-Entity Schema

   Arguments are stored directly in the arg entity:
   - arg.value → literal JSONB value
   - arg.ref-id → FK to fn (execute and use result)
   - arg.is-fn → pass fn-id directly (for HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.storage.protocol.config :as config]))


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


;; === build-delay tests ===

(deftest build-delay-test
  (testing "builds delay for literal value"
    (let [arg {:id (random-uuid)
               :name "x"
               :type :int
               :value 42}
          context {}
          execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not be called" {})))
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (= 42 @result))))

  (testing "builds delay for ref-id with is-fn=true (pass fn-id directly)"
    (let [ref-fn-id (random-uuid)
          arg {:id (random-uuid)
               :name "f"
               :type :fn
               :ref-id ref-fn-id
               :is-fn true}
          context {}
          execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not be called" {})))
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (= ref-fn-id @result))))

  (testing "builds delay for ref-id with is-fn=false (execute fn)"
    (let [ref-fn-id (random-uuid)
          arg-id (random-uuid)
          arg {:id arg-id
               :name "result"
               :type :int
               :ref-id ref-fn-id
               :is-fn false}
          context {:test true}
          ;; Context will be augmented with :triggering-arg-id
          expected-ctx (assoc context :triggering-arg-id arg-id)
          execute-ref-fn (fn [ctx fn-id _caller-args _arg-delays]
                           (is (= expected-ctx ctx))
                           (is (= ref-fn-id fn-id))
                           100)
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (= 100 @result))))

  (testing "builds nil delay for arg with no value"
    (let [arg {:id (random-uuid)
               :name "optional"
               :type :text}
          context {}
          execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not be called" {})))
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (nil? @result)))))


(deftest arg-has-value-test
  (testing "returns true when value is set"
    (is (true? (#'arg-res/arg-has-value? {:value 42}))))

  (testing "returns true when ref-id is set"
    (is (true? (#'arg-res/arg-has-value? {:ref-id (random-uuid)}))))

  (testing "returns false when neither is set"
    (is (false? (#'arg-res/arg-has-value? {:name "x"})))))


;; === build-value-delay tests for is-fn with string UUID ===

(deftest build-value-delay-fn-type-test
  (testing "converts string UUID to UUID when is-fn=true"
    (let [uuid (random-uuid)
          uuid-str (str uuid)
          result (#'arg-res/build-value-delay uuid-str true)]
      (is (delay? result))
      (is (uuid? @result))
      (is (= uuid @result))))

  (testing "passes through invalid UUID string when is-fn=true"
    (let [result (#'arg-res/build-value-delay "not-a-uuid" true)]
      (is (delay? result))
      (is (= "not-a-uuid" @result))))

  (testing "passes through non-string value when is-fn=true"
    (let [uuid (random-uuid)
          result (#'arg-res/build-value-delay uuid true)]
      (is (delay? result))
      (is (= uuid @result))))

  (testing "passes through value unchanged when is-fn=false"
    (let [uuid-str (str (random-uuid))
          result (#'arg-res/build-value-delay uuid-str false)]
      (is (delay? result))
      (is (= uuid-str @result)))))
