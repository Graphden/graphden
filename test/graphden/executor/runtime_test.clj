(ns graphden.executor.runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.runtime :as rt]))


(deftest resolve-arg-literal
  (testing "returns literal value as-is"
    (is (= 42 (rt/resolve-arg {:x 42} :x)))
    (is (= "hello" (rt/resolve-arg {:x "hello"} :x)))
    (is (nil? (rt/resolve-arg {:x nil} :x)))
    (is (nil? (rt/resolve-arg {} :missing)))))


(deftest resolve-arg-thunk
  (testing "calls thunk wrapped via rt/thunk"
    (let [t (rt/thunk (fn [] 42))]
      (is (= 42 (rt/resolve-arg {:x t} :x)))))
  (testing "thunk called every time resolve-arg is invoked"
    (let [call-count (atom 0)
          t (rt/thunk (fn [] (swap! call-count inc)))]
      (rt/resolve-arg {:x t} :x)
      (rt/resolve-arg {:x t} :x)
      (is (= 2 @call-count)))))


(deftest raw-fn-not-resolved
  (testing "raw fn (without thunk marker) returned as-is — HOF callables"
    (let [raw-fn (fn [item] (* 2 item))]
      (is (identical? raw-fn (rt/resolve-arg {:x raw-fn} :x))))))


(deftest thunk-predicate
  (is (rt/thunk? (rt/thunk (fn [] 1))))
  (is (not (rt/thunk? (fn [] 1))))
  (is (not (rt/thunk? 42)))
  (is (not (rt/thunk? nil))))


(deftest resolve-arg-ideref
  (testing "forces a Delay and returns the underlying value"
    (is (= 7 (rt/resolve-arg {:x (delay 7)} :x))))
  (testing "forces an atom — atoms are IDeref too"
    (is (= 99 (rt/resolve-arg {:x (atom 99)} :x))))
  (testing "thunk takes precedence over IDeref when both would match"
    ;; Thunks happen to also be IDeref via future/delay machinery in some
    ;; JVM paths, so the order in `resolve-arg`'s cond matters. We assert
    ;; the thunk branch wins by checking the fn is called rather than
    ;; treated as a deref target.
    (let [t (rt/thunk (fn [] :thunk-was-called))]
      (is (= :thunk-was-called (rt/resolve-arg {:x t} :x))))))


;; ============================================================================
;; `hof-callable` — normalises a `:fn`-type arg into an invokable callable.
;; ============================================================================

(deftest hof-callable-passes-through-fn
  (testing "when value is already a fn, returns it unchanged"
    (let [f (fn [item] (* 2 item))]
      (is (identical? f (rt/hof-callable {:func f} :func nil))))))


(deftest hof-callable-passes-through-non-uuid
  (testing "non-UUID, non-IDeref, non-fn values pass through as-is"
    (is (= :raw-keyword (rt/hof-callable {:func :raw-keyword} :func nil)))
    (is (= 42 (rt/hof-callable {:func 42} :func nil)))
    (is (nil? (rt/hof-callable {:func nil} :func nil)))))


(deftest hof-callable-ideref-with-fn-value
  (testing "IDeref wrapping a non-UUID returns the dereffed value as-is"
    (let [inner (fn [x] (str "echo " x))
          wrapped (delay inner)]
      ;; Per `hof-callable`'s :else branch inside the IDeref clause: if
      ;; the derefed value isn't a UUID, it's returned. Deref-and-return.
      (is (= inner (rt/hof-callable {:func wrapped} :func nil))))))
