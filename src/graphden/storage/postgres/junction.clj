(ns graphden.storage.postgres.junction
  "Junction table operations for :ref-many fields.

   Each :ref-many field on an entity is materialized as a separate junction table:
     CREATE TABLE {entity}_{field} (
       owner_id  UUID NOT NULL REFERENCES {entity}(id) ON DELETE CASCADE,
       target_id UUID NOT NULL,
       ord       INT  NOT NULL,
       PRIMARY KEY (owner_id, ord),
       UNIQUE (owner_id, target_id)
     )

   The user-facing API treats the field as a vector of UUIDs - this namespace
   handles the translation between vector form and junction rows."
  (:require
    [graphden.storage.postgres.ddl :as ddl]
    [graphden.storage.postgres.util :as util]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defn- insert-junction-sql
  "HoneySQL-generated SQL string for the per-row INSERT, with three
   `?` placeholders for owner_id / target_id / ord. Used as the
   prepared-statement template for `jdbc/execute-batch!`; the parent
   passes the rows separately so JDBC's batch API stays in play."
  [jt]
  (first (sql/format {:insert-into [(keyword jt)]
                      :columns [:owner_id :target_id :ord]
                      ;; Placeholders — values discarded; we want
                      ;; the `(?, ?, ?)` SQL shape only.
                      :values [[0 0 0]]})))


(defn ref-many-fields
  "Returns sequence of [field-name field-spec] for :ref-many fields in fields map."
  [fields]
  (filter (fn [[_ fspec]] (= :ref-many (:type fspec))) fields))


(defn has-ref-many?
  "Returns true if any field is :ref-many."
  [fields]
  (boolean (seq (ref-many-fields fields))))


(defn extract-ref-many-data
  "Splits entity data into [columnar-data ref-many-data].
   - columnar-data: map without :ref-many fields (goes into main entity row)
   - ref-many-data: map of {field-name [uuid ...]} for junction inserts"
  [data fields]
  (let [rm-field-names (set (map first (ref-many-fields fields)))]
    [(into {} (remove (fn [[k _]] (contains? rm-field-names k))) data)
     (select-keys data rm-field-names)]))


(defn- normalize-uuid
  [v]
  (cond
    (uuid? v) v
    (string? v) (java.util.UUID/fromString v)
    :else (throw (ex-info "Expected UUID or UUID string"
                          {:type :invalid-data :value v}))))


(defn insert-junction-rows!
  "Inserts rows into the junction table for one entity row.
   targets is a sequence of UUIDs (or strings); ord is the position (0-indexed)."
  [ds entity-name field-name owner-id targets]
  (when (seq targets)
    (let [jt (ddl/junction-table-name entity-name field-name)
          rows (map-indexed
                 (fn [idx target]
                   [owner-id (normalize-uuid target) idx])
                 targets)]
      (util/with-sql-error-handling "Database error" :insert-junction
                                    {:entity-name entity-name :field-name field-name :owner-id owner-id}
                                    (jdbc/execute-batch! ds
                                                         (insert-junction-sql jt)
                                                         rows
                                                         {})))))


(defn delete-junction-rows!
  "Deletes all junction rows for an owner-id."
  [ds entity-name field-name owner-id]
  (let [jt (ddl/junction-table-name entity-name field-name)]
    (util/with-sql-error-handling "Database error" :delete-junction
                                  {:entity-name entity-name :field-name field-name :owner-id owner-id}
                                  (util/exec! ds (sql/format {:delete-from [(keyword jt)]
                                                              :where [:= :owner_id owner-id]}) {}))))


(defn read-junction-rows
  "Returns vector of target UUIDs for an owner-id, ordered by ord."
  [ds entity-name field-name owner-id]
  (let [jt (ddl/junction-table-name entity-name field-name)]
    (util/with-sql-error-handling "Database error" :read-junction
                                  {:entity-name entity-name :field-name field-name :owner-id owner-id}
                                  (let [rows (util/exec! ds
                                                         (sql/format {:select [:target_id]
                                                                      :from [(keyword jt)]
                                                                      :where [:= :owner_id owner-id]
                                                                      :order-by [:ord]}))]
                                    ;; query-opts uses :as-unqualified-lower-maps which converts underscore to underscore
                                    ;; (NOT to kebab-case), so column "target_id" becomes :target_id
                                    (mapv :target_id rows)))))


(defn read-junction-owners
  "Reverse junction lookup: returns vector of owner-ids whose junction
   row has `target-id`. Hits the `idx_<jt>_target` index installed by
   `create-junction-table!` — O(log n) on the target column.

   Used by reverse-dependency checks (e.g. \"which fns name this fn
   as a parent?\") that previously had to full-scan the owner table."
  [ds entity-name field-name target-id]
  (let [jt (ddl/junction-table-name entity-name field-name)]
    (util/with-sql-error-handling "Database error" :read-junction-owners
                                  {:entity-name entity-name :field-name field-name :target-id target-id}
                                  (let [rows (util/exec! ds
                                                         (sql/format {:select-distinct [:owner_id]
                                                                      :from [(keyword jt)]
                                                                      :where [:= :target_id target-id]}))]
                                    (mapv :owner_id rows)))))


(defn read-junction-rows-batch
  "Returns map of {owner-id [target-uuids...]} for multiple owner-ids in one query.
   Used for batch reads to avoid N+1."
  [ds entity-name field-name owner-ids]
  (if (empty? owner-ids)
    {}
    (let [jt (ddl/junction-table-name entity-name field-name)
          query (sql/format {:select [:owner_id :target_id]
                             :from [(keyword jt)]
                             :where [:in :owner_id (vec owner-ids)]
                             :order-by [:owner_id :ord]})]
      (util/with-sql-error-handling "Database error" :read-junction-batch
                                    {:entity-name entity-name :field-name field-name}
                                    (let [rows (util/exec! ds query)]
                                      (reduce (fn [acc row]
                                                (update acc (:owner_id row) (fnil conj []) (:target_id row)))
                                              {}
                                              rows))))))


(defn populate-ref-many-fields
  "Loads junction data for all :ref-many fields and merges into entity records.
   - records: sequence of entity records (each must have :id)
   - fields: entity field specs

   Returns records with :ref-many fields populated as vectors of UUIDs."
  [ds entity-name records fields]
  (let [rm-fields (ref-many-fields fields)]
    (if (or (empty? records) (empty? rm-fields))
      records
      (let [ids (mapv :id records)]
        (reduce (fn [recs [field-name _]]
                  (let [batch (read-junction-rows-batch ds entity-name field-name ids)]
                    (mapv (fn [r] (assoc r field-name (or (get batch (:id r)) []))) recs)))
                records
                rm-fields)))))


(defn write-ref-many-fields!
  "Inserts junction rows for all :ref-many fields after creating an entity."
  [ds entity-name owner-id ref-many-data]
  (doseq [[field-name targets] ref-many-data]
    (insert-junction-rows! ds entity-name field-name owner-id targets)))


(defn replace-ref-many-fields!
  "For update: deletes existing junction rows then inserts new ones."
  [ds entity-name owner-id ref-many-data fields]
  (let [rm-fields (ref-many-fields fields)]
    (doseq [[field-name _] rm-fields
            :when (contains? ref-many-data field-name)]
      (delete-junction-rows! ds entity-name field-name owner-id)
      (insert-junction-rows! ds entity-name field-name owner-id (get ref-many-data field-name)))))


(defn- insert-junction-rows-multi!
  "Insert rows for many owners in one execute-batch. `owner->targets`
   is `[[owner-id [target …]] …]`. Each owner's `targets` is written
   with 0-based ord; positions are independent per owner."
  [ds entity-name field-name owner->targets]
  (let [rows (reduce (fn [acc [owner-id targets]]
                       (into acc
                             (map-indexed
                               (fn [idx target]
                                 [owner-id (normalize-uuid target) idx]))
                             targets))
                     []
                     (filter #(seq (second %)) owner->targets))]
    (when (seq rows)
      (let [jt (ddl/junction-table-name entity-name field-name)]
        (util/with-sql-error-handling "Database error" :insert-junction-batch
                                      {:entity-name entity-name :field-name field-name
                                       :owner-count (count owner->targets)}
                                      (jdbc/execute-batch! ds
                                                           (insert-junction-sql jt)
                                                           rows
                                                           {}))))))


(defn- delete-junction-rows-multi!
  "DELETE rows for many owner-ids in one statement. No-op when empty."
  [ds entity-name field-name owner-ids]
  (when (seq owner-ids)
    (let [jt (ddl/junction-table-name entity-name field-name)
          query (sql/format {:delete-from [(keyword jt)]
                             :where [:in :owner_id (vec owner-ids)]})]
      (util/with-sql-error-handling "Database error" :delete-junction-batch
                                    {:entity-name entity-name :field-name field-name
                                     :owner-count (count owner-ids)}
                                    (util/exec! ds query)))))


(defn write-ref-many-fields-batch!
  "Batch version of `write-ref-many-fields!`: takes a sequence of
   `[owner-id ref-many-data]` pairs and emits one INSERT per
   (entity, field) covering every owner. Use after `create-entities`
   so N×M individual INSERTs collapse to M."
  [ds entity-name owner+ref-many-pairs]
  (let [field->owners (reduce
                        (fn [acc [owner-id rm-data]]
                          (reduce-kv (fn [m field-name targets]
                                       (update m field-name (fnil conj [])
                                               [owner-id targets]))
                                     acc
                                     rm-data))
                        {}
                        (filter #(seq (second %)) owner+ref-many-pairs))]
    (doseq [[field-name owner->targets] field->owners]
      (insert-junction-rows-multi! ds entity-name field-name owner->targets))))


(defn replace-ref-many-fields-batch!
  "Batch version of `replace-ref-many-fields!`: one DELETE + one
   INSERT per (entity, field) instead of N×M of each."
  [ds entity-name owner+ref-many-pairs fields]
  (let [rm-fields (ref-many-fields fields)
        ;; Per field, collect owners that supplied data for it. Empty data
        ;; vector still means "replace with nothing" — we DELETE the owner's
        ;; rows but skip the INSERT.
        field->owners (reduce
                        (fn [acc [field-name _]]
                          (let [pairs (keep (fn [[owner-id rm-data]]
                                              (when (contains? rm-data field-name)
                                                [owner-id (get rm-data field-name)]))
                                            owner+ref-many-pairs)]
                            (assoc acc field-name (vec pairs))))
                        {}
                        rm-fields)]
    (doseq [[field-name owner->targets] field->owners
            :when (seq owner->targets)]
      (delete-junction-rows-multi! ds entity-name field-name (mapv first owner->targets))
      (insert-junction-rows-multi! ds entity-name field-name owner->targets))))
