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
  [arg-map arg]
  (let [root (loop [a arg, depth 0]
               (if (or (> depth 200) (not (:source-id a)))
                 a
                 (recur (get arg-map (:source-id a)) (inc depth))))]
    (false? (:required root))))


;; Forward declarations — these defn-s reference each other. The
;; pure-helper block (target-interface-names / compute-edge-label /
;; build-inverse-source-map / caller-bound-arg) lives below the
;; mutating helpers but is used by them.
(declare target-interface-names
         compute-edge-label
         build-inverse-source-map
         caller-bound-arg
         terminal-arg-of
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


(defn- add-arg-value-node
  "Emit a value-style arg node + edge linking it to `source-node-id`.
   No-op when the node is already present (dedup by `:added-node-ids`).
   Returns the emitted node-id."
  [state lookups arg-name value arg-id source-node-id expanded-fns]
  (let [node-id (str "arg-" source-node-id "-" arg-id)
        edge-id (str "e-val-" source-node-id "-" arg-id)]
    (when-not (contains? (:added-node-ids @state) node-id)
      (swap! state update :added-node-ids conj node-id)
      (let [display-value (truncate-label (json/generate-string value) 20)]
        (swap! state update :nodes conj
               {:data {:id node-id
                       :label display-value
                       :type "arg"
                       ;; Carries the arg-row id so the frontend can
                       ;; PUT /api/entities/arg/<id> from a click
                       ;; without having to parse it out of node-id.
                       :argId (str arg-id)}}))
      (swap! state update :edges conj
             {:data {:id edge-id
                     :source source-node-id
                     :target node-id
                     :sourceArgId arg-id
                     :argName (or (compute-edge-label lookups arg-id source-node-id expanded-fns)
                                  (when arg-name (name arg-name)))}}))
    node-id))


(defn- make-parent-bound-terminals
  "Returns a memoized `(fn [fn-id])` that gives the set of terminal-
   source-ids bound by `fn-id`'s parent-inheritance closure (including
   itself). Bindings inside ref-id targets are NOT included — those are
   scoped to the ref's own call context.

   Memoised because the same fn-id is queried from many call sites
   during a single layout pass."
  [lookups]
  (let [{:keys [fn-map arg-map args-by-fn]} lookups
        cache (atom {})]
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
                             (swap! terms conj (terminal-source-of arg-map (:id a)))))
                         (when-let [f (get fn-map fid)]
                           (doseq [pid (:parent-ids f)]
                             (walk pid)))))]
            (walk fn-id)
            (let [result @terms]
              (swap! cache assoc fn-id result)
              result))))))


