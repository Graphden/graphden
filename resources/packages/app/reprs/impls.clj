(ns graphden.packages.app.reprs.impls
  "Implementations for the app/reprs base functions — the typed
   value-representation resolver shims (delegating to
   `graphden.crud.value-repr`, mirroring how app/forms delegates to
   `crud.value-form`) plus the sparkline geometry primitive."
  (:require
    [cheshire.core :as cheshire]
    [clojure.math :as math]
    [clojure.string :as str]
    [graphden.crud.value-repr :as value-repr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase fn-return-type-fn
  "Declared/inferred return type of fn `fn-id` from the id-keyed
   rich-types registry — nil when unknown. §3.1 library boundary."
  [fn-id]
  (value-repr/declared-return-type fn-id))


(defbase render-value-repr
  "Resolve + purity-check + execute + sanitize the registered repr of
   `value` (see `crud.value-repr/render-repr`). Kept atomic like
   `:build-form`: the dispatch pipeline is the safety boundary; the
   extensibility seam is the `:_value-repr-registry` fn-def."
  [value fn-id]
  (value-repr/render-repr ctx value fn-id))


(defn- cell-str
  "Display string for one table cell — simple scalars verbatim,
   anything structured as its JSON."
  [v]
  (if (or (nil? v) (string? v) (number? v) (boolean? v) (keyword? v) (uuid? v))
    (str (if (keyword? v) (name v) v))
    (cheshire/generate-string v)))


(defbase tabulate-records
  "Project `records` onto ordered `columns` as display strings —
   `[[cell …] …]`, one row per record, a missing key an empty cell.
   Pure tabular projection (the record-table repr's data half; the
   hiccup half stays in the graph)."
  [records columns]
  (mapv (fn [r] (mapv #(cell-str (get r %)) columns)) records))


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
;; `:render-value-repr` propagates marker taint (SECRETS.md § T3): its
;; `:value` slot is `:any`, so a `[:secret T]` CAN reach it, and the
;; returned hiccup derives from that content — the flag keeps the
;; output marked so hide-at-sink still fires. The other two can't
;; carry taint: `:fn-return-type` passes an id, not content, and
;; `:svg-polyline-points`' `[:list :numeric]` slot rejects
;; secret-marked input outright (same rationale as `:h-raw`).
(def impls
  {:fn-return-type fn-return-type-fn
   :render-value-repr {:impl render-value-repr :taint-propagate? true}
   ;; content-passing (record values → cell strings) — SECRETS.md § T3
   :tabulate-records {:impl tabulate-records :taint-propagate? true}
   :svg-polyline-points svg-polyline-points})
