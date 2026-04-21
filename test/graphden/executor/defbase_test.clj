(ns ^{:clj-kondo/config (quote {:linters {:shadowed-var {:level :off}, :unused-binding {:level :off}}})} graphden.executor.defbase-test
  "Tests for the `defbase` macro. Shadowed-var and unused-binding are
   disabled here because sample impls use arg names matching core vars
   (`test`, `key`) by design — that's what the macro is supposed to
   support — and shadow tests define args intentionally unused."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.runtime :as rt]))


;; =============================================================================
;; Sample impls to exercise the macro
;; =============================================================================

(defbase add-fn [a b]
  (+ a b))


(defbase if-fn [test then else]
  (if test then else))


(defbase map-fn [func coll]
  (mapv func coll))


(defbase ctx-aware
  "Impl that uses the execution context."
  [key]
  (get ctx key))


(defbase shadowed
  "Inner let shadows the arg name — inner binding wins inside the let scope."
  [x]
  (let [x 100] (inc x)))


(defbase with-closure
  "fn form shadows the arg symbol inside the closure body."
  [x]
  ((fn [x] (+ x 10)) 5))


;; =============================================================================
;; Tests
;; =============================================================================

(deftest plain-args-resolved
  (testing "literal args passed through as values"
    (is (= 5 (add-fn {:a 2 :b 3} nil))))
  (testing "thunk args called at use-site"
    (let [call-count (atom 0)
          thunk-b (rt/thunk (fn [] (swap! call-count inc) 7))]
      (is (= 9 (add-fn {:a 2 :b thunk-b} nil)))
      (is (= 1 @call-count)))))


(deftest if-fn-is-lazy
  (testing "only the chosen branch's thunk runs"
    (let [then-calls (atom 0)
          else-calls (atom 0)
          then-thunk (rt/thunk (fn [] (swap! then-calls inc) :then-value))
          else-thunk (rt/thunk (fn [] (swap! else-calls inc) :else-value))]

      (is (= :then-value (if-fn {:test true :then then-thunk :else else-thunk} nil)))
      (is (= 1 @then-calls))
      (is (zero? @else-calls))

      (is (= :else-value (if-fn {:test false :then then-thunk :else else-thunk} nil)))
      (is (= 1 @then-calls) "then not called again")
      (is (= 1 @else-calls)))))


(deftest fn-type-passthrough
  (testing "HOF callable arg is passed directly to impl — macro does not wrap it"
    (let [callable (fn [x] (* 2 x))
          result (map-fn {:func callable :coll [1 2 3]} nil)]
      (is (= [2 4 6] result)))))


(deftest ctx-available-in-body
  (testing "ctx symbol bound to second parameter"
    (is (= "hello" (ctx-aware {:key :greeting} {:greeting "hello"})))))


(deftest lexical-shadowing
  (testing "let binding shadows the arg inside its scope"
    (is (= 101 (shadowed {:x 999} nil))))
  (testing "fn parameter shadows the arg inside its scope"
    (is (= 15 (with-closure {:x 999} nil)))))


(defn- expand-cause
  "Macroexpand, and return the root ExceptionInfo cause if it throws a
   CompilerException (which wraps our ex-info)."
  [form]
  (try
    (macroexpand form)
    (catch Exception e
      (loop [ex e]
        (let [cause (Throwable/.getCause ex)]
          (if (instance? clojure.lang.ExceptionInfo ex)
            ex
            (if cause (recur cause) ex)))))))


(deftest macro-validation
  (testing "rejects non-vector arg list"
    (let [ex (expand-cause '(graphden.executor.defbase/defbase bad {:not :vector} (+ 1 2)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #"arg list must be a vector" (ex-message ex)))))
  (testing "rejects reserved arg name"
    (let [ex (expand-cause '(graphden.executor.defbase/defbase bad [ctx] (+ ctx 1)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #"clashes with reserved symbol" (ex-message ex)))))
  (testing "rejects __args as reserved"
    (let [ex (expand-cause '(graphden.executor.defbase/defbase bad [__args] __args))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #"clashes with reserved symbol" (ex-message ex)))))
  (testing "rejects non-symbol entries in arg list"
    (let [ex (expand-cause '(graphden.executor.defbase/defbase bad [a "b"] (+ a b)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #"arg list must contain only simple symbols" (ex-message ex))))))


;; =============================================================================
;; Additional binding forms — verify each known shadowing form is respected
;; =============================================================================

;; Destructured fn params (e.g. `(fn [{:keys [x]}] ...)`) are a known
;; limitation — `extract-bound-symbols` doesn't walk into map destructure
;; forms, so the inner `x` incorrectly gets rewritten to `(rt/resolve-arg
;; __args :x)`. Bodies needing destructure have to use `let` after the fn
;; param: `(fn [m] (let [x (:x m)] ...))`.


(defbase shadow-via-letfn
  "letfn binding shadows the arg symbol inside the local fn's body."
  [x]
  (letfn [(x [] 99)]
    (x)))


(defbase shadow-via-loop
  "loop binding shadows the arg."
  [x]
  (loop [x 5 acc 0]
    (if (zero? x) acc (recur (dec x) (inc acc)))))


(defbase shadow-via-for
  "for binding shadows the arg."
  [x]
  (first (for [x [10 20 30]] (* x 2))))


(defbase shadow-via-if-let
  "if-let binding shadows the arg in the then-branch."
  [x]
  (if-let [x :from-if-let] (name x) :unreachable))


(defbase shadow-via-when-let
  [x]
  (when-let [x :from-when-let] (name x)))


(defbase shadow-via-if-some
  [x]
  (if-some [x :from-if-some] (name x) :unreachable))


(defbase shadow-via-when-some
  [x]
  (when-some [x :from-when-some] (name x)))


(defbase shadow-via-doseq
  "doseq binding shadows the arg — final captured value comes from the
   loop var, not from the original `x`."
  [x]
  (let [last-seen (atom nil)]
    (doseq [x [1 2 3]]
      (reset! last-seen x))
    @last-seen))


(defbase shadow-via-catch
  [x]
  (try
    (throw (ex-info "boom" {}))
    (catch Exception x (ex-message x))))


(deftest binding-forms-all-shadow-correctly
  (is (= 99 (shadow-via-letfn {:x 999} nil)))
  (is (= 5 (shadow-via-loop {:x 999} nil)))
  (is (= 20 (shadow-via-for {:x 999} nil)))
  (is (= "from-if-let" (shadow-via-if-let {:x 999} nil)))
  (is (= "from-when-let" (shadow-via-when-let {:x 999} nil)))
  (is (= "from-if-some" (shadow-via-if-some {:x 999} nil)))
  (is (= "from-when-some" (shadow-via-when-some {:x 999} nil)))
  (is (= 3 (shadow-via-doseq {:x 999} nil)))
  (is (= "boom" (shadow-via-catch {:x 999} nil))))


;; =============================================================================
;; Nested data structures — map and set substitution paths
;; =============================================================================

(defbase nested-in-map-fn [value]
  {:wrapped value :literal 42})


(defbase nested-in-set-fn [value]
  #{value :sentinel})


(defbase nested-in-vector-fn [value]
  [value [value value]])


(deftest arg-substitution-in-nested-data
  (testing "arg sub works inside maps"
    (is (= {:wrapped :x :literal 42} (nested-in-map-fn {:value :x} nil))))
  (testing "arg sub works inside sets"
    (is (= #{:x :sentinel} (nested-in-set-fn {:value :x} nil))))
  (testing "arg sub works inside nested vectors"
    (is (= [:x [:x :x]] (nested-in-vector-fn {:value :x} nil)))))
