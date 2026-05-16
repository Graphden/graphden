(ns graphden.packages.app.layout.impls
  "Graph layout calculation - fetches data from DB, builds graph, computes layout.

   API: POST /api/graph/layout
   Input: {root-id: uuid, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}}

   Core layout rules:
   1. Children of a node are placed RIGHT of parent, never above
   2. First child is on SAME ROW as parent, others are BELOW (each on own row)
   3. Horizontal branch = chain of first children
   4. Shared nodes (multiple parents) are placed by SHALLOWEST parent (min column depth)
   5. Splitting siblings (leading to same shared node) must be adjacent in child list
   6. Parents of shared nodes are aligned via column offsets (shallower parents shift right)"
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :as defbase]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


;; =============================================================================
;; NODE ID UTILITIES
;; =============================================================================

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

(defn- synth-arg-id
  "Deterministic UUID for a (fn-id, slot-id) pair. Same pair always
   produces the same id, so anchor rows have a stable identity across
   layout invocations."
  ^java.util.UUID
  [^java.util.UUID fn-id ^java.util.UUID slot-id]
  (java.util.UUID. (bit-xor (java.util.UUID/.getMostSignificantBits fn-id)
                            (java.util.UUID/.getMostSignificantBits slot-id))
                   (bit-xor (java.util.UUID/.getLeastSignificantBits fn-id)
                            (java.util.UUID/.getLeastSignificantBits slot-id))))


