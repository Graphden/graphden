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
  [{:keys [value]}]
  (not value))


(defn some?-fn
  [{:keys [value]}]
  (some? value))


(defn nil?-fn
  [{:keys [value]}]
  (nil? value))


;; === Conditionals ===

(defn if-fn
  [{:keys [test then else]}]
  ;; then/else are lazy (SmartDelay) — only deref the chosen branch
  (if test
    (if (instance? clojure.lang.IDeref then) @then then)
    (if (instance? clojure.lang.IDeref else) @else else)))


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


(defn case-fn
  "Dispatches on value. Clauses is a map {match-value result ...}.
   Returns result for matching value, or default if no match."
  [{:keys [value clauses default]}]
  (get clauses value default))


;; === Defaults ===

(defn coalesce
  [{:keys [value default]}]
  (or value default))


;; === Constants ===

(defn const
  [{:keys [value]}]
  value)


;; === Registry ===

(def impls
  "Map of fn-name → impl-fn"
  {:and and-fn
   :or or-fn
   :not not-fn
   :some? some?-fn
   :nil? nil?-fn
   :if if-fn
   :cond cond-fn
   :case case-fn
   :coalesce coalesce
   :const const
   :equal? (fn [{:keys [a b]}] (= a b))})
