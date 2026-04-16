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
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core VersionedStorage)))


;; =============================================================================
;; NODE ID UTILITIES
;; =============================================================================

;; =============================================================================
;; DATA LOADING FROM STORAGE
;; =============================================================================

(defn- load-graph-entities
  "Load all fns and args from storage."
  [storage]
  (if (instance? VersionedStorage storage)
    (vs/query-all-graph-entities storage)
    {:fns (vec (sp/query-entities storage :fn {}))
     :args (vec (sp/query-entities storage :arg {}))}))


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

(defn- truncate-label [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))


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
                    fn-name-of (fn [fid]
                                 (let [f (get fn-map fid)]
                                   (or (when (:name f) (name (:name f)))
                                       ;; Anonymous fn — show nearest named ancestor
                                       (some (fn [pid]
                                               (when-let [p (get fn-map pid)]
                                                 (when (:name p) (name (:name p)))))
                                             (rest (get-inheritance-chain fid fn-map)))
                                       "(anonymous)")))
                    visible-levels (take (inc max-visible-ancestors) levels)
                    raw-lines (mapv (fn [level-fn-ids]
                                      (str/join ", " (map fn-name-of level-fn-ids)))
                                    visible-levels)
                    ;; Remove consecutive duplicate lines (e.g. anonymous fn
                    ;; whose resolved name matches the first ancestor)
                    label-lines (reduce (fn [acc line]
                                          (if (and (seq acc) (= (peek acc) line))
                                            acc
                                            (conj acc line)))
                                        [] raw-lines)
                    label-lines (if (> (count levels) (inc max-visible-ancestors))
                                  (conj label-lines "...")
                                  label-lines)
                    label (str/join "\n" label-lines)]
                (swap! nodes conj
                       {:data {:id node-id
                               :label label
                               :type "fn"
                               :isRoot is-root
                               :originalFnId (str original-fn-id)}})))
            node-id))

        add-arg-value-node
        (fn [arg-name value arg-id source-node-id]
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
                             :argName (when arg-name (name arg-name))}}))
            node-id))

        add-unset-arg-node
        (fn [arg-name arg-type arg-id source-node-id]
          ;; Node ID must include source-node-id to ensure uniqueness per expansion
          ;; Different fns expanding to same ancestor should get separate unset nodes
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
                             :argName (when arg-name (name arg-name))
                             :isUnset true}}))))

        ;; Compute terminal source-id of an arg (walk source chain to root)
        terminal-source-of
        (fn [arg-id]
          (loop [cur (get arg-map arg-id)]
            (if (and cur (:source-id cur))
              (if-let [src (get arg-map (:source-id cur))]
                (recur src)
                (:id cur))
              (or (:id cur) arg-id))))

        ;; For each fn, the set of terminal-source-ids bound by its parent
        ;; inheritance closure (including itself). Bindings in ref-id targets
        ;; are NOT included — those are scoped to the ref's call context.
        parent-bound-terminals
        (let [cache (atom {})]
          (fn [fn-id]
            (or (get @cache fn-id)
                (let [visited (atom #{})
                      terms (atom #{})
                      walk (fn walk [fid]
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
          (let [args (get args-by-fn fn-id [])
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
                                       ;; ancestor source-id deep in the chain.
                                       raw-binding (loop [sid (:id arg)]
                                                     (if-let [b (get bindings sid)]
                                                       b
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

                                     ;; Binding existed but went to child - hide this arg entirely
                                     ;; Like Clojure function inlining: args are REPLACED at point of use
                                     raw-binding
                                     nil

                                     :else
                                     {:type :unset :arg-name arg-name
                                      :arg-type (:type arg) :arg-id (:id arg)})))
                               args)
                ;; Sort args: refs first (fn type), then values (fixed), then unset (free)
                type-order {:ref 0 :value 1 :unset 2}
                sorted-args (sort-by #(get type-order (:type %) 3) all-args)]
            (filterv some? sorted-args)))

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
          (let [active-fns (vec (filter expand-set (mapcat identity levels)))
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
                ;; Collect all arg-ids that belong to displayed ref-fns (including inheritance)
                ;; EXCLUDE args from fns that are also in the expansion root's inheritance chain.
                ;; This prevents shared ancestors (e.g., conj-any shared by list-11 and list-10)
                ;; from causing items to be filtered as "belonging inside ref-fn".
                expansion-chain-fns full-chain-fns
                ref-fn-arg-ids (atom #{})
                _ (doseq [ref-fn-id @ref-fn-ids]
                    (let [ref-chain (get-inheritance-chain ref-fn-id fn-map)]
                      (doseq [rfn-id ref-chain]
                        (when-not (contains? expansion-chain-fns rfn-id)
                          (let [ref-args (get args-by-fn rfn-id [])]
                            (doseq [ra ref-args]
                              (swap! ref-fn-arg-ids conj (:id ra))))))))
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
              (let [args (get args-by-fn fn-id [])
                    current-level @chain-level
                    fn-refs (atom [])
                    fn-values (atom [])
                    fn-unsets (atom [])
                    ;; Self-reference set: bindings whose ref-id targets a fn
                    ;; in the expand-set are self-cycles (parent calls this fn,
                    ;; and propagation tries to feed it back). Treat as no binding.
                    self-ref-targets expand-set]
                (doseq [arg args]
                  (let [arg-id (:id arg)
                        source-id (or (:source-id arg) arg-id)
                        already-covered (contains? @covered-sources source-id)
                        has-value (some? (:value arg))
                        has-ref (some? (:ref-id arg))
                        ;; Walk the FULL source-id chain to find a binding.
                        ;; Skip bindings whose ref-id is a self-reference
                        ;; (target fn is in the expand-set being processed).
                        binding (loop [sid arg-id]
                                  (if-let [b (get bindings sid)]
                                    (if (and (:ref-id b)
                                             (contains? self-ref-targets (:ref-id b)))
                                      ;; self-cycle binding — keep walking
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

                          :else
                          (swap! fn-unsets conj {:type :unset :arg-name arg-name
                                                 :arg-type (:type arg) :arg-id arg-id
                                                 :from-ancestor from-ancestor}))))))
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
    (letfn [(process-fn [original-fn-id display-fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root]
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
                                     :argName (when edge-arg-name (name edge-arg-name))}}))))

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
                                   ref-bindings bindings]
                               (process-any-fn (:ref-id arg) node-id (:arg-name arg) false ref-bindings (:arg-id arg) ref-expansion-root))
                        :value (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)
                        :unset (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id)
                        nil))))
                node-id))

            (process-expanded-fn [original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root]
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
                                         :argName (when edge-arg-name (name edge-arg-name))}}))))
                    node-id)
                  (do
                    (swap! in-progress-expansions conj in-progress-key)
                    (try
                      (process-expanded-fn-impl original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root)
                      (finally
                        (swap! in-progress-expansions disj in-progress-key)))))))

            (process-expanded-fn-impl [original-fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings parent-expansion-root]
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
                                     :argName (when edge-arg-name (name edge-arg-name))}}))))

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
                          (letfn [(walk [fn-id visited]
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

                    ;; Level-0 refs
                    (doseq [arg level-0-refs]
                      (when-not (binding-covered? arg)
                        (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) parent-expansion-root)))

                    ;; Level-0 unsets (free args)
                    (doseq [arg level-0-unsets]
                      (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id))

                    ;; Level-0 values (bindings)
                    (doseq [arg level-0-values]
                      (when-not (binding-covered? arg)
                        (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)))

                    ;; Ancestor refs (structural expansion)
                    (doseq [arg ancestor-refs]
                      (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) effective-expansion-root))

                    ;; Ancestor unsets (free args inherited)
                    (doseq [arg ancestor-unsets]
                      (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id))

                    ;; Ancestor values (ancestor bindings)
                    (doseq [arg ancestor-values]
                      (when-not (binding-covered? arg)
                        (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)))))

                node-id))

            (process-any-fn [fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id expansion-root]
              ;; Named fns (with name in DB) are "boundaries" — their implementation
              ;; is hidden by default. Only the root fn and anonymous (name=nil) fns
              ;; are expanded automatically. Named fns show as leaf nodes unless
              ;; the user explicitly requests expansion.
              (let [fn-entity (get fn-map fn-id)
                    is-named (and fn-entity (:name fn-entity))
                    spec (get-effective-spec fn-id expansion-root)
                    ;; A named non-root fn with no expansion spec → show as leaf
                    show-as-leaf (and is-named (not is-root) (spec-trivial? spec)
                                      ;; Inside an expansion, don't collapse named refs
                                      ;; — they are part of the expanded view
                                      (nil? expansion-root))]
                (if show-as-leaf
                  ;; Add the node + edge, then show the fn's OWN args.
                  ;; Only PARENT-defined bindings are hidden behind expansion.
                  ;; Order: refs (structural) → unsets (free interface) → values (own bindings).
                  (let [node-id (add-fn-node fn-id false nil)]
                    (when (and source-node-id edge-arg-name)
                      (let [edge-id (str "e-ref-" source-node-id "-" node-id)]
                        (when-not (contains? @added-node-ids edge-id)
                          (swap! added-node-ids conj edge-id)
                          (swap! edges conj
                                 {:data {:id edge-id
                                         :source source-node-id
                                         :target node-id
                                         :argName (when edge-arg-name (name edge-arg-name))}}))))
                    (let [own-args (get args-by-fn fn-id [])
                          classified (mapv (fn [arg]
                                             (let [arg-name (resolve-arg-name arg arg-map)]
                                               (cond
                                                 (some? (:ref-id arg))
                                                 {:type :ref :arg arg :arg-name arg-name}
                                                 (some? (:value arg))
                                                 {:type :value :arg arg :arg-name arg-name}
                                                 (not (arg-determined? (:id arg)))
                                                 {:type :unset :arg arg :arg-name arg-name}
                                                 :else nil)))
                                           own-args)
                          classified (filterv some? classified)
                          refs (filter #(= :ref (:type %)) classified)
                          unsets (filter #(= :unset (:type %)) classified)
                          values (filter #(= :value (:type %)) classified)]
                      ;; Refs (structural connections to other fns)
                      (doseq [{:keys [arg arg-name]} refs]
                        (process-any-fn (:ref-id arg) node-id arg-name false nil (:id arg) nil))
                      ;; Unsets (free args — interface)
                      (doseq [{:keys [arg arg-name]} unsets]
                        (add-unset-arg-node arg-name (:type arg) (:id arg) node-id))
                      ;; Values (bindings made by this fn)
                      (doseq [{:keys [arg arg-name]} values]
                        (add-arg-value-node arg-name (:value arg) (:id arg) node-id)))
                    node-id)
                  ;; Normal processing
                  (if (spec-trivial? spec)
                    (let [bindings (build-arg-bindings fn-id fn-map args-by-fn arg-map)
                          ;; Merge order: parent first, local (base) WINS.
                          ;; See process-expanded-fn comment for rationale.
                          bindings (if parent-bindings
                                     (merge parent-bindings bindings)
                                     bindings)]
                      (process-fn fn-id fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root))
                    ;; Expanded mode - pass parent expansion-root to maintain context
                    (process-expanded-fn fn-id spec source-node-id edge-arg-name is-root source-arg-id parent-bindings expansion-root)))))]

      ;; Start processing from root - no expansion-root initially
      (process-any-fn root-fn-id nil nil true nil nil nil)))

    {:nodes @nodes
     :edges @edges}))


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
    (letfn [(reachable-from [node-id]
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
                      (map :id))

]

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
(defn- empty-matrix [] {:grid {} :positions {}})
(defn- get-cell [matrix row col] (get (:grid matrix) [row col]))
(defn- cell-occupied? [matrix row col] (some? (get-cell matrix row col)))
(defn- place-node-in-matrix [matrix node-id row col]
  (-> matrix
      (assoc-in [:grid [row col]] node-id)
      (assoc-in [:positions node-id] {:row row :col col})))
(defn- get-node-pos [matrix node-id] (get-in matrix [:positions node-id]))


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

    (letfn [(get-sorted-children [node-id]
              (get sorted-children-map node-id []))

            (get-offset [node-id]
              (get parent-offsets node-id 0))

            ;; Build horizontal branch (chain of first children)
            (build-branch [node-id start-col]
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
            (branch-fits-at-row? [matrix branch row]
              (every? (fn [{:keys [col offset]}]
                        (let [offset (or offset 0)]
                          ;; Check the node cell AND any offset-reserved cells
                          (and (not (cell-occupied? matrix row col))
                               (every? #(not (cell-occupied? matrix row %))
                                       (range (- col offset) col)))))
                      branch))

            ;; Find row where branch fits
            (find-row-for-branch [matrix branch min-row]
              (loop [row min-row]
                (if (branch-fits-at-row? matrix branch row)
                  row
                  (recur (inc row)))))

            ;; Place branch at row, reserving edge cells for offsets
            (place-branch [matrix branch row]
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
            (reserve-vertical-edge [matrix parent-row child-row child-col]
              (if (<= child-row (inc parent-row))
                matrix  ; Adjacent rows, no intermediate cells to reserve
                (reduce (fn [m edge-row]
                          (assoc-in m [:grid [edge-row child-col]]
                                    {:vertical-edge true}))
                        matrix
                        (range (inc parent-row) child-row))))

            ;; Get max row used by a subtree (for computing next sibling's start row)
            (subtree-max-row [matrix node-id]
              (if-let [pos (get-node-pos matrix node-id)]
                (:row pos) 0))

            ;; Recursively find max row in entire subtree rooted at node-id
            (find-subtree-max-row [matrix node-id]
              (let [pos (get-node-pos matrix node-id)
                    my-row (if pos (:row pos) 0)
                    kids (get-sorted-children node-id)]
                (if (empty? kids)
                  my-row
                  (apply max my-row (map #(find-subtree-max-row matrix %) kids)))))

            ;; Main recursive placement function
            ;; Places node-id and its entire subtree, returns [matrix max-row-used]
            ;; parent-row is the row of the parent node (for reserving vertical edges)
            (place-subtree [matrix node-id target-row target-col parent-row]
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

(defn get-layout-data
  "Compute layout from root-id and expansions.
   Input (from request body): {root-id: uuid-string, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}"
  [{:keys [request]} ctx]
  (let [storage (:storage ctx)]
    (when-not storage
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))
    (let [{:keys [root-id expansions]} (parse-layout-request request)
          raw-data (load-graph-entities storage)
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
  {:get-layout-data (with-meta get-layout-data {:ctx true})})
