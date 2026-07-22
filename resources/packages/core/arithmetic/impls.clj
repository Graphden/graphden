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


(defn- safe-arith
  "Apply `f` to `nums`, normalising BOTH overflow modes to a typed
   `:execution-error/numeric-overflow`. Long overflow surfaces as a raw
   `ArithmeticException` from `+`/`-`/`*` BEFORE `check-numeric-result!`'s
   double infinity/NaN check ever runs, so catch it here and re-wrap with
   the same `:operation` / `:num-count` context the double path provides."
  [f nums operation]
  (let [result (try (apply f nums)
                    (catch ArithmeticException e
                      (throw (ex-info (str "Arithmetic overflow (" (name operation) "): "
                                           (ex-message e))
                                      {:type :execution-error/numeric-overflow
                                       :operation operation
                                       :num-count (count nums)}))))]
    (check-numeric-result! result operation nums)))


;; === Arithmetic ===

(defbase add [nums]
  (safe-arith + nums :add))


(defbase sub [nums]
  (when (empty? nums)
    (throw (ex-info "Subtraction requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  (safe-arith - nums :sub))


(defbase mul [nums]
  (safe-arith * nums :mul))


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


(defn add-return-rule
  [b d]
  (narrow-numeric-to-int b d))


(defn sub-return-rule
  [b d]
  (narrow-numeric-to-int b d))


(defn mul-return-rule
  [b d]
  (narrow-numeric-to-int b d))


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
  {:add {:impl add :return-type-rule add-return-rule :taint-propagate? true}
   :sub {:impl sub :return-type-rule sub-return-rule :taint-propagate? true}
   :mul {:impl mul :return-type-rule mul-return-rule :taint-propagate? true}
   :div {:impl div :taint-propagate? true}
   :mod {:impl mod-fn :return-type-rule mod-return-rule :taint-propagate? true}
   :neg {:impl neg :return-type-rule neg-return-rule :taint-propagate? true}
   :abs {:impl abs-fn :return-type-rule abs-return-rule :taint-propagate? true}
   :eq {:impl eq :taint-propagate? true}
   :neq {:impl neq :taint-propagate? true}
   :lt {:impl lt :taint-propagate? true}
   :lte {:impl lte :taint-propagate? true}
   :gt {:impl gt :taint-propagate? true}
   :gte {:impl gte :taint-propagate? true}})
