(ns graphden.executor.source-recipe-jdbc-rows
  "Known next.jdbc row metadata without retaining its connection closures.
   Only the trace's canonical producer/option contract may use this adapter;
   recognizing callback classes alone never establishes authority."
  (:require
    [graphden.executor.source-recipe-values :as values]
    [next.jdbc.result-set :as rs]))


(deftype ^:private Rows
  [frozen metadata-flags one? charge])


(deftype ^:private ColumnarRows
  [frozen charge])


(def ^:private row-classes
  #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap})


(def ^:private metadata-classes
  ;; Obtain implementation class facts through the public producer, without
  ;; retaining either specimen or callbacks. No connection is involved.
  (update-vals (meta (rs/datafiable-row (hash-map) nil (hash-map))) class))


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
           (every? (fn [[key value]]
                     ;; Admit keys before lookup: unknown keys must not run
                     ;; custom hashing while metadata is being rejected.
                     (and (= clojure.lang.Symbol (class key))
                          (= (get metadata-classes key ::unknown) (class value))))
                   metadata)) true
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


(defn- column-keys
  [row]
  (mapv (fn [[key _]]
          (when-not (or (nil? key) (keyword? key) (= String (class key)))
            (reject! :jdbc-column-key))
          key)
        row))


(defn- columnar-layout
  [rows flags max-bytes]
  (reduce (fn [{:keys [dictionary schemas records overhead]} [row datafiable?]]
            (let [overhead (+' overhead 512 (*' 64 (count row)))]
              (when (>= overhead max-bytes) (reject! :byte-limit))
              ;; Only admitted scalar keys reach this dictionary's hashing.
              (let [columns (column-keys row)
                    schema-id (get dictionary columns (count schemas))
                    cells (mapv val row)
                    present (mapv some? cells)
                    schemas (if (= schema-id (count schemas))
                              (conj schemas (vector columns present))
                              (update-in schemas [schema-id 1]
                                         #(mapv (fn [old new] (or old new)) % present)))]
                {:dictionary (assoc dictionary columns schema-id)
                 :schemas schemas
                 :records (conj records (vector schema-id datafiable? cells))
                 :overhead overhead})))
          {:dictionary {} :schemas [] :records [] :overhead 512}
          (map vector rows flags)))


(defn freeze-columnar
  "Copy a multirow JDBC vector using one key dictionary per exact row schema.
   Columns nil in every row of a schema retain explicit key presence without
   storing a nil cell per row. The complete encoded payload is charged once,
   plus conservative per-row map/navigation reconstruction headroom."
  [result max-bytes]
  (when-not (= clojure.lang.PersistentVector (class result))
    (reject! :jdbc-result-shape))
  (when (some? (meta result)) (reject! :jdbc-result-metadata))
  (when-not (and (integer? max-bytes) (pos? max-bytes))
    (throw (ex-info "Source recipe reservation must be positive" {})))
  (when (>= (+' 512 (*' 512 (count result))) max-bytes)
    (reject! :byte-limit))
  ;; Admit ALL row classes and known metadata before inspecting any row keys.
  (let [flags (mapv metadata-kind result)
        {:keys [schemas records overhead]} (columnar-layout result flags max-bytes)
        schemas (mapv (fn [[columns present]]
                        (vector columns (into (vector) (keep-indexed (fn [index live?]
                                                                       (when live? index))) present)))
                      schemas)
        records (mapv (fn [[schema-id datafiable? cells]]
                        (vector schema-id datafiable?
                                (mapv #(nth cells %) (second (nth schemas schema-id)))))
                      records)]
    (when (>= overhead max-bytes) (reject! :byte-limit))
    ;; Instrumented collection literals can carry source-location metadata.
    ;; Build this owned encoding without adding metadata to admitted values.
    (let [frozen (values/freeze (vector schemas records) (- max-bytes overhead))]
      (ColumnarRows. frozen (+ overhead (values/charge-bytes frozen))))))


(defn- thaw-columnar
  [frozen connection opts]
  (let [[schemas records] (values/thaw frozen)]
    (mapv (fn [[schema-id datafiable? cells]]
            (let [[columns indices] (nth schemas schema-id)
                  row (reduce (fn [row [index value]]
                                (assoc row (nth columns index) value))
                              (zipmap columns (repeat nil))
                              (map vector indices cells))]
              (if datafiable? (rs/datafiable-row row connection opts) row)))
          records)))


^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn thaw
  "Fresh mutable leaves and fresh datafy/nav closures bound to THIS connection
   and options. A prior snapshot's closure is never copied into a new lease."
  [rows connection opts]
  (cond
    (instance? ColumnarRows rows)
    (thaw-columnar (.-frozen ^ColumnarRows rows) connection opts)

    (instance? Rows rows)
    (let [result (mapv (fn [row datafiable?]
                         (if datafiable? (rs/datafiable-row row connection opts) row))
                       (values/thaw (.-frozen ^Rows rows)) (.-metadata-flags ^Rows rows))]
      (if (.-one? ^Rows rows) (first result) result))

    :else (reject! :frozen-rows-missing)))


;; The field spelling belongs to two unrelated generated classes. Merging
;; these typed branches would replace direct field access with reflection.
^{:splint/disable [lint/prefer-method-values lint/identical-branches]}
(defn charge-bytes
  [rows]
  (cond
    (instance? Rows rows) (.-charge ^Rows rows)
    (instance? ColumnarRows rows) (.-charge ^ColumnarRows rows)
    :else (reject! :frozen-rows-missing)))
