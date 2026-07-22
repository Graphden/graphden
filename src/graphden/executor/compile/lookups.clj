(ns graphden.executor.compile.lookups
  "Index helpers over the slot / fn-slot / binding model used during compile.

   Pure functions on maps — no side effects, no dependency on the compile
   driver. Shared by `compile` (binding resolution, ref rewriting) and
   `compile-runtime` (mapping ext-names back to slots).

   `cached-build-lookups` wraps `build-lookups` with a process-wide
   reference-identity cache (bounded LRU, ~8 entries). Hits when the
   SAME graph map is passed — stable across calls in one ctx between
   mutations. Different per-branch ctxs each get their own entry.")


(defn build-lookups
  "Index entities for fast lookup during compile. Inputs:
     fns                 — vector of fn rows
     slots               — vector of slot rows
     fn-slots            — vector of fn-slot junction rows
     bindings            — vector of binding rows
     list-items          — vector of binding-list-item rows

   Output keys:
     :fn-map               {fn-id → fn-row}
     :slot-map             {slot-id → slot-row}
     :fn-slots-by-fn       {fn-id → [fn-slot-row …]}, ordered by :position
     :slot-by-fn-name      {[fn-id slot-name-keyword] → slot-row}
     :slot-by-fn-source-slot
                            {[fn-id source-slot-id] → slot-row} —
                            renamed-view lookup. A composed fn that
                            exposes ancestor slot S under a new name
                            owns a slot row whose `:source-slot-id`
                            points back at S; this index lets renamers
                            be located in O(1) without walking
                            fn-slots-by-fn.
     :bindings-by-fn       {fn-id → [binding-row …]}
     :binding-by-fn-slot   {[fn-id slot-id] → binding-row}
     :items-by-binding     {binding-id → [item-row …]}, ordered by :position
     :chain-cache          atom {fn-id → chain-vector} — lazy cache for
                            `inheritance-chain*`. Multiple compile-time
                            helpers (`effective-required?`, `effective-binding`,
                            `fn-typed-slot?`, `list-items-for`,
                            `closest-binding-for-slot`, `rename-for-slot`)
                            walk the same chain for the same fn-id many
                            times during one classification pass; this atom
                            collapses that to a single BFS per fn-id."
  [{:keys [fns slots fn-slots bindings list-items]}]
  (let [fn-map (into {} (map (juxt :id identity)) fns)
        slot-map (into {} (map (juxt :id identity)) slots)
        fn-slots-by-fn (->> fn-slots
                            (sort-by (juxt :fn-id :position))
                            (reduce (fn [acc fs]
                                      (update acc (:fn-id fs) (fnil conj []) fs))
                                    {}))
        slot-by-fn-name (into {}
                              (mapcat (fn [[fid junctions]]
                                        (keep (fn [fs]
                                                (when-let [s (get slot-map (:slot-id fs))]
                                                  [[fid (keyword (:name s))] s]))
                                              junctions)))
                              fn-slots-by-fn)
        slot-by-fn-source-slot (into {}
                                     (mapcat (fn [[fid junctions]]
                                               (keep (fn [fs]
                                                       (when-let [s (get slot-map (:slot-id fs))]
                                                         (when-let [src (:source-slot-id s)]
                                                           [[fid src] s])))
                                                     junctions)))
                                     fn-slots-by-fn)
        bindings-by-fn (reduce (fn [acc b]
                                 (update acc (:fn-id b) (fnil conj []) b))
                               {}
                               bindings)
        binding-by-fn-slot (into {}
                                 (map (juxt (juxt :fn-id :slot-id) identity))
                                 bindings)
        items-by-binding (->> list-items
                              (sort-by (juxt :binding-id :position))
                              (reduce (fn [acc i]
                                        (update acc (:binding-id i) (fnil conj []) i))
                                      {}))]
    {:fn-map             fn-map
     :slot-map           slot-map
     :fn-slots-by-fn     fn-slots-by-fn
     :slot-by-fn-name    slot-by-fn-name
     :slot-by-fn-source-slot slot-by-fn-source-slot
     :bindings-by-fn     bindings-by-fn
     :binding-by-fn-slot binding-by-fn-slot
     :items-by-binding   items-by-binding
     :chain-cache        (atom {})
     ;; Hot per-(fn-id) caches used by the compile pipeline. Same
     ;; lifetime as `:chain-cache` — populated lazily by the
     ;; compile fns and shared across the compile-all pass so a
     ;; deep walk runs once per fn-id instead of once per ref-
     ;; binding pointing at it.
     :deep-frees-cache   (atom {})
     :deep-free-ext-entries-cache (atom {})
     :cache-projection-frees-cache (atom {})
     :bindings-cache     (atom {})
     :global-env-cache   (atom nil)}))


(def ^:private cached-build-lookups-max-size
  "Bound on the identity-keyed cache. Sized to comfortably cover the
   active per-branch ctxs typical of dev workflows.

   2 (not 8): the cache is keyed on graph IDENTITY (`identical?`),
   but `read-graph` returns a fresh map every call — so the cache
   effectively never hits across calls from different read-graph
   invocations. The 8 entries just held 8 generations of lookups
   tables (each carrying fn-map + slot-map + 4 index maps + 4 lazy
   atom caches), ~1MB of accumulating GC pressure for zero hit-
   rate benefit. 2 covers the single-active-branch case + a
   one-slot overlap for the branch-router ctx switching window."
  2)


(defonce ^:private cached-build-lookups-state
  ;; Bounded LRU as a plain vector of `[graph-ref lookups]` pairs.
  ;; Reference-identity comparison via `identical?` — two same-CONTENT
  ;; graphs from different ctxs each get their own entry, which is
  ;; correct (chain-caches are per-entry, and an unrelated ctx
  ;; shouldn't share them).
  (atom []))


(defn cached-build-lookups
  "Reference-identity-memoised wrapper over `build-lookups`. Returns
   the same lookups map (including the chain-cache atom) for repeated
   calls with the same graph map identity — so a sibling caller that
   walks `inheritance-chain*` benefits from prior calls' BFS results.

   Cache miss recomputes. Bounded LRU at
   `cached-build-lookups-max-size`; the oldest entry is evicted on
   overflow."
  [graph]
  (or (some (fn [[g l]] (when (identical? g graph) l))
            @cached-build-lookups-state)
      (let [lookups (build-lookups graph)]
        (swap! cached-build-lookups-state
               (fn [v]
                 (let [pruned (filterv (fn [[g _]] (not (identical? g graph))) v)
                       capped (vec (take-last (dec cached-build-lookups-max-size)
                                              pruned))]
                   (conj capped [graph lookups]))))
        lookups)))


(defn inheritance-chain
  "Vector of fn-ids reachable from F via `parent-ids` in BFS order. F is
   first, then direct parents, then grandparents, etc. Multi-inheritance
   collects ALL parents — bindings on non-primary parents stay visible.

   Walking this vector in order (closest-first) makes the standard
   override rule work: a binding on F shadows the same slot bound by an
   ancestor."
  [fn-id fn-map]
  (loop [acc [fn-id]
         seen #{fn-id}
         queue (->> (get-in fn-map [fn-id :parent-ids])
                    (remove nil?)
                    vec)]
    (if (empty? queue)
      acc
      (let [fid (first queue)
            rest-queue (subvec queue 1)]
        (if (contains? seen fid)
          (recur acc seen rest-queue)
          (let [pids (->> (get-in fn-map [fid :parent-ids])
                          (remove nil?)
                          (remove seen))]
            (recur (conj acc fid)
                   (conj seen fid)
                   (into rest-queue pids))))))))


(defn inheritance-chain*
  "Memoised variant of `inheritance-chain`. Reads from / writes through
   `:chain-cache` on the lookups map. Falls back to plain
   `inheritance-chain` when `:chain-cache` isn't present — callers
   that hand-build a lookups map outside `build-lookups` (e.g. in
   tests) get correct behaviour without the cache."
  [fn-id {:keys [fn-map chain-cache]}]
  (if chain-cache
    (or (get @chain-cache fn-id)
        (let [chain (inheritance-chain fn-id fn-map)]
          (swap! chain-cache assoc fn-id chain)
          chain))
    (inheritance-chain fn-id fn-map)))


(defn root-fn
  "Walk the inheritance chain of `fn-id` and return the first ancestor
   with empty `:parent-ids` — the root that owns the slots. In the new
   model the root is a base-fn (return-type-fn-id set) OR a type-row (record /
   refinement / list / primitive); both have synthesised impls registered
   under their fn-name. Two-arity form takes raw `fn-map` (used when
   the caller has no lookups in hand); three-arity form takes the
   full lookups map and benefits from chain caching."
  ([fn-id fn-map]
   (let [chain (inheritance-chain fn-id fn-map)]
     (some (fn [fid]
             (let [f (get fn-map fid)]
               (when (empty? (:parent-ids f))
                 f)))
           chain)))
  ([fn-id fn-map lookups]
   (let [chain (inheritance-chain* fn-id (assoc lookups :fn-map fn-map))]
     (some (fn [fid]
             (let [f (get fn-map fid)]
               (when (empty? (:parent-ids f))
                 f)))
           chain))))


(defn root-slots
  "Vector of slot-rows owned by `fn-id`'s root, ordered by fn-slot
   position. These are the parameters the impl receives."
  [fn-id {:keys [fn-map slot-map fn-slots-by-fn] :as lookups}]
  (when-let [root (root-fn fn-id fn-map lookups)]
    (->> (get fn-slots-by-fn (:id root) [])
         (keep (fn [fs] (get slot-map (:slot-id fs))))
         vec)))


(defn closest-binding-for-slot
  "Walk F's inheritance chain (closest-first) and return the first
   binding-row that targets `slot-id`. nil if no chain ancestor binds
   the slot."
  [fn-id slot-id {:keys [binding-by-fn-slot] :as lookups}]
  (some (fn [fid]
          (get binding-by-fn-slot [fid slot-id]))
        (inheritance-chain* fn-id lookups)))


(defn- rename-chain-reaches?
  "True iff `candidate-slot`'s `:source-slot-id` chain transitively
   reaches `target-slot-id`. `candidate-slot` may rename a renamed
   slot — e.g. renamed-leaf's `:item` renames leaf-id's `:row` which
   itself renames `:get`'s `:coll`; asking 'does :item reach :coll?'
   needs to follow both hops. Bounded to 16 hops to be safe."
  [candidate-slot target-slot-id slot-map]
  (loop [src (:source-slot-id candidate-slot)
         depth 0]
    (cond
      (nil? src) false
      (= src target-slot-id) true
      (>= depth 16) false
      :else (recur (:source-slot-id (get slot-map src)) (inc depth)))))


(defn rename-for-slot
  "Effective external name for `slot-id` as seen by F's caller. Walks
   the inheritance chain (closest-first); the first own-slot found
   in the chain whose `:source-slot-id` chain transitively reaches
   `slot-id` wins — its `:name` is the rename. Falls back to the
   slot's own name.

   Transitive resolution matters when a fn-def renames a parent's
   already-renamed slot (e.g. `_list-branches-as-json-item :parent
   :as-json-branch :args {:branch-row {:as :item}}` — the slot
   chain is item → branch-row → coll; asking for the rename of
   `coll` at `_list-branches-as-json-item` must return `:item`,
   not the intermediate `:branch-row`).

   Phase 6c: the FK link `slot.source-slot-id` is now the canonical
   carrier for renames. The legacy `binding.rename-to` text is no
   longer read here — Phase 6b ensures every UI-driven rename also
   creates the renamed-view slot row, and Phase 6a's parser
   populates the FK for every EDN-declared rename. Positional
   list-item renames don't reach this resolver — they live in
   binding-list-item rows and resolve through that path."
  [fn-id slot-id {:keys [slot-map fn-slots-by-fn] :as lookups}]
  (let [renamed (some (fn [fid]
                        (let [own-slots (->> (get fn-slots-by-fn fid [])
                                             (keep #(get slot-map (:slot-id %))))]
                          (some-> (first (filter #(rename-chain-reaches?
                                                    % slot-id slot-map)
                                                 own-slots))
                                  :name
                                  keyword)))
                      (inheritance-chain* fn-id lookups))]
    (or renamed
        (some-> (get-in slot-map [slot-id :name]) keyword))))


(defn effective-reader-slot-id
  "Slot-id sibling of `rename-for-slot` — Phase 4 of the slot-id-keyed
   runtime refactor.

   For a `:free` reader at `fn-id` reading the chain-leaf
   `slot-id`, returns the slot-id the runtime's `fa` actually carries
   that value under. Walks the inheritance chain (closest-first); the
   first own-slot whose `source-slot-id` chain transitively reaches
   `slot-id` wins — its OWN id is the reader key. Falls back to the
   chain-leaf itself.

   Why this matters for collision-avoidance: two inline-anons of the
   same base-fn (e.g. `{:parent :assoc :args {:value {:as :src}}}`
   and `{:parent :assoc :args {:value {:as :alt}}}`) both have the
   binding's `:slot-id` field = `:assoc.value-sid` (chain-leaf is
   shared across all `:assoc` instances). Pure chain-leaf reading
   collides — both inline-anons would index the same `fa` cell.

   The rename binding creates a NEW slot row on the anon (parser
   Phase 6c) whose `source-slot-id` chains back to `:assoc.value-sid`.
   That rename slot has a UNIQUE id. With this resolver, each
   anon's reader gets its own rename slot id as the `fa` key, and
   the caller's `:src` / `:alt` values land in DISTINCT cells.

   Mirrors `rename-for-slot`'s walk but returns `:id` instead of
   `:name`."
  [fn-id slot-id {:keys [slot-map fn-slots-by-fn] :as lookups}]
  (or (some (fn [fid]
              (let [own-slots (->> (get fn-slots-by-fn fid [])
                                   (keep #(get slot-map (:slot-id %))))]
                (some-> (first (filter #(rename-chain-reaches?
                                          % slot-id slot-map)
                                       own-slots))
                        :id)))
            (inheritance-chain* fn-id lookups))
      slot-id))