(defn- arg-determined?
  "True iff `arg-id`'s terminal source-id is bound by some fn reachable
   via the source chain owners' parent inheritance.

   Walks `arg-id`'s source chain; for each arg in the chain, takes the
   OWNING fn's `parent-bound-terminals` set; checks if the terminal is
   in it.

   This correctly handles MI-propagated args (e.g. `text-error-router.headers`
   → `text-not-found-response` in chain → `text-content-type` binds
   `headers` via MI parent) WITHOUT falsely including ref-target
   bindings (e.g. `method-map` references `assoc-handler` via 'value'
   ref — `assoc-handler.key` binding is scoped to assoc-handler's own
   call, not method-map.key)."
  [arg-map parent-bound-terminals arg-id]
  (let [terminal (terminal-source-of arg-map arg-id)]
    (loop [cur (get arg-map arg-id)]
      (if (nil? cur)
        false
        (let [fid (:fn-id cur)]
          (if (and fid (contains? (parent-bound-terminals fid) terminal))
            true
            (recur (get arg-map (:source-id cur)))))))))


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
        binding-reaches? (fn [b target-id]
                           (let [barg (some-> (:arg-id b) arg-map)
                                 terminal (terminal-arg-of arg-map target-id)]
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
        bound-by-chain? (fn [arg]
                          (loop [sid (:source-id arg)]
                            (when sid
                              (if (contains? bindings sid)
                                true
                                (recur (:source-id (get arg-map sid)))))))
        raw-args (get args-by-fn fn-id [])
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
        child-sources (child-covered-sources-for-fn lookups fn-id :expansion-root-chain expansion-root-chain)
        all-covered-sources (into child-sources displayed-ref-arg-ids)
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
                               source-has-ref (when-let [sid (:source-id arg)]
                                                (let [source-arg (get arg-map sid)]
                                                  (some? (:ref-id source-arg))))
                               defines-own-ref (and has-ref (not source-has-ref))
                               binding-key (or (:id arg) (:source-id arg))
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
                               binding (when (and raw-binding
                                                  (not (binding-goes-to-child? binding-key)))
                                         raw-binding)]
                           (cond
                             (or (and has-ref defines-own-ref)
                                 (and binding (:ref-id binding) (= (:ref-id binding) (:ref-id arg))))
                             {:type :ref :arg-name arg-name
                              :ref-id (:ref-id arg) :arg-id (:id arg)
                              :is-binding false}

                             (and binding (:ref-id binding)
                                  (or (and has-ref (not= (:ref-id binding) (:ref-id arg)))
                                      (and (not has-ref) (not has-value))))
                             {:type :ref :arg-name (:arg-name binding)
                              :ref-id (:ref-id binding) :arg-id (:arg-id binding)
                              :is-binding true}

                             (and binding (some? (:value binding)))
                             {:type :value :arg-name (:arg-name binding)
                              :value (:value binding) :arg-id (:arg-id binding)}

                             (and has-ref (not is-structural))
                             {:type :ref :arg-name arg-name
                              :ref-id (:ref-id arg) :arg-id (:id arg)
                              :is-binding false}

                             (and has-ref is-structural (not defines-own-ref))
                             {:type :unset :arg-name arg-name
                              :arg-type (:type arg) :arg-id (:id arg)}

                             has-value
                             {:type :value :arg-name arg-name
                              :value (:value arg) :arg-id (:id arg)}

                             (or raw-binding (bound-by-chain? arg))
                             nil

                             :else
                             {:type :unset :arg-name arg-name
                              :arg-type (:type arg) :arg-id (:id arg)})))
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
                        {:type :ref
                         :arg-name (resolve-arg-name a arg-map)
                         :ref-id (:ref-id a)
                         :arg-id (:id a)
                         :is-binding false}))))
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
        covered-sources (atom #{})
        result (atom [])
        chain-level (atom 0)
        bound-slot-terminals
        (reduce
          (fn [acc fn-id]
            (reduce
              (fn [acc2 arg]
                (if (or (some? (:value arg)) (some? (:ref-id arg)))
                  (conj acc2 (terminal-source-of arg-map (:id arg)))
                  acc2))
              acc
              (get args-by-fn fn-id [])))
          #{}
          active-fns)]
    (doseq [fn-id active-fns]
      (let [raw-args (get args-by-fn fn-id [])
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
            fn-unsets (atom [])]
        (doseq [arg args]
          (let [arg-id (:id arg)
                source-id (or (:source-id arg) arg-id)
                already-covered (contains? @covered-sources source-id)
                has-value (some? (:value arg))
                has-ref (some? (:ref-id arg))
                shadow-of-bound
                (and (not has-value) (not has-ref)
                     (contains? bound-slot-terminals
                                (terminal-source-of arg-map arg-id)))]
            (when (and (not already-covered) (not shadow-of-bound))
              (loop [sid source-id]
                (when sid
                  (swap! covered-sources conj sid)
                  (recur (:source-id (get arg-map sid)))))
              (let [arg-name (resolve-arg-name arg arg-map)
                    from-ancestor (pos? current-level)]
                (cond
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
        (let [raw-args-of-fn (get args-by-fn fn-id [])
              anchors (filter #(= :sequence (:type %)) raw-args-of-fn)
              from-ancestor (pos? current-level)]
          (doseq [anchor anchors
                  :let [slot-name (or (resolve-arg-name anchor arg-map) "items")]
                  entry (expand-sequence-anchor anchor slot-name arg-map)]
            (swap! result conj (assoc entry :from-ancestor from-ancestor))))
        (doseq [a @fn-refs] (swap! result conj a))
        (doseq [a @fn-values] (swap! result conj a))
        (doseq [a @fn-unsets] (swap! result conj a))
        (swap! chain-level inc)))
    (let [seen (atom #{})
          term (fn [sid]
                 (loop [cur sid]
                   (if-let [a (get arg-map cur)]
                     (if (:source-id a)
                       (recur (:source-id a))
                       (:id a))
                     cur)))]
      (into []
            (keep (fn [arg]
                    (let [t (term (:arg-id arg))
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
  "Source-ids that `fn-id`'s child refs already render — used for binding
   deduplication so the same upstream binding isn't drawn twice (once on
   `fn-id` and once on the child node it actually feeds).

   `expansion-root-chain` (optional): set of fn-ids in the expansion
   root's inheritance chain. Source-ids pointing INTO that chain are
   excluded — they're shared-ancestor args, not child-owned, so the
   parent should still render them."
  [lookups fn-id & {:keys [expansion-root-chain] :or {expansion-root-chain #{}}}]
  (let [{:keys [fn-map args-by-fn]} lookups
        fn-args (get args-by-fn fn-id [])
        child-ref-ids (keep :ref-id fn-args)
        expansion-chain-arg-ids (when (seq expansion-root-chain)
                                  (set (mapcat (fn [eid]
                                                 (map :id (get args-by-fn eid [])))
                                               expansion-root-chain)))]
    (set (mapcat (fn [child-ref-id]
                   (let [child-chain (get-inheritance-chain child-ref-id fn-map)]
                     (mapcat (fn [child-fn-id]
                               (keep (fn [arg]
                                       (when-let [sid (:source-id arg)]
                                         (when-not (and expansion-chain-arg-ids
                                                        (contains? expansion-chain-arg-ids sid))
                                           sid)))
                                     (get args-by-fn child-fn-id [])))
                             child-chain)))
                 child-ref-ids))))


(defn- arg-marks-hof?
  "Does `arg-entity` propagate an `:is-fn=true` marker anywhere in its
   source-id chain? Used to decide whether a ref-binding to another fn
   crosses a HOF boundary."
  [arg-map arg-entity]
  (loop [a arg-entity, depth 0]
    (cond
      (nil? a) false
      (> depth 200) false
      (:is-fn a) true
      :else (recur (get arg-map (:source-id a)) (inc depth)))))


(defn- child-hof
  "HOF context to thread into a child render: ORs the parent's `is-hof`
   with whether `arg-id` crosses the HOF boundary (any source-id chain
   step has `:is-fn=true`). Once HOF, descendants stay HOF."
  [arg-map arg-id is-hof]
  (or is-hof (arg-marks-hof? arg-map (get arg-map arg-id))))


(defn- terminal-arg-of
  "Walks `:source-id` chain from `arg-id` to the terminal arg (one with
   no source-id, or the deepest id we can resolve in `arg-map`).
   Returns the terminal arg ENTITY, or nil if the starting id has no
   entry. Use `terminal-source-of` for the id-only variant."
  [arg-map arg-id]
  (loop [cur (get arg-map arg-id)]
    (if (and cur (:source-id cur))
      (if-let [src (get arg-map (:source-id cur))]
        (recur src)
        cur)
      cur)))


(defn- terminal-source-of
  "Id of `arg-id`'s terminal arg, or `arg-id` itself if no chain entry."
  [arg-map arg-id]
  (or (:id (terminal-arg-of arg-map arg-id)) arg-id))


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
               {:data {:id edge-id
                       :source source-node-id
                       :target node-id
                       :sourceArgId source-arg-id
                       :argName (or (compute-edge-label lookups source-arg-id source-node-id source-expanded-fns)
                                    (when edge-arg-name (name edge-arg-name)))}})))))


(def ^:private max-visible-ancestors
  "Cap on the number of ancestor BFS levels rendered in a fn-node's
   header label before we collapse the rest into `…`."
  4)


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
       optional?
       (record-optional-unset! state source-node-id displayed-name)

       is-hof
       (if-let [bound (caller-bound-arg inverse-source-map arg-id)]
         (swap! state update :captured-edge-migrations assoc (:id bound) source-node-id)
         (record-hof-captured! state source-node-id displayed-name))

       :else
       (let [node-id (str "unset-" source-node-id "-" arg-id)
             edge-id (str "e-unset-" source-node-id "-" arg-id)]
         (when-not (contains? (:added-node-ids @state) node-id)
           (swap! state update :added-node-ids conj node-id)
           (swap! state update :nodes conj
                  {:data {:id node-id
                          :label (if arg-type (name arg-type) "any")
                          :type "fn"
                          :isPlaceholder true}})
           (swap! state update :edges conj
                  {:data {:id edge-id
                          :source source-node-id
                          :target node-id
                          :argName displayed-name
                          :isUnset true}})))))))


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
  "Reverse source-id BFS from `target-arg-id` over `inverse-source-map`:
   returns the FIRST downstream arg with `:value` or `:ref-id` set
   (structurally bound by some caller via cross-HOF source-id chain),
   or nil. Used both to classify captures and to migrate the rendered
   edge from the caller down to the inner consumer node."
  [inverse-source-map target-arg-id]
  (loop [queue [target-arg-id]
         visited #{}]
    (if (empty? queue)
      nil
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
            (if bound
              bound
              (recur (into rest-q (map :id) children)
                     (conj visited cur)))))))))


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
  (let [{:keys [fn-map arg-map args-by-fn]} lookups
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
        parent-bound-terminals (make-parent-bound-terminals lookups)



        ]

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
                                (arg-determined? arg-map parent-bound-terminals arg-id)))

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
                                 (process-any-fn (:ref-id arg) node-id (:arg-name arg) false ref-bindings (:arg-id arg) ref-expansion-root #{display-fn-id} (child-hof arg-map (:arg-id arg) is-hof)))
                          :value (add-arg-value-node state lookups (:arg-name arg) (:value arg) (:arg-id arg) node-id #{display-fn-id})
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

                        migration-target-for
                        (fn [arg]
                          (loop [sid (:arg-id arg)]
                            (when sid
                              (let [a (get arg-map sid)]
                                (or (get fn-id->ancestor-ref-fn-id (:fn-id a))
                                    (recur (:source-id a)))))))

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

                        ;; For a migrated arg, build bindings keyed by the
                        ;; source-chain so the target leaf's find-migrated
                        ;; walk hits them. Keys are the arg's source-chain
                        ;; elements (same format as add-bindings-from-fn).
                        migrated-bindings-for
                        (fn [args]
                          (reduce
                            (fn [b arg]
                              (let [entry {:arg-name (:arg-name arg)
                                           :value (:value arg)
                                           :ref-id (:ref-id arg)
                                           :arg-id (:arg-id arg)}]
                                (loop [sid (:arg-id arg), b b]
                                  (if-not sid
                                    b
                                    (let [a (get arg-map sid)]
                                      (recur (:source-id a)
                                             (assoc b sid entry)))))))
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
                              (loop [sid arg-id]
                                (when sid
                                  (if (contains? parent-bindings sid)
                                    (get parent-bindings sid)
                                    (recur (:source-id (get arg-map sid))))))))
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
                                                    (or (:arg-name m) (:arg-name arg))
                                                    (:value m) (:arg-id arg) node-id expand-set)
                                :else
                                (add-unset-arg-node state lookups inverse-source-map
                                                    (:arg-name arg) (:arg-type arg)
                                                    (:arg-id arg) node-id expand-set is-hof))))]
                      (doseq [arg (filter #(= (:type %) :ref) level-0-stay)]
                        (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) parent-expansion-root expand-set (child-hof arg-map (:arg-id arg) is-hof)))

                      (doseq [arg level-0-unsets] (render-unset arg))

                      (doseq [arg (filter #(= (:type %) :value) level-0-stay)]
                        (add-arg-value-node state lookups (:arg-name arg) (:value arg) (:arg-id arg) node-id expand-set))

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
                        (add-arg-value-node state lookups (:arg-name arg) (:value arg) (:arg-id arg) node-id expand-set))))

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
                                (loop [sid arg-id]
                                  (when sid
                                    (if-let [b (get parent-bindings sid)]
                                      b
                                      (recur (:source-id (get arg-map sid))))))))]
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
                                                      (resolve-arg-name arg arg-map)
                                                      (:value arg) (:id arg) node-id #{fn-id}))

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
                                                      (or (:arg-name migrated)
                                                          (resolve-arg-name arg arg-map))
                                                      (:value migrated) (:id arg)
                                                      node-id #{fn-id}))

                                (and (not has-ref) (not (arg-determined? arg-map parent-bound-terminals (:id arg))))
                                (when (mark-once! [terminal :unset])
                                  (add-unset-arg-node state lookups inverse-source-map
                                                      (resolve-arg-name arg arg-map)
                                                      (:type arg) (:id arg) node-id #{fn-id} is-hof)))))))
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
                            (:edges @state))]
      {:nodes final-nodes
       :edges final-edges})))


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
