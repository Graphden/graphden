(ns graphden.versioning.storage.uniqueness
  "Per-branch RESOLVED-VIEW uniqueness checks shared by the write path
   (`versioning.storage.core` create/update) and the merge path
   (`versioning.storage.merge`).

   Two former base-table UNIQUE keys were retired because the base
   identity row is cross-branch and soft-deleted identities persist, so
   uniqueness is a per-branch RESOLVED-VIEW property (a cross-branch
   base index would wrongly block legal divergence):

   - `fn(namespace-id, name)` — `check-fn-name-collision!`
   - `binding-list-item(binding-id, position)` —
     `check-list-item-position-collisions!`

   Both resolve candidate rows against the LIVE branch view before
   deciding, so off-branch / tombstoned rows never collide. They live
   here — not in `core` — so the merge path can re-run the same checks
   over the entities a merge SURFACES onto the target without a
   `core → merge → core` require cycle."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.resolution :as res]))


(defn check-list-item-position-collisions!
  "Per-branch resolved-view check for a WHOLE batch: throw if any item in
   `check-seq` resolves to a `(binding-id, position)` already taken by
   ANOTHER item on this branch. Enforces the per-branch resolved-view
   `UNIQUE (binding-id, position)` invariant — uniqueness is a per-branch
   resolved-view property, not a cross-branch one (a cross-branch
   base-table index would wrongly block divergence).

   Skips when `entity-name` isn't `:binding-list-item`. One version query
   for ALL touched bindings + one resolve pass, regardless of batch size —
   the singular `check-list-item-position-collision!` delegates here with a
   one-element seq."
  [base-storage branch-id entity-name check-seq]
  (when (= :binding-list-item entity-name)
    (let [candidates (filter #(and (:binding-id %) (some? (:position %))) check-seq)]
      (when (seq candidates)
        (let [chain (#'res/collect-branch-chain base-storage branch-id)
              ;; Merge-aware: a collision can be introduced by a MERGE too —
              ;; a source-branch item that resolves onto this branch at a
              ;; position an existing item already holds. Those items live
              ;; only on the merge SOURCE branch (never on the ancestor
              ;; chain), so a chain-only scan never enumerates them and the
              ;; collision slips past. Widen the scan to every branch whose
              ;; rows can surface on the chain — the ancestor chain PLUS the
              ;; source of every merge landing on it — mirroring the
              ;; resolver's own reachability. `resolved-map` below already
              ;; resolves merge-aware, so once an id is enumerated its
              ;; winning (chain-or-merged) position is compared correctly.
              merge-source-bids (into []
                                      (comp (keep :source-branch-id) (distinct))
                                      (sp/query-entities base-storage :branch-merge
                                                         {:target-branch-id (vec chain)}))
              scan-bids (into (vec chain) merge-source-bids)
              ;; Every item-version on the touched bindings' reachable
              ;; branches. The SQL WHERE narrows to those bindings so we
              ;; don't scan the whole version table.
              versions (sp/query-entities base-storage :binding-list-item-version
                                          {:binding-id (vec (distinct (map :binding-id candidates)))
                                           :branch-id scan-bids})
              versions-by-binding (group-by :binding-id versions)
              ;; Resolve every touched item ONCE — the collision rule
              ;; applies to the LIVE branch view, not raw version rows.
              ;; Items WITHOUT a version on the chain resolve to nil / a bare
              ;; off-branch row and can't collide; only touched item-ids
              ;; (those carrying a chain version) are considered.
              all-touched-ids (into #{} (map :item-id) versions)
              resolved-map (if (seq all-touched-ids)
                             (into {} (res/resolve-entities-batch
                                        base-storage :binding-list-item
                                        (vals (sp/read-entities base-storage :binding-list-item
                                                                (vec all-touched-ids)))
                                        branch-id))
                             {})
              ;; The batch's OWN writes overlay the resolved view: an
              ;; item this same batch moves elsewhere no longer holds
              ;; its old position, so a batched permutation (declarative
              ;; re-sync of reordered items, merge surfacing a reorder)
              ;; checks against the POST-batch view. Without this, any
              ;; position swap between two syncs deadlocked the sync
              ;; forever — each item's new position "collided" with a
              ;; sibling that was itself moving away in the same batch.
              pending (into {} (map (juxt :id :position)) candidates)]
          (doseq [{:keys [binding-id position id]} candidates]
            (let [touched-ids (distinct
                                (map :item-id (get versions-by-binding binding-id)))
                  collisions (for [eid touched-ids
                                   :let [row (get resolved-map eid)
                                         eff-pos (if (contains? pending eid)
                                                   (get pending eid)
                                                   (:position row))]
                                   :when (and (some? row)
                                              (not= eid id)
                                              (= position eff-pos))]
                               eid)]
              (when (seq collisions)
                ;; Message is USER-facing: no internal branch uuid;
                ;; carry :reason so response renderers surface the
                ;; same text (audit-7 error honesty).
                (let [human (str "Position " position " is already taken "
                                 "in this binding on the current branch")]
                  (throw (ex-info human
                                  {:type :constraint-violation/position-collision
                                   :reason human
                                   :entity-name :binding-list-item
                                   :binding-id binding-id
                                   :position position
                                   :branch-id branch-id
                                   :colliding-item-ids (vec collisions)})))))))))))


(defn check-list-item-position-collision!
  "Singular form — delegates to `check-list-item-position-collisions!`."
  [base-storage branch-id entity-name new-data]
  (check-list-item-position-collisions! base-storage branch-id entity-name [new-data]))


(defn check-fn-name-collision!
  "Per-branch resolved-view uniqueness for a live fn's `(namespace-id, name)`.

   The raw `UNIQUE (namespace_id, name)` index was retired (NOTE in
   schema/graph/schema.clj): soft-deleted identity rows persist by design
   and kept the key occupied forever — delete a fn inside a namespace and
   every later create/move of a same-named fn there bounced with a
   unique-violation — while NULL `namespace_id` (root fns) was never
   covered by the btree at all. Like list-item positions above, uniqueness
   is a property of the LIVE per-branch view, so enforce it against
   resolved rows.

   Candidates come from ONE indexed query on `fn-version.name` — every
   version row carries the fn's then-current name, so this covers creation
   names AND renames; identities with no version row at all can't resolve
   on any branch and can't collide. Each candidate then goes through
   `res/resolve-entity`, which already yields nil for off-branch and
   tombstoned fns, so cross-branch name divergence stays legal."
  [base-storage branch-id entity-name merged]
  (when (and (= :fn entity-name) (:name merged))
    (let [nm (:name merged)
          target-ns (:namespace-id merged)
          self-id (:id merged)
          version-matches (sp/query-entities base-storage :fn-version {:name nm})
          cand-ids (-> #{}
                       (into (map :fn-id) version-matches)
                       (disj self-id))
          colliding (into []
                          (comp (map #(res/resolve-entity base-storage :fn % branch-id))
                                (filter #(and (some? %)
                                              (= nm (:name %))
                                              (= target-ns (:namespace-id %))))
                                (map :id))
                          cand-ids)]
      (when (seq colliding)
        (let [human (str "fn " (pr-str nm) " already exists"
                         (when target-ns " in this namespace")
                         " — pick a different name")]
          (throw (ex-info human
                          {:type :constraint-violation/fn-name-collision
                           :entity-name :fn
                           :name nm
                           :namespace-id target-ns
                           :branch-id branch-id
                           :colliding-fn-ids colliding
                           :reason human})))))))


(defn fn-name-lock-key
  "Advisory-lock key serializing all name-writes that could collide on the
   same `(branch, namespace, name)` triple — concurrent create/rename/move
   otherwise both pass `check-fn-name-collision!` and both commit. nil when
   the write can't collide (not a :fn, or anonymous)."
  [branch-id entity-name merged]
  (when (and (= :fn entity-name) (:name merged))
    (str "fn-name|" branch-id "|" (:namespace-id merged) "|" (:name merged))))
