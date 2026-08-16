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
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.merge :as mrg]
    [graphden.versioning.storage.resolution :as res]
    [graphden.versioning.storage.uniqueness :as uniq]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
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


(def ^:private identity-child-refs
  "Child identity rows that logically reference an identity row a hard
   delete may purge (no SQL FKs exist — refs are logical, index-only).
   An id with surviving children keeps its identity row so nothing
   dangles; the versionless backstop in `update-entities` still heals
   it on the next content-equal write. Callers that delete leaves
   first (`reconcile-fn-bodies!`, the crud rollback cascade) purge
   cleanly.

   The `:fn` and `:slot` lists include the INBOUND ref families (the
   same ones `graphden.dev.integrity`'s dangling-refs detector
   enumerates), not just structural children: today's hard-delete
   callers are leaf-first so those never retain, but a future caller
   hard-deleting a still-referenced fn must be met by a conservative
   keep, not a silently manufactured dangling ref. `:fn.parent-ids`
   is a ref-many junction, checked separately in
   `purgeable-identity-ids`.

   VERSION-plane referrers are checked too: a versioned ref field
   (`binding.ref-fn-id` set by a later update) lives ONLY in version
   rows — the identity row keeps the create-time NULL — so an
   identity-plane probe alone would purge a fn that a binding's
   current version still references."
  {:binding [[:binding-list-item :binding-id]]
   :slot    [[:fn-slot :slot-id] [:binding :slot-id]
             [:fn-slot-version :slot-id] [:binding-version :slot-id]]
   :fn      [[:fn-slot :fn-id] [:binding :fn-id]
             [:binding :ref-fn-id] [:binding :type-override-fn-id]
             [:binding :resolver-fn-id]
             [:binding-list-item :ref-fn-id]
             [:slot :type-fn-id]
             [:fn :base-fn-id] [:fn :element-fn-id]
             [:fn :return-type-fn-id]
             [:binding-version :ref-fn-id]
             [:binding-version :type-override-fn-id]
             [:binding-version :resolver-fn-id]
             [:binding-list-item-version :ref-fn-id]
             [:fn-version :base-fn-id] [:fn-version :element-fn-id]
             [:fn-version :return-type-fn-id]]})


