(ns graphden.versioning.storage.core
  "Storage decorator that adds Git-like versioning with branch support.

   Wraps any storage implementation with branch-aware CRUD:
   - Versioned entities (fn, fn-slot, binding, binding-list-item) are
     intercepted: reads resolve versions on the current branch, writes
     append version records
   - Non-versioned entities (slot, namespace, branch, branch-merge,
     all version tables) delegate directly to base storage
   - ExecutionGraph resolution works transparently via CRUD interception

   The authoritative versioned-entity list lives in
   `graphden.versioning.storage.resolution/entity-config`.

   ## Usage

   (def base (-> (pg/create-storage config) (sp/initialize-with-cleanup! schema)))
   (def storage (vs/wrap-with-versioning base))

   ;; CRUD works like normal storage, but is branch-aware
   (sp/create-entity storage :fn {:name \"foo\" :parent-ids [id]})

   ;; Create branch and switch
   (def branch (vs/create-branch! storage \"feature\"))
   (def feature (vs/switch-branch storage (:id branch)))

   ## Design

   Two-table pattern: the base entity table stores identity fields (id + immutable refs),
   the version table stores mutable data fields. VersionedStorage merges them on read.

   Composability: VersionedStorage has ZERO imports from cached-storage or cache-protocol.
   It is independently usable: VersionedStorage(BaseStorage) works without any cache.
   CachedStorage(VersionedStorage(BaseStorage)) works via simple stacking."
  (:require
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-constraints :as gc]
    [graphden.storage.protocol.graph :as graph]
    [graphden.storage.tx :as tx]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.merge :as mrg]
    [graphden.versioning.storage.purge :as purge]
    [graphden.versioning.storage.resolution :as res]
    [graphden.versioning.storage.uniqueness :as uniq])
  (:import
    (java.time
      Instant)))


;; === Internal Helpers ===

(defn- now
  []
  (Instant/now))


(defn- prepare-version-record
  "Build a row for the version table from a versioned-entity payload:
   pull the version-data fields off `data`, stamp identity (`:id`,
   `<version-id-field>`, `:branch-id`, `:created-at`). Used by both
   single-entity and batch write paths."
  [version-config entity-id branch-id timestamp data]
  (let [{:keys [version-id-field version-data-fields]} version-config]
    (-> (select-keys data version-data-fields)
        (assoc :id (random-uuid)
               version-id-field entity-id
               :branch-id branch-id
               :created-at timestamp))))


(defn- strip-version-framework-cols
  "Version-plane bookkeeping must never reach an identity-plane INSERT
   (the identity table has no such columns). The version INSERT is
   whitelisted via `prepare-version-record`; this is its symmetric
   guard for the base row — strips `:branch-id` / `:created-at` /
   `:deleted-at` / the entity's version-id-field from a row a caller
   may have echoed from a raw version-row read. None of these is a
   legitimate identity column on any versioned entity."
  [entity-name row]
  (let [{:keys [version-id-field]} (get res/entity-config entity-name)]
    (cond-> (dissoc row :branch-id :created-at :deleted-at)
      version-id-field (dissoc version-id-field))))


(defn- apply-identity-field-diff!
  "A create hitting an EXISTING identity row used to silently drop any
   changed identity-plane field (`:namespace-id`, `:parent-ids`,
   `:branch-local?`, …) — create treated identity fields as write-once,
   so behavior diverged on identity presence. Flow the non-versioned
   diff through the base `update-entity` (which also reconciles
   ref-many junctions), mirroring what `update-entities` already does.
   Guarded: an unchanged re-create stays a no-op."
  [st entity-name id normalized existing version-data-fields]
  (let [non-versioned (apply dissoc normalized :id version-data-fields)]
    (when (and (seq non-versioned)
               (not= non-versioned
                     (select-keys existing (keys non-versioned))))
      (sp/update-entity st entity-name id non-versioned))))


(defn- create-version-record!
  "Creates a version record in the version table for a versioned entity."
  [base-storage entity-name entity-id branch-id data]
  (let [config (get res/entity-config entity-name)
        version-data (prepare-version-record config entity-id branch-id (now) data)]
    (sp/create-entity base-storage (:version-entity config) version-data)))


(def ^:dynamic *tombstone-delete?*
  "When true, `delete-entity`/`delete-entities` write a TOMBSTONE version
   (`:deleted-at` set) instead of hard-deleting this branch's version rows.
   The user-facing CRUD delete (`crud.entities/delete-entity`) binds this so
   deleting an entity inherited from a PARENT branch actually hides it (the
   hard-delete-current-branch-only path is a silent no-op for inherited
   entities). Default false → hard delete, which is what DECLARATIVE SYNC
   (`composition/core.clj` stale-row cleanup, which also runs through
   VersionedStorage) and rollback paths need — those must truly remove rows,
   not accumulate tombstones on every rebuild."
  false)


(defn- with-bump*
  "Bump the graph epoch for a graph-shaped write (bump BEFORE write — a
   committed change is always preceded by a visible bump), run `write!`,
   and on a THROW mark the bump applied. The write paths are transactional,
   so a refused write — a name collision, a protection or constraint
   violation, a tenant gate — changed nothing; left un-noted, its bump aged
   past the grace into an `:aborted` heal, i.e. a full base rebuild per
   user error (three per e2e run on). A bump that never happened
   (no pool, no sequence) needs no note; an Error (not an Exception) leaves
   the bump un-noted on purpose — the heal is the right answer to a JVM
   that may not have rolled back."
  [base-storage entity-name write!]
  (let [v (epoch/bump! base-storage entity-name)]
    (try
      (write!)
      (catch Exception t
        (when v (epoch/note-applied! base-storage [v]))
        (throw t)))))


(defn- with-write*
  "Every VersionedStorage write method's frame: the graph-epoch bump
   (`with-bump*`), then the derived caches this layer owns. A `:fn`
   write — `:branch-local?` itself, or a `:parent-ids` junction
   replacement (which only ever happens as part of a `:fn` write) — can
   shift the effective branch-local set, so it drops the per-storage
   `effective-branch-local?` cache here, whoever the caller is: the CRUD
   layer, the bundle sync behind MCP `upsert-fn-defs` / registry install,
   or a raw storage write. Cleared AFTER the write so a concurrent reader
   cannot re-cache the pre-write value."
  [base-storage entity-name write!]
  (let [result (with-bump* base-storage entity-name write!)]
    (when (= :fn entity-name)
      (bl/invalidate! base-storage))
    result))


(defn- tombstone-version!
  "Write a tombstone version (current resolved data + `:deleted-at`) on
   `branch-id`, so the entity resolves ABSENT here and on descendants while
   the ancestor/identity rows survive. Non-nullable version columns are
   satisfied by the current version data. Returns true when a tombstone was
   written (the entity was live), false when there was nothing live to delete."
  [base-storage entity-name id branch-id]
  (let [config (get res/entity-config entity-name)
        current (res/resolve-version base-storage entity-name id branch-id)]
    (when current
      (let [ts (now)
            version-data (-> (prepare-version-record config id branch-id ts current)
                             (assoc :deleted-at ts))]
        (sp/create-entity base-storage (:version-entity config) version-data)))
    (some? current)))


(defn- revive-or-update!
  "The update half of `upsert-entities`, with the resurrection case.

   An identity row outlives its content: delete tombstones the VERSION
   and leaves the identity behind, and package ids are deterministic
   per (namespace, name) — so a re-sync of a deleted fn hands us an id
   whose identity exists and whose content does not. `update-entities`
   resolves versions and throws `:not-found` for exactly those ids;
   upsert means create-or-update, so they are re-created instead.
   (Re-installing a package whose materialised fns had been deleted
   answered 404 forever otherwise.)

   The existence probe upstream stays the cheap identity read — this
   path costs nothing unless the write actually hit a tombstone, and
   `update-entities` has already done the resolve by the time it
   reports which ids are missing."
  [storage entity-name to-update]
  (try
    (sp/update-entities storage entity-name to-update)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type missing-ids]} (ex-data e)
            missing (set missing-ids)]
        (if (and (= :not-found type) (seq missing))
          (let [{revive true survivors false}
                (group-by #(contains? missing (:id %)) to-update)]
            (when (seq revive)
              (sp/create-entities storage entity-name revive))
            (when (seq survivors)
              (sp/update-entities storage entity-name survivors)))
          (throw e))))))


