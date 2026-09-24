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
    [clojure.string :as str]
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
        (let [chain (res/collect-branch-chain base-storage branch-id)
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


(defn- first-hit
  "The first shape in `keyed` whose key another row of `view`
   (`{id → row}`) already holds → `[shape colliding-ids]`, else nil."
  [view key-fn keyed]
  (let [by-key (group-by (comp key-fn val) view)]
    (some (fn [shape]
            (let [hits (into []
                             (comp (map key) (remove #(= % (:id shape))))
                             (get by-key (key-fn shape)))]
              (when (seq hits) [shape hits])))
          keyed)))


(defn- descendant-chains
  "`{descendant-branch-id → the part of its chain BELOW branch-id}` for
   every branch in `bids` that forks (transitively) off `branch-id`."
  [base-storage branch-id bids]
  (into {}
        (keep (fn [bid]
                (let [chain (res/collect-branch-chain base-storage bid)]
                  (when (and (not= bid branch-id) (some #{branch-id} chain))
                    [bid (into [] (take-while #(not= % branch-id)) chain)]))))
        bids))


(defn- descendant-collision
  "The write on `branch-id` becomes visible on every branch forked off it,
   so a key a DESCENDANT already holds collides there even though this
   branch's own view is clean (main creating `foo` while a feature branch
   has its own `foo`). Only a descendant that itself holds a candidate
   version row can hold such a key — a row inherited from this branch or
   above is in this branch's own view — so the candidate versions the
   caller already loaded name the branches to look at, and the rows to
   resolve there. A shape the descendant overrides (its own version of the
   same id, somewhere below this branch) does not surface on it and is
   left out. Returns `[shape colliding-ids descendant-id]` or nil."
  [base-storage branch-id entity-name {:keys [version-entity id-field key-fn]}
   keyed cand-versions]
  (let [below-by-desc (descendant-chains base-storage branch-id
                                         (into #{} (map :branch-id) cand-versions))]
    (when (seq below-by-desc)
      (let [overridden (into #{}
                             (map (juxt :branch-id id-field))
                             (sp/query-entities base-storage version-entity
                                                {id-field (vec (keep :id keyed))
                                                 :branch-id (vec (distinct (mapcat val below-by-desc)))}))]
        (some (fn [[desc below]]
                (let [below? (set below)
                      ids (into #{} (comp (filter #(below? (:branch-id %))) (map id-field))
                                cand-versions)
                      surfacing (filterv (fn [shape]
                                           (not-any? #(overridden [% (:id shape)]) below))
                                         keyed)
                      live (res/resolve-live-entities base-storage entity-name ids desc)
                      view (into live (comp (filter :id) (map (juxt :id identity))) surfacing)]
                  (some-> (first-hit view key-fn surfacing) (conj desc))))
              below-by-desc)))))


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
   other. Then the same question for the branches forked off this one
   (`descendant-collision`) — the write will show there too. Returns
   `[shape colliding-ids]` (plus the descendant's id when the collision is
   on one) for the first colliding shape, else nil. Replaces one version
   query + one resolve PER ROW (~3 round trips a fn — ~12k sequential ones
   on a full boot sync)."
  [base-storage branch-id entity-name
   {:keys [version-entity id-field query-field key-fn] :as spec} shapes]
  (let [keyed (filterv query-field shapes)]
    (when (seq keyed)
      (let [cand-versions (sp/query-entities base-storage version-entity
                                             {query-field (vec (distinct (map query-field keyed)))})
            live (res/resolve-live-entities base-storage entity-name
                                            (into #{} (map id-field) cand-versions) branch-id)
            view (into live (comp (filter :id) (map (juxt :id identity))) keyed)]
        (or (first-hit view key-fn keyed)
            (descendant-collision base-storage branch-id entity-name spec keyed cand-versions))))))


(defn- on-branch-clause
  "\" on branch <name>\" for a collision found on a descendant, else nil."
  [base-storage desc-id]
  (when desc-id
    (str " on branch " (pr-str (or (:name (sp/read-entity base-storage :branch desc-id))
                                   (str desc-id))))))


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
    (when-let [[shape colliding desc-id]
               (live-key-collision base-storage branch-id :fn
                                   {:version-entity :fn-version :id-field :fn-id
                                    :query-field :name
                                    :key-fn (juxt :namespace-id :name)}
                                   shapes)]
      (let [{nm :name target-ns :namespace-id} shape
            human (str "fn " (pr-str nm) " already exists"
                       (when target-ns " in this namespace")
                       (on-branch-clause base-storage desc-id)
                       " — pick a different name")]
        (throw (ex-info human
                        (cond-> {:type :constraint-violation/fn-name-collision
                                 :entity-name :fn
                                 :name nm
                                 :namespace-id target-ns
                                 :branch-id branch-id
                                 :colliding-fn-ids colliding
                                 :reason human}
                          desc-id (assoc :descendant-branch-id desc-id))))))))


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
    (when-let [[shape colliding desc-id]
               (live-key-collision base-storage branch-id :resource-override
                                   {:version-entity :resource-override-version
                                    :id-field :override-id
                                    :query-field :path
                                    :key-fn :path}
                                   shapes)]
      (let [path (:path shape)
            human (str "an override for " (pr-str path) " already exists"
                       (or (on-branch-clause base-storage desc-id) " on this branch")
                       " — edit it instead")]
        (throw (ex-info human
                        (cond-> {:type :constraint-violation/resource-override-path-collision
                                 :entity-name :resource-override
                                 :path path
                                 :branch-id branch-id
                                 :colliding-ids colliding
                                 :reason human}
                          desc-id (assoc :descendant-branch-id desc-id))))))))


(defn check-resource-override-path-collision!
  "Singular form — delegates to `check-resource-override-path-collisions!`."
  [base-storage branch-id entity-name merged]
  (check-resource-override-path-collisions! base-storage branch-id entity-name [merged]))


(defn collision-lock-key
  "The advisory-lock key serializing writes of `row` that could collide in
   the resolved view — nil when the write can't collide:

   - a `:binding-list-item` → its owning binding (`(binding-id, position)`
     appends / moves on the same binding);
   - a named `:fn` → `(namespace, name)` (concurrent create/rename/move
     otherwise both pass `check-fn-name-collisions!` and both commit);
   - a pathed `:resource-override` → its `path`.

   The fn and override keys leave the BRANCH out on purpose: their checks
   look at the branches forked off the writer too, so a write on main and
   one on a feature branch can collide and must be serialized against each
   other. (Same-name writes on unrelated branches merely queue — rare.)
   `branch-id` is kept in the signature for the callers' symmetry."
  [_branch-id entity-name row]
  (case entity-name
    :binding-list-item (some->> (:binding-id row) (str "item|"))
    :fn (when (:name row)
          (str "fn-name|" (:namespace-id row) "|" (:name row)))
    :resource-override (when (:path row)
                         (str "resource-override-path|" (:path row)))
    nil))


(def ^:private row-lock-buckets
  "How many advisory keys the row locks of one (branch, entity) spread
   over. Bounded so a batch update of thousands of rows takes at most this
   many locks — each advisory lock takes a slot in Postgres' shared lock
   table (`max_locks_per_transaction` × connections); two rows sharing a
   bucket only queue behind each other."
  64)


(defn row-lock-keys
  "Advisory keys serializing read-merge-write updates of the rows `ids` of
   `entity-name` on `branch-id` — the lost-update guard of the update
   paths. Stable across JVMs (a string hash), so executors on different
   pods agree on them."
  [branch-id entity-name ids]
  (into #{}
        (map #(str "row|" branch-id "|" (name entity-name) "|"
                   (mod (hash (str %)) row-lock-buckets)))
        ids))


(def ^:private identity-lock-buckets
  "How many advisory keys the identity locks of one entity spread over —
   bounded like `row-lock-buckets`, for the same lock-table reason."
  64)


(defn identity-lock-keys
  "Advisory keys serializing, ACROSS branches, a write that may add a
   version to the identity rows `ids` of `entity-name` (a create re-minting
   a deterministic id, an update flowing a version onto an existing
   identity) against a branch delete purging those identity rows. Without
   it the delete could see an id as versioned nowhere, a create on another
   branch could then find the identity still there and write only a
   version row, and the delete removed the identity under it — a version
   with no identity. Stable across JVMs (a string hash)."
  [entity-name ids]
  (into #{}
        (map #(str "ident|" (name entity-name) "|"
                   (mod (hash (str %)) identity-lock-buckets)))
        ids))


(defn write-identity-lock-keys
  "The `identity-lock-keys` a create of `rows` of `entity-name` takes: the
   rows' own ids and, for a `:fn-slot` / `:binding`, the slot each one
   references — a branch delete purges the slots only its own fn-slots
   exposed, and must not do so while another branch is writing a new
   reference to one."
  [entity-name rows]
  (into (identity-lock-keys entity-name (keep :id rows))
        (when (#{:fn-slot :binding} entity-name)
          (identity-lock-keys :slot (keep :slot-id rows)))))


(def ^:private advisory-buckets
  "How many locks one KEY GROUP can take in a transaction. Each advisory
   lock held takes a slot in Postgres' shared lock table
   (`max_locks_per_transaction` × `max_connections`): the boot sync of
   ~6000 platform fns took one collision lock per name and ran a managed
   Postgres out of it (`ERROR: out of shared memory`, prod deploy
   2026-09-24). Equal keys always share a bucket, so the serialization a
   key promises holds; unrelated keys of one group that share a bucket
   only queue."
  256)


(defn advisory-key
  "The advisory lock `k` is taken under. Keys come in GROUPS — the text
   before the first `|`: `branch`, `row`, `ident`, `fn-name`, `item`,
   `resource-override-path`. Every write takes its groups in one order —
   branch → row → ident → collision (`lock-rank`) — and that order is what
   keeps two transactions from waiting on each other in a cycle; so a key
   only ever shares a lock with keys of ITS OWN group. `branch`, `row` and
   `ident` keys are already bounded (one per branch touched;
   `row-lock-keys` / `identity-lock-keys` bucket per entity) and pass
   through; a collision key is folded into `advisory-buckets` buckets of
   its group. Stable across JVMs (a string hash), so pods agree on it."
  [k]
  (let [k (str k)
        group (first (str/split k #"\|" 2))]
    (if (#{"branch" "row" "ident"} group)
      k
      (str group "|" (mod (hash k) advisory-buckets)))))


(defn- lock-rank
  "Position of `k`'s group in the one order every write takes its locks
   in: branch → row → ident → collision."
  [k]
  (case (first (str/split k #"\|" 2))
    "branch" 0
    "row" 1
    "ident" 2
    3))


(defn xact-lock!
  "Take a transaction-scoped `pg_advisory_xact_lock` (released at commit /
   rollback) on the lock (`advisory-key`) of every key in `lock-keys`, in
   ONE statement — at most `advisory-buckets` per collision group.
   Deadlock-free within the statement:
   the keys are de-duplicated and SORTED (by group rank, then key), and every caller locks through
   here, so no two transactions can acquire an overlapping key set in
   opposite orders (`WITH ORDINALITY … ORDER BY` keeps the array order; a
   volatile target-list function is evaluated after the sort). `conn` is
   the caller's transaction connection; nil (no pooled backend) or no keys
   is a no-op. Runs through `util/exec!` so the parallel-test
   `*jdbc-override*` seam sees it."
  [conn lock-keys]
  (let [ks (into-array String (->> lock-keys (remove nil?) (map advisory-key) distinct
                                   (sort-by (juxt lock-rank identity))))]
    (when (and conn (pos? (alength ks)))
      (util/exec! conn
                  [(str "SELECT pg_advisory_xact_lock(hashtext(k)::bigint)"
                        " FROM unnest(?::text[]) WITH ORDINALITY AS u(k, ord)"
                        " ORDER BY ord")
                   ks]
                  {}))))
