(ns graphden.layout.data
  "Data-loading + slot-view synthesis for the layout pipeline (Stage 1).

   Extracted from `graphden.layout.graph` so the graph-building cluster
   stays focused on the build-graph-elements algorithm. Holds:

   - `synth-arg-id` — deterministic UUID for (fn-id, slot-id) anchor
     identity;
   - inheritance walks (`get-inheritance-levels` / `get-inheritance-chain`
     / `get-inheritance-chain*` memoised);
   - slot-view synthesis — flattening `{fns slots fn-slots bindings
     list-items}` into the (fn × slot) row shape the rest of the
     pipeline walks (`derive-fn-slot-views`);
   - data loading from storage (`load-graph-entities-uncached`);
   - lookup-map construction (`build-lookups` / `cached-build-lookups`).

   Leaf namespace within `graphden.layout.*` — depends on nothing in
   that hierarchy. The `ctx`-aware cache wrapper around
   `load-graph-entities-uncached` lives in the defbase shim, not here."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


;; =============================================================================
;; NODE ID UTILITIES
;; =============================================================================

(defn synth-arg-id
  "Deterministic UUID for a (fn-id, slot-id) pair. Same pair always
   produces the same id, so anchor rows have a stable identity across
   layout invocations."
  ^java.util.UUID
  [^java.util.UUID fn-id ^java.util.UUID slot-id]
  (java.util.UUID. (bit-xor (java.util.UUID/.getMostSignificantBits fn-id)
                            (java.util.UUID/.getMostSignificantBits slot-id))
                   (bit-xor (java.util.UUID/.getLeastSignificantBits fn-id)
                            (java.util.UUID/.getLeastSignificantBits slot-id))))


;; =============================================================================
;; INHERITANCE WALKS
;; =============================================================================

(defn get-inheritance-levels
  "Get inheritance as BFS layers from fn-id.
   Returns a vector of vectors: [[fn-id] [parent1 parent2 ...] [gp1 gp2 ...] ...]
   Each layer contains all fns reachable in exactly N parent-hops, deduped
   so each fn appears only at its shallowest level. Stops when no new fns
   are discovered."
  [fn-id fn-map]
  (loop [current-level [fn-id]
         visited #{fn-id}
         levels []]
    (if (empty? current-level)
      levels
      (let [next-level (->> current-level
                            (mapcat (fn [fid]
                                      (when-let [f (get fn-map fid)]
                                        (:parent-ids f))))
                            (remove nil?)
                            (remove visited)
                            distinct
                            vec)
            new-visited (into visited next-level)]
        (recur next-level new-visited (conj levels current-level))))))


(defn- get-inheritance-chain
  "Flat list of all ancestor fn-ids reachable from fn-id (including fn-id itself).
   Order is BFS, with each fn appearing exactly once at its shallowest depth.
   Use get-inheritance-levels when you need the per-level structure."
  [fn-id fn-map]
  (vec (mapcat identity (get-inheritance-levels fn-id fn-map))))


(defn get-inheritance-chain*
  "Memoised variant of `get-inheritance-chain`. Reads from / writes
   through `:chain-cache` on the lookups map. Mirrors the
   `executor.compile.lookups/inheritance-chain*` pattern. Falls back
   to a fresh walk when `:chain-cache` isn't present so tests that
   hand-build a lookups map outside `build-lookups` still get correct
   behaviour.

   `build-graph-elements` calls this per ref / per ancestor / per
   binding-classification — the dominant call sites all share a
   single lookups map for one layout request, so the cache pays back
   across the whole pipeline run."
  [fn-id {:keys [fn-map chain-cache]}]
  (if chain-cache
    (or (get @chain-cache fn-id)
        (let [chain (get-inheritance-chain fn-id fn-map)]
          (swap! chain-cache assoc fn-id chain)
          chain))
    (get-inheritance-chain fn-id fn-map)))


;; =============================================================================
;; DATA LOADING FROM STORAGE
;; =============================================================================

;; ---------------------------------------------------------------------
;; Slot-view synthesis
;;
;; Layout's helpers think in terms of (fn × slot) rows: each fn exposes
;; its own slots plus inherited ones, with the closest binding overlaying
;; `:value` / `:ref-id`. The new storage model keeps slots, bindings,
;; and list-items in separate tables; `derive-fn-slot-views` flattens
;; them into the row shape the rest of this file consumes.
;;
;; This is purely an internal layout concept — `/api/graph/entities`
;; ships the underlying tables; the editor JS reads them directly via
;; `slotMap` / `bindingsByFn` / `itemsByBinding`. Nobody outside this
;; namespace should depend on the synthetic shape.

(defn- substitution-context-bindings-by-fn
  "Bindings whose slot's owner is OUTSIDE the binding-fn's parent
   chain. These come from ref-chain free-arg propagation — e.g.
   `_app-ring-response :args {:func :_router}` binds the slot `:func`
   of `:invoke`, which is reached only via `:m → :router-result →
   :invoke`. The Pass 1 chain walk would otherwise miss the slot.
   The expansion's migration mechanism (slot-owner chain matched
   against ancestor-ref chains) routes the synth row down to the
   right ref-target.

   Returns a map fn-id → vector of bindings."
  [fn-by-id slot-owner-by-id bindings]
  (reduce
    (fn [acc b]
      (let [pchain (set (get-inheritance-chain (:fn-id b) fn-by-id))
            owner (get slot-owner-by-id (:slot-id b))]
        (if (or (nil? owner) (contains? pchain owner))
          acc
          (update acc (:fn-id b) (fnil conj []) b))))
    {}
    bindings))