(defn- purgeable-identity-ids
  "GHOST-IDENTITY prevention (the 2026-07-20 shrink-regrow class): the
   subset of hard-deleted `ids` whose identity rows can be removed
   outright — no OTHER branch retains a version row (per-branch
   isolation: a diverged branch's view must survive this branch's
   delete), and no child identity row still references them. Removing
   the identity makes a later re-mint of the same deterministic id
   flow through `create-entities` — which always writes a version — so
   the row can never get stuck invisible-on-every-list-read the way a
   surviving versionless identity does.

   Known out-of-scope corner: a NON-descendant branch that merged this
   branch reads its rows by reference (`merges-by-target`), which no
   version row on that branch records. A hard delete here already
   stripped such a row from that merge view before the purge existed
   (own versions deleted), so the purge only changes the corner's
   failure shape, not its reachability. Merge-target visibility stays
   the merge endpoint's concern."
  [base-storage entity-name ids branch-id all-versions version-id-field]
  (let [other-branch (into #{}
                           (comp (remove #(= branch-id (:branch-id %)))
                                 (map version-id-field))
                           all-versions)
        candidates (into [] (remove other-branch) ids)
        retained (when (seq candidates)
                   (into #{}
                         (mapcat (fn [[child-entity fk-field]]
                                   (map fk-field
                                        (sp/query-entities base-storage child-entity
                                                           {fk-field candidates}))))
                         (identity-child-refs entity-name)))
        ;; `:fn.parent-ids` is a ref-many junction — per-candidate owner
        ;; probe (hard-delete batches are small: sync stale rows,
        ;; rollback singles). A fn still referenced as somebody's parent
        ;; keeps its identity.
        retained (if (and (= :fn entity-name) (seq candidates))
                   (into (or retained #{})
                         (filter (fn [id]
                                   (seq (sp/query-ref-many-owners
                                          base-storage :fn :parent-ids id))))
                         candidates)
                   retained)]
    (into [] (remove (or retained #{})) candidates)))


;; === VersionedStorage Record ===

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
    (epoch/bump! base-storage entity-name)
    (if-not (res/versioned-entity? entity-name)
      (sp/create-entity base-storage entity-name data)
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
      (let [id (or (:id data) (random-uuid))
            normalized (sp/standard-crud-normalize-data entity-name
                                                        (assoc data :id id))
            do-create!
            (fn [st]
              (uniq/check-list-item-position-collision! st branch-id entity-name normalized)
              (uniq/check-fn-name-collision! st branch-id entity-name normalized)
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
            lock-key (or (when (and (= entity-name :binding-list-item)
                                    (:binding-id normalized))
                           (str (:binding-id normalized)))
                         (uniq/fn-name-lock-key branch-id entity-name normalized))]
        (cond
          (and lock-key (:pool base-storage))
          ;; `:ignore` so any nested `with-transaction` in the create path
          ;; (`crud/create-entity` opens one to write `:ref-many` junction rows —
          ;; e.g. a `:fn`'s `:parent-ids`) runs INLINE on this connection instead
          ;; of committing early. A nested commit would end THIS transaction and
          ;; release the `pg_advisory_xact_lock` before the collision check +
          ;; version insert finished, letting a racing same-name create slip
          ;; through — the (branch, ns, name) serialization would silently break.
          (binding [jdbc-tx/*nested-tx* :ignore]
            (jdbc/with-transaction [tx (:pool base-storage)]
                                   (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(hashtext(?)::bigint)"
                                                      lock-key])
                                   (do-create! (assoc base-storage :pool tx))))

          ;; No lock key, but a pooled backend: still wrap the base-row +
          ;; version-row pair in ONE transaction. Off the lock path the two
          ;; inserts otherwise run on autocommit as separate statements, so a
          ;; crash between them leaves a versionless GHOST identity — invisible
          ;; to resolved reads, only healed later by the `ids-without-chain-
          ;; version` force-a-version backstop. A plain `:binding` / `:fn-slot`
          ;; create needs the same crash-atomicity the lock path already gives
          ;; named-`:fn` / list-item writes, just without the advisory lock.
          ;; `:ignore` for the same nested-`with-transaction` reason as above.
          (:pool base-storage)
          (binding [jdbc-tx/*nested-tx* :ignore]
            (jdbc/with-transaction [tx (:pool base-storage)]
                                   (do-create! (assoc base-storage :pool tx))))

          :else
          (do-create! base-storage)))))


  (read-entity
    [_ entity-name id]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entity base-storage entity-name id)
      (res/resolve-entity base-storage entity-name id branch-id)))


  (update-entity
    [_ entity-name id data]
    (epoch/bump! base-storage entity-name)
    (if-not (res/versioned-entity? entity-name)
      (sp/update-entity base-storage entity-name id data)
      ;; Versioned: append new version with merged data (if changed)
      (let [current (res/resolve-entity base-storage entity-name id branch-id)]
        (when-not current
          (throw (ex-info "Entity not found"
                          {:type :not-found
                           :entity-name entity-name
                           :id id})))
        (let [merged (merge current data)
              ;; Only compare version-controlled fields, not :id
              {:keys [version-data-fields]} (get res/entity-config entity-name)
              current-data (select-keys current version-data-fields)
              merged-data (select-keys merged version-data-fields)
              ;; Non-versioned fields (e.g. :ref-many junction fields like
              ;; :parent-ids) live on the identity row / junction tables,
              ;; not in the version table. Updates to those have to be
              ;; flowed through to base storage explicitly — without this,
              ;; PUTs that change ONLY non-versioned fields are silent
              ;; no-ops because version-data-fields-only diffing returns
              ;; equal and create-version-record! is skipped.
              non-versioned-data (apply dissoc data version-data-fields)
              ;; A rename / namespace-move can land on an occupied
              ;; (ns, name) — same live-view rule as create. Only those two
              ;; keys can introduce a collision, so other updates (the
              ;; common case: description, effects, …) skip the check and
              ;; its lock entirely.
              name-write? (and (= :fn entity-name)
                               (:name merged)
                               (or (contains? data :name)
                                   (contains? data :namespace-id)))
              ;; A `:binding-list-item` position / binding move can land on a
              ;; (binding-id, position) another item already holds — the same
              ;; live-view rule as create. Only those two keys can introduce a
              ;; collision (mirrors `name-write?`), so other item updates
              ;; (`:value`, `:ref-fn-id`, …) skip the check + lock. Its version
              ;; insert must be serialized w.r.t. concurrent moves to the SAME
              ;; binding, exactly as the create-append path is — otherwise two
              ;; concurrent position updates both pass the check and both
              ;; commit, corrupting the per-branch sequence order.
              list-item-write? (and (= :binding-list-item entity-name)
                                    (:binding-id merged)
                                    (some? (:position merged))
                                    (or (contains? data :position)
                                        (contains? data :binding-id)))
              do-update!
              (fn [st]
                (when name-write?
                  (uniq/check-fn-name-collision! st branch-id entity-name
                                                 (assoc merged :id id)))
                (when list-item-write?
                  (uniq/check-list-item-position-collision! st branch-id entity-name
                                                            (assoc merged :id id)))
                ;; Skip creating new version if data unchanged. (A
                ;; versionless identity can't reach here at all — the
                ;; `resolve-entity` above is version-gated and already threw
                ;; not-found. The batch path, which the package sync uses,
                ;; resolves identity-as-is and therefore needs the explicit
                ;; versionless guard it carries.)
                (when (not= current-data merged-data)
                  (create-version-record! st entity-name id branch-id merged))
                ;; Apply non-versioned fields directly. base-storage's
                ;; update-entity handles columnar identity columns AND
                ;; ref-many junction replacement.
                (when (seq non-versioned-data)
                  (sp/update-entity st entity-name id non-versioned-data))
                merged)
              ;; Serialize on (branch, ns, name) for a rename/move and on the
              ;; owning binding for a list-item position move — the same
              ;; per-binding `pg_advisory_xact_lock` the create-append uses.
              lock-key (cond
                         name-write? (uniq/fn-name-lock-key branch-id entity-name merged)
                         list-item-write? (str (:binding-id merged)))]
          (if (and lock-key (:pool base-storage))
            ;; `:ignore` — same reason as the create path: a nested
            ;; `with-transaction` inside `do-update!` (crud/update-entity opens
            ;; one to replace `:ref-many` junction rows) must run INLINE, not
            ;; commit early and drop the advisory lock mid-update.
            (binding [jdbc-tx/*nested-tx* :ignore]
              (jdbc/with-transaction [tx (:pool base-storage)]
                                     (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(hashtext(?)::bigint)"
                                                        lock-key])
                                     (do-update! (assoc base-storage :pool tx))))
            (do-update! base-storage))))))


  (delete-entity
    [_ entity-name id]
    (epoch/bump! base-storage entity-name)
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
      (let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
            all-versions (sp/query-entities base-storage version-entity
                                            {version-id-field id})
            own-versions (filterv #(= branch-id (:branch-id %)) all-versions)
            purgeable (purgeable-identity-ids base-storage entity-name [id]
                                              branch-id all-versions version-id-field)]
        (when (seq own-versions)
          (sp/delete-entities base-storage version-entity (mapv :id own-versions)))
        (when (seq purgeable)
          (sp/delete-entity base-storage entity-name id))
        (pos? (count own-versions)))))


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
    (epoch/bump! base-storage entity-name)
    (if-not (res/versioned-entity? entity-name)
      (sp/create-entities base-storage entity-name data-seq)
      ;; Versioned: batch create base records + batch create version records
      (let [data-with-ids (mapv (fn [data]
                                  (if (:id data) data (assoc data :id (random-uuid))))
                                data-seq)
            config (get res/entity-config entity-name)
            ;; INTRA-batch duplicates (two items in the same batch sharing
            ;; `(binding-id, position)`) both pass the against-storage check
            ;; below — neither is committed yet — so reject them upfront
            ;; (pure, no storage), keeping batch import from a broken landing.
            _ (when (= :binding-list-item entity-name)
                (let [by-key (group-by (juxt :binding-id :position) data-with-ids)
                      dupe (some (fn [[k items]]
                                   (when (and (some? (first k))
                                              (some? (second k))
                                              (> (count items) 1))
                                     [k items]))
                                 by-key)]
                  (when dupe
                    (throw (ex-info "Batch contains items with duplicate (binding-id, position)"
                                    {:type :constraint-violation/position-collision
                                     :entity-name :binding-list-item
                                     :binding-id (ffirst dupe)
                                     :position (second (first dupe))
                                     :colliding-item-ids (mapv :id (second dupe))})))))
            ;; The base-row + version-row batch writes must land under ONE
            ;; transaction (M3): off the lock path they otherwise ran on
            ;; autocommit as separate statements, so a crash between them left
            ;; versionless GHOST identities. The against-storage collision
            ;; check + existence read run inside the same tx as the writes.
            do-batch!
            (fn [st]
              ;; Position-collision check against storage, mirroring the
              ;; singular create-entity.
              (uniq/check-list-item-position-collisions! st branch-id
                                                         entity-name data-with-ids)
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
        (if-let [pool (:pool base-storage)]
          ;; `:ignore` so a nested `with-transaction` inside an inner write
          ;; (a `:fn`'s `:parent-ids` ref-many junction replacement) runs
          ;; INLINE rather than committing early and breaking the atomic
          ;; boundary — same convention as the singular create + delete-branch.
          (binding [jdbc-tx/*nested-tx* :ignore]
            (jdbc/with-transaction [tx pool]
                                   (do-batch! (assoc base-storage :pool tx))))
          (do-batch! base-storage)))))


  (read-entities
    [_ entity-name ids]
    (if-not (res/versioned-entity? entity-name)
      (sp/read-entities base-storage entity-name ids)
      ;; Batch resolve: load identity records, then batch resolve versions
      (let [identity-records (vals (sp/read-entities base-storage entity-name ids))]
        (res/resolve-entities-batch base-storage entity-name identity-records branch-id))))


  (update-entities
    [_ entity-name data-seq]
    (epoch/bump! base-storage entity-name)
    (if-not (res/versioned-entity? entity-name)
      (sp/update-entities base-storage entity-name data-seq)
      ;; Batch update: resolve all current versions, compute diffs, batch create versions
      (let [ids (mapv :id data-seq)
            ;; Batch resolve current versions using efficient batch resolution
            identity-records (vals (sp/read-entities base-storage entity-name ids))
            current-versions (res/resolve-entities-batch base-storage entity-name
                                                         identity-records branch-id)
            current-by-id (into {} (map (fn [e] [(:id e) e])) (vals current-versions))
            ;; Check all entities exist
            missing-ids (remove #(contains? current-by-id %) ids)]
        (when (seq missing-ids)
          (throw (ex-info "Entities not found"
                          {:type :not-found
                           :entity-name entity-name
                           :missing-ids (vec missing-ids)})))
        ;; Position-collision check against storage, mirroring the singular
        ;; update-entity. Uses the merged shape (current + incoming partial
        ;; update) so a position-only update is checked against the rest of
        ;; the binding's items on the chain.
        (uniq/check-list-item-position-collisions!
          base-storage branch-id entity-name
          (vec (keep (fn [data]
                       (let [id (:id data)
                             current (get current-by-id id)]
                         (when current (assoc (merge current data) :id id))))
                     data-seq)))
        ;; Compute merged versions and filter to only changed ones
        (let [{:keys [version-entity version-data-fields] :as config}
              (get res/entity-config entity-name)
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
              ;; router (2026-07-20) while a fresh-DB run stayed green.
              ;; So: force a version for any id that has none, whatever the
              ;; diff. The hard-delete path now purges a sole-branch identity
              ;; outright (see `delete-entities` :else), so this is the
              ;; BACKSTOP for identities another branch still pins — and the
              ;; probe is chain-scoped + merge-aware for exactly that case: a
              ;; version living only on an unrelated branch must not satisfy
              ;; THIS branch's visibility.
              versionless-ids (res/ids-without-chain-version
                                base-storage entity-name ids branch-id)
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
            (sp/create-entities base-storage version-entity version-records))
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
            (sp/update-entity base-storage entity-name id non-versioned))
          ;; Return merged data for all records (including unchanged)
          (mapv (fn [data]
                  (merge (get current-by-id (:id data)) data))
                data-seq)))))


  (upsert-entities
    [this entity-name data-seq]
    (if-not (res/versioned-entity? entity-name)
      (sp/upsert-entities base-storage entity-name data-seq)
      ;; For versioned: batch check existence in BASE storage (no version resolution),
      ;; then create/update accordingly
      (let [ids (keep :id data-seq)
            ;; Check existence in base-storage directly - O(n) not O(n × versions)
            ;; We only need to know if identity record exists, not resolve versions
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
          (sp/update-entities this entity-name to-update))
        ;; Return all records
        (vec data-seq))))


  (delete-entities
    [_ entity-name ids]
    (epoch/bump! base-storage entity-name)
    (cond
      (not (res/versioned-entity? entity-name))
      (sp/delete-entities base-storage entity-name ids)

      *tombstone-delete?*
      (count (filterv #(tombstone-version! base-storage entity-name % branch-id) ids))

      :else
      ;; Batch hard delete: drop this branch's version rows, and — for
      ;; ids no other branch retains a version of — the identity rows
      ;; too (see `purgeable-identity-ids`; the singular `delete-entity`
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
            purgeable (purgeable-identity-ids base-storage entity-name (vec ids)
                                              branch-id all-versions version-id-field)]
        (when (seq own-versions)
          (sp/delete-entities base-storage version-entity (mapv :id own-versions)))
        (when (seq purgeable)
          (sp/delete-entities base-storage entity-name purgeable))
        (count deleted-entity-ids))))


  (query-ref-many-owners
    [_ entity-name field-name target-id]
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
     enforced by the tenancy addon's authorize-writer)."
  ([versioned-storage branch-name]
   (create-branch! versioned-storage branch-name {}))
  ([versioned-storage branch-name {:keys [base-branch-id forbid-invalid?
                                          owner-id write-policy]}]
   (let [parent-id (or base-branch-id (:branch-id versioned-storage))]
     (epoch/bump! (:base-storage versioned-storage) :branch)
     (sp/create-entity (:base-storage versioned-storage) :branch
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
                         (assoc :write-policy write-policy))))))


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
   (epoch/bump! (:base-storage versioned-storage) :branch-merge)
   (let [result (mrg/merge-branch! versioned-storage source-branch-id opts)]
     ;; See the 2-arity note — post-commit target-branch invalidation.
     (diag/clear-branch! (:branch-id versioned-storage))
     result)))


;; === Delete Branch ===

(defn delete-branch!
  "Deletes a branch and all its version records.
   Throws if branch has child branches or if trying to delete main branch.
   Returns true on success.

   Cascade: any `:service` row scoped to this branch is soft-disabled
   (`:enabled? false`) BEFORE the branch row goes away. The next
   reconcile pass picks the row up via the standard diff-desired path,
   stops the running closure, and releases its advisory lock. We don't
   hard-delete the rows because they form part of the audit trail and
   may be wanted for `branch-restore` (planned)."
  [versioned-storage branch-id]
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
    ;; Check no child branches
    (let [children (sp/query-entities base :branch {:base-branch-id branch-id})]
      (when (seq children)
        (throw (ex-info "Branch has child branches"
                        {:type :constraint-violation/branch-has-children
                         :branch-id branch-id
                         :child-branch-ids (mapv :id children)}))))
    ;; Refuse to delete a branch that is a live MERGE SOURCE. Merge is
    ;; by-reference — no version rows are copied — so the target's
    ;; merged-in content lives entirely in THIS branch's version rows
    ;; (read via `merges-by-target`). Deleting them (below) would
    ;; silently revert every such target to its pre-merge state, leaving
    ;; versionless ghost identities. Data-integrity beats convenience:
    ;; the merged branch stays deletable only once its targets are gone.
    ;; (Targets already deleted don't count — their merge records were
    ;; removed with them.)
    (let [source-merges (sp/query-entities base :branch-merge {:source-branch-id branch-id})
          live-targets (into []
                             (comp (map :target-branch-id)
                                   (distinct)
                                   (filter #(some? (sp/read-entity base :branch %))))
                             source-merges)]
      (when (seq live-targets)
        (throw (ex-info (str "Branch is a merge source for " (count live-targets)
                             " branch(es) that still exist — deleting it would "
                             "revert their merged-in content. Delete those "
                             "branches first, or keep this one.")
                        {:type :constraint-violation/branch-is-merge-source
                         :branch-id branch-id
                         :merged-into-branch-ids live-targets}))))
    ;; ATOMIC: service soft-disable + version-row deletes + merge-record
    ;; deletes + the branch-row delete must land together. A mid-op
    ;; failure between them used to leave a partially-deleted branch —
    ;; e.g. its version rows gone but the branch row surviving (a
    ;; versionless ghost branch), or merge records dangling at a deleted
    ;; endpoint. Wrap the whole write sequence in one transaction so it
    ;; commits whole or rolls back whole. Bump the graph epoch BEFORE the
    ;; writes (bump-then-write, same as the merge path): a rolled-back
    ;; bump is harmless over-invalidation, a committed delete is always
    ;; preceded by a visible bump. Non-PG storages (no `:pool`) fall back
    ;; to the prior sequential behaviour.
    (epoch/bump! base :branch)
    (let [do-delete!
          (fn [st]
            ;; Soft-disable services scoped to this branch so the
            ;; reconciler stops them on its next pass — see the
            ;; docstring's cascade note. The `:service` entity is only
            ;; registered when the services schema is loaded (production
            ;; system + integration tests); storage-only tests use a
            ;; smaller schema that omits it. Skip the cascade quietly in
            ;; that case rather than throwing `:table-not-found`.
            (when (contains? (sp/current-entities st) :service)
              (let [svcs (sp/query-entities st :service {:branch-id branch-id
                                                         :enabled? true})]
                (when (seq svcs)
                  ;; One batched partial-UPDATE instead of a round-trip per service.
                  (sp/update-entities st :service
                                      (mapv (fn [s] {:id (:id s) :enabled? false}) svcs)))))
            ;; Delete all version records on this branch (batch)
            (doseq [[_ {:keys [version-entity]}] res/entity-config]
              (let [version-ids (mapv :id (sp/query-entities st version-entity {:branch-id branch-id}))]
                (when (seq version-ids)
                  (sp/delete-entities st version-entity version-ids))))
            ;; Delete branch-merge records referencing this branch.
            ;; Two targeted queries are more efficient than full table scan + memory filter
            (let [source-merges (sp/query-entities st :branch-merge {:source-branch-id branch-id})
                  target-merges (sp/query-entities st :branch-merge {:target-branch-id branch-id})
                  merge-ids (into [] (comp (map :id) (distinct))
                                  (concat source-merges target-merges))]
              (when (seq merge-ids)
                (sp/delete-entities st :branch-merge merge-ids)))
            ;; Delete the branch record
            (sp/delete-entity st :branch branch-id))]
      (if-let [pool (:pool base)]
        ;; `:ignore` so a nested `with-transaction` in an inner write
        ;; (e.g. a ref-many junction replacement) runs INLINE rather than
        ;; committing early and breaking the atomic boundary.
        (binding [jdbc-tx/*nested-tx* :ignore]
          (jdbc/with-transaction [tx pool]
                                 (do-delete! (assoc base :pool tx))))
        (do-delete! base)))
    ;; Drop any cached chain that referenced this branch as an
    ;; ancestor — globals survive across CRUD calls and would
    ;; otherwise still hand back the pre-delete chain.
    (res/invalidate-chain-cache! branch-id)
    ;; The diagnostics store is per-branch and derived — a deleted
    ;; branch's entries can never be recomputed, so drop them here.
    (diag/clear-branch! branch-id)
    true))


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
