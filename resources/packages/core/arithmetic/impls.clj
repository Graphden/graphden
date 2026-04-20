(ns graphden.packages.core.arithmetic.impls
  "Implementations for core/arithmetic base functions.

   Written with the new `defbase` macro — arg symbols resolve lazily at
   use site, short-circuit evaluation preserved, and HOF `:fn` args
   are auto-wrapped by the runtime. See `graphden.executor.defbase`."
  (:require
    [graphden.executor.defbase :refer [defbase]]))


;; === Helpers ===

(defn- check-numeric-result!
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

(defbase add [nums]
  (check-numeric-result! (apply + nums) :add nums))


(defbase sub [nums]
  (when (empty? nums)
    (throw (ex-info "Subtraction requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  (check-numeric-result! (apply - nums) :sub nums))


(defbase mul [nums]
  (check-numeric-result! (apply * nums) :mul nums))


(defbase div [nums]
  (when (empty? nums)
    (throw (ex-info "Division requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  (when-let [zero-divisor (some #(when (zero? %) %) (rest nums))]
    (throw (ex-info "Division by zero"
                    {:type :execution-error/division-by-zero
                     :nums nums
                     :zero-at zero-divisor})))
  (check-numeric-result! (apply / nums) :div nums))


(defbase mod-fn [dividend divisor]
  (when (zero? divisor)
    (throw (ex-info "Modulo by zero"
                    {:type :execution-error/modulo-by-zero
                     :dividend dividend :divisor divisor})))
  (mod dividend divisor))


(defbase neg [number]
  (- number))


(defbase abs-fn [number]
  (abs number))


;; === Comparison ===

(defbase eq [values]
  (apply = values))


(defbase neq [values]
  (apply not= values))


(defbase lt [nums]
  (apply < nums))


(defbase lte [nums]
  (apply <= nums))


(defbase gt [nums]
  (apply > nums))


(defbase gte [nums]
  (apply >= nums))


;; === Registry ===

(def impls
  "Map of fn-name → impl-fn"
  {:add add
   :sub sub
   :mul mul
   :div div
   :mod mod-fn
   :neg neg
   :abs abs-fn
   :eq eq
   :neq neq
   :lt lt
   :lte lte
   :gt gt
   :gte gte})
