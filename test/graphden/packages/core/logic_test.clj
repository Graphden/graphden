(ns graphden.packages.core.logic-test
  "Unit tests for `core.logic` base-fn impls — direct invocations
   against the per-impl `(fn [args ctx])` shape, no full bootstrap.

   Coverage targets `:equal?` and `:constant-time-equal?` — the
   second is security-critical (bearer-token comparison; a regression
   that re-introduces `=` short-circuiting on the first differing
   byte reopens the timing channel). Pattern mirrors
   `concurrency_test.clj`: slurp + eval `impls.clj` via the loader's
   private helper, then poke each impl through its registry-shape
   value."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "core" "logic"))


;; ============================================================================
;; :equal? — Clojure `=` over two derefable args
;; ============================================================================

(deftest equal?-matches-clojure-equal-on-primitives
  (testing "structurally-equal scalars / collections compare true"
    (let [f (impls/impl-of :equal?)]
      (is (true? (f {:a (delay 1) :b (delay 1)} nil)))
      (is (true? (f {:a (delay "abc") :b (delay "abc")} nil)))
      (is (true? (f {:a (delay [1 2 3]) :b (delay [1 2 3])} nil)))
      (is (true? (f {:a (delay nil) :b (delay nil)} nil)))))
  (testing "structurally-unequal compare false"
    (let [f (impls/impl-of :equal?)]
      (is (false? (f {:a (delay 1) :b (delay 2)} nil)))
      (is (false? (f {:a (delay "abc") :b (delay "abd")} nil)))
      (is (false? (f {:a (delay [1 2 3]) :b (delay [1 2])} nil))))))


;; ============================================================================
;; :constant-time-equal? — string equality for bearer-token compares.
;;
;; These tests assert boolean CORRECTNESS only. The constant-time property
;; (the actual security value — no early-out on first mismatched byte) is a
;; timing invariant a result-only test cannot observe: swapping the impl for a
;; short-circuiting `Arrays/equals` keeps every boolean below identical. That
;; invariant is guaranteed by the impl delegating to
;; `java.security.MessageDigest/isEqual` (see `core/logic/impls.clj`) and is a
;; code-review boundary, not something asserted here.
;; ============================================================================

(deftest constant-time-equal?-matches-equal?-on-strings
  (testing "matching strings → true"
    (let [f (impls/impl-of :constant-time-equal?)]
      (is (true? (f {:a (delay "abc") :b (delay "abc")} nil)))
      (is (true? (f {:a (delay "") :b (delay "")} nil)))
      (is (true? (f {:a (delay "secret-token-1234") :b (delay "secret-token-1234")} nil)))))
  (testing "non-matching strings → false (regardless of mismatch position)"
    (let [f (impls/impl-of :constant-time-equal?)]
      (is (false? (f {:a (delay "abc") :b (delay "abd")} nil))
          "differs in last byte")
      (is (false? (f {:a (delay "abc") :b (delay "bbc")} nil))
          "differs in first byte")
      (is (false? (f {:a (delay "abc") :b (delay "abcd")} nil))
          "differs in length")
      (is (false? (f {:a (delay "") :b (delay "x")} nil))
          "empty vs non-empty"))))


(deftest constant-time-equal?-rejects-non-string-input
  (testing "nil / non-string inputs short-circuit to false at the boundary"
    (let [f (impls/impl-of :constant-time-equal?)]
      (is (false? (f {:a (delay nil) :b (delay nil)} nil))
          "nil-vs-nil is FALSE (asymmetric with :equal? — this is intentional, a callable that never saw a token must reject)")
      (is (false? (f {:a (delay nil) :b (delay "abc")} nil)))
      (is (false? (f {:a (delay "abc") :b (delay nil)} nil)))
      (is (false? (f {:a (delay 123) :b (delay "123")} nil))
          "type-mismatched inputs are false even when their string forms match"))))


;; ============================================================================
;; :assert / :assert-eq — the test-assertion primitives. Pass = value
;; returned unchanged; fail = `:execution-error/assertion-failed` with
;; the operands in ex-data (NOT in the message — the message stays
;; constant-shaped so a secret operand can't leak through it).
;; ============================================================================

(defn- thrown-data
  [f args]
  (try (f args nil) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest assert-passes-truthy-values-through
  (let [f (impls/impl-of :assert)]
    (is (= 42 (f {:value (delay 42)} nil)))
    (is (true? (f {:value (delay true)} nil)))
    (is (= [] (f {:value (delay [])} nil))
        "empty collections are truthy, like in Clojure")
    (is (zero? (f {:value (delay 0)} nil))
        "zero is truthy, like in Clojure")))


(deftest assert-throws-on-falsy
  (let [f (impls/impl-of :assert)]
    (testing "nil and false both throw the canonical error type"
      (is (= :execution-error/assertion-failed
             (:type (thrown-data f {:value (delay nil)}))))
      (is (= :execution-error/assertion-failed
             (:type (thrown-data f {:value (delay false)})))))))


(deftest assert-eq-returns-actual-on-match
  (let [f (impls/impl-of :assert-eq)]
    (is (= 7 (f {:actual (delay 7) :expected (delay 7)} nil)))
    (is (= {:a [1 2]} (f {:actual (delay {:a [1 2]}) :expected (delay {:a [1 2]})} nil))
        "structural equality, Clojure `=`")
    (is (nil? (f {:actual (delay nil) :expected (delay nil)} nil))
        "nil = nil passes and returns nil")))


(deftest assert-eq-throws-with-both-operands-in-data
  (let [f (impls/impl-of :assert-eq)
        ex (try (f {:actual (delay 7) :expected (delay 8)} nil)
                nil
                (catch clojure.lang.ExceptionInfo e e))
        data (ex-data ex)]
    (is (= :execution-error/assertion-failed (:type data)))
    (is (= 7 (:actual data)))
    (is (= 8 (:expected data)))
    (testing "operand VALUES stay out of the visible message (secret-leak guard)"
      (is (not (re-find #"7|8" (ex-message ex)))))))
