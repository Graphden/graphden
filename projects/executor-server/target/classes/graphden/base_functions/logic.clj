(ns graphden.base-functions.logic
  "Logic and conditional base functions.

   Logic: and, or, not
   Conditionals: if, cond"
  (:require
    [graphden.fn-registry.macros :refer [defbase]]))


;; === Logic ===
;; Short-circuit works naturally - deref happens at usage sites

(defbase and-fn
  {:args {:a :bool, :b :bool}
   :return-type :bool}
  (and a b))


(defbase or-fn
  {:args {:a :bool, :b :bool}
   :return-type :bool}
  (or a b))


(defbase not-fn
  {:args {:x :bool}
   :return-type :bool}
  (not x))


;; === Conditionals ===
;; Short-circuit works naturally - deref happens at usage sites

(defbase if-fn
  {:args {:condition :bool, :then :any, :else :any}
   :return-type :any}
  (if condition then else))


(defbase cond-fn
  {:args {:pairs :jsonb, :default :any}
   :return-type :any}
  ;; pairs is a vector of {:pred bool :result value}
  (loop [ps pairs]
    (if (empty? ps)
      default
      (let [{:keys [pred result]} (first ps)]
        (if pred result (recur (rest ps)))))))


;; === Exports ===

(def logic-defs
  {:and and-fn
   :or or-fn
   :not not-fn})


(def conditional-defs
  {:if if-fn
   :cond cond-fn})
