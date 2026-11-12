(ns graphden.storage.postgres.source-projection
  "Physical structural columns only, under an active owned RR snapshot."
  (:require
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.read-plan :as plan]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.source-projection :as projection]
    [honey.sql :as sql]))


(defn- physical-columns
  [connection entity]
  (let [cache (:shapes (grouped/snapshot-cache connection))]
    (or (get @cache entity)
        (let [rows (util/exec! connection
                               ["SELECT attname FROM pg_attribute WHERE attrelid = to_regclass(?) AND attnum > 0 AND NOT attisdropped"
                                (util/kw->snake-case entity)])
              columns (mapv (comp keyword :attname) rows)]
          (when-not (seq columns) (projection/unavailable!))
          (swap! cache assoc entity columns)
          columns))))


(defn- chunks
  [{:keys [op ids where] :as request}]
  (let [capacity (dec util/*max-statement-params*)]
    (when (< capacity 2) (projection/unavailable!))
    (case op
      :read-ids (map #(assoc request :ids (vec %)) (partition-all capacity (distinct (seq ids))))
      :query (let [id-field (first (remove #{:branch-id} (keys where)))
                   branches (distinct (seq (:branch-id where)))
                   branch-size (max 1 (min (count branches) (quot capacity 2)))]
               (for [branch-chunk (partition-all branch-size branches)
                     id-chunk (partition-all (- capacity (count branch-chunk)) (distinct (seq (get where id-field))))]
                 (assoc request :where {id-field (vec id-chunk) :branch-id (vec branch-chunk)}))))))


(defn read-projection
  [storage connection fields-for {:keys [entity fields max-rows] :as request}]
  (projection/validate-physical! storage request)
  (let [columns (set (physical-columns connection entity))
        physical #(keyword (util/kw->snake-case %))
        optional #{:fn-id}
        selected (mapv (fn [field]
                         (let [column (physical field)]
                           (cond (contains? columns column) column
                                 (contains? optional field) [[:inline nil] column]
                                 :else (projection/unavailable!)))) fields)
        field-specs (fields-for entity)]
    (reduce
      (fn [result {:keys [op ids where] :as chunk}]
        (projection/validate-physical! storage chunk)
        (let [remaining (- max-rows (count result))
              query (case op
                      :read-ids (plan/by-ids entity ids)
                      :query (plan/query entity where field-specs nil))
              query (assoc query :select selected :limit (inc remaining))
              rows (util/exec! connection (sql/format query {:quoted true}))]
          (when (> (count rows) remaining) (projection/unavailable!))
          (into result (map #(codec/row->entity % (select-keys field-specs fields))) rows)))
      [] (chunks request))))
