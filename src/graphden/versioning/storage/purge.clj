(ns graphden.versioning.storage.purge
  "Storage RECLAMATION for versioned entities — the two places a row is
   removed outright rather than tombstoned:

   - `purgeable-identity-ids` — which identity rows a hard delete (sync /
     rollback, `versioning.storage.core`) may drop along with this
     branch's version rows, guarded by `identity-child-refs`;
   - `tombstone-gc-sweep!` — the periodic GC (`system.init.cleanup`)
     that hard-purges entities DEAD on every branch.

   Split out of `versioning.storage.core` (one topic, not the
   VersionedStorage write paths); `core` requires this, never the
   reverse."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.identity-repair :as idrepair]
    [graphden.versioning.storage.resolution :as res]))


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


(defn purgeable-identity-ids
  "GHOST-IDENTITY prevention (the shrink-regrow class): the
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


;; =============================================================================
;; Tombstone GC — storage reclamation for provably-DEAD entities
;; =============================================================================
;;
;; A user-facing delete writes a TOMBSTONE version (`:deleted-at`) and KEEPS
;; the identity row + all version rows (so inheriting branches see the delete).
;; The storage quota counts identity rows, so a tenant that churns
;; create/delete monotonically fills its cap with dead rows it can't reclaim.
;; This GC hard-purges entities that are dead EVERYWHERE, freeing that storage
;; without any per-write cost.
;;
;; SAFETY (why "resolve on every branch" and not "delete old tombstones"):
;; deleting a tombstone version RESURRECTS the entity on any branch that
;; inherits an OLDER live version (a fork taken between the create and the
;; delete). So we never delete a tombstone in isolation — we purge an entity
;; (all versions + identity) ONLY when no LIVE version wins for it (`res/live-ids`)
;; on EVERY branch (main + every feature branch + the base view). If it
;; resolves to nil everywhere, no reader can see it and no fork can inherit a
;; live version, so the purge changes no resolved view. A live fn still naming
;; it as a parent also blocks the purge (dangling-ref guard).

(defn- ts-ms
  "Milliseconds-since-epoch of a timestamptz column, tolerant of the shapes
   the codec/driver return (Instant / java.sql.Timestamp / java.util.Date /
   ISO string). nil / unparseable → nil, treated as 'no timestamp'."
  [x]
  (cond
    (nil? x) nil
    (instance? java.time.Instant x) (java.time.Instant/.toEpochMilli x)
    (instance? java.util.Date x) (java.util.Date/.getTime x)
    :else (try (java.time.Instant/.toEpochMilli (java.time.Instant/parse (str x)))
               (catch Exception _ nil))))


(defn- branch-ids-for-gc
  "Every branch id whose resolved view the GC must prove empty. A fork is a
   reference to its parent (not a snapshot), so it always resolves the
   parent's LATEST version — there is no un-listed 'base' view to check
   beyond the `:branch` rows themselves (main included)."
  [base-storage]
  (mapv :id (sp/query-entities base-storage :branch {})))


(def ^:private liveness-chunk
  "Candidate ids per merge-aware liveness load — keeps the `IN (…)` list
   well under Postgres' 65535-parameter ceiling on a tenant with a large
   tombstone backlog."
  1000)


(defn- dead-on-every-branch
  "The subset of `ids` that resolve to nil (deleted/absent) on EVERY
   branch — the safety precondition for purging them. One merge-aware
   liveness load per branch (per chunk of ids), not one resolve per id
   per branch."
  [base-storage entity-name ids branch-ids]
  (reduce (fn [dead bid]
            (if (empty? dead)
              (reduced dead)
              (into #{}
                    (remove (into #{}
                                  (mapcat #(res/live-ids base-storage entity-name % bid))
                                  (partition-all liveness-chunk dead)))
                    dead)))
          (set ids)
          branch-ids))


(defn- gc-candidate-ids
  "Entity ids whose NEWEST version (latest `:created-at` across all
   branches) is a tombstone older than `cutoff` — the cheap pre-filter
   before the authoritative per-branch liveness check. One row per
   entity (`query-latest-per-group`), not the whole version history."
  [base-storage version-entity version-id-field cutoff]
  (let [cutoff-ms (java.time.Instant/.toEpochMilli cutoff)]
    (into []
          (keep (fn [newest]
                  (let [newest-ms (ts-ms (:created-at newest))]
                    (when (and (:deleted-at newest) newest-ms (< newest-ms cutoff-ms))
                      (get newest version-id-field)))))
          (sp/query-latest-per-group base-storage version-entity {} [version-id-field]))))


(defn- purgeable-dead-ids
  "The ids of `entity-name` the sweep may purge: tombstoned before
   `cutoff`, dead on every branch, and — for a `:fn` — referenced by
   NOTHING outside its own subgraph. The parent-ids junction alone is not
   enough there: a `binding.ref-fn-id` / `slot.type-fn-id` /
   `fn.return-type-fn-id` (incl. the version plane, e.g. a ref set on
   ANOTHER branch) would be left dangling — and for an editor random-id
   fn that ref can never be healed, since the id can't be re-minted.
   `idrepair/inbound-refs-many` is the exact surface the hard-delete guard
   (`identity-child-refs`) and the bundle-prune guard both trust, in ONE
   pass for every dead fn; it subsumes the parent-ids check and excludes
   each fn's own owned rows."
  [base-storage entity-name {:keys [version-entity version-id-field]} cutoff branch-ids]
  (let [candidates (gc-candidate-ids base-storage version-entity version-id-field cutoff)
        dead (dead-on-every-branch base-storage entity-name candidates branch-ids)
        referenced (if (and (= :fn entity-name) (seq dead))
                     (set (keys (idrepair/inbound-refs-many base-storage dead)))
                     #{})]
    (filterv #(and (contains? dead %) (not (contains? referenced %))) candidates)))


(defn- purge-own-versions!
  [base-storage entity-name id]
  (let [{:keys [version-entity version-id-field]} (get res/entity-config entity-name)
        vs (sp/query-entities base-storage version-entity {version-id-field id})]
    (when (seq vs)
      (sp/delete-entities base-storage version-entity (mapv :id vs)))))


(defn- purge-dead-entity!
  "Hard-purge one dead entity and everything it owns."
  [base-storage entity-name id]
  (case entity-name
    ;; A fn OWNS a subgraph (its bindings + their list-items, fn-slots, all
    ;; version rows). A bare identity+version delete reclaims only the fn
    ;; row and orphans the rest — a monotonic storage leak on
    ;; create/delete churn, plus a dangling `binding.fn-id` /
    ;; `binding-list-item.binding-id` at the purged fn. Purge the whole
    ;; subgraph (the fn is unreferenced from outside — the inbound-refs
    ;; guard in `purgeable-dead-ids`).
    :fn (idrepair/purge-fn-subgraph! base-storage id)
    ;; A binding OWNS its list-items. A user delete tombstones only the
    ;; binding, so its items stay live-orphaned; purging the binding without
    ;; them dangles `binding-list-item.binding-id` (invisible to the
    ;; dangling-refs detector, which checks only `.ref-fn-id`). Cascade the
    ;; items (+ versions), then the binding's own version rows + identity.
    ;; (Filtered in SQL on `:binding-id` — this used to read both list-item
    ;; tables whole, once per purged binding.)
    :binding
    (let [liv (sp/query-entities base-storage :binding-list-item-version {:binding-id id})
          li (sp/query-entities base-storage :binding-list-item {:binding-id id})]
      (when (seq liv)
        (sp/delete-entities base-storage :binding-list-item-version (mapv :id liv)))
      (when (seq li)
        (sp/delete-entities base-storage :binding-list-item (mapv :id li)))
      (purge-own-versions! base-storage entity-name id)
      (sp/delete-entity base-storage entity-name id))
    ;; :fn-slot / :binding-list-item — nothing outside their own
    ;; (co-purged) version rows references them by id (verified vs
    ;; `ref-fields` / `identity-child-refs`). Bare purge is safe.
    (do (purge-own-versions! base-storage entity-name id)
        (sp/delete-entity base-storage entity-name id))))


(defn tombstone-gc-sweep!
  "Reclaim storage from versioned entities that are DELETED on every branch
   and whose newest tombstone is older than `retention-ms`. Purges each such
   entity's version rows (all branches) + its identity row. Idempotent and
   safe to run periodically — see the safety note above. Returns a map
   `{entity-name purged-count}`.

   `base-storage` is the UNWRAPPED storage (a `VersionedStorage`'s
   `base-storage`), the same handle the delete path holds. The 3-arity
   takes `{:before-purge (fn [entity-name id])}` — called for every
   entity about to be purged, while its rows are still readable."
  ([base-storage retention-ms] (tombstone-gc-sweep! base-storage retention-ms nil))
  ([base-storage retention-ms {:keys [before-purge]}]
   (let [cutoff (java.time.Instant/.minusMillis (java.time.Instant/now) (long retention-ms))
         branch-ids (branch-ids-for-gc base-storage)]
     (into {}
           (map (fn [[entity-name config]]
                  (let [purgeable (purgeable-dead-ids base-storage entity-name config
                                                      cutoff branch-ids)]
                    (doseq [id purgeable]
                      ;; `:before-purge` sees the entity while its rows
                      ;; still exist — the seam a store OUTSIDE graphden
                      ;; (the vault behind a secret binding) is reconciled
                      ;; through. Its failure must not stop reclamation.
                      (when before-purge
                        (try (before-purge entity-name id)
                             (catch Exception e
                               (log/warn e "tombstone-gc: before-purge hook failed"
                                         {:entity entity-name :id id}))))
                      (purge-dead-entity! base-storage entity-name id))
                    [entity-name (count purgeable)])))
           res/entity-config))))
