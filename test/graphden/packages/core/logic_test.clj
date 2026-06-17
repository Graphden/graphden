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
    [clojure.test :refer [deftest is testing use-fixtures]]))


(def ^:dynamic *impls* nil)


(defn- load-logic-impls-fixture
  [f]
  (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                     "core" "logic")]
    (f)))


(use-fixtures :once load-logic-impls-fixture)


(defn- impl-of
  [kw]
  (let [entry (get *impls* kw)]
    (or (and (map? entry) (:impl entry))
        (and (fn? entry) entry)
        (throw (ex-info (str "No impl for " kw) {:available (keys *impls*)})))))


;; ============================================================================
;; :equal? — Clojure `=` over two derefable args
;; ============================================================================

(deftest equal?-matches-clojure-equal-on-primitives
  (testing "structurally-equal scalars / collections compare true"
    (let [f (impl-of :equal?)]
      (is (true? (f {:a (delay 1) :b (delay 1)} nil)))
      (is (true? (f {:a (delay "abc") :b (delay "abc")} nil)))
      (is (true? (f {:a (delay [1 2 3]) :b (delay [1 2 3])} nil)))
      (is (true? (f {:a (delay nil) :b (delay nil)} nil)))))
  (testing "structurally-unequal compare false"
    (let [f (impl-of :equal?)]
      (is (false? (f {:a (delay 1) :b (delay 2)} nil)))
      (is (false? (f {:a (delay "abc") :b (delay "abd")} nil)))
      (is (false? (f {:a (delay [1 2 3]) :b (delay [1 2])} nil))))))


;; ============================================================================
;; :constant-time-equal? — string-only equality with no per-byte short-circuit
;; ============================================================================

(deftest constant-time-equal?-matches-equal?-on-strings
  (testing "matching strings → true"
    (let [f (impl-of :constant-time-equal?)]
      (is (true? (f {:a (delay "abc") :b (delay "abc")} nil)))
      (is (true? (f {:a (delay "") :b (delay "")} nil)))
      (is (true? (f {:a (delay "secret-token-1234") :b (delay "secret-token-1234")} nil)))))
  (testing "non-matching strings → false (regardless of mismatch position)"
    (let [f (impl-of :constant-time-equal?)]
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
    (let [f (impl-of :constant-time-equal?)]
      (is (false? (f {:a (delay nil) :b (delay nil)} nil))
          "nil-vs-nil is FALSE (asymmetric with :equal? — this is intentional, a callable that never saw a token must reject)")
      (is (false? (f {:a (delay nil) :b (delay "abc")} nil)))
      (is (false? (f {:a (delay "abc") :b (delay nil)} nil)))
      (is (false? (f {:a (delay 123) :b (delay "123")} nil))
          "type-mismatched inputs are false even when their string forms match"))))
