(ns graphden.library.base-fns.core.hof
  "Higher-order base functions.

   Functions: map, filter, reduce, some, every?, find-first, group-by,
              sort-by, apply, identity, constantly, comp, transduce

   ## Transducer Support

   HOFs like map, filter support two modes via Clojure's multi-arity pattern:
   - With coll: `(map f coll)` returns transformed collection
   - Without coll: `(map f)` returns transducer

   This enables efficient composition:
   ```clojure
   ;; Composed transducer - single pass over data
   (transduce (comp (filter pred) (map f)) conj [] coll)
   ```

   Note: :fn type args are automatically wrapped as callables by defbase.
   User functions must have exactly 1 required argument (any name).
   For reduce, user function takes a single arg which receives [acc item] vector."
  (:require
    [graphden.executor.registry.macros :refer [defbase]]))


(defbase map-fn
  "Applies f to each element of coll.
   With coll: returns lazy sequence of results.
   Without coll: returns transducer."
  {:args {:f :fn
          :coll {:type :jsonb :required false}}
   :return-type :any}
  (if coll
    (map f coll)
    (map f)))


(defbase filter-fn
  "Returns elements of coll for which pred returns truthy.
   With coll: returns lazy sequence of matching elements.
   Without coll: returns transducer."
  {:args {:pred :fn
          :coll {:type :jsonb :required false}}
   :return-type :any}
  (if coll
    (filter pred coll)
    (filter pred)))


(defbase reduce-fn
  {:args {:f :fn, :init :any, :coll :jsonb}
   :return-type :any}
  ;; reduce passes [acc item] as single vector to the function
  (reduce (fn [acc item] (f [acc item])) init coll))


(defbase some-base-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :any}
  (some (fn [item]
          (when-let [result (pred item)]
            result))
        coll))


(defbase every?-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :bool}
  (every? pred coll))


(defbase find-first-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :any}
  (some #(when (pred %) %) coll))


(defbase group-by-fn
  {:args {:key-fn :fn, :coll :jsonb}
   :return-type :jsonb}
  (group-by key-fn coll))


(defbase sort-by-fn
  {:args {:key-fn :fn, :coll :jsonb}
   :return-type :jsonb}
  (vec (sort-by key-fn coll)))


(defbase apply-fn
  {:args {:f :fn, :args :jsonb}
   :return-type :any}
  (f args))


(defbase identity-fn
  {:args {:x :any}
   :return-type :any}
  x)


(defbase constantly-fn
  "Returns x, ignoring the optional _item argument.
   When used with HOF (map, filter, etc.), always returns x regardless of input.

   Examples:
   - Direct: (constantly {:x 42}) => 42
   - With map: (map {:f constantly, :coll [1 2 3]}) where constantly has x=42 => [42 42 42]"
  {:args {:x :any
          :_item {:type :any :required false}}
   :return-type :any}
  x)


(defbase const-fn
  "Returns a function that always returns x, ignoring its argument.
   Like Clojure's constantly but returns an actual function.

   Use this when you need a function value (e.g., as a Ring handler)
   that always returns the same response.

   Example:
   const-fn with x={:status 200 :body \"ok\"} returns (fn [_] {:status 200 :body \"ok\"})"
  {:args {:x :any}
   :return-type :fn}
  (fn [_] x))


;; === Transducer Support ===

(defbase comp-fn
  "Composes functions/transducers from left to right.
   Takes a vector of functions/transducers and returns their composition.

   When used with transducers, note that comp applies right-to-left,
   but for transducers this means data flows left-to-right:
   (comp (filter odd?) (map inc)) - filters first, then maps.

   Example:
   (comp [(filter odd?) (map inc)]) -> composed transducer"
  {:args {:fns :jsonb}
   :return-type :any}
  (apply comp fns))


(defbase transduce-fn
  "Reduces coll using transducer xf, reducing function rf, and initial value init.
   Transforms and reduces in a single pass - more efficient than separate steps.

   The rf function receives [acc item] as a single argument (same as reduce).

   Example:
   (transduce (comp (filter odd?) (map inc)) + 0 [1 2 3 4 5])
   -> 12  ; (+ 0 2 4 6) - filters [1 3 5], maps to [2 4 6], sums"
  {:args {:xf :any
          :rf :fn
          :init :any
          :coll :jsonb}
   :return-type :any}
  ;; transduce calls rf with 1 arg (completion) and 2 args (reduce step)
  ;; We wrap to support both arities, passing [acc item] as single vector
  (transduce xf
             (fn
               ([acc] acc)
               ;; completion step - just return accumulator

               ([acc item] (rf [acc item])))
             init coll))


;; === Exports ===

(def hof-defs
  {:map map-fn
   :filter filter-fn
   :reduce reduce-fn
   :some some-base-fn
   :every? every?-fn
   :find-first find-first-fn
   :group-by group-by-fn
   :sort-by sort-by-fn
   :apply apply-fn
   :identity identity-fn
   :constantly constantly-fn
   :const const-fn
   :comp comp-fn
   :transduce transduce-fn})
