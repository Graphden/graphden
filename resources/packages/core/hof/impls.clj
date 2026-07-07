(ns graphden.packages.core.hof.impls
  "Implementations for core/hof base functions.

   `:fn`-type args arrive as already-wrapped single-arg callables (via
   compile.clj/hof-wrap under the new executor, or via make-single-arg-
   callable under the legacy queue — both normalised by the loader)."
  (:refer-clojure :exclude [some-fn])
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase map-fn [func coll]
  ;; EAGER (`doall`) — a bare `(map …)` returns an UNREALIZED lazy seq that
  ;; escapes the future's `*effect-trace*` / `*allowed-effects*` /
  ;; `*cancel-check*` bindings and gets realized later during result
  ;; JSON-encoding: a throwing callback is then caught by the size-cap
  ;; encoder and mislabeled `:succeeded nil`, and effects run ungated +
  ;; untraced. `doall` realizes every per-element callback inside the bound
  ;; execution scope. It must stay a SEQ (not `mapv` → vector): hiccup
  ;; splices a seq of children but treats a vector as a single `[tag attrs]`
  ;; element, so `[:div (:map …)]` would try the first child as the tag.
  ;; Streaming stays on `:map-xf`. nil coll → `(map f nil)` → `()`.
  (doall (map func coll)))


(defbase filter-fn [pred coll]
  ;; EAGER (`doall`), SEQ-returning — same reasoning as `map-fn` (effect
  ;; scope + hiccup splicing); streaming is `:filter-xf`.
  (doall (filter pred coll)))


(defbase map-xf-fn [func]
  ;; Transducer-only form — use via `:transduce` or `:comp`.
  (map func))


(defbase filter-xf-fn [pred]
  ;; Transducer-only form — see `map-xf-fn`.
  (filter pred))


(defbase reduce-fn [func init coll]
  ;; reduce passes [acc item] as single vector to the function
  (reduce (fn [acc item] (func [acc item])) init coll))


(defbase some-fn [pred coll]
  (some pred coll))


(defbase every?-fn [pred coll]
  (every? pred coll))


(defbase find-first [pred coll]
  ;; `(some #(when (pred %) %) …)` skips PAST a matching element that is
  ;; itself falsy (nil/false) — `some` reads its falsy return as "keep
  ;; going" — and returns a later element or nil. `reduced` returns the
  ;; actual first match, falsy or not.
  (reduce (fn [acc x] (if (pred x) (reduced x) acc)) nil coll))


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
   :map-xf map-xf-fn
   :filter-xf filter-xf-fn
   :reduce reduce-fn
   :some some-fn
   :every? every?-fn
   :find-first find-first
   :group-by group-by-fn
   :sort-by sort-by-fn
   :constantly constantly-fn
   :comp comp-fn
   :transduce transduce-fn})
