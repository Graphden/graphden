(ns graphden.packages.core.arithmetic-test
  "Unit tests for `core.arithmetic` base-fn impls — direct invocations
   against the per-impl `(fn [args ctx])` shape, no full bootstrap
   (pattern of `logic_test.clj`).

   `:round` is here because of a live bug the registry platform tests
   surfaced 2026-09-10: a Ratio from `:div` over longs (14/3 — three
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
