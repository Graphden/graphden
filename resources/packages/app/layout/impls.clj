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

(defn- load-graph-entities-uncached
  [storage]
  (if (instance? VersionedStorage storage)
    (vs/query-all-graph-entities storage)
    {:fns (vec (sp/query-entities storage :fn {}))
     :args (vec (sp/query-entities storage :arg {}))}))


;; Graph entities are loaded ONCE per executor context and cached on
;; `(:graph-cache ctx)`. Layout runs on every hover-preview + click; a
;; full `query-all-graph-entities` takes ~130ms on the current graph, so
;; we cannot re-query per request. The compile-at-startup executor
;; already assumes graph state is built once at startup; layout follows
;; the same model. Invalidation is driven by CRUD mutation defbase's
;; (create/update/delete entity, sequence append/remove) calling
;; `graphden.executor.context/invalidate-graph-cache!` after writing.
(defn- load-graph-entities
  [ctx]
  (let [cache (:graph-cache ctx)]
    (or (and cache @cache)
        (let [data (load-graph-entities-uncached (:storage ctx))]
          (when cache (reset! cache data))
          data))))


(defn- build-lookups
  "Build lookup maps from raw data."
  [{:keys [fns args]}]
  (let [fn-map (into {} (map (fn [f] [(:id f) f]) fns))
        arg-map (into {} (map (fn [a] [(:id a) a]) args))
        args-by-fn (reduce (fn [m a]
                             (if-let [fn-id (:fn-id a)]
                               (update m fn-id (fnil conj []) a)
                               m))
                           {} args)]
    {:fn-map fn-map
     :arg-map arg-map
     :args-by-fn args-by-fn}))


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
  "Resolve arg name by following source chain."
  [arg arg-map]
  (loop [current arg
         depth 0]
    (cond
      (or (nil? current) (> depth 100)) nil
      (:name current) (:name current)
      (:source-id current) (recur (get arg-map (:source-id current)) (inc depth))
      :else nil)))


(defn- fn-sets-args?
  "Check if fn sets any args (has value or ref-id)."
  [fn-id args-by-fn]
  (let [args (get args-by-fn fn-id [])]
    (some (fn [arg]
            (or (some? (:value arg))
                (some? (:ref-id arg))))
          args)))


;; =============================================================================
;; BINDINGS RESOLUTION
;; =============================================================================

(defn- add-bindings-from-fn
  "Add arg bindings from a fn to bindings map."
  [fn-id bindings args-by-fn arg-map]
  (let [args (get args-by-fn fn-id [])]
    (reduce
      (fn [b arg]
        (let [has-value (some? (:value arg))
              has-ref (some? (:ref-id arg))]
          (if (and (or has-value has-ref) (:source-id arg))
            ;; Walk up source chain and bind all ancestors
            (loop [source-id (:source-id arg)
                   b b]
              (if-not source-id
                b
                (let [b (assoc b source-id
                               {:arg-name (resolve-arg-name arg arg-map)
                                :value (:value arg)
                                :ref-id (:ref-id arg)
                                :arg-id (:id arg)})
                      source-arg (get arg-map source-id)]
                  (recur (:source-id source-arg) b))))
            b)))
      bindings
      args)))


(defn- build-chain-bindings
  "Build bindings from ALL fns in the BFS inheritance closure.
   levels is [[self] [parent1 parent2 ...] [gp1 gp2 ...] ...].

   target-depth used to be a limit but with multiple inheritance + diamond,
   bindings can live on any ancestor (one parent binds X while a sibling
   parent has X free). We always walk every level so the lookup finds the
   binding wherever it is. Descendants are visited first; their bindings
   override deeper ancestors via reduce ordering."
  [levels _target-depth args-by-fn arg-map]
  (let [all-fns (mapcat identity levels)]
    (reduce
      (fn [bindings fn-id]
        (add-bindings-from-fn fn-id bindings args-by-fn arg-map))
      {}
      all-fns)))


(defn- build-arg-bindings
  "Build bindings from the fn's OWN args only (level-0, non-expanded mode).
   Ancestor bindings are NOT included — they appear only when the user
   explicitly expands to those depths (via build-chain-bindings in expanded
   mode). This keeps the level-0 display clean: only the fn's own bindings
   are visible, deeper values require explicit navigation."
  [fn-id _fn-map args-by-fn arg-map]
  (add-bindings-from-fn fn-id {} args-by-fn arg-map))


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
   the ordered vector of item arg entities."
  [anchor arg-map]
  (loop [cur (:next-arg-id anchor)
         acc []
         depth 0]
    (cond
      (or (nil? cur) (> depth 10000)) acc
      :else
      (let [item (get arg-map cur)]
        (if (nil? item)
          acc
          (recur (:next-arg-id item) (conj acc item) (inc depth)))))))


(defn- expand-sequence-anchor
  "For a sequence anchor, returns a vector of synthetic arg descriptors —
   one per chain item, labeled `<slot>[idx]`. Anchor itself is not emitted.
   Items with ref-id become :ref entries, items with value become :value."
  [anchor slot-name arg-map]
  (let [items (walk-anchor-chain anchor arg-map)]
    (into []
          (map-indexed
            (fn [idx item]
              (let [lbl (str slot-name "[" idx "]")]
                (cond
                  (some? (:ref-id item))
                  {:type :ref :arg-name lbl
                   :ref-id (:ref-id item) :arg-id (:id item)
                   :is-binding false}

                  (some? (:value item))
                  {:type :value :arg-name lbl
                   :value (:value item) :arg-id (:id item)}

                  :else
                  {:type :unset :arg-name lbl
                   :arg-type (:type item) :arg-id (:id item)}))))
          items)))


