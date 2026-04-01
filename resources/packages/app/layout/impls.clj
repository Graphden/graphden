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
  "Build bindings for inheritance chain up to target level."
  [chain target-level args-by-fn arg-map]
  (reduce
    (fn [bindings fn-id]
      (add-bindings-from-fn fn-id bindings args-by-fn arg-map))
    {}
    (take target-level (reverse chain))))


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
          (let [node-id (str "arg-" arg-id)]
            (when-not (contains? @added-node-ids node-id)
              (swap! added-node-ids conj node-id)
              (let [display-value (truncate-label (json/generate-string value) 20)]
                (swap! nodes conj
                       {:data {:id node-id
                               :label display-value
                               :type "arg"}}))
              (swap! edges conj
                     {:data {:id (str "e-val-" arg-id)
                             :source source-node-id
                             :target node-id
                             :argName (when arg-name (name arg-name))}}))
            node-id))

        add-unset-arg-node
        (fn [arg-name arg-type arg-id source-node-id]
          (let [node-id (str "unset-" arg-id)]
            (when-not (contains? @added-node-ids node-id)
              (swap! added-node-ids conj node-id)
              (swap! nodes conj
                     {:data {:id node-id
                             :label (if arg-type (name arg-type) "any")
                             :type "fn"
                             :isPlaceholder true}})
              (swap! edges conj
                     {:data {:id (str "e-unset-" arg-id)
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
                                       binding (get bindings (:id arg))]
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
                               args)]
            (filterv some? all-args)))]

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

            ;; Get root source-id for an arg (walk up source chain)
            (get-root-source-id [arg]
              (loop [source-id (:id arg)
                     current arg
                     depth 0]
                (if (or (nil? current) (> depth 100))
                  source-id
                  (if-let [parent-source-id (:source-id current)]
                    (recur parent-source-id (get arg-map parent-source-id) (inc depth))
                    source-id))))

            ;; Collect ALL source-ids in the chain from arg up to root
            (collect-source-chain [arg]
              (loop [current arg
                     chain #{}
                     depth 0]
                (if (or (nil? current) (> depth 100))
                  chain
                  (let [chain (conj chain (:id current))]
                    (if-let [source-id (:source-id current)]
                      (recur (get arg-map source-id) chain (inc depth))
                      chain)))))

            ;; Collect SET args from ALL fns in chain [0..level]
            ;; Returns {:set-args #{all-covered-source-ids}, :ordered-args [...]}
            ;; When an arg sets a value/ref, ALL source-ids in its chain are marked as "covered"
            (collect-set-args-from-chain [chain level]
              (let [active-fns (take (inc level) chain) ; fns from 0 to level inclusive
                    set-args (atom #{}) ; Set of ALL covered source-ids
                    ordered-args (atom [])]
                ;; Process each fn in chain
                (doseq [fn-id active-fns]
                  (let [args (get args-by-fn fn-id [])
                        level-ref-args (atom [])
                        level-value-args (atom [])]
                    (doseq [arg args]
                      (let [has-value (some? (:value arg))
                            has-ref (some? (:ref-id arg))]
                        (when (or has-value has-ref)
                          ;; Check if this arg's immediate source is already covered
                          (let [immediate-source (or (:source-id arg) (:id arg))]
                            (when-not (contains? @set-args immediate-source)
                              ;; Mark ALL source-ids in chain as covered
                              (let [source-chain (collect-source-chain arg)]
                                (swap! set-args into source-chain))
                              (let [arg-info {:arg-name (resolve-arg-name arg arg-map)
                                              :value (:value arg)
                                              :ref-id (:ref-id arg)
                                              :arg-id (:id arg)
                                              :source-id immediate-source}]
                                (if has-ref
                                  (swap! level-ref-args conj arg-info)
                                  (swap! level-value-args conj arg-info))))))))
                    ;; Add this level's args: refs first, then values
                    (doseq [a @level-ref-args] (swap! ordered-args conj a))
                    (doseq [a @level-value-args] (swap! ordered-args conj a))))
                {:set-args @set-args
                 :ordered-args @ordered-args}))

            ;; Collect UNSET args from displayFnId only
            ;; Returns list of unset arg infos
            ;; set-args is now a set of ALL covered arg-ids in the source chains
            (collect-unset-args-from-display [display-fn-id set-args]
              (let [args (get args-by-fn display-fn-id [])]
                (filterv
                  (fn [arg]
                    (let [has-value (some? (:value arg))
                          has-ref (some? (:ref-id arg))]
                      (and (not has-value)
                           (not has-ref)
                           ;; Check if this arg's id is covered
                           (not (contains? set-args (:id arg))))))
                  args)))

            (process-expanded-fn [original-fn-id level source-node-id edge-arg-name is-root source-arg-id]
              (let [chain (get-inheritance-chain original-fn-id fn-map)
                    display-fn-id (nth chain (min level (dec (count chain))) original-fn-id)
                    ;; Collect set args from ALL fns in chain [0..level]
                    {:keys [set-args ordered-args]} (collect-set-args-from-chain chain level)
                    ;; Collect unset args from display-fn-id only
                    unset-args (collect-unset-args-from-display display-fn-id set-args)
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

                ;; Process ordered set args (refs and values)
                (doseq [arg-info ordered-args]
                  (if (:ref-id arg-info)
                    ;; Ref arg -> recursively process
                    (process-any-fn (:ref-id arg-info) node-id (:arg-name arg-info) false nil (:arg-id arg-info))
                    ;; Value arg
                    (add-arg-value-node (:arg-name arg-info) (:value arg-info) (:arg-id arg-info) node-id)))

                ;; Process unset args
                (doseq [arg unset-args]
                  (add-unset-arg-node (resolve-arg-name arg arg-map) (:type arg) (:id arg) node-id))

                node-id))

            (process-any-fn [fn-id source-node-id edge-arg-name is-root parent-bindings source-arg-id]
              (let [level (get-effective-level fn-id)]
                (if (> level 0)
                  (process-expanded-fn fn-id level source-node-id edge-arg-name is-root source-arg-id)
                  (let [bindings (build-arg-bindings fn-id args-by-fn arg-map)
                        ;; Merge parent bindings (don't override)
                        bindings (if parent-bindings
                                   (merge parent-bindings bindings)
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
  "Order children: group by shared target (so siblings leading to same shared are adjacent),
   then by type (fn > fixed > free).

   Children with SAME shared target are placed together. Within a shared-target group,
   longer paths come before shorter paths (so shorter = last parent for that shared).
   This ensures the shared node ends up on the row of its last parent."
  [parent-id children-map paths-to-shared shared-nodes graph-children node-data-map]
  (let [child-ids (get children-map parent-id [])
        type-order {:fn 0 :fixed 1 :free 2}

        classify-child
        (fn [child-id]
          (let [targets (get paths-to-shared child-id #{})
                child-type (get-child-type child-id node-data-map)]
            {:id child-id
             :type child-type
             :targets targets
             ;; Primary sort key: first shared target (or nil)
             :primary-target (first (sort targets))
             ;; Path length for ordering within group
             :path-len (if (seq targets)
                         (apply min (map #(or (path-length-to-shared child-id % graph-children) 999) targets))
                         0)}))

        classified (map classify-child child-ids)

        ;; Separate into groups: those leading to shared nodes vs those without
        with-targets (filter #(seq (:targets %)) classified)
        without-targets (filter #(empty? (:targets %)) classified)

        ;; Group children by their primary shared target
        by-target (group-by :primary-target with-targets)

        ;; Sort each group: longer paths first (so shorter path = last = on same row as shared)
        sorted-groups (mapcat (fn [[_target group]]
                                (sort-by (fn [c] [(- (:path-len c)) (get type-order (:type c) 2) (:id c)]) group))
                              (sort-by first by-target))

        ;; Sort non-shared children by type then id
        sorted-regular (sort-by (fn [c] [(get type-order (:type c) 2) (:id c)]) without-targets)]

    ;; Children leading to shared nodes come first (grouped by target),
    ;; then regular children sorted by type
    (vec (concat (map :id sorted-groups)
                 (map :id sorted-regular)))))


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
  "Main layout function.

   Algorithm:
   1. Order children: shared-target siblings grouped, then fn > fixed > free
   2. Place horizontal branch (chain of first children) as a unit
   3. If collision, shift entire branch down
   4. Then process remaining children of rightmost fn in branch
   5. Recurse back up to parent when done with a subtree

   Rules:
   - First child of each node is on SAME ROW as parent (horizontal branch)
   - Other children are BELOW (each on own row)
   - Shared node goes on SAME ROW as its LAST (max row) parent"
  [root-id graph-info]
  (let [{:keys [children parents shared-nodes node-data-map]} graph-info
        paths-to-shared (find-paths-to-shared children shared-nodes)
        initial-state {:matrix (empty-matrix)
                       :placed #{}
                       :pending-shared #{}
                       :col-max-rows {}}]  ; Track max row used in each column

    (letfn [(find-free-row [matrix col min-row]
              (loop [row min-row]
                (if (cell-occupied? matrix row col)
                  (recur (inc row))
                  row)))

            (place-node-at [state node-id row col]
              (-> state
                  (update :matrix place-node-in-matrix node-id row col)
                  (update :placed conj node-id)
                  (update :col-max-rows (fn [m] (update m col #(max (or % -1) row))))))

            (get-col-max-row [state col]
              (get-in state [:col-max-rows col] -1))

            ;; Check if current node is the "last" parent for a shared node
            ;; (last = will have max row when all parents are placed)
            ;; For now, we use order in children list as proxy - siblings that come
            ;; later in child order will have higher row numbers
            (is-last-parent-of? [parent-id shared-id placed-set]
              ;; A parent is "last" if all other parents are already placed
              ;; OR if this parent comes later in sibling order than other parents
              (let [all-parents (get parents shared-id [])
                    other-parents (remove #{parent-id} all-parents)]
                ;; If all other parents are placed, this is effectively the last one
                (every? #(contains? placed-set %) other-parents)))

            ;; Get children for node, excluding shared nodes that should go via lower branches
            ;; Returns {:children [...], :excluded-shared [...]}
            ;; Shared nodes are included if this node is their "last" parent
            (get-children-for-branch [node-id placed-set]
              (let [all-children (order-children node-id children paths-to-shared
                                                  shared-nodes children node-data-map)
                    ;; For each shared child, check if this is its last parent
                    should-include? (fn [child-id]
                                      (if (contains? shared-nodes child-id)
                                        (is-last-parent-of? node-id child-id placed-set)
                                        true))
                    included (filterv should-include? all-children)
                    excluded (filterv #(and (contains? shared-nodes %)
                                            (not (should-include? %))) all-children)]
                {:children included
                 :excluded-shared excluded}))

            ;; Build the horizontal branch starting from a node
            ;; Returns {:branch [...], :ends-at-shared node-id-or-nil, :all-excluded-shared [...]}
            ;; placed-set: nodes that are already placed (used to determine if this is last parent)
            (build-horizontal-branch [node-id start-col placed-set]
              (loop [current-id node-id
                     col start-col
                     branch []
                     all-excluded-shared []
                     current-placed placed-set]
                (cond
                  (nil? current-id)
                  {:branch branch :ends-at-shared nil :all-excluded-shared all-excluded-shared}

                  :else
                  (let [;; Check if this is a shared node that should be deferred
                        ;; (i.e., not all other parents are placed yet)
                        defer-shared? (and (contains? shared-nodes current-id)
                                           (not (is-last-parent-of?
                                                  ;; Find which parent led us here
                                                  (some #(when (some #{current-id}
                                                                     (get children %)) %)
                                                        current-placed)
                                                  current-id
                                                  current-placed)))]
                    (if defer-shared?
                      ;; Stop at shared nodes that should be deferred
                      {:branch branch :ends-at-shared current-id :all-excluded-shared all-excluded-shared}
                      ;; Continue building branch
                      (let [branch (conj branch {:id current-id :col col})
                            ;; Update placed set to include nodes in branch so far
                            updated-placed (conj current-placed current-id)
                            {:keys [children excluded-shared]} (get-children-for-branch current-id updated-placed)
                            ;; Collect all excluded shared nodes
                            all-excluded-shared (into all-excluded-shared excluded-shared)
                            ;; First child continues the horizontal branch
                            first-child (first children)]
                        (if first-child
                          (recur first-child (inc col) branch all-excluded-shared updated-placed)
                          {:branch branch :ends-at-shared nil :all-excluded-shared all-excluded-shared})))))))

            ;; Check if placing branch at given row would collide
            (branch-collides? [state branch row]
              (some (fn [{:keys [col]}]
                      (cell-occupied? (:matrix state) row col))
                    branch))

            ;; Find minimum row where branch can be placed without collision
            (find-branch-row [state branch min-row]
              (loop [row min-row]
                (if (branch-collides? state branch row)
                  (recur (inc row))
                  row)))

            ;; Place entire horizontal branch at given row
            (place-branch [state branch row]
              (reduce (fn [s {:keys [id col]}]
                        (place-node-at s id row col))
                      state
                      branch))

            ;; Process a node: place its horizontal branch, then recurse on remaining children
            (place-subtree [state node-id target-row target-col]
              (cond
                ;; Already placed - skip
                (contains? (:placed state) node-id)
                state

                ;; Shared node - defer to phase 2
                (contains? shared-nodes node-id)
                (update state :pending-shared conj node-id)

                :else
                (let [;; Build horizontal branch starting from this node
                      ;; Pass current placed set to determine which shared nodes to include
                      {:keys [branch ends-at-shared all-excluded-shared]} (build-horizontal-branch node-id target-col (:placed state))

                      ;; Find row where branch fits (starting from target-row)
                      actual-row (find-branch-row state branch target-row)

                      ;; Place the entire branch
                      state (place-branch state branch actual-row)

                      ;; Add all excluded shared nodes to pending
                      state (reduce (fn [s shared-id]
                                      (update s :pending-shared conj shared-id))
                                    state
                                    all-excluded-shared)

                      ;; If branch ends at shared node, add to pending
                      state (if ends-at-shared
                              (update state :pending-shared conj ends-at-shared)
                              state)]

                  ;; Now process remaining children of each fn in the branch (right to left)
                  ;; Start from rightmost fn and work back
                  (let [fns-in-branch (filter (fn [{:keys [id]}]
                                                (= :fn (get-child-type id node-data-map)))
                                              (reverse branch))]
                    (reduce
                      (fn [s {:keys [id col]}]
                        (let [;; Get children, using current placed set to determine shared inclusion
                              {:keys [children excluded-shared]} (get-children-for-branch id (:placed s))
                              ;; Add excluded shared to pending
                              s (reduce (fn [st shared-id]
                                          (update st :pending-shared conj shared-id))
                                        s
                                        excluded-shared)
                              ;; Skip first child (already in horizontal branch)
                              remaining-children (rest children)
                              child-col (inc col)]
                          ;; Place each remaining child below the previous one
                          (loop [remaining remaining-children
                                 next-row (inc actual-row)
                                 state s]
                            (if (empty? remaining)
                              state
                              (let [child-id (first remaining)
                                    ;; Find actual row for this child
                                    child-row (find-free-row (:matrix state) child-col next-row)
                                    ;; Recursively place this child's subtree
                                    state (place-subtree state child-id child-row child-col)
                                    ;; Get max row used by this subtree
                                    child-pos (get-node-pos (:matrix state) child-id)
                                    new-next-row (if child-pos
                                                   (inc (max (:row child-pos)
                                                             (get-col-max-row state child-col)))
                                                   next-row)]
                                (recur (rest remaining) new-next-row state))))))
                      state
                      fns-in-branch)))))

            (place-shared-node [state shared-id]
              ;; Place shared node on same row as its LAST (max-row) parent
              (if (contains? (:placed state) shared-id)
                state
                (let [parent-ids (get parents shared-id [])
                      parent-positions (keep #(get-node-pos (:matrix state) %) parent-ids)]
                  (if (empty? parent-positions)
                    state
                    (let [max-row (apply max (map :row parent-positions))
                          max-col (apply max (map :col parent-positions))
                          shared-col (inc max-col)
                          ;; Check if target cell is occupied
                          occupied? (cell-occupied? (:matrix state) max-row shared-col)
                          ;; If occupied, shift the blocking subtree down
                          state (if occupied?
                                  (let [blocking-node (get-cell (:matrix state) max-row shared-col)
                                        free-row (find-free-row (:matrix state) shared-col (inc max-row))
                                        shift-amount (- free-row max-row)]
                                    (shift-subtree-down state blocking-node shift-amount shared-nodes children))
                                  state)]
                      (place-node-at state shared-id max-row shared-col))))))

            (shift-subtree-down [state node-id delta shared-nodes-set children-map]
              (if (or (zero? delta) (contains? shared-nodes-set node-id))
                state
                (let [pos (get-node-pos (:matrix state) node-id)]
                  (if-not pos
                    state
                    (let [old-row (:row pos)
                          old-col (:col pos)
                          new-row (+ old-row delta)
                          matrix (-> (:matrix state)
                                     (update :grid dissoc [old-row old-col])
                                     (assoc-in [:grid [new-row old-col]] node-id)
                                     (assoc-in [:positions node-id] {:row new-row :col old-col}))
                          state (-> state
                                    (assoc :matrix matrix)
                                    (update :col-max-rows (fn [m] (update m old-col #(max (or % -1) new-row)))))
                          child-ids (get children-map node-id [])]
                      (reduce
                        (fn [s cid]
                          (if (and (contains? (:placed s) cid)
                                   (not (contains? shared-nodes-set cid)))
                            (shift-subtree-down s cid delta shared-nodes-set children-map)
                            s))
                        state
                        child-ids))))))

            (place-shared-subtree [state shared-id]
              ;; Place a shared node and its children
              (let [state (place-shared-node state shared-id)
                    node-pos (get-node-pos (:matrix state) shared-id)]
                (if-not node-pos
                  state
                  ;; Process children of shared node using same algorithm
                  (let [ordered-children (order-children shared-id children paths-to-shared
                                                         shared-nodes children node-data-map)
                        child-col (inc (:col node-pos))]
                    ;; First child on same row, rest below
                    (loop [remaining ordered-children
                           first? true
                           next-row (inc (:row node-pos))
                           s state]
                      (if (empty? remaining)
                        s
                        (let [child-id (first remaining)
                              child-row (if first? (:row node-pos) next-row)
                              s (place-subtree s child-id child-row child-col)
                              child-pos (get-node-pos (:matrix s) child-id)
                              new-next-row (if child-pos
                                             (inc (max (:row child-pos)
                                                       (get-col-max-row s child-col)))
                                             next-row)]
                          (recur (rest remaining) false new-next-row s))))))))

            (place-all-shared-nodes [state]
              ;; Phase 2: Place all pending shared nodes based on final parent positions
              (loop [s state
                     max-iterations 100]
                (if (or (zero? max-iterations) (empty? (:pending-shared s)))
                  s
                  (let [pending (vec (:pending-shared s))
                        s (reduce
                            (fn [acc shared-id]
                              ;; Check if all parents are placed
                              (let [parent-ids (set (get parents shared-id []))
                                    placed-parents (clojure.set/intersection parent-ids (:placed acc))]
                                (if (= parent-ids placed-parents)
                                  ;; All parents placed - place this shared node
                                  (let [acc (place-shared-subtree acc shared-id)]
                                    (update acc :pending-shared disj shared-id))
                                  ;; Not all parents placed yet - keep pending
                                  acc)))
                            s
                            pending)]
                    (recur s (dec max-iterations))))))]

      ;; Execute layout
      (let [state (place-subtree initial-state root-id 0 0)
            state (place-all-shared-nodes state)]
        (:matrix state)))))


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
