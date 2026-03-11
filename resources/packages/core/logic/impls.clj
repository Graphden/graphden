(ns graphden.packages.core.logic.impls
  "Implementations for core/logic base functions.

   All functions receive already-dereferenced arguments.
   The loader handles deref before calling these implementations.")


;; === Logic ===

(defn and-fn
  [{:keys [values]}]
  (every? identity values))


(defn or-fn
  [{:keys [values]}]
  (boolean (some identity values)))


(defn not-fn
  [{:keys [x]}]
  (not x))


;; === Conditionals ===

(defn if-fn
  [{:keys [test then else]}]
  (if test then else))


(defn cond-fn
  "Evaluates clauses as [[test1 result1] [test2 result2] ...].
   Returns first result where test is truthy, or nil if none match."
  [{:keys [clauses]}]
  (loop [remaining clauses]
    (when (seq remaining)
      (let [[test result] (first remaining)]
        (if test
          result
          (recur (rest remaining)))))))


;; === Constants ===

(defn const
  [{:keys [x]}]
  x)


(defn identity-fn
  [{:keys [x]}]
  x)


;; === Registry ===

(def impls
  "Map of fn-name → impl-fn"
  {:and and-fn
   :or or-fn
   :not not-fn
   :if if-fn
   :cond cond-fn
   :const const
   :identity identity-fn})
