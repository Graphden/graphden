(ns graphden.library.base-fns.core.hof
  "Higher-order base functions.

   Functions: map, filter, reduce, some, every?, find-first, group-by,
              sort-by, apply, identity, constantly

   Note: :fn type args are automatically wrapped as callables by defbase.
   User functions must have exactly 1 required argument (any name).
   For reduce, user function takes a single arg which receives [acc item] vector."
  (:require
    [graphden.executor.registry.macros :refer [defbase]]))


(defbase map-fn
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb}
  (mapv f coll))


(defbase filter-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :jsonb}
  (filterv pred coll))


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
  (first (filter pred coll)))


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
   :const const-fn})
