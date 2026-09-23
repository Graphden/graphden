(ns graphden.packages.core.arithmetic-test
  "Unit tests for `core.arithmetic` base-fn impls — direct invocations
   against the per-impl `(fn [args ctx])` shape, no full bootstrap
   (pattern of `logic_test.clj`).

   `:round` is here because of a live bug the registry platform tests
   surfaced: a Ratio from `:div` over longs (14/3 — three
   ratings summing to 14) has no exact BigDecimal, and `(bigdec 14/3)`
   threw \"Non-terminating decimal expansion\" — every marketplace
   card whose ratings did not divide evenly broke."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "core" "arithmetic"))


(deftest round-half-up-to-decimals
  (let [f (impls/impl-of :round)]
    (testing "doubles round half-up"
      (is (= 4.3 (f {:number (delay 4.26) :decimals (delay 1)} nil)))
      (is (= 4.26 (f {:number (delay 4.255) :decimals (delay 2)} nil))))
    (testing "zero decimals yields a long"
      (is (= 4 (f {:number (delay 4.26) :decimals (delay 0)} nil)))
      (is (= 5 (f {:number (delay 4.5) :decimals (delay 0)} nil))))
    (testing "longs pass through"
      (is (= 7 (f {:number (delay 7) :decimals (delay 0)} nil)))
      (is (= 7.0 (f {:number (delay 7) :decimals (delay 1)} nil))))))


(deftest round-accepts-a-non-terminating-ratio
  (let [f (impls/impl-of :round)]
    (testing "14/3 — the marketplace's three-ratings average"
      (is (= 4.7 (f {:number (delay 14/3) :decimals (delay 1)} nil)))
      (is (= 5 (f {:number (delay 14/3) :decimals (delay 0)} nil))))
    (testing "a terminating ratio still rounds exactly"
      (is (= 4.3 (f {:number (delay 17/4) :decimals (delay 1)} nil))))))


;; ============================================================================
;; :min / :max / :floor / :ceil / :sqrt / :pow — the bounds-and-roots batch
;; ============================================================================

(deftest min-max-over-a-list
  (let [mn (impls/impl-of :min) mx (impls/impl-of :max)]
    (is (= 1 (mn {:nums (delay [3 1 2])} nil)))
    (is (= 3 (mx {:nums (delay [3 1 2])} nil)))
    (is (= 2.5 (mx {:nums (delay [2.5 -1])} nil)))
    (testing "an empty list is refused with a typed error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one"
            (mn {:nums (delay [])} nil))))))


(deftest floor-ceil-return-ints
  (let [fl (impls/impl-of :floor) ce (impls/impl-of :ceil)]
    (is (= 4 (fl {:number (delay 4.7)} nil)))
    (is (= -5 (fl {:number (delay -4.2)} nil)))
    (is (= 5 (ce {:number (delay 4.2)} nil)))
    (is (= 7 (ce {:number (delay 7)} nil)))
    (is (instance? Long (fl {:number (delay 4.7)} nil)))))


(deftest sqrt-and-pow
  (let [sq (impls/impl-of :sqrt) pw (impls/impl-of :pow)]
    (is (= 3.0 (sq {:number (delay 9)} nil)))
    (is (= 8.0 (pw {:base (delay 2) :exponent (delay 3)} nil)))
    (is (= 0.5 (pw {:base (delay 2) :exponent (delay -1)} nil)))
    (testing "a NaN / infinite result is the numeric-overflow error, not a non-number"
      (is (thrown? clojure.lang.ExceptionInfo (sq {:number (delay -1)} nil)))
      (is (thrown? clojure.lang.ExceptionInfo (pw {:base (delay 10) :exponent (delay 400)} nil))))))


(deftest quot-of-two-ints-types-as-int
  ;; `:quot` promised "int inputs stay ints" with no rule behind it, so a
  ;; quotient of two ints typed `:numeric` and failed an `:int` slot.
  (let [rule (:return-type-rule (get impls/*impls* :quot))
        info (fn [a b] {:dividend {:type a} :divisor {:type b}})]
    (is (fn? rule) "`:quot` carries a return-type rule")
    (is (= :int (rule (info :int :int) :numeric)))
    (is (= :numeric (rule (info :int :numeric) :numeric)))))
