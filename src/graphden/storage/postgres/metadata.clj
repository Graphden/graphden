(ns graphden.storage.postgres.metadata
  "Metadata table operations for schema tracking.
   Stores entity, field, enum, and enum-value UUIDs for migration support."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc])
  (:import
    (org.postgresql.util
      PGobject)))


(def ^:private metadata-table-name "_schema_metadata")


;; === Table management ===

(defn ensure-metadata-table!
  "Creates metadata table and indexes if they don't exist."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:create-table [(keyword metadata-table-name) :if-not-exists]
                              :with-columns [[:uuid :uuid [:primary-key]]
                                             [:kind :text [:not nil]]
                                             [:name :text [:not nil]]
                                             [:parent_uuid :uuid]
                                             [:extra :jsonb]]}
                             {:quoted true}))
  ;; Index on parent_uuid for faster lookups when parsing metadata
  (jdbc/execute! ds [(str "CREATE INDEX IF NOT EXISTS \"idx_" metadata-table-name "_parent_uuid\" "
                          "ON \"" metadata-table-name "\" (parent_uuid)")]))


;; === Read operations ===

(defn read-metadata-rows
  "Reads raw metadata rows for processing."
  [ds]
  (jdbc/execute! ds
                 (sql/format {:select [:uuid :kind :name :parent_uuid :extra]
                              :from [(keyword metadata-table-name)]}
                             {:quoted true})
                 (util/query-opts)))


;; === JSON conversion ===

(defn extra->json
  "Converts extra map to JSON string for PostgreSQL JSONB.
   Keywords are converted to their name strings."
  [extra]
  (when extra
    (json/generate-string
      (into {}
            (map (fn [[k v]]
                   [(name k) (if (keyword? v) (name v) v)])
                 extra)))))


(defn parse-extra
  "Parses the extra JSONB column. Handles both string and PGobject formats.
   String values are converted back to keywords."
  [extra]
  (when extra
    (let [s (cond
              (string? extra) extra
              (instance? PGobject extra) (PGobject/.getValue ^PGobject extra)
              :else (str extra))]
      (when (and (seq s) (not= s "null") (not= s "{}"))
        (let [parsed (json/parse-string s)]
          (when (map? parsed)
            (into {}
                  (map (fn [[k v]]
                         [(keyword k) (if (string? v) (keyword v) v)])
                       parsed))))))))


;; === Metadata parsing ===

(defn- handle-orphan
  "Either throw on strict mode or count + debug-log on lenient. Used
   by both `:field` and `:enum-value` rows whose `parent_uuid`
   doesn't resolve to a row in the same batch."
  [strict? counter description data]
  (if strict?
    (throw (ex-info (str "Orphaned " description " entry in metadata")
                    (assoc data :type :metadata-error/corrupted)))
    (do (swap! counter inc)
        (log/debug (str "Orphaned " description " entry in metadata, skipping") data)
        nil)))


(defn- field-entry
  "`{:entity :field [optional :type :nullable? :enum-name]}` from a row."
  [parent-row field-name extra]
  (merge {:entity (keyword (:name parent-row))
          :field  field-name}
         (when extra
           (cond-> {:type (:type extra) :nullable? (:nullable? extra)}
             (:enum-name extra)
             (assoc :enum-name (:enum-name extra))))))


(defn- absorb-row
  "Fold one metadata row into the accumulator. Returns the new acc;
   orphan handling for `:field` / `:enum-value` either throws (strict)
   or skips with a debug log."
  [acc row uuid->row strict? orphan-counter]
  (let [uuid (:uuid row)
        kind (keyword (:kind row))
        n (keyword (:name row))
        parent-uuid (:parent_uuid row)
        parent-row (get uuid->row parent-uuid)
        extra (parse-extra (:extra row))]
    (case kind
      :entity     (assoc-in acc [:entities uuid] n)
      :enum       (assoc-in acc [:enums uuid] n)

      :field
      (if parent-row
        (assoc-in acc [:fields uuid] (field-entry parent-row n extra))
        (or (handle-orphan strict? orphan-counter "field"
                           {:field-uuid uuid :field-name n
                            :missing-parent-uuid parent-uuid})
            acc))

      :enum-value
      (if parent-row
        (assoc-in acc [:enum-values uuid]
                  {:enum (keyword (:name parent-row)) :value n})
        (or (handle-orphan strict? orphan-counter "enum-value"
                           {:enum-value-uuid uuid :value-name n
                            :missing-parent-uuid parent-uuid})
            acc))

      acc)))


