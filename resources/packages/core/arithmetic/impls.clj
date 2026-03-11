(ns graphden.packages.core.arithmetic.impls
  "Implementations for core/arithmetic base functions.

   All functions receive already-dereferenced arguments.
   The loader handles deref before calling these implementations.")


;; === Helpers ===

(defn- check-numeric-result!
  "Validates that result is not Infinity or NaN."
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

(defn add
  [{:keys [nums]}]
  (check-numeric-result! (apply + nums) :add nums))


(defn sub
  [{:keys [nums]}]
  (when (empty? nums)
    (throw (ex-info "Subtraction requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  (check-numeric-result! (apply - nums) :sub nums))


(defn mul
  [{:keys [nums]}]
  (check-numeric-result! (apply * nums) :mul nums))


(defn div
  [{:keys [nums]}]
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


(defn mod-fn
  [{:keys [a b]}]
  (when (zero? b)
    (throw (ex-info "Modulo by zero"
                    {:type :execution-error/modulo-by-zero
                     :a a :b b})))
  (mod a b))


(defn neg
  [{:keys [n]}]
  (- n))


(defn abs-fn
  [{:keys [n]}]
  (abs n))


;; === Comparison ===

(defn eq
  [{:keys [values]}]
  (apply = values))


(defn neq
  [{:keys [values]}]
  (apply not= values))


(defn lt
  [{:keys [nums]}]
  (apply < nums))


(defn lte
  [{:keys [nums]}]
  (apply <= nums))


(defn gt
  [{:keys [nums]}]
  (apply > nums))


(defn gte
  [{:keys [nums]}]
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