(defn- chain-of
  "BFS of an fn's parent-id closure (self first, ancestors after).
   Inlined here so this helper runs before the rest of the file's
   inheritance walkers are declared."
  [fn-by-id fn-id]
  (loop [acc [fn-id], seen #{fn-id},
         queue (vec (->> (get-in fn-by-id [fn-id :parent-ids])
                         (remove nil?)))]
    (if (empty? queue)
      acc
      (let [fid (first queue)
            rest-q (subvec queue 1)]
        (if (contains? seen fid)
          (recur acc seen rest-q)
          (let [pids (->> (get-in fn-by-id [fid :parent-ids])
                          (remove nil?)
                          (remove seen))]
            (recur (conj acc fid)
                   (conj seen fid)
                   (into rest-q pids))))))))


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
      (let [pchain (set (chain-of fn-by-id (:fn-id b)))
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
        ;; Phase 6c — renamed-view slot owned by `fn-id` whose
        ;; source-slot-id points back at slot-id. Replaces the legacy
        ;; `(:rename-to b)` text lookup. Same scope as before — only
        ;; the binding-owner's own renames affect the displayed name
        ;; here; ancestor-owned renames flow through different
        ;; build-anchor-row calls keyed on those ancestors.
        renamed-view (when slot-by-fn-source-slot
                       (get slot-by-fn-source-slot [fn-id slot-id]))
        eff-tfn (or (some-> b :type-override-fn-id fn-by-id)
                    (some-> (:type-fn-id slot) fn-by-id))
        type-kw (or (when (and eff-tfn
                               (empty? (:parent-ids eff-tfn))
                               (nil? (:impl-hash eff-tfn))
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
     :ref-id (:ref-fn-id b)
     :next-arg-id first-item-id
     :append? (true? (:list-append b))}))


(defn- derive-fn-slot-views
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
   own binding."
  [{:keys [fns slots fn-slots bindings list-items]}]
  (let [fn-by-id (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        own-fn-slots (group-by :fn-id fn-slots)
        binding-by (into {} (map (juxt (juxt :fn-id :slot-id) identity)) bindings)
        items-by-binding (->> list-items
                              (sort-by :position)
                              (reduce (fn [acc i]
                                        (update acc (:binding-id i) (fnil conj []) i))
                                      {}))
        slot-owner-by-id (into {} (map (juxt :slot-id :fn-id)) fn-slots)
        binding-extra-by-fn (substitution-context-bindings-by-fn
                              fn-by-id slot-owner-by-id bindings)
        ctx {:fn-by-id fn-by-id :slot-by-id slot-by-id
             :binding-by binding-by :items-by-binding items-by-binding}
        anchor-rows
        (vec
          (mapcat
            (fn [{fn-id :id}]
              (let [chain (chain-of fn-by-id fn-id)
                    seen-slots (volatile! #{})
                    rows (volatile! [])
                    emit (fn [inherits-from-fid sid]
                           (when (and (get slot-by-id sid)
                                      (not (contains? @seen-slots sid)))
                             (vswap! seen-slots conj sid)
                             (vswap! rows conj
                                     (build-anchor-row fn-id sid inherits-from-fid ctx))))]
                ;; Pass 1 — parent-chain slots.
                (doseq [fid chain
                        fs (get own-fn-slots fid [])]
                  (emit fid (:slot-id fs)))
                ;; Pass 2 — bindings on ref-chain-propagated slots.
                ;; Migration sees these through their slot-owner.
                (doseq [b (get binding-extra-by-fn fn-id [])]
                  (emit nil (:slot-id b)))
                @rows))
            fns))
        item-rows
        (vec
          (mapcat
            (fn [b]
              (let [items (get items-by-binding (:id b) [])
                    anchor-id (synth-arg-id (:fn-id b) (:slot-id b))
                    sorted (vec items)]
                (map-indexed
                  (fn [idx item]
                    (let [next-item (get sorted (inc idx))
                          ;; First item's `:prev-arg-id` points at the
                          ;; sequence anchor — the editor's "render `×`
                          ;; and `+` on every item with a non-nil
                          ;; prev-arg-id" rule relies on it.
                          prev-item-id (if (pos? idx)
                                         (:id (get sorted (dec idx)))
                                         anchor-id)]
                      {:id (:id item)
                       :fn-id (:fn-id b)
                       :slot-id (:slot-id b)
                       :binding-id (:id b)
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
            bindings))]
    (into anchor-rows item-rows)))


(defn- load-graph-entities-uncached
  [storage]
  (let [graph (if (instance? VersionedStorage storage)
                (vs/query-all-graph-entities storage)
                {:fns        (vec (sp/query-entities storage :fn {}))
                 :slots      (vec (sp/query-entities storage :slot {}))
                 :fn-slots   (vec (sp/query-entities storage :fn-slot {}))
                 :bindings   (vec (sp/query-entities storage :binding {}))
                 :list-items (vec (sp/query-entities storage :binding-list-item {}))})]
    (assoc graph :args (derive-fn-slot-views graph))))


;; Graph entities are loaded ONCE per executor context and cached on
;; `(:graph-cache ctx)`. Layout runs on every hover-preview + click; a
;; full `query-all-graph-entities` takes ~130ms on the current graph, so
;; we cannot re-query per request. The compile-at-startup executor
;; already assumes graph state is built once at startup; layout follows
;; the same model. Invalidation is driven by CRUD mutation defbase's
;; (create/update/delete entity, sequence append/remove) calling
;; `graphden.executor.context/invalidate-graph-cache!` after writing.
(defn- ensure-synth-args
  "Cached graph data may come from `compile-runtime/rebuild!` which
   doesn't carry `:args`. Synthesise the slot views on demand if
   missing."
  [graph]
  (cond-> graph
    (not (contains? graph :args)) (assoc :args (derive-fn-slot-views graph))))


(defn- load-graph-entities
  [ctx]
  (or (some-> (exec-ctx/cached-graph ctx) ensure-synth-args)
      (let [data (load-graph-entities-uncached (:storage ctx))]
        (exec-ctx/fill-graph-cache! ctx data)
        data)))


(defn- build-lookups
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
     :slot-owner slot-owner}))


;; =============================================================================
;; INHERITANCE & ARG RESOLUTION
;; =============================================================================

(defn- get-inheritance-levels
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


(defn- resolve-arg-name
  "Effective name of an arg row. Anchor rows already carry the
   resolved name (closest rename-to, falling back to slot.name) — see
   `derive-fn-slot-views`. Item rows have `:name=nil`; their anchor
   sits exactly one source-id hop up, so a single fallback step is
   enough."
  [arg arg-map]
  (or (:name arg)
      (some-> arg :source-id arg-map :name)))


;; =============================================================================
;; BINDINGS RESOLUTION
;; =============================================================================

(defn- arg-ids-from
  "Pluck slot-id/binding-id/item-id/fn-id from an arg row so it can be
   merged into the internal `compute-display-args` arg-row shape and
   eventually surface on the emitted cytoscape node via
   `arg-row->node-id-fields`."
  [arg]
  (select-keys arg [:slot-id :binding-id :item-id :fn-id]))


(defn- add-bindings-from-fn
  "Add `fn-id`'s OWN binding rows to the slot-id-keyed bindings map.

   A binding's mere presence (whether scalar `:value` / `:ref-fn-id`
   or `:list-append` with items) marks the slot as bound at fn-id's
   level. List-items live UNDER a binding row and don't introduce new
   slot identities, so the binding's own entry suffices to make
   downstream `(contains? bindings slot-id)` checks succeed.

   Caller is responsible for reduce ordering: `build-chain-bindings`
   walks ancestors-FIRST so descendants overlay via `assoc`."
  [fn-id bindings lookups]
  (let [{:keys [bindings-by-fn slot-map items-by-binding slot-by-fn-source-slot]} lookups]
    (reduce
      (fn [b binding]
        (let [sid (:slot-id binding)
              slot (get slot-map sid)
              ;; Phase 6c — same lookup as build-anchor-row. The
              ;; renamed-view's name overrides the source slot's
              ;; for display purposes when the binding-owner has
              ;; declared a rename.
              renamed-view (when slot-by-fn-source-slot
                             (get slot-by-fn-source-slot [(:fn-id binding) sid]))
              has-value (some? (:value binding))
              has-ref (some? (:ref-fn-id binding))
              has-items (seq (get items-by-binding (:id binding) []))]
          (if (and slot (or has-value has-ref has-items))
            (assoc b sid {:arg-name (or (:name renamed-view) (:name slot))
                          :value (:value binding)
                          :ref-id (:ref-fn-id binding)
                          :arg-id (synth-arg-id fn-id sid)
                          :slot-id sid
                          :binding-id (:id binding)
                          :fn-id fn-id})
            b)))
      bindings
      (get bindings-by-fn fn-id []))))


(defn- build-chain-bindings
  "Slot-id-keyed bindings map for the BFS inheritance closure of a fn.
   Closer fn (descendant) wins for the same slot. We walk ANCESTORS
   first and let `assoc` overwrite as we descend — natural reduce
   semantics produce closest-wins."
  [levels lookups]
  (reduce
    (fn [b fn-id] (add-bindings-from-fn fn-id b lookups))
    {}
    (reverse (mapcat identity levels))))


(defn- build-arg-bindings
  "Bindings from fn's OWN binding rows only (level-0, non-expanded
   mode). Ancestor bindings excluded — they only appear when the user
   explicitly expands those depths."
  [fn-id lookups]
  (add-bindings-from-fn fn-id {} lookups))


;; =============================================================================
;; GRAPH BUILDING (translated from editor-graph.js)
;; =============================================================================

(defn- truncate-label
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))


(defn- walk-anchor-chain
  "From a sequence anchor arg, walks next-arg-id via arg-map and returns
   the ordered vector of item arg entities. When the anchor has
   `:append? true`, recursively prepends the parent's effective chain
   (resolved via `:source-id`) so callers see parent's items followed
   by this anchor's appended items."
  [anchor arg-map]
  (let [own (loop [cur (:next-arg-id anchor)
                   acc []
                   depth 0]
              (cond
                (or (nil? cur) (> depth 10000)) acc
                :else
                (let [item (get arg-map cur)]
                  (if (nil? item)
                    acc
                    (recur (:next-arg-id item) (conj acc item) (inc depth))))))
        prepended (when (true? (:append? anchor))
                    (when-let [parent-anchor (some-> (:source-id anchor) arg-map)]
                      (when (= :sequence (:type parent-anchor))
                        (walk-anchor-chain parent-anchor arg-map))))]
    (vec (concat (or prepended []) own))))


(defn- sequence-anchor?
  "A :sequence-typed arg is a chain anchor only when it doesn't carry
   its own fn-ref binding. An arg whose `:ref-id` is set IS a
   ref-to-list-returning-fn (e.g. `:_router/:routes :all`) and must
   render as a regular ref edge — not as an empty-chain sentinel."
  [arg]
  (and (= :sequence (:type arg))
       (nil? (:ref-id arg))))


(defn- expand-sequence-anchor
  "For a sequence anchor, returns a vector of synthetic arg descriptors —
   one per chain item, labeled `<slot>[idx]`. Anchor itself is not emitted.
   Items with ref-id become :ref entries, items with value become :value.

   Empty anchors produce a single sentinel `:unset` entry pointing AT the
   anchor with `:sequence-anchor? true`, so the frontend can render an
   empty-chain placeholder whose click fires the sequence-append flow
   (rather than the regular free-arg binder, which would try to PUT
   `value=` / `ref-id=` on the anchor itself)."
  [anchor slot-name arg-map]
  (let [items (walk-anchor-chain anchor arg-map)]
    (if (empty? items)
      [(merge {:type :unset :arg-name slot-name
               :arg-type (:type anchor) :arg-id (:id anchor)
               :sequence-anchor? true}
              (arg-ids-from anchor))]
      (into []
            (map-indexed
              (fn [idx item]
                (let [lbl (str slot-name "[" idx "]")
                      ids (arg-ids-from item)]
                  (cond
                    (some? (:ref-id item))
                    (merge {:type :ref :arg-name lbl
                            :ref-id (:ref-id item) :arg-id (:id item)
                            :is-binding false}
                           ids)

                    (some? (:value item))
                    (merge {:type :value :arg-name lbl
                            :value (:value item) :arg-id (:id item)}
                           ids)

                    :else
                    (merge {:type :unset :arg-name lbl
                            :arg-type (:type item) :arg-id (:id item)}
                           ids)))))
            items))))


;; =============================================================================
;; PURE HELPERS used by build-graph-elements
;; =============================================================================

(defn- get-effective-spec
  "Look up expansion spec by cytoscape node-id string. The `expansions`
   map is keyed by the same node-id that `add-fn-node` emits, so the
   match is exact."
  [expansions node-id]
  (or (get expansions node-id) 0))


(defn- spec->expand-set
  "Convert any expansion spec into a set of fn-ids that should be
   `merged into` the focus fn's display (always includes fn-id itself)."
  [fn-map fn-id spec]
  (let [levels (get-inheritance-levels fn-id fn-map)
        full-depth (cond
                     (integer? spec) spec
                     (map? spec) (or (:full-depth spec) 0)
                     :else 0)
        partial-fns (when (map? spec)
                      (set (map (fn [id]
                                  (if (uuid? id)
                                    id
                                    (parse-uuid (str id))))
                                (:partial-fns spec))))
        cascade-fns (set (mapcat identity
                                 (take (inc full-depth) levels)))]
    (cond-> cascade-fns
      (seq partial-fns) (into partial-fns))))


(defn- spec-trivial?
  "True for specs that don't expand anything beyond the focus fn."
  [spec]
  (cond
    (integer? spec) (zero? spec)
    (map? spec) (and (zero? (or (:full-depth spec) 0))
                     (empty? (:partial-fns spec)))
    :else true))


(defn- arg-is-optional?
  "Walk the source-id chain to the root arg and return its `:required`
   value. Propagated shadows have `:required=nil`, so we need to look at
   the base-fn's primary arg to know whether an unbound slot is truly
   optional (`:required false` → caller may leave it blank) or required
   (no explicit `:required false` → caller must supply it)."
  [_arg-map arg]
  ;; Anchors carry the slot row's `:required` directly (see
  ;; derive-fn-slot-views) so the legacy walk-to-root step is
  ;; redundant — every anchor on the chain represents the same slot
  ;; and therefore the same value.
  (false? (:required arg)))


;; Forward declarations — these defn-s reference each other. The
;; pure-helper block (target-interface-names / compute-edge-label /
;; build-inverse-source-map / caller-bound-arg) lives below the
;; mutating helpers but is used by them.
(declare target-interface-names
         compute-edge-label
         compute-edge-type-chain
         edge-narrowing-fields
         build-inverse-source-map
         caller-bound-arg
         terminal-source-of
         child-covered-sources-for-fn)


(defn- record-optional-unset!
  "Append `arg-name` to `state.optional-unsets-by-node[node-id]`. Used
   to populate a node's `+default, +else` badge with the names of
   unbound optional args the caller chose to leave blank."
  [state node-id arg-name]
  (when (and node-id arg-name)
    (swap! state update-in [:optional-unsets-by-node node-id]
           (fn [xs] (if xs (conj xs arg-name) [arg-name])))))


(defn- record-hof-captured!
  "Append `arg-name` to `state.hof-captured-by-node[node-id]`. Used to
   populate the λname HOF capture badge on nodes whose lambda-param
   free arg is supplied per-call by the surrounding HOF invocation."
  [state node-id arg-name]
  (when (and node-id arg-name)
    (swap! state update-in [:hof-captured-by-node node-id]
           (fn [xs] (if xs (conj xs arg-name) [arg-name])))))


(defn- arg-row->node-id-fields
  "Extract slot/binding/item/fn ids from an arg row for embedding in
   node data. Editor JS reads these directly so it can address the
   real binding row through /api/entities/binding/:id without going
   through a synth-arg-id reverse-lookup."
  [arg]
  (cond-> {}
    (:slot-id arg)    (assoc :slotId    (str (:slot-id arg)))
    (:binding-id arg) (assoc :bindingId (str (:binding-id arg)))
    (:item-id arg)    (assoc :itemId    (str (:item-id arg)))
    (:fn-id arg)      (assoc :fnId      (str (:fn-id arg)))
    (:type arg)       (assoc :argType   (-> arg :type name))))


(defn- edge-source-fields
  "Edge-data shape mirroring `arg-row->node-id-fields` for the SOURCE
   side of an inheritance/ref edge — same slot/binding/item/fn ids
   plus `:sourcePrevArgId` / `:sourceNextArgId` so editor JS can render
   sequence-item × / + buttons without consulting `lookups.argMap`.
   Pulled from the synth arg row via arg-id."
  [lookups source-arg-id]
  (when source-arg-id
    (when-let [arg (get-in lookups [:arg-map source-arg-id])]
      (cond-> (arg-row->node-id-fields arg)
        (:prev-arg-id arg) (assoc :sourcePrevArgId (str (:prev-arg-id arg)))
        (:next-arg-id arg) (assoc :sourceNextArgId (str (:next-arg-id arg)))))))


(defn- add-arg-value-node
  "Emit a value-style arg node + edge linking it to `source-node-id`.
   No-op when the node is already present (dedup by `:added-node-ids`).
   Returns the emitted node-id."
  [state lookups arg source-node-id expanded-fns]
  (let [arg-id (:arg-id arg)
        arg-name (:arg-name arg)
        value (:value arg)
        node-id (str "arg-" source-node-id "-" arg-id)
        edge-id (str "e-val-" source-node-id "-" arg-id)]
    (when-not (contains? (:added-node-ids @state) node-id)
      (swap! state update :added-node-ids conj node-id)
      ;; pr-str renders keywords with the leading `:`, distinguishing
      ;; them from same-name strings. json/generate-string would lose
      ;; that distinction (`:foo` and `"foo"` both serialised as `"foo"`),
      ;; so the wire `:value` for a keyword goes out as `":foo"` (with
      ;; the colon) — same convention the codec's `preserve-keywords`
      ;; already uses for jsonb columns. The editor's `classifyLiteralJS`
      ;; relies on the leading `:` to recognise keywords; without this
      ;; fixup a literal `:headers` bound to a `[:list :keyword]` slot
      ;; would mismatch as `text ⊄ keyword` and render the red ring.
      (let [display-value (truncate-label (pr-str value) 20)
            wire-value (if (keyword? value) (str value) value)
            id-fields (arg-row->node-id-fields arg)]
        (swap! state update :nodes conj
               {:data (merge {:id node-id
                              :label display-value
                              :type "arg"
                              :argId (str arg-id)
                              :value wire-value}
                             id-fields)}))
      (swap! state update :edges conj
             {:data (merge {:id edge-id
                            :source source-node-id
                            :target node-id
                            :sourceArgId arg-id
                            :argName (or (compute-edge-label lookups arg-id source-node-id expanded-fns)
                                         (when arg-name (name arg-name)))}
                           (edge-source-fields lookups arg-id)
                           (edge-narrowing-fields lookups arg-id expanded-fns))}))
    node-id))


(defn- make-parent-bound-terminals
  "Memoised `(fn [fn-id]) → #{slot-id …}`. Returns the set of slot-ids
   that `fn-id`'s parent-id closure (including itself) binds. A slot
   counts as bound when some fn in the closure has a binding row with
   `:value`, `:ref-fn-id`, `:terminal true`, or non-empty list-items.

   Bindings inside ref-id targets are NOT included — those are scoped
   to the ref's own call context, not to the inheritance chain."
  [lookups]
  (let [{:keys [fn-map bindings-by-fn items-by-binding]} lookups
        cache (atom {})]
    (fn [fn-id]
      (or (get @cache fn-id)
          (let [visited (atom #{})
                slots (atom #{})
                walk (fn walk
                       [fid]
                       (when (and fid (not (contains? @visited fid)))
                         (swap! visited conj fid)
                         (doseq [b (get bindings-by-fn fid [])]
                           (when (or (some? (:value b))
                                     (some? (:ref-fn-id b))
                                     (true? (:terminal b))
                                     (seq (get items-by-binding (:id b) [])))
                             (swap! slots conj (:slot-id b))))
                         (when-let [f (get fn-map fid)]
                           (doseq [pid (:parent-ids f)]
                             (walk pid)))))]
            (walk fn-id)
            (let [result @slots]
              (swap! cache assoc fn-id result)
              result))))))


(defn- arg-determined?
  "True iff arg's slot is bound somewhere in its owning fn's
   parent-id closure. With slots as terminal identity the legacy
   source-chain walk collapses to a single closure-membership check
   on the arg's `:fn-id`. MI is handled inside parent-bound-terminals
   (it walks every parent path); ref-target bindings are excluded
   there too (the walk only follows :parent-ids, not :ref-id)."
  [arg-map parent-bound-terminals arg-id]
  (when-let [arg (get arg-map arg-id)]
    (when-let [fid (:fn-id arg)]
      (boolean (contains? (parent-bound-terminals fid) (:slot-id arg))))))


(defn- collect-fn-args
  "Collect renderable arg entries for `fn-id` given the active
   `bindings` map. Each entry is `{:type :ref|:value|:unset :arg-name
   :arg-id …}`. Pure — no state mutation.

   Options:
     :is-structural          — true for structural nodes inside an
                                expansion. Unbound refs (no binding)
                                become `:unset` instead of `:ref` so
                                we don't false-share with sibling fns.
     :displayed-ref-arg-ids  — set of arg-ids belonging to fns shown
                                as nodes in the current expansion.
                                Bindings whose source-chain leads
                                here are deferred to the leaf node.
     :expansion-root-chain   — set of fn-ids in the expansion root's
                                inheritance chain. Forwarded into
                                `child-covered-sources-for-fn`."
  [lookups fn-id bindings & {:keys [is-structural displayed-ref-arg-ids expansion-root-chain]
                             :or {is-structural false displayed-ref-arg-ids #{} expansion-root-chain #{}}}]
  (let [{:keys [fn-map arg-map args-by-fn]} lookups
        fn-ancestry (set (get-inheritance-chain fn-id fn-map))
        ;; A binding "applies" when its owning fn is in `fn-id`'s
        ;; inheritance closure AND it targets the same slot the arg
        ;; under inspection lives on. With slots as terminal identity
        ;; the old "walk source-id chain to see if it reaches target"
        ;; collapses to a slot-id equality check.
        ;; Bindings is keyed by slot-id; the entry already carries the
        ;; binding's owner :fn-id. "Applies" reduces to: owner-fn is in
        ;; fn-id's inheritance closure (slot-id match is implicit since
        ;; we look up by slot-id).
        binding-applies? (fn [b]
                           (or (nil? b)
                               (contains? fn-ancestry (:fn-id b))))
        bound-by-chain? (fn [arg]
                          (contains? bindings (:slot-id arg)))
        raw-args (get args-by-fn fn-id [])
        sequence-anchors (filterv sequence-anchor? raw-args)
        chain-item-ids (into #{}
                             (mapcat (fn [anchor]
                                       (map :id (walk-anchor-chain anchor arg-map))))
                             sequence-anchors)
        anchor-ids (set (map :id sequence-anchors))
        sequence-slot-entries
        (vec (mapcat
               (fn [anchor]
                 (expand-sequence-anchor
                   anchor
                   (or (resolve-arg-name anchor arg-map) "items")
                   arg-map))
               sequence-anchors))
        args (filterv (fn [a]
                        (not (or (contains? anchor-ids (:id a))
                                 (contains? chain-item-ids (:id a)))))
                      raw-args)
        child-sources (child-covered-sources-for-fn lookups fn-id :expansion-root-chain expansion-root-chain)
        all-covered-sources (into child-sources displayed-ref-arg-ids)
        ;; child-covered-sources-for-fn now returns slot-ids; the
        ;; binding's lookup is one direct slot-id check on the arg
        ;; the binding is anchored at.
        binding-goes-to-child?
        (fn [binding-key]
          (when-let [arg (get arg-map binding-key)]
            (contains? all-covered-sources (:slot-id arg))))
        all-args (mapv (fn [arg]
                         (let [arg-name (resolve-arg-name arg arg-map)
                               has-value (some? (:value arg))
                               has-ref (some? (:ref-id arg))
                               source-has-ref (when-let [sid (:source-id arg)]
                                                (let [source-arg (get arg-map sid)]
                                                  (some? (:ref-id source-arg))))
                               defines-own-ref (and has-ref (not source-has-ref))
                               binding-key (or (:id arg) (:source-id arg))
                               ;; bindings is slot-id-keyed; one direct
                               ;; lookup replaces the source-id chain
                               ;; walk and the per-step `binding-applies?`
                               ;; check is just the closure-membership
                               ;; test (slot equality is implicit).
                               raw-binding (let [b (get bindings (:slot-id arg))]
                                             (when (binding-applies? b) b))
                               binding (when (and raw-binding
                                                  (not (binding-goes-to-child? binding-key)))
                                         raw-binding)]
                           (cond
                             (or (and has-ref defines-own-ref)
                                 (and binding (:ref-id binding) (= (:ref-id binding) (:ref-id arg))))
                             (merge {:type :ref :arg-name arg-name
                                     :ref-id (:ref-id arg) :arg-id (:id arg)
                                     :is-binding false}
                                    (arg-ids-from arg))

                             (and binding (:ref-id binding)
                                  (or (and has-ref (not= (:ref-id binding) (:ref-id arg)))
                                      (and (not has-ref) (not has-value))))
                             (merge {:type :ref :arg-name (:arg-name binding)
                                     :ref-id (:ref-id binding) :arg-id (:arg-id binding)
                                     :is-binding true}
                                    (arg-ids-from binding))

                             (and binding (some? (:value binding)))
                             (merge {:type :value :arg-name (:arg-name binding)
                                     :value (:value binding) :arg-id (:arg-id binding)}
                                    (arg-ids-from binding))

                             (and has-ref (not is-structural))
                             (merge {:type :ref :arg-name arg-name
                                     :ref-id (:ref-id arg) :arg-id (:id arg)
                                     :is-binding false}
                                    (arg-ids-from arg))

                             (and has-ref is-structural (not defines-own-ref))
                             (merge {:type :unset :arg-name arg-name
                                     :arg-type (:type arg) :arg-id (:id arg)}
                                    (arg-ids-from arg))

                             has-value
                             (merge {:type :value :arg-name arg-name
                                     :value (:value arg) :arg-id (:id arg)}
                                    (arg-ids-from arg))

                             (or raw-binding (bound-by-chain? arg))
                             nil

                             :else
                             (merge {:type :unset :arg-name arg-name
                                     :arg-type (:type arg) :arg-id (:id arg)}
                                    (arg-ids-from arg)))))
                       args)
        own-slot-terminals (into #{}
                                 (keep (fn [a]
                                         (terminal-source-of arg-map (:id a))))
                                 raw-args)
        inherited-ref-args
        (if-not is-structural
          []
          (let [ancestors (rest (get-inheritance-chain fn-id fn-map))
                seen-terminals (atom own-slot-terminals)]
            (vec
              (keep
                (fn [a]
                  (when (:ref-id a)
                    (let [terminal-id (terminal-source-of arg-map (:id a))]
                      (when-not (contains? @seen-terminals terminal-id)
                        (swap! seen-terminals conj terminal-id)
                        (merge {:type :ref
                                :arg-name (resolve-arg-name a arg-map)
                                :ref-id (:ref-id a)
                                :arg-id (:id a)
                                :is-binding false}
                               (arg-ids-from a))))))
                (mapcat #(get args-by-fn % []) ancestors)))))
        deduped-args
        (let [seen (atom #{})]
          (into []
                (keep (fn [arg]
                        (let [terminal-id (terminal-source-of arg-map (:arg-id arg))]
                          (when-not (and terminal-id (contains? @seen terminal-id))
                            (when terminal-id (swap! seen conj terminal-id))
                            arg))))
                (into (filterv some? all-args) inherited-ref-args)))
        type-order {:ref 0 :value 1 :unset 2}
        sorted-args (sort-by #(get type-order (:type %) 3) deduped-args)]
    (into (vec sorted-args) sequence-slot-entries)))


(defn- collect-expanded-args
  "Collect rendered arg entries for an EXPANDED group of fns. `levels` is
   a vector of BFS levels (each a coll of fn-ids), `expand-set` selects
   which fns within `levels` participate in this expansion. Walks
   descendant-first, dedups slots covered by closer fns, and finally
   collapses MI shadows by (terminal-primary, ref-or-value).

   Pure — no state mutation. Closures are over `lookups` plus top-level
   helpers (`terminal-source-of`, `walk-anchor-chain`, `resolve-arg-name`,
   `expand-sequence-anchor`)."
  [lookups levels expand-set bindings]
  (let [{:keys [arg-map args-by-fn]} lookups
        active-fns (filterv expand-set (mapcat identity levels))
        ;; Slot-id-keyed dedup: once a slot has been emitted at the
        ;; closest active fn, deeper ancestors' views of that same
        ;; slot are skipped. Replaces the per-step source-chain walk.
        covered-slots (atom #{})
        result (atom [])
        chain-level (atom 0)
        bound-slot-terminals
        (reduce
          (fn [acc fn-id]
            (reduce
              (fn [acc2 arg]
                (if (or (some? (:value arg)) (some? (:ref-id arg))
                        (true? (:terminal? arg))
                        ;; Sequence binding — the anchor arg row carries
                        ;; no :value (items live in chained arg rows),
                        ;; so without this branch a slot bound to e.g.
                        ;; `[:headers]` reads as still-unset and an
                        ;; expanded ancestor emits a phantom unset
                        ;; placeholder for the same slot.
                        (and (sequence-anchor? arg)
                             (some? (:next-arg-id arg))))
                  (conj acc2 (:slot-id arg))
                  acc2))
              acc
              (get args-by-fn fn-id [])))
          #{}
          active-fns)]
    (doseq [fn-id active-fns]
      (let [raw-args (get args-by-fn fn-id [])
            anchor-ids (into #{}
                             (comp (filter sequence-anchor?)
                                   (map :id))
                             raw-args)
            chain-ids (into #{}
                            (mapcat (fn [a]
                                      (when (sequence-anchor? a)
                                        (map :id (walk-anchor-chain a arg-map)))))
                            raw-args)
            args (filterv (fn [a]
                            (not (or (contains? anchor-ids (:id a))
                                     (contains? chain-ids (:id a)))))
                          raw-args)
            current-level @chain-level
            fn-refs (atom [])
            fn-values (atom [])
            fn-unsets (atom [])]
        (doseq [arg args]
          (let [arg-id (:id arg)
                slot-id (:slot-id arg)
                already-covered (contains? @covered-slots slot-id)
                has-value (some? (:value arg))
                has-ref (some? (:ref-id arg))
                shadow-of-bound
                (and (not has-value) (not has-ref)
                     (contains? bound-slot-terminals slot-id))]
            (when (and (not already-covered) (not shadow-of-bound))
              (swap! covered-slots conj slot-id)
              (let [arg-name (resolve-arg-name arg arg-map)
                    from-ancestor (pos? current-level)]
                (let [ids (arg-ids-from arg)]
                  (cond
                    has-ref
                    (swap! fn-refs conj (merge {:type :ref :arg-name arg-name
                                                :ref-id (:ref-id arg) :arg-id arg-id
                                                :from-ancestor from-ancestor}
                                               ids))

                    has-value
                    (swap! fn-values conj (merge {:type :value :arg-name arg-name
                                                  :value (:value arg) :arg-id arg-id
                                                  :from-ancestor from-ancestor}
                                                 ids))

                    :else
                    (swap! fn-unsets conj (merge {:type :unset :arg-name arg-name
                                                  :arg-type (:type arg) :arg-id arg-id
                                                  :from-ancestor from-ancestor}
                                                 ids))))))))
        (let [raw-args-of-fn (get args-by-fn fn-id [])
              anchors (filter sequence-anchor? raw-args-of-fn)
              from-ancestor (pos? current-level)]
          ;; Dedup anchors by slot-id too. Without this an inherited
          ;; slot that's already bound by a closer fn (sequence items
          ;; chained off the descendant's anchor) re-renders here from
          ;; the parent's EMPTY anchor — which `expand-sequence-anchor`
          ;; surfaces as a phantom `:unset` placeholder. The user sees
          ;; "two `path` rows" after expanding the parent. Mirrors the
          ;; covered-slots gate the scalar arg loop above uses.
          (doseq [anchor anchors
                  :when (not (contains? @covered-slots (:slot-id anchor)))
                  :let [slot-name (or (resolve-arg-name anchor arg-map) "items")]
                  entry (expand-sequence-anchor anchor slot-name arg-map)]
            (swap! covered-slots conj (:slot-id anchor))
            (swap! result conj (assoc entry :from-ancestor from-ancestor))))
        (doseq [a @fn-refs] (swap! result conj a))
        (doseq [a @fn-values] (swap! result conj a))
        (doseq [a @fn-unsets] (swap! result conj a))
        (swap! chain-level inc)))
    (let [seen (atom #{})
          ;; Slot-id is the terminal identity: dedup keys can use it
          ;; directly instead of walking arg's source chain to find
          ;; the defining anchor.
          slot-id-of (fn [aid]
                       (or (:slot-id (get arg-map aid)) aid))]
      (into []
            (keep (fn [arg]
                    (let [t (slot-id-of (:arg-id arg))
                          k (case (:type arg)
                              :ref [t :ref (:ref-id arg)]
                              :value [t :value (:value arg)]
                              :unset [t :unset]
                              [t (:type arg)])]
                      (when-not (contains? @seen k)
                        (swap! seen conj k)
                        arg))))
            @result))))


(defn- child-covered-sources-for-fn
  "Slot-ids that `fn-id`'s child refs already render — used for
   binding deduplication so the same upstream binding isn't drawn
   twice (once on `fn-id` and once on the child node it feeds).

   For each child-ref of `fn-id`, walks the ref-target's inheritance
   closure and collects every slot in its fn-slots junctions (own +
   inherited). `expansion-root-chain` slots are excluded — those are
   shared-ancestor slots, rendered at the parent regardless."
  [lookups fn-id & {:keys [expansion-root-chain] :or {expansion-root-chain #{}}}]
  (let [{:keys [fn-map args-by-fn fn-slots-by-fn]} lookups
        fn-args (get args-by-fn fn-id [])
        child-ref-ids (keep :ref-id fn-args)
        expansion-chain-slot-ids (when (seq expansion-root-chain)
                                   (set (mapcat (fn [eid]
                                                  (map :slot-id
                                                       (get fn-slots-by-fn eid [])))
                                                expansion-root-chain)))
        slot-ids-of-fn-closure
        (fn [root-fn-id]
          (->> (get-inheritance-chain root-fn-id fn-map)
               (mapcat (fn [fid] (map :slot-id (get fn-slots-by-fn fid []))))
               (remove (fn [sid]
                         (and expansion-chain-slot-ids
                              (contains? expansion-chain-slot-ids sid))))))]
    (set (mapcat slot-ids-of-fn-closure child-ref-ids))))


(defn- arg-marks-hof?
  "Does `arg-entity` propagate a fn-typed marker anywhere in its
   source-id chain? Used to decide whether a ref-binding to another
   fn crosses a HOF boundary. After #15b, the HOF marker is `:type
   :fn` (the legacy `:is-fn` flag was retired)."
  [_arg-map arg-entity]
  ;; Anchor rows carry the slot's effective type directly (slot's
  ;; `:type-fn-id` overlaid by the binding's `:type-override-fn-id`),
  ;; so the legacy walk-to-root step is redundant — every row in the
  ;; same slot's chain reports the same `:type`.
  (= :fn (:type arg-entity)))


(defn- child-hof
  "HOF context to thread into a child render: ORs the parent's `is-hof`
   with whether `arg-id` crosses the HOF boundary (any source-id chain
   step has `:is-fn=true`). Once HOF, descendants stay HOF."
  [arg-map arg-id is-hof]
  (or is-hof (arg-marks-hof? arg-map (get arg-map arg-id))))


(defn- terminal-source-of
  "Stable identity for `arg-id`'s slot-inheritance lineage. In the
   slot/binding model the slot itself is the terminal identity, so
   we just return `:slot-id` of the row. Falls back to `arg-id`
   when the row isn't in `arg-map` (callers occasionally pass a node
   id that never came from a real arg row — e.g. expansion roots)."
  [arg-map arg-id]
  (or (:slot-id (get arg-map arg-id)) arg-id))


(defn- add-ref-edge!
  "Emit a ref-style edge from `source-node-id` to `node-id` if not
   already present. Dedup by edge-id (no double draw of the same
   source→target line) AND by `(source-node-id + source-arg-id →
   node-id)` (the same caller-arg can produce multiple synthetic edges
   through different MI paths; `processed-arg-targets` collapses them
   to one). Carries `:sourceArgId` so the post-process edge-migration
   pass can rewrite `:source` for cross-HOF captures."
  [state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns]
  (when (and source-node-id edge-arg-name)
    (let [edge-id (str "e-ref-" source-node-id "-" node-id)
          arg-target-key (when source-arg-id
                           (str source-node-id "-" source-arg-id "->" node-id))
          is-duplicate (and arg-target-key
                            (contains? (:processed-arg-targets @state) arg-target-key))]
      (when (and (not (contains? (:added-node-ids @state) edge-id))
                 (not is-duplicate))
        (swap! state update :added-node-ids conj edge-id)
        (when arg-target-key
          (swap! state update :processed-arg-targets conj arg-target-key))
        (swap! state update :edges conj
               {:data (merge {:id edge-id
                              :source source-node-id
                              :target node-id
                              :sourceArgId source-arg-id
                              :argName (or (compute-edge-label lookups source-arg-id source-node-id source-expanded-fns)
                                           (when edge-arg-name (name edge-arg-name)))}
                             (edge-source-fields lookups source-arg-id)
                             (edge-narrowing-fields lookups source-arg-id source-expanded-fns))})))))


(def ^:private max-visible-ancestors
  "Cap on the number of ancestor BFS levels rendered in a fn-node's
   header label before we collapse the rest into `…`."
  4)


(declare add-fn-node)


(defn- type-row-role
  "Classify a fn-row into one of the type roles. Mirrors
   `executor.compile-runtime/type-row-role` so the layout can decide
   whether to surface internal-structure edges for a type. Returns
   `:base-fn` / `:composed` / `:refinement` / `:list` / `:union` /
   `:variant` / `:fn-type` / `:record` / `:primitive`."
  [fn-row has-slots?]
  (let [c (:constraint fn-row)]
    (cond
      (seq (:parent-ids fn-row))      :composed
      (some? (:impl-hash fn-row))     :base-fn
      (some? (:base-fn-id fn-row))    :refinement
      (some? (:element-fn-id fn-row)) :list
      (and (vector? c) (= :union (first c)))   :union
      (and (vector? c) (= :variant (first c))) :variant
      (and (vector? c) (= :fn (first c)))      :fn-type
      has-slots?                      :record
      :else                           :primitive)))


(defn- resolve-type-ref
  "Resolve a type-form reference (a `:constraint`-vector element) to a
   fn-id. Named primitives / aliases resolve via `fn-by-name`; nested
   vectors are anonymous and skipped — they'll surface inline at a
   later iteration."
  [fn-by-name form]
  (when (keyword? form)
    (:id (get fn-by-name form))))


(defn- emit-type-row-internal-edge!
  "Append a single synthetic edge from a type-row root to one of its
   constituent type targets. `target-fn-id` is added as a fn-node so
   it renders as its own card."
  [state lookups source-node-id target-fn-id edge-id arg-name]
  (when (and target-fn-id
             (not (contains? (:added-node-ids @state) edge-id)))
    (let [target-id (add-fn-node state lookups target-fn-id false nil nil)]
      (swap! state update :added-node-ids conj edge-id)
      (swap! state update :edges conj
             {:data {:id edge-id
                     :source source-node-id
                     :target target-id
                     :argName arg-name
                     :isTypeInternal true}}))))


(defn- emit-type-row-internals!
  "Surface a root-level type-row's internal composition as outgoing
   edges. The target nodes are the real fn-rows of the referenced
   types (rendered like any other fn-card). Roles:
     :refinement → edge to base-fn (`base`)
     :list       → edge to element-fn (`element`)
     :union      → one unlabelled edge per branch
     :variant    → one edge per branch, labelled with the tag
   :record, :base-fn, :composed, :primitive, :fn-type — no synthetic
   edges (records render via their own fn-slots; primitives are
   leaves; composed/base-fns flow through the normal pipeline)."
  [state lookups root-fn-id node-id]
  (let [{:keys [fn-map fn-by-name fn-slots-by-fn]} lookups
        f (get fn-map root-fn-id)
        has-slots? (boolean (seq (get fn-slots-by-fn root-fn-id)))
        role (when f (type-row-role f has-slots?))
        edge-id (fn [suffix] (str "e-type-" node-id "-" suffix))]
    (case role
      :refinement
      (emit-type-row-internal-edge!
        state lookups node-id (:base-fn-id f) (edge-id "base") "base")

      :list
      (emit-type-row-internal-edge!
        state lookups node-id (:element-fn-id f) (edge-id "element") "element")

      :union
      (doseq [[idx form] (map-indexed vector (rest (:constraint f)))]
        (emit-type-row-internal-edge!
          state lookups node-id (resolve-type-ref fn-by-name form)
          (edge-id (str "union-" idx)) ""))

      :variant
      (doseq [[idx [tag form]] (map-indexed vector
                                            (partition 2 (rest (:constraint f))))]
        (emit-type-row-internal-edge!
          state lookups node-id (resolve-type-ref fn-by-name form)
          (edge-id (str "variant-" idx))
          (str tag)))

      nil)))


(defn- add-fn-node
  "Emit (or reuse) a cytoscape fn-node for `original-fn-id`. The node-id
   uniquely identifies the call-site: root fns key by `\"fn-<id>\"`; nested
   fns key by `(caller-node-id, source-arg-id)` so two usages of the
   same fn from different bindings are distinct nodes (matches Clojure
   call-site semantics). Returns the resolved node-id."
  [state lookups original-fn-id is-root source-node-id source-arg-id]
  (let [{:keys [fn-map]} lookups
        node-id (cond
                  (or is-root (nil? source-arg-id))
                  (str "fn-" original-fn-id)

                  ;; Strip "fn-" from caller-node-id so we don't keep
                  ;; doubling the prefix at each nesting.
                  :else
                  (let [caller-tag (if (and source-node-id
                                            (str/starts-with? source-node-id "fn-"))
                                     (subs source-node-id 3)
                                     (str source-node-id))]
                    (str "fn-" caller-tag "-" source-arg-id)))]
    (when-not (contains? (:added-node-ids @state) node-id)
      (swap! state update :added-node-ids conj node-id)
      (let [levels (get-inheritance-levels original-fn-id fn-map)
            ;; Name of a fn for label rendering. For level 0 (`top-level?`)
            ;; we do NOT substitute the nearest named ancestor: an anonymous
            ;; fn's "name slot" stays empty so the black header bar visually
            ;; signals "no own name". For levels ≥ 1 we DO substitute so each
            ;; ancestor row still shows something meaningful.
            fn-name-of (fn [fid top-level?]
                         (let [f (get fn-map fid)]
                           (or (when (:name f) (name (:name f)))
                               (when-not top-level?
                                 (some (fn [pid]
                                         (when-let [p (get fn-map pid)]
                                           (when (:name p) (name (:name p)))))
                                       (rest (get-inheritance-chain fid fn-map))))
                               (if top-level? "" "(anonymous)"))))
            visible-levels (take (inc max-visible-ancestors) levels)
            raw-lines (vec
                        (map-indexed
                          (fn [lvl-idx level-fn-ids]
                            (str/join ", "
                                      (map #(fn-name-of % (zero? lvl-idx))
                                           level-fn-ids)))
                          visible-levels))
            label-lines (if (> (count levels) (inc max-visible-ancestors))
                          (conj raw-lines "...")
                          raw-lines)
            label (str/join "\n" label-lines)]
        (swap! state update :nodes conj
               {:data {:id node-id
                       :label label
                       :type "fn"
                       :isRoot is-root
                       :originalFnId (str original-fn-id)}})))
    node-id))


(defn- add-unset-arg-node
  "Emit a placeholder for an unset arg, choosing one of four routings:

     1. Optional (root `:required=false`) — compact `?name` badge via
        `:optionalArgs`; the caller's fn has a sensible fallback baked in.
     2. Lambda-param of an enclosing HOF (`is-hof=true` AND no caller-side
        structural binding via cross-HOF source-id chain) — compact
        `λname` badge via `:hofCapturedArgs`. The HOF impl supplies it
        per call.
     3. HOF capture — `is-hof=true` but a caller's chain DOES bind this
        arg structurally (cross-HOF source-id). The binding is rendered
        on the capturing caller's edge; we record a migration target so
        post-processing rewrites the edge to originate from THIS inside-
        consumer node (the leaf that actually reads the value). Nothing
        new is emitted in the lambda body to avoid double-counting.
     4. Otherwise — a visible dashed placeholder node. This IS the
        caller's interface; the caller must fill it.

   Recursive 4-arity exists for back-compat with call-sites that don't
   know whether the slot is inside a HOF subtree (defaults to false)."
  ([state lookups inverse-source-map arg-name arg-type arg-id source-node-id expanded-fns]
   (add-unset-arg-node state lookups inverse-source-map
                       arg-name arg-type arg-id source-node-id expanded-fns false))
  ([state lookups inverse-source-map arg-name arg-type arg-id source-node-id expanded-fns is-hof]
   (let [arg-map (:arg-map lookups)
         arg-rec (get arg-map arg-id)
         optional? (arg-is-optional? arg-map arg-rec)
         displayed-name (or (compute-edge-label lookups arg-id source-node-id expanded-fns)
                            (when arg-name (name arg-name)))]
     (cond
       ;; Terminal seal at this fn — slot is consumed, render nothing.
       (true? (:terminal? arg-rec))
       nil

       optional?
       (record-optional-unset! state source-node-id displayed-name)

       is-hof
       (if-let [bound (caller-bound-arg arg-map inverse-source-map arg-id)]
         (swap! state update :captured-edge-migrations assoc (:id bound) source-node-id)
         (record-hof-captured! state source-node-id displayed-name))

       :else
       (let [node-id (str "unset-" source-node-id "-" arg-id)
             edge-id (str "e-unset-" source-node-id "-" arg-id)
             ;; Empty sequence anchor: the arg itself is :sequence-typed
             ;; and the chain head is nil. Mark the node so the frontend
             ;; routes the click into `appendSequenceItem` (Phase 5)
             ;; instead of the regular free-arg binder, which would try
             ;; to PUT `value=` on the anchor.
             empty-seq? (and arg-rec
                             (= :sequence (:type arg-rec))
                             (nil? (:next-arg-id arg-rec)))]
         (when-not (contains? (:added-node-ids @state) node-id)
           (swap! state update :added-node-ids conj node-id)
           (swap! state update :nodes conj
                  {:data (cond-> (merge {:id node-id
                                         :label (if arg-type (name arg-type) "any")
                                         :type "fn"
                                         :isPlaceholder true
                                         ;; argId / argType let the frontend
                                         ;; offer in-place binding of free-arg
                                         ;; slots (Phase 4) without re-deriving
                                         ;; them from the node-id string.
                                         :argId (str arg-id)}
                                        (when arg-rec
                                          (arg-row->node-id-fields arg-rec)))
                           arg-type  (assoc :argType (name arg-type))
                           empty-seq? (assoc :isSequenceAnchor true
                                             :sequenceFnId (str (:fn-id arg-rec))))})
           (swap! state update :edges conj
                  {:data (merge {:id edge-id
                                 :source source-node-id
                                 :target node-id
                                 :sourceArgId arg-id
                                 :argName displayed-name
                                 :isUnset true}
                                ;; Same id-bundle as a bound-arg edge so the
                                ;; edge-label overlay can resolve the slot /
                                ;; fn / type via `argRowFromNode` and render
                                ;; the type-chip on this edge (the placeholder
                                ;; no longer carries a type label of its own).
                                (edge-source-fields lookups arg-id)
                                (edge-narrowing-fields lookups arg-id expanded-fns))})))))))


(defn- target-interface-names
  "Distinct external names of `target-fn-id`'s named free slots. E.g.
   `merge-in` declares `:value {:as :defaults}`, so its interface is
   `[\"defaults\"]`. Used to enrich edge labels when the source-side
   label is uninformative — most notably sequence chain items whose
   own arg has `:source-id=nil` and no name, so the source-chain walk
   yields nil and the edge falls back to the synthetic `maps[0]`-style
   index."
  [lookups target-fn-id]
  (when target-fn-id
    (->> (get (:args-by-fn lookups) target-fn-id [])
         (keep (fn [a]
                 (when (and (:name a)
                            (nil? (:value a))
                            (nil? (:ref-id a))
                            (:source-id a))
                   (:name a))))
         (distinct)
         (vec))))


(defn- build-inverse-source-map
  "Reverse source-id index: arg-id → vector of args whose `:source-id`
   points directly here. Used to walk DOWNWARD from a HOF-target's free
   arg to find caller-side bindings via structural source-id chains
   (cross-HOF)."
  [arg-map]
  (reduce (fn [m a]
            (if-let [sid (:source-id a)]
              (update m sid (fnil conj []) a)
              m))
          {}
          (vals arg-map)))


(defn- caller-bound-arg
  "Walks the cross-HOF inheritance lineage of `target-arg-id` and
   returns the first arg in that lineage with `:value` or `:ref-id`
   set — i.e., the caller-side binding that fills the inner unset
   slot. Returns nil when no caller has supplied a value.

   Algorithm:
     1. Walk UP `target-arg-id`'s `:source-id` chain to the terminal
        anchor (`source-id=nil`). Every member of the chain shares a
        slot identity.
     2. BFS DOWN `inverse-source-map` starting at the terminal — that
        traverses every arg in any descendant fn that views the same
        slot.
     3. Return the first hit with a binding.

   Used both to classify HOF captures (λ-badge vs migration) and to
   record the migration target so post-processing rewrites the edge
   to originate at the inner consumer node."
  [arg-map inverse-source-map target-arg-id]
  (let [terminal (loop [aid target-arg-id]
                   (if-let [parent (some-> aid arg-map :source-id)]
                     (recur parent)
                     aid))]
    (loop [queue [terminal]
           visited #{}]
      (when-not (empty? queue)
        (let [cur (peek queue)
              rest-q (pop queue)]
          (if (contains? visited cur)
            (recur rest-q visited)
            (let [children (get inverse-source-map cur [])
                  bound (some (fn [a]
                                (when (or (some? (:value a))
                                          (some? (:ref-id a)))
                                  a))
                              children)]
              (or bound
                  (recur (into rest-q (map :id) children)
                         (conj visited cur))))))))))


(defn- edge-narrowing-fields
  "Optional `:typeChain` edge-data field. Returns `{}` when the chain
   is uninteresting (no narrowing visible at the current expansion),
   so callers can `(merge … (edge-narrowing-fields …))` unconditionally."
  [lookups arg-id expanded-fns]
  (if-let [chain (compute-edge-type-chain lookups arg-id expanded-fns)]
    {:typeChain chain}
    {}))


(defn- compute-edge-type-chain
  "Walk the same source-chain `compute-edge-label` uses, group adjacent
   anchor rows by their effective `:type`, and surface the groups iff
   the user's expansion crosses a type-narrowing boundary (≥ 2 distinct
   groups visible AND those groups span more than one fn). Each group:
   `{:type kw :fns [fn-name …]}`, leaf first. nil when no narrowing is
   visible — the edge then keeps its default single-chip rendering.

   The extra cross-fn gate is what keeps a sequence-item edge clean:
   inside one fn, the source-chain traverses item → anchor (e.g. `any`
   → `sequence`), but that's a container/element relation, not an
   inheritance narrowing. Without the gate the edge would carry a
   bogus '↑ sequence (router-ring-response)' row that says nothing
   about ancestors."
  [lookups arg-id expanded-fns]
  (let [{:keys [fn-map arg-map]} lookups]
    (when arg-id
      (let [source-chain (loop [acc [], cur (get arg-map arg-id)]
                           (if cur
                             (recur (conj acc cur)
                                    (some-> (:source-id cur) arg-map))
                             acc))
            visible (filter #(contains? expanded-fns (:fn-id %)) source-chain)
            labeled (mapv (fn [arg]
                            {:fn   (some-> (:fn-id arg) fn-map :name name)
                             :type (some-> (:type arg) name)})
                          visible)
            groups (->> labeled
                        (partition-by :type)
                        (mapv (fn [grp]
                                {:type (:type (first grp))
                                 :fns  (vec (keep :fn grp))})))
            distinct-fns (->> groups (mapcat :fns) distinct count)]
        (when (and (> (count groups) 1)
                   (> distinct-fns 1))
          groups)))))


(defn- compute-edge-label
  "Pick the most informative label for an edge sourced at `arg-id`,
   given the current set of `expanded-fns`. Walks the source-id chain
   and prefers names from fns the user actually expanded; falls back
   to the target fn's interface names so the user sees WHICH slot of
   the target this edge is feeding."
  [lookups arg-id source-node-id expanded-fns]
  (let [{:keys [fn-map arg-map]} lookups]
    (when arg-id
      (let [source-chain (loop [acc [], cur (get arg-map arg-id)]
                           (if cur
                             (recur (conj acc cur)
                                    (some-> (:source-id cur) arg-map))
                             acc))
            source-arg (get arg-map arg-id)
            ;; Keep only args whose fn-id is in the expanded set
            visible (filter #(contains? expanded-fns (:fn-id %)) source-chain)
            labeled (mapv (fn [arg]
                            {:fn (some-> (:fn-id arg) fn-map :name name)
                             :arg-name (resolve-arg-name arg arg-map)})
                          visible)
            groups (->> labeled
                        (partition-by :arg-name)
                        (mapv (fn [grp]
                                {:name (:arg-name (first grp))
                                 :fns (vec (keep :fn grp))})))
            source-label
            (cond
              (empty? groups) nil
              ;; Single arg name across every visible ancestor — no rename
              ;; along the chain, so no fn-name disambiguation is needed.
              (= 1 (count groups)) (:name (first groups))
              :else (->> groups
                         (map (fn [{:keys [name fns]}]
                                (if (seq fns)
                                  (str name " (" (str/join ", " fns) ")")
                                  name)))
                         (str/join "\n")))]
        (cond
          ;; Source-chain gave a non-blank label — keep it.
          (and source-label (not (str/blank? source-label))) source-label

          ;; No useful source-side name (typically a chain item with
          ;; source-id=nil). Fall back to the target's renamed free
          ;; args.
          :else
          (let [interface-names (target-interface-names lookups (:ref-id source-arg))]
            (when (seq interface-names)
              (str/join ", " interface-names))))))))


(defn- build-graph-elements
  "Build graph elements (nodes, edges) from selected function.
   Returns {:nodes [...] :edges [...]}"
  [root-fn-id expansions lookups]
  (let [{:keys [fn-map arg-map args-by-fn fn-slots-by-fn]} lookups
        ;; Mutable state collected during traversal lives in ONE atom keyed
        ;; by purpose. Helpers receive `state` via lexical closure and use
        ;; `(swap! state update :nodes conj …)` / `(:nodes @state)` to read.
        ;;
        ;; Keys:
        ;;   :nodes / :edges                  — accumulated cytoscape elements
        ;;   :added-node-ids                  — set of emitted node-ids (dedup)
        ;;   :processed-arg-targets           — arg-target keys already wired
        ;;   :processed-fn-nodes              — fn-process keys already done
        ;;                                       (cycle guard for shared-fn graphs)
        ;;   :in-progress-expansions          — expansion keys currently active
        ;;                                       (cycle guard for self-referential refs)
        ;;   :optional-unsets-by-node         — node-id → [arg-name …] for the
        ;;                                       compact `+default, +else` badge
        ;;   :hof-captured-by-node            — node-id → [arg-name …] for the
        ;;                                       λname HOF capture badge
        ;;   :captured-edge-migrations        — caller arg-id → inside consumer
        ;;                                       node-id, post-process rewrite of
        ;;                                       cross-HOF edges
        state (atom {:nodes []
                     :edges []
                     :added-node-ids #{}
                     :processed-arg-targets #{}
                     :processed-fn-nodes #{}
                     :in-progress-expansions #{}
                     :optional-unsets-by-node {}
                     :hof-captured-by-node {}
                     :captured-edge-migrations {}})

        inverse-source-map (build-inverse-source-map arg-map)
        parent-bound-terminals (make-parent-bound-terminals lookups)]

    ;; Track bindings for EACH expanded function
    ;; Key: expanded-fn-id, Value: {:refs #{ref-ids}, :values #{arg-ids}}
    ;; When processing refs from ancestors of an expanded fn, skip bindings that
    ;; were already shown at the expanded fn itself
    (let [expansion-bindings (atom {})]

      ;; Declare process-any-fn before using it
      ;; expansion-root: the original-fn-id of the expanded function we're inside (nil if not in expansion)
      (letfn [(process-fn
                [original-fn-id display-fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root source-expanded-fns is-hof]
                (let [node-id (add-fn-node state lookups original-fn-id is-root source-node-id source-arg-id)
                      ;; Key for tracking fully processed nodes - includes expansion context
                      process-key (str node-id "-" (hash bindings))]
                  (add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)

                  ;; Only process children if this node wasn't already fully processed
                  ;; This prevents infinite recursion when same fn is reached via different paths
                  (when-not (contains? (:processed-fn-nodes @state) process-key)
                    (swap! state update :processed-fn-nodes conj process-key)
                    ;; Process children
                    ;; When inside an expansion context (expansion-root is set),
                    ;; we WANT to show bindings - they should appear here, not at the root
                    ;; Mark as structural when inside expansion - prevents false refs to other fns
                    ;;
                    ;; Compute displayed-ref-arg-ids: arg-ids of fns that will be displayed as child nodes
                    ;; This is used to hide bindings that will appear on child nodes instead
                    (let [displayed-ref-arg-ids
                          (when (some? expansion-root)
                            ;; Slot-ids the displayed child refs already
                            ;; render — fed into collect-fn-args as
                            ;; `displayed-ref-arg-ids` (now slot-id-
                            ;; keyed alongside child-covered-sources-
                            ;; for-fn). Bindings whose slot is in this
                            ;; set are deferred to the leaf so we don't
                            ;; double-render.
                            (let [fn-args (get args-by-fn display-fn-id [])
                                  ref-fn-ids (set (concat
                                                    (keep :ref-id fn-args)
                                                    (keep (fn [arg]
                                                            (when-let [b (get bindings (:slot-id arg))]
                                                              (:ref-id b)))
                                                          fn-args)))
                                  expansion-chain-fns (set (get-inheritance-chain expansion-root fn-map))]
                              (set (mapcat (fn [ref-fn-id]
                                             (let [ref-chain (get-inheritance-chain ref-fn-id fn-map)]
                                               (mapcat (fn [rfn-id]
                                                         (when-not (contains? expansion-chain-fns rfn-id)
                                                           (map :slot-id (get fn-slots-by-fn rfn-id []))))
                                                       ref-chain)))
                                           ref-fn-ids))))
                          exp-root-chain (when expansion-root
                                           (set (get-inheritance-chain expansion-root fn-map)))
                          all-args (collect-fn-args lookups display-fn-id bindings
                                                    :is-structural (some? expansion-root)
                                                    :displayed-ref-arg-ids (or displayed-ref-arg-ids #{})
                                                    :expansion-root-chain (or exp-root-chain #{}))
                          ;; Filter out :unset args that are BOUND BY ANCESTORS.
                          ;; These aren't truly free — a parent in the inheritance chain
                          ;; already sets their value/ref. They should be hidden at level 0
                          ;; and only become visible when expanding to the ancestor level.
                          ;; We compute ancestor-bindings by walking all parents of the
                          ;; display-fn (excluding itself) and collecting their bindings.
                          ancestor-bindings
                          (when-not (some? expansion-root)
                            ;; Only filter in non-structural (level-0) mode.
                            ;; In structural mode the expanded chain already handles this.
                            (let [all-levels (get-inheritance-levels display-fn-id fn-map)
                                  ;; Ancestor-first reduce so descendants
                                  ;; overlay via assoc (slot-id keying).
                                  ancestor-fns (reverse (rest (mapcat identity all-levels)))]
                              (reduce
                                (fn [b fid] (add-bindings-from-fn fid b lookups))
                                {} ancestor-fns)))

                          ancestor-bound?
                          (fn [arg-id]
                            (let [arg (get arg-map arg-id)]
                              (or (and ancestor-bindings arg
                                       (contains? ancestor-bindings (:slot-id arg)))
                                  (arg-determined? arg-map parent-bound-terminals arg-id))))

                          ;; Loader synthesizes a `value` slot on every
                          ;; refinement and an `items` slot on every list
                          ;; type-row so a runtime-narrowing impl has
                          ;; somewhere to read its input from. At the
                          ;; refinement's / list's OWN page that slot is
                          ;; plumbing — the type's structure is already
                          ;; carried by the `base` / `element` edge
                          ;; emit-type-row-internals! emits. Composed
                          ;; children still see and bind it via slot
                          ;; inheritance, so hiding here doesn't lose
                          ;; functionality.
                          hidden-synth-slot
                          (when is-root
                            (let [f (get fn-map display-fn-id)
                                  has-slots? (boolean
                                               (seq (get fn-slots-by-fn display-fn-id)))]
                              (case (type-row-role f has-slots?)
                                :refinement "value"
                                :list       "items"
                                nil)))
                          synth-slot?
                          (fn [arg]
                            (and hidden-synth-slot
                                 (= hidden-synth-slot
                                    (:name (get-in lookups [:slot-map (:slot-id arg)])))))

                          filtered-args
                          (filterv (fn [arg]
                                     (and (not (synth-slot? arg))
                                          (if (= :unset (:type arg))
                                            (not (ancestor-bound? (:arg-id arg)))
                                            true)))
                                   all-args)]
                      (doseq [arg filtered-args]
                        (case (:type arg)
                          :ref (let [ref-expansion-root (when-not (:is-binding arg) expansion-root)
                                     ref-bindings bindings]
                                 (process-any-fn (:ref-id arg) node-id (:arg-name arg) false ref-bindings (:arg-id arg) ref-expansion-root #{display-fn-id} (child-hof arg-map (:arg-id arg) is-hof)))
                          :value (add-arg-value-node state lookups arg node-id #{display-fn-id})
                          :unset (add-unset-arg-node state lookups inverse-source-map (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id #{display-fn-id} is-hof)
                          nil))))
                  node-id))

              (process-expanded-fn
                [original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof]
                ;; parent-expansion-root: if we're nested inside another expansion, keep that context
                ;; Otherwise, this fn becomes its own expansion root
                ;;
                ;; spec is an expansion spec (integer N for full cascade, or
                ;; {:full-depth N :partial-fns #{...}} for cascade + per-fn).
                ;; The spec is converted to a set of fn-ids that are "merged in".
                ;;
                ;; Cycle protection: if we're already processing this expansion,
                ;; just add the node + edge and return without recursing further.
                ;; This handles cyclic refs like method-map.value → assoc-handler
                ;; whose binding chain leads back to method-map.
                (let [in-progress-key [original-fn-id parent-expansion-root]]
                  (if (contains? (:in-progress-expansions @state) in-progress-key)
                    (let [node-id (add-fn-node state lookups original-fn-id is-root source-node-id source-arg-id)]
                      (add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)
                      node-id)
                    (do
                      (swap! state update :in-progress-expansions conj in-progress-key)
                      (try
                        (process-expanded-fn-impl original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof)
                        (finally
                          (swap! state update :in-progress-expansions disj in-progress-key)))))))

              (process-expanded-fn-impl
                [original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof]
                (let [levels (get-inheritance-levels original-fn-id fn-map)
                      chain (vec (mapcat identity levels))  ; flat for set ops
                      expand-set (spec->expand-set fn-map original-fn-id spec)
                      ;; TWO binding maps:
                      ;; 1. display-bindings: from EXPAND-SET fns only. Used for
                      ;;    collect-expanded-args to render values/refs. Only shows
                      ;;    bindings from fns the user explicitly expanded.
                      ;; 2. all-bindings: from ALL ancestors. Used for filtering —
                      ;;    hides :unset args that are bound by non-expanded ancestors
                      ;;    (they're not truly free, just not yet visible).
                      display-bindings (reduce
                                         (fn [b fid] (add-bindings-from-fn fid b lookups))
                                         {} expand-set)
                      all-bindings (build-chain-bindings levels lookups)
                      ;; Merge order: parent FIRST, display WINS for collisions.
                      chain-bindings (merge parent-bindings display-bindings)
                      ;; Determine expansion root for this node and its children:
                      ;; - If we're already inside an expansion (parent-expansion-root set),
                      ;;   keep that context to avoid merging nodes from different expansions
                      ;; - If this is a top-level expansion (parent-expansion-root is nil),
                      ;;   this fn becomes the expansion root
                      effective-expansion-root (or parent-expansion-root original-fn-id)
                      node-id (add-fn-node state lookups original-fn-id is-root source-node-id source-arg-id)]
                  (add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)

                  ;; For expanded mode, collect args from entire chain [0..level]
                  ;;
                  ;; KEY INSIGHT: When expanding, bindings flow to ancestor refs.
                  ;; Level-0 refs that point to the SAME target as bindings in ancestor refs
                  ;; should NOT be shown at root - they'll appear at the ancestor ref.
                  ;;
                  ;; Example for delete-entity-route at level 2:
                  ;; - handler -> api-handler: assoc-handler has handler arg, binding flows there
                  ;; - key -> "delete": method-map has key arg, binding flows there
                  ;; - path -> "/api/...": NO ancestor ref uses path, show at root
                  ;;
                  ;; Strategy:
                  ;; 1. Collect all ref-ids that will be shown by ancestor refs
                  ;;    (by simulating what bindings they'll resolve)
                  ;; 2. Level-0 refs pointing to those targets are hidden at root
                  ;; 3. Level-0 refs pointing to OTHER targets are shown at root
                  (let [raw-args (collect-expanded-args lookups levels expand-set chain-bindings)
                        ;; Filter out :unset args whose terminal is bound by some
                        ;; fn in the source-chain owners' parent inheritance
                        ;; closure. Bindings in ref-id targets are NOT considered
                        ;; (they're scoped to the ref's call context).
                        all-args (filterv (fn [arg]
                                            (if (= :unset (:type arg))
                                              (not (arg-determined? arg-map parent-bound-terminals (:arg-id arg)))
                                              true))
                                          raw-args)
                        ;; Separate by type and origin
                        ancestor-refs (filter #(and (:from-ancestor %) (= (:type %) :ref)) all-args)
                        ancestor-values (filter #(and (:from-ancestor %) (= (:type %) :value)) all-args)
                        ancestor-unsets (filter #(and (:from-ancestor %) (= (:type %) :unset)) all-args)
                        level-0-args (remove :from-ancestor all-args)
                        level-0-refs (filter #(= (:type %) :ref) level-0-args)
                        level-0-values (filter #(= (:type %) :value) level-0-args)
                        level-0-unsets (filter #(= (:type %) :unset) level-0-args)

                        has-ancestor-refs (seq ancestor-refs)

                        ;; Caller-side bindings (level-0 refs/values) whose
                        ;; source-chain walks into an ancestor-ref's subtree
                        ;; don't belong on the caller — they fill a slot
                        ;; defined deeper. Per clojure inline semantics,
                        ;; expanding `(pgr ... :func X)` places `:func` on
                        ;; whichever descendant actually uses it.
                        ;;
                        ;; Map owner-fn-id → ancestor-ref-fn-id via each
                        ;; ancestor-ref's inheritance chain. Walking a
                        ;; binding's source-chain, first owner that matches
                        ;; tells us which leaf to migrate to.
                        fn-id->ancestor-ref-fn-id
                        (into {}
                              (mapcat (fn [ref]
                                        (let [chain (get-inheritance-chain (:ref-id ref) fn-map)]
                                          (map (fn [fid] [fid (:ref-id ref)]) chain))))
                              ancestor-refs)

                        ;; Walks `arg-id`'s owning fn-id closure
                        ;; checking each ancestor's match against
                        ;; the ancestor-ref index. Replaces the old
                        ;; per-step `:source-id` walk through synth
                        ;; anchors — semantics: visit each fn whose
                        ;; view of the slot the chain went through.
                        ;; Slot-owner first: a binding on a ref-chain-
                        ;; propagated slot (e.g. `_app-ring-response :args
                        ;; {:func :_router}` — `:func` is owned by `:invoke`,
                        ;; reached only via `:m → :router-result`) needs
                        ;; migration to the ancestor-ref whose chain
                        ;; INCLUDES that slot owner. Walking the binding's
                        ;; own fn-id chain finds nothing (the slot is in a
                        ;; different branch). Walk the slot-owner's chain.
                        migration-target-for
                        (fn [arg]
                          (when-let [a (get arg-map (:arg-id arg))]
                            (let [slot-owner (some-> (:slot-id a)
                                                     ((:slot-owner lookups)))]
                              (or (when slot-owner
                                    (some fn-id->ancestor-ref-fn-id
                                          (get-inheritance-chain slot-owner fn-map)))
                                  (some fn-id->ancestor-ref-fn-id
                                        (get-inheritance-chain (:fn-id a) fn-map))))))

                        ;; Partition level-0 refs/values: stay at caller or
                        ;; migrate to one of the ancestor-refs.
                        classified-level-0
                        (reduce
                          (fn [acc arg]
                            (if-let [target (migration-target-for arg)]
                              (update-in acc [:migrated target] (fnil conj []) arg)
                              (update acc :stay conj arg)))
                          {:stay [] :migrated {}}
                          (concat level-0-refs level-0-values))

                        level-0-stay (:stay classified-level-0)
                        migrated-by-ref (:migrated classified-level-0)

                        ;; For a migrated arg, build bindings keyed by
                        ;; slot-id so the target leaf's `find-migrated`
                        ;; lookup hits them. With slot-as-terminal we
                        ;; need exactly one entry per arg — no chain
                        ;; walk required.
                        migrated-bindings-for
                        (fn [args]
                          (reduce
                            (fn [b arg]
                              (if-let [sid (:slot-id (get arg-map (:arg-id arg)))]
                                (assoc b sid {:arg-name (:arg-name arg)
                                              :value (:value arg)
                                              :ref-id (:ref-id arg)
                                              :arg-id (:arg-id arg)
                                              :slot-id sid
                                              :fn-id (:fn-id (get arg-map (:arg-id arg)))})
                                b))
                            {} args))]

                    (swap! expansion-bindings assoc original-fn-id
                           {:has-ancestor-refs has-ancestor-refs})

                    (let [;; When this fn was reached as an ancestor-ref of an
                          ;; outer expansion, parent-bindings carries entries
                          ;; whose source-chain terminates at one of THIS fn's
                          ;; slots. For an unset slot, walk its source-chain
                          ;; through parent-bindings; first hit fills the slot
                          ;; (matches the leaf-path's `find-migrated`).
                          find-migrated
                          (fn [arg-id]
                            (when parent-bindings
                              (some->> arg-id
                                       (get arg-map)
                                       :slot-id
                                       (get parent-bindings))))
                          ;; Render an unset arg, consulting parent-bindings
                          ;; first for a migrated entry that fills the slot
                          ;; (renders as ref/value); falls back to the unset
                          ;; placeholder/λ-bейдж/optional badge otherwise.
                          render-unset
                          (fn [arg]
                            (let [m (find-migrated (:arg-id arg))]
                              (cond
                                (and m (:ref-id m))
                                (process-any-fn (:ref-id m) node-id
                                                (or (:arg-name m) (:arg-name arg))
                                                false parent-bindings (:arg-id arg)
                                                parent-expansion-root expand-set (child-hof arg-map (:arg-id arg) is-hof))
                                (and m (some? (:value m)))
                                (add-arg-value-node state lookups
                                                    (assoc m
                                                           :arg-id (:arg-id arg)
                                                           :arg-name (or (:arg-name m) (:arg-name arg)))
                                                    node-id expand-set)
                                :else
                                (add-unset-arg-node state lookups inverse-source-map
                                                    (:arg-name arg) (:arg-type arg)
                                                    (:arg-id arg) node-id expand-set is-hof))))]
                      (doseq [arg (filter #(= (:type %) :ref) level-0-stay)]
                        (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) parent-expansion-root expand-set (child-hof arg-map (:arg-id arg) is-hof)))

                      (doseq [arg level-0-unsets] (render-unset arg))

                      (doseq [arg (filter #(= (:type %) :value) level-0-stay)]
                        (add-arg-value-node state lookups arg node-id expand-set))

                      ;; Ancestor refs: pass ONLY their migrated bindings (if
                      ;; any) as the leaf's parent-bindings so it picks them
                      ;; up via find-migrated without seeing siblings'.
                      (doseq [arg ancestor-refs]
                        (let [ref-target-id (:ref-id arg)
                              migrated-to-this-ref (get migrated-by-ref ref-target-id [])
                              leaf-bindings (migrated-bindings-for migrated-to-this-ref)]
                          (process-any-fn ref-target-id node-id (:arg-name arg) false leaf-bindings (:arg-id arg) effective-expansion-root expand-set (child-hof arg-map (:arg-id arg) is-hof))))

                      (doseq [arg ancestor-unsets] (render-unset arg))

                      (doseq [arg ancestor-values]
                        (add-arg-value-node state lookups arg node-id expand-set))))

                  node-id))

              (process-any-fn
                [fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id expansion-root source-expanded-fns is-hof]
                ;; Named fns (with name in DB) are "boundaries" — their implementation
                ;; is hidden by default. Only the root fn and anonymous (name=nil) fns
                ;; are expanded automatically. Named fns show as leaf nodes unless
                ;; the user explicitly requests expansion.
                (let [fn-entity (get fn-map fn-id)
                      is-named (and fn-entity (:name fn-entity))
                      ;; Compute the node-id this call-site will carry so we
                      ;; can look up its expansion spec under the exact key
                      ;; the frontend sent back.
                      node-id-for-lookup
                      (cond
                        is-root (str "fn-" fn-id)
                        (nil? source-arg-id) (str "fn-" fn-id)
                        :else (let [caller-tag (if (and source-node-id
                                                        (str/starts-with?
                                                          source-node-id "fn-"))
                                                 (subs source-node-id 3)
                                                 (str source-node-id))]
                                (str "fn-" caller-tag "-" source-arg-id)))
                      spec (get-effective-spec expansions node-id-for-lookup)
                      ;; Named fns are boundaries. Expanding a fn substitutes
                      ;; only THAT fn's impl — its ref-targets stay leaves
                      ;; until the user explicitly expands them. Holds inside
                      ;; enclosing expansions too. Propagated bindings that
                      ;; target the leaf's slots still render on the leaf
                      ;; (see leaf code path below) so a named ref-target
                      ;; reached from an anon `_` intermediate shows the
                      ;; migrated :coll/:item bindings as edges from itself.
                      show-as-leaf (and is-named (not is-root) (spec-trivial? spec))]
                  (if show-as-leaf
                    ;; Named leaf boundary. Show only THIS fn's own args —
                    ;; its free-arg interface (unsets → placeholder / HOF-λ /
                    ;; optional-? badge) and its own literal value bindings.
                    ;; No recursion into refs: user must explicitly expand to
                    ;; see the leaf's body.
                    (let [node-id (add-fn-node state lookups fn-id false source-node-id source-arg-id)]
                      (add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)
                      (let [raw-own-args (get args-by-fn fn-id [])
                            seq-anchors (filterv sequence-anchor? raw-own-args)
                            seq-chain-ids (into #{}
                                                (mapcat (fn [anchor]
                                                          (map :id (walk-anchor-chain anchor arg-map))))
                                                seq-anchors)
                            anchor-ids (into #{} (map :id) seq-anchors)
                            own-args (filterv (fn [a]
                                                (not (or (contains? anchor-ids (:id a))
                                                         (contains? seq-chain-ids (:id a)))))
                                              raw-own-args)
                            ;; parent-bindings carries bindings from the
                            ;; enclosing expansion that MIGRATED down to this
                            ;; leaf because their source-chain terminates
                            ;; inside the leaf's closure. For an unset own
                            ;; arg, walk the source chain up through
                            ;; parent-bindings: first hit is the migrated
                            ;; binding filling THIS slot. Render as an edge
                            ;; to ref / value — the expand visually becomes
                            ;; `(fn ... :slot bound-value)` at the leaf, per
                            ;; clojure inline semantics.
                            find-migrated
                            (fn [arg-id]
                              (when parent-bindings
                                (some->> arg-id
                                         (get arg-map)
                                         :slot-id
                                         (get parent-bindings))))]
                        (let [;; Dedup by (terminal-slot, rendered-kind).
                              ;; Propagation materializes many shadows per
                              ;; semantic slot; only emit one edge per slot.
                              seen (atom #{})
                              mark-once!
                              (fn [key-extra]
                                (let [k key-extra]
                                  (if (contains? @seen k) false (do (swap! seen conj k) true))))]
                          (doseq [arg own-args]
                            (let [has-value (some? (:value arg))
                                  has-ref (some? (:ref-id arg))
                                  migrated (when-not (or has-value has-ref)
                                             (find-migrated (:id arg)))
                                  terminal (terminal-source-of arg-map (:id arg))]
                              (cond
                                has-value
                                (when (mark-once! [terminal :value (:value arg)])
                                  (add-arg-value-node state lookups
                                                      {:arg-id (:id arg)
                                                       :arg-name (resolve-arg-name arg arg-map)
                                                       :value (:value arg)
                                                       :type (:type arg)
                                                       :slot-id (:slot-id arg)
                                                       :binding-id (:binding-id arg)
                                                       :item-id (:item-id arg)
                                                       :fn-id (:fn-id arg)}
                                                      node-id #{fn-id}))

                                (and migrated (:ref-id migrated))
                                (when (mark-once! [terminal :ref (:ref-id migrated)])
                                  (process-any-fn (:ref-id migrated) node-id
                                                  (or (:arg-name migrated)
                                                      (resolve-arg-name arg arg-map))
                                                  false parent-bindings (:id arg)
                                                  nil #{fn-id} (child-hof arg-map (:id arg) is-hof)))

                                (and migrated (some? (:value migrated)))
                                (when (mark-once! [terminal :value (:value migrated)])
                                  (add-arg-value-node state lookups
                                                      (assoc migrated
                                                             :arg-id (:id arg)
                                                             :arg-name (or (:arg-name migrated)
                                                                           (resolve-arg-name arg arg-map))
                                                             :type (:type arg)
                                                             :fn-id (:fn-id arg))
                                                      node-id #{fn-id}))

                                (and (not has-ref) (not (arg-determined? arg-map parent-bound-terminals (:id arg))))
                                (when (mark-once! [terminal :unset])
                                  (add-unset-arg-node state lookups inverse-source-map
                                                      (resolve-arg-name arg arg-map)
                                                      (:type arg) (:id arg) node-id #{fn-id} is-hof)))))))
                      node-id)
                    ;; Normal processing
                    (if (spec-trivial? spec)
                      (let [bindings (build-arg-bindings fn-id lookups)
                            ;; Merge order: parent first, local (base) WINS.
                            ;; See process-expanded-fn comment for rationale.
                            bindings (if parent-bindings
                                       (merge parent-bindings bindings)
                                       bindings)]
                        (process-fn fn-id fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root source-expanded-fns is-hof))
                      ;; Expanded mode - pass parent expansion-root to maintain context
                      (process-expanded-fn fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings expansion-root source-expanded-fns is-hof)))))]

        ;; Start processing from root - no expansion-root initially, not HOF.
        (process-any-fn root-fn-id nil nil true nil nil nil #{} false)

        ;; Type-row roots (refinement / list / union / variant) have no
        ;; slots and no parents, so the standard pipeline produces only
        ;; an empty card. Surface their referenced types as synthetic
        ;; outgoing edges so the user sees the type's composition in
        ;; the canvas instead of buried inside a chip's title.
        (emit-type-row-internals! state lookups root-fn-id (str "fn-" root-fn-id))))

    ;; Attach the list of optional-unbound arg names to their source node so
    ;; the client can render a compact hint (e.g. "+default, +not-found")
    ;; instead of cluttering the graph with placeholder nodes.
    (let [final-nodes (mapv (fn [n]
                              (let [node-id (get-in n [:data :id])
                                    optionals (get (:optional-unsets-by-node @state) node-id)
                                    hof-captured (get (:hof-captured-by-node @state) node-id)]
                                (cond-> n
                                  (seq optionals)
                                  (assoc-in [:data :optionalArgs] (vec (distinct optionals)))

                                  (seq hof-captured)
                                  (assoc-in [:data :hofCapturedArgs] (vec (distinct hof-captured))))))
                            (:nodes @state))
          ;; Edge migration: when an unset arg inside an expanded HOF
          ;; was structurally captured, rewrite the caller's edge so it
          ;; originates from the inside-consumer node. The captured
          ;; mapping was filled by `add-unset-arg-node`'s capture branch.
          ;; Edges keep their target/argName; only `:source` and `:id`
          ;; are rewritten so the edge visually starts at the leaf
          ;; that actually reads the value.
          migrations (:captured-edge-migrations @state)
          final-edges (mapv (fn [e]
                              (let [data (:data e)
                                    sai (:sourceArgId data)
                                    new-src (when sai (get migrations sai))]
                                (if new-src
                                  (assoc e :data
                                         (assoc data
                                                :source new-src
                                                :id (str "e-cap-" new-src "-" (:target data))))
                                  e)))
                            (:edges @state))
          ;; Dedup duplicate value-arg-overlays. Same logical binding
          ;; can be emitted twice when a level-0 :value migrates into
          ;; a fn-ref-reached child: once as the parent's level-0
          ;; binding, once as the child's `find-migrated` resolution.
          ;; Substitution semantics says it should live at the deepest
          ;; consumer only — keep the overlay whose source-node-id has
          ;; the longest "fn-<root>-<arg1>-<arg2>..." suffix chain.
          ;;
          ;; Group by (terminal-source-id, displayed-value). Within a
          ;; group, the overlay reached via the longer source-node-id
          ;; chain is the deeper consumer; drop the others (and their
          ;; edges).
          arg-map (:arg-map lookups)
          arg-node? (fn [n] (= "arg" (get-in n [:data :type])))
          node-by-id (into {} (map (juxt #(get-in % [:data :id]) identity)) final-nodes)
          edges-by-target (group-by #(get-in % [:data :target]) final-edges)
          source-of (fn [node-id]
                      (some-> (first (edges-by-target node-id))
                              :data :source))
          depth-of (fn [node-id]
                     (count (filter #{\-} (or node-id ""))))
          ;; Group arg-overlays by (terminal-source-id, value-label)
          dedupe-groups (->> final-nodes
                             (filter arg-node?)
                             (group-by (fn [n]
                                         (let [data (:data n)
                                               aid (some-> (:argId data) parse-uuid)
                                               terminal (when aid
                                                          (terminal-source-of arg-map aid))]
                                           [terminal (:label data)]))))
          ;; For each group with >1 entry, drop everything except the
          ;; deepest. Keep groups with [nil _] keys (no terminal —
          ;; literals not wired to a primary slot — never dedup).
          drop-node-ids (->> dedupe-groups
                             (mapcat (fn [[[terminal _] members]]
                                       (when (and terminal (> (count members) 1))
                                         (let [scored (mapv (fn [n]
                                                              [(depth-of (source-of (get-in n [:data :id]))) n])
                                                            members)
                                               max-depth (apply max (map first scored))
                                               losers (->> scored
                                                           (remove #(= max-depth (first %)))
                                                           (map (comp #(get-in % [:data :id]) second)))]
                                           losers))))
                             set)
          deduped-nodes (filterv (fn [n]
                                   (not (contains? drop-node-ids
                                                   (get-in n [:data :id]))))
                                 final-nodes)
          deduped-edges (filterv (fn [e]
                                   (not (contains? drop-node-ids
                                                   (get-in e [:data :target]))))
                                 final-edges)]
      {:nodes deduped-nodes
       :edges deduped-edges})))


;; =============================================================================
;; LAYOUT ALGORITHM
;; =============================================================================

(defn- build-graph-info
  "Build graph structure from nodes and edges for layout."
  [nodes edges]
  (let [children (reduce (fn [m e]
                           (update m (get-in e [:data :source]) (fnil conj []) (get-in e [:data :target])))
                         {} edges)
        parents (reduce (fn [m e]
                          (update m (get-in e [:data :target]) (fnil conj []) (get-in e [:data :source])))
                        {} edges)
        shared-nodes (->> parents
                          (filter (fn [[_ ps]] (> (count ps) 1)))
                          (map first)
                          (into #{}))
        node-data-map (into {} (map (fn [n] [(get-in n [:data :id]) (:data n)]) nodes))]
    {:children children
     :parents parents
     :shared-nodes shared-nodes
     :node-data-map node-data-map}))


(defn- find-root-node
  "Find root node (no incoming edges)."
  [nodes edges]
  (let [has-parent (set (map #(get-in % [:data :target]) edges))]
    (first (filter #(not (contains? has-parent (get-in % [:data :id]))) nodes))))


(defn- get-child-type
  "Get type of child node: :fn, :fixed, or :free"
  [child-id node-data-map]
  (let [data (get node-data-map child-id)]
    (cond
      (or (nil? data) (:isPlaceholder data)) :free
      (= (:type data) "fn") :fn
      (= (:type data) "arg") :fixed
      :else :free)))


(defn- order-children
  "Order a node's children for placement. Per-call-site model: every
   child has exactly one parent, no sharing, so ordering is a simple
   stable sort by type (fn > fixed > free) preserving original index
   within a type."
  [parent-id children-map node-data-map]
  (let [type-order {:fn 0 :fixed 1 :free 2}
        child-ids (get children-map parent-id [])]
    (vec
      (sort-by
        (fn [cid]
          [(get type-order (get-child-type cid node-data-map) 3)
           (.indexOf ^java.util.List child-ids cid)])
        child-ids))))


;; Matrix operations
(defn- empty-matrix
  []
  {:grid {} :positions {}})


(defn- get-cell
  [matrix row col]
  (get (:grid matrix) [row col]))


(defn- cell-occupied?
  [matrix row col]
  (some? (get-cell matrix row col)))


(defn- place-node-in-matrix
  [matrix node-id row col]
  (-> matrix
      (assoc-in [:grid [row col]] node-id)
      (assoc-in [:positions node-id] {:row row :col col})))


(defn- get-node-pos
  [matrix node-id]
  (get-in matrix [:positions node-id]))


(defn- layout-graph
  "Depth-first grid placement. Per-call-site model: every node has
   exactly one parent, so the output is a tree.

   Algorithm:
   1. Build the horizontal branch (chain of first children from root).
   2. Find the first row where the branch fits (checks column occupancy).
   3. Place the branch on that row.
   4. Right-to-left across the branch, place each node's remaining
      children as subtrees, starting one row below.
   5. Recurse into each placed subtree.

   Invariant: a node's entire subtree is placed before its next sibling."
  [root-id graph-info]
  (let [{:keys [children node-data-map]} graph-info
        sorted-children-map
        (into {}
              (map (fn [node-id]
                     [node-id (order-children node-id children node-data-map)])
                   (keys node-data-map)))]

    (letfn [(get-sorted-children
              [node-id]
              (get sorted-children-map node-id []))

            ;; Build horizontal branch (chain of first children)
            (build-branch
              [node-id start-col]
              (loop [current node-id
                     col start-col
                     branch []]
                (if (nil? current)
                  branch
                  (let [branch (conj branch {:id current :col col})
                        kids (get-sorted-children current)
                        first-child (first kids)]
                    (if first-child
                      (recur first-child (inc col) branch)
                      branch)))))

            ;; Check if branch fits at row
            (branch-fits-at-row?
              [matrix branch row]
              (every? (fn [{:keys [col]}]
                        (not (cell-occupied? matrix row col)))
                      branch))

            ;; Find row where branch fits
            (find-row-for-branch
              [matrix branch min-row]
              (loop [row min-row]
                (if (branch-fits-at-row? matrix branch row)
                  row
                  (recur (inc row)))))

            ;; Place branch at row
            (place-branch
              [matrix branch row]
              (reduce
                (fn [m {:keys [id col]}]
                  (place-node-in-matrix m id row col))
                matrix
                branch))

            ;; Reserve vertical edge cells from parent to child
            ;; When child is placed below parent, the edge goes through intermediate rows
            (reserve-vertical-edge
              [matrix parent-row child-row child-col]
              (if (<= child-row (inc parent-row))
                matrix  ; Adjacent rows, no intermediate cells to reserve
                (reduce (fn [m edge-row]
                          (assoc-in m [:grid [edge-row child-col]]
                                    {:vertical-edge true}))
                        matrix
                        (range (inc parent-row) child-row))))

            ;; Get max row used by a subtree (for computing next sibling's start row)
            (subtree-max-row
              [matrix node-id]
              (if-let [pos (get-node-pos matrix node-id)]
                (:row pos) 0))

            ;; Recursively find max row in entire subtree rooted at node-id
            (find-subtree-max-row
              [matrix node-id]
              (let [pos (get-node-pos matrix node-id)
                    my-row (if pos (:row pos) 0)
                    kids (get-sorted-children node-id)]
                (if (empty? kids)
                  my-row
                  (apply max my-row (map #(find-subtree-max-row matrix %) kids)))))

            ;; Main recursive placement function
            ;; Places node-id and its entire subtree, returns [matrix max-row-used]
            ;; parent-row is the row of the parent node (for reserving vertical edges)
            (place-subtree
              [matrix node-id target-row target-col parent-row]
              (let [;; Build horizontal branch from this node
                    branch (build-branch node-id target-col)
                    ;; Find row where branch fits (checks only cells in branch's column range)
                    actual-row (find-row-for-branch matrix branch target-row)
                    ;; Place the branch
                    matrix (place-branch matrix branch actual-row)
                    ;; Reserve vertical edge from parent to this branch's first node
                    ;; The edge goes from parent (at parent-row) down to node-id (at actual-row)
                    ;; through the child's column (target-col)
                    matrix (if parent-row
                             (reserve-vertical-edge matrix parent-row actual-row target-col)
                             matrix)]

                ;; Process non-first children of each node in branch
                ;; RIGHT-TO-LEFT order (deepest first) for depth-first placement
                (loop [branch-nodes (reverse branch)
                       matrix matrix
                       global-max-row actual-row]
                  (if (empty? branch-nodes)
                    [matrix global-max-row]
                    (let [{:keys [id col]} (first branch-nodes)
                          kids (get-sorted-children id)
                          rest-kids (rest kids)  ; skip first (in horizontal branch)
                          child-col (inc col)
                          ;; Parent row for children is the row where this node was placed
                          ;; (which is actual-row for all nodes in the horizontal branch)
                          this-node-row actual-row
                          ;; Place this node's remaining children
                          ;; Each starts search from (inc actual-row), find-row-for-branch
                          ;; will find where it actually fits based on column occupancy.
                          min-child-row (inc actual-row)
                          [matrix local-max-row]
                          (loop [remaining rest-kids
                                 matrix matrix
                                 max-row-so-far actual-row]
                            (if (empty? remaining)
                              [matrix max-row-so-far]
                              (let [child-id (first remaining)
                                    ;; Each child starts from min-child-row
                                    ;; find-row-for-branch (inside place-subtree) will find actual row
                                    ;; Pass parent's row for vertical edge reservation
                                    [matrix child-max-row] (place-subtree matrix child-id min-child-row child-col this-node-row)]
                                (recur (rest remaining)
                                       matrix
                                       (max max-row-so-far child-max-row)))))]

                      (recur (rest branch-nodes)
                             matrix
                             ;; Track overall max for return value
                             (max global-max-row local-max-row)))))))]

      (let [[matrix _] (place-subtree (empty-matrix) root-id 0 0 nil)]
        matrix))))


(defn- validate-layout
  "Check for collisions in the layout."
  [matrix]
  (let [positions (vals (:positions matrix))
        pos-keys (map (fn [{:keys [row col]}] [row col]) positions)
        unique-count (count (set pos-keys))
        total-count (count pos-keys)]
    {:valid (= unique-count total-count)
     :issues (when (not= unique-count total-count)
               [{:type "collision"
                 :message (str "Found " (- total-count unique-count) " collisions")}])}))


;; =============================================================================
;; PUBLIC API
;; =============================================================================

(defn compute-layout-matrix
  "Compute grid-based layout from elements (for testing).
   Input: {:elements {:nodes [...], :edges [...]}}
   Output: {:grid-pos {node-id {:row r :col c}}, :validation {...}}"
  [{:keys [elements]}]
  (let [nodes (mapv (fn [n] {:data n}) (or (:nodes elements) []))
        edges (mapv (fn [e] {:data e}) (or (:edges elements) []))]
    (if (empty? nodes)
      {:grid-pos {}
       :validation {:valid true :issues []}}
      (let [graph-info (build-graph-info nodes edges)
            root (find-root-node nodes edges)]
        (if-not root
          {:grid-pos {}
           :validation {:valid false
                        :issues [{:type "no_root" :message "No root node found"}]}}
          (let [matrix (layout-graph (get-in root [:data :id]) graph-info)
                validation (validate-layout matrix)]
            {:grid-pos (:positions matrix)
             :validation validation}))))))


(defn- parse-spec
  "Parse a single expansion spec value.
   Returns integer level or {:full-depth N :partial-fns #{uuid ...}}."
  [v]
  (cond
    (integer? v) v
    (map? v) {:full-depth (or (:full-depth v) 0)
              :partial-fns (set (map (fn [s]
                                       (if (uuid? s)
                                         s
                                         (java.util.UUID/fromString (str s))))
                                     (:partial-fns v)))}
    :else 0))


(defn- parse-expansions
  "Parse raw expansions map from request.
   Keys are cytoscape node-ids (`fn-<...>` strings). Under per-call-site
   scoping a non-root node id has the form `fn-<caller-tag>-<source-arg-id>`
   which is NOT a single UUID, so we just keep the full id string as the
   map key. Layout looks up the spec using the exact same string it
   assigned when building each node."
  [expansions-raw]
  (into {}
        (map (fn [[k v]]
               [(name k) (parse-spec v)]))
        expansions-raw))


(defn- parse-layout-request
  "Parse request body into {:root-id UUID, :expansions parsed-map}.
   Throws on missing root-id. Accepts both string bodies and the raw
   httpkit InputStream — the internal-request keeps `:body` un-slurped
   so middleware-wrapped handlers don't see a consumed stream."
  [request]
  (let [raw-body (:body request)
        body (cond
               (instance? java.io.InputStream raw-body)
               (json/parse-stream (java.io.InputStreamReader. raw-body "UTF-8") true)
               (and (string? raw-body) (not (str/blank? raw-body)))
               (json/parse-string raw-body true)
               :else nil)
        root-id-str (:root-id body)]
    (when-not root-id-str
      (throw (ex-info "Request body must contain 'root-id'"
                      {:type :execution-error/invalid-args})))
    {:root-id (java.util.UUID/fromString root-id-str)
     :expansions (parse-expansions (:expansions body {}))}))


(defbase/defbase get-layout-data
  "Compute layout from root-id and expansions.
   Input (from request body): {root-id: uuid-string, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}"
  [request]
  (let [storage (:storage ctx)]
    (when-not storage
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))
    (let [{:keys [root-id expansions]} (parse-layout-request request)
          raw-data (load-graph-entities ctx)
          lookups (build-lookups raw-data)
          _ (when-not (get (:fn-map lookups) root-id)
              (throw (ex-info "Root function not found"
                              {:type :execution-error/not-found
                               :root-id root-id})))
          {:keys [nodes edges]} (build-graph-elements root-id expansions lookups)
          graph-info (build-graph-info nodes edges)
          root-node (find-root-node nodes edges)
          matrix (if root-node
                   (layout-graph (get-in root-node [:data :id]) graph-info)
                   (empty-matrix))
          validation (validate-layout matrix)]
      {:nodes nodes
       :edges edges
       :grid-pos (:positions matrix)
       :validation validation})))


;; === Registry ===

(def impls
  {:get-layout-data get-layout-data})
