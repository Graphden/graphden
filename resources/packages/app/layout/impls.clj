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
        max-visible-ancestors 4

        get-effective-level
        (fn [fn-id]
          (get expansions fn-id 0))

        add-fn-node
        (fn [original-fn-id is-root]
          (let [node-id (str "fn-" original-fn-id)]
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
          (let [node-id (str "arg-" arg-id)
                ;; Edge ID must include source to handle re-parenting when expansion level changes
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
          (let [node-id (str "unset-" arg-id)
                ;; Edge ID must include source to handle re-parenting when expansion level changes
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
        (fn [fn-id bindings]
          (let [args (get args-by-fn fn-id [])
                all-args (mapv (fn [arg]
                                 (let [arg-name (resolve-arg-name arg arg-map)
                                       has-value (some? (:value arg))
                                       has-ref (some? (:ref-id arg))
                                       ;; Binding only applies to UNSET args (no value, no ref)
                                       ;; If arg already has value or ref, use that
                                       binding (when (and (not has-value) (not has-ref))
                                                 (or (get bindings (:id arg))
                                                     (when-let [sid (:source-id arg)]
                                                       (get bindings sid))))]
                                   (cond
                                     binding
                                     (cond
                                       (:ref-id binding)
                                       {:type :ref :arg-name (:arg-name binding)
                                        :ref-id (:ref-id binding) :arg-id (:arg-id binding)}

                                       (some? (:value binding))
                                       {:type :value :arg-name (:arg-name binding)
                                        :value (:value binding) :arg-id (:arg-id binding)}

                                       :else nil)

                                     has-ref
                                     {:type :ref :arg-name arg-name
                                      :ref-id (:ref-id arg) :arg-id (:id arg)}

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
        collect-expanded-args
        (fn [chain level bindings]
          (let [active-fns (take (inc level) chain)
                covered-sources (atom #{})
                result (atom [])]
            ;; Process each fn in chain order (original first, ancestors after)
            (doseq [fn-id active-fns]
              (let [args (get args-by-fn fn-id [])
                    fn-refs (atom [])
                    fn-values (atom [])
                    fn-unsets (atom [])]
                (doseq [arg args]
                  (let [arg-id (:id arg)
                        ;; Check if this arg or its source is already covered
                        source-id (or (:source-id arg) arg-id)
                        already-covered (contains? @covered-sources source-id)]
                    (when-not already-covered
                      ;; Mark as covered (including all sources in chain)
                      (loop [sid source-id]
                        (when sid
                          (swap! covered-sources conj sid)
                          (let [source-arg (get arg-map sid)]
                            (recur (:source-id source-arg)))))
                      ;; Classify the arg
                      (let [arg-name (resolve-arg-name arg arg-map)
                            has-value (some? (:value arg))
                            has-ref (some? (:ref-id arg))
                            binding (get bindings arg-id)]
                        (cond
                          binding
                          (cond
                            (:ref-id binding)
                            (swap! fn-refs conj {:type :ref :arg-name (:arg-name binding)
                                                 :ref-id (:ref-id binding) :arg-id (:arg-id binding)})
                            (some? (:value binding))
                            (swap! fn-values conj {:type :value :arg-name (:arg-name binding)
                                                   :value (:value binding) :arg-id (:arg-id binding)}))

                          has-ref
                          (swap! fn-refs conj {:type :ref :arg-name arg-name
                                               :ref-id (:ref-id arg) :arg-id arg-id})

                          has-value
                          (swap! fn-values conj {:type :value :arg-name arg-name
                                                 :value (:value arg) :arg-id arg-id})

                          :else
                          (swap! fn-unsets conj {:type :unset :arg-name arg-name
                                                 :arg-type (:type arg) :arg-id arg-id}))))))
                ;; Add this fn's args in order: refs, values, unsets
                (doseq [a @fn-refs] (swap! result conj a))
                (doseq [a @fn-values] (swap! result conj a))
                (doseq [a @fn-unsets] (swap! result conj a))))
            @result))]

    ;; Declare process-any-fn before using it
    (letfn [(process-fn [original-fn-id display-fn-id bindings source-node-id edge-arg-name is-root source-arg-id]
              (let [node-id (add-fn-node original-fn-id is-root)]
                ;; Add edge from parent
                (when (and source-node-id edge-arg-name)
                  (let [edge-id (str "e-ref-" source-node-id "-" original-fn-id)
                        arg-target-key (when source-arg-id
                                         (str source-arg-id "->" original-fn-id))
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

                ;; Process children
                (let [all-args (collect-fn-args display-fn-id bindings)]
                  (doseq [arg all-args]
                    (case (:type arg)
                      :ref (process-any-fn (:ref-id arg) node-id (:arg-name arg) false bindings (:arg-id arg))
                      :value (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)
                      :unset (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id)
                      nil)))
                node-id))

            (process-expanded-fn [original-fn-id level source-node-id edge-arg-name is-root source-arg-id parent-bindings]
              (let [chain (get-inheritance-chain original-fn-id fn-map)
                    display-fn-id (nth chain (min level (dec (count chain))) original-fn-id)
                    ;; Build chain bindings to pass to nested fns
                    ;; This allows ref-fns to know about bindings from the ancestor chain
                    ;; Merge with parent-bindings (parent takes precedence)
                    base-chain-bindings (build-chain-bindings chain (inc level) args-by-fn arg-map)
                    chain-bindings (merge base-chain-bindings parent-bindings)
                    ;; Add the node
                    node-id (add-fn-node original-fn-id is-root)]

                ;; Add edge from parent
                (when (and source-node-id edge-arg-name)
                  (let [edge-id (str "e-ref-" source-node-id "-" original-fn-id)
                        arg-target-key (when source-arg-id
                                         (str source-arg-id "->" original-fn-id))
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
                ;; This shows all args from the expansion chain with proper ordering
                ;; The chain-bindings ensure that unset args show bound values
                (let [all-args (collect-expanded-args chain level chain-bindings)]
                  (doseq [arg all-args]
                    (case (:type arg)
                      :ref (process-any-fn (:ref-id arg) node-id (:arg-name arg) false chain-bindings (:arg-id arg))
                      :value (add-arg-value-node (:arg-name arg) (:value arg) (:arg-id arg) node-id)
                      :unset (add-unset-arg-node (:arg-name arg) (:arg-type arg) (:arg-id arg) node-id)
                      nil)))

                node-id))

            (process-any-fn [fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id]
              (let [level (get-effective-level fn-id)]
                (if (> level 0)
                  ;; Expanded mode - process-expanded-fn builds its own chain-bindings
                  ;; but we need to pass parent-bindings for merging
                  (process-expanded-fn fn-id level source-node-id edge-arg-name is-root source-arg-id parent-bindings)
                  (let [bindings (build-arg-bindings fn-id args-by-fn arg-map)
                        ;; Merge parent bindings - parent takes precedence
                        bindings (if parent-bindings
                                   (merge bindings parent-bindings)
                                   bindings)]
                    (process-fn fn-id fn-id bindings source-node-id edge-arg-name is-root source-arg-id)))))]

      ;; Start processing from root
      (process-any-fn root-fn-id nil nil true nil nil))

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


(defn- order-children
  "Order children for layout placement.

   Rules (in priority order):
   1. Regular children (not leading to shared) come FIRST
      - Sort by type: fn > fixed > free (fn on horizontal branch)
      - Within same type, preserve original edge order
   2. Children leading to shared nodes come LAST
      - These go below regular children
      - Longer paths before shorter

   This ensures:
   - fn children are on horizontal branch (same row as parent)
   - fixed/free children are below
   - Paths to shared nodes are at the bottom"
  [parent-id children-map paths-to-shared shared-nodes graph-children node-data-map]
  (let [child-ids (get children-map parent-id [])
        type-order {:fn 0 :fixed 1 :free 2}

        classify-child
        (fn [child-id idx]
          (let [targets (get paths-to-shared child-id #{})
                child-type (get-child-type child-id node-data-map)]
            {:id child-id
             :type child-type
             :original-idx idx
             :targets targets
             :primary-target (first (sort targets))
             :path-len (if (seq targets)
                         (apply min (map #(or (path-length-to-shared child-id % graph-children) 999) targets))
                         0)}))

        classified (map-indexed (fn [idx id] (classify-child id idx)) child-ids)

        ;; Separate into groups: those leading to shared nodes vs those without
        with-targets (filter #(seq (:targets %)) classified)
        without-targets (filter #(empty? (:targets %)) classified)

        ;; Regular children: sort by type (fn first for horizontal branch)
        ;; Within same type, preserve original edge order
        sorted-regular (sort-by (fn [c] [(get type-order (:type c) 2) (:original-idx c)]) without-targets)

        ;; Shared-path children: group by target, longer paths first within group
        by-target (group-by :primary-target with-targets)
        sorted-groups (mapcat (fn [[_target group]]
                                (sort-by (fn [c] [(- (:path-len c)) (:original-idx c)]) group))
                              (sort-by first by-target))]

    ;; Regular children FIRST, shared-path children LAST
    (vec (concat (map :id sorted-regular)
                 (map :id sorted-groups)))))


;; Matrix operations
(defn- empty-matrix [] {:grid {} :positions {}})
(defn- get-cell [matrix row col] (get (:grid matrix) [row col]))
(defn- cell-occupied? [matrix row col] (some? (get-cell matrix row col)))
(defn- place-node-in-matrix [matrix node-id row col]
  (-> matrix
      (assoc-in [:grid [row col]] node-id)
      (assoc-in [:positions node-id] {:row row :col col})))
(defn- get-node-pos [matrix node-id] (get-in matrix [:positions node-id]))


(defn- layout-graph
  "Main layout function - simple and uniform.

   Pre-calculation phase (done before this function):
   - Graph is fully built with all nodes (including expanded ancestors)
   - Children lists are already sorted (fn > fixed > free, shared-path last)

   This function just fills the matrix uniformly:
   - First child goes on horizontal branch (same row as parent)
   - Other children go below (each on own row)
   - No special handling for shared/expanded - they're just regular nodes"
  [root-id graph-info]
  (let [{:keys [children parents shared-nodes node-data-map]} graph-info
        paths-to-shared (find-paths-to-shared children shared-nodes)

        ;; Pre-calculate sorted children for each node
        sorted-children-map
        (into {}
              (map (fn [node-id]
                     [node-id (order-children node-id children paths-to-shared
                                              shared-nodes children node-data-map)])
                   (keys node-data-map)))]

    (letfn [(get-sorted-children [node-id]
              (get sorted-children-map node-id []))

            ;; Build horizontal branch (chain of first children)
            (build-branch [node-id start-col visited]
              (loop [current node-id
                     col start-col
                     branch []]
                (if (or (nil? current) (contains? visited current))
                  branch
                  (let [branch (conj branch {:id current :col col})
                        children (get-sorted-children current)
                        first-child (first children)]
                    (if first-child
                      (recur first-child (inc col) branch)
                      branch)))))

            ;; Check if entire branch fits at given row
            (branch-fits? [matrix branch row]
              (not-any? (fn [{:keys [col]}]
                          (cell-occupied? matrix row col))
                        branch))

            ;; Find row where entire branch fits
            (find-branch-row [matrix branch min-row]
              (loop [row min-row]
                (if (branch-fits? matrix branch row)
                  row
                  (recur (inc row)))))

            ;; Place entire branch at given row
            (place-branch [matrix branch row]
              (reduce (fn [m {:keys [id col]}]
                        (place-node-in-matrix m id row col))
                      matrix
                      branch))

            ;; Layout a subtree starting from node-id
            (layout-subtree [matrix node-id target-row target-col visited]
              (if (contains? visited node-id)
                [matrix visited]
                (let [;; Build horizontal branch starting from this node
                      branch (build-branch node-id target-col visited)
                      ;; Find row where branch fits
                      actual-row (find-branch-row matrix branch target-row)
                      ;; Place the branch
                      matrix (place-branch matrix branch actual-row)
                      ;; Mark all branch nodes as visited
                      visited (into visited (map :id branch))]

                  ;; Process non-first children of each node in the branch
                  ;; IMPORTANT: Process right-to-left (reverse branch) so that
                  ;; children of deeper nodes are placed before siblings of shallower nodes.
                  ;; This ensures delete-entity-route's path is placed right after its
                  ;; horizontal branch, not after all sibling routes.
                  (reduce
                    (fn [[matrix visited] {:keys [id col]}]
                      (let [children (get-sorted-children id)
                            rest-children (rest children)  ; skip first (already in branch)
                            child-col (inc col)]
                        ;; Place each remaining child below
                        (loop [remaining rest-children
                               next-row (inc actual-row)
                               matrix matrix
                               visited visited]
                          (if (empty? remaining)
                            [matrix visited]
                            (let [child-id (first remaining)
                                  [matrix visited] (layout-subtree matrix child-id next-row child-col visited)
                                  ;; Find max row used by this child's subtree
                                  child-pos (get-node-pos matrix child-id)
                                  subtree-max-row (if child-pos (:row child-pos) next-row)]
                              (recur (rest remaining)
                                     (inc subtree-max-row)
                                     matrix
                                     visited))))))
                    [matrix visited]
                    (reverse branch)))))]

      (let [[matrix _] (layout-subtree (empty-matrix) root-id 0 0 #{})]
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
