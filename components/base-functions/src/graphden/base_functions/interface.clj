(ns graphden.base-functions.interface
  "Base function definitions for graphden executor.

   Provides definitions for fundamental operations:
   - Arithmetic: add, sub, mul, div, mod, neg, abs
   - Comparison: eq, neq, lt, lte, gt, gte
   - Logic: and, or, not
   - Conditionals: if, cond
   - Strings: str, subs, str-len, str-upper, str-lower, str-trim, str-split, str-join
   - Collections: first, rest, cons, conj, get, assoc, dissoc, count, empty?, etc.
   - HOF: map, filter, reduce, some, every?, find-first, group-by, sort-by, apply

   Registration and storage sync should be done by consuming components
   using fn-registry."
  (:require
    [graphden.base-functions.core :as core]))


(defn get-all-defs
  "Returns all base function definitions with metadata.
   Each entry is {fn-name {:args {...} :return-type :type :impl fn}}.

   Use fn-registry/register-base-fns! to register these functions.
   Use fn-registry/sync-defs-to-storage! to sync to storage."
  []
  (core/get-all-defs))
