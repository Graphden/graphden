(ns graphden.versioning.storage.uniqueness
  "Per-branch RESOLVED-VIEW uniqueness checks shared by the write path
   (`versioning.storage.core` create/update) and the merge path
   (`versioning.storage.merge`).

   Two former base-table UNIQUE keys were retired because the base
   identity row is cross-branch and soft-deleted identities persist, so
   uniqueness is a per-branch RESOLVED-VIEW property (a cross-branch
   base index would wrongly block legal divergence):

   - `fn(namespace-id, name)` — `check-fn-name-collisions!`
   - `binding-list-item(binding-id, position)` —
     `check-list-item-position-collisions!`

   Both resolve candidate rows against the LIVE branch view before
   deciding, so off-branch / tombstoned rows never collide. They live
   here — not in `core` — so the merge path can re-run the same checks
   over the entities a merge SURFACES onto the target without a
   `core → merge → core` require cycle."
  (:require
    [graphden.storage.postgres.util :as util]
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


(defn- live-key-collision
  "The shared core of the `(namespace-id, name)` and `:path` checks, over
   a whole batch of `shapes` (the rows about to be written, each carrying
   `:id`). `spec`:

   - `:version-entity` / `:id-field` — the version table and its owner
     column, queried ONCE on `:query-field` for every value the batch
     writes (every version row carries the entity's then-current value, so
     this covers creation values AND renames; an identity with no version
     row can't resolve on any branch and can't collide);
   - `:key-fn` — the uniqueness key compared in memory.

   The candidates resolve through ONE `res/resolve-live-entities` — nil
   for off-branch, tombstoned and chain-versionless rows, so cross-branch
   divergence stays legal. The batch's OWN rows overlay that view (the
   position check's rule): a row this batch renames away no longer holds
   its old key, and two batch rows claiming the same key collide with each
   other. Returns `[shape colliding-ids]` for the first colliding shape,
   else nil. Replaces one version query + one resolve PER ROW (~3 round
   trips a fn — ~12k sequential ones on a full boot sync)."
  [base-storage branch-id entity-name
   {:keys [version-entity id-field query-field key-fn]} shapes]
  (let [keyed (filterv query-field shapes)]
    (when (seq keyed)
      (let [cand-ids (into #{}
                           (map id-field)
                           (sp/query-entities base-storage version-entity
                                              {query-field (vec (distinct (map query-field keyed)))}))
            live (res/resolve-live-entities base-storage entity-name cand-ids branch-id)
            view (into live (comp (filter :id) (map (juxt :id identity))) keyed)
            by-key (group-by (comp key-fn val) view)]
        (some (fn [shape]
                (let [hits (into []
                                 (comp (map key) (remove #(= % (:id shape))))
                                 (get by-key (key-fn shape)))]
                  (when (seq hits) [shape hits])))
              keyed)))))


(defn check-fn-name-collisions!
  "Per-branch resolved-view uniqueness for live fns' `(namespace-id, name)`,
   over a whole batch of `shapes` (create: the new rows; update: current ⊕
   incoming; merge: the surfaced rows). Skips when `entity-name` isn't
   `:fn`; anonymous fns never collide.

   The raw `UNIQUE (namespace_id, name)` index was retired (NOTE in
   schema/graph/schema.clj): soft-deleted identity rows persist by design
   and kept the key occupied forever — delete a fn inside a namespace and
   every later create/move of a same-named fn there bounced with a
   unique-violation — while NULL `namespace_id` (root fns) was never
   covered by the btree at all. Like list-item positions above, uniqueness
   is a property of the LIVE per-branch view, so enforce it against
   resolved rows (`live-key-collision` — one version query + one batch
   resolve for the whole batch)."
  [base-storage branch-id entity-name shapes]
  (when (= :fn entity-name)
    (when-let [[shape colliding]
               (live-key-collision base-storage branch-id :fn
                                   {:version-entity :fn-version :id-field :fn-id
                                    :query-field :name
                                    :key-fn (juxt :namespace-id :name)}
                                   shapes)]
      (let [{nm :name target-ns :namespace-id} shape
            human (str "fn " (pr-str nm) " already exists"
                       (when target-ns " in this namespace")
                       " — pick a different name")]
        (throw (ex-info human
                        {:type :constraint-violation/fn-name-collision
                         :entity-name :fn
                         :name nm
                         :namespace-id target-ns
                         :branch-id branch-id
                         :colliding-fn-ids colliding
                         :reason human}))))))


(defn check-fn-name-collision!
  "Singular form — delegates to `check-fn-name-collisions!`."
  [base-storage branch-id entity-name merged]
  (check-fn-name-collisions! base-storage branch-id entity-name [merged]))


(defn check-resource-override-path-collisions!
  "Per-branch resolved-view uniqueness for live :resource-overrides'
   `:path` — the fn-name check's shape applied to asset overrides: an
   override whose path is already live on this branch would make
   `:read-resource-overridable` nondeterministic (whichever row a query
   returns first wins). Off-branch / tombstoned rows can't collide."
  [base-storage branch-id entity-name shapes]
  (when (= :resource-override entity-name)
    (when-let [[shape colliding]
               (live-key-collision base-storage branch-id :resource-override
                                   {:version-entity :resource-override-version
                                    :id-field :override-id
                                    :query-field :path
                                    :key-fn :path}
                                   shapes)]
      (let [path (:path shape)
            human (str "an override for " (pr-str path)
                       " already exists on this branch — edit it instead")]
        (throw (ex-info human
                        {:type :constraint-violation/resource-override-path-collision
                         :entity-name :resource-override
                         :path path
                         :branch-id branch-id
                         :colliding-ids colliding
                         :reason human}))))))


(defn check-resource-override-path-collision!
  "Singular form — delegates to `check-resource-override-path-collisions!`."
  [base-storage branch-id entity-name merged]
  (check-resource-override-path-collisions! base-storage branch-id entity-name [merged]))


(defn collision-lock-key
  "The advisory-lock key serializing writes of `row` that could collide in
   the resolved view — nil when the write can't collide:

   - a `:binding-list-item` → its owning binding (`(binding-id, position)`
     appends / moves on the same binding);
   - a named `:fn` → `(branch, namespace, name)` (concurrent
     create/rename/move otherwise both pass `check-fn-name-collisions!`
     and both commit);
   - a pathed `:resource-override` → `(branch, path)`."
  [branch-id entity-name row]
  (case entity-name
    :binding-list-item (some-> (:binding-id row) str)
    :fn (when (:name row)
          (str "fn-name|" branch-id "|" (:namespace-id row) "|" (:name row)))
    :resource-override (when (:path row)
                         (str "resource-override-path|" branch-id "|" (:path row)))
    nil))


(defn xact-lock!
  "Take a transaction-scoped `pg_advisory_xact_lock` (released at commit /
   rollback) on every key in `lock-keys`, in ONE statement. Deadlock-free:
   the keys are de-duplicated and SORTED, and every caller locks through
   here, so no two transactions can acquire an overlapping key set in
   opposite orders (`WITH ORDINALITY … ORDER BY` keeps the array order; a
   volatile target-list function is evaluated after the sort). `conn` is
   the caller's transaction connection; nil (no pooled backend) or no keys
   is a no-op. Runs through `util/exec!` so the parallel-test
   `*jdbc-override*` seam sees it."
  [conn lock-keys]
  (let [ks (into-array String (->> lock-keys (remove nil?) distinct sort))]
    (when (and conn (pos? (alength ks)))
      (util/exec! conn
                  [(str "SELECT pg_advisory_xact_lock(hashtext(k)::bigint)"
                        " FROM unnest(?::text[]) WITH ORDINALITY AS u(k, ord)"
                        " ORDER BY ord")
                   ks]
                  {}))))
