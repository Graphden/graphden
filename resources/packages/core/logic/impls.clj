(ns graphden.packages.core.logic.impls
  "Implementations for core/logic base functions.

   Migrated to `defbase` — arg symbols resolve at use site via the runtime
   helper. Clojure's native short-circuit evaluation (`if`, `and`, `or`,
   `cond`) handles laziness for conditional args without any `:lazy` flag
   inspection: references in unchosen branches simply never run."
  (:require
    [graphden.executor.defbase :refer [defbase]]))


;; === Logic ===

(defbase and-fn [values]
  (every? identity values))


(defbase or-fn [values]
  (boolean (some identity values)))


(defbase not-fn [value]
  (not value))


(defbase some?-fn [value]
  (some? value))


(defbase nil?-fn [value]
  (nil? value))


;; === Conditionals ===

(defbase if-fn
  "Lazy if: only the chosen branch's ref-thunk is invoked. Clojure's
   native `if` guarantees only one branch's arg reference is evaluated,
   and `rt/resolve-arg` (injected by the macro) handles both new-style
   thunks and legacy IDeref delays."
  [test then else]
  (if test then else))


(defbase cond-fn
  "Evaluates clauses as [[test1 result1] [test2 result2] ...].
   Returns first result where test is truthy, or nil if none match."
  [clauses]
  (loop [remaining clauses]
    (when (seq remaining)
      (let [[test result] (first remaining)]
        (if test
          result
          (recur (rest remaining)))))))


(defbase case-fn
  "Dispatches on value. Clauses is a map {match-value result ...}.
   Returns result for matching value, or default if no match."
  [value clauses default]
  (get clauses value default))


;; === Defaults ===

(defbase coalesce [value default]
  (or value default))


;; === Constants ===

(defbase const [value]
  value)


(defbase equal?-fn [a b]
  (= a b))


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
   :equal? equal?-fn})