(defn- build-graph-elements
  "Build graph elements (nodes, edges) from selected function.
   Returns {:nodes [...] :edges [...]}"
  [root-fn-id expansions lookups]
  (let [{:keys [fn-map arg-map args-by-fn]} lookups
        nodes (atom [])
        edges (atom [])
        added-node-ids (atom #{})
        processed-arg-targets (atom #{})
        ;; Track fully processed fn nodes (with all children processed)
        ;; Different from added-node-ids which only tracks node creation
        ;; This prevents infinite recursion when same fn is reached via different paths
        processed-fn-nodes (atom #{})
        ;; Track expansions currently being processed (cycle protection for
        ;; process-expanded-fn). Cyclic refs (e.g. method-map.value → assoc-handler
        ;; whose binding chain leads back to method-map) would otherwise loop.
        in-progress-expansions (atom #{})
        max-visible-ancestors 4

        get-effective-spec
        ;; Get expansion spec for a fn in its context.
        ;; Lookup order: [expansion-root fn-id], [nil fn-id], fn-id (legacy).
        ;;
        ;; Returns one of:
        ;;   integer N            — legacy: full cascade through BFS depth N
        ;;   {:full-depth N       — depths 1..N fully expanded
        ;;    :partial-fns #{...}}  plus these specific fn-ids at depth N+1
        ;;   nil/0                — no expansion
        (fn [fn-id expansion-root]
          (or (get expansions [expansion-root fn-id])
              (get expansions [nil fn-id])
              (get expansions fn-id)
              0))

        ;; Convert any expansion spec into a set of fn-ids that should be
        ;; "merged into" the focus fn's display (always includes fn-id itself).
        spec->expand-set
        (fn [fn-id spec]
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

        spec-trivial?
        (fn [spec]
          (cond
            (integer? spec) (zero? spec)
            (map? spec) (and (zero? (or (:full-depth spec) 0))
                             (empty? (:partial-fns spec)))
            :else true))

        ;; Walk the source-id chain to the root arg and return its :required
        ;; value. Propagated shadows have :required=nil, so we need to look at
        ;; the base-fn's primary arg to know whether an unbound slot is truly
        ;; optional (`:required false` → caller may leave it blank) or required
        ;; (no explicit `:required false` → caller must supply it).
        arg-is-optional?
        (fn [arg]
          (let [root (loop [a arg, depth 0]
                       (if (or (> depth 200) (not (:source-id a)))
                         a
                         (recur (get arg-map (:source-id a)) (inc depth))))]
            (false? (:required root))))

        ;; Optional unbound args don't deserve their own placeholder node — the
        ;; caller's fn has a sensible fallback baked in. We collect the NAMES
        ;; per displayed node-id here and surface them on the node's `:data`
        ;; so the client can render a compact badge like "+default, +else".
        optional-unsets-by-node (atom {})
        record-optional-unset!
        (fn [node-id arg-name]
          (when (and node-id arg-name)
            (swap! optional-unsets-by-node update node-id
                   (fn [xs] (if xs (conj xs arg-name) [arg-name])))))

        ;; Free args on HOF-reachable nodes (descendants reached through an
        ;; `is-fn=true` arg boundary) are supplied by the HOF invocation, not
        ;; by the graph-level caller. Collect their names here and surface
        ;; them on node data; frontend renders them as a compact badge
        ;; separate from interface free args.
        hof-captured-by-node (atom {})
        record-hof-captured!
        (fn [node-id arg-name]
          (when (and node-id arg-name)
            (swap! hof-captured-by-node update node-id
                   (fn [xs] (if xs (conj xs arg-name) [arg-name])))))

        ;; Does `arg-entity` propagate an `is-fn=true` marker anywhere in its
        ;; source-id chain? Used to decide whether a ref-binding to another
        ;; fn crosses a HOF boundary.
        arg-marks-hof?
        (fn [arg-entity]
          (loop [a arg-entity, depth 0]
            (cond
              (nil? a) false
              (> depth 200) false
              (:is-fn a) true
              :else (recur (get arg-map (:source-id a)) (inc depth)))))

        add-fn-node
        (fn [original-fn-id is-root expansion-root]
          ;; Node ID logic for correct sharing behavior:
          ;;
          ;; 1. Root nodes and expanded roots: use canonical ID (fn-{id})
          ;;    - These are the main nodes user sees and expands
          ;;
          ;; 2. Nodes inside expansion (expansion-root is set, not root):
          ;;    - Use expansion-prefixed ID: fn-{expansion-root}-{id}
          ;;    - This ensures each expansion has its own "copy" of ancestor structure
          ;;    - Example: metrics-route and api-entities-route both expand to route,
          ;;      each gets own method-map with own bindings
          ;;
          ;; 3. Nodes at level 0 (no expansion): use canonical ID
          ;;    - These are direct refs from non-expanded fns
          ;;    - Example: entity-form-handler referenced by multiple routes at level 0
          ;;
          ;; Key insight: expansion-root being nil means we're NOT inside an expansion
          ;; (either this is the expansion root itself, or it's a level-0 fn)
          (let [use-expansion-prefix (and expansion-root (not is-root))
                ;; Use underscore as separator between expansion-root and fn-id
                ;; This allows parsing: fn-{uuid} or fn-{uuid1}_{uuid2}
                node-id (if use-expansion-prefix
                          (str "fn-" expansion-root "_" original-fn-id)
                          (str "fn-" original-fn-id))]
            (when-not (contains? @added-node-ids node-id)
              (swap! added-node-ids conj node-id)
              (let [levels (get-inheritance-levels original-fn-id fn-map)
                    ;; Name of a fn for label rendering. For level 0 (`top-level?`)
                    ;; we do NOT substitute the nearest named ancestor: if the fn
                    ;; is anonymous, its own "name slot" stays empty so the black
                    ;; header bar visually signals "no own name" and the real
                    ;; parent chain keeps rendering on the rows below.
                    ;; For levels >= 1 we preserve the old substitution so each
                    ;; ancestor row shows something meaningful even if the fid
                    ;; at that BFS level happens to be anonymous.
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
                (swap! nodes conj
                       {:data {:id node-id
                               :label label
                               :type "fn"
                               :isRoot is-root
                               :originalFnId (str original-fn-id)}})))
            node-id))

        ;; Compute terminal source-id of an arg (walk source chain to root)
        terminal-source-of
        (fn [arg-id]
          (loop [cur (get arg-map arg-id)]
            (if (and cur (:source-id cur))
              (if-let [src (get arg-map (:source-id cur))]
                (recur src)
                (:id cur))
              (or (:id cur) arg-id))))

        ;; Compute edge label showing the rename history through the arg's
        ;; inheritance chain — but only for fns that are "expanded" at the
        ;; source node (visible structurally). Without expansions only the
        ;; source fn's own arg is considered, giving a single name.
        ;;
        ;; expanded-fns: set of fn-ids visible structurally at the source node.
        ;;   - For leaf / non-expanded source: just #{source-fn-id}
        ;;   - For expanded source: the expand-set
        ;;
        ;; Returns:
        ;;   - single name string (no rename detected through expanded chain)
        ;;   - multi-line "name1 (fn1, fn2)\nname2 (fn3, fn4)" (renames visible)
        compute-edge-label
        (fn [arg-id source-node-id expanded-fns]
          (when arg-id
            (let [source-chain (loop [acc [], cur (get arg-map arg-id)]
                                 (if cur
                                   (recur (conj acc cur)
                                          (some-> (:source-id cur) arg-map))
                                   acc))
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
                                       :fns (vec (keep :fn grp))})))]
              (cond
                (empty? groups) nil
                ;; Single arg name across every visible ancestor — no rename
                ;; along the chain, so no fn-name disambiguation is needed.
                ;; (Matches the docstring contract: a single-group label is
                ;; just `name`; only multi-group labels carry fn-names.)
                (= 1 (count groups)) (:name (first groups))
                :else (->> groups
                           (map (fn [{:keys [name fns]}]
                                  (if (seq fns)
                                    (str name " (" (str/join ", " fns) ")")
                                    name)))
                           (str/join "\n"))))))

        add-arg-value-node
        (fn [arg-name value arg-id source-node-id expanded-fns]
          ;; Node ID must include source-node-id to ensure uniqueness per expansion
          ;; Different fns expanding to same ancestor should get separate arg nodes
          (let [node-id (str "arg-" source-node-id "-" arg-id)
                edge-id (str "e-val-" source-node-id "-" arg-id)]
            (when-not (contains? @added-node-ids node-id)
              (swap! added-node-ids conj node-id)
              (let [display-value (truncate-label (json/generate-string value) 20)]
                (swap! nodes conj
                       {:data {:id node-id
                               :label display-value
                               :type "arg"}}))
              (swap! edges conj
                     {:data {:id edge-id
                             :source source-node-id
                             :target node-id
                             :argName (or (compute-edge-label arg-id source-node-id expanded-fns)
                                          (when arg-name (name arg-name)))}}))
            node-id))

        add-unset-arg-node
        (fn add-unset-arg-node
          ;; 4-arity kept for back-compat; 6-arity adds `is-hof` flag.
          ([arg-name arg-type arg-id source-node-id expanded-fns]
           (add-unset-arg-node arg-name arg-type arg-id source-node-id expanded-fns false))
          ([arg-name arg-type arg-id source-node-id expanded-fns is-hof]
           ;; Three routing cases, in priority order:
           ;;
           ;;   1. Optional (root `:required=false`, e.g. `:get.default`) —
           ;;      compact `?name` badge via `:optionalArgs`. Sane fallback
           ;;      exists, not part of the interface.
           ;;   2. HOF-captured (the node sits under an `is-fn=true` ref
           ;;      boundary) — compact `λname` badge via `:hofCapturedArgs`.
           ;;      The enclosing HOF invocation will supply the value.
           ;;   3. Otherwise — visible dashed placeholder node. This IS the
           ;;      caller's interface; the caller must fill it.
           (let [arg-rec (get arg-map arg-id)
                 optional? (arg-is-optional? arg-rec)
                 displayed-name (or (compute-edge-label arg-id source-node-id expanded-fns)
                                    (when arg-name (name arg-name)))]
             (cond
               optional?
               (record-optional-unset! source-node-id displayed-name)

               is-hof
               (record-hof-captured! source-node-id displayed-name)

               :else
               (let [node-id (str "unset-" source-node-id "-" arg-id)
                     edge-id (str "e-unset-" source-node-id "-" arg-id)]
                 (when-not (contains? @added-node-ids node-id)
                   (swap! added-node-ids conj node-id)
                   (swap! nodes conj
                          {:data {:id node-id
                                  :label (if arg-type (name arg-type) "any")
                                  :type "fn"
                                  :isPlaceholder true}})
                   (swap! edges conj
                          {:data {:id edge-id
                                  :source source-node-id
                                  :target node-id
                                  :argName displayed-name
                                  :isUnset true}})))))))

        ;; For each fn, the set of terminal-source-ids bound by its parent
        ;; inheritance closure (including itself). Bindings in ref-id targets
        ;; are NOT included — those are scoped to the ref's call context.
        parent-bound-terminals
        (let [cache (atom {})]
          (fn [fn-id]
            (or (get @cache fn-id)
                (let [visited (atom #{})
                      terms (atom #{})
                      walk (fn walk
                             [fid]
                             (when (and fid (not (contains? @visited fid)))
                               (swap! visited conj fid)
                               (doseq [a (get args-by-fn fid [])]
                                 (when (or (some? (:value a)) (some? (:ref-id a)))
                                   (swap! terms conj (terminal-source-of (:id a)))))
                               (when-let [f (get fn-map fid)]
                                 (doseq [pid (:parent-ids f)]
                                   (walk pid)))))]
                  (walk fn-id)
                  (let [result @terms]
                    (swap! cache assoc fn-id result)
                    result)))))

        ;; Check if arg X is "determined" — its terminal is bound by some fn
        ;; reachable via the source chain owners' parent inheritance.
        ;;
        ;; Walks source chain of X; for each arg in the chain, takes the
        ;; OWNING fn's parent-bound-terminals set; checks if X's terminal is in it.
        ;;
        ;; This correctly handles MI-propagated args (e.g. text-error-router.headers
        ;; → text-not-found-response in chain → text-content-type binds headers via
        ;; MI parent) WITHOUT falsely including ref-target bindings (e.g. method-map
        ;; references assoc-handler via 'value' ref — assoc-handler.key binding is
        ;; scoped to assoc-handler's own call, not method-map.key).
        arg-determined?
        (fn [arg-id]
          (let [terminal (terminal-source-of arg-id)]
            (loop [cur (get arg-map arg-id)]
              (if (nil? cur)
                false
                (let [fid (:fn-id cur)]
                  (if (and fid (contains? (parent-bound-terminals fid) terminal))
                    true
                    (recur (get arg-map (:source-id cur)))))))))

        ;; Compute sources covered by child refs of a fn
        ;; Returns set of source-ids that child refs will handle (for binding deduplication)
        child-covered-sources-for-fn
        ;; expansion-root-chain: set of fn-ids in expansion root's inheritance chain
        ;; Used to exclude source-ids that point to args owned by shared ancestors
        (fn [fn-id & {:keys [expansion-root-chain] :or {expansion-root-chain #{}}}]
          (let [fn-args (get args-by-fn fn-id [])
                child-ref-ids (keep :ref-id fn-args)
                ;; Collect arg-ids that belong to fns in expansion-root-chain
                ;; These are "shared ancestor args" that shouldn't be treated as covered
                expansion-chain-arg-ids (when (seq expansion-root-chain)
                                          (set (mapcat (fn [eid]
                                                         (map :id (get args-by-fn eid [])))
                                                       expansion-root-chain)))]
            (set (mapcat (fn [child-ref-id]
                           (let [child-chain (get-inheritance-chain child-ref-id fn-map)]
                             (mapcat (fn [child-fn-id]
                                       ;; Collect source-ids from args, but exclude those pointing
                                       ;; to shared ancestor args (they're not "owned" by the child)
                                       (keep (fn [arg]
                                               (when-let [sid (:source-id arg)]
                                                 (when-not (and expansion-chain-arg-ids
                                                                (contains? expansion-chain-arg-ids sid))
                                                   sid)))
                                             (get args-by-fn child-fn-id [])))
                                     child-chain)))
                         child-ref-ids))))

        collect-fn-args
        ;; is-structural: true when collecting args for a structural node inside expansion
        ;; For structural nodes, unbound refs (no binding) should be shown as unset, not as direct refs
        ;; This prevents false sharing with other fns that happen to have the same ancestor ref-id
        ;; displayed-ref-arg-ids: set of arg-ids belonging to fns that are displayed as nodes in current expansion
        ;;   If a binding's source chain leads to one of these arg-ids, the binding should be shown there, not here
        (fn [fn-id bindings & {:keys [is-structural displayed-ref-arg-ids expansion-root-chain]
                               :or {is-structural false displayed-ref-arg-ids #{} expansion-root-chain #{}}}]
          (let [;; Inheritance ancestry of the fn we're rendering. Used to
                ;; filter out sibling bindings that would otherwise flow in
                ;; through a shared base-fn's source-id chain (e.g. three
                ;; other `:get` instances binding their own `:default` — none
                ;; of them apply to THIS `:get` instance unless they sit in
                ;; its ancestry).
                fn-ancestry (set (get-inheritance-chain fn-id fn-map))
                ;; Walk the target arg's source-id chain to its terminal —
                ;; the PRIMARY arg (source-id=nil) on whatever base fn owns
                ;; the slot. That base fn is what decides whether a binding
                ;; propagating from above is truly landing on this fn's
                ;; own inherited slot (`:invoke.func` reaching `router-result`
                ;; which inherits from `:invoke`) or just passing through a
                ;; ref-chain relay (`:invoke.func` reaching `router-response-body`
                ;; via a `:coll → router-result` hop — not an inheritance parent).
                terminal-primary-arg (fn [start-id]
                                       (loop [sid start-id]
                                         (let [a (get arg-map sid)]
                                           (if (:source-id a)
                                             (recur (:source-id a))
                                             a))))
                binding-reaches? (fn [b target-id]
                                   (let [barg (some-> (:arg-id b) arg-map)
                                         terminal (terminal-primary-arg target-id)]
                                     (and (contains? fn-ancestry (:fn-id terminal))
                                          (loop [sid (:source-id barg)]
                                            (cond
                                              (nil? sid) false
                                              (or (= sid target-id)
                                                  (= sid (:id terminal))) true
                                              :else (recur (:source-id (get arg-map sid))))))))
                binding-applies? (fn [b current-arg-id]
                                   (let [barg (some-> (:arg-id b) arg-map)]
                                     (or (nil? barg)
                                         (contains? fn-ancestry (:fn-id barg))
                                         (binding-reaches? b current-arg-id))))
                ;; Does the arg's source-id chain pass through any binding
                ;; key? If yes, the upstream caller already shows the
                ;; binding, so this downstream arg should not emit a free
                ;; placeholder. Used to suppress shadows like `:func` on
                ;; `router-response-*` when `_app-path-gated-response`
                ;; already binds it.
                bound-by-chain? (fn [arg]
                                  (loop [sid (:source-id arg)]
                                    (when sid
                                      (if (contains? bindings sid)
                                        true
                                        (recur (:source-id (get arg-map sid)))))))
                raw-args (get args-by-fn fn-id [])
                ;; Sequence handling: find anchors (type=:sequence) and their chain items.
                ;; Anchor + items get EXCLUDED from normal scalar processing; instead the
                ;; chain expands to N synthetic slot entries (labeled `<slot>[idx]`).
                sequence-anchors (filterv #(= :sequence (:type %)) raw-args)
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
                ;; Collect sources that child refs will handle (static)
                ;; This catches direct children of this fn
                child-sources (child-covered-sources-for-fn fn-id :expansion-root-chain expansion-root-chain)
                ;; Combined set: both static child sources AND dynamically displayed ref arg-ids
                all-covered-sources (into child-sources displayed-ref-arg-ids)
                ;; Check if a binding's source chain leads to covered sources
                binding-goes-to-child?
                (fn [binding-key]
                  (loop [sid binding-key]
                    (when sid
                      (if (contains? all-covered-sources sid)
                        true
                        (let [src-arg (get arg-map sid)]
                          (recur (:source-id src-arg)))))))
                all-args (mapv (fn [arg]
                                 (let [arg-name (resolve-arg-name arg arg-map)
                                       has-value (some? (:value arg))
                                       has-ref (some? (:ref-id arg))
                                       ;; Check if this fn DEFINES the ref (source has no ref)
                                       ;; vs INHERITS the ref (source also has ref)
                                       ;; Only structural nodes with INHERITED unbound refs should be unset
                                       source-has-ref (when-let [sid (:source-id arg)]
                                                        (let [source-arg (get arg-map sid)]
                                                          (some? (:ref-id source-arg))))
                                       defines-own-ref (and has-ref (not source-has-ref))
                                       ;; Check for binding - applies to UNSET args
                                       ;; For structural nodes, also check binding for args with ref
                                       binding-key (or (:id arg) (:source-id arg))
                                       ;; Walk the FULL source-id chain to find a binding.
                                       ;; With multiple inheritance the bound arg may be on a
                                       ;; sibling parent, so the binding ends up keyed by an
                                       ;; ancestor source-id deep in the chain. Skip bindings
                                       ;; that don't belong to this fn's ancestry — those come
                                       ;; from siblings sharing the same base-fn's source-id
                                       ;; (e.g. router-response-body.default piggybacking onto
                                       ;; ring-method-kw via `:get.default`).
                                       raw-binding (loop [sid (:id arg)]
                                                     (if-let [b (get bindings sid)]
                                                       (if (binding-applies? b (:id arg))
                                                         b
                                                         (when-let [src-arg (get arg-map sid)]
                                                           (when-let [next-sid (:source-id src-arg)]
                                                             (recur next-sid))))
                                                       (when-let [src-arg (get arg-map sid)]
                                                         (when-let [next-sid (:source-id src-arg)]
                                                           (recur next-sid)))))
                                       ;; If binding goes to a child ref, don't use it here
                                       binding (when (and raw-binding
                                                          (not (binding-goes-to-child? binding-key)))
                                                 raw-binding)]
                                   (cond
                                     ;; CRITICAL: Any node with ref DEFINED HERE takes precedence
                                     ;; over any binding! The arg's own ref-id is what matters, not
                                     ;; an ancestor binding that has a DIFFERENT ref-id.
                                     ;; Example: list-10.coll -> list-10-9 (defines own ref)
                                     ;;   binding from list-11.coll -> list-10 should NOT override
                                     ;; Example: editor-scripts.item1 -> dagre-script (defines own ref)
                                     ;;   binding from editor-routes.item1 -> favicon-route should NOT override
                                     ;; NOTE: This applies to BOTH structural AND canonical nodes!
                                     ;; Own ref takes precedence, OR binding matches own ref (structural)
                                     (or (and has-ref defines-own-ref)
                                         (and binding (:ref-id binding) (= (:ref-id binding) (:ref-id arg))))
                                     {:type :ref :arg-name arg-name
                                      :ref-id (:ref-id arg) :arg-id (:id arg)
                                      :is-binding false}

                                     ;; Binding ref: overrides existing ref OR fills unset arg
                                     ;; Mark with :is-binding true so process-fn uses canonical mode
                                     ;; CRITICAL: arg with value should NOT be overridden by binding ref
                                     (and binding (:ref-id binding)
                                          (or (and has-ref (not= (:ref-id binding) (:ref-id arg)))
                                              (and (not has-ref) (not has-value))))
                                     {:type :ref :arg-name (:arg-name binding)
                                      :ref-id (:ref-id binding) :arg-id (:arg-id binding)
                                      :is-binding true}

                                     (and binding (some? (:value binding)))
                                     {:type :value :arg-name (:arg-name binding)
                                      :value (:value binding) :arg-id (:arg-id binding)}

                                     ;; Direct ref - non-structural always shows as ref
                                     (and has-ref (not is-structural))
                                     {:type :ref :arg-name arg-name
                                      :ref-id (:ref-id arg) :arg-id (:id arg)
                                      :is-binding false}

                                     ;; Structural node with INHERITED unbound ref - treat as unset
                                     ;; Example: if parent has ref to X and child inherits it without binding
                                     (and has-ref is-structural (not defines-own-ref))
                                     {:type :unset :arg-name arg-name
                                      :arg-type (:type arg) :arg-id (:id arg)}

                                     has-value
                                     {:type :value :arg-name arg-name
                                      :value (:value arg) :arg-id (:id arg)}

                                     ;; Either (a) binding existed but went to child (treat as
                                     ;; inlined at point of use), or (b) arg unbound here but an
                                     ;; upstream in its source chain has a binding. Both cases
                                     ;; hide this arg from the rendered subtree.
                                     (or raw-binding (bound-by-chain? arg))
                                     nil

                                     :else
                                     {:type :unset :arg-name arg-name
                                      :arg-type (:type arg) :arg-id (:id arg)})))
                               args)
                ;; Include ref-bindings that live on inheritance ancestors
                ;; but have no corresponding own arg on this fn. Without this
                ;; pass, bindings like `:coll :router-result` on
                ;; `router-result-field` never surface as edges from
                ;; `router-response-body` (which only stores its overrides
                ;; `:key`/`:default`), so the node `router-result` never
                ;; appears and propagated args like `:func` have nowhere to
                ;; migrate to. We walk every ancestor, collect bound ref args
                ;; whose slot (terminal primary arg-id) isn't already covered
                ;; by this fn's own args, and emit each as a synthetic :ref.
                own-slot-terminals (into #{}
                                         (keep (fn [a]
                                                 (:id (terminal-primary-arg (:id a)))))
                                         raw-args)
                inherited-ref-args
                (if-not is-structural
                  ;; Level-0 renders show only OWN bindings of the displayed
                  ;; fn. Ancestor bindings become visible as the user expands
                  ;; to that ancestor level. Inside a structural expansion
                  ;; context (is-structural=true) we do surface ancestor
                  ;; ref-bindings — e.g. router-response-body's ancestor
                  ;; router-result-field binds `:coll :router-result`, and
                  ;; without this pass router-result would never become a
                  ;; node so propagated args like `:func` have no consumption
                  ;; point to migrate to.
                  []
                  (let [ancestors (rest (get-inheritance-chain fn-id fn-map))
                        seen-terminals (atom own-slot-terminals)]
                    (vec
                      (keep
                        (fn [a]
                          (when (:ref-id a)
                            (let [terminal-id (:id (terminal-primary-arg (:id a)))]
                              (when-not (contains? @seen-terminals terminal-id)
                                (swap! seen-terminals conj terminal-id)
                                {:type :ref
                                 :arg-name (resolve-arg-name a arg-map)
                                 :ref-id (:ref-id a)
                                 :arg-id (:id a)
                                 :is-binding false}))))
                        (mapcat #(get args-by-fn % []) ancestors)))))
                ;; Dedup by terminal primary arg-id. Propagation creates
                ;; multiple shadows per free arg (one per ref-path it flowed
                ;; through), and rendering each as a separate placeholder
                ;; turns a single `:request` free arg into 5+ identical
                ;; edges to the `any` placeholder. Collapse shadows that
                ;; share a terminal primary slot into one.
                deduped-args
                (let [seen (atom #{})]
                  (into []
                        (keep (fn [arg]
                                (let [terminal-id (:id (terminal-primary-arg (:arg-id arg)))]
                                  (when-not (and terminal-id (contains? @seen terminal-id))
                                    (when terminal-id (swap! seen conj terminal-id))
                                    arg))))
                        (into (filterv some? all-args) inherited-ref-args)))
                ;; Sort args: refs first (fn type), then values (fixed), then unset (free)
                type-order {:ref 0 :value 1 :unset 2}
                sorted-args (sort-by #(get type-order (:type %) 3) deduped-args)
                ;; Append sequence slot entries (expanded from anchor chains).
                ;; They come after the scalar args so the scalar order is unchanged.
                final-args (into sorted-args sequence-slot-entries)]
            final-args))

        ;; Collect args from a set of expand-fns with proper ordering:
        ;; For each fn in expand-set (descendants first by BFS order):
        ;;   - refs first, then values, then unset
        ;; Args covered by earlier fns are skipped
        ;; Returns args with :from-ancestor flag indicating if arg came from
        ;; an fn at BFS depth > 0
        ;;
        ;; Key insight: when a ref-fn is shown, value args that bind TO that ref-fn's args
        ;; should not be shown as direct children - they become part of the ref-fn's display
        collect-expanded-args
        (fn [levels expand-set bindings]
          ;; Order active fns by BFS depth (descendants first)
          (let [active-fns (filterv expand-set (mapcat identity levels))
                ;; Full chain (all reachable ancestors) — used as set for excluding
                ;; ref-fn-arg-ids (so shared ancestors don't get filtered)
                full-chain-fns (set (mapcat identity levels))
                covered-sources (atom #{})
                ;; First pass: collect all refs to know which fns will be displayed
                ref-fn-ids (atom #{})
                _ (doseq [fn-id active-fns]
                    (let [args (get args-by-fn fn-id [])]
                      (doseq [arg args]
                        (when-let [ref-id (:ref-id arg)]
                          (swap! ref-fn-ids conj ref-id))
                        ;; Also check bindings
                        (when-let [binding (get bindings (:id arg))]
                          (when-let [ref-id (:ref-id binding)]
                            (swap! ref-fn-ids conj ref-id))))))
                ;; Collect all arg-ids that belong to displayed ref-fns. Walk
                ;; BOTH inheritance ancestry AND the transitive ref-target
                ;; closure — propagated free-arg shadows on the current fn
                ;; often have their source-id pointing DEEP into a ref-chain
                ;; (e.g. `:request` on `_app-path-gated-response` sources to
                ;; `router-response-body`, which `router-ring-response` reaches
                ;; through its own refs). The closure catches those so shadows
                ;; whose source chain flows into any displayed sub-graph get
                ;; suppressed at the parent — they render on the ref-target
                ;; where the slot is actually introduced.
                ;; EXCLUDE args from fns in the expansion root's inheritance
                ;; chain so shared ancestors (e.g. `conj-any` shared by
                ;; list-11 and list-10) don't cause items to be filtered as
                ;; "belonging inside ref-fn".
                expansion-chain-fns full-chain-fns
                ref-closure-fns (let [seen (atom #{})]
                                  (letfn [(walk
                                            [fn-id]
                                            (when-not (contains? @seen fn-id)
                                              (swap! seen conj fn-id)
                                              (doseq [anc (get-inheritance-chain fn-id fn-map)]
                                                (swap! seen conj anc)
                                                (doseq [a (get args-by-fn anc [])]
                                                  (when-let [r (:ref-id a)]
                                                    (walk r))
                                                  ;; Sequence anchors: walk each
                                                  ;; item's ref-id too. Without
                                                  ;; this, `:entries` on
                                                  ;; `internal-request` stops the
                                                  ;; closure at the anchor, and
                                                  ;; `ring-request` / `ring-uri`
                                                  ;; stay outside it.
                                                  (when (= :sequence (:type a))
                                                    (doseq [item (walk-anchor-chain a arg-map)]
                                                      (when-let [r (:ref-id item)]
                                                        (walk r))))))))]
                                    (doseq [rid @ref-fn-ids]
                                      (walk rid)))
                                  @seen)
                ref-fn-arg-ids (atom #{})
                _ (doseq [rfn-id ref-closure-fns]
                    (when-not (contains? expansion-chain-fns rfn-id)
                      (let [ref-args (get args-by-fn rfn-id [])]
                        (doseq [ra ref-args]
                          (swap! ref-fn-arg-ids conj (:id ra))))))
                result (atom [])
                chain-level (atom 0)
                ;; Helper: check if any arg in the source chain is in ref-fn-arg-ids
                source-chain-binds-to-ref-fn
                (fn [start-arg-id]
                  (loop [sid start-arg-id]
                    (when sid
                      (if (contains? @ref-fn-arg-ids sid)
                        true
                        (let [src-arg (get arg-map sid)]
                          (recur (:source-id src-arg)))))))]
            ;; Second pass: collect args, skipping those that bind to ref-fn args
            (doseq [fn-id active-fns]
              (let [raw-args (get args-by-fn fn-id [])
                    ;; Exclude sequence anchors and their chain items from the
                    ;; scalar classifier. Anchors are bindings, not free slots,
                    ;; and their items have no semantic identity outside the
                    ;; anchor's chain.
                    anchor-ids (into #{}
                                     (comp (filter #(= :sequence (:type %)))
                                           (map :id))
                                     raw-args)
                    chain-ids (into #{}
                                    (mapcat (fn [a]
                                              (when (= :sequence (:type a))
                                                (map :id (walk-anchor-chain a arg-map)))))
                                    raw-args)
                    args (filterv (fn [a]
                                    (not (or (contains? anchor-ids (:id a))
                                             (contains? chain-ids (:id a)))))
                                  raw-args)
                    current-level @chain-level
                    fn-refs (atom [])
                    fn-values (atom [])
                    fn-unsets (atom [])
                    ;; Self-reference set: bindings whose ref-id targets a fn
                    ;; in the expand-set are self-cycles (parent calls this fn,
                    ;; and propagation tries to feed it back). Treat as no binding.
                    self-ref-targets expand-set]
                (let [;; Inheritance chain of the fn whose args we're iterating;
                      ;; used to verify that a candidate binding isn't coming
                      ;; from a sibling instance of the same base-fn. Two fns
                      ;; like `router-response-body` and `ring-method-kw` both
                      ;; inherit from `:get`, so their `:default` args share a
                      ;; root source-id. Without this check, router-response-
                      ;; body's `:default ""` binding (also `:default {}` /
                      ;; `:default 200` from the headers/status siblings) would
                      ;; falsely surface on ring-method-kw's display.
                      fn-ancestry (set (get-inheritance-chain fn-id fn-map))
                      terminal-primary-arg (fn [start-id]
                                             (loop [sid start-id]
                                               (let [a (get arg-map sid)]
                                                 (if (:source-id a)
                                                   (recur (:source-id a))
                                                   a))))
                      binding-reaches? (fn [b target-id]
                                         (let [barg (some-> (:arg-id b) arg-map)
                                               terminal (terminal-primary-arg target-id)]
                                           (and (contains? fn-ancestry (:fn-id terminal))
                                                (loop [sid (:source-id barg)]
                                                  (cond
                                                    (nil? sid) false
                                                    (or (= sid target-id)
                                                        (= sid (:id terminal))) true
                                                    :else (recur (:source-id (get arg-map sid))))))))
                      binding-applies? (fn [b current-arg-id]
                                         (let [barg (some-> (:arg-id b) arg-map)]
                                           (or (nil? barg)
                                               (contains? fn-ancestry (:fn-id barg))
                                               (binding-reaches? b current-arg-id))))
                      ;; Same logic as in collect-fn-args: hide unbound args
                      ;; whose source chain passes through a bound arg-id.
                      ;; The binding is shown upstream; a downstream shadow
                      ;; would just add a ghost placeholder edge.
                      bound-by-chain? (fn [arg]
                                        (loop [sid (:source-id arg)]
                                          (when sid
                                            (if (contains? bindings sid)
                                              true
                                              (recur (:source-id (get arg-map sid)))))))]
                  (doseq [arg args]
                    (let [arg-id (:id arg)
                          source-id (or (:source-id arg) arg-id)
                          already-covered (contains? @covered-sources source-id)
                          has-value (some? (:value arg))
                          has-ref (some? (:ref-id arg))
                          ;; Walk the FULL source-id chain to find a binding.
                          ;; Skip bindings whose ref-id is a self-reference
                          ;; (target fn is in the expand-set being processed).
                          ;; Also skip bindings that live on a sibling fn (same
                          ;; base-fn but not in our inheritance chain) — see
                          ;; `binding-applies?` docstring above.
                          binding (loop [sid arg-id]
                                    (if-let [b (get bindings sid)]
                                      (if (or (and (:ref-id b)
                                                   (contains? self-ref-targets (:ref-id b)))
                                              (not (binding-applies? b arg-id)))
                                        ;; Not applicable here — keep walking up.
                                        (when-let [src-arg (get arg-map sid)]
                                          (when-let [next-sid (:source-id src-arg)]
                                            (recur next-sid)))
                                        b)
                                      (when-let [src-arg (get arg-map sid)]
                                        (when-let [next-sid (:source-id src-arg)]
                                          (recur next-sid)))))
                          ;; Check if ANY arg in the source chain is an arg of a displayed ref-fn
                          ;; This applies to BOTH value and ref args - if the arg's source chain
                          ;; leads to an arg inside a displayed ref-fn, this arg is a binding for
                          ;; that ref-fn's arg and should be shown inside, not as direct child
                          binds-to-ref-fn (and (:source-id arg)
                                               (source-chain-binds-to-ref-fn (:source-id arg)))
                          ;; Skip if: already covered OR (has binding AND binds to ref-fn)
                          ;; The binding will appear on the ref-fn instead
                          skip-binding-to-ref (and binding binds-to-ref-fn)]
                      (when (and (not already-covered) (not skip-binding-to-ref))
                        ;; Mark as covered (including all sources in chain)
                        (loop [sid source-id]
                          (when sid
                            (swap! covered-sources conj sid)
                            (let [source-arg (get arg-map sid)]
                              (recur (:source-id source-arg)))))
                        ;; Classify the arg - add :from-ancestor flag
                        (let [arg-name (resolve-arg-name arg arg-map)
                              from-ancestor (pos? current-level)]
                          (cond
                            binding
                            (cond
                              (:ref-id binding)
                              (swap! fn-refs conj {:type :ref :arg-name (:arg-name binding)
                                                   :ref-id (:ref-id binding) :arg-id (:arg-id binding)
                                                   :from-ancestor from-ancestor})
                              (some? (:value binding))
                              (swap! fn-values conj {:type :value :arg-name (:arg-name binding)
                                                     :value (:value binding) :arg-id (:arg-id binding)
                                                     :from-ancestor from-ancestor}))

                            has-ref
                            (swap! fn-refs conj {:type :ref :arg-name arg-name
                                                 :ref-id (:ref-id arg) :arg-id arg-id
                                                 :from-ancestor from-ancestor})

                            has-value
                            (swap! fn-values conj {:type :value :arg-name arg-name
                                                   :value (:value arg) :arg-id arg-id
                                                   :from-ancestor from-ancestor})

                            ;; Arg unbound here but bound somewhere upstream in the
                            ;; source chain — shown there, skip emitting a ghost.
                            (bound-by-chain? arg)
                            nil

                            ;; Propagated free-arg shadow whose source-id chain
                            ;; reaches into a displayed ref-fn (or anything in
                            ;; its ref-closure). The slot is introduced by that
                            ;; ref-target — render it there, not as a duplicate
                            ;; placeholder on the parent.
                            binds-to-ref-fn
                            nil

                            :else
                            (swap! fn-unsets conj {:type :unset :arg-name arg-name
                                                   :arg-type (:type arg) :arg-id arg-id
                                                   :from-ancestor from-ancestor})))))))
                ;; Append sequence-slot entries for each anchor on this fn —
                ;; one entry per item, labelled `<slot>[idx]`, classified by
                ;; the item's own binding (:ref-id → :ref, :value → :value,
                ;; nothing → :unset). Matches the root path's behaviour in
                ;; `collect-fn-args` so `:items`-style slots render as N edges
                ;; regardless of whether the fn is root or expanded.
                (let [raw-args-of-fn (get args-by-fn fn-id [])
                      anchors (filter #(= :sequence (:type %)) raw-args-of-fn)
                      from-ancestor (pos? current-level)]
                  (doseq [anchor anchors
                          :let [slot-name (or (resolve-arg-name anchor arg-map) "items")]
                          entry (expand-sequence-anchor anchor slot-name arg-map)]
                    (swap! result conj (assoc entry :from-ancestor from-ancestor))))
                ;; Add this fn's args in order: refs, values, unsets
                (doseq [a @fn-refs] (swap! result conj a))
                (doseq [a @fn-values] (swap! result conj a))
                (doseq [a @fn-unsets] (swap! result conj a))
                (swap! chain-level inc)))
            @result))]

    ;; Track bindings for EACH expanded function
    ;; Key: expanded-fn-id, Value: {:refs #{ref-ids}, :values #{arg-ids}}
    ;; When processing refs from ancestors of an expanded fn, skip bindings that
    ;; were already shown at the expanded fn itself
    (let [expansion-bindings (atom {})]

      ;; Declare process-any-fn before using it
      ;; expansion-root: the original-fn-id of the expanded function we're inside (nil if not in expansion)
      (letfn [(process-fn
                [original-fn-id display-fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root source-expanded-fns is-hof]
                (let [node-id (add-fn-node original-fn-id is-root expansion-root)
                      ;; Key for tracking fully processed nodes - includes expansion context
                      process-key (str node-id "-" (hash bindings))]
                  ;; Add edge from parent ALWAYS (even if node already processed)
                  (when (and source-node-id edge-arg-name)
                    (let [edge-id (str "e-ref-" source-node-id "-" node-id)
                          ;; Include source-node-id in key to allow same arg->target from different sources
                          arg-target-key (when source-arg-id
                                           (str source-node-id "-" source-arg-id "->" node-id))
                          is-duplicate (and arg-target-key
                                            (contains? @processed-arg-targets arg-target-key))]
                      (when (and (not (contains? @added-node-ids edge-id))
                                 (not is-duplicate))
                        (swap! added-node-ids conj edge-id)
                        (when arg-target-key
                          (swap! processed-arg-targets conj arg-target-key))
                        (swap! edges conj
                               {:data {:id edge-id
                                       :source source-node-id
                                       :target node-id
                                       :argName (or (compute-edge-label source-arg-id source-node-id source-expanded-fns)
                                                    (when edge-arg-name (name edge-arg-name)))}}))))

                  ;; Only process children if this node wasn't already fully processed
                  ;; This prevents infinite recursion when same fn is reached via different paths
                  (when-not (contains? @processed-fn-nodes process-key)
                    (swap! processed-fn-nodes conj process-key)
                    ;; Process children
                    ;; When inside an expansion context (expansion-root is set),
                    ;; we WANT to show bindings - they should appear here, not at the root
                    ;; Mark as structural when inside expansion - prevents false refs to other fns
                    ;;
                    ;; Compute displayed-ref-arg-ids: arg-ids of fns that will be displayed as child nodes
                    ;; This is used to hide bindings that will appear on child nodes instead
                    (let [displayed-ref-arg-ids
                          (when (some? expansion-root)
                            ;; Collect all ref-ids from this fn's args and bindings
                            (let [fn-args (get args-by-fn display-fn-id [])
                                  ref-fn-ids (set (concat
                                                    (keep :ref-id fn-args)
                                                    (keep (fn [arg]
                                                            (when-let [b (get bindings (:id arg))]
                                                              (:ref-id b)))
                                                          fn-args)))
                                  ;; Exclude fns in expansion root's chain to prevent
                                  ;; shared ancestors (e.g., conj-any) from filtering out
                                  ;; bindings that should flow to structural nodes
                                  expansion-chain-fns (set (get-inheritance-chain expansion-root fn-map))]
                              ;; Get all arg-ids from these ref-fns, excluding shared ancestors
                              (set (mapcat (fn [ref-fn-id]
                                             (let [ref-chain (get-inheritance-chain ref-fn-id fn-map)]
                                               (mapcat (fn [rfn-id]
                                                         (when-not (contains? expansion-chain-fns rfn-id)
                                                           (map :id (get args-by-fn rfn-id []))))
                                                       ref-chain)))
                                           ref-fn-ids))))
                          exp-root-chain (when expansion-root
                                           (set (get-inheritance-chain expansion-root fn-map)))
                          all-args (collect-fn-args display-fn-id bindings
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
                                  ancestor-fns (rest (mapcat identity all-levels))]
                              (reduce
                                (fn [b fid] (add-bindings-from-fn fid b args-by-fn arg-map))
                                {} ancestor-fns)))

                          ancestor-bound?
                          (fn [arg-id]
                            (or (when ancestor-bindings
                                  (loop [sid arg-id]
                                    (when sid
                                      (if (get ancestor-bindings sid)
                                        true
                                        (when-let [src-arg (get arg-map sid)]
                                          (recur (:source-id src-arg)))))))
                                (arg-determined? arg-id)))

                          filtered-args
                          (filterv (fn [arg]
                                     (if (= :unset (:type arg))
                                       (not (ancestor-bound? (:arg-id arg)))
                                       true))
                                   all-args)]
                      (doseq [arg filtered-args]
                        (case (:type arg)
                          :ref (let [ref-expansion-root (when-not (:is-binding arg) expansion-root)
                                     ref-bindings bindings
                                     ;; HOF-reachability crosses this edge if the
                                     ;; arg binding (or any in its source chain)
                                     ;; is is-fn=true. Otherwise inherit caller's
                                     ;; is-hof context.
                                     arg-entity (get arg-map (:arg-id arg))
                                     child-is-hof (or is-hof (arg-marks-hof? arg-entity))]
                                 (process-any-fn (:ref-id arg) node-id (:arg-name arg) false ref-bindings (:arg-id arg) ref-expansion-root #{display-fn-id} child-is-hof))
                          :value (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id #{display-fn-id})
                          :unset (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id #{display-fn-id} is-hof)
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
                  (if (contains? @in-progress-expansions in-progress-key)
                    (let [node-id (add-fn-node original-fn-id is-root parent-expansion-root)]
                      (when (and source-node-id edge-arg-name)
                        (let [edge-id (str "e-ref-" source-node-id "-" node-id)]
                          (when-not (contains? @added-node-ids edge-id)
                            (swap! added-node-ids conj edge-id)
                            (swap! edges conj
                                   {:data {:id edge-id
                                           :source source-node-id
                                           :target node-id
                                           :argName (or (compute-edge-label source-arg-id source-node-id source-expanded-fns)
                                                        (when edge-arg-name (name edge-arg-name)))}}))))
                      node-id)
                    (do
                      (swap! in-progress-expansions conj in-progress-key)
                      (try
                        (process-expanded-fn-impl original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof)
                        (finally
                          (swap! in-progress-expansions disj in-progress-key)))))))

              (process-expanded-fn-impl
                [original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root source-expanded-fns is-hof]
                (let [levels (get-inheritance-levels original-fn-id fn-map)
                      chain (vec (mapcat identity levels))  ; flat for set ops
                      expand-set (spec->expand-set original-fn-id spec)
                      ;; TWO binding maps:
                      ;; 1. display-bindings: from EXPAND-SET fns only. Used for
                      ;;    collect-expanded-args to render values/refs. Only shows
                      ;;    bindings from fns the user explicitly expanded.
                      ;; 2. all-bindings: from ALL ancestors. Used for filtering —
                      ;;    hides :unset args that are bound by non-expanded ancestors
                      ;;    (they're not truly free, just not yet visible).
                      display-bindings (reduce
                                         (fn [b fid] (add-bindings-from-fn fid b args-by-fn arg-map))
                                         {} expand-set)
                      all-bindings (build-chain-bindings levels nil args-by-fn arg-map)
                      ;; Merge order: parent FIRST, display WINS for collisions.
                      chain-bindings (merge parent-bindings display-bindings)
                      ;; Determine expansion root for this node and its children:
                      ;; - If we're already inside an expansion (parent-expansion-root set),
                      ;;   keep that context to avoid merging nodes from different expansions
                      ;; - If this is a top-level expansion (parent-expansion-root is nil),
                      ;;   this fn becomes the expansion root
                      effective-expansion-root (or parent-expansion-root original-fn-id)
                      ;; Add the node with appropriate expansion root
                      ;; Only use nil (canonical) if this is truly a top-level expansion
                      node-id (add-fn-node original-fn-id is-root parent-expansion-root)]


                  ;; Add edge from parent
                  (when (and source-node-id edge-arg-name)
                    (let [edge-id (str "e-ref-" source-node-id "-" node-id)
                          ;; Include source-node-id in key to allow same arg->target from different sources
                          arg-target-key (when source-arg-id
                                           (str source-node-id "-" source-arg-id "->" node-id))
                          is-duplicate (and arg-target-key
                                            (contains? @processed-arg-targets arg-target-key))]
                      (when (and (not (contains? @added-node-ids edge-id))
                                 (not is-duplicate))
                        (swap! added-node-ids conj edge-id)
                        (when arg-target-key
                          (swap! processed-arg-targets conj arg-target-key))
                        (swap! edges conj
                               {:data {:id edge-id
                                       :source source-node-id
                                       :target node-id
                                       :argName (or (compute-edge-label source-arg-id source-node-id source-expanded-fns)
                                                    (when edge-arg-name (name edge-arg-name)))}}))))

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
                  (let [raw-args (collect-expanded-args levels expand-set chain-bindings)
                        ;; Filter out :unset args whose terminal is bound by some
                        ;; fn in the source-chain owners' parent inheritance
                        ;; closure. Bindings in ref-id targets are NOT considered
                        ;; (they're scoped to the ref's call context).
                        all-args (filterv (fn [arg]
                                            (if (= :unset (:type arg))
                                              (not (arg-determined? (:arg-id arg)))
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

                        has-ancestor-refs (seq ancestor-refs)]

                    ;; Store info for deduplication
                    (swap! expansion-bindings assoc original-fn-id
                           {:has-ancestor-refs has-ancestor-refs})

                    ;; Compute binding-covered? BEFORE processing nodes.
                    ;; A binding is "covered" if its source chain leads to an arg
                    ;; of a displayed ancestor ref fn — it will appear there instead.
                    (let [expansion-chain-fns (set chain)

                          all-ancestor-ref-fn-ids
                          (let [result (atom #{})]
                            (letfn [(walk
                                      [fn-id visited]
                                      (when-not (contains? visited fn-id)
                                        (swap! result conj fn-id)
                                        (let [fn-args (get args-by-fn fn-id [])
                                              child-refs (keep :ref-id fn-args)]
                                          (doseq [cr child-refs]
                                            (walk cr (conj visited fn-id))))))]
                              (doseq [ref ancestor-refs]
                                (walk (:ref-id ref) #{})))
                            @result)

                          ancestor-ref-arg-sources
                          (set (mapcat (fn [fn-id]
                                         (let [fn-chain (get-inheritance-chain fn-id fn-map)]
                                           (mapcat (fn [chain-fn-id]
                                                     (when-not (contains? expansion-chain-fns chain-fn-id)
                                                       (map :id (get args-by-fn chain-fn-id []))))
                                                   fn-chain)))
                                       all-ancestor-ref-fn-ids))

                          binding-covered?
                          (fn [arg]
                            (loop [sid (:arg-id arg)]
                              (when sid
                                (if (contains? ancestor-ref-arg-sources sid)
                                  true
                                  (let [src-arg (get arg-map sid)]
                                    (recur (:source-id src-arg)))))))]

                      ;; ORDER: level-0 args FIRST, then ancestor args.
                      ;; Within each level: refs (structural connections),
                      ;; then UNSETS (interface — free args the user provides),
                      ;; then VALUES (internal bindings — implementation details).
                      ;; Free args come above bindings so the interface is visible
                      ;; first when reading top-to-bottom.

                      ;; Level-0 refs. Hidden when `binding-covered?` — the
                      ;; binding's source chain passes through an arg inside a
                      ;; displayed ancestor-ref subtree. In that case the
                      ;; binding MOVES to the descendant that actually consumes
                      ;; the slot (e.g. `:func :_router` on an `:if` migrates
                      ;; to `router-result` which inherits from `:invoke` where
                      ;; `:func` is primary). `binding-applies?` accepts the
                      ;; binding there via the terminal-primary-on-ancestry
                      ;; check; `bound-by-chain?` suppresses the propagation
                      ;; relays in between so the edge renders exactly once.
                      ;;
                      ;; Level-0 refs are NOT prefixed with the expansion root
                      ;; (pass nil as expansion-root). Two expansions that both
                      ;; reference the same shared fn at their own level 0
                      ;; should point at a single canonical node, not at two
                      ;; per-expansion copies.
                      (doseq [arg level-0-refs]
                        (when-not (binding-covered? arg)
                          (let [arg-entity (get arg-map (:arg-id arg))
                                child-is-hof (or is-hof (arg-marks-hof? arg-entity))]
                            (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) parent-expansion-root expand-set child-is-hof))))

                      ;; Level-0 unsets (free args)
                      (doseq [arg level-0-unsets]
                        (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id expand-set is-hof))

                      ;; Level-0 values (bindings)
                      (doseq [arg level-0-values]
                        (when-not (binding-covered? arg)
                          (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id expand-set)))

                      ;; Ancestor refs (structural expansion)
                      (doseq [arg ancestor-refs]
                        (let [arg-entity (get arg-map (:arg-id arg))
                              child-is-hof (or is-hof (arg-marks-hof? arg-entity))]
                          (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) effective-expansion-root expand-set child-is-hof)))

                      ;; Ancestor unsets (free args inherited)
                      (doseq [arg ancestor-unsets]
                        (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id expand-set is-hof))

                      ;; Ancestor values (ancestor bindings)
                      (doseq [arg ancestor-values]
                        (when-not (binding-covered? arg)
                          (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id expand-set)))))

                  node-id))

              (process-any-fn
                [fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id expansion-root source-expanded-fns is-hof]
                ;; Named fns (with name in DB) are "boundaries" — their implementation
                ;; is hidden by default. Only the root fn and anonymous (name=nil) fns
                ;; are expanded automatically. Named fns show as leaf nodes unless
                ;; the user explicitly requests expansion.
                (let [fn-entity (get fn-map fn-id)
                      is-named (and fn-entity (:name fn-entity))
                      spec (get-effective-spec fn-id expansion-root)
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
                    ;; Add the node + edge. Show the fn's OWN values and unsets,
                    ;; and its direct refs to other named leaves (no explosion
                    ;; since those targets are also leaves). Inherited bindings
                    ;; from parents propagate here as edges from this leaf so
                    ;; migrated slot values surface at the leaf where the slot
                    ;; lives.
                    ;;
                    ;; Scope: when inside an enclosing expansion each leaf
                    ;; gets a per-expansion-root copy (fn-{root}_{fn-id}) so
                    ;; the same named fn reached via two different expansion
                    ;; contexts stays as two separate nodes with their own
                    ;; migrated bindings. Outside any expansion we reuse the
                    ;; canonical id so multiple callers share one leaf.
                    (let [node-id (add-fn-node fn-id false expansion-root)
                          process-key (str node-id "-" (hash parent-bindings))]
                      (when (and source-node-id edge-arg-name)
                        (let [edge-id (str "e-ref-" source-node-id "-" node-id)]
                          (when-not (contains? @added-node-ids edge-id)
                            (swap! added-node-ids conj edge-id)
                            (swap! edges conj
                                   {:data {:id edge-id
                                           :source source-node-id
                                           :target node-id
                                           :argName (or (compute-edge-label source-arg-id source-node-id source-expanded-fns)
                                                        (when edge-arg-name (name edge-arg-name)))}}))))
                      ;; Skip child render if this leaf+binding-context was
                      ;; already processed. Cycles arise via propagated ref
                      ;; chains (e.g. list-10 → list-10-9 → list-10 through
                      ;; :coll migration). The incoming edge above is still
                      ;; added every time — only further recursion is pruned.
                      (when-not (contains? @processed-fn-nodes process-key)
                        (swap! processed-fn-nodes conj process-key)
                      (let [;; Transitive ref-closure of this leaf — all fn-ids
                            ;; reachable by walking this fn's inheritance and
                            ;; its bound refs recursively. Used to identify
                            ;; shadows on the enclosing expansion-root that
                            ;; migrated to this leaf (their source-chain walks
                            ;; into a fn inside the closure).
                            leaf-closure
                            (let [seen (atom #{})]
                              (letfn [(walk [fid]
                                        (when-not (contains? @seen fid)
                                          (swap! seen conj fid)
                                          (doseq [anc (get-inheritance-chain fid fn-map)]
                                            (swap! seen conj anc)
                                            (doseq [a (get args-by-fn anc [])]
                                              (when-let [r (:ref-id a)]
                                                (walk r))
                                              (when (= :sequence (:type a))
                                                (doseq [item (walk-anchor-chain a arg-map)]
                                                  (when-let [r (:ref-id item)]
                                                    (walk r))))))))]
                                (walk fn-id))
                              @seen)
                            leaf-closure-arg-ids
                            (into #{}
                                  (mapcat (fn [fid] (map :id (get args-by-fn fid []))))
                                  leaf-closure)
                            raw-own-args (get args-by-fn fn-id [])
                            ;; Sequence anchors (type=:sequence) and their chain items
                            ;; are NOT free args — the anchor's presence IS the binding
                            ;; for the parent's `:items`-style slot. Exclude both from
                            ;; the scalar pipeline so they don't surface as phantom
                            ;; "items" placeholders on referenced nodes.
                            seq-anchors (filterv #(= :sequence (:type %)) raw-own-args)
                            seq-chain-ids (into #{}
                                                (mapcat (fn [anchor]
                                                          (map :id (walk-anchor-chain anchor arg-map))))
                                                seq-anchors)
                            anchor-ids (into #{} (map :id) seq-anchors)
                            own-args (filterv (fn [a]
                                                (not (or (contains? anchor-ids (:id a))
                                                         (contains? seq-chain-ids (:id a)))))
                                              raw-own-args)
                            ;; Walk arg-id source chain for a propagated binding
                            ;; coming in via the enclosing expansion. When a named
                            ;; leaf is reached from an expanded parent, the parent's
                            ;; bindings migrate here and should render as edges from
                            ;; THIS leaf to the bound ref-target (or value node).
                            find-propagated-binding
                            (fn [arg-id]
                              (when parent-bindings
                                (loop [sid arg-id]
                                  (when sid
                                    (if-let [b (get parent-bindings sid)]
                                      b
                                      (let [src-arg (get arg-map sid)]
                                        (recur (:source-id src-arg))))))))
                            classified (mapv
                                         (fn [arg]
                                           (let [has-value (some? (:value arg))
                                                 has-ref (some? (:ref-id arg))
                                                 prop-binding (when-not (or has-value has-ref)
                                                                (find-propagated-binding (:id arg)))]
                                             (cond
                                               (and prop-binding (:ref-id prop-binding))
                                               {:kind :prop-ref :arg arg :binding prop-binding}

                                               (and prop-binding (some? (:value prop-binding)))
                                               {:kind :prop-value :arg arg :binding prop-binding}

                                               has-value
                                               {:kind :value :arg arg}

                                               (and (not has-value) (not has-ref)
                                                    (not (arg-determined? (:id arg))))
                                               {:kind :unset :arg arg}

                                               :else nil)))
                                         own-args)
                            parts (group-by :kind (remove nil? classified))]
                        ;; Unsets (free args — interface, propagate up to caller).
                        ;; On HOF-reachable leaves these are supplied by the HOF
                        ;; runtime; `add-unset-arg-node` routes them to the
                        ;; compact badge when is-hof is true.
                        (doseq [{:keys [arg]} (:unset parts)]
                          (add-unset-arg-node (resolve-arg-name arg arg-map)
                                              (:type arg) (:id arg) node-id #{fn-id} is-hof))
                        ;; Values (own literal bindings — local state)
                        (doseq [{:keys [arg]} (:value parts)]
                          (add-arg-value-node (resolve-arg-name arg arg-map)
                                              (:value arg) (:id arg) node-id #{fn-id}))
                        ;; Propagated bindings from the enclosing expansion —
                        ;; migrate here per rule 5. Rendered as edges from this
                        ;; leaf to the bound target. Pass expansion-root=nil so
                        ;; the target gets a canonical id: the bound value is
                        ;; caller-supplied and shared across call sites, not
                        ;; structurally inside any one expansion context.
                        (doseq [{:keys [arg binding]} (:prop-ref parts)]
                          (let [arg-entity (get arg-map (:id arg))
                                child-is-hof (or is-hof (arg-marks-hof? arg-entity))]
                            (process-any-fn (:ref-id binding) node-id
                                            (or (:arg-name binding)
                                                (resolve-arg-name arg arg-map))
                                            false parent-bindings (:id arg)
                                            nil #{fn-id} child-is-hof)))
                        (doseq [{:keys [arg binding]} (:prop-value parts)]
                          (add-arg-value-node (or (:arg-name binding)
                                                  (resolve-arg-name arg arg-map))
                                              (:value binding) (:id arg)
                                              node-id #{fn-id}))
                        ;; Transitive free args migrating from the enclosing
                        ;; expansion-root. Per rule 5, the leaf boundary is
                        ;; where free args of its hidden body surface — if
                        ;; composition didn't eagerly propagate them onto the
                        ;; leaf's own DB shadows (as is the case for
                        ;; `router-result` whose parent is a base-fn with no
                        ;; ref-chain to walk), we surface them here by looking
                        ;; at the caller-side expansion-root's shadow args
                        ;; whose terminal source-id lives inside this leaf's
                        ;; ref-closure. Shadows already covered by this leaf's
                        ;; own args are skipped to avoid double rendering.
                        (when expansion-root
                          (let [own-terminals (into #{}
                                                    (map (fn [a] (terminal-source-of (:id a))))
                                                    raw-own-args)
                                already-rendered-terminals
                                (into own-terminals
                                      (map (fn [{:keys [arg]}] (terminal-source-of (:id arg))))
                                      (:unset parts))
                                root-args (get args-by-fn expansion-root [])
                                seen-keys (atom already-rendered-terminals)]
                            (doseq [a root-args
                                    :let [term (terminal-source-of (:id a))
                                          nm (resolve-arg-name a arg-map)
                                          dedup-key [term nm]]
                                    :when (and (nil? (:value a))
                                               (nil? (:ref-id a))
                                               (not= :sequence (:type a))
                                               (contains? leaf-closure-arg-ids term)
                                               (not (contains? @seen-keys term))
                                               (not (contains? @seen-keys dedup-key))
                                               (not (arg-determined? (:id a))))]
                              (swap! seen-keys conj dedup-key)
                              (add-unset-arg-node nm (:type a) (:id a) node-id
                                                  #{fn-id} is-hof))))))
                      node-id)
                    ;; Normal processing
                    (if (spec-trivial? spec)
                      (let [bindings (build-arg-bindings fn-id fn-map args-by-fn arg-map)
                            ;; Merge order: parent first, local (base) WINS.
                            ;; See process-expanded-fn comment for rationale.
                            bindings (if parent-bindings
                                       (merge parent-bindings bindings)
                                       bindings)]
                        (process-fn fn-id fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root source-expanded-fns is-hof))
                      ;; Expanded mode - pass parent expansion-root to maintain context
                      (process-expanded-fn fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings expansion-root source-expanded-fns is-hof)))))]

        ;; Start processing from root - no expansion-root initially, not HOF.
        (process-any-fn root-fn-id nil nil true nil nil nil #{} false)))

    ;; Attach the list of optional-unbound arg names to their source node so
    ;; the client can render a compact hint (e.g. "+default, +not-found")
    ;; instead of cluttering the graph with placeholder nodes.
    (let [final-nodes (mapv (fn [n]
                              (let [node-id (get-in n [:data :id])
                                    optionals (get @optional-unsets-by-node node-id)
                                    hof-captured (get @hof-captured-by-node node-id)]
                                (cond-> n
                                  (seq optionals)
                                  (assoc-in [:data :optionalArgs] (vec (distinct optionals)))

                                  (seq hof-captured)
                                  (assoc-in [:data :hofCapturedArgs] (vec (distinct hof-captured))))))
                            @nodes)]
      {:nodes final-nodes
       :edges @edges})))


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


(defn- find-paths-to-shared
  "For each node, find which shared nodes are reachable from it."
  [children shared-nodes]
  (let [memo (atom {})]
    (letfn [(reachable-from
              [node-id]
              (if-let [cached (get @memo node-id)]
                cached
                (let [result (if (contains? shared-nodes node-id)
                               #{node-id}
                               (let [child-ids (get children node-id [])]
                                 (reduce (fn [acc cid]
                                           (into acc (reachable-from cid)))
                                         #{} child-ids)))]
                  (swap! memo assoc node-id result)
                  result)))]
      (reduce (fn [m node-id]
                (assoc m node-id (reachable-from node-id)))
              {}
              (keys children)))))


(defn- path-length-to-shared
  "Calculate path length from node to shared node (BFS)."
  [from-id shared-id children]
  (loop [queue [[from-id 0]]
         visited #{}]
    (when (seq queue)
      (let [[node-id dist] (first queue)]
        (cond
          (= node-id shared-id) dist
          (contains? visited node-id) (recur (rest queue) visited)
          :else
          (let [child-ids (get children node-id [])]
            (recur (into (vec (rest queue))
                         (map (fn [c] [c (inc dist)]) child-ids))
                   (conj visited node-id))))))))


(defn- get-child-type
  "Get type of child node: :fn, :fixed, or :free"
  [child-id node-data-map]
  (let [data (get node-data-map child-id)]
    (cond
      (or (nil? data) (:isPlaceholder data)) :free
      (= (:type data) "fn") :fn
      (= (:type data) "arg") :fixed
      :else :free)))


(defn- find-divergence-roots
  "Find divergence roots - siblings that both lead to the same shared node.

   Returns set of node-ids that are divergence roots.
   These nodes should NOT have path-position applied to them at their parent level.
   Only their descendants should use path-position ordering.

   Divergence roots are nodes where paths to a shared node SPLIT - i.e., siblings
   that both lead to the same shared node. We find them by:
   1. For each shared node, trace paths back to common ancestor
   2. The children of that ancestor that lead to shared are divergence roots"
  [children parents shared-nodes paths-to-shared]
  (let [divergence-roots (atom #{})]
    ;; For each shared node, find siblings that lead to it
    (doseq [shared-id shared-nodes]
      ;; Find all nodes that have this shared node in their paths-to-shared
      (let [leading-to-shared (filter (fn [[_node-id targets]]
                                        (contains? targets shared-id))
                                      paths-to-shared)
            ;; Group by their parent
            by-parent (group-by (fn [[node-id _]]
                                  ;; Find parent of this node
                                  (first (filter (fn [potential-parent]
                                                   (some #(= % node-id) (get children potential-parent [])))
                                                 (keys children))))
                                leading-to-shared)]
        ;; Siblings (same parent) that both lead to shared are divergence roots
        (doseq [[_parent siblings] by-parent]
          (when (> (count siblings) 1)
            ;; All these siblings are divergence roots for this shared node
            (doseq [[sibling-id _] siblings]
              (swap! divergence-roots conj sibling-id))))))
    @divergence-roots))


(defn- compute-path-positions
  "For each node, determine if it's on an upper or lower path to shared nodes.

   Returns map: node-id -> :upper | :lower | nil

   Key insight: path positions only apply to nodes AFTER the divergence point.
   Pre-divergence nodes (including the divergence point itself) are on BOTH paths
   and should NOT be marked upper or lower.

   Divergence point = parent of divergence roots (siblings where paths split).
   Propagation of lower/upper stops at the divergence point.

   Rules:
   - Bottom parent of shared node → :lower
   - Ancestors of bottom parent up to (but NOT including) divergence point → :lower
   - Other parents and their ancestors up to divergence point → :upper
   - Divergence roots themselves get path-position for ordering their own children
   - Pre-divergence nodes (divergence point and above) → nil (no position)"
  [children parents shared-nodes]
  (let [paths-to-shared (find-paths-to-shared children shared-nodes)

        ;; Find divergence points for each shared node
        ;; A divergence point is a node whose children include 2+ siblings
        ;; that both lead to the same shared node
        divergence-points-per-shared
        (into {}
              (map (fn [shared-id]
                     (let [;; Find all nodes that have children leading to this shared node
                           points (filter
                                    (fn [node-id]
                                      (let [kids (get children node-id [])
                                            kids-leading (filter #(contains? (get paths-to-shared % #{}) shared-id) kids)]
                                        (> (count kids-leading) 1)))
                                    (keys children))]
                       [shared-id (set points)]))
                   shared-nodes))

        lower-path-nodes (atom #{})

        ;; For each shared node, mark bottom parent and ancestors up to divergence point
        _ (doseq [shared-id shared-nodes]
            (let [parent-ids (get parents shared-id [])
                  div-points (get divergence-points-per-shared shared-id #{})]
              (when (seq parent-ids)
                (let [bottom-parent (last parent-ids)]
                  (swap! lower-path-nodes conj bottom-parent)
                  ;; Propagate UP but STOP at divergence points
                  (loop [to-check [bottom-parent]]
                    (when (seq to-check)
                      (let [node-id (first to-check)
                            node-parents (get parents node-id [])
                            eligible (filter #(and (contains? (get paths-to-shared % #{}) shared-id)
                                                   (not (contains? div-points %))
                                                   (not (contains? @lower-path-nodes %)))
                                             node-parents)]
                        (doseq [parent-id eligible]
                          (swap! lower-path-nodes conj parent-id))
                        (recur (into (vec (rest to-check)) eligible)))))))))

        ;; Similarly compute upper-path nodes: non-bottom parents up to divergence point
        upper-path-nodes (atom #{})
        _ (doseq [shared-id shared-nodes]
            (let [parent-ids (get parents shared-id [])
                  div-points (get divergence-points-per-shared shared-id #{})]
              (when (> (count parent-ids) 1)
                (let [bottom-parent (last parent-ids)
                      upper-parents (butlast parent-ids)]
                  (doseq [up upper-parents]
                    (swap! upper-path-nodes conj up)
                    ;; Propagate UP but STOP at divergence points
                    (loop [to-check [up]]
                      (when (seq to-check)
                        (let [node-id (first to-check)
                              node-parents (get parents node-id [])
                              ;; Filter eligible parents BEFORE marking them
                              eligible (filter #(and (contains? (get paths-to-shared % #{}) shared-id)
                                                     (not (contains? div-points %))
                                                     (not (contains? @upper-path-nodes %)))
                                               node-parents)]
                          ;; Mark eligible parents
                          (doseq [parent-id eligible]
                            (swap! upper-path-nodes conj parent-id))
                          ;; Continue propagation with newly marked parents
                          (recur (into (vec (rest to-check)) eligible))))))))))]

    ;; Build result map - only nodes AFTER divergence get positions
    (reduce (fn [m node-id]
              (condp contains? node-id
                @lower-path-nodes (assoc m node-id :lower)
                @upper-path-nodes (assoc m node-id :upper)
                m))
            {}
            (keys children))))


(defn- order-children
  "Order children for layout placement.

   See docs/LAYOUT.md Section 3.3 and 4.2 for full specification.

   Key rules:
   1. Divergence roots stay ADJACENT at their original position (not pushed to top/bottom)
   2. Within divergence group: lower-path root goes LAST
   3. Path nodes on LOWER path: path-to-shared children go FIRST (horizontal branch)
   4. Path nodes on UPPER path: path-to-shared children go LAST (pushed down)
   5. Direct shared children: included in output (handled by remove-shared-from-upper-parents separately)

   This ensures the lower path forms a horizontal branch to the shared node."
  [parent-id children-map paths-to-shared shared-nodes graph-children node-data-map path-position divergence-roots all-path-positions]
  (let [child-ids (get children-map parent-id [])

        classify-child
        (fn [child-id idx]
          (let [targets (get paths-to-shared child-id #{})
                child-type (get-child-type child-id node-data-map)
                is-shared-node (contains? shared-nodes child-id)
                is-divergence-root (contains? divergence-roots child-id)
                is-path-to-shared (and (seq targets) (not is-divergence-root) (not is-shared-node))]
            {:id child-id
             :type child-type
             :original-idx idx
             :targets targets
             :is-shared-node is-shared-node
             :is-divergence-root is-divergence-root
             :is-path-to-shared is-path-to-shared
             :primary-target (first (sort targets))}))

        classified (map-indexed (fn [idx id] (classify-child id idx)) child-ids)

        ;; Separate children into categories
        divergence-children (filter :is-divergence-root classified)
        path-children (filter :is-path-to-shared classified)
        direct-shared-children (filter :is-shared-node classified)
        neutral-children (filter #(and (not (:is-divergence-root %))
                                       (not (:is-path-to-shared %))
                                       (not (:is-shared-node %)))
                                 classified)

        ;; Group divergence roots by their shared target
        divergence-by-target (group-by :primary-target divergence-children)

        ;; Build unified list preserving positions:
        ;; - Divergence groups: insert at min-idx of the group, sorted internally (lower-path LAST)
        ;; - Neutral children: stay at original position
        ;; - Direct shared children: stay at original position
        ;; - Path-to-shared: moved based on path-position rule
        divergence-groups
        (map (fn [[target members]]
               (let [min-idx (apply min (map :original-idx members))
                     ;; Within group: lower-path member goes LAST
                     sorted-members (sort-by (fn [m]
                                               (if (= :lower (get all-path-positions (:id m)))
                                                 1  ; Lower path = sort last within group
                                                 0))
                                             members)]
                 {:type :divergence-group
                  :min-idx min-idx
                  :target target
                  :members sorted-members}))
             divergence-by-target)

        ;; Is this parent on lower or upper path?
        is-lower-path (= path-position :lower)
        is-upper-path (= path-position :upper)

        ;; Build sorted list:
        ;; 1. Create position slots for neutral, direct-shared, path, and divergence groups
        neutral-items (map (fn [c] {:type :neutral :idx (:original-idx c) :child c}) neutral-children)
        shared-items (map (fn [c] {:type :shared :idx (:original-idx c) :child c}) direct-shared-children)
        path-items (map (fn [c] {:type :path :idx (:original-idx c) :child c}) path-children)
        divergence-items (map (fn [g] {:type :divergence :idx (:min-idx g) :group g}) divergence-groups)

        ;; Sort all positional items by index (includes path-children for the :else case)
        positional-items (sort-by :idx (concat neutral-items shared-items divergence-items))

        ;; All items including path (for pre-divergence nodes where no special ordering needed)
        all-positional-items (sort-by :idx (concat neutral-items shared-items path-items divergence-items))

        ;; Extract IDs from positional items
        positional-ids (mapcat (fn [item]
                                 (case (:type item)
                                   :neutral [(:id (:child item))]
                                   :shared [(:id (:child item))]
                                   :path [(:id (:child item))]
                                   :divergence (map :id (:members (:group item)))))
                               positional-items)

        ;; All IDs including path-children at their original positions
        all-positional-ids (mapcat (fn [item]
                                     (case (:type item)
                                       :neutral [(:id (:child item))]
                                       :shared [(:id (:child item))]
                                       :path [(:id (:child item))]
                                       :divergence (map :id (:members (:group item)))))
                                   all-positional-items)

        ;; Path-to-shared children IDs (sorted by original-idx within each target group)
        path-ids (->> path-children
                      (group-by :primary-target)
                      (mapcat (fn [[_target group]] (sort-by :original-idx group)))
                      (map :id))]

    ;; Final ordering based on parent's path position
    (cond
      ;; Lower path: path-to-shared children FIRST (forms horizontal branch)
      is-lower-path
      (vec (concat path-ids positional-ids))

      ;; Upper path: path-to-shared children LAST (pushed down)
      is-upper-path
      (vec (concat positional-ids path-ids))

      ;; Not on any path (pre-divergence or neutral): include all children
      ;; at their original positions, no special path ordering
      :else
      (vec all-positional-ids))))         ; regular last           ; then divergence roots


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


(defn- compute-column-depths
  "Compute column depth (distance from root in columns) for each node.
   Each step in the graph = +1 column.
   Returns map: node-id -> column-depth"
  [root-id children]
  (loop [queue [[root-id 0]]
         depths {}]
    (if (empty? queue)
      depths
      (let [[node-id depth] (first queue)
            rest-queue (rest queue)]
        (if (contains? depths node-id)
          (recur rest-queue depths)
          (let [depths (assoc depths node-id depth)
                child-ids (get children node-id [])
                new-entries (map (fn [c] [c (inc depth)]) child-ids)]
            (recur (into (vec rest-queue) new-entries) depths)))))))


(defn- compute-parent-offsets
  "For each shared node, compute offsets so all direct parents end up at the same column.

   Uses column-depths directly: the deepest parent stays as-is,
   shallower parents get shifted right by the difference.

   This ensures the shared node (placed at keeper's col+1) is always
   to the RIGHT of all parents, eliminating backward edges.

   Returns map: node-id -> offset (number of extra columns to shift right)"
  [shared-nodes parents-map column-depths]
  (reduce
    (fn [offsets shared-id]
      (let [parent-ids (get parents-map shared-id [])
            parent-depths (map (fn [pid] {:id pid :depth (get column-depths pid 0)}) parent-ids)
            max-depth (if (seq parent-depths)
                        (apply max (map :depth parent-depths))
                        0)]
        (reduce
          (fn [offs {:keys [id depth]}]
            (let [needed (- max-depth depth)]
              (if (pos? needed)
                ;; Take max if parent already has offset from another shared node
                (update offs id (fn [old] (max (or old 0) needed)))
                offs)))
          offsets
          parent-depths)))
    {}
    shared-nodes))


(defn- remove-shared-from-upper-parents
  "For each shared node, remove it from children lists of all parents except
   the one that should place it on its horizontal branch (the 'keeper').

   Keeper selection:
   1. If any parent is on the LOWER path to this shared node, that parent
      keeps it. The lower path forms the horizontal branch; the shared node
      belongs there. Upper-path parents have it removed entirely so their
      edge simply points down to the shared node on the lower branch.
   2. Otherwise (no path info), fall back to shallowest-parent rule:
      sort by column depth ASC, index DESC; first wins.

   Edges still exist for drawing — removing from children-map only affects
   placement, not connectivity."
  [children-map parents-map shared-nodes column-depths path-positions]
  (reduce
    (fn [cm shared-id]
      (let [parent-ids (get parents-map shared-id [])
            ;; Try to find a parent on the lower path to this shared node
            lower-parent (first (filter (fn [pid]
                                          (= :lower (get path-positions pid)))
                                        parent-ids))
            ;; Fall back: shallowest parent (min column depth), latest index wins ties
            fallback-keeper
            (when-not lower-parent
              (let [indexed (map-indexed (fn [idx pid]
                                           [pid (get column-depths pid Integer/MAX_VALUE) idx])
                                         parent-ids)
                    sorted (sort-by (fn [[_pid depth idx]] [depth (- idx)]) indexed)]
                (ffirst sorted)))
            keeper (or lower-parent fallback-keeper)
            parents-to-remove (remove #(= % keeper) parent-ids)]
        (reduce
          (fn [cm2 parent-id]
            (update cm2 parent-id (fn [kids] (vec (remove #(= % shared-id) kids)))))
          cm
          parents-to-remove)))
    children-map
    shared-nodes))


(defn- layout-graph
  "Main layout function implementing depth-first placement.

   Algorithm (see docs/LAYOUT.md for full description):

   1. Build horizontal branch: chain of first children from SELECTED node
   2. Find row where branch fits (checking cols are free below too)
   3. Place the branch
   4. For each node in branch (RIGHT-TO-LEFT), process remaining children DEPTH-FIRST
      - For each remaining child, recursively place its ENTIRE subtree
      - Only move to next child after current child's subtree is fully placed
   5. Backtrack when branch is fully processed

   Key invariant: A node's entire subtree is placed before its sibling."
  [root-id graph-info]
  (let [{:keys [children parents shared-nodes node-data-map]} graph-info
        paths-to-shared (find-paths-to-shared children shared-nodes)
        divergence-roots (find-divergence-roots children parents shared-nodes paths-to-shared)
        path-positions (compute-path-positions children parents shared-nodes)

        ;; Pre-calculate sorted children for each node
        ;; Compute column depths FIRST - needed for determining which parent keeps shared nodes
        column-depths (compute-column-depths root-id children)

        sorted-children-map
        (into {}
              (map (fn [node-id]
                     [node-id (order-children node-id children paths-to-shared
                                              shared-nodes children node-data-map
                                              (get path-positions node-id)
                                              divergence-roots
                                              path-positions)])
                   (keys node-data-map)))

        ;; Remove shared nodes from non-keeper parents' children lists.
        ;; Lower-path parent keeps the shared child (for horizontal branch).
        sorted-children-map (remove-shared-from-upper-parents
                              sorted-children-map parents shared-nodes
                              column-depths path-positions)

        ;; Compute column offsets: align direct parents of shared nodes
        ;; Shallower parents get shifted right so all parents end up at same column
        parent-offsets (compute-parent-offsets shared-nodes parents column-depths)]

    (letfn [(get-sorted-children
              [node-id]
              (get sorted-children-map node-id []))

            (get-offset
              [node-id]
              (get parent-offsets node-id 0))

            ;; Build horizontal branch (chain of first children)
            (build-branch
              [node-id start-col]
              (loop [current node-id
                     col start-col
                     branch []]
                (if (nil? current)
                  branch
                  (let [offset (get-offset current)
                        actual-col (+ col offset)
                        branch (conj branch {:id current :col actual-col :offset offset})
                        kids (get-sorted-children current)
                        first-child (first kids)]
                    (if first-child
                      (recur first-child (inc actual-col) branch)
                      branch)))))

            ;; Check if branch fits at row (including reserved edge cells for offsets)
            (branch-fits-at-row?
              [matrix branch row]
              (every? (fn [{:keys [col offset]}]
                        (let [offset (or offset 0)]
                          ;; Check the node cell AND any offset-reserved cells
                          (and (not (cell-occupied? matrix row col))
                               (every? #(not (cell-occupied? matrix row %))
                                       (range (- col offset) col)))))
                      branch))

            ;; Find row where branch fits
            (find-row-for-branch
              [matrix branch min-row]
              (loop [row min-row]
                (if (branch-fits-at-row? matrix branch row)
                  row
                  (recur (inc row)))))

            ;; Place branch at row, reserving edge cells for offsets
            (place-branch
              [matrix branch row]
              (reduce
                (fn [m {:keys [id col offset]}]
                  (let [m (place-node-in-matrix m id row col)
                        offset (or offset 0)]
                    ;; Reserve cells for horizontal edge gap when offset > 0
                    (if (pos? offset)
                      (reduce (fn [m2 edge-col]
                                (assoc-in m2 [:grid [row edge-col]]
                                          {:edge-reserve true}))
                              m
                              (range (- col offset) col))
                      m)))
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
   Keys have 'fn-' prefix from node IDs; structural nodes use 'fn-{root}_{fn-id}' format.
   Returns map of {[expansion-root fn-id] spec} or {[nil fn-id] spec}."
  [expansions-raw]
  (into {}
        (map (fn [[k v]]
               (let [k-str (name k)
                     stripped (if (str/starts-with? k-str "fn-")
                                (subs k-str 3)
                                k-str)
                     [expansion-root fn-id]
                     (if (str/includes? stripped "_")
                       (let [parts (str/split stripped #"_")]
                         [(java.util.UUID/fromString (first parts))
                          (java.util.UUID/fromString (second parts))])
                       [nil (java.util.UUID/fromString stripped)])]
                 [[expansion-root fn-id] (parse-spec v)]))
             expansions-raw)))


(defn- parse-layout-request
  "Parse request body into {:root-id UUID, :expansions parsed-map}.
   Throws on missing root-id."
  [request]
  (let [body-str (:body request)
        body (when (and body-str (not (str/blank? body-str)))
               (json/parse-string body-str true))
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
