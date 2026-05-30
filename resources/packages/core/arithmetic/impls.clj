(ns graphden.packages.core.arithmetic.impls
  "Implementations for core/arithmetic base functions.

   Written with the new `defbase` macro — arg symbols resolve lazily at
   use site, short-circuit evaluation preserved, and HOF `:fn` args
   are auto-wrapped by the runtime. See `graphden.executor.defbase`.

   Each base-fn's type-rule (moved verbatim from
   `graphden.types.rules`) lives here as a plain `defn` and is wired
   into the `impls` map as `{:impl … :return-type-rule …}`."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.types.core :as types]))


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


;; === Type-rules ===
;; Arithmetic narrowing — when every input is `:int`, the result of an
;; integer-preserving op stays `:int`. Falls back to the parent's
;; declared :numeric otherwise.
;;
;; :div is omitted: in Clojure `(/ 10 3)` returns a rational, which
;; we represent as :numeric. Narrowing it to :int would be wrong.

(defn- nums-elem-type
  "When `:nums` (the sequence-arg every arithmetic base-fn carries)
   is bound to a typed list, return the element type. Otherwise nil."
  [bindings-info]
  (let [t (get-in bindings-info [:nums :type])]
    (when (types/list-type? t) (types/list-elem t))))


(defn- narrow-numeric-to-int
  [bindings-info default-ret]
  (if (= :int (nums-elem-type bindings-info)) :int default-ret))


(defn add-return-rule [b d] (narrow-numeric-to-int b d))
(defn sub-return-rule [b d] (narrow-numeric-to-int b d))
(defn mul-return-rule [b d] (narrow-numeric-to-int b d))


(defn mod-return-rule
  [bindings-info default-ret]
  ;; :mod takes named scalar args, not :nums.
  (let [a (get-in bindings-info [:dividend :type])
        b (get-in bindings-info [:divisor :type])]
    (if (and (= :int a) (= :int b)) :int default-ret)))


(defn neg-return-rule
  [bindings-info default-ret]
  (if (= :int (get-in bindings-info [:number :type])) :int default-ret))


(defn abs-return-rule
  [bindings-info default-ret]
  (if (= :int (get-in bindings-info [:number :type])) :int default-ret))


;; === Registry ===
;; A value is either a bare impl fn or a `{:impl … :*-rule …}` map.

;; Numeric ops: secrets are rarely numbers, but a secret-int passing
;; through `:add` / `:eq` / `:lt` still leaks via the result. All
;; content-passing; bool predicates included since `(eq secret 42)`
;; tells you what the secret IS.
(def impls
  {:add {:impl add :return-type-rule (types/wrap-with-taint add-return-rule)}
   :sub {:impl sub :return-type-rule (types/wrap-with-taint sub-return-rule)}
   :mul {:impl mul :return-type-rule (types/wrap-with-taint mul-return-rule)}
   :div {:impl div :return-type-rule (types/wrap-with-taint nil)}
   :mod {:impl mod-fn :return-type-rule (types/wrap-with-taint mod-return-rule)}
   :neg {:impl neg :return-type-rule (types/wrap-with-taint neg-return-rule)}
   :abs {:impl abs-fn :return-type-rule (types/wrap-with-taint abs-return-rule)}
   :eq {:impl eq :return-type-rule (types/wrap-with-taint nil)}
   :neq {:impl neq :return-type-rule (types/wrap-with-taint nil)}
   :lt {:impl lt :return-type-rule (types/wrap-with-taint nil)}
   :lte {:impl lte :return-type-rule (types/wrap-with-taint nil)}
   :gt {:impl gt :return-type-rule (types/wrap-with-taint nil)}
   :gte {:impl gte :return-type-rule (types/wrap-with-taint nil)}})
