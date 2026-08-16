(ns graphden.storage.postgres.crud
  "CRUD operations for PostgreSQL storage.
   Generic entity operations for create, read, update, delete, query."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.errors :as errors]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defn- entity->row
  [entity fields]
  (codec/encode-row entity fields))


(defn- split-ref-many-batch
  "Pull ref-many junction data off each record. Returns
   `[columnar-records ref-many-records]` (parallel vectors)."
  [records fields]
  (let [split (mapv #(junction/extract-ref-many-data % fields) records)]
    [(mapv first split) (mapv second split)]))


(defn- collect-batch-columns
  "Union of every key across `rows` — different records may carry
   different fields (e.g. some have `:name`, some don't). Using just
   the first row's keys would silently drop columns present only
   later."
  [rows]
  (vec (into #{} (mapcat keys) rows)))


(defn- batch-row-values
  "Extract `[v1 v2 …]` for every row, in `columns` order."
  [rows columns]
  (mapv (fn [row] (mapv #(get row %) columns)) rows))


(def ^:private max-statement-params
  "PostgreSQL's JDBC driver caps one PreparedStatement at 65,535 bind
   parameters. Parameters scale with rows × columns, so a ROW-count cap
   can't guard this: the bundled-package bootstrap legitimately writes
   10k+ `:binding-version` rows × 7 columns = 70k+ params in one batch
   (2026-08-15, the fresh-DB boot killer). Budget kept under the cap
   with headroom."
  60000)


(defn- chunk-rows
  "Split `rows` into chunks that each stay under the statement
   parameter budget for `n-cols` columns — one chunk for any batch
   small enough for a single statement (the common case). A multi-chunk
   batch executes as several statements on the SAME `ds`; mid-batch
   failure semantics match the existing multi-statement entity-type
   sequence in the sync path (no new transaction is opened here — `ds`
   may already BE a caller's transaction, and nesting would break it)."
  [rows n-cols]
  (partition-all (max 1 (quot max-statement-params (max 1 n-cols))) rows))


(defn- merge-back-ref-many
  "Pair `result-rows` from JDBC back with the `ref-many-records` we
   stripped off before persistence, so the returned records reflect
   the full shape we wrote. `fields` flows into `row->entity` so
   enum / jsonb columns decode against their schema spec."
  [result-rows ref-many-records fields]
  (mapv (fn [row rm-data] (merge (codec/row->entity row fields) rm-data))
        result-rows
        ref-many-records))


(defn- write-junction-rows!
  "Write ref-many junction rows for every record in the batch in
   ONE INSERT per (entity, field). `replace?` controls whether the
   owner's existing rows are deleted first (update / upsert) or
   inserted fresh (create)."
  [ds entity-name batch-ids ref-many-records fields replace?]
  (when (junction/has-ref-many? fields)
    (let [pairs (filterv #(seq (second %))
                         (map vector batch-ids ref-many-records))]
      (when (seq pairs)
        (if replace?
          (junction/replace-ref-many-fields-batch! ds entity-name pairs fields)
          (junction/write-ref-many-fields-batch! ds entity-name pairs))))))


(defn- batch-execute!
  "Run a JDBC batch query with the canonical batch-error wrap. SQL
   exceptions get the op-context (`entity-name` + `batch-ids`) folded
   into the classified envelope; non-SQL throwables get the raw
   `sp/wrap-batch-error` envelope. `op` is the operation keyword used
   by error classifiers (`:create-entities` / `:update-entities` /
   `:upsert-entities`). Lifted from three open-coded copies inside
   create/update/upsert — the shape was identical modulo the `op`."
  [ds query op entity-name batch-ids batch-size]
  (try
    (util/exec! ds query)
    (catch java.sql.SQLException e
      ;; Index is -1 because PostgreSQL batch INSERT/UPDATE doesn't
      ;; reveal which row failed.
      (let [wrapped (errors/wrap-sql-error e "Database error" op
                                           {:entity-name entity-name
                                            :batch-ids batch-ids})]
        (throw (sp/wrap-batch-error wrapped -1 batch-size nil))))
    (catch Exception e
      (throw (sp/wrap-batch-error e -1 batch-size nil)))))


(defn create-entity
  "Creates a new entity record in the database.
   Returns the created record with generated id if not provided.
   Validates required fields if fields metadata is provided.
   Throws with :unique-violation type if unique constraint violated.
   Throws with :invalid-data type if data is not a map.

   For :ref-many fields: writes to junction tables after entity row is created."
  [ds entity-name data fields]
  (let [data (sp/standard-crud-normalize-data entity-name data)]
    (sp/standard-crud-validations! entity-name data fields)
    (let [table-name (keyword (util/kw->snake-case entity-name))
          id (or (:id data) (random-uuid))
          record (assoc data :id id)
          ;; Split off ref-many fields - they go into junction tables
          [columnar-data ref-many-data] (junction/extract-ref-many-data record fields)
          row (entity->row columnar-data fields)
          columns (keys row)
          values (vals row)
          query (sql/format {:insert-into table-name
                             :columns columns
                             :values [values]
                             :returning [:*]}
                            {:quoted true})]
      (util/with-sql-error-handling "Database error" :create-entity {:entity-name entity-name :id id}
                                    (letfn [(do-create
                                              [conn]
                                              (let [created (-> (util/exec-one! conn query)
                                                                (codec/row->entity fields))]
                                                (when (seq ref-many-data)
                                                  (junction/write-ref-many-fields! conn entity-name (:id created) ref-many-data))
                                                ;; Merge back the ref-many fields so the returned record
                                                ;; reflects what was written.
                                                (merge created ref-many-data)))]
                                      ;; Columnar row + junction rows must land atomically — a
                                      ;; failed junction insert after the row commits would leave
                                      ;; an orphan row (or, on update, wiped relations). Only pay
                                      ;; the transaction when there ARE ref-many fields to write.
                                      (if (seq ref-many-data)
                                        (jdbc/with-transaction [tx ds] (do-create tx))
                                        (do-create ds)))))))


(defn read-entity
  "Reads an entity by id. Returns nil if not found.
   Throws with :table-not-found if entity table doesn't exist.

   For :ref-many fields: loads from junction tables and populates the field as
   a vector of UUIDs. Pass `fields` (the entity field specs) to enable this."
  ([ds entity-name id]
   (read-entity ds entity-name id nil))
  ([ds entity-name id fields]
   (let [table-name (keyword (util/kw->snake-case entity-name))
         query (sql/format {:select [:*]
                            :from [table-name]
                            :where [:= :id id]}
                           {:quoted true})]
     (util/with-sql-error-handling "Database error" :read-entity {:entity-name entity-name :id id}
                                   (when-let [row (util/exec-one! ds query)]
                                     (let [record (codec/row->entity row fields)]
                                       (if (and fields (junction/has-ref-many? fields))
                                         (first (junction/populate-ref-many-fields ds entity-name [record] fields))
                                         record)))))))


(defn update-entity
  "Updates an entity by id. Returns the updated record.
   Throws :not-found if entity doesn't exist.
   Throws :unique-violation if update violates unique constraint.
   Throws :constraint-violation/has-descendants if arg has descendants.
   Validates required fields if fields metadata is provided.

   For :ref-many fields: replaces junction rows (delete + insert)."
  [ds entity-name id data fields]
  (let [data (sp/standard-crud-normalize-data entity-name data)
        table-name (keyword (util/kw->snake-case entity-name))
        existing (read-entity ds entity-name id fields)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [updated (merge existing data {:id id})]
      (when fields
        (sp/validate-required-fields! entity-name fields updated))
      (let [[columnar-data ref-many-data] (junction/extract-ref-many-data updated fields)
            row (entity->row (dissoc columnar-data :id) fields)
            ;; If only ref-many fields changed, columnar row may be empty
            do-column-update? (seq row)
            query (when do-column-update?
                    (sql/format {:update table-name
                                 :set row
                                 :where [:= :id id]
                                 :returning [:*]}
                                {:quoted true}))]
        (util/with-sql-error-handling "Database error" :update-entity {:entity-name entity-name :id id}
                                      (letfn [(do-update
                                                [conn]
                                                (let [updated-row (if do-column-update?
                                                                    (-> (util/exec-one! conn query)
                                                                        (codec/row->entity fields))
                                                                    (dissoc columnar-data nil))]
                                                  ;; Replace junction rows for any ref-many fields actually present in the update
                                                  (when (and (seq ref-many-data) fields)
                                                    (junction/replace-ref-many-fields! conn entity-name id ref-many-data fields))
                                                  (merge updated-row ref-many-data)))]
                                        ;; `replace-ref-many-fields!` deletes then re-inserts; on
                                        ;; separate autocommit connections a failed insert would
                                        ;; leave the relation WIPED (e.g. a re-parent that throws
                                        ;; mid-insert clears parent-ids). Wrap the row + junction
                                        ;; writes in one transaction so a failure rolls back.
                                        (if (and (seq ref-many-data) fields)
                                          (jdbc/with-transaction [tx ds] (do-update tx))
                                          (do-update ds))))))))


(defn delete-entity
  "Deletes an entity by id. Returns true if entity existed and was deleted.
   Throws :foreign-key-violation if entity is referenced by other records.
   Throws :constraint-violation/has-descendants if arg has descendants."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:delete-from table-name
                           :where [:= :id id]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :delete-entity {:entity-name entity-name :id id}
                                  (pos? (:next.jdbc/update-count
                                          (util/exec-one! ds query))))))


(defn- build-where-clause
  "Builds the HoneySQL `:where` vector from a `{field value}` map,
   with the same semantics `query-entities` exposes: nil → IS NULL,
   collection → IN, else → =. Returns nil when `where` is empty so
   callers can `cond-> ... when-let`-style splice."
  [where fields]
  (when (seq where)
    (into [:and]
          (map (fn [[k v]]
                 (let [col (keyword (util/kw->snake-case k))
                       field-spec (get fields k)
                       encoded-v (codec/encode-value v field-spec)]
                   (cond
                     (nil? encoded-v)
                     [:is col nil]

                     ;; Collection = IN clause (for batch lookups). An EMPTY
                     ;; collection can't be `col IN ()` (invalid SQL in
                     ;; PostgreSQL) — it matches nothing, so emit an
                     ;; always-false predicate and let the query return [].
                     (and (or (vector? v) (set? v) (seq? v))
                          (not (map? v)))
                     (if (empty? v)
                       [:= [:inline 1] [:inline 0]]
                       [:in col (vec (map #(codec/encode-value % field-spec) v))])

                     :else
                     [:= col encoded-v])))
               where))))


(defn query-entities
  "Queries entities by conditions.
   where is a map of field->value for equality matching.
   Supports nil values (generates IS NULL instead of = NULL).
   Returns a sequence of matching entities.
   Throws :table-not-found if entity table doesn't exist.
   Throws :invalid-where-clause if where is not nil or a map.

   Optional `opts` (4-arg form):
     :order-by  [[:column :asc-or-:desc] ...]
     :limit     non-negative int
     :offset    non-negative int
   Column keywords in :order-by are translated through snake-case the
   same way `where` keys are.

   Note: Empty where clause ({} or nil) returns all entities (full table scan).
   This is logged at DEBUG level to help identify unintended full scans."
  ([ds entity-name where fields]
   (query-entities ds entity-name where fields nil))
  ([ds entity-name where fields opts]
   (sp/standard-query-validations! entity-name fields where)
   (sp/validate-query-opts! entity-name opts)
   (let [table-name (keyword (util/kw->snake-case entity-name))
         where-clause (build-where-clause where fields)
         order-by (when-let [ob (:order-by opts)]
                    (mapv (fn [[col dir]]
                            [(keyword (util/kw->snake-case col)) dir])
                          ob))
         query (sql/format (cond-> {:select [:*]
                                    :from [table-name]}
                             where-clause (assoc :where where-clause)
                             order-by (assoc :order-by order-by)
                             (:limit opts) (assoc :limit (:limit opts))
                             (:offset opts) (assoc :offset (:offset opts)))
                           {:quoted true})]
     (when-not where-clause
       (log/debug "Full table scan query (no where clause)" {:entity-name entity-name}))
     (util/with-sql-error-handling "Database error" :query-entities {:entity-name entity-name :where where :opts opts}
                                   (let [rows (util/exec! ds query)
                                         records (mapv #(codec/row->entity % fields) rows)]
                                     (if (and fields (junction/has-ref-many? fields))
                                       (junction/populate-ref-many-fields ds entity-name records fields)
                                       records))))))


(defn query-latest-per-group
  "Returns ONE row per distinct `group-cols` tuple — the row with the
   greatest `:created-at` (descending tie-break by `group-cols` order
   for determinism). Postgres-specific via `DISTINCT ON`.

   Used by the versioning layer's `load-merge-aware-cache` to bound
   the per-call working set when downstream callers (`resolve-version-
   from-cache`) only ever consult `latest-by-created-at` per
   (entity-id, branch-id). Plain `query-entities` would pull every
   historical version row — fine for short-lived test databases, but
   on a long-running executor with N versioned writes per entity the
   payload of `/api/graph/entities` (and `/api/types`, anything that
   triggers `resolve-all-entities`) grows O(N) per entity instead of
   O(1), and eventually heap-OOMs at the cheshire-encode boundary.

   `where` shape matches `query-entities`. Passing nil/empty `where`
   issues a full-table DISTINCT ON — fine, but logged at DEBUG.

   `group-cols` MUST include the entity-id field — e.g.
   `[:fn-id :branch-id]` for `:fn-version`. Mixing additional columns
   (e.g. the unique-id `:id` of the version row itself) defeats the
   dedup and reverts to query-entities behaviour."
  [ds entity-name where group-cols fields]
  (sp/standard-query-validations! entity-name fields where)
  (when (or (empty? group-cols) (not (every? keyword? group-cols)))
    (throw (ex-info "query-latest-per-group requires non-empty keyword group-cols"
                    {:type :storage-error/invalid-group-cols
                     :entity-name entity-name
                     :group-cols group-cols})))
  (let [table-name (keyword (util/kw->snake-case entity-name))
        cols-snake (mapv #(keyword (util/kw->snake-case %)) group-cols)
        where-clause (build-where-clause where fields)
        ;; ORDER BY must lead with the DISTINCT-ON cols so Postgres
        ;; can pick the row per group; the trailing `created_at DESC`
        ;; is what makes it the LATEST.
        order-by (conj (vec cols-snake) [:created_at :desc])
        query (sql/format (cond-> {:select-distinct-on (into [cols-snake] [:*])
                                   :from [table-name]
                                   :order-by order-by}
                            where-clause (assoc :where where-clause))
                          {:quoted true})]
    (when-not where-clause
      (log/debug "Full table DISTINCT ON scan (no where clause)" {:entity-name entity-name}))
    (util/with-sql-error-handling "Database error" :query-latest-per-group
                                  {:entity-name entity-name :where where
                                   :group-cols group-cols}
                                  (let [rows (util/exec! ds query)
                                        records (mapv #(codec/row->entity % fields) rows)]
                                    (if (and fields (junction/has-ref-many? fields))
                                      (junction/populate-ref-many-fields ds entity-name records fields)
                                      records)))))


;; === Batch CRUD operations ===

(def ^:dynamic *create-entities-override*
  "Parallel-test failure-injection seam: when bound, `create-entities`
   calls this fn `(f ds entity-name data-seq fields)` instead of the
   real batch insert. nil (production) = real body. Tests `binding`
   this instead of `with-redefs`-ing the root var — a root rebind is
   process-global, and an injected failure leaked into whatever
   sibling NS happened to batch-write during that window (observed:
   `versioning.merge.core-test`'s `boom` killing a sibling's fn-def
   sync, which forced a `^:serial` pin on that NS). Mirrors
   `advisory-lock/*impl-override*`. Cost on the real path: one nil
   check per batch write."
  nil)


(defn- create-entities-impl
  "Real body of `create-entities` — see its docstring. Split out so
   the `*create-entities-override*` seam check stays a one-liner."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (let [data-seq (mapv #(sp/standard-crud-normalize-data entity-name %) data-seq)]
      (sp/validate-batch-size! (count data-seq) :create-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (util/kw->snake-case entity-name))
            ;; Prepare all records with IDs
            records (mapv (fn [data]
                            (when fields
                              (sp/validate-required-fields! entity-name fields data))
                            (let [id (or (:id data) (random-uuid))]
                              (assoc data :id id)))
                          data-seq)
            batch-size (count records)
            batch-ids (mapv :id records)
            [columnar-records ref-many-records] (split-ref-many-batch records fields)
            rows (mapv #(entity->row % fields) columnar-records)
            columns (collect-batch-columns rows)]
        ;; The row batch + junction rows must land atomically — a failed
        ;; junction insert after the row batch commits would leave orphan
        ;; rows (single-entity create wraps exactly this, crud.clj do-create).
        ;; Only pay the transaction when there ARE ref-many fields to write.
        (letfn [(do-batch
                  [conn]
                  (let [result-rows (into []
                                          (mapcat (fn [chunk]
                                                    (batch-execute!
                                                      conn
                                                      (sql/format {:insert-into table-name
                                                                   :columns columns
                                                                   :values (batch-row-values (vec chunk) columns)
                                                                   :returning [:*]}
                                                                  {:quoted true})
                                                      :create-entities entity-name batch-ids batch-size)))
                                          (chunk-rows rows (count columns)))
                        actual-count (count result-rows)]
                    (when (not= batch-size actual-count)
                      (throw (ex-info "Batch insert returned unexpected number of records"
                                      {:type :batch-insert-mismatch
                                       :entity-name entity-name
                                       :expected-count batch-size
                                       :actual-count actual-count})))
                    (write-junction-rows! conn entity-name batch-ids ref-many-records fields false)
                    (merge-back-ref-many result-rows ref-many-records fields)))]
          (if (seq ref-many-records)
            (jdbc/with-transaction [tx ds] (do-batch tx))
            (do-batch ds)))))))


(defn create-entities
  "Creates multiple entity records in a single transaction.
   Returns a sequence of created records with generated ids.
   Throws :unique-violation if any unique constraint violated.
   Throws :duplicate-ids if duplicate IDs found in batch.
   Throws :batch-error/batch-too-large if batch exceeds *max-batch-size*.

   Note: PostgreSQL batch INSERT uses a single statement, so on failure
   the exact failing record index is unknown. Error context includes
   batch-size and all record IDs for debugging."
  [ds entity-name data-seq fields]
  (if-let [f *create-entities-override*]
    (f ds entity-name data-seq fields)
    (create-entities-impl ds entity-name data-seq fields)))


(defn read-entities
  "Reads multiple entities by ids. Returns {id -> record} for found records.
   Throws :table-not-found if entity table doesn't exist.

   For :ref-many fields: loads from junction tables and populates fields as
   vectors of UUIDs. Pass `fields` (the entity field specs) to enable this."
  ([ds entity-name ids]
   (read-entities ds entity-name ids nil))
  ([ds entity-name ids fields]
   (if (empty? ids)
     {}
     (let [table-name (keyword (util/kw->snake-case entity-name))
           query (sql/format {:select [:*]
                              :from [table-name]
                              :where [:in :id (vec ids)]}
                             {:quoted true})]
       (util/with-sql-error-handling "Database error" :read-entities {:entity-name entity-name :count (count ids)}
                                     (let [rows (util/exec! ds query)
                                           ;; Decode WITHOUT field-specs on purpose: the
                                           ;; versioned-resolution + compile paths that call
                                           ;; `read-entities` (versioning/storage/*) rely on the
                                           ;; raw-shape base row, then overlay version data /
                                           ;; re-decode themselves. Passing `fields` here (as
                                           ;; `query-entities` does) double-processes jsonb and
                                           ;; corrupts the compiled graph — 27 executor tests
                                           ;; (record/list-type, :fix recursion, versioned-rows)
                                           ;; break. `fields` is still used for ref-many below.
                                           records (mapv codec/row->entity rows)
                                           populated (if (and fields (junction/has-ref-many? fields))
                                                       (junction/populate-ref-many-fields ds entity-name records fields)
                                                       records)]
                                       (into {} (map (fn [e] [(:id e) e])) populated)))))))


(defn- field-type->cast
  "PG cast string for a column from its declared field-spec — the
   fallback when a batch has no non-nil sample to infer from (every row
   is nil for this column). Text / numeric need no cast (PG infers)."
  [spec]
  (case (:type spec)
    (:uuid :ref)    "uuid"
    :bool           "boolean"
    :int            "bigint"
    :timestamptz    "timestamptz"
    (:jsonb :union) "jsonb"
    :enum           (some-> (:enum-name spec) util/kw->snake-case)
    nil))


(defn- column-type-cast
  "PostgreSQL type cast for one VALUES column. `:id` is always `uuid`;
   other columns inferred from the first non-nil sample value, falling
   back to the declared field type when EVERY row is nil for the column.
   Returns the cast string (`\"uuid\"` / `\"boolean\"` / `\"bigint\"` /
   `\"timestamptz\"` / `\"jsonb\"` / an enum type) or nil when no cast is
   needed (text / numeric).

   The all-nil fallback matters because a batch UPDATE that clears a
   non-text column (a `:ref` uuid, `:bool`, `:int`, `:jsonb`, …) to nil
   for EVERY row has no sample to infer from; without an explicit cast
   PG types the VALUES NULL as `text` and the UPDATE fails with
   `column is of type uuid but expression is of type text`.

   Note: codec's `encode-row` runs BEFORE this fn, so timestamptz values
   arrive as `java.sql.Timestamp` (post `Instant/from`), not raw
   `java.time.Instant`. Both classes get matched here — `Instant` for
   defensive coverage on the off-chance a row bypasses encode, and
   `Timestamp` for the normal post-encode case."
  [col rows fields]
  (if (= col :id)
    "uuid"
    (if-let [sample (some #(get % col) rows)]
      (cond
        (uuid? sample) "uuid"
        (boolean? sample) "boolean"
        (int? sample) "bigint"
        (or (instance? java.time.Instant sample)
            (instance? java.sql.Timestamp sample)) "timestamptz"
        :else nil)
      ;; Every row nil for this column — infer from the declared type.
      (field-type->cast (get fields col)))))


(defn- build-batch-update-sql
  "Assemble the `UPDATE … FROM (VALUES …)` JDBC tuple for a batch
   UPDATE. Pure HoneySQL composition — the caller `update-entities`
   reads as the orchestrator (validate → build → execute → verify →
   junctions).

   Returns `[sql-string param1 param2 …]` suitable for `batch-execute!`.

   Inputs:
   - `table-name-str` — snake-case table name (PG identifier).
   - `rows` — decoded entity rows (post `entity->row` projection).
   - `columns` — every column key present across the batch
     (`collect-batch-columns` output; superset includes `:id`).
   - `update-columns` — `columns` minus `:id`; only these appear in
     the `SET` clause."
  [table-name-str rows columns update-columns fields]
  (let [values (batch-row-values rows columns)
        ;; PostgreSQL needs explicit type casts for UUID + non-string
        ;; scalars in the VALUES clause; nil cast = pass through (text
        ;; / numeric columns infer correctly without help). `fields`
        ;; supplies the cast for a column that is nil in every row.
        column-types (mapv #(column-type-cast % rows fields) columns)
        apply-cast (fn [v cast-type]
                     (if cast-type [:cast v (keyword cast-type)] v))
        value-rows (mapv (fn [row]
                           (mapv apply-cast row column-types))
                         values)
        ;; SET col = v.col for each updatable column.
        set-clause (into {}
                         (map (fn [c] [c (keyword "v" (name c))]))
                         update-columns)
        col-aliases (mapv #(keyword (name %)) columns)]
    ;; `:quoted true` — `user` / `order` / other entity names are PG
    ;; reserved words; without quoting the unqualified table-name
    ;; aborts the statement. Matches the original raw-SQL behavior
    ;; which manually wrapped table-name in `"..."`.
    (sql/format {:update [(keyword table-name-str) :t]
                 :set set-clause
                 :from [[{:values value-rows}
                         [:v {:columns col-aliases}]]]
                 :where [:= :t.id :v.id]
                 :returning [:t.*]}
                {:quoted true})))


(defn update-entities
  "Updates multiple entity records in a single batch.
   Each record must have :id. Returns seq of updated records.
   Uses PostgreSQL UPDATE ... FROM (VALUES ...) for efficient batch update.
   Throws :not-found if any entity doesn't exist."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (let [data-seq (mapv #(sp/standard-crud-normalize-data entity-name %) data-seq)]
      (sp/validate-batch-size! (count data-seq) :update-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name-str (util/kw->snake-case entity-name)
            ;; Validate all records have :id
            missing-ids (vec (remove :id data-seq))]
        (when (seq missing-ids)
          (throw (ex-info "Each record must have :id for batch update"
                          {:type :invalid-data
                           :entity-name entity-name
                           :count (count missing-ids)})))
        (let [records (vec data-seq)
              batch-size (count records)
              batch-ids (mapv :id records)
              [columnar-records ref-many-records] (split-ref-many-batch records fields)
              rows (mapv #(entity->row % fields) columnar-records)
              columns (collect-batch-columns rows)
              update-columns (vec (remove #{:id} columns))]
          ;; If no columns to update (only :id provided), just verify existence and return
          (if (empty? update-columns)
            (let [existing (read-entities ds entity-name batch-ids)
                  missing (vec (remove #(contains? existing %) batch-ids))]
              (when (seq missing)
                (throw (ex-info "Entity not found"
                                {:type :not-found
                                 :entity-name entity-name
                                 :missing-ids missing})))
              (map #(get existing (:id %)) records))
            ;; Normal case: have columns to update. Row batch + junction
            ;; REPLACE (delete-then-insert) must be atomic — a failed insert
            ;; on a separate autocommit connection would leave the ref-many
            ;; relation WIPED for every owner in the batch (for :fn that's
            ;; parent-ids). Mirrors single-entity update's transaction.
            (letfn [(do-batch
                      [conn]
                      (let [result-rows (into []
                                              (mapcat (fn [chunk]
                                                        (batch-execute!
                                                          conn
                                                          (build-batch-update-sql table-name-str (vec chunk)
                                                                                  columns update-columns fields)
                                                          :update-entities entity-name batch-ids batch-size)))
                                              (chunk-rows rows (count columns)))
                            actual-count (count result-rows)]
                        (when (not= batch-size actual-count)
                          (let [updated-ids (set (map :id result-rows))
                                missing (vec (remove updated-ids batch-ids))]
                            (throw (ex-info "Entity not found"
                                            {:type :not-found
                                             :entity-name entity-name
                                             :missing-ids missing
                                             :expected-count batch-size
                                             :actual-count actual-count}))))
                        (write-junction-rows! conn entity-name batch-ids ref-many-records fields true)
                        (merge-back-ref-many result-rows ref-many-records fields)))]
              (if (seq ref-many-records)
                (jdbc/with-transaction [tx ds] (do-batch tx))
                (do-batch ds)))))))))


(defn upsert-entities
  "Inserts or updates multiple entity records using INSERT ... ON CONFLICT DO UPDATE.
   Each record must have :id. Returns seq of upserted records.
   Uses single SQL statement for efficiency."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (let [data-seq (mapv #(sp/standard-crud-normalize-data entity-name %) data-seq)]
      (sp/validate-batch-size! (count data-seq) :upsert-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (util/kw->snake-case entity-name))
            ;; Validate all records have :id
            _ (doseq [data data-seq]
                (when-not (:id data)
                  (throw (ex-info "Each record must have :id for upsert"
                                  {:type :invalid-data
                                   :entity-name entity-name
                                   :data (sp/redact-sensitive-map data)}))))
            records (vec data-seq)
            batch-size (count records)
            batch-ids (mapv :id records)
            [columnar-records ref-many-records] (split-ref-many-batch records fields)
            rows (mapv #(entity->row % fields) columnar-records)
            columns (collect-batch-columns rows)
            ;; Build ON CONFLICT DO UPDATE SET for all columns except :id
            ;; HoneySQL auto-generates SET col = EXCLUDED.col when given a vector
            update-columns (vec (remove #{:id} columns))]
        ;; Row batch + junction REPLACE must be atomic (see update-entities).
        (letfn [(do-batch
                  [conn]
                  (let [result-rows (into []
                                          (mapcat (fn [chunk]
                                                    (batch-execute!
                                                      conn
                                                      (sql/format {:insert-into table-name
                                                                   :columns columns
                                                                   :values (batch-row-values (vec chunk) columns)
                                                                   :on-conflict [:id]
                                                                   :do-update-set update-columns
                                                                   :returning [:*]}
                                                                  {:quoted true})
                                                      :upsert-entities entity-name batch-ids batch-size)))
                                          (chunk-rows rows (count columns)))
                        actual-count (count result-rows)]
                    (when (not= batch-size actual-count)
                      (throw (ex-info "Batch upsert returned unexpected number of records"
                                      {:type :batch-upsert-mismatch
                                       :entity-name entity-name
                                       :expected-count batch-size
                                       :actual-count actual-count})))
                    (write-junction-rows! conn entity-name batch-ids ref-many-records fields true)
                    (merge-back-ref-many result-rows ref-many-records fields)))]
          (if (seq ref-many-records)
            (jdbc/with-transaction [tx ds] (do-batch tx))
            (do-batch ds)))))))


(defn delete-entities
  "Deletes multiple entities by ids. Returns count of deleted records.
   Throws :foreign-key-violation if any entity is referenced."
  [ds entity-name ids]
  (if (empty? ids)
    0
    (let [table-name (keyword (util/kw->snake-case entity-name))
          query (sql/format {:delete-from table-name
                             :where [:in :id (vec ids)]}
                            {:quoted true})]
      (util/with-sql-error-handling "Database error" :delete-entities {:entity-name entity-name :count (count ids)}
                                    (:next.jdbc/update-count
                                      (util/exec-one! ds query))))))
