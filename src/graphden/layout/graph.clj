(ns graphden.layout.graph
  "Top-level orchestrator of the layout pipeline.

   Re-exports the loading + lookup-map surface from `graphden.layout.data`
   so callers that already `:require [graphden.layout.graph]` keep
   working. Holds:

   - the cycle-guarded `process-*` mutual-recursion family
     (`process-any-fn` / `process-fn` / `process-expanded-fn` /
     `process-expanded-fn-impl`) that walks a fn-graph rooted at one fn
     and emits graph nodes/edges into a shared `state` atom;
   - the three post-processing transforms (`annotate-optionals` /
     `migrate-captured-edges` / `dedup-overlays`) that shape the wire
     output after the walkers have populated `state`;
   - the `build-graph-elements` assembler — constructs `ctx`, dispatches
     the walkers, then pipes state through the transforms.

   Stage 1 (raw load + slot-view synthesis + lookup-map construction)
   lives in `graphden.layout.data`. Bindings resolution + classifier-item
   constructors + sequence-anchor helpers live in
   `graphden.layout.bindings`. The bulk of the pure helpers used by
   `build-graph-elements` (~28 small functions: arg classification,
   node/edge field shaping, ref/value/unset emission, edge-label /
   type-chain computation) live in `graphden.layout.builder-helpers`."
  (:require
    [clojure.string :as str]
    [graphden.executor.compile.renames :as renames]
    [graphden.layout.bindings :as bnd]
    [graphden.layout.builder-helpers :as bh]
    [graphden.layout.data :as data]
    [graphden.packages.records.ids :as ids]))


;; ---------------------------------------------------------------------------
;; Re-export Stage-1 public surface so external callers
;; (`graphden.layout.core`, `resources/packages/app/layout/impls.clj`,
;; layout tests) don't need to migrate their `:require`s. New code
;; should refer to `graphden.layout.data` directly.

(def derive-fn-slot-views data/derive-fn-slot-views)
(def load-graph-entities-uncached data/load-graph-entities-uncached)
(def ensure-synth-args data/ensure-synth-args)
(def build-lookups data/build-lookups)
(def cached-build-lookups data/cached-build-lookups)


