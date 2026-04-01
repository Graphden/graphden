(ns graphden.packages.app.layout.impls
  "Graph layout calculation - simplified recursive algorithm.

   Core rules:
   1. Children of a node are placed RIGHT of parent, never above
   2. First child is on SAME ROW as parent, others are BELOW (each on own row)
   3. Horizontal branch = chain of first children
   4. Shared nodes (multiple parents) go in horizontal branch of LAST parent
   5. Splitting siblings (leading to same shared node) must be adjacent in child list
   6. Parents of shared node must be in same column (shift right if needed)"
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]))


;; =============================================================================
;; GRAPH ANALYSIS
;; =============================================================================

(defn build-graph-info
  "Build graph structure from nodes and edges.
   Returns {:children {id -> [child-ids]}
            :parents {id -> [parent-ids]}
            :shared-nodes #{ids with multiple parents}
            :edge-names {\"src->tgt\" -> arg-name}
            :node-data-map {id -> node-data}}"
  [nodes edges]
  (let [children (reduce (fn [m e]
                           (update m (:source e) (fnil conj []) (:target e)))
                         {} edges)
        parents (reduce (fn [m e]
                          (update m (:target e) (fnil conj []) (:source e)))
                        {} edges)
        shared-nodes (->> parents
                          (filter (fn [[_ ps]] (> (count ps) 1)))
                          (map first)
                          (into #{}))
        edge-names (reduce (fn [m e]
                             (if (:argName e)
                               (assoc m (str (:source e) "->" (:target e)) (:argName e))
                               m))
                           {} edges)
        node-data-map (into {} (map (fn [n] [(:id n) n]) nodes))]
    {:children children
     :parents parents
     :shared-nodes shared-nodes
     :edge-names edge-names
     :node-set (into #{} (map :id nodes))
     :node-data-map node-data-map}))


(defn find-root
  "Find root node (no incoming edges)."
  [nodes edges]
  (let [has-parent (into #{} (map :target edges))]
    (first (filter #(not (contains? has-parent (:id %))) nodes))))


;; =============================================================================
;; PATH ANALYSIS FOR SHARED NODES
;; =============================================================================

(defn find-paths-to-shared
  "For each node, find which shared nodes are reachable from it.
   Returns {node-id -> #{reachable shared node ids}}"
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


(defn path-length-to-shared
  "Calculate path length from node to shared node (BFS).
   Returns nil if not reachable."
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


;; =============================================================================
;; CHILD ORDERING
;; =============================================================================

(defn get-child-type
  "Get type of child node: :fn, :fixed, or :free"
  [child-id node-data-map]
  (let [data (get node-data-map child-id)]
    (cond
      (nil? data) :free
      (:isPlaceholder data) :free
      (= (:type data) "fn") :fn
      (= (:type data) "arg") :fixed
      :else :free)))


(defn order-children
  "Order children according to strict rules:

   1. LOWER paths to shared args (longest path = lower branch) - FIRST
   2. fn args (not part of any shared path)
   3. fixed args (not part of any shared path)
   4. free args (not part of any shared path)
   5. UPPER paths to shared args (shortest path = upper branch) - LAST

   Splitting siblings (leading to same shared node) must be adjacent."
  [parent-id children-map paths-to-shared shared-nodes graph-children node-data-map]
  (let [child-ids (get children-map parent-id [])

        ;; For each child, determine if it's part of a path to shared node
        ;; and if so, whether it's upper (short) or lower (long) path
        classify-child
        (fn [child-id]
          (let [targets (get paths-to-shared child-id #{})
                child-type (get-child-type child-id node-data-map)]
            (if (empty? targets)
              ;; Not part of any shared path - classify by type
              {:id child-id
               :category :regular
               :type child-type
               :targets #{}}
              ;; Part of shared path - determine if upper or lower
              ;; For each shared node, find path length
              (let [path-info (map (fn [shared-id]
                                     {:shared-id shared-id
                                      :path-len (or (path-length-to-shared child-id shared-id graph-children) 999)})
                                   targets)
                    ;; Find max path among siblings to this shared node
                    ;; Child with max path = lower, others = upper
                    ;; We need to compare with siblings, so just store info for now
                    min-path (apply min (map :path-len path-info))]
                {:id child-id
                 :category :shared-path
                 :type child-type
                 :targets targets
                 :min-path-len min-path}))))

        classified (map classify-child child-ids)

        ;; Separate regular children from shared-path children
        regular (filter #(= (:category %) :regular) classified)
        shared-path (filter #(= (:category %) :shared-path) classified)

        ;; Sort regular children by type: fn > fixed > free
        type-order {:fn 0, :fixed 1, :free 2}
        sorted-regular (sort-by #(get type-order (:type %) 2) regular)

        ;; For shared-path children, determine upper vs lower
        ;; Group by shared node, find max path length, classify
        shared-path-classified
        (when (seq shared-path)
          (let [;; Find all shared targets
                all-targets (distinct (mapcat :targets shared-path))
                ;; For each shared target, find the max path length among siblings
                max-paths (into {}
                                (map (fn [shared-id]
                                       (let [leading (filter #(contains? (:targets %) shared-id) shared-path)
                                             max-len (apply max (map :min-path-len leading))]
                                         [shared-id max-len]))
                                     all-targets))
                ;; Classify each child as upper or lower
                classify-path (fn [child]
                                (let [;; Check if this child is the lower path for ANY of its targets
                                      is-lower? (some (fn [shared-id]
                                                        (let [max-len (get max-paths shared-id)
                                                              my-len (or (path-length-to-shared (:id child) shared-id graph-children) 999)]
                                                          (= my-len max-len)))
                                                      (:targets child))]
                                  (assoc child :path-position (if is-lower? :lower :upper))))]
            (map classify-path shared-path)))

        ;; Separate lower and upper paths
        lower-paths (filter #(= (:path-position %) :lower) shared-path-classified)
        upper-paths (filter #(= (:path-position %) :upper) shared-path-classified)

        ;; Sort lower paths by shared-id for grouping (siblings adjacent)
        ;; Sort upper paths similarly
        sort-by-first-target (fn [items]
                               (sort-by #(first (:targets %)) items))]

    ;; Final order:
    ;; 1. Lower paths (longest path first in each group)
    ;; 2. Regular: fn, then fixed, then free
    ;; 3. Upper paths (shortest path last)
    (vec (concat
           (map :id (sort-by-first-target lower-paths))
           (map :id sorted-regular)
           (map :id (sort-by-first-target upper-paths))))))


;; =============================================================================
;; MATRIX OPERATIONS (PURE FUNCTIONS)
;; =============================================================================

(defn empty-matrix []
  {:grid {}        ; {[row col] -> node-id}
   :positions {}}) ; {node-id -> {:row r :col c}}


(defn get-cell [matrix row col]
  (get (:grid matrix) [row col]))


(defn cell-occupied? [matrix row col]
  (some? (get-cell matrix row col)))


(defn place-node [matrix node-id row col]
  (-> matrix
      (assoc-in [:grid [row col]] node-id)
      (assoc-in [:positions node-id] {:row row :col col})))


(defn get-node-pos [matrix node-id]
  (get-in matrix [:positions node-id]))


(defn max-col-at-row
  "Find the maximum occupied column at given row, or -1 if none."
  [matrix row]
  (let [occupied-cols (->> (:grid matrix)
                           (filter (fn [[[r _] _]] (= r row)))
                           (map (fn [[[_ c] _]] c)))]
    (if (seq occupied-cols)
      (apply max occupied-cols)
      -1)))


(defn find-free-row
  "Find first row >= min-row where columns from start-col to start-col+length are free."
  [matrix start-col length min-row]
  (loop [row min-row]
    (let [collision (some #(cell-occupied? matrix row %)
                          (range start-col (+ start-col length)))]
      (if collision
        (recur (inc row))
        row))))


(defn shift-subtree-down
  "Shift a node and all its descendants down by delta rows."
  [matrix node-id delta children-map]
  (if (zero? delta)
    matrix
    (letfn [(collect-subtree [nid visited]
              (if (contains? visited nid)
                visited
                (let [visited (conj visited nid)
                      child-ids (get children-map nid [])]
                  (reduce (fn [v cid] (collect-subtree cid v))
                          visited child-ids))))]
      (let [subtree-nodes (collect-subtree node-id #{})
            ;; Move all nodes in subtree
            new-matrix (reduce
                         (fn [m nid]
                           (if-let [pos (get-node-pos m nid)]
                             (let [old-row (:row pos)
                                   old-col (:col pos)
                                   new-row (+ old-row delta)]
                               (-> m
                                   (update :grid dissoc [old-row old-col])
                                   (assoc-in [:grid [new-row old-col]] nid)
                                   (assoc-in [:positions nid] {:row new-row :col old-col})))
                             m))
                         matrix
                         subtree-nodes)]
        new-matrix))))


(defn shift-subtree-right
  "Shift a node and all its descendants right by delta columns."
  [matrix node-id delta children-map]
  (if (zero? delta)
    matrix
    (letfn [(collect-subtree [nid visited]
              (if (contains? visited nid)
                visited
                (let [visited (conj visited nid)
                      child-ids (get children-map nid [])]
                  (reduce (fn [v cid] (collect-subtree cid v))
                          visited child-ids))))]
      (let [subtree-nodes (collect-subtree node-id #{})
            new-matrix (reduce
                         (fn [m nid]
                           (if-let [pos (get-node-pos m nid)]
                             (let [old-row (:row pos)
                                   old-col (:col pos)
                                   new-col (+ old-col delta)]
                               (-> m
                                   (update :grid dissoc [old-row old-col])
                                   (assoc-in [:grid [old-row new-col]] nid)
                                   (assoc-in [:positions nid] {:row old-row :col new-col})))
                             m))
                         matrix
                         subtree-nodes)]
        new-matrix))))


;; =============================================================================
;; MAIN LAYOUT ALGORITHM
;; =============================================================================

(defn layout-graph
  "Main layout function. Pure recursive algorithm.

   Core rules:
   1. First child is ALWAYS on same row as parent (horizontal branch)
   2. Other children are below, each on its own row
   3. If first child's cell is occupied, shift parent (and ancestors) down
   4. Shared nodes placed when last parent is reached"
  [root-id graph-info]
  (let [{:keys [children parents shared-nodes node-data-map]} graph-info
        paths-to-shared (find-paths-to-shared children shared-nodes)

        ;; State: matrix + tracking info
        initial-state {:matrix (empty-matrix)
                       :placed #{}
                       :shared-parent-placed {}  ; {shared-id -> #{placed parent ids}}
                       :first-child {}}]         ; {node-id -> first-child-id} for shift propagation

    ;; Initialize shared parent tracking
    (letfn [(init-shared-tracking [state]
              (reduce
                (fn [s shared-id]
                  (assoc-in s [:shared-parent-placed shared-id] #{}))
                state
                shared-nodes))

            (all-parents-placed? [state shared-id]
              (let [parent-ids (set (get parents shared-id []))
                    placed-parents (get-in state [:shared-parent-placed shared-id] #{})]
                (= parent-ids placed-parents)))

            (mark-as-parent-placed [state parent-id]
              (reduce
                (fn [s child-id]
                  (if (contains? shared-nodes child-id)
                    (update-in s [:shared-parent-placed child-id] conj parent-id)
                    s))
                state
                (get children parent-id [])))

            ;; Find minimum row where we can place node at given col
            ;; considering the entire horizontal branch
            (find-free-row-for-branch [matrix col min-row]
              (loop [row min-row]
                (if (cell-occupied? matrix row col)
                  (recur (inc row))
                  row)))

            ;; Shift a node and ALL nodes below it in the same "vertical slice"
            ;; This includes: the node, its subtree, and any nodes below
            (shift-node-down [state node-id delta]
              (if (zero? delta)
                state
                (let [pos (get-node-pos (:matrix state) node-id)]
                  (if-not pos
                    state
                    (let [old-row (:row pos)
                          old-col (:col pos)
                          new-row (+ old-row delta)
                          ;; Move this node
                          matrix (-> (:matrix state)
                                     (update :grid dissoc [old-row old-col])
                                     (assoc-in [:grid [new-row old-col]] node-id)
                                     (assoc-in [:positions node-id] {:row new-row :col old-col}))
                          state (assoc state :matrix matrix)
                          ;; Recursively shift all children
                          child-ids (get children node-id [])]
                      (reduce
                        (fn [s cid]
                          (if (contains? (:placed s) cid)
                            (shift-node-down s cid delta)
                            s))
                        state
                        child-ids))))))

            ;; Place node and its children
            ;; Returns updated state
            (place-node-at [state node-id row col]
              (-> state
                  (update :matrix place-node node-id row col)
                  (update :placed conj node-id)
                  (mark-as-parent-placed node-id)))

            ;; Main recursive function
            (place-subtree [state node-id target-row target-col parent-id is-first-child]
              (if (contains? (:placed state) node-id)
                state
                (let [;; Check if target cell is free
                      cell-free? (not (cell-occupied? (:matrix state) target-row target-col))

                      ;; If not free and this is first child, we need to shift parent down
                      state (if (and (not cell-free?) is-first-child parent-id)
                              ;; Find how much to shift
                              (let [free-row (find-free-row-for-branch (:matrix state) target-col target-row)
                                    shift-amount (- free-row target-row)]
                                ;; Shift parent (which shifts us too via recursion)
                                (shift-node-down state parent-id shift-amount))
                              state)

                      ;; After potential shift, recalculate our target row
                      actual-row (if parent-id
                                   (let [parent-pos (get-node-pos (:matrix state) parent-id)]
                                     (if is-first-child
                                       (:row parent-pos)  ; Same row as parent
                                       (find-free-row-for-branch (:matrix state) target-col target-row)))
                                   target-row)

                      ;; Place this node
                      state (place-node-at state node-id actual-row target-col)

                      ;; Get ordered children
                      ordered-children (order-children node-id children paths-to-shared
                                                       shared-nodes children node-data-map)

                      ;; Filter out shared nodes that aren't ready
                      ready-children (filterv
                                       (fn [cid]
                                         (if (contains? shared-nodes cid)
                                           (all-parents-placed? state cid)
                                           true))
                                       ordered-children)

                      ;; Place children: first on same row, rest below
                      child-col (inc target-col)]

                  (loop [remaining ready-children
                         next-row (inc actual-row)  ; Second child starts below
                         first? true
                         state state]
                    (if (empty? remaining)
                      state
                      (let [child-id (first remaining)
                            child-row (if first? actual-row next-row)
                            is-shared (contains? shared-nodes child-id)]

                        (if is-shared
                          ;; Shared node: place on row of this (last) parent
                          (let [parent-ids (get parents child-id [])
                                parent-positions (keep #(get-node-pos (:matrix state) %) parent-ids)
                                max-parent-col (if (seq parent-positions)
                                                 (apply max (map :col parent-positions))
                                                 target-col)
                                shared-col (inc max-parent-col)
                                ;; Place shared node on current row (this parent's row)
                                state (place-subtree state child-id actual-row shared-col node-id true)
                                child-pos (get-node-pos (:matrix state) child-id)]
                            (recur (rest remaining)
                                   (if child-pos (inc (:row child-pos)) next-row)
                                   false
                                   state))

                          ;; Regular node
                          (let [state (place-subtree state child-id child-row child-col node-id first?)
                                child-pos (get-node-pos (:matrix state) child-id)]
                            (recur (rest remaining)
                                   (if child-pos (inc (:row child-pos)) (inc next-row))
                                   false
                                   state)))))))))]

      ;; Start from root at (0,0)
      (let [state (init-shared-tracking initial-state)
            final-state (place-subtree state root-id 0 0 nil false)]
        (:matrix final-state)))))


;; =============================================================================
;; VALIDATION
;; =============================================================================

(defn validate-layout
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
;; HIGH-LEVEL API
;; =============================================================================

(defn compute-layout-matrix
  "Compute grid-based layout from elements.
   Input: {:elements {:nodes [...], :edges [...]}}
   Output: {:grid-pos {node-id {:row r :col c}}, :validation {...}}"
  [{:keys [elements]}]
  (let [nodes (or (:nodes elements) [])
        edges (or (:edges elements) [])]
    (if (empty? nodes)
      {:grid-pos {}
       :validation {:valid true :issues []}}
      (let [graph-info (build-graph-info nodes edges)
            root (find-root nodes edges)]
        (if-not root
          {:grid-pos {}
           :validation {:valid false
                        :issues [{:type "no_root" :message "No root node found"}]}}
          (let [matrix (layout-graph (:id root) graph-info)
                validation (validate-layout matrix)]
            {:grid-pos (:positions matrix)
             :validation validation}))))))


;; =============================================================================
;; API HANDLER
;; =============================================================================

(defn get-layout-data
  "Compute layout from elements passed in request body."
  [{:keys [request]}]
  (let [body-str (:body request)
        body (when (and body-str (not (str/blank? body-str)))
               (json/parse-string body-str true))
        elements (:elements body)]
    (if-not elements
      (throw (ex-info "Request body must contain 'elements' with 'nodes' and 'edges'"
                      {:type :execution-error/invalid-args}))
      (compute-layout-matrix {:elements elements}))))


;; === Registry ===

(def impls
  {:compute-layout-matrix compute-layout-matrix
   :get-layout-data (with-meta get-layout-data {:ctx true})})
