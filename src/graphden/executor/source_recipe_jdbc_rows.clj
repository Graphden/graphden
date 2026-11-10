(ns graphden.executor.source-recipe-jdbc-rows
  "Known next.jdbc row metadata without retaining its connection closures.
   Only the trace's canonical producer/option contract may use this adapter;
   recognizing callback classes alone never establishes authority."
  (:require
    [graphden.executor.source-recipe-values :as values]
    [next.jdbc.result-set :as rs]))


(deftype ^:private Rows
  [frozen metadata-flags one? charge])


(def ^:private row-classes
  #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap})


(def ^:private metadata-classes
  ;; Obtain implementation class facts through the public producer, without
  ;; retaining either specimen or callbacks. No connection is involved.
  (update-vals (meta (rs/datafiable-row {} nil {})) class))


(defn- reject!
  [reason]
  (throw (ex-info "Source JDBC rows are ineligible" {:type ::values/ineligible :reason reason})))


(defn- metadata-kind
  [row]
  (when-not (contains? row-classes (class row)) (reject! :jdbc-row-shape))
  (let [metadata (meta row)]
    (cond
      (nil? metadata) false
      (and (contains? row-classes (class metadata))
           (= (count metadata-classes) (count metadata))
           (= metadata-classes (update-vals metadata class))) true
      :else (reject! :jdbc-row-metadata))))


(defn freeze
  "Remove ONLY the exact known top-level datafy/nav metadata before bounded
   generic copying. Unknown/nested metadata remains ineligible. The reservation
   includes row wrappers and flags; no callback or connection is retained."
  [result one? max-bytes]
  (when-not (or one? (= clojure.lang.PersistentVector (class result)))
    (reject! :jdbc-result-shape))
  (when (and (not one?) (some? (meta result))) (reject! :jdbc-result-metadata))
  (let [rows (if one? (if (nil? result) [] [result]) result)
        overhead (+ 512 (* 128 (count rows)))]
    (when (>= overhead max-bytes) (reject! :byte-limit))
    (let [flags (mapv metadata-kind rows)
          plain (mapv #(with-meta % nil) rows)
          frozen (values/freeze plain (- max-bytes overhead))]
      (Rows. frozen flags one? (+ overhead (values/charge-bytes frozen))))))


^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn thaw
  "Fresh mutable leaves and fresh datafy/nav closures bound to THIS connection
   and options. A prior snapshot's closure is never copied into a new lease."
  [rows connection opts]
  (when-not (instance? Rows rows) (reject! :frozen-rows-missing))
  (let [result (mapv (fn [row datafiable?]
                       (if datafiable? (rs/datafiable-row row connection opts) row))
                     (values/thaw (.-frozen ^Rows rows)) (.-metadata-flags ^Rows rows))]
    (if (.-one? ^Rows rows) (first result) result)))


^{:splint/disable [lint/prefer-method-values]}
(defn charge-bytes
  [rows]
  (when-not (instance? Rows rows) (reject! :frozen-rows-missing))
  (.-charge ^Rows rows))
