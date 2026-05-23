(ns graphden.packages.core.hof.impls
  "Implementations for core/hof base functions.

   `:fn`-type args arrive as already-wrapped single-arg callables (via
   compile.clj/hof-wrap under the new executor, or via make-single-arg-
   callable under the legacy queue — both normalised by the loader)."
  (:refer-clojure :exclude [some-fn])
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase map-fn [func coll]
  (if coll
    (map func coll)
    (map func)))


(defbase filter-fn [pred coll]
  (if coll
    (filter pred coll)
    (filter pred)))


(defbase reduce-fn [func init coll]
  ;; reduce passes [acc item] as single vector to the function
  (reduce (fn [acc item] (func [acc item])) init coll))


(defbase some-fn [pred coll]
  (some pred coll))


(defbase every?-fn [pred coll]
  (every? pred coll))


(defbase find-first [pred coll]
  (some #(when (pred %) %) coll))


(defbase group-by-fn [key-fn coll]
  (group-by key-fn coll))


(defbase sort-by-fn [key-fn coll]
  (vec (sort-by key-fn coll)))


(defbase constantly-fn [value _item]
  value)


(defbase comp-fn [functions]
  (apply comp functions))


(defbase transduce-fn [transducer reducer init coll]
  (transduce transducer
             (fn
               ([acc] acc)
               ([acc item] (reducer [acc item])))
             init coll))


;; === Registry ===

(def impls
  {:map map-fn
   :filter filter-fn
   :reduce reduce-fn
   :some some-fn
   :every? every?-fn
   :find-first find-first
   :group-by group-by-fn
   :sort-by sort-by-fn
   :constantly constantly-fn
   :comp comp-fn
   :transduce transduce-fn})