(defn- build-anchor-row
  "Build one anchor row for `(fn-id, slot-id)`. `inherits-from-fid`
   is the parent fn whose slot this row inherits (nil for the
   binding-owner itself OR for ref-chain-propagated bindings whose
   `:source-id` chain doesn't apply)."
  [fn-id slot-id inherits-from-fid {:keys [fn-by-id slot-by-id binding-by items-by-binding
                                           slot-by-fn-source-slot]}]
  (let [slot (get slot-by-id slot-id)
        b (get binding-by [fn-id slot-id])
        ;; Renamed-view slot owned by `fn-id` whose source-slot-id
        ;; points back at slot-id. Only the binding-owner's own renames
        ;; affect the displayed name here; ancestor-owned renames flow
        ;; through different build-anchor-row calls keyed on those
        ;; ancestors.
        renamed-view (when slot-by-fn-source-slot
                       (get slot-by-fn-source-slot [fn-id slot-id]))
        eff-tfn (or (some-> b :type-override-fn-id fn-by-id)
                    (some-> (:type-fn-id slot) fn-by-id))
        type-kw (or (when (and eff-tfn
                               (empty? (:parent-ids eff-tfn))
                               (nil? (:base-fn-id eff-tfn))
                               (nil? (:element-fn-id eff-tfn))
                               (some? (:name eff-tfn)))
                      (keyword (:name eff-tfn)))
                    ;; Anonymous fn-type rows (synthesised for inline
                    ;; `[:fn args ret]` slot declarations) have nil
                    ;; `:name` but a `:constraint [:fn …]`. Surface
                    ;; them as the `:fn` primitive marker so the
                    ;; flat-type chip / `compute-fn-typed-fn-ids`
                    ;; both recognise the slot as HOF-callable. The
                    ;; structural shape is recovered separately via
                    ;; the rich-types snapshot.
                    (when (and eff-tfn
                               (vector? (:constraint eff-tfn))
                               (= :fn (first (:constraint eff-tfn))))
                      :fn)
                    :jsonb)
        inherits-from (when (and inherits-from-fid (not= inherits-from-fid fn-id))
                        (synth-arg-id inherits-from-fid slot-id))
        first-item-id (some-> b :id items-by-binding first :id)]
    {:id (synth-arg-id fn-id slot-id)
     :fn-id fn-id
     :slot-id slot-id
     :binding-id (:id b)
     :name (or (:name renamed-view) (:name slot))
     :type type-kw
     :required (get slot :required true)
     :source-id inherits-from
     :value (:value b)
     :value-present (true? (:value-present b))
     :ref-id (:ref-fn-id b)
     :next-arg-id first-item-id
     :append? (true? (:list-append b))}))


(defn- anchor-rows-for-fn
  "Build the per-fn anchor rows for `fn-id`'s inheritance chain. Each
   `(fn, slot)` pair contributes one row; renamed-view slots
   (`:source-slot-id` set) collapse with their source so a single
   logical slot doesn't emit twice.

   Two passes: parent-chain slots (pass 1), then bindings on
   ref-chain-propagated slots from `binding-extra-by-fn` (pass 2 —
   migration sees these through their slot-owner)."
  [fn-id
   {:keys [fn-by-id slot-by-id] :as anchor-ctx}
   own-fn-slots binding-extra-by-fn]
  (let [chain (get-inheritance-chain fn-id fn-by-id)
        seen-slots (volatile! #{})
        rows (volatile! [])
        emit (fn [inherits-from-fid sid]
               (when (and (get slot-by-id sid)
                          (not (contains? @seen-slots sid)))
                 ;; A renamed-view slot (`{:as …}` — a slot with
                 ;; `:source-slot-id`) and the source slot it renames
                 ;; are ONE logical slot. Mark the whole source chain
                 ;; seen so the inherited raw slot doesn't re-emit.
                 (loop [s sid, guard #{}]
                   (vswap! seen-slots conj s)
                   (let [src (:source-slot-id (get slot-by-id s))]
                     (when (and src (not (contains? guard s)))
                       (recur src (conj guard s)))))
                 (vswap! rows conj
                         (build-anchor-row fn-id sid inherits-from-fid anchor-ctx))))]
    (doseq [fid chain
            fs (get own-fn-slots fid [])]
      (emit fid (:slot-id fs)))
    (doseq [b (get binding-extra-by-fn fn-id [])]
      (emit nil (:slot-id b)))
    @rows))


(defn- item-rows-for-binding
  "Build the per-binding sequence-item rows. First item's `:prev-arg-id`
   points at the binding's synthetic anchor — the editor's 'render `×`
   and `+` on every item with a non-nil prev-arg-id' rule relies on
   it. Subsequent items chain to the prior item's `:id`."
  [bnd items-by-binding]
  (let [items (get items-by-binding (:id bnd) [])
        anchor-id (synth-arg-id (:fn-id bnd) (:slot-id bnd))
        sorted (vec items)]
    (map-indexed
      (fn [idx item]
        (let [next-item (get sorted (inc idx))
              prev-item-id (if (pos? idx)
                             (:id (get sorted (dec idx)))
                             anchor-id)]
          {:id (:id item)
           :fn-id (:fn-id bnd)
           :slot-id (:slot-id bnd)
           :binding-id (:id bnd)
           :item-id (:id item)
           :name nil
           :type :any
           :required false
           :source-id anchor-id
           :value (:value item)
           :ref-id (:ref-fn-id item)
           :next-arg-id (some-> next-item :id)
           :prev-arg-id prev-item-id}))
      sorted)))


(defn derive-fn-slot-views
  "Flatten {fns slots fn-slots bindings list-items} into the row shape
   layout's helpers walk. Each `(fn, slot)` pair contributes one anchor
   row; each binding-list-item contributes one item row.

   Anchor row keys: :id :fn-id :slot-id :binding-id :name :type
                    :required :source-id :value :ref-id
   Item row keys add: :item-id :prev-arg-id :next-arg-id

   `:source-id` on an anchor row points at the same slot's anchor one
   parent hop up the chain (nil for the slot's defining fn), so the
   pipeline's source-chain walks model inheritance the same way the
   legacy primary/inherited arg pair did. `:source-id` on an item row
   points at the binding's anchor — items never inherit beyond their
   own binding.

   Top-level assembler: builds the indexes once, then delegates per-fn
   anchor construction to `anchor-rows-for-fn` and per-binding item
   construction to `item-rows-for-binding`."
  [{:keys [fns slots fn-slots bindings list-items]}]
  (let [fn-by-id (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        ;; Single pass over fn-slots populates both indexes via
        ;; transients — the previous code walked the vector twice
        ;; (group-by + into-map).
        [own-fn-slots slot-owner-by-id]
        (let [own (volatile! (transient {}))
              owners (volatile! (transient {}))]
          (run! (fn [fs]
                  (let [fid (:fn-id fs)
                        sid (:slot-id fs)]
                    (vswap! own (fn [m]
                                  (assoc! m fid
                                          (conj (or (get m fid) []) fs))))
                    (vswap! owners assoc! sid fid)))
                fn-slots)
          [(persistent! @own) (persistent! @owners)])
        binding-by (into {} (map (juxt (juxt :fn-id :slot-id) identity)) bindings)
        items-by-binding (->> list-items
                              (sort-by :position)
                              (reduce (fn [acc i]
                                        (update acc (:binding-id i) (fnil conj []) i))
                                      {}))
        binding-extra-by-fn (substitution-context-bindings-by-fn
                              fn-by-id slot-owner-by-id bindings)
        anchor-ctx {:fn-by-id fn-by-id :slot-by-id slot-by-id
                    :binding-by binding-by :items-by-binding items-by-binding}
        anchor-rows (vec (mapcat #(anchor-rows-for-fn (:id %) anchor-ctx
                                                      own-fn-slots binding-extra-by-fn)
                                 fns))
        item-rows (vec (mapcat #(item-rows-for-binding % items-by-binding)
                               bindings))]
    (into anchor-rows item-rows)))


(defn load-graph-entities-uncached
  "Pure storage read — fetch every fn/slot/fn-slot/binding/list-item
   row and attach the derived `:args` slot views. Takes a plain
   storage; the `ctx`-aware cache wrapper lives in the defbase shim."
  [storage]
  (let [graph (if (instance? VersionedStorage storage)
                (vs/query-all-graph-entities storage)
                {:fns        (vec (sp/query-entities storage :fn {}))
                 :slots      (vec (sp/query-entities storage :slot {}))
                 :fn-slots   (vec (sp/query-entities storage :fn-slot {}))
                 :bindings   (vec (sp/query-entities storage :binding {}))
                 :list-items (vec (sp/query-entities storage :binding-list-item {}))})]
    (assoc graph :args (derive-fn-slot-views graph))))


(defn ensure-synth-args
  "Cached graph data may come from `compile-runtime/rebuild!` which
   doesn't carry `:args`. Synthesise the slot views on demand if
   missing."
  [graph]
  (cond-> graph
    (not (contains? graph :args)) (assoc :args (derive-fn-slot-views graph))))


(defn build-lookups
  "Build lookup maps from raw data.

   Two parallel views live here while the pipeline migrates:

   - The (fn × slot) row view (`:arg-map`, `:args-by-fn`) — still used
     by most helpers. Each row carries `:fn-id :slot-id :value :ref-id
     :name :type` plus the synthetic `:id` (UUID xor of fn-id+slot-id)
     and `:source-id` (the same slot's row at one parent hop up).

   - The slot/binding tables (`:slot-map :fn-slots-by-fn
     :binding-by-fn-slot :items-by-binding`) — used by helpers that
     have been ported off the source-id chain idiom in favour of
     walking `parent-ids` directly."
  [{:keys [fns args slots fn-slots bindings list-items]}]
  (let [fn-map (into {} (map (fn [f] [(:id f) f]) fns))
        arg-map (into {} (map (fn [a] [(:id a) a]) args))
        args-by-fn (reduce (fn [m a]
                             (if-let [fn-id (:fn-id a)]
                               (update m fn-id (fnil conj []) a)
                               m))
                           {} args)
        slot-map (into {} (map (juxt :id identity)) slots)
        fn-slots-by-fn (reduce (fn [m fs]
                                 (if-let [fid (:fn-id fs)]
                                   (update m fid (fnil conj []) fs)
                                   m))
                               {} fn-slots)
        binding-by-fn-slot (into {}
                                 (map (fn [b] [[(:fn-id b) (:slot-id b)] b]))
                                 bindings)
        ;; Phase 6c — index renamed-view slots by (fn-id, source-slot-id)
        ;; so layout helpers can answer "the displayed name of this
        ;; binding's slot" via FK lookup instead of the legacy
        ;; `binding.rename_to` text.
        slot-by-fn-source-slot (into {}
                                     (keep (fn [fs]
                                             (when-let [s (get slot-map (:slot-id fs))]
                                               (when-let [src (:source-slot-id s)]
                                                 [[(:fn-id fs) src] s]))))
                                     fn-slots)
        bindings-by-fn (reduce (fn [m b]
                                 (if-let [fid (:fn-id b)]
                                   (update m fid (fnil conj []) b)
                                   m))
                               {} bindings)
        items-by-binding (->> list-items
                              (sort-by :position)
                              (reduce (fn [m it]
                                        (if-let [bid (:binding-id it)]
                                          (update m bid (fnil conj []) it)
                                          m))
                                      {}))
        ;; slot-id → fn-id of the fn that DECLARES the slot (i.e. has
        ;; a fn-slot junction row for it). One owner per slot — slot
        ;; identities are derived from `(owner-fn-id, slot-name)`.
        slot-owner (into {} (map (fn [fs] [(:slot-id fs) (:fn-id fs)])) fn-slots)
        ;; name (keyword) → fn-row. Lets type-row internals resolve
        ;; `:int` / `:null` / `:text` etc. in `:constraint` payloads
        ;; without having to walk `fn-map` linearly.
        fn-by-name (into {} (keep (fn [f]
                                    (when-let [n (:name f)]
                                      [(keyword n) f])))
                         fns)]
    {:fn-map fn-map
     :fn-by-name fn-by-name
     :arg-map arg-map
     :args-by-fn args-by-fn
     :slot-map slot-map
     :fn-slots-by-fn fn-slots-by-fn
     :slot-by-fn-source-slot slot-by-fn-source-slot
     :binding-by-fn-slot binding-by-fn-slot
     :bindings-by-fn bindings-by-fn
     :items-by-binding items-by-binding
     :slot-owner slot-owner
     ;; Per-request inheritance-chain memo. `build-graph-elements`
     ;; hits get-inheritance-chain dozens of times for the same
     ;; fn-ids; cache the BFS walk for the lifetime of one layout
     ;; request via this atom.
     :chain-cache (atom {})}))


(def ^:private cached-build-lookups-max-size 8)


(defonce ^:private cached-build-lookups-state (atom []))


(defn cached-build-lookups
  "Reference-identity-memoised wrapper over `build-lookups`. Layout
   recomputes ~8 in-memory indexes per request (~5-10ms each); when
   the same graph map is passed (cached-or-load-graph between
   mutations), reuses the result. Bounded LRU; identical to the
   policy used in `graphden.executor.compile.lookups`."
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
