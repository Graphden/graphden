(ns graphden.postgres-storage.metadata
  "Metadata table operations for schema tracking.
   Stores entity, field, enum, and enum-value UUIDs for migration support."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.postgres-storage.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (org.postgresql.util
      PGobject)))


(def ^:private metadata-table-name "_schema_metadata")


;; Use shared timeout utility from util.clj
(def ^:private get-query-timeout util/get-query-timeout-seconds)


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
                 {:builder-fn rs/as-unqualified-lower-maps
                  :timeout (get-query-timeout)}))


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

(defn- parse-metadata-impl
  "Parses metadata rows into structured format.
   When strict? is true, throws if orphaned entries are detected.
   When strict? is false, skips orphaned entries with warning and logs summary."
  [rows strict?]
  (when (seq rows)
    (let [uuid->row (into {} (map (fn [r] [(:uuid r) r]) rows))
          orphaned-count (atom 0)
          result (reduce
                   (fn [acc row]
                     (let [uuid (:uuid row)
                           kind (keyword (:kind row))
                           n (keyword (:name row))
                           parent-uuid (:parent_uuid row)
                           parent-row (get uuid->row parent-uuid)
                           extra (parse-extra (:extra row))]
                       (case kind
                         :entity (assoc-in acc [:entities uuid] n)
                         :field (if parent-row
                                  (assoc-in acc [:fields uuid]
                                            (merge {:entity (keyword (:name parent-row))
                                                    :field n}
                                                   (when extra
                                                     (cond-> {:type (:type extra)
                                                              :nullable? (:nullable? extra)}
                                                       (:enum-name extra)
                                                       (assoc :enum-name (:enum-name extra))))))
                                  (if strict?
                                    (throw (ex-info "Orphaned field entry in metadata"
                                                    {:type :metadata-corruption
                                                     :field-uuid uuid
                                                     :field-name n
                                                     :missing-parent-uuid parent-uuid}))
                                    (do
                                      (swap! orphaned-count inc)
                                      (log/debug "Orphaned field entry in metadata, skipping"
                                                 {:field-uuid uuid
                                                  :field-name n
                                                  :missing-parent-uuid parent-uuid})
                                      acc)))
                         :enum (assoc-in acc [:enums uuid] n)
                         :enum-value (if parent-row
                                       (assoc-in acc [:enum-values uuid]
                                                 {:enum (keyword (:name parent-row))
                                                  :value n})
                                       (if strict?
                                         (throw (ex-info "Orphaned enum-value entry in metadata"
                                                         {:type :metadata-corruption
                                                          :enum-value-uuid uuid
                                                          :value-name n
                                                          :missing-parent-uuid parent-uuid}))
                                         (do
                                           (swap! orphaned-count inc)
                                           (log/debug "Orphaned enum-value entry in metadata, skipping"
                                                      {:enum-value-uuid uuid
                                                       :value-name n
                                                       :missing-parent-uuid parent-uuid})
                                           acc)))
                         acc)))
                   {:entities {} :fields {} :enums {} :enum-values {}}
                   rows)]
      ;; Log summary if any orphaned entries were found
      (when (pos? @orphaned-count)
        (log/warn "Metadata parsing found orphaned entries"
                  {:orphaned-count @orphaned-count
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


(defn- save-entity-field-metadata!
  "Saves metadata for a single field."
  [tx entity-uuid field-name field-spec]
  (upsert-metadata! tx (:uuid field-spec) :field field-name entity-uuid
                    (cond-> {:type (:type field-spec)
                             :nullable? (get field-spec :nullable? false)}
                      ;; Include enum-name for enum fields to enable proper casting
                      (= (:type field-spec) :enum)
                      (assoc :enum-name (:enum-name field-spec)))))


(defn- save-entity-metadata!
  "Saves metadata for a single entity and its fields."
  [tx schema entity-name]
  (let [entity-uuid (ds/entity-uuid schema entity-name)]
    (upsert-metadata! tx entity-uuid :entity entity-name nil)
    (run! (fn [[field-name field-spec]]
            (save-entity-field-metadata! tx entity-uuid field-name field-spec))
          (ds/entity-fields schema entity-name))))


(defn- save-enum-value-metadata!
  "Saves metadata for a single enum value."
  [tx enum-uuid value-kw value-uuid]
  (upsert-metadata! tx value-uuid :enum-value value-kw enum-uuid))


(defn- save-enum-metadata!
  "Saves metadata for a single enum and its values."
  [tx enum-name {:keys [uuid values]}]
  (upsert-metadata! tx uuid :enum enum-name nil)
  (run! (fn [[value-kw value-uuid]]
          (save-enum-value-metadata! tx uuid value-kw value-uuid))
        values))


(defn save-metadata-in-tx!
  "Saves complete metadata to table (truncate + insert all).
   Assumes caller has already started a transaction."
  [tx schema]
  (jdbc/execute! tx (sql/format {:truncate (keyword metadata-table-name)}
                                {:quoted true}))
  (run! #(save-entity-metadata! tx schema %) (ds/entities schema))
  (run! (fn [[enum-name enum-def]] (save-enum-metadata! tx enum-name enum-def))
        (ds/enums schema)))
