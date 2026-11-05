(ns graphden.packages.app.reprs.impls
  "Implementations for the app/reprs base functions — the typed
   value-representation resolver shims (delegating to
   `graphden.crud.value-repr`, mirroring how app/forms delegates to
   `crud.value-form`) plus the sparkline geometry primitive."
  (:require
    [clojure.math :as math]
    [clojure.string :as str]
    [graphden.crud.value-form :as value-form]
    [graphden.crud.value-repr :as value-repr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.types.core :as types]))


(defbase fn-return-type-fn
  "Declared/inferred return type of fn `fn-id` from the id-keyed
   rich-types registry — nil when unknown. §3.1 library boundary."
  [fn-id]
  (value-repr/declared-return-type fn-id))


(defbase _repr-canonical-declared-type
  "Resolve aliases in a declared type without selecting dispatch policy."
  [declared]
  (some-> declared types/resolve-alias))


(defbase _repr-runtime-type
  "Canonical runtime shape classification, preserving heterogeneous record lists."
  [value]
  (value-repr/runtime-repr-type value))


(defbase _repr-registry
  "Read the representation registry in the caller's execution context."
  []
  (value-form/registry-pairs ctx value-repr/registry-fn-name))


(defbase _repr-render-safe
  "Execute a selected typed representation with effects denied and sanitize its DOM output."
  [target value]
  (value-repr/render-selected-repr ctx target value))


(defn- fmt2
  "Locale-independent 2-decimal string for an SVG coordinate."
  [d]
  (str (/ (math/round (* 100.0 (double d))) 100.0)))


(defbase svg-polyline-points
  "SVG polyline `points` for `nums` scaled into `width` x `height` —
   min..max stretched to fit, y growing downward. Series longer than
   2 x width stride-downsampled: an output-size bound (a 100k-point
   string helps nobody at 240px), part of safely bounding the
   primitive, not composition."
  [nums width height]
  (let [xs0 (vec nums)
        step (max 1 (quot (count xs0) (* 2 (long width))))
        xs (if (> step 1) (vec (take-nth step xs0)) xs0)
        n (count xs)]
    (if (< n 2)
      ""
      (let [lo (double (reduce min (first xs) (rest xs)))
            hi (double (reduce max (first xs) (rest xs)))
            span (if (== lo hi) 1.0 (- hi lo))
            sx (/ (double width) (dec n))]
        (str/join " "
                  (map-indexed
                    (fn [i v]
                      (str (fmt2 (* i sx)) ","
                           (fmt2 (- height (* height (/ (- (double v) lo) span))))))
                    xs))))))


;; The package loader pairs each base-fn declared in `fns.edn` with its
;; impl by looking up this `impls` map (keyword name -> impl fn).
;; Repr inputs can carry caller content or a secret marker, so the
;; decode/classify/render seams propagate taint (SECRETS.md § T3).
;; `:fn-return-type` passes an id, `:_repr-registry` reads server data,
;; and numeric polyline points cannot carry secret-marked input.
(def impls
  {:fn-return-type fn-return-type-fn
   :_repr-canonical-declared-type {:impl _repr-canonical-declared-type :taint-propagate? true}
   :_repr-runtime-type {:impl _repr-runtime-type :taint-propagate? true}
   :_repr-registry _repr-registry
   :_repr-render-safe {:impl _repr-render-safe :taint-propagate? true}
   :svg-polyline-points svg-polyline-points})
