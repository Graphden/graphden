(ns graphden.library.base-fns.core.arithmetic
  "Arithmetic and comparison base functions.

   Arithmetic: add, sub, mul, div, mod, neg, abs
   Comparison: eq, neq, lt, lte, gt, gte"
  (:require
    [graphden.executor.registry.macros :refer [defbase]]))


;; === Arithmetic helpers ===

(defn- check-numeric-result!
  "Validates that result is not Infinity or NaN.
   These can occur from operations on very large numbers.
   Throws :execution-error/numeric-overflow on invalid result."
  [result operation nums]
  (when (and (number? result)
             (double? result)
             (or (Double/isInfinite result)
                 (Double/isNaN result)))
    (throw (ex-info (if (Double/isNaN result)
                      "Arithmetic result is NaN (Not a Number)"
                      "Arithmetic overflow: result is infinite")
                    {:type :execution-error/numeric-overflow
                     :operation operation
                     :result (str result)
                     :num-count (count nums)})))
  result)


;; === Arithmetic ===
;; Note: add, sub, mul, div accept lists like Clojure's +, -, *, /

(defbase add-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (check-numeric-result! (apply + nums) :add nums))


(defbase sub-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (when (empty? nums)
    (throw (ex-info "Subtraction requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  (check-numeric-result! (apply - nums) :sub nums))


(defbase mul-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (check-numeric-result! (apply * nums) :mul nums))


(defbase div-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (when (empty? nums)
    (throw (ex-info "Division requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  ;; Check all divisors (rest nums) for zero
  ;; (/ a b c) = (/ (/ a b) c), so b and c must be non-zero
  (when-let [zero-divisor (some #(when (zero? %) %) (rest nums))]
    (throw (ex-info "Division by zero"
                    {:type :execution-error/division-by-zero
                     :nums nums
                     :zero-at zero-divisor})))
  (check-numeric-result! (apply / nums) :div nums))


(defbase mod-fn
  {:args {:a :numeric, :b :numeric}
   :return-type :numeric}
  (when (zero? b)
    (throw (ex-info "Modulo by zero"
                    {:type :execution-error/modulo-by-zero
                     :a a :b b})))
  (mod a b))


(defbase neg-fn
  {:args {:n :numeric}
   :return-type :numeric}
  (- n))


(defbase abs-fn
  {:args {:n :numeric}
   :return-type :numeric}
  (abs n))


;; === Comparison ===
;; Note: comparison functions accept lists like Clojure's =, <, >, etc.
;; (eq [1 1 1]) => true, (lt [1 2 3]) => true (ascending)

(defbase eq-fn
  {:args {:values :jsonb}
   :return-type :bool}
  (apply = values))


(defbase neq-fn
  {:args {:values :jsonb}
   :return-type :bool}
  (apply not= values))


(defbase lt-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply < nums))


(defbase lte-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply <= nums))


(defbase gt-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply > nums))


(defbase gte-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply >= nums))


;; === Exports ===

(def arithmetic-defs
  {:add add-fn
   :sub sub-fn
   :mul mul-fn
   :div div-fn
   :mod mod-fn
   :neg neg-fn
   :abs abs-fn})


(def comparison-defs
  {:eq eq-fn
   :neq neq-fn
   :lt lt-fn
   :lte lte-fn
   :gt gt-fn
   :gte gte-fn})