;; === VersionedStorage Record ===

(defn- tombstoned-natural-key-id
  "For an entity whose identity is a NATURAL key — `:binding` and
   `:fn-slot` are `(fn-id, slot-id)`, UNIQUE at the base table — the id
   of the identity row that already holds that key while being DEAD on
   this branch (tombstoned, or never versioned here), else nil. A
   user-facing delete leaves the identity behind, so \"bind the slot
   again\" after \"unbind it\" used to insert a second identity for the
   same key and bounce off the UNIQUE index with a 409 (`binding already
   exists with these fields`) — the same trap the retired `fn (namespace,
   name)` index set for names, met one layer down. Re-using the dead
   identity turns the create into a revival: the version written on top
   is the new live one. A LIVE identity is left alone, so a genuine
   duplicate still hits the index and the 409 it deserves."
  [base-storage branch-id entity-name data]
  (when (and (contains? #{:binding :fn-slot} entity-name)
             (:fn-id data) (:slot-id data))
    (let [rows (sp/query-entities base-storage entity-name
                                  {:fn-id (:fn-id data) :slot-id (:slot-id data)})
          row (first rows)]
      (when (and row
                 (nil? (res/resolve-version base-storage entity-name (:id row) branch-id)))
        (:id row)))))


(defn- create-entity-versioned!
  "Create one versioned entity: identity row + version row."
  [base-storage branch-id entity-name data]
  ;; Versioned: create full record in base table + version record.
  ;; When the identity row already exists (deterministic UUID re-sync),
  ;; resolve the current version directly from `existing` instead of
  ;; calling `resolve-entity` (which would re-fetch identity).
  ;;
  ;; `normalized` runs the same per-entity write-side rules the base
  ;; `sp/create-entity` applies internally (notably `:binding`'s
  ;; `:value-present true` when `:value` is present but the flag
  ;; isn't). Without this, the BASE row gets `:value-present true`
  ;; via the base-storage's own normalizer call, but the VERSION
  ;; row was built off the un-normalized `data` map — every binding
  ;; created here through CRUD landed in the version table with
  ;; `:value-present nil`, and versioned reads (which overlay
  ;; version-data over identity via `merge identity version`) then
  ;; overwrote the base's `true` back to `nil`. Result: every freshly-
  ;; created literal-bound arg disappeared from arg-overlays in the
  ;; editor (the layout walker classifies `value-present=nil` as
  ;; `:free`, no value-node).
  (let [id (or (:id data)
               (tombstoned-natural-key-id base-storage branch-id entity-name data)
               (random-uuid))
        normalized (sp/standard-crud-normalize-data entity-name
                                                    (assoc data :id id))
        do-create!
        (fn [st]
          (uniq/check-list-item-position-collision! st branch-id entity-name normalized)
          (uniq/check-fn-name-collision! st branch-id entity-name normalized)
          (uniq/check-resource-override-path-collision! st branch-id entity-name normalized)
          (let [existing (sp/read-entity st entity-name id)
                base-row (strip-version-framework-cols entity-name normalized)]
            (if existing
              (apply-identity-field-diff!
                st entity-name id base-row existing
                (:version-data-fields (get res/entity-config entity-name)))
              (sp/create-entity st entity-name base-row))
            (let [{:keys [version-id-field version-data-fields]}
                  (get res/entity-config entity-name)
                  current-version (when existing
                                    (res/resolve-version st entity-name id branch-id))
                  current-data (when current-version
                                 (select-keys (merge existing
                                                     (dissoc current-version
                                                             :id :branch-id :created-at
                                                             version-id-field))
                                              version-data-fields))
                  new-data (select-keys normalized version-data-fields)]
              (when (or (nil? current-version) (not= current-data new-data))
                (create-version-record! st entity-name id branch-id normalized))))
          normalized)
        ;; A `:binding-list-item` append's collision check + version insert
        ;; must be atomic w.r.t. concurrent appends to the SAME binding —
        ;; otherwise two appends read the same used-positions, compute the
        ;; same next position, and both insert there, corrupting the
        ;; per-branch sequence order. (The `(binding,position)` UNIQUE index
        ;; was dropped because uniqueness is a per-branch RESOLVED-VIEW
        ;; property, not a base-table one.) Serialize on a per-binding
        ;; `pg_advisory_xact_lock` (auto-released at commit/rollback): the
        ;; loser then sees the committed position and its collision check
        ;; fires loudly instead of silently double-inserting. Named-:fn
        ;; creates ride the same mechanism keyed on (branch, ns, name) —
        ;; their retired UNIQUE moved to check-fn-name-collision! too.
        lock-key (uniq/collision-lock-key branch-id entity-name normalized)]
    ;; One transaction either way: off the lock path the base-row +
    ;; version-row inserts would otherwise run on autocommit as separate
    ;; statements, and a crash between them leaves a versionless GHOST
    ;; identity — invisible to resolved reads, only healed later by the
    ;; `ids-without-chain-version` force-a-version backstop.
    (tx/in-transaction base-storage
                       (fn [st]
                         (uniq/xact-lock! (tx/datasource st) [lock-key])
                         (do-create! st)))))


(defn- collision-writes
  "Which resolved-view uniqueness keys an update of `entity-name` with
   `data` (merged onto the current row as `merged`) can move — only those
   need their check and its advisory lock; every other update (the common
   case: description, value, effects, …) skips both.

   - `:name?` — a rename / namespace-move can land on an occupied
     `(ns, name)`;
   - `:path?` — a `:resource-override` path RE-POINT can land on a path
     another live override holds;
   - `:position?` — a `:binding-list-item` position / binding move can
     land on a `(binding-id, position)` another item holds."
  [entity-name data merged]
  {:name? (boolean (and (= :fn entity-name)
                        (:name merged)
                        (or (contains? data :name)
                            (contains? data :namespace-id))))
   :path? (boolean (and (= :resource-override entity-name)
                        (:path merged)
                        (contains? data :path)))
   :position? (boolean (and (= :binding-list-item entity-name)
                            (:binding-id merged)
                            (some? (:position merged))
                            (or (contains? data :position)
                                (contains? data :binding-id))))})


(defn- update-entity-versioned!
  "Update one versioned entity: append a new version when the
   version-controlled fields changed, write the non-versioned ones
   straight through, serialize the collision-prone writes.

   The whole read-merge-write runs in ONE transaction, and `current` is
   resolved INSIDE it, after the row lock: two concurrent updates of
   different fields of the same row each merge onto the other's committed
   version instead of both merging onto the same stale one and the later
   commit silently undoing the earlier (a lost update)."
  [base-storage branch-id entity-name id data]
  (tx/in-transaction
    base-storage
    (fn [st]
      (uniq/xact-lock! (tx/datasource st) (uniq/row-lock-keys branch-id entity-name [id]))
      (let [current (res/resolve-entity st entity-name id branch-id)
            _ (when-not current
                (throw (ex-info "Entity not found"
                                {:type :not-found
                                 :entity-name entity-name
                                 :id id})))
            merged (merge current data)
            shape (assoc merged :id id)
            ;; Only compare version-controlled fields, not :id
            {:keys [version-data-fields]} (get res/entity-config entity-name)
            ;; Non-versioned fields (e.g. :ref-many junction fields like
            ;; :parent-ids) live on the identity row / junction tables,
            ;; not in the version table. Updates to those have to be
            ;; flowed through to base storage explicitly — without this,
            ;; PUTs that change ONLY non-versioned fields are silent
            ;; no-ops because version-data-fields-only diffing returns
            ;; equal and create-version-record! is skipped.
            non-versioned-data (apply dissoc data version-data-fields)
            {:keys [name? path? position?]} (collision-writes entity-name data merged)]
        ;; Serialize on (ns, name) for a rename/move, on the path for an
        ;; override re-point and on the owning binding for a list-item
        ;; position move — the keys the create path locks. Taken after the
        ;; row lock (the order every write path uses: branch → row →
        ;; collision), so no two transactions wait on each other in a cycle.
        (when (or name? path? position?)
          (uniq/xact-lock! (tx/datasource st)
                           [(uniq/collision-lock-key branch-id entity-name merged)]))
        (when name?
          (uniq/check-fn-name-collision! st branch-id entity-name shape))
        (when position?
          (uniq/check-list-item-position-collision! st branch-id entity-name shape))
        (when path?
          (uniq/check-resource-override-path-collision! st branch-id entity-name shape))
        ;; Skip creating new version if data unchanged. (A versionless
        ;; identity can't reach here at all — the `resolve-entity` above is
        ;; version-gated and already threw not-found. The batch path, which
        ;; the package sync uses, resolves identity-as-is and therefore
        ;; needs the explicit versionless guard it carries.)
        (when (not= (select-keys current version-data-fields)
                    (select-keys merged version-data-fields))
          (create-version-record! st entity-name id branch-id merged))
        ;; Apply non-versioned fields directly. base-storage's
        ;; update-entity handles columnar identity columns AND ref-many
        ;; junction replacement.
        (when (seq non-versioned-data)
          (sp/update-entity st entity-name id non-versioned-data))
        merged))))


(defn- hard-delete-entity!
  "Hard delete (sync / rollback) of one entity. Returns true when this
   branch had a version row to drop."
  [base-storage branch-id entity-name id]
  (let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
        all-versions (sp/query-entities base-storage version-entity
                                        {version-id-field id})
        own-versions (filterv #(= branch-id (:branch-id %)) all-versions)
        purgeable (purge/purgeable-identity-ids base-storage entity-name [id]
                                                branch-id all-versions version-id-field)]
    (when (seq own-versions)
      (sp/delete-entities base-storage version-entity (mapv :id own-versions)))
    (when (seq purgeable)
      (sp/delete-entity base-storage entity-name id))
    (pos? (count own-versions))))


(defn- batch-collision-guard!
  "Batch counterpart of the singular create/update collision protection. The
   singular paths (`create-entity-versioned!` / `update-entity-versioned!`) take
   a per-key `pg_advisory_xact_lock` and run every resolved-view uniqueness
   check — fn `(namespace-id, name)`, list-item `(binding-id, position)`,
   resource-override `path`. The batch paths ran ONLY the list-item check and NO
   lock, so a bundle sync (MCP `upsert-fn-defs`, package install) could land a
   duplicate live `(namespace-id, name)` — e.g. an editor-created random-id fn
   plus a same-named deterministic-id sync row — and a batch racing a singular
   write could double-insert. `shapes` are the full-or-merged rows about to be
   written (create: new rows; update: current⊕incoming, carrying `:id`). MUST
   run inside the write transaction (`st` carries the tx pool) so lock + check +
   write are one critical section."
  [st branch-id entity-name shapes]
  ;; Every collision lock in ONE sorted statement (deadlock-free — see
  ;; `uniq/xact-lock!`), not one round trip per fn / binding.
  (uniq/xact-lock! (tx/datasource st)
                   (keep #(uniq/collision-lock-key branch-id entity-name %) shapes))
  ;; Intra-batch duplicates both pass the against-storage check (neither is
  ;; committed yet), so reject them up front (pure).
  (when (= entity-name :fn)
    (when-let [[k items] (some (fn [[k items]] (when (and (second k) (> (count items) 1)) [k items]))
                               (group-by (juxt :namespace-id :name) shapes))]
      (throw (ex-info (str "Batch contains fns with duplicate (namespace-id, name): " (pr-str k))
                      {:type :constraint-violation/fn-name-collision
                       :entity-name :fn :name (second k) :namespace-id (first k)
                       :colliding-fn-ids (mapv :id items)}))))
  (when (= entity-name :binding-list-item)
    (when-let [[k items] (some (fn [[k items]]
                                 (when (and (some? (first k)) (some? (second k))
                                            (> (count items) 1))
                                   [k items]))
                               (group-by (juxt :binding-id :position) shapes))]
      (throw (ex-info "Batch contains items with duplicate (binding-id, position)"
                      {:type :constraint-violation/position-collision
                       :entity-name :binding-list-item :binding-id (first k) :position (second k)
                       :colliding-item-ids (mapv :id items)}))))
  ;; Against-storage resolved-view checks, each batched over the whole
  ;; batch (and a no-op for the entity types it doesn't apply to).
  (uniq/check-list-item-position-collisions! st branch-id entity-name shapes)
  (uniq/check-fn-name-collisions! st branch-id entity-name shapes)
  (uniq/check-resource-override-path-collisions! st branch-id entity-name shapes))


(defn- create-entities-versioned!
  "Batch create of a versioned entity: identity rows + version rows, in
   one transaction."
  [base-storage branch-id entity-name data-seq]
  ;; Versioned: batch create base records + batch create version records
  (let [data-with-ids (mapv (fn [data]
                              (if (:id data) data (assoc data :id (random-uuid))))
                            data-seq)
        config (get res/entity-config entity-name)
        ;; The base-row + version-row batch writes must land under ONE
        ;; transaction (M3): off the lock path they otherwise ran on
        ;; autocommit as separate statements, so a crash between them left
        ;; versionless GHOST identities. The against-storage collision
        ;; check + existence read run inside the same tx as the writes.
        do-batch!
        (fn [st]
          ;; Full resolved-view collision protection (locks + fn-name +
          ;; list-item + override), mirroring the singular create-entity — the
          ;; batch path previously ran only the list-item check and no lock.
          (batch-collision-guard! st branch-id entity-name data-with-ids)
          (let [ids (mapv :id data-with-ids)
                ;; Find which base records don't exist yet
                existing-by-id (sp/read-entities st entity-name ids)
                existing-ids (set (keys existing-by-id))
                {existing-records true new-base-records false}
                (group-by #(contains? existing-ids (:id %)) data-with-ids)]
            ;; Batch create base records. Identity-plane INSERT: strip
            ;; version-plane bookkeeping a caller may echo from a
            ;; version-row read (the crud rollback replay re-creates
            ;; captured pre-state rows) — the identity table has no
            ;; such column. Rows whose identity already exists flow
            ;; their identity-field diffs through update (guarded
            ;; no-op when unchanged) instead of being silently
            ;; write-once.
            (when (seq new-base-records)
              (sp/create-entities st entity-name
                                  (mapv #(strip-version-framework-cols entity-name %)
                                        new-base-records)))
            (doseq [row existing-records]
              (apply-identity-field-diff!
                st entity-name (:id row)
                (strip-version-framework-cols entity-name row)
                (get existing-by-id (:id row))
                (:version-data-fields config)))
            ;; Prepare and batch create version records
            (let [timestamp (now)
                  version-records (mapv #(prepare-version-record config (:id %) branch-id
                                                                 timestamp %)
                                        data-with-ids)]
              (sp/create-entities st (:version-entity config) version-records)))
          data-with-ids)]
    (tx/in-transaction base-storage do-batch!)))


(declare do-update-writes!)


(defn- update-entities-versioned!
  "Batch update of a versioned entity: resolve current versions, append a
   version row per CHANGED record, flow non-versioned fields through.

   One transaction; the current versions are resolved INSIDE it, after the
   row locks — the singular update's lost-update rule, batched (the row
   keys are bucketed, so a sync of thousands of rows takes a bounded
   number of advisory locks)."
  [base-storage branch-id entity-name data-seq]
  (tx/in-transaction
    base-storage
    (fn [st]
      (let [ids (mapv :id data-seq)
            _ (uniq/xact-lock! (tx/datasource st) (uniq/row-lock-keys branch-id entity-name ids))
            identity-records (vals (sp/read-entities st entity-name ids))
            current-versions (res/resolve-entities-batch st entity-name
                                                         identity-records branch-id)
            current-by-id (into {} (map (fn [e] [(:id e) e])) (vals current-versions))
            missing-ids (remove #(contains? current-by-id %) ids)]
        (when (seq missing-ids)
          (throw (ex-info "Entities not found"
                          {:type :not-found
                           :entity-name entity-name
                           :missing-ids (vec missing-ids)})))
        (let [{:keys [version-entity version-data-fields] :as config}
              (get res/entity-config entity-name)
              ;; Merged shapes (current ⊕ incoming) — what each row resolves to
              ;; after the update; the collision guard judges THESE so a
              ;; position/name/path-only update is checked against the rest of
              ;; the chain (a rename to an already-live name is rejected, not
              ;; silently duplicated).
              merged-shapes (mapv (fn [data]
                                    (assoc (merge (get current-by-id (:id data)) data)
                                           :id (:id data)))
                                  data-seq)]
          ;; Full resolved-view collision protection (advisory locks +
          ;; fn-name + list-item + override), mirroring the singular update.
          (batch-collision-guard! st branch-id entity-name merged-shapes)
          (do-update-writes! st config branch-id entity-name data-seq
                             current-by-id version-entity version-data-fields))))))


(defn- do-update-writes!
  "The version-row + non-versioned write half of `update-entities-versioned!`,
   split out so the batch update's `do-update!` closure stays readable. Runs on
   the tx-bound `st`."
  [st config branch-id entity-name data-seq current-by-id version-entity version-data-fields]
  (let [ids (mapv :id data-seq)
        timestamp (now)
        ;; An identity row with NO version on the chain resolves to
        ;; ITSELF (resolution/resolve-entities-batch's identity-as-is
        ;; fallback), so a content-equal update diffs to nothing — and
        ;; without a version row the READ path (`resolve-all-entities`,
        ;; which lists only entities that have one) never returns it.
        ;; Such remnants are real: item-ids are deterministic per
        ;; (binding, position), so an older, longer list leaves identity
        ;; rows that a later sync re-touches. Growing a package list
        ;; through one silently dropped a route from the live demo's
        ;; router while a fresh-DB run stayed green.
        ;; So: force a version for any id that has none, whatever the
        ;; diff. The hard-delete path now purges a sole-branch identity
        ;; outright (see `delete-entities` :else), so this is the
        ;; BACKSTOP for identities another branch still pins — and the
        ;; probe is chain-scoped + merge-aware for exactly that case: a
        ;; version living only on an unrelated branch must not satisfy
        ;; THIS branch's visibility.
        versionless-ids (res/ids-without-chain-version
                          st entity-name ids branch-id)
        ;; Build version records for changed entities + versionless ones
        version-records
        (into []
              (keep (fn [data]
                      (let [id (:id data)
                            current (get current-by-id id)
                            merged (merge current data)
                            current-data (select-keys current version-data-fields)
                            merged-data (select-keys merged version-data-fields)]
                        (when (or (not= current-data merged-data)
                                  (contains? versionless-ids id))
                          (prepare-version-record config id branch-id
                                                  timestamp merged)))))
              data-seq)]
    ;; Batch create all version records
    (when (seq version-records)
      (sp/create-entities st version-entity version-records))
    ;; Ref-many junctions (notably fn `:parent-ids`) and other
    ;; non-versioned columns live on the base identity row, not in
    ;; a version record — the diff above only covers
    ;; `version-data-fields`. Flow every non-versioned change
    ;; through to base storage, whose singular `update-entity`
    ;; merges with the existing row (filling NOT-NULL columns) and
    ;; reconciles ref-many junctions. Without this a base-fn →
    ;; composed-fn parent change is silently dropped. Mirrors the
    ;; singular `update-entity` above; guarded so an unchanged
    ;; re-sync stays a no-op.
    (doseq [data data-seq
            :let [id (:id data)
                  non-versioned (apply dissoc data :id version-data-fields)
                  current (get current-by-id id)]
            :when (and (seq non-versioned)
                       (not= non-versioned
                             (select-keys current (keys non-versioned))))]
      (sp/update-entity st entity-name id non-versioned))
    ;; Return merged data for all records (including unchanged)
    (mapv (fn [data]
            (merge (get current-by-id (:id data)) data))
          data-seq)))


(defn- hard-delete-entities!
  "Batch hard delete (sync / rollback). Returns the count of ids that had
   a version on this branch."
  [base-storage branch-id entity-name ids]
  ;; Batch hard delete: drop this branch's version rows, and — for
  ;; ids no other branch retains a version of — the identity rows
  ;; too (see `purge/purgeable-identity-ids`; the singular `delete-entity`
  ;; mirrors this).
  (let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
        ;; Single WHERE IN query for ALL branches' versions — the
        ;; current branch's are deleted, the others gate the
        ;; identity purge.
        all-versions (sp/query-entities base-storage version-entity
                                        {version-id-field (vec ids)})
        own-versions (filterv #(= branch-id (:branch-id %)) all-versions)
        ;; Count unique entity-ids that had versions on this branch
        ;; (return-value contract, unchanged)
        deleted-entity-ids (into #{} (map version-id-field) own-versions)
        purgeable (purge/purgeable-identity-ids base-storage entity-name (vec ids)
                                                branch-id all-versions version-id-field)]
    (when (seq own-versions)
      (sp/delete-entities base-storage version-entity (mapv :id own-versions)))
    (when (seq purgeable)
      (sp/delete-entities base-storage entity-name purgeable))
    (count deleted-entity-ids)))


(defn- live-ref-many-owners
  "Owners of `target-id` through the `field-name` junction, filtered to
   the ones still ALIVE on this branch."
  [base-storage branch-id entity-name field-name target-id]
  ;; Junction tables are NOT versioned (the model versions :parent-ids only via
  ;; fn re-creation, not a separate junction-version table), so the base query
  ;; has to answer from the junction rows — and those name every owner that
  ;; EVER pointed here, including owners this branch has since deleted.
  ;;
  ;; Deletion is a TOMBSTONE, not a row removal. Passing the base answer
  ;; through therefore reported dead children as live dependents, and the
  ;; delete guard built on this ("Graph is a parent of N other graph(s) —
  ;; remove the dependents first") refused forever:
  ;;
  ;;     DELETE child  -> 200
  ;;     DELETE parent -> 409   "is a parent of 1 other graph"
  ;;
  ;; A fn that ever had a child could not be deleted again, by anyone — the
  ;; user in the editor, or a test cleaning up after itself. The e2e suite hit
  ;; it every run: the leaked parents stayed in the graph and the NEXT test
  ;; file failed on them, which is where the "flaky e2e" investigations all
  ;; went, and why they never found anything.
  ;;
  ;; The junction is the right place to ask WHO pointed here. It is not the
  ;; place to ask WHO STILL EXISTS. Resolve the owners against this branch and
  ;; keep the living.
  (let [owner-ids (sp/query-ref-many-owners base-storage entity-name
                                            field-name target-id)]
    (if (or (empty? owner-ids) (not (res/versioned-entity? entity-name)))
      owner-ids
      (let [identity-records (vals (sp/read-entities base-storage entity-name
                                                     (vec owner-ids)))
            alive (into #{}
                        (map :id)
                        (vals (res/resolve-entities-batch
                                base-storage entity-name
                                identity-records branch-id)))]
        (filterv alive owner-ids)))))


(def ^:dynamic *enforce-require-merge?*
  "Armed `true` by the HTTP request dispatch (`system.branch-router`);
   `false` during boot / system / merge writes. When true, a DIRECT write
   to a `:require-merge?` branch's versioned graph entity is refused
   (`:branch/merge-required`) — GitHub-style 'push only via merge'. Merge
   writes never reach this path (they write version rows straight to
   base-storage, whose `:fn-version` &c. are not versioned entities), so a
   merge INTO a protected branch is allowed. Default false so boot's
   package sync — which writes to main through VersionedStorage but never
   goes through dispatch — is never gated."
  false)


(defn- branch-requires-merge?
  "Does `branch-id`'s `:branch` row carry `:require-merge?`? One direct
   (non-versioned) base read; nil branch-id (unset) → false."
  [base-storage branch-id]
  (boolean (and branch-id
                (:require-merge? (sp/read-entity base-storage :branch branch-id)))))


(defn- assert-not-merge-protected!
  "Refuse a DIRECT write to a merge-protected branch. Called once per
   write-method entry (not per row) for versioned graph entities, and only
   when enforcement is armed. Merge is exempt structurally (see the var
   docstring)."
  [base-storage branch-id entity-name]
  (when (and *enforce-require-merge?*
             (res/versioned-entity? entity-name)
             (branch-requires-merge? base-storage branch-id))
    (let [reason (str "This branch accepts changes only via merge — "
                      "push-only-via-merge is on. Work on another branch "
                      "and merge into it.")]
      ;; `:reason` is the user-facing text — `crud.entities` surfaces it
      ;; verbatim (no opaque ref), and `web/errors` maps the type to 409.
      (throw (ex-info reason
                      {:type :branch/merge-required :reason reason
                       :branch-id branch-id :entity entity-name})))))


(defrecord VersionedStorage
  [base-storage branch-id]

  sp/Storage

  (initialize
    [_ schema]
    (sp/initialize base-storage schema))


  (close
    [_]
    (sp/close base-storage))


  sp/StorageIntrospection

  (current-entities
    [_]
    (sp/current-entities base-storage))


  (current-fields
    [_ entity-name]
    (sp/current-fields base-storage entity-name))


  (current-enums
    [_]
    (sp/current-enums base-storage))


  (current-enum-values
    [_ enum-name]
    (sp/current-enum-values base-storage enum-name))


  (schema-metadata
    [_]
    (sp/schema-metadata base-storage))


  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (with-write* base-storage entity-name
      (fn []
        (if-not (res/versioned-entity? entity-name)
          (sp/create-entity base-storage entity-name data)
          (create-entity-versioned! base-storage branch-id entity-name data)))))


  (read-entity
    [_ entity-name id]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entity base-storage entity-name id)
      (res/resolve-entity base-storage entity-name id branch-id)))


  (update-entity
    [_ entity-name id data]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (with-write* base-storage entity-name
      (fn []
        (if-not (res/versioned-entity? entity-name)
          (sp/update-entity base-storage entity-name id data)
          (update-entity-versioned! base-storage branch-id entity-name id data)))))


  (delete-entity
    [_ entity-name id]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (with-write* base-storage entity-name
      (fn []
        (cond
          (not (res/versioned-entity? entity-name))
          (sp/delete-entity base-storage entity-name id)

          ;; User-facing delete: tombstone so an inherited entity is hidden too.
          *tombstone-delete?*
          (tombstone-version! base-storage entity-name id branch-id)

          ;; Hard delete (sync / rollback): drop this branch's own version
          ;; rows, and — when no other branch retains a version — the
          ;; identity row too, so no versionless ghost survives to swallow
          ;; a later re-mint of the same deterministic id.
          :else
          (hard-delete-entity! base-storage branch-id entity-name id)))))


  (query-entities
    [_ entity-name where]
    (if-not (res/versioned-entity? entity-name)
      (sp/query-entities base-storage entity-name where)
      ;; Branch chain cache is process-wide (`global-chain-cache`)
      ;; — no per-call binding needed.
      (res/resolve-all-entities base-storage entity-name branch-id where)))


  (query-entities
    [this entity-name where opts]
    (cond
      (or (nil? opts) (empty? opts))
      (sp/query-entities this entity-name where)

      (res/versioned-entity? entity-name)
      ;; Versioned reads run a deduplicate-by-id resolver that
      ;; collapses N version rows into ≤ N base rows. Applying
      ;; ORDER BY / LIMIT at the SQL layer would happen BEFORE that
      ;; dedup and silently return the wrong page. Refuse rather
      ;; than mislead — callers that need pagination over a
      ;; versioned table should fetch + page in memory.
      (throw (ex-info (str "query-entities :opts not supported on versioned entity "
                           entity-name " — pagination must run after version resolution")
                      {:type :storage-error/unsupported-opts
                       :entity-name entity-name :opts opts}))

      :else
      (sp/query-entities base-storage entity-name where opts)))


  (query-latest-per-group
    [_ entity-name where group-cols]
    ;; Pure pass-through to the base storage. The resolver layer
    ;; (resolution/load-merge-aware-cache) drives this method
    ;; against version-entity tables directly; it is NOT meant to be
    ;; invoked on identity tables through VersionedStorage's
    ;; resolution wrap. If a future caller asks for dedup on a
    ;; versioned IDENTITY entity, we'd need a parallel resolution
    ;; path — defer until that materialises.
    (sp/query-latest-per-group base-storage entity-name where group-cols))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (with-write* base-storage entity-name
      (fn []
        (if-not (res/versioned-entity? entity-name)
          (sp/create-entities base-storage entity-name data-seq)
          (create-entities-versioned! base-storage branch-id entity-name data-seq)))))


  (read-entities
    [_ entity-name ids]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entities base-storage entity-name ids)
      ;; Batch resolve: load identity records, then batch resolve versions
      (let [identity-records (vals (sp/read-entities base-storage entity-name ids))]
        (res/resolve-entities-batch base-storage entity-name identity-records branch-id))))


  (update-entities
    [_ entity-name data-seq]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (with-write* base-storage entity-name
      (fn []
        (if-not (res/versioned-entity? entity-name)
          (sp/update-entities base-storage entity-name data-seq)
          (update-entities-versioned! base-storage branch-id entity-name data-seq)))))


  (upsert-entities
    [this entity-name data-seq]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (if-not (res/versioned-entity? entity-name)
      (sp/upsert-entities base-storage entity-name data-seq)
      ;; For versioned: batch check existence in BASE storage (no version
      ;; resolution), then create/update accordingly. The identity probe is
      ;; deliberately the cheap one — boot syncs thousands of fn-defs through
      ;; here, and resolving every id's version chain up front turned the
      ;; O(n) read into O(n × versions) and stalled startup.
      (let [ids (keep :id data-seq)
            existing-ids (if (seq ids)
                           (set (keys (sp/read-entities base-storage entity-name (vec ids))))
                           #{})
            {to-update true to-create false}
            (group-by #(contains? existing-ids (:id %)) data-seq)]
        ;; Batch create new records
        (when (seq to-create)
          (sp/create-entities this entity-name to-create))
        ;; Batch update existing records
        (when (seq to-update)
          (revive-or-update! this entity-name to-update))
        ;; Return all records
        (vec data-seq))))


  (delete-entities
    [_ entity-name ids]
    (assert-not-merge-protected! base-storage branch-id entity-name)
    (with-write* base-storage entity-name
      (fn []
        (cond
          (not (res/versioned-entity? entity-name))
          (sp/delete-entities base-storage entity-name ids)

          *tombstone-delete?*
          (count (filterv #(tombstone-version! base-storage entity-name % branch-id) ids))

          :else
          (hard-delete-entities! base-storage branch-id entity-name ids)))))


  (query-ref-many-owners
    [_ entity-name field-name target-id]
    (live-ref-many-owners base-storage branch-id entity-name field-name target-id))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    (gc/validate-no-dependency-cycle! this owner-fn-id value-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; Batch resolution loads all data in 4 queries + BFS in memory
    ;; (avoiding the N+1 ~400-query path). Chain hierarchy lookups
    ;; consult the process-wide `global-chain-cache` directly.
    (let [result (res/resolve-execution-graph-batch base-storage fn-id branch-id)]
      (when (empty? (:fns result))
        (throw (ex-info "Function not found"
                        {:type :not-found
                         :fn-id fn-id})))
      (graph/->execution-graph result))))


;; === Branch Operations ===

(defn- ensure-main-branch!
  "Creates the 'main' branch if it doesn't exist. Returns its id."
  [base-storage]
  (let [existing (sp/query-entities base-storage :branch {:name "main"})]
    (if (seq existing)
      (:id (first existing))
      (let [id (random-uuid)]
        (sp/create-entity base-storage :branch
                          {:id id
                           :name "main"
                           :base-branch-id nil
                           :created-at (now)})
        id))))


(defn wrap-with-versioning
  "Wraps a storage with versioning support.

   Creates a 'main' branch if it doesn't exist.
   Returns a VersionedStorage on the main branch.

   Arguments:
   - base-storage: Any storage implementing Storage protocols, initialized with
     versioned-data-schema (or compatible schema that includes version entities)

   Example:
   (def schema (vds/build-schema (mds/create-builder)))
   (def base (-> (pg/create-storage config) (sp/initialize-with-cleanup! schema)))
   (def storage (wrap-with-versioning base))"
  ([base-storage]
   (wrap-with-versioning base-storage "main"))
  ([base-storage branch-name]
   (let [branch-id (if (= branch-name "main")
                     (ensure-main-branch! base-storage)
                     (let [existing (sp/query-entities base-storage :branch
                                                       {:name branch-name})]
                       (if (seq existing)
                         (:id (first existing))
                         (throw (ex-info "Branch not found"
                                         {:type :not-found
                                          :branch-name branch-name})))))]
     (->VersionedStorage base-storage branch-id))))


(defn create-branch!
  "Creates a new branch forked from the current branch (or specified base).
   Returns the branch record.

   Arguments:
   - versioned-storage: VersionedStorage instance
   - branch-name: Name for the new branch (must be unique)
   - opts: Optional map with :base-branch-id to fork from a different
     branch, :forbid-invalid? to set the merge-policy flag
     (error-tolerance Phase 5 — merges INTO the branch are refused
     while recorded type diagnostics exist on either side), and the
     protected-branch pair :owner-id / :write-policy (Stage 1 —
     enforced by the tenancy addon's authorize-writer), and
     :require-merge? (Stage 2 — 'push only via merge', enforced in
     open core)."
  ([versioned-storage branch-name]
   (create-branch! versioned-storage branch-name {}))
  ([versioned-storage branch-name {:keys [base-branch-id forbid-invalid?
                                          owner-id write-policy require-merge?]}]
   (let [base (:base-storage versioned-storage)
         parent-id (or base-branch-id (:branch-id versioned-storage))]
     (with-bump* base :branch
       (fn []
         ;; Under the PARENT's branch lock — the one `delete-branch!` takes
         ;; on the branch it removes — re-read the parent: a delete that
         ;; committed first is seen (no child planted under a missing
         ;; parent), a delete that comes second sees this child and refuses.
         (tx/in-transaction
           base
           (fn [st]
             (mrg/lock-branches! st parent-id)
             (when-not (sp/read-entity st :branch parent-id)
               (throw (ex-info "Base branch not found"
                               {:type :not-found :branch-id parent-id})))
             (sp/create-entity st :branch
                               (cond-> {:id (random-uuid)
                                        :name branch-name
                                        :base-branch-id parent-id
                                        :created-at (now)}
                                 ;; cond-> (not a bare assoc): an absent optional key
                                 ;; must not surface as an explicit nil column write.
                                 (some? forbid-invalid?)
                                 (assoc :forbid-invalid? (boolean forbid-invalid?))
                                 (some? owner-id)
                                 (assoc :owner-id owner-id)
                                 (some? write-policy)
                                 (assoc :write-policy write-policy)
                                 (some? require-merge?)
                                 (assoc :require-merge? (boolean require-merge?)))))))))))


(defn switch-branch
  "Returns a new VersionedStorage pointing to the specified branch.
   Does NOT copy data. Just changes the branch context."
  [versioned-storage branch-id]
  (when-not (sp/read-entity (:base-storage versioned-storage) :branch branch-id)
    (throw (ex-info "Branch not found"
                    {:type :not-found
                     :branch-id branch-id})))
  (->VersionedStorage (:base-storage versioned-storage) branch-id))


(defn list-branches
  "Lists all branches."
  [versioned-storage]
  (sp/query-entities (:base-storage versioned-storage) :branch {}))


(defn get-branch
  "Returns branch record by id, or nil."
  [versioned-storage branch-id]
  (sp/read-entity (:base-storage versioned-storage) :branch branch-id))


(defn current-branch-id
  "Returns the current branch-id."
  [versioned-storage]
  (:branch-id versioned-storage))


(defn versioned-storage?
  "Returns true if storage is a VersionedStorage wrapper."
  [storage]
  (instance? VersionedStorage storage))


(defn unwrap
  "Returns the base storage from a VersionedStorage wrapper.
   Returns the storage unchanged if it's not a VersionedStorage."
  [storage]
  (if (versioned-storage? storage)
    (:base-storage storage)
    storage))


;; === Merge Operations ===

(defn detect-conflicts
  "Finds entities modified in both source and target branches after fork point.

   Returns a map:
   {:conflicts [{:entity-name :fn
                 :entity-id uuid
                 :source-version <resolved version data>
                 :target-version <resolved version data>}]
    :fork-point <Instant>}"
  [versioned-storage source-branch-id]
  (mrg/detect-conflicts (:base-storage versioned-storage)
                        source-branch-id (:branch-id versioned-storage)))


(defn merge-branch!
  "Merges source branch into the current (target) branch.

   Creates a branch-merge record that makes source versions visible on target
   via the resolution algorithm. No version records are copied.

   If conflicts exist, throws unless conflict-resolutions are provided.

   conflict-resolutions: {[entity-name entity-id] :source | :target}
   - :source — keep source branch version
   - :target — keep target branch version

   Returns the branch-merge record."
  ([versioned-storage source-branch-id]
   ;; Delegate to the 2-arity so a no-opts merge ALSO bumps the graph
   ;; epoch. Merge is a graph-shaped write (it surfaces every source
   ;; version on the target), so without the bump the branch-router's
   ;; lazy epoch validation keeps serving the target's pre-merge
   ;; compiled/resolved view — a stale read after a branch op. The
   ;; `mrg` 1-arity is itself just the 2-arity with `{}`, so this is
   ;; behaviour-identical apart from the (previously missing) bump.
   (merge-branch! versioned-storage source-branch-id {}))
  ([versioned-storage source-branch-id opts]
   ;; The merge record is written via base storage inside the merge
   ;; transaction; bump the graph epoch here (bump-before-write) so a
   ;; committed merge is always visible to the router's lazy epoch
   ;; validation even when the eager post-commit invalidate is skipped.
   (with-bump* (:base-storage versioned-storage) :branch-merge
     (fn []
       (let [result (mrg/merge-branch! versioned-storage source-branch-id opts)]
         ;; See the 2-arity note — post-commit target-branch invalidation.
         (diag/clear-branch! (:branch-id versioned-storage))
         result)))))


;; === Delete Branch ===

(defn- assert-not-merge-source!
  "Refuse to delete a branch that is a live MERGE SOURCE. Merge is
   by-reference — no version rows are copied — so the target's merged-in
   content lives entirely in THIS branch's version rows. Deleting them
   would silently revert every such target to its pre-merge state, leaving
   versionless ghost identities. (Targets already deleted don't count —
   their merge records were removed with them.) Read on the tx storage
   UNDER the branch lock, so a racing merge is either already
   committed-and-visible or blocked behind us."
  [st branch-id]
  (let [live-targets (into []
                           (comp (map :target-branch-id)
                                 (distinct)
                                 (filter #(some? (sp/read-entity st :branch %))))
                           (sp/query-entities st :branch-merge {:source-branch-id branch-id}))]
    (when (seq live-targets)
      (throw (ex-info (str "Branch is a merge source for " (count live-targets)
                           " branch(es) that still exist — deleting it would "
                           "revert their merged-in content. Delete those "
                           "branches first, or keep this one.")
                      {:type :constraint-violation/branch-is-merge-source
                       :branch-id branch-id
                       :merged-into-branch-ids live-targets})))))


(defn- assert-no-children!
  "Refuse to delete a branch other branches fork from — they resolve
   through its rows. Read UNDER the branch lock that `create-branch!`
   also takes on the parent, so a fork racing the delete either landed
   first (and is seen here) or waits and then finds its parent gone."
  [st branch-id]
  (let [children (sp/query-entities st :branch {:base-branch-id branch-id})]
    (when (seq children)
      (throw (ex-info "Branch has child branches"
                      {:type :constraint-violation/branch-has-children
                       :branch-id branch-id
                       :child-branch-ids (mapv :id children)})))))


(def ^:private purge-order
  "Leaf-first, so a parent identity's child-reference guard
   (`purge/purgeable-identity-ids`) no longer sees children purged in the
   same delete."
  [:binding-list-item :binding :fn-slot :resource-override :fn])


(defn- purge-versionless-identities!
  "After a branch's version rows are gone, drop the identity rows that now
   have NO version on any branch — the rows created on that branch. They
   resolve nowhere, and left behind they are a leak the tenancy quota
   counts (it counts identity rows) and nothing ever reclaims.
   `own-ids-by-entity` are the ids that had a version on the branch; an id
   still versioned elsewhere, or referenced by a surviving row
   (`purge/purgeable-identity-ids`), stays. `:fn` repeats to a fixpoint: a
   branch-created fn referenced only by another branch-created fn (a
   return type, a parent) is released once that one is gone."
  [st branch-id own-ids-by-entity]
  (doseq [entity-name purge-order
          :let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
                ids (vec (get own-ids-by-entity entity-name))]
          :when (seq ids)]
    (let [still-versioned (into #{} (map version-id-field)
                                (sp/query-entities st version-entity {version-id-field ids}))]
      (loop [candidates (into [] (remove still-versioned) ids)]
        (let [purgeable (when (seq candidates)
                          (purge/purgeable-identity-ids st entity-name candidates
                                                        branch-id [] version-id-field))]
          (when (seq purgeable)
            (sp/delete-entities st entity-name purgeable)
            (when (= :fn entity-name)
              (recur (into [] (remove (set purgeable)) candidates)))))))))


(defn- delete-branch-rows!
  "The delete itself, on the tx storage `st` — see `delete-branch!`.
   Returns the branch's own (now deleted) binding version rows."
  [st branch-id]
  ;; Serialize against a concurrent merge that uses this branch as SOURCE
  ;; (which locks both its endpoints) and against a fork off it
  ;; (`create-branch!` locks the parent). Taken FIRST, so the guards below
  ;; and the deletes see a lock-stable view — a merge or fork committing
  ;; after an unlocked guard read would otherwise be missed (L5).
  (mrg/lock-branches! st branch-id)
  (assert-no-children! st branch-id)
  (assert-not-merge-source! st branch-id)
  ;; Soft-disable services scoped to this branch so the reconciler stops
  ;; them on its next pass — see the docstring's cascade note. The
  ;; `:service` entity is only registered when the services schema is
  ;; loaded (production system + integration tests); storage-only tests
  ;; use a smaller schema that omits it. Skip the cascade quietly then.
  (when (contains? (sp/current-entities st) :service)
    (let [svcs (sp/query-entities st :service {:branch-id branch-id :enabled? true})]
      (when (seq svcs)
        ;; One batched partial-UPDATE instead of a round-trip per service.
        (sp/update-entities st :service (mapv (fn [s] {:id (:id s) :enabled? false}) svcs)))))
  ;; Delete all version records on this branch (batch), remembering whose
  ;; they were for the identity purge below.
  (let [own (into {}
                  (map (fn [[entity-name {:keys [version-entity]}]]
                         (let [rows (sp/query-entities st version-entity {:branch-id branch-id})]
                           (when (seq rows)
                             (sp/delete-entities st version-entity (mapv :id rows)))
                           [entity-name rows])))
                  res/entity-config)]
    (purge-versionless-identities!
      st branch-id
      (into {}
            (map (fn [[entity-name rows]]
                   (let [{:keys [version-id-field]} (get res/entity-config entity-name)]
                     [entity-name (into #{} (map version-id-field) rows)])))
            own))
    ;; Delete branch-merge records referencing this branch.
    (let [merge-ids (into [] (comp (map :id) (distinct))
                          (concat (sp/query-entities st :branch-merge {:source-branch-id branch-id})
                                  (sp/query-entities st :branch-merge {:target-branch-id branch-id})))]
      (when (seq merge-ids)
        (sp/delete-entities st :branch-merge merge-ids)))
    ;; Change-review approvals and comments recorded against this branch
    ;; (the proposal source) would otherwise be orphaned.
    (doseq [entity [:branch-approval :branch-comment]]
      (let [ids (mapv :id (sp/query-entities st entity {:source-branch-id branch-id}))]
        (when (seq ids)
          (sp/delete-entities st entity ids))))
    (sp/delete-entity st :branch branch-id)
    (:binding own)))


(defn delete-branch!
  "Deletes a branch and all its version records.
   Throws if branch has child branches or if trying to delete main branch.
   Returns true on success.

   Cascade: any `:service` row scoped to this branch is soft-disabled
   (`:enabled? false`) BEFORE the branch row goes away. The next
   reconcile pass picks the row up via the standard diff-desired path,
   stops the running closure, and releases its advisory lock. We don't
   hard-delete the rows because they form part of the audit trail and
   may be wanted for `branch-restore` (planned). Identity rows created on
   the branch — versionless once its versions go — are purged with it.

   `opts` `:reclaim-secrets!` — `(fn [binding-versions])`, called AFTER
   the commit with the branch's deleted binding version rows: a secret
   bound on the branch lives in the vault, outside this transaction, and
   the caller reclaims the paths nothing references any more
   (`crud.secrets/sweep-orphan-secrets!`)."
  ([versioned-storage branch-id] (delete-branch! versioned-storage branch-id nil))
  ([versioned-storage branch-id {:keys [reclaim-secrets!]}]
   (let [base (:base-storage versioned-storage)
         branch (sp/read-entity base :branch branch-id)]
     (when-not branch
       (throw (ex-info "Branch not found"
                       {:type :not-found :branch-id branch-id})))
     ;; Protect the ROOT branch by its structural identity —
     ;; `:base-branch-id nil` — NOT by the display name "main". Branch
     ;; names are only `[:org-id :name]`-unique and the root id is
     ;; non-deterministic (`random-uuid`), so the name is the wrong and
     ;; org-unsafe handle: a root named otherwise would be unprotected, a
     ;; non-root named "main" falsely protected.
     (when (nil? (:base-branch-id branch))
       (throw (ex-info "Cannot delete the root branch"
                       {:type :constraint-violation/root-branch-undeletable :branch-id branch-id})))
     ;; ATOMIC: guards + service soft-disable + version-row deletes +
     ;; identity purge + merge-record deletes + the branch-row delete land
     ;; together — a mid-op failure used to leave a partially-deleted
     ;; branch (a versionless ghost branch, merge records dangling at a
     ;; deleted endpoint). Bump the graph epoch BEFORE the writes
     ;; (bump-then-write, same as the merge path): a rolled-back bump is
     ;; harmless over-invalidation, a committed delete is always preceded
     ;; by a visible bump.
     (let [binding-versions (with-bump* base :branch
                              (fn [] (tx/in-transaction base #(delete-branch-rows! % branch-id))))]
       ;; Drop any cached chain that referenced this branch as an
       ;; ancestor — globals survive across CRUD calls and would
       ;; otherwise still hand back the pre-delete chain.
       (res/invalidate-chain-cache! branch-id)
       (res/forget-merges-memo!)
       ;; The diagnostics store is per-branch and derived — a deleted
       ;; branch's entries can never be recomputed, so drop them here.
       (diag/clear-branch! branch-id)
       (when (and reclaim-secrets! (seq binding-versions))
         (reclaim-secrets! binding-versions))
       true))))


(defn query-all-graph-entities
  "Loads every entity of the slot/fn-slot/binding model with a shared
   branch-chain cache. More efficient than calling `query-entities`
   five times sequentially.

   Returns {:fns [...] :slots [...] :fn-slots [...] :bindings [...]
            :list-items [...]}."
  [versioned-storage]
  (let [{:keys [base-storage branch-id]} versioned-storage]
    ;; Chain cache is process-wide; no per-call binding required.
    {:fns        (vec (res/resolve-all-entities base-storage :fn branch-id {}))
     :slots      (vec (sp/query-entities base-storage :slot {}))
     :fn-slots   (vec (res/resolve-all-entities base-storage :fn-slot branch-id {}))
     :bindings   (vec (res/resolve-all-entities base-storage :binding branch-id {}))
     :list-items (vec (res/resolve-all-entities base-storage :binding-list-item
                                                branch-id {}))}))


(defn revive-entity!
  "Bring back an entity a user-facing delete tombstoned on THIS branch:
   write a fresh live version from the tombstone's own data (the pre-delete
   row — `tombstone-version!` copied the current data into it, so nothing
   has to be remembered elsewhere). The undo of ⋯ → Delete. Append-only
   like every other write here: the tombstone stays in history, the
   revival is one more version on top. Returns true when a version was
   written, false when nothing was tombstoned (the entity is live, never
   existed, or the tombstone GC already purged it — a 404 to the caller).

   A revival goes through the same collision checks as a create: the
   name / path / position may have been reused since (the indexes that
   once blocked that were retired precisely so a deleted key could be
   reused), and two live rows under one such key is what the checks exist
   to refuse."
  [storage entity-name id]
  (let [base (unwrap storage)
        branch-id (current-branch-id storage)]
    (assert-not-merge-protected! base branch-id entity-name)
    (with-write* base entity-name
      (fn []
        ;; A revival is a create in all but id: same transaction, same
        ;; locks, same three resolved-view checks — an fn's name, an
        ;; override's path and a list item's position may all have been
        ;; taken by a live row since the delete.
        (tx/in-transaction
          base
          (fn [st]
            (uniq/xact-lock! (tx/datasource st) (uniq/row-lock-keys branch-id entity-name [id]))
            (if-let [tomb (res/resolve-tombstone st entity-name id branch-id)]
              (let [{:keys [version-id-field]} (get res/entity-config entity-name)
                    data (res/extract-version-data tomb version-id-field)
                    shape (merge (sp/read-entity st entity-name id) data {:id id})]
                (uniq/xact-lock! (tx/datasource st)
                                 [(uniq/collision-lock-key branch-id entity-name shape)])
                (uniq/check-fn-name-collision! st branch-id entity-name shape)
                (uniq/check-resource-override-path-collision! st branch-id entity-name shape)
                (uniq/check-list-item-position-collision! st branch-id entity-name shape)
                (create-version-record! st entity-name id branch-id data)
                true)
              false)))))))