(defn- parse-metadata-impl
  "Parses metadata rows into structured format.
   When strict? is true, throws if orphaned entries are detected.
   When strict? is false, skips orphaned entries with debug log and
   a final summary warn."
  [rows strict?]
  (when (seq rows)
    (let [uuid->row (into {} (map (juxt :uuid identity)) rows)
          orphan-counter (atom 0)
          result (reduce (fn [acc row]
                           (absorb-row acc row uuid->row strict? orphan-counter))
                         {:entities {} :fields {} :enums {} :enum-values {}}
                         rows)]
      (when (pos? @orphan-counter)
        (log/warn "Metadata parsing found orphaned entries"
                  {:orphaned-count @orphan-counter
                   :total-rows (count rows)}))
      result)))


(defn parse-metadata
  "Parses metadata rows strictly. Throws on orphaned entries."
  [rows]
  (parse-metadata-impl rows true))


(defn parse-metadata-lenient
  "Parses metadata rows leniently. Skips orphaned entries."
  [rows]
  (parse-metadata-impl rows false))


;; === Write operations ===

(defn upsert-metadata!
  "Inserts or updates a metadata row."
  ([ds uuid kind meta-name parent-uuid]
   (upsert-metadata! ds uuid kind meta-name parent-uuid nil))
  ([ds uuid kind meta-name parent-uuid extra]
   (jdbc/execute! ds
                  (sql/format {:insert-into (keyword metadata-table-name)
                               :values [{:uuid uuid
                                         :kind (name kind)
                                         :name (name meta-name)
                                         :parent_uuid parent-uuid
                                         :extra [:cast (extra->json extra) :jsonb]}]
                               :on-conflict [:uuid]
                               :do-update-set [:name :parent_uuid :extra]}
                              {:quoted true}))))


(defn- field-metadata-row
  "Build the row map for a single field's metadata entry."
  [entity-uuid field-name field-spec]
  (let [extra (cond-> {:type (:type field-spec)
                       :nullable? (get field-spec :nullable? false)}
                ;; Include enum-name for enum fields to enable proper casting
                (= (:type field-spec) :enum)
                (assoc :enum-name (:enum-name field-spec)))]
    {:uuid (:uuid field-spec)
     :kind "field"
     :name (name field-name)
     :parent_uuid entity-uuid
     :extra [:cast (extra->json extra) :jsonb]}))


(defn- collect-metadata-rows
  "Build every metadata row a full schema would persist:
   - one per entity
   - one per field of each entity
   - one per enum
   - one per enum value
   Returns a vector ready for a single batched INSERT."
  [schema]
  (let [entity-rows
        (reduce
          (fn [acc entity-name]
            (let [entity-uuid (ds/entity-uuid schema entity-name)
                  row {:uuid entity-uuid
                       :kind "entity"
                       :name (name entity-name)
                       :parent_uuid nil
                       :extra [:cast (extra->json nil) :jsonb]}
                  field-rows (mapv (fn [[field-name field-spec]]
                                     (field-metadata-row entity-uuid field-name field-spec))
                                   (ds/entity-fields schema entity-name))]
              (-> acc (conj row) (into field-rows))))
          []
          (ds/entities schema))
        enum-rows
        (reduce-kv
          (fn [acc enum-name {:keys [uuid values]}]
            (let [enum-row {:uuid uuid
                            :kind "enum"
                            :name (name enum-name)
                            :parent_uuid nil
                            :extra [:cast (extra->json nil) :jsonb]}
                  value-rows (mapv (fn [[value-kw value-uuid]]
                                     {:uuid value-uuid
                                      :kind "enum-value"
                                      :name (name value-kw)
                                      :parent_uuid uuid
                                      :extra [:cast (extra->json nil) :jsonb]})
                                   values)]
              (-> acc (conj enum-row) (into value-rows))))
          []
          (ds/enums schema))]
    (into entity-rows enum-rows)))


(defn save-metadata-in-tx!
  "Saves complete metadata to table (truncate + insert all in one batch).
   Assumes caller has already started a transaction."
  [tx schema]
  (jdbc/execute! tx (sql/format {:truncate (keyword metadata-table-name)}
                                {:quoted true}))
  (let [rows (collect-metadata-rows schema)]
    (when (seq rows)
      (jdbc/execute! tx
                     (sql/format {:insert-into (keyword metadata-table-name)
                                  :values rows
                                  :on-conflict [:uuid]
                                  :do-update-set [:name :parent_uuid :extra]}
                                 {:quoted true})))))