;; =============================================================================
;; Graph traversal — process-* family.
;;
;; The four functions below cooperatively walk a fn-graph rooted at one fn
;; and emit graph nodes/edges into a shared `state` atom, sharing the
;; ~10 walk values through an explicit `ctx` map. Roles:
;;
;;   process-any-fn            — dispatch: leaf vs in-place vs expanded
;;   process-fn                — non-expanded path (no `letfn` rename
;;                                conflict since we're at top-level)
;;   process-expanded-fn       — cycle-guarded entry to the expanded path
;;   process-expanded-fn-impl  — actual expanded-mode body
;;
;; The functions are mutually recursive (`process-any-fn` → `process-fn` /
;; `process-expanded-fn`; both expansion paths → `process-any-fn`), so a
;; forward `declare` lets `process-any-fn` be defined first.
;;
;; `ctx` map keys (all values captured from `build-graph-elements`):
;;   :state              atom — see comment in `build-graph-elements` for keys
;;   :lookups            the lookups bundle (fn-map, arg-map, …)
;;   :fn-map :arg-map    destructured for hot-loop reads
;;   :args-by-fn :fn-slots-by-fn  destructured for hot-loop reads
;;   :inverse-source-map :parent-bound-terminals  precomputed read helpers
;;   :expansion-bindings atom — per-expanded-fn binding bookkeeping
;;   :expansions         the user-supplied expansion spec map
;; =============================================================================

(declare process-fn process-expanded-fn process-expanded-fn-impl process-any-fn)


(defn- process-fn
  [ctx original-fn-id display-fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root source-expanded-fns is-hof]
  (let [{:keys [state lookups fn-map arg-map args-by-fn fn-slots-by-fn
                inverse-source-map parent-bound-terminals]} ctx
        node-id (bh/add-fn-node state lookups original-fn-id is-root source-node-id source-arg-id)
        ;; Key for tracking fully processed nodes - includes expansion context
        process-key (str node-id "-" (hash bindings))]
    (bh/add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)

    ;; Only process children if this node wasn't already fully processed
    ;; This prevents infinite recursion when same fn is reached via different paths
    (when-not (contains? (:processed-fn-nodes @state) process-key)
      (swap! state update :processed-fn-nodes conj process-key)
      ;; Compute displayed-ref-arg-ids: arg-ids of fns that will be displayed as child nodes
      ;; This is used to hide bindings that will appear on child nodes instead
      (let [displayed-ref-arg-ids
            (when (some? expansion-root)
              (let [fn-args (get args-by-fn display-fn-id [])
                    ref-fn-ids (set (concat
                                      (keep :ref-id fn-args)
                                      (keep (fn [arg]
                                              (when-let [b (get bindings (:slot-id arg))]
                                                (:ref-id b)))
                                            fn-args)))
                    expansion-chain-fns (set (data/get-inheritance-chain* expansion-root lookups))]
                (set (mapcat (fn [ref-fn-id]
                               (let [ref-chain (data/get-inheritance-chain* ref-fn-id lookups)]
                                 (mapcat (fn [rfn-id]
                                           (when-not (contains? expansion-chain-fns rfn-id)
                                             (map :slot-id (get fn-slots-by-fn rfn-id []))))
                                         ref-chain)))
                             ref-fn-ids))))
            exp-root-chain (when expansion-root
                             (set (data/get-inheritance-chain* expansion-root lookups)))
            all-args (bh/collect-fn-args lookups display-fn-id bindings
                                         :is-structural (some? expansion-root)
                                         :displayed-ref-arg-ids (or displayed-ref-arg-ids #{})
                                         :expansion-root-chain (or exp-root-chain #{}))
            ;; Filter out :unset args that are BOUND BY ANCESTORS.
            ancestor-bindings
            (when-not (some? expansion-root)
              (let [all-levels (data/get-inheritance-levels display-fn-id fn-map)
                    ancestor-fns (reverse (rest (mapcat identity all-levels)))]
                (reduce
                  (fn [b fid] (bnd/add-bindings-from-fn fid b lookups))
                  {} ancestor-fns)))

            ancestor-bound?
            (fn [arg-id]
              (let [arg (get arg-map arg-id)]
                (or (and ancestor-bindings arg
                         (contains? ancestor-bindings (:slot-id arg)))
                    (bh/arg-determined? arg-map parent-bound-terminals arg-id))))

            ;; Loader synthesizes a `value` slot on every refinement and an
            ;; `items` slot on every list type-row. Hidden at the type-row's
            ;; own page (its structure is already carried by base/element
            ;; edges); composed children still see + bind via inheritance.
            ;; The synth slot's id is deterministic — records/parse.clj
            ;; seeds it as `(ids/slot-id owner-fn-id slot-name)`. Match on
            ;; that id, not the resolved name: we already hold both the
            ;; owner (`display-fn-id`) and `(:slot-id arg)`.
            hidden-synth-slot-id
            (when is-root
              (let [f (get fn-map display-fn-id)
                    has-slots? (boolean
                                 (seq (get fn-slots-by-fn display-fn-id)))]
                (when-let [nm (case (bh/type-row-role f has-slots?)
                                :refinement "value"
                                :list       "items"
                                nil)]
                  (ids/slot-id display-fn-id nm))))
            synth-slot?
            (fn [arg]
              (and hidden-synth-slot-id
                   (= hidden-synth-slot-id (:slot-id arg))))

            filtered-args
            (filterv (fn [arg]
                       (and (not (synth-slot? arg))
                            (if (= :unset (:kind arg))
                              (not (ancestor-bound? (:arg-id arg)))
                              true)))
                     all-args)]
        (doseq [arg filtered-args]
          (case (:kind arg)
            :ref (let [ref-expansion-root (when-not (:is-binding arg) expansion-root)
                       ref-bindings bindings]
                   (process-any-fn ctx (:ref-id arg) node-id (:arg-name arg) false ref-bindings (:arg-id arg) ref-expansion-root #{display-fn-id} (bh/child-hof arg-map (:arg-id arg) is-hof)))
            :value (bh/add-arg-value-node state lookups arg node-id #{display-fn-id})
            :unset (bh/add-unset-arg-node state lookups inverse-source-map (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id #{display-fn-id} is-hof)
            nil))))
    node-id))


(defn- process-expanded-fn
  [ctx original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof]
  ;; parent-expansion-root: if we're nested inside another expansion, keep that context
  ;; Otherwise, this fn becomes its own expansion root
  ;;
  ;; Cycle protection: if we're already processing this expansion,
  ;; just add the node + edge and return without recursing further.
  (let [{:keys [state lookups]} ctx
        in-progress-key [original-fn-id parent-expansion-root]]
    (if (contains? (:in-progress-expansions @state) in-progress-key)
      (let [node-id (bh/add-fn-node state lookups original-fn-id is-root source-node-id source-arg-id)]
        (bh/add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)
        node-id)
      (do
        (swap! state update :in-progress-expansions conj in-progress-key)
        (try
          (process-expanded-fn-impl ctx original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof)
          (finally
            (swap! state update :in-progress-expansions disj in-progress-key)))))))


;; ---------------------------------------------------------------------
;; Migration target resolution — used by process-expanded-fn-impl to
;; decide where a binding visualised on the expansion-root card should
;; "migrate" to. Four resolution paths, all returning {:target fn-id
;; :target-slot-id sid-or-nil :via tag}; see comments per branch for the
;; case each one covers. Extracted (originally inline ~70 lines) so the
;; orchestrator reads as a stage list, not a nested let.

(defn- ancestor-ref-fn-id-index
  "Build {descendant-fn-id → ref-id} where descendant-fn-id ranges
   over each ancestor-ref's full inheritance chain. Lets the slot-
   owner / PB' / fn-id-of-arg branches answer 'does any of these
   fids appear inside an ancestor-ref's chain?' in O(1)."
  [ancestor-refs lookups]
  (into {}
        (mapcat (fn [ref-arg]
                  (let [chain (data/get-inheritance-chain* (:ref-id ref-arg) lookups)]
                    (map (fn [fid] [fid (:ref-id ref-arg)]) chain))))
        ancestor-refs))


(defn- migration-via-pb-bridge
  "PB' own-slots on a composed ancestor (e.g. `:router
   :not-found-response`) carry `:source-slot-id` linking them to the
   underlying base-fn slot (parser bridge, commit ffd07480). Walking
   the source-slot-id chain finds the deep slot's owner — typically
   a base-fn whose inheritance chain IS in `fn-id->ref-id-index` —
   so the migration target falls out of the same fn-id-keyed lookup
   the slot-owner branch uses. Records the deep slot-id so
   `migrated-bindings-for-target` keys by the target's slot-id, not
   the PB' own-slot id."
  [sid fn-id->ref-id lookups]
  (some (fn [chain-sid]
          (let [chain-owner ((:slot-owner lookups) chain-sid)]
            (when-let [target (some fn-id->ref-id
                                    (data/get-inheritance-chain*
                                      chain-owner lookups))]
              {:target target :target-slot-id chain-sid :via :pb-bridge})))
        (rest (renames/chain-source-slot-ids sid (:slot-map lookups)))))


(defn- migration-via-free-arg-name
  "FREE-ARG / use-site fallback (see memory
   `expansion_substitution_model.md`, concrete failure pattern
   2026-06-19). The slot-id-based paths only find a target when the
   arg's slot is also a slot of an ancestor-ref. This branch
   instead matches by NAME: if the arg's name appears in some
   ancestor-ref's `deep-free-ext-names`, that ref is consuming the
   binding through free-arg propagation and is the correct β-inline
   target.

   May return MULTIPLE targets — shared-arg case (the same name
   read by N consumers ⇒ N edges to the same source node, matching
   `let`-style binding visualisation)."
  [arg ancestor-refs lookups]
  (when-let [arg-kw (some-> (:arg-name arg) keyword)]
    (->> ancestor-refs
         (keep (fn [ref-arg]
                 (let [ref-id (:ref-id ref-arg)]
                   (when (and ref-id
                              (some #{arg-kw}
                                    (renames/deep-free-ext-names
                                      ref-id lookups)))
                     {:target ref-id :target-slot-id nil :via :free-arg}))))
         (distinct))))


(defn- migration-targets-for-arg
  "Resolve migration targets for one arg in the expansion-root's
   classified bucket. Returns a (possibly-empty) seq of
   `{:target :target-slot-id :via}` records. The slot-id-based
   branches (slot-owner / PB' / fn-id-of-arg) are tried in priority
   order — first hit wins, each returns at most one target. If none
   matches, falls through to `migration-via-free-arg-name` which may
   return multiple targets (shared-arg)."
  [arg arg-map fn-id->ref-id ancestor-refs lookups]
  (when-let [a (get arg-map (:arg-id arg))]
    (let [sid (:slot-id a)
          slot-owner (some-> sid ((:slot-owner lookups)))
          slot-target (or (when slot-owner
                            (when-let [t (some fn-id->ref-id
                                               (data/get-inheritance-chain*
                                                 slot-owner lookups))]
                              {:target t :target-slot-id nil
                               :via :slot-owner}))
                          (migration-via-pb-bridge sid fn-id->ref-id lookups)
                          (when-let [t (some fn-id->ref-id
                                             (data/get-inheritance-chain*
                                               (:fn-id a) lookups))]
                            {:target t :target-slot-id nil
                             :via :fn-id-of-arg}))]
      (if slot-target
        [slot-target]
        (seq (migration-via-free-arg-name arg ancestor-refs lookups))))))


(defn- classify-arg-migrations
  "Reduce over candidate args (level-0 refs/values + ancestor refs/
   values) and group by migration target. Returns
   `{:migrated {target-fn-id [arg+migration-meta …]}
     :migrated-ids #{arg-id-of-each-migrated-arg}}`.

   `:free-arg-migration true` is stamped on args that reached their
   target via free-arg-name matching — process-expanded-fn-impl's
   post-pass uses that flag to route them through the consumer-side
   render (slot-id-keyed `migrated-bindings-for-target` can't
   surface them because the consumer's surface slots don't carry the
   caller's slot-id)."
  [candidate-args arg-map fn-id->ref-id ancestor-refs lookups]
  (reduce
    (fn [acc arg]
      (let [targets (migration-targets-for-arg
                      arg arg-map fn-id->ref-id ancestor-refs lookups)
            valid (->> targets
                       (filter #(and (:target %)
                                     (not= (:target %) (:ref-id arg))))
                       (seq))]
        (if valid
          (-> (reduce (fn [a {:keys [target target-slot-id via]}]
                        (update-in a [:migrated target] (fnil conj [])
                                   (cond-> arg
                                     target-slot-id (assoc :migrated-target-slot-id
                                                           target-slot-id)
                                     (= via :free-arg) (assoc :free-arg-migration true))))
                      acc
                      valid)
              (update :migrated-ids conj (:arg-id arg)))
          acc)))
    {:migrated {} :migrated-ids #{}}
    candidate-args))


;; ---------------------------------------------------------------------
;; Canonical visual ordering across an expansion-root's slot-set.

(defn- canonical-sort-by-tier-position
  "Stable-sort args by (deepest-ancestor-first, position-within-
   that-ancestor). The 'deepest-ancestor' component puts inherited
   slots from the OLDEST ancestor in the chain BEFORE newer
   ancestors' own additions — so `:if`'s `:test :then :else`
   (deepest, declaration-order) render before `:response-cache-
   wrap`'s own `:base-handler`. The 'position' component preserves
   each ancestor's `:args` map declaration order (encoded as
   `:position` on the `fn-slot` row). Falls back to walk-order
   (Long/MAX_VALUE rank) when slot/owner data is missing (test
   fixtures, synth-args). Default-on — matches what authors
   implicitly mean when they read the base-fn's `:args` declaration."
  [args original-fn-id lookups]
  (let [chain (data/get-inheritance-chain* original-fn-id lookups)
        tier-of-fn (zipmap chain (range))
        fn-slots-by-fn (:fn-slots-by-fn lookups)
        slot-owner-fn (:slot-owner lookups)
        arg-map* (:arg-map lookups)
        position-of-fn-slot
        (fn [fn-id slot-id]
          (some (fn [fs] (when (= (:slot-id fs) slot-id) (:position fs)))
                (get fn-slots-by-fn fn-id [])))]
    (sort-by
      (fn [arg]
        (let [sid (or (:slot-id arg)
                      (some-> (:arg-id arg) arg-map* :slot-id))
              owner (some-> sid slot-owner-fn)
              tier (get tier-of-fn owner)
              pos (when (and owner sid)
                    (position-of-fn-slot owner sid))]
          ;; (-tier) so deeper ancestor (higher index in chain
          ;; vector) sorts FIRST under ascending sort.
          [(if tier (- tier) Long/MAX_VALUE)
           (or pos Long/MAX_VALUE)]))
      args)))


;; ---------------------------------------------------------------------
;; Migrated-bindings to leaf-bindings — slot-id-keyed channel that
;; surfaces a migrated binding inside the target's render call.

(defn- migrated-bindings-for-target
  "Build `{slot-id → binding-shape}` from migration entries the
   target receives. Keyed by `:migrated-target-slot-id` when PB'-
   bridge migration recorded it (the deep slot in the target's
   chain); otherwise by the source slot-id (legacy migrations where
   source and target share the slot). The consumer's
   `find-migrated` does `(get parent-bindings <slot-id>)`."
  [args arg-map]
  (reduce
    (fn [b arg]
      (let [source-sid (:slot-id (get arg-map (:arg-id arg)))
            key-sid (or (:migrated-target-slot-id arg) source-sid)]
        (if key-sid
          (assoc b key-sid {:arg-name (:arg-name arg)
                            :value (:value arg)
                            :ref-id (:ref-id arg)
                            :arg-id (:arg-id arg)
                            :slot-id key-sid
                            :fn-id (:fn-id (get arg-map (:arg-id arg)))})
          b)))
    {} args))


(defn- process-expanded-fn-impl
  "Render `original-fn-id` in expanded mode. Stages:

   1. Classify args (level-0 / ancestor) by kind (ref/value/unset).
   2. Resolve migration targets (slot-owner / PB' / fn-id-of-arg /
      free-arg-name) — see `migration-targets-for-arg`.
   3. Canonical-sort remaining (non-migrated) args.
   4. Render level-0 children + unsets + values from this card.
   5. Render ancestor-ref children, capturing each consumer's
      node-id so step 6 can attach edges to them.
   6. Free-arg β-inline pass: render free-arg-migrated bindings
      from the consumer's node (where the use-site actually lives);
      stamp consumer with `:deep-free-by-node` so the post-processor
      can surface the `⇣` strip on the card.
   7. Render ancestor unsets / values.

   Returns the root node-id."
  [ctx original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof]
  (let [{:keys [state lookups fn-map arg-map inverse-source-map
                parent-bound-terminals expansion-bindings]} ctx
        levels (data/get-inheritance-levels original-fn-id fn-map)
        expand-set (bh/spec->expand-set fn-map original-fn-id spec)
        display-bindings (reduce
                           (fn [b fid] (bnd/add-bindings-from-fn fid b lookups))
                           {} expand-set)
        chain-bindings (merge parent-bindings display-bindings)
        effective-expansion-root (or parent-expansion-root original-fn-id)
        node-id (bh/add-fn-node state lookups original-fn-id is-root source-node-id source-arg-id)]
    (bh/add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)

    (let [;; Stage 1 — classify
          raw-args (bh/collect-expanded-args lookups levels expand-set chain-bindings)
          all-args (filterv (fn [arg]
                              (if (= :unset (:kind arg))
                                (not (bh/arg-determined? arg-map parent-bound-terminals (:arg-id arg)))
                                true))
                            raw-args)
          ancestor-refs (filter #(and (:from-ancestor %) (= (:kind %) :ref)) all-args)
          ancestor-values (filter #(and (:from-ancestor %) (= (:kind %) :value)) all-args)
          ancestor-unsets (filter #(and (:from-ancestor %) (= (:kind %) :unset)) all-args)
          level-0-args (remove :from-ancestor all-args)
          level-0-refs (filter #(= (:kind %) :ref) level-0-args)
          level-0-values (filter #(= (:kind %) :value) level-0-args)
          level-0-unsets (filter #(= (:kind %) :unset) level-0-args)
          has-ancestor-refs (seq ancestor-refs)

          ;; Stage 2 — migration target resolution
          fn-id->ref-id (ancestor-ref-fn-id-index ancestor-refs lookups)
          {:keys [migrated migrated-ids]}
          (classify-arg-migrations
            (concat level-0-refs level-0-values
                    ancestor-refs ancestor-values)
            arg-map fn-id->ref-id ancestor-refs lookups)
          not-migrated (fn [coll] (remove #(contains? migrated-ids (:arg-id %)) coll))

          ;; Stage 3 — canonical sort the staying args
          sort-it (fn [coll]
                    (canonical-sort-by-tier-position coll original-fn-id lookups))
          level-0-stay         (sort-it (not-migrated (concat level-0-refs
                                                              level-0-values)))
          ancestor-refs-stay   (sort-it (not-migrated ancestor-refs))
          ancestor-values-stay (sort-it (not-migrated ancestor-values))]

      (swap! expansion-bindings assoc original-fn-id
             {:has-ancestor-refs has-ancestor-refs})

      (let [find-migrated
            (fn [arg-id]
              (when parent-bindings
                (some->> arg-id
                         (get arg-map)
                         :slot-id
                         (get parent-bindings))))
            render-unset
            (fn [arg]
              (let [m (find-migrated (:arg-id arg))]
                (cond
                  (and m (:ref-id m))
                  (process-any-fn ctx (:ref-id m) node-id
                                  (or (:arg-name m) (:arg-name arg))
                                  false parent-bindings (:arg-id arg)
                                  parent-expansion-root expand-set (bh/child-hof arg-map (:arg-id arg) is-hof))
                  (and m (true? (:value-present m)))
                  (bh/add-arg-value-node state lookups
                                         (assoc m
                                                :arg-id (:arg-id arg)
                                                :arg-name (or (:arg-name m) (:arg-name arg)))
                                         node-id expand-set)
                  :else
                  (bh/add-unset-arg-node state lookups inverse-source-map
                                         (:arg-name arg) (:arg-type arg)
                                         (:arg-id arg) node-id expand-set is-hof))))]

        ;; Stage 4 — level-0 children
        (doseq [arg (filter #(= (:kind %) :ref) level-0-stay)]
          (process-any-fn ctx (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) parent-expansion-root expand-set (bh/child-hof arg-map (:arg-id arg) is-hof)))
        (doseq [arg level-0-unsets] (render-unset arg))
        (doseq [arg (filter #(= (:kind %) :value) level-0-stay)]
          (bh/add-arg-value-node state lookups arg node-id expand-set))

        ;; Stage 5 — ancestor-ref children + capture consumer node-ids
        ;; for stage 6
        (let [consumer-node-ids
              (into {}
                    (for [arg ancestor-refs-stay]
                      (let [ref-target-id (:ref-id arg)
                            migrated-to-this-ref (get migrated ref-target-id [])
                            leaf-bindings (migrated-bindings-for-target
                                            migrated-to-this-ref arg-map)
                            child-node-id (process-any-fn ctx ref-target-id node-id
                                                          (:arg-name arg) false leaf-bindings
                                                          (:arg-id arg)
                                                          effective-expansion-root expand-set
                                                          (bh/child-hof arg-map (:arg-id arg) is-hof))]
                        [ref-target-id child-node-id])))]

          ;; Stage 6 — free-arg β-inline post-pass.
          ;; For each migrated binding tagged `:free-arg-migration`,
          ;; render its ref-target as a child of the CONSUMER node
          ;; (the ancestor-ref whose deep-free reads the arg-name).
          ;; The slot-id-keyed leaf-bindings channel can't surface
          ;; these because the consumer's surface slots don't carry
          ;; the free-arg's slot-id — an explicit `process-any-fn`
          ;; from the consumer's node is the equivalent of "the
          ;; substituted body now references _app-encoded at this
          ;; use-site". Shared-arg case (consumer set > 1) falls out
          ;; — one extra `process-any-fn` per consumer, all
          ;; targeting the same ref-id, dedup'd at the node level by
          ;; the standard shared-node machinery.
          ;;
          ;; Each consumer also gets a `:deep-free-by-node` entry —
          ;; the post-processor turns those into the `⇣` strip on
          ;; the card. Without it the card displays the outgoing
          ;; edge but nothing on the card itself indicates "this fn
          ;; accepts X as a free arg propagated to my body", which
          ;; misleads readers into thinking the slot must live on
          ;; one of the visible ancestor rows.
          (doseq [[target migrated-args] migrated
                  arg migrated-args
                  :when (:free-arg-migration arg)
                  :let [consumer-node-id (get consumer-node-ids target)]
                  :when (and consumer-node-id (:ref-id arg))]
            (process-any-fn ctx (:ref-id arg) consumer-node-id
                            (:arg-name arg) false {} (:arg-id arg)
                            effective-expansion-root expand-set
                            (bh/child-hof arg-map (:arg-id arg) is-hof))
            (when-let [nm (:arg-name arg)]
              (swap! state update-in
                     [:deep-free-by-node consumer-node-id]
                     (fnil conj #{}) nm))))

        ;; Stage 7 — ancestor unsets / values
        (doseq [arg ancestor-unsets] (render-unset arg))
        (doseq [arg ancestor-values-stay]
          (bh/add-arg-value-node state lookups arg node-id expand-set))))

    node-id))


(defn- process-any-fn
  [ctx fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id expansion-root source-expanded-fns is-hof]
  ;; Named fns (with name in DB) are "boundaries" — their implementation
  ;; is hidden by default. Only the root fn and anonymous (name=nil) fns
  ;; are expanded automatically.
  (let [{:keys [state lookups fn-map arg-map args-by-fn
                inverse-source-map parent-bound-terminals expansions]} ctx
        fn-entity (get fn-map fn-id)
        is-named (and fn-entity (:name fn-entity))
        ;; Compute the node-id this call-site will carry so we
        ;; can look up its expansion spec under the exact key
        ;; the frontend sent back.
        node-id-for-lookup
        (cond
          (or is-root (nil? source-arg-id)) (str "fn-" fn-id)
          :else (let [caller-tag (if (and source-node-id
                                          (str/starts-with?
                                            source-node-id "fn-"))
                                   (subs source-node-id 3)
                                   (str source-node-id))]
                  (str "fn-" caller-tag "-" source-arg-id)))
        spec (bh/get-effective-spec expansions node-id-for-lookup)
        show-as-leaf (and is-named (not is-root) (bh/spec-trivial? spec))]
    (if show-as-leaf
      ;; Named leaf boundary. Show only THIS fn's own args.
      (let [node-id (bh/add-fn-node state lookups fn-id false source-node-id source-arg-id)]
        (bh/add-ref-edge! state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns)
        (let [raw-own-args (get args-by-fn fn-id [])
              seq-anchors (filterv bnd/sequence-anchor? raw-own-args)
              seq-chain-ids (into #{}
                                  (mapcat (fn [anchor]
                                            (map :id (bnd/walk-anchor-chain anchor arg-map))))
                                  seq-anchors)
              anchor-ids (into #{} (map :id) seq-anchors)
              own-args (filterv (fn [a]
                                  (not (or (contains? anchor-ids (:id a))
                                           (contains? seq-chain-ids (:id a)))))
                                raw-own-args)
              find-migrated
              (fn [arg-id]
                (when parent-bindings
                  (some->> arg-id
                           (get arg-map)
                           :slot-id
                           (get parent-bindings))))
              ;; Dedup by (terminal-slot, rendered-kind).
              seen (atom #{})
              mark-once!
              (fn [k]
                (if (contains? @seen k) false (do (swap! seen conj k) true)))]
          (doseq [arg own-args]
            (let [has-value (true? (:value-present arg))
                  has-ref (some? (:ref-id arg))
                  migrated (when-not (or has-value has-ref)
                             (find-migrated (:id arg)))
                  terminal (bh/terminal-source-of arg-map (:id arg))]
              (cond
                has-value
                (when (mark-once! [terminal :value (:value arg)])
                  (bh/add-arg-value-node state lookups
                                         {:arg-id (:id arg)
                                          :arg-name (bnd/resolve-arg-name arg arg-map)
                                          :value (:value arg)
                                          :arg-type (:type arg)
                                          :slot-id (:slot-id arg)
                                          :binding-id (:binding-id arg)
                                          :item-id (:item-id arg)
                                          :fn-id (:fn-id arg)}
                                         node-id #{fn-id}))

                ;; Skip the migration recursion when the migrated binding's
                ;; ref-id IS the leaf itself (StackOverflow guard — see the
                ;; original letfn comment for the worked example).
                (and migrated (:ref-id migrated)
                     (not= (:ref-id migrated) fn-id))
                (when (mark-once! [terminal :ref (:ref-id migrated)])
                  (process-any-fn ctx (:ref-id migrated) node-id
                                  (or (:arg-name migrated)
                                      (bnd/resolve-arg-name arg arg-map))
                                  false parent-bindings (:id arg)
                                  nil #{fn-id} (bh/child-hof arg-map (:id arg) is-hof)))

                (and migrated (true? (:value-present migrated)))
                (when (mark-once! [terminal :value (:value migrated)])
                  (bh/add-arg-value-node state lookups
                                         (assoc migrated
                                                :arg-id (:id arg)
                                                :arg-name (or (:arg-name migrated)
                                                              (bnd/resolve-arg-name arg arg-map))
                                                :arg-type (:type arg)
                                                :fn-id (:fn-id arg))
                                         node-id #{fn-id}))

                (and (not has-ref) (not (bh/arg-determined? arg-map parent-bound-terminals (:id arg))))
                (when (mark-once! [terminal :unset])
                  (bh/add-unset-arg-node state lookups inverse-source-map
                                         (bnd/resolve-arg-name arg arg-map)
                                         (:type arg) (:id arg) node-id #{fn-id} is-hof))))))
        node-id)
      ;; Normal processing
      (if (bh/spec-trivial? spec)
        (let [bindings (bnd/build-arg-bindings fn-id lookups)
              bindings (if parent-bindings
                         (merge parent-bindings bindings)
                         bindings)]
          (process-fn ctx fn-id fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root source-expanded-fns is-hof))
        ;; Expanded mode - pass parent expansion-root to maintain context
        (process-expanded-fn ctx fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings expansion-root source-expanded-fns is-hof)))))


;; =============================================================================
;; Post-processing — once the process-* walkers have populated `state`,
;; three independent transforms shape the wire output:
;;
;;   1. annotate-optionals — attach :optionalArgs / :hofCapturedArgs
;;      metadata to source nodes so the client renders compact badges
;;      instead of cluttering the graph with placeholder cards.
;;   2. migrate-captured-edges — when an unset arg inside an expanded
;;      HOF was structurally captured, rewrite the caller's edge so it
;;      visually originates from the inside-consumer node (the leaf
;;      that actually reads the value), keeping :target/:argName intact.
;;   3. dedup-overlays — collapse duplicate value-arg-overlays (same
;;      terminal-slot + displayed label) that show up when a level-0
;;      :value migrates into a fn-ref-reached child; keep the deepest
;;      consumer, drop shallower copies and any edges pointing at them.
;;
;; Splitting these out keeps `build-graph-elements` a thin assembler:
;; it constructs ctx, dispatches the walkers, then pipes state through
;; the three transforms.
;; =============================================================================

(defn- annotate-optionals
  "Attach `:optionalArgs` / `:hofCapturedArgs` / `:deepFreeArgs`
   arrays to each node's `:data` from the state-atom's per-node
   maps. Pure transform.

   `:deepFreeArgs` is the union of free-arg names migrated INTO
   this fn from the caller's expanded context — i.e. names this
   card accepts as input but whose actual use-site lives in the
   card's sub-tree (deeper than the visible slot surface). The
   frontend renders these as a `⇣ name` strip below the effects
   strip, telling the reader 'this card receives X as a free arg
   and threads it down into its body'. Populated by the free-arg
   β-inline pass in `process-expanded-fn-impl`."
  [nodes state]
  (mapv (fn [n]
          (let [node-id (get-in n [:data :id])
                optionals (get (:optional-unsets-by-node @state) node-id)
                hof-captured (get (:hof-captured-by-node @state) node-id)
                deep-free (get (:deep-free-by-node @state) node-id)]
            (cond-> n
              (seq optionals)
              (assoc-in [:data :optionalArgs] (vec (distinct optionals)))

              (seq hof-captured)
              (assoc-in [:data :hofCapturedArgs] (vec (distinct hof-captured)))

              (seq deep-free)
              (assoc-in [:data :deepFreeArgs] (vec (sort deep-free))))))
        nodes))


(defn- migrate-captured-edges
  "Rewrite each edge whose `:sourceArgId` resolves in `migrations` to
   originate from the inside-consumer node. Edges keep their
   `:target`/`:argName`; only `:source` and `:id` are rewritten."
  [edges migrations]
  (mapv (fn [e]
          (let [data (:data e)
                sai (:sourceArgId data)
                new-src (when sai (get migrations sai))]
            (if new-src
              (assoc e :data
                     (assoc data
                            :source new-src
                            :id (str "e-cap-" new-src "-" (:target data))))
              e)))
        edges))


(defn- dedup-overlays
  "Collapse duplicate value-arg-overlays. Same logical binding can be
   emitted twice when a level-0 :value migrates into a fn-ref-reached
   child; substitution semantics says it should live at the deepest
   consumer only.

   Groups arg-overlay nodes by `(terminal-source-id, displayed-label)`.
   Within each group with >1 entry, keep the overlay whose source-node-id
   has the longest 'fn-<root>-<arg1>-<arg2>...' chain (depth measured
   by '-' count); drop the others AND any edges targeting them. Groups
   with no terminal (e.g. literals not wired to a primary slot) are
   never deduped.

   Returns `{:nodes deduped :edges deduped}`."
  [nodes edges arg-map]
  (let [arg-node? (fn [n] (= "arg" (get-in n [:data :type])))
        ;; Direct {target-id -> first source-id} index — `edges-by-target`
        ;; only ever read via `(first … :source)`, so collapse one level
        ;; of map+seq lookup into one hash hit per node scored.
        source-by-target (persistent!
                           (reduce (fn [acc e]
                                     (let [t (get-in e [:data :target])]
                                       (if (contains? acc t)
                                         acc
                                         (assoc! acc t (get-in e [:data :source])))))
                                   (transient {})
                                   edges))
        depth-of (fn ^long [^String node-id]
                   (if (nil? node-id)
                     0
                     (loop [i 0 n 0]
                       (if (= i (String/.length node-id))
                         n
                         (recur (inc i)
                                (if (= \- (String/.charAt node-id i)) (inc n) n))))))
        dedupe-groups (->> nodes
                           (filter arg-node?)
                           (group-by (fn [n]
                                       (let [data (:data n)
                                             aid (some-> (:argId data) parse-uuid)
                                             terminal (when aid
                                                        (bh/terminal-source-of arg-map aid))]
                                         [terminal (:label data)]))))
        drop-node-ids (->> dedupe-groups
                           (mapcat (fn [[[terminal _] members]]
                                     (when (and terminal (> (count members) 1))
                                       ;; Single pass over members: build
                                       ;; [id depth] pairs while tracking
                                       ;; max-depth, then collect non-max.
                                       (let [pairs (mapv (fn [n]
                                                           (let [id (get-in n [:data :id])
                                                                 src (get source-by-target id)]
                                                             [id (depth-of src)]))
                                                         members)
                                             max-depth (reduce (fn [^long m [_ ^long d]]
                                                                 (if (> d m) d m))
                                                               Long/MIN_VALUE
                                                               pairs)]
                                         (keep (fn [[id ^long d]]
                                                 (when (not= d max-depth) id))
                                               pairs)))))
                           set)]
    {:nodes (filterv (fn [n]
                       (not (contains? drop-node-ids
                                       (get-in n [:data :id]))))
                     nodes)
     :edges (filterv (fn [e]
                       (not (contains? drop-node-ids
                                       (get-in e [:data :target]))))
                     edges)}))


(defn build-graph-elements
  "Build graph elements (nodes, edges) from selected function.
   Returns {:nodes [...] :edges [...]}"
  [root-fn-id expansions lookups]
  (let [{:keys [fn-map arg-map args-by-fn fn-slots-by-fn]} lookups
        ;; Mutable state collected during traversal lives in ONE atom keyed
        ;; by purpose. Helpers receive `state` via lexical closure and use
        ;; `(swap! state update :nodes conj …)` / `(:nodes @state)` to read.
        ;;
        ;; Keys:
        ;;   :nodes / :edges                  — accumulated graph elements
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

        inverse-source-map (bh/build-inverse-source-map arg-map)
        parent-bound-terminals (bh/make-parent-bound-terminals lookups)]

    ;; Track bindings for EACH expanded function
    ;; Key: expanded-fn-id, Value: {:refs #{ref-ids}, :values #{arg-ids}}
    ;; When processing refs from ancestors of an expanded fn, skip bindings that
    ;; were already shown at the expanded fn itself
    (let [expansion-bindings (atom {})
          ;; The four `process-*` walkers (process-fn / process-expanded-fn /
          ;; process-expanded-fn-impl / process-any-fn) live as top-level
          ;; defns above this fn. All four take `ctx` as first arg and
          ;; recurse through each other.
          ctx {:state state
               :lookups lookups
               :fn-map fn-map
               :arg-map arg-map
               :args-by-fn args-by-fn
               :fn-slots-by-fn fn-slots-by-fn
               :inverse-source-map inverse-source-map
               :parent-bound-terminals parent-bound-terminals
               :expansion-bindings expansion-bindings
               :expansions expansions}]

      ;; Start processing from root - no expansion-root initially, not HOF.
      (process-any-fn ctx root-fn-id nil nil true nil nil nil #{} false)

      ;; Type-row roots (refinement / list / union / variant) have no
      ;; slots and no parents, so the standard pipeline produces only
      ;; an empty card. Surface their referenced types as synthetic
      ;; outgoing edges so the user sees the type's composition in
      ;; the canvas instead of buried inside a chip's title.
      (bh/emit-type-row-internals! state lookups root-fn-id (str "fn-" root-fn-id)))

    ;; Post-process: annotate optional/HOF metadata, migrate captured
    ;; HOF edges to their inside-consumer source, dedup duplicate
    ;; value-overlays. Each transform is a top-level defn — see the
    ;; section header above for the role split.
    (let [final-nodes (annotate-optionals (:nodes @state) state)
          final-edges (migrate-captured-edges
                        (:edges @state)
                        (:captured-edge-migrations @state))]
      (dedup-overlays final-nodes final-edges (:arg-map lookups)))))
