(ns graphden.packages.app.layout.impls
  "Graph layout calculation - fetches data from DB, builds graph, computes layout.

   API: POST /api/graph/layout
   Input: {root-id: uuid, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}}

   Core layout rules:
   1. Children of a node are placed RIGHT of parent, never above
   2. First child is on SAME ROW as parent, others are BELOW (each on own row)
   3. Horizontal branch = chain of first children
   4. Shared nodes (multiple parents) go in horizontal branch of LAST parent
   5. Splitting siblings (leading to same shared node) must be adjacent in child list"
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core VersionedStorage)))


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

(defn- get-inheritance-chain
  "Get inheritance chain: [fn-id, parent-id, grandparent-id, ...]"
  [fn-id fn-map]
  (loop [current fn-id
         chain []
         visited #{}]
    (if (or (nil? current) (contains? visited current))
      chain
      (let [f (get fn-map current)]
        (recur (:parent-id f)
               (conj chain current)
               (conj visited current))))))


(defn- resolve-arg-name
  "Resolve arg name by following source chain."
  [arg arg-map]
  (loop [current arg
         depth 0]
    (cond
      (nil? current) nil
      (> depth 100) nil
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
  "Build bindings for inheritance chain up to target level.
   Chain is [self, parent, grandparent, ...].
   We want bindings from descendant fns (those that override ancestor args).
   So we take fns [0..target-level) from the chain (without reverse)."
  [chain target-level args-by-fn arg-map]
  (reduce
    (fn [bindings fn-id]
      (add-bindings-from-fn fn-id bindings args-by-fn arg-map))
    {}
    (take target-level chain)))


(defn- build-arg-bindings
  "Build bindings just from the fn itself."
  [fn-id args-by-fn arg-map]
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
        max-visible-ancestors 4

        get-effective-level
        (fn [fn-id]
          (get expansions fn-id 0))

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
                node-id (if use-expansion-prefix
                          (str "fn-" expansion-root "-" original-fn-id)
                          (str "fn-" original-fn-id))]
            (when-not (contains? @added-node-ids node-id)
              (swap! added-node-ids conj node-id)
              (let [chain (get-inheritance-chain original-fn-id fn-map)
                    label-lines (mapv (fn [fid]
                                        (let [f (get fn-map fid)]
                                          (if (:name f)
                                            (name (:name f))
                                            "(anonymous)")))
                                      (take (inc max-visible-ancestors) chain))
                    label-lines (if (> (count chain) (inc max-visible-ancestors))
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

        collect-fn-args
        ;; is-structural: true when collecting args for a structural node inside expansion
        ;; For structural nodes, unbound refs (no binding) should be shown as unset, not as direct refs
        ;; This prevents false sharing with other fns that happen to have the same ancestor ref-id
        (fn [fn-id bindings & {:keys [is-structural] :or {is-structural false}}]
          (let [args (get args-by-fn fn-id [])
                all-args (mapv (fn [arg]
                                 (let [arg-name (resolve-arg-name arg arg-map)
                                       has-value (some? (:value arg))
                                       has-ref (some? (:ref-id arg))
                                       ;; Check for binding - applies to UNSET args
                                       ;; For structural nodes, also check binding for args with ref
                                       binding (or (get bindings (:id arg))
                                                   (when-let [sid (:source-id arg)]
                                                     (get bindings sid)))]
                                   (cond
                                     ;; Binding ref - these come from bindings map, should use canonical ID
                                     ;; Mark with :is-binding true so process-fn knows to use nil expansion-root
                                     (and binding (:ref-id binding))
                                     {:type :ref :arg-name (:arg-name binding)
                                      :ref-id (:ref-id binding) :arg-id (:arg-id binding)
                                      :is-binding true}

                                     (and binding (some? (:value binding)))
                                     {:type :value :arg-name (:arg-name binding)
                                      :value (:value binding) :arg-id (:arg-id binding)}

                                     ;; Direct ref - but for structural nodes WITHOUT binding, treat as unset
                                     ;; This prevents pair-1.item1 from pointing to favicon-route
                                     (and has-ref (not is-structural))
                                     {:type :ref :arg-name arg-name
                                      :ref-id (:ref-id arg) :arg-id (:id arg)
                                      :is-binding false}

                                     ;; Structural node with unbound ref - treat as unset
                                     (and has-ref is-structural)
                                     {:type :unset :arg-name arg-name
                                      :arg-type (:type arg) :arg-id (:id arg)}

                                     has-value
                                     {:type :value :arg-name arg-name
                                      :value (:value arg) :arg-id (:id arg)}

                                     :else
                                     {:type :unset :arg-name arg-name
                                      :arg-type (:type arg) :arg-id (:id arg)})))
                               args)
                ;; Sort args: refs first (fn type), then values (fixed), then unset (free)
                type-order {:ref 0 :value 1 :unset 2}
                sorted-args (sort-by #(get type-order (:type %) 3) all-args)]
            (filterv some? sorted-args)))

        ;; Collect args from chain [0..level] with proper ordering:
        ;; For each fn in chain (from original to display-fn):
        ;;   - refs first, then values, then unset
        ;; Args covered by earlier fns in chain are skipped
        ;; Returns args with :from-ancestor flag indicating if arg came from ancestor (level > 0)
        ;;
        ;; Key insight: when a ref-fn is shown, value args that bind TO that ref-fn's args
        ;; should not be shown as direct children - they become part of the ref-fn's display
        collect-expanded-args
        (fn [chain level bindings]
          (let [active-fns (take (inc level) chain)
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
                ref-fn-arg-ids (atom #{})
                _ (doseq [ref-fn-id @ref-fn-ids]
                    (let [ref-chain (get-inheritance-chain ref-fn-id fn-map)]
                      (doseq [rfn-id ref-chain]
                        (let [ref-args (get args-by-fn rfn-id [])]
                          (doseq [ra ref-args]
                            (swap! ref-fn-arg-ids conj (:id ra)))))))
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
                    fn-unsets (atom [])]
                (doseq [arg args]
                  (let [arg-id (:id arg)
                        source-id (or (:source-id arg) arg-id)
                        already-covered (contains? @covered-sources source-id)
                        has-value (some? (:value arg))
                        has-ref (some? (:ref-id arg))
                        binding (get bindings arg-id)
                        ;; Check if ANY arg in the source chain is an arg of a displayed ref-fn
                        ;; This applies to BOTH value and ref args - if the arg's source chain
                        ;; leads to an arg inside a displayed ref-fn, this arg is a binding for
                        ;; that ref-fn's arg and should be shown inside, not as direct child
                        binds-to-ref-fn (and (:source-id arg)
                                             (source-chain-binds-to-ref-fn (:source-id arg)))]
                    ;; TEMPORARILY disable binds-to-ref-fn check - it's too aggressive
                    ;; TODO: fix the logic to only exclude args that bind to DISPLAYED ref-fn args
                    (when (not already-covered)
                      ;; Mark as covered (including all sources in chain)
                      (loop [sid source-id]
                        (when sid
                          (swap! covered-sources conj sid)
                          (let [source-arg (get arg-map sid)]
                            (recur (:source-id source-arg)))))
                      ;; Classify the arg - add :from-ancestor flag
                      (let [arg-name (resolve-arg-name arg arg-map)
                            from-ancestor (> current-level 0)]
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
                  (let [all-args (collect-fn-args display-fn-id bindings :is-structural (some? expansion-root))]
                    (doseq [arg all-args]
                      (case (:type arg)
                        ;; For refs: binding refs use canonical ID (nil expansion-root)
                        ;; structural refs use expansion context
                        ;; IMPORTANT: when switching to canonical mode (nil expansion-root),
                        ;; don't pass expansion bindings - they don't apply to canonical nodes
                        :ref (let [ref-expansion-root (if (:is-binding arg) nil expansion-root)
                                   ref-bindings (if (:is-binding arg) {} bindings)]
                               (process-any-fn (:ref-id arg) node-id (:arg-name arg) false ref-bindings (:arg-id arg) ref-expansion-root))
                        :value (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)
                        :unset (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id)
                        nil))))
                node-id))

            (process-expanded-fn [original-fn-id level source-node-id edge-arg-name is-root source-arg-id parent-bindings]
              (let [chain (get-inheritance-chain original-fn-id fn-map)
                    display-fn-id (nth chain (min level (dec (count chain))) original-fn-id)
                    ;; Build chain bindings to pass to nested fns
                    ;; This allows ref-fns to know about bindings from the ancestor chain
                    ;; Merge with parent-bindings (parent takes precedence)
                    base-chain-bindings (build-chain-bindings chain (inc level) args-by-fn arg-map)
                    chain-bindings (merge base-chain-bindings parent-bindings)
                    ;; Add the node - expansion root is nil because this IS the expansion root
                    node-id (add-fn-node original-fn-id is-root nil)]


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
                (let [all-args (collect-expanded-args chain level chain-bindings)
                      ;; Separate by type and origin
                      ancestor-refs (filter #(and (:from-ancestor %) (= (:type %) :ref)) all-args)
                      ancestor-values (filter #(and (:from-ancestor %) (= (:type %) :value)) all-args)
                      ancestor-unsets (filter #(and (:from-ancestor %) (= (:type %) :unset)) all-args)
                      level-0-args (filter #(not (:from-ancestor %)) all-args)
                      level-0-refs (filter #(= (:type %) :ref) level-0-args)
                      level-0-values (filter #(= (:type %) :value) level-0-args)
                      level-0-unsets (filter #(= (:type %) :unset) level-0-args)

                      has-ancestor-refs (seq ancestor-refs)]

                  ;; Store info for deduplication
                  (swap! expansion-bindings assoc original-fn-id
                         {:has-ancestor-refs has-ancestor-refs})

                  ;; Show ancestor refs (structural expansion)
                  ;; These will show bindings as their children
                  (doseq [arg ancestor-refs]
                    (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) original-fn-id))

                  ;; For bindings (level-0 refs/values and ancestor values):
                  ;; Show at root ONLY if they DON'T flow to ancestor refs
                  ;;
                  ;; How to know if a binding flows to an ancestor ref?
                  ;; The binding's source chain must lead to an arg of one of the ancestor ref fns
                  ;;
                  ;; For simplicity: track which arg sources are "covered" by ancestor refs
                  ;; Then show uncovered bindings at root
                  (let [;; Collect all arg-ids that ancestor refs will resolve
                        ;; (by looking at their inheritance chains)
                        ancestor-ref-arg-sources
                        (into #{}
                              (mapcat (fn [ref]
                                        (let [ref-chain (get-inheritance-chain (:ref-id ref) fn-map)]
                                          (mapcat (fn [fn-id]
                                                    (map :id (get args-by-fn fn-id [])))
                                                  ref-chain)))
                                      ancestor-refs))

                        ;; Check if a binding's source chain leads to ancestor ref args
                        binding-covered?
                        (fn [arg]
                          (loop [sid (:arg-id arg)]
                            (when sid
                              (if (contains? ancestor-ref-arg-sources sid)
                                true
                                (let [src-arg (get arg-map sid)]
                                  (recur (:source-id src-arg)))))))]

                    ;; Level-0 refs: show if not covered by ancestor refs
                    (doseq [arg level-0-refs]
                      (when-not (binding-covered? arg)
                        (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg) nil)))

                    ;; Level-0 values: show if not covered
                    (doseq [arg level-0-values]
                      (when-not (binding-covered? arg)
                        (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)))

                    ;; Ancestor values: show if not covered
                    (doseq [arg ancestor-values]
                      (when-not (binding-covered? arg)
                        (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id))))

                  ;; Show all unsets (free args)
                  (doseq [arg (concat level-0-unsets ancestor-unsets)]
                    (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id)))

                node-id))

            (process-any-fn [fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id expansion-root]
              (let [level (get-effective-level fn-id)]
                (if (> level 0)
                  ;; Expanded mode - process-expanded-fn will set its own expansion-root
                  (process-expanded-fn fn-id level source-node-id edge-arg-name is-root source-arg-id parent-bindings)
                  (let [bindings (build-arg-bindings fn-id args-by-fn arg-map)
                        ;; Merge parent bindings - parent takes precedence
                        bindings (if parent-bindings
                                   (merge bindings parent-bindings)
                                   bindings)]
                    (process-fn fn-id fn-id bindings source-node-id edge-arg-name is-root source-arg-id expansion-root)))))]

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
  (let [has-parent (into #{} (map #(get-in % [:data :target]) edges))]
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
      (nil? data) :free
      (:isPlaceholder data) :free
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

   A node is on a 'lower' path if it IS the bottom parent of a shared node,
   OR if it is an ANCESTOR of the bottom parent that leads to the shared node.

   This is used to determine child ordering:
   - Lower path: path-to-shared children go FIRST (at top)
   - Upper path: path-to-shared children go LAST (at bottom)

   NOTE: Divergence roots DO get path-position (for ordering their own children),
   but they are handled specially in order-children (grouped together, not sorted
   by path-position relative to siblings)."
  [children parents shared-nodes]
  (let [paths-to-shared (find-paths-to-shared children shared-nodes)

        ;; For each shared node, find the 'bottom' parent (last in list)
        ;; and trace the path UPWARD to find all ancestors on the lower path
        lower-path-nodes (atom #{})

        ;; For each shared node, mark its bottom parent AND all ancestors
        ;; that lead to it as 'lower path'
        _ (doseq [shared-id shared-nodes]
            (let [parent-ids (get parents shared-id [])]
              (when (seq parent-ids)
                (let [bottom-parent (last parent-ids)]
                  ;; The bottom parent is on lower path
                  (swap! lower-path-nodes conj bottom-parent)
                  ;; Propagate UP: mark all ancestors that lead to this shared node
                  ;; An ancestor is on lower path if it has a child that leads to shared
                  ;; and that child is on the lower path
                  (loop [to-check [bottom-parent]]
                    (when (seq to-check)
                      (let [node-id (first to-check)
                            node-parents (get parents node-id [])]
                        ;; For each parent of this node
                        (doseq [parent-id node-parents]
                          ;; Check if parent leads to this shared node
                          (when (contains? (get paths-to-shared parent-id #{}) shared-id)
                            ;; Parent is on lower path
                            (when-not (contains? @lower-path-nodes parent-id)
                              (swap! lower-path-nodes conj parent-id))))
                        ;; Continue with parents
                        (recur (into (vec (rest to-check))
                                     (filter #(and (contains? (get paths-to-shared % #{}) shared-id)
                                                   (not (contains? @lower-path-nodes %)))
                                             node-parents))))))))))]

    ;; Build result map
    (reduce (fn [m node-id]
              (cond
                ;; On lower path (bottom parent or ancestor leading to shared via bottom)
                (contains? @lower-path-nodes node-id) (assoc m node-id :lower)
                ;; On path to shared but not lower = upper
                (seq (get paths-to-shared node-id #{})) (assoc m node-id :upper)
                :else m))
            {}
            (keys children))))


(defn- order-children
  "Order children for layout placement.

   Rules depend on whether parent is on lower or upper path to shared:

   LOWER path (last parent of shared):
   - Path-to-shared children go FIRST (at top)
   - This keeps the shared node close to this branch

   UPPER path (not last parent):
   - Path-to-shared children go LAST (at bottom)
   - This pushes the shared node away from this branch

   IMPORTANT: Divergence roots (siblings that both lead to same shared node)
   must be GROUPED TOGETHER. They appear at the position of the earliest
   divergence root in the original order.

   WITHIN a divergence group: if parent is on lower path, the divergence root
   that is ALSO on lower path comes first. This ensures the lower-path route
   gets placed first, keeping its subtree closer to the shared node.

   Within each group, sort by: type (fn > fixed > free), then original-idx"
  [parent-id children-map paths-to-shared shared-nodes graph-children node-data-map path-position divergence-roots all-path-positions]
  (let [child-ids (get children-map parent-id [])
        type-order {:fn 0 :fixed 1 :free 2}
        ;; Is this parent on the lower path?
        is-lower-path (= path-position :lower)

        classify-child
        (fn [child-id idx]
          (let [targets (get paths-to-shared child-id #{})
                child-type (get-child-type child-id node-data-map)
                ;; Check if this child IS a shared node (direct shared child)
                is-shared-node (contains? shared-nodes child-id)
                ;; Check if this child is a divergence root - should NOT be treated as path-to-shared
                is-divergence-root (contains? divergence-roots child-id)]
            {:id child-id
             :type child-type
             :original-idx idx
             :targets targets
             :is-shared-node is-shared-node
             :is-divergence-root is-divergence-root
             :primary-target (first (sort targets))
             :path-len (if (seq targets)
                         (apply min (map #(or (path-length-to-shared child-id % graph-children) 999) targets))
                         0)}))

        classified (map-indexed (fn [idx id] (classify-child id idx)) child-ids)

        ;; Separate into groups:
        ;; 1. Direct shared children - the shared node itself (treat as regular)
        ;; 2. Divergence roots - siblings that lead to shared but are the divergence point
        ;; 3. Regular children - not leading to shared
        ;; 4. Path-to-shared children - leading to shared, not divergence root
        direct-shared (filter :is-shared-node classified)
        not-direct-shared (filter (complement :is-shared-node) classified)
        divergence-roots-children (filter :is-divergence-root not-direct-shared)
        not-divergence (filter (complement :is-divergence-root) not-direct-shared)
        with-targets (filter #(seq (:targets %)) not-divergence)
        without-targets (filter #(empty? (:targets %)) not-divergence)

        ;; Group divergence roots by their shared target
        ;; Each group will be placed together at the position of its earliest member
        divergence-by-target (group-by :primary-target divergence-roots-children)
        ;; For each divergence group, compute min-idx (position of earliest member)
        ;; IMPORTANT: Sort members within group so that lower-path comes first (if parent is on lower path)
        divergence-groups (map (fn [[target members]]
                                 (let [;; Sort by path position: lower path first when parent is on lower path
                                       ;; Otherwise sort by original-idx to preserve stability
                                       sorted-members
                                       (if is-lower-path
                                         ;; On lower path: lower-path divergence root should be LAST
                                         ;; This ensures shared node ends up at bottom
                                         (sort-by (fn [m]
                                                    ;; Lower path = 1 (last), otherwise 0 (first)
                                                    (if (= :lower (get all-path-positions (:id m)))
                                                      1
                                                      0))
                                                  members)
                                         ;; On upper path (or no path): original order
                                         (sort-by :original-idx members))
                                       min-idx (:original-idx (first (sort-by :original-idx members)))]
                                   {:target target
                                    :min-idx min-idx
                                    :members sorted-members}))
                               divergence-by-target)

        ;; Merge divergence groups with regular children (direct-shared + without-targets)
        ;; We'll create a unified list where divergence groups are treated as single units
        ;; at their min-idx position
        regular-items (concat direct-shared without-targets)
        ;; Convert regular items to same format
        regular-with-idx (map (fn [c] {:type :single :min-idx (:original-idx c) :child c}) regular-items)
        ;; Convert divergence groups to same format
        divergence-with-idx (map (fn [g] {:type :group :min-idx (:min-idx g) :members (:members g)}) divergence-groups)
        ;; Combine and sort by min-idx
        all-regular-units (sort-by :min-idx (concat regular-with-idx divergence-with-idx))
        ;; Flatten back to child list (preserving group ordering)
        sorted-regular-ids (mapcat (fn [unit]
                                     (case (:type unit)
                                       :single [(:id (:child unit))]
                                       :group (map :id (:members unit))))
                                   all-regular-units)

        ;; Shared-path children (excluding divergence roots): group by target, PRESERVE original order
        by-target (group-by :primary-target with-targets)
        sorted-groups (mapcat (fn [[_target group]]
                                (sort-by :original-idx group))
                              (sort-by first by-target))

        ;; Extract divergence root IDs (grouped by target, sorted by original-idx within group)
        ;; These are path-to-shared but need to stay grouped together
        divergence-ids (mapcat (fn [g] (map :id (:members g)))
                               (sort-by :min-idx divergence-groups))

        ;; True regular children (direct-shared + without-targets, NOT including divergence roots)
        true-regular-ids (mapcat (fn [unit]
                                   (when (= (:type unit) :single)
                                     [(:id (:child unit))]))
                                 (sort-by :min-idx regular-with-idx))]

    ;; Order depends on path position:
    ;; The goal: shared node should be at the BOTTOM so all edges go DOWN to reach it.
    ;; - Lower path (bottom parent's path): put path-to-shared children LAST (larger rows)
    ;;   so the shared node ends up at the bottom
    ;; - Upper path: put path-to-shared children FIRST (smaller rows)
    ;;   so their path eventually leads DOWN to the shared node
    (if is-lower-path
      (vec (concat true-regular-ids              ; regular first (smaller rows)
                   (map :id sorted-groups)       ; then other path-to-shared
                   divergence-ids))              ; divergence roots last (they lead to shared, larger rows)
      (vec (concat divergence-ids                ; divergence roots first (smaller rows)
                   (map :id sorted-groups)       ; then other path-to-shared
                   true-regular-ids)))))         ; regular last           ; then divergence roots


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
  "For each shared node, compute how much each parent needs to be shifted right.

   Logic:
   1. Find all parents of shared node
   2. Compute their column depths (distance from root)
   3. Find max depth (rightmost parent)
   4. For each parent, offset = max_depth - their_depth

   Returns map: parent-id -> offset (number of extra columns to shift right)"
  [shared-nodes parents-map column-depths]
  (reduce
    (fn [offsets shared-id]
      (let [parent-ids (get parents-map shared-id [])
            parent-depths (map (fn [pid] [pid (get column-depths pid 0)]) parent-ids)
            max-depth (if (seq parent-depths)
                        (apply max (map second parent-depths))
                        0)]
        (reduce
          (fn [offs [pid depth]]
            (let [needed-offset (- max-depth depth)]
              (if (> needed-offset 0)
                ;; Take max if parent already has an offset from another shared node
                (update offs pid (fn [old] (max (or old 0) needed-offset)))
                offs)))
          offsets
          parent-depths)))
    {}
    shared-nodes))


(defn- remove-shared-from-upper-parents
  "For each shared node, remove it from children lists of all parents EXCEPT the last one.

   This ensures:
   - Upper parents don't process the shared node at all
   - Shared node is placed only in the branch of the bottom parent
   - Edges still exist for drawing (edges are separate from children-map)"
  [children-map parents-map shared-nodes]
  (reduce
    (fn [cm shared-id]
      (let [parent-ids (get parents-map shared-id [])
            upper-parents (butlast parent-ids)]
        (reduce
          (fn [cm2 parent-id]
            (update cm2 parent-id (fn [kids] (vec (remove #(= % shared-id) kids)))))
          cm
          upper-parents)))
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
        sorted-children-map
        (into {}
              (map (fn [node-id]
                     [node-id (order-children node-id children paths-to-shared
                                              shared-nodes children node-data-map
                                              (get path-positions node-id)
                                              divergence-roots
                                              path-positions)])
                   (keys node-data-map)))

        ;; Remove shared nodes from upper parents' children lists
        sorted-children-map (remove-shared-from-upper-parents
                              sorted-children-map parents shared-nodes)

        ;; Compute column offsets for upper parents of shared nodes
        column-depths (compute-column-depths root-id children)
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

            ;; Calculate branch length including offsets
            (branch-length [branch]
              (if (empty? branch)
                0
                (let [last-node (last branch)]
                  (inc (:col last-node)))))

            ;; Check if branch fits at row (all cells from row down must be free)
            ;; We check that the entire horizontal span is free at this row
            ;; and that we can extend down for children
            (branch-fits-at-row? [matrix branch row]
              (every? (fn [{:keys [col]}]
                        (not (cell-occupied? matrix row col)))
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
                  (let [m (place-node-in-matrix m id row col)]
                    ;; Reserve cells for horizontal edge if offset > 0
                    (if (> offset 0)
                      (reduce (fn [m2 edge-col]
                                (assoc-in m2 [:grid [row edge-col]]
                                          {:edge-from (- col offset 1) :edge-to id}))
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
              (let [pos (get-node-pos matrix node-id)]
                (if pos (:row pos) 0)))

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
                          this-node-row actual-row]

                      ;; Place this node's remaining children
                      ;; Each starts search from (inc actual-row), find-row-for-branch
                      ;; will find where it actually fits based on column occupancy.
                      (let [min-child-row (inc actual-row)
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
                               (max global-max-row local-max-row))))))))]

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


(defn get-layout-data
  "Compute layout from root-id and expansions.
   Input (from request body): {root-id: uuid-string, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}"
  [{:keys [request]} ctx]
  (let [storage (:storage ctx)
        body-str (:body request)
        body (when (and body-str (not (str/blank? body-str)))
               (json/parse-string body-str true))
        root-id-str (:root-id body)
        expansions-raw (:expansions body {})]

    (when-not storage
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))

    (when-not root-id-str
      (throw (ex-info "Request body must contain 'root-id'"
                      {:type :execution-error/invalid-args})))

    (let [;; Parse root-id
          root-id (java.util.UUID/fromString root-id-str)

          ;; Parse expansions: {"uuid-string": level} -> {uuid: level}
          expansions (into {}
                           (map (fn [[k v]]
                                  [(java.util.UUID/fromString (name k)) v])
                                expansions-raw))

          ;; Load data from storage
          raw-data (load-graph-entities storage)
          lookups (build-lookups raw-data)

          ;; Verify root exists
          _ (when-not (get (:fn-map lookups) root-id)
              (throw (ex-info "Root function not found"
                              {:type :execution-error/not-found
                               :root-id root-id})))

          ;; Build graph elements
          {:keys [nodes edges]} (build-graph-elements root-id expansions lookups)

          ;; Compute layout
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
