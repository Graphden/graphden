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


(defn read-projection
  [storage connection fields-for {:keys [op entity fields ids where max-rows] :as request}]
  (projection/validate-physical! storage request)
  (let [columns (set (physical-columns connection entity))
        physical #(keyword (util/kw->snake-case %))
        optional #{:fn-id}
        selected (mapv (fn [field]
                         (let [column (physical field)]
                           (cond (contains? columns column) column
                                 (contains? optional field) [[:inline nil] column]
                                 :else (projection/unavailable!)))) fields)
        field-specs (fields-for entity)
        query (case op
                :read-ids (plan/by-ids entity ids)
                :query (plan/query entity where field-specs nil))
        query (assoc query :select selected :limit (inc max-rows))
        rows (util/exec! connection (sql/format query {:quoted true}))]
    (when (> (count rows) max-rows) (projection/unavailable!))
    (mapv #(codec/row->entity % (select-keys field-specs fields)) rows)))
