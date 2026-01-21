(ns graphden.base-functions.core
  "Base function definitions.

   This component defines the standard library of base functions:
   - Arithmetic: add, sub, mul, div, mod, neg, abs
   - Comparison: eq, neq, lt, lte, gt, gte
   - Logic: and, or, not
   - Conditionals: if, cond
   - Strings: str, subs, str-len, str-upper, str-lower, str-trim, str-split, str-join
   - Collections: first, rest, cons, conj, get, assoc, dissoc, count, empty?, etc.
   - HOF: map, filter, reduce, some, every?, find-first, group-by, sort-by, apply

   All functions are defined using the defbase macro which handles
   automatic argument dereferencing. Arguments are passed as delays
   and automatically deref'd. Arguments with :fn type are automatically
   wrapped as callables via make-single-arg-callable.

   ## Optional Arguments Convention

   Optional arguments MUST use the map form with explicit :required false:

     {:args {:x :int                              ; required (shorthand)
             :y {:type :int :required false}}}    ; optional (explicit)

   Do NOT use shorthand for optional args - always use the explicit map form.
   This ensures clarity about which arguments are optional.

   In the function body, use `or` with default value:
     (let [actual-y (or y 0)] ...)

   Registration and storage sync should be done by consuming components
   using fn-registry."
  (:require
    [graphden.base-functions.arithmetic :as arithmetic]
    [graphden.base-functions.collections :as collections]
    [graphden.base-functions.hof :as hof]
    [graphden.base-functions.logic :as logic]
    [graphden.base-functions.strings :as strings]))


;; Re-export individual def maps for consumers who want subsets
(def arithmetic-defs arithmetic/arithmetic-defs)
(def comparison-defs arithmetic/comparison-defs)
(def logic-defs logic/logic-defs)
(def conditional-defs logic/conditional-defs)
(def string-defs strings/string-defs)
(def collection-defs collections/collection-defs)
(def hof-defs hof/hof-defs)


;; All definitions merged
(def all-defs
  "All base function definitions as a map of {fn-name -> fn-def}."
  (merge arithmetic-defs
         comparison-defs
         logic-defs
         conditional-defs
         string-defs
         collection-defs
         hof-defs))
