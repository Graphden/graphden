(ns graphden.base-functions.interface
  "Base functions for the graphden executor.

   Provides fundamental operations:
   - Arithmetic: add, sub, mul, div, mod, neg, abs
   - Comparison: eq, neq, lt, lte, gt, gte
   - Logic: and, or, not
   - Conditionals: if, cond
   - Strings: str, subs, str-len, str-upper, str-lower, str-trim
   - Collections: first, rest, cons, conj, get, assoc, dissoc, count, empty?
   - HOF: map, filter, reduce, comp

   All functions operate on thunks and use lazy evaluation."
  (:require
    [graphden.base-functions.core :as core]))


;; === Registration ===

(defn register-arithmetic!
  "Registers arithmetic base functions: add, sub, mul, div, mod, neg, abs."
  []
  (core/register-arithmetic!))


(defn register-comparison!
  "Registers comparison functions: eq, neq, lt, lte, gt, gte."
  []
  (core/register-comparison!))


(defn register-logic!
  "Registers logic functions: and, or, not."
  []
  (core/register-logic!))


(defn register-conditionals!
  "Registers conditional functions: if, cond."
  []
  (core/register-conditionals!))


(defn register-strings!
  "Registers string functions: str, subs, str-len, str-upper, str-lower, str-trim."
  []
  (core/register-strings!))


(defn register-collections!
  "Registers collection functions: first, rest, cons, conj, get, assoc, dissoc, count, empty?."
  []
  (core/register-collections!))


(defn register-hof!
  "Registers higher-order functions: map, filter, reduce, comp."
  []
  (core/register-hof!))


(defn register-all!
  "Registers all base functions.
   Convenience function for setting up the complete function library."
  []
  (core/register-all!))
