(ns graphden.packages.app.layout.impls
  "Graph layout calculation implementations.
   Ports the grid-based layout algorithm from JavaScript."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]))


;; =============================================================================
;; ADJACENCY AND ROOT DETECTION
;; =============================================================================

(defn build-adjacency
  "Build adjacency maps from edges.
   Returns {:children {parent-id [child-ids]}
            :parents {child-id [parent-ids]}
            :edge-arg-names {\"parent->child\" arg-name}}"
  [edges]
  (reduce
    (fn [acc edge]
      (let [src (:source edge)
            tgt (:target edge)
            edge-key (str src "->" tgt)
            arg-name (:argName edge)]
        (if (contains? (:seen acc) edge-key)
          acc
          (-> acc
              (update :seen conj edge-key)
              (update-in [:children src] (fnil conj []) tgt)
              (update-in [:parents tgt] (fnil conj []) src)
              (cond-> arg-name
                (assoc-in [:edge-arg-names edge-key] arg-name))))))
    {:children {}
     :parents {}
     :edge-arg-names {}
     :seen #{}}
    edges))


(defn find-root-node
  "Find root node (node with no incoming edges)."
  [nodes edges]
  (let [has-incoming (into #{} (map :target edges))]
    (some #(when-not (contains? has-incoming (:id %)) (:id %)) nodes)))


;; =============================================================================
;; NODE TYPE DETECTION
;; =============================================================================

(defn get-node-type
  "Get node type for sorting: 'fn' > 'fixed' > 'free'"
  [node-id node-data-map]
  (let [data (get node-data-map node-id)]
    (cond
      (nil? data) :free
      (:isPlaceholder data) :free
      (= (:type data) "fn") :fn
      (= (:type data) "arg") :fixed
      :else :free)))


(defn sort-children-by-priority
  "Sort children by priority: fn > fixed > free (preserve relative order)."
  [child-ids node-data-map]
  (let [prioritized (map-indexed
                      (fn [idx child-id]
                        (let [node-type (get-node-type child-id node-data-map)
                              priority (case node-type
                                         :fn 0
                                         :fixed 1
                                         2)]
                          {:child-id child-id :priority priority :original-index idx}))
                      child-ids)]
    (->> prioritized
         (sort-by (juxt :priority :original-index))
         (mapv :child-id))))


(defn group-shared-parents
  "Reorder children so that parents of the same shared node are adjacent.
   This minimizes edge crossings when connecting to shared nodes.

   Strategy:
   1. Find which children lead to which shared nodes
   2. Group children by their shared node targets
   3. Place groups consecutively, with shared-targeting children at the end"
  [child-ids children shared-info]
  (if (empty? (:shared-nodes shared-info))
    child-ids
    (let [paths-to-shared (:paths-to-shared shared-info)
          ;; Find which shared nodes each child leads to
          child-shared-targets (into {}
                                     (map (fn [child-id]
                                            [child-id (get paths-to-shared child-id #{})])
                                          child-ids))
          ;; Separate children that lead to shared nodes from those that don't
          {shared-parents true, non-shared false}
          (group-by #(boolean (seq (get child-shared-targets %))) child-ids)

          ;; Group shared parents by their target shared node
          ;; Children leading to the same shared node should be adjacent
          shared-groups (if shared-parents
                          (let [;; Get all shared nodes these children lead to
                                all-targets (distinct (mapcat #(get child-shared-targets %) shared-parents))
                                ;; For each shared node, collect children that lead to it
                                groups-by-target (map (fn [shared-id]
                                                        {:shared-id shared-id
                                                         :parents (filterv #(contains? (get child-shared-targets %) shared-id)
                                                                           shared-parents)})
                                                      all-targets)]
                            ;; Flatten groups, keeping parents of same shared node together
                            ;; Use a set to avoid duplicates (a child might lead to multiple shared nodes)
                            (let [seen (atom #{})
                                  result (atom [])]
                              (doseq [{:keys [parents]} groups-by-target
                                      parent parents]
                                (when-not (contains? @seen parent)
                                  (swap! seen conj parent)
                                  (swap! result conj parent)))
                              @result))
                          [])]
      ;; Non-shared children first, then grouped shared parents
      (vec (concat non-shared shared-groups)))))


;; =============================================================================
;; SHARED ARGUMENT DETECTION
;; =============================================================================

(defn analyze-shared-arguments
  "Find shared arguments and build path information.
   Returns {:shared-nodes #{node-ids with multiple parents}
            :paths-to-shared {node-id #{reachable shared node-ids}}
            :path-lengths {\"node->shared\" distance}}"
  [children parents]
  (let [shared-nodes (into #{}
                           (comp
                             (filter (fn [[_ parent-list]] (> (count parent-list) 1)))
                             (map first))
                           parents)]
    (if (empty? shared-nodes)
      {:shared-nodes shared-nodes
       :paths-to-shared {}
       :path-lengths {}}
      ;; BFS from each shared node backwards
      (let [result (atom {:paths-to-shared {}
                          :path-lengths {}})]
        (doseq [shared-id shared-nodes]
          (loop [queue [{:node-id shared-id :dist 0}]
                 visited #{}]
            (when (seq queue)
              (let [{:keys [node-id dist]} (first queue)
                    remaining (rest queue)]
                (if (contains? visited node-id)
                  (recur remaining visited)
                  (do
                    (swap! result update :paths-to-shared
                           (fn [m] (update m node-id (fnil conj #{}) shared-id)))
                    (swap! result assoc-in [:path-lengths (str node-id "->" shared-id)] dist)
                    (let [node-parents (get parents node-id [])
                          new-queue (into remaining
                                          (for [parent-id node-parents
                                                :when (not (contains? visited parent-id))]
                                            {:node-id parent-id :dist (inc dist)}))]
                      (recur new-queue (conj visited node-id)))))))))
        (assoc @result :shared-nodes shared-nodes)))))


(defn find-splitting-info
  "Find splitting info for a shared argument.
   Returns {:shared-id, :lower-child, :upper-children} or nil."
  [node-id child-ids shared-id paths-to-shared path-lengths]
  (let [leading-children (filterv
                           (fn [child-id]
                             (let [paths (get paths-to-shared child-id)]
                               (and paths (contains? paths shared-id))))
                           child-ids)]
    (when (>= (count leading-children) 2)
      (let [;; Find child with longest path (lower branch)
            with-lengths (map (fn [child-id]
                               {:child-id child-id
                                :path-len (get path-lengths (str child-id "->" shared-id) 0)})
                             leading-children)
            max-path-len (apply max (map :path-len with-lengths))
            ;; Last one with max path length wins
            lower-child (->> with-lengths
                             (filter #(= (:path-len %) max-path-len))
                             last
                             :child-id)
            upper-children (filterv #(not= % lower-child) leading-children)]
        {:shared-id shared-id
         :lower-child lower-child
         :upper-children upper-children}))))


;; =============================================================================
;; MATRIX STATE
;; =============================================================================

(defn create-matrix-state []
  {:node-grid []   ; node-grid[row][col] = node-id or nil
   :h-edge []      ; h-edge[row][col] = arg-name or nil
   :v-edge []})    ; v-edge[row][col] = true/false


(defn ensure-matrix-size [matrix target-row target-col]
  (let [ensure-grid-row (fn [grid r c]
                          (let [padded-rows (if (> (inc r) (count grid))
                                              (into grid (repeat (- (inc r) (count grid)) []))
                                              grid)]
                            (update padded-rows r
                                    (fn [rv]
                                      (let [row-vec (or rv [])]
                                        (if (> (inc c) (count row-vec))
                                          (into row-vec (repeat (- (inc c) (count row-vec)) nil))
                                          row-vec))))))]
    (-> matrix
        (update :node-grid #(ensure-grid-row % target-row target-col))
        (update :h-edge #(ensure-grid-row % target-row target-col))
        (update :v-edge #(ensure-grid-row % target-row target-col)))))


(defn get-node-at [matrix row col]
  (when (and (>= row 0) (>= col 0)
             (< row (count (:node-grid matrix)))
             (< col (count (get (:node-grid matrix) row))))
    (get-in matrix [:node-grid row col])))


(defn has-v-edge-at? [matrix row col]
  (when (and (>= row 0) (>= col 0)
             (< row (count (:v-edge matrix)))
             (< col (count (get (:v-edge matrix) row))))
    (boolean (get-in matrix [:v-edge row col]))))


(defn has-h-edge-at? [matrix row col]
  (when (and (>= row 0) (>= col 0)
             (< row (count (:h-edge matrix)))
             (< col (count (get (:h-edge matrix) row))))
    (some? (get-in matrix [:h-edge row col]))))


(defn cell-occupied? [matrix row col]
  (or (some? (get-node-at matrix row col))
      (has-v-edge-at? matrix row col)
      (has-h-edge-at? matrix row col)))


(defn place-node [matrix node-id row col]
  (let [m (ensure-matrix-size matrix row col)]
    (assoc-in m [:node-grid row col] node-id)))


(defn place-h-edge [matrix row col arg-name]
  (let [m (ensure-matrix-size matrix row col)]
    (assoc-in m [:h-edge row col] (or arg-name ""))))


(defn place-v-edge [matrix row col]
  (let [m (ensure-matrix-size matrix row col)]
    (if (some? (get-node-at m row col))
      m  ; Don't place v-edge where node exists
      (assoc-in m [:v-edge row col] true))))


(defn remove-v-edge [matrix row col]
  (if (and (>= row 0) (< row (count (:v-edge matrix)))
           (>= col 0) (< col (count (get (:v-edge matrix) row))))
    (assoc-in matrix [:v-edge row col] false)
    matrix))


;; =============================================================================
;; LAYOUT HELPERS
;; =============================================================================

(defn check-branch-collision
  "Check if placing a horizontal branch at given row would cause collisions."
  [matrix row start-col length]
  (some #(cell-occupied? matrix row %) (range start-col (+ start-col length))))


(defn find-row-for-branch
  "Find a row where a branch of given length can fit starting at start-col."
  [matrix start-col length min-row]
  (loop [row min-row
         iterations 0]
    (if (> iterations 1000)
      row
      (if (check-branch-collision matrix row start-col length)
        (recur (inc row) (inc iterations))
        row))))


;; =============================================================================
;; HORIZONTAL BRANCH BUILDING
;; =============================================================================

(defn adjust-priority-for-shared
  "Adjust child priority based on shared argument handling."
  [sorted-children shared-info current-node-id parents path-lengths _reserved-for-lower]
  (if (empty? (:shared-nodes shared-info))
    sorted-children
    (let [result (atom (vec sorted-children))]
      ;; Case 1: Current node is a parent of a shared node
      (doseq [shared-child-id (filter #(contains? (:shared-nodes shared-info) %) sorted-children)]
        (let [child-parents (get parents shared-child-id [])]
          (when (and (>= (count child-parents) 2)
                     (some #(= % current-node-id) child-parents))
            ;; Determine lower parent (longer path)
            (let [lower-parent (reduce
                                 (fn [best pid]
                                   (let [path-len (get (:path-lengths shared-info)
                                                       (str pid "->" shared-child-id) 1)]
                                     (if (or (nil? best)
                                             (>= path-len (:path-len best)))
                                       {:parent pid :path-len path-len}
                                       best)))
                                 nil
                                 child-parents)
                  is-lower? (= current-node-id (:parent lower-parent))
                  current-result @result
                  idx (.indexOf current-result shared-child-id)]
              (when (>= idx 0)
                (if is-lower?
                  ;; Lower parent: shared child goes FIRST
                  (when (> idx 0)
                    (reset! result (into [shared-child-id]
                                         (concat (subvec current-result 0 idx)
                                                 (subvec current-result (inc idx))))))
                  ;; Upper parent: shared child goes LAST
                  (when (< idx (dec (count current-result)))
                    (reset! result (conj (into (subvec current-result 0 idx)
                                               (subvec current-result (inc idx)))
                                         shared-child-id)))))))))
      @result)))


(defn build-horizontal-branch
  "Build a horizontal branch starting from node-id.

   For shared nodes (nodes with multiple parents):
   - Skip shared nodes unless they are 'ready' (all parents placed)
   - Ready shared nodes get PRIORITY in horizontal branch (placed first)
   - This ensures shared node is on same row as last parent

   Parameters:
   - ready-shared-nodes: set of shared nodes whose all parents are placed
     (these get priority in horizontal branch)"
  [start-node-id children placed node-data-map shared-info
   ready-shared-nodes parents path-lengths]
  (let [shared-nodes (:shared-nodes shared-info)]
    (loop [branch []
           current start-node-id]
      (if (or (nil? current) (contains? placed current))
        branch
        (let [new-branch (conj branch current)
              node-children (get children current [])
              sorted-children (sort-children-by-priority node-children node-data-map)
              ;; Prioritize ready shared nodes - they go FIRST in horizontal branch
              ;; This ensures shared node is placed horizontally with last parent
              prioritized-children (let [ready (filterv #(contains? ready-shared-nodes %) sorted-children)
                                         others (filterv #(not (contains? ready-shared-nodes %)) sorted-children)]
                                     (vec (concat ready others)))
              ;; Find next unplaced child that we can place
              ;; - Ready shared nodes: YES (prioritized above)
              ;; - Non-ready shared nodes: NO (skip, wait for all parents)
              ;; - Regular nodes: YES
              next-child (first (filter
                                  (fn [c]
                                    (and (not (contains? placed c))
                                         (or (not (contains? shared-nodes c))
                                             (contains? ready-shared-nodes c))))
                                  prioritized-children))]
          (if (nil? next-child)
            new-branch
            (let [next-type (get-node-type next-child node-data-map)]
              (if (not= next-type :fn)
                ;; Non-fn node: add and stop
                (conj new-branch next-child)
                ;; fn node: continue branch
                (recur new-branch next-child)))))))))


;; =============================================================================
;; MAIN LAYOUT ALGORITHM
;; =============================================================================

(defn build-matrix
  "Build the complete layout matrix.

   Strategy for shared nodes (nodes with multiple parents):
   1. First pass: place all non-shared nodes, defer shared nodes
   2. Track which parents have been placed for each shared node
   3. When all parents of a shared node are placed, place the shared node
      on the row of its last-placed parent (to minimize edge crossings)"
  [root-id children parents edge-arg-names node-data-map]
  (let [shared-info (analyze-shared-arguments children parents)
        shared-nodes (:shared-nodes shared-info)
        state (atom {:matrix (create-matrix-state)
                     :grid-pos {}
                     :placed #{}
                     ;; Track which parents are placed for each shared node
                     :shared-parent-info {}})]

    ;; Initialize shared parent tracking
    (doseq [shared-id shared-nodes]
      (swap! state assoc-in [:shared-parent-info shared-id]
             {:parent-ids (set (get parents shared-id []))
              :placed-parents #{}}))

    (letfn [(all-parents-placed? [shared-id]
              (let [info (get-in @state [:shared-parent-info shared-id])]
                (= (:parent-ids info) (:placed-parents info))))

            (mark-parent-placed [parent-id]
              ;; Mark this parent as placed for all shared children
              (doseq [child-id (get children parent-id [])]
                (when (contains? shared-nodes child-id)
                  (swap! state update-in [:shared-parent-info child-id :placed-parents]
                         conj parent-id))))

            (draw-edge [from-id to-id]
              (let [from-pos (get-in @state [:grid-pos from-id])
                    to-pos (get-in @state [:grid-pos to-id])
                    arg-name (get edge-arg-names (str from-id "->" to-id) "")]
                (when (and from-pos to-pos)
                  (let [from-col (:col from-pos)
                        from-row (:row from-pos)
                        to-col (:col to-pos)
                        to-row (:row to-pos)]
                    (if (= to-row from-row)
                      ;; Horizontal edge
                      (doseq [c (range from-col to-col)]
                        (when-not (has-h-edge-at? (:matrix @state) from-row c)
                          (swap! state update :matrix place-h-edge from-row c
                                 (if (= c from-col) arg-name ""))))
                      ;; Vertical + horizontal edge
                      (do
                        (let [[r-start r-end] (if (< from-row to-row)
                                                [(inc from-row) (inc to-row)]
                                                [(inc to-row) (inc from-row)])]
                          (doseq [r (range r-start r-end)]
                            (swap! state update :matrix place-v-edge r from-col)))
                        (doseq [c (range from-col to-col)]
                          (when-not (has-h-edge-at? (:matrix @state) to-row c)
                            (swap! state update :matrix place-h-edge to-row c
                                   (if (= c from-col) arg-name ""))))))))))

            (place-shared-node-if-ready [shared-id]
              ;; Place shared node when all its parents are placed
              ;; Strategy: place on the SAME ROW as the last (lowest) parent
              ;; This creates a horizontal connection from the last parent
              (when (and (not (contains? (:placed @state) shared-id))
                         (all-parents-placed? shared-id))
                (let [parent-ids (get parents shared-id [])
                      parent-positions (keep #(get-in @state [:grid-pos %]) parent-ids)
                      ;; Place on row of last (lowest) parent - SAME row for horizontal edge
                      target-row (apply max (map :row parent-positions))
                      ;; Column is one past the rightmost parent
                      max-parent-col (apply max (map :col parent-positions))
                      target-col (inc max-parent-col)
                      ;; Check if target cell is free, otherwise find next free row
                      actual-row (if (cell-occupied? (:matrix @state) target-row target-col)
                                   (find-row-for-branch (:matrix @state) target-col 1 target-row)
                                   target-row)]
                  ;; Place the shared node
                  (swap! state update :matrix place-node shared-id actual-row target-col)
                  (swap! state assoc-in [:grid-pos shared-id] {:row actual-row :col target-col})
                  (swap! state update :placed conj shared-id)
                  (mark-parent-placed shared-id)
                  ;; Draw edges from all parents
                  (doseq [parent-id parent-ids]
                    (draw-edge parent-id shared-id))
                  ;; Recursively process children of shared node
                  (let [shared-children (get children shared-id [])]
                    (when (seq shared-children)
                      (let [sorted-children (sort-children-by-priority shared-children node-data-map)
                            child-row-atom (atom (inc actual-row))]
                        (doseq [child-id sorted-children]
                          (when (not (contains? (:placed @state) child-id))
                            (let [child-col (inc target-col)]
                              (place-branch-and-children child-id child-col @child-row-atom)
                              (when-let [child-pos (get-in @state [:grid-pos child-id])]
                                (draw-edge shared-id child-id)
                                (reset! child-row-atom (inc (:row child-pos)))))))))))))

            (place-branch-and-children [start-node-id start-col min-row]
              (let [branch (build-horizontal-branch
                             start-node-id children
                             (:placed @state) node-data-map shared-info
                             #{} parents
                             (:path-lengths shared-info))
                    new-nodes (filterv #(not (contains? (:placed @state) %)) branch)]
                (if (seq new-nodes)
                  (let [branch-length (count new-nodes)
                        current-row (find-row-for-branch (:matrix @state) start-col branch-length min-row)]
                    ;; Place branch nodes
                    (doseq [[i node-id] (map-indexed vector new-nodes)]
                      (let [col (+ start-col i)]
                        (swap! state update :matrix place-node node-id current-row col)
                        (swap! state assoc-in [:grid-pos node-id] {:row current-row :col col})
                        (swap! state update :placed conj node-id)
                        ;; Mark as parent placed for shared children
                        (mark-parent-placed node-id)
                        ;; Place horizontal edge to next node
                        (when (< i (dec (count new-nodes)))
                          (let [next-id (nth new-nodes (inc i))
                                arg-name (get edge-arg-names (str node-id "->" next-id) "")]
                            (swap! state update :matrix place-h-edge current-row col arg-name)))))

                    ;; Process children of branch nodes (right to left)
                    (let [max-row-used (atom current-row)]
                      (doseq [i (reverse (range (count new-nodes)))]
                        (let [node-id (nth new-nodes i)
                              node-col (+ start-col i)
                              node-row current-row
                              node-children (get children node-id [])
                              branch-continuation (when (< i (dec (count new-nodes)))
                                                    (nth new-nodes (inc i)))]
                          (when (seq node-children)
                            (let [sorted-children (sort-children-by-priority node-children node-data-map)
                                  ;; Group parents of shared nodes together
                                  grouped-children (group-shared-parents sorted-children children shared-info)
                                  adjusted-children (if (seq shared-nodes)
                                                      (adjust-priority-for-shared
                                                        grouped-children shared-info node-id
                                                        parents (:path-lengths shared-info)
                                                        #{})
                                                      grouped-children)
                                  ;; IMPORTANT: Separate ready shared nodes from other children
                                  ;; Ready shared nodes go HORIZONTAL (same row as this parent)
                                  ;; Other children go VERTICAL (below on separate rows)
                                  ready-shared (filterv #(and (contains? shared-nodes %)
                                                              (all-parents-placed? %))
                                                        adjusted-children)
                                  other-children (filterv #(not (and (contains? shared-nodes %)
                                                                     (all-parents-placed? %)))
                                                          adjusted-children)
                                  ;; Start vertical children below current row
                                  child-row-atom (atom (inc node-row))]

                              ;; FIRST: Place ready shared nodes HORIZONTALLY
                              ;; These go on same row as this parent (extends branch)
                              (doseq [shared-id ready-shared]
                                (when-not (contains? (:placed @state) shared-id)
                                  (let [shared-col (inc node-col)
                                        ;; Find first free column on this row
                                        actual-col (loop [c shared-col]
                                                     (if (cell-occupied? (:matrix @state) node-row c)
                                                       (recur (inc c))
                                                       c))]
                                    ;; Place shared node on same row
                                    (swap! state update :matrix place-node shared-id node-row actual-col)
                                    (swap! state assoc-in [:grid-pos shared-id] {:row node-row :col actual-col})
                                    (swap! state update :placed conj shared-id)
                                    (mark-parent-placed shared-id)
                                    ;; Draw edge from this parent
                                    (draw-edge node-id shared-id)
                                    ;; Draw edges from OTHER parents
                                    (doseq [other-parent-id (get parents shared-id [])]
                                      (when (and (not= other-parent-id node-id)
                                                 (contains? (:placed @state) other-parent-id))
                                        (draw-edge other-parent-id shared-id)))
                                    ;; Process children of shared node
                                    (let [shared-children (get children shared-id [])]
                                      (when (seq shared-children)
                                        (let [sorted-sc (sort-children-by-priority shared-children node-data-map)
                                              sc-row-atom (atom (inc node-row))]
                                          (doseq [sc-id sorted-sc]
                                            (when-not (contains? (:placed @state) sc-id)
                                              (place-branch-and-children sc-id (inc actual-col) @sc-row-atom)
                                              (when-let [sc-pos (get-in @state [:grid-pos sc-id])]
                                                (draw-edge shared-id sc-id)
                                                (reset! sc-row-atom (inc (:row sc-pos)))
                                                (reset! max-row-used (max @max-row-used (:row sc-pos))))))))))))

                              ;; SECOND: Place other children VERTICALLY (below)
                              (doseq [child-id other-children]
                                (when (not= child-id branch-continuation)
                                  (let [child-col (inc node-col)
                                        is-shared (contains? shared-nodes child-id)
                                        already-placed (contains? (:placed @state) child-id)]
                                    (cond
                                      ;; Shared node not yet ready - skip for now
                                      is-shared
                                      (do
                                        (place-shared-node-if-ready child-id)
                                        (when-let [child-pos (get-in @state [:grid-pos child-id])]
                                          (reset! max-row-used (max @max-row-used (:row child-pos)))))

                                      ;; Already placed - draw edge
                                      already-placed
                                      (when-let [child-pos (get-in @state [:grid-pos child-id])]
                                        (draw-edge node-id child-id))

                                      ;; New non-shared node - place BELOW and draw edge
                                      :else
                                      (do
                                        (place-branch-and-children child-id child-col @child-row-atom)
                                        (when-let [child-pos (get-in @state [:grid-pos child-id])]
                                          (draw-edge node-id child-id)
                                          (reset! child-row-atom (inc (:row child-pos)))
                                          (reset! max-row-used (max @max-row-used (:row child-pos)))))))))))))
                      @max-row-used)
                    current-row)
                  min-row)))]

      ;; Start placement from root
      (when root-id
        (place-branch-and-children root-id 0 0))

      {:matrix (:matrix @state)
       :grid-pos (:grid-pos @state)})))


;; =============================================================================
;; VALIDATION
;; =============================================================================

(defn validate-matrix
  "Validate the layout matrix for collisions and crossings."
  [matrix grid-pos]
  (let [issues (atom [])
        positions (atom {})]
    ;; Check for node collisions
    (doseq [[node-id pos] grid-pos]
      (let [key (str (:row pos) "," (:col pos))]
        (if-let [existing (get @positions key)]
          (swap! issues conj {:type "collision"
                              :message (str "Nodes " existing " and " node-id
                                           " both at (" (:row pos) ", " (:col pos) ")")})
          (swap! positions assoc key node-id))))
    {:valid (empty? @issues)
     :issues @issues}))


;; =============================================================================
;; HIGH-LEVEL API
;; =============================================================================

(defn compute-layout-matrix
  "Compute grid-based layout from elements.
   Input: {:nodes [...], :edges [...]}
   Output: {:grid-pos {node-id {:row n :col n}}, :validation {...}}"
  [{:keys [elements]}]
  (let [nodes (or (:nodes elements) [])
        edges (or (:edges elements) [])]
    (if (empty? nodes)
      {:grid-pos {}
       :validation {:valid true :issues []}}
      (let [{:keys [children parents edge-arg-names]} (build-adjacency edges)
            root-id (find-root-node nodes edges)]
        (if-not root-id
          {:grid-pos {}
           :validation {:valid false
                        :issues [{:type "no_root" :message "No root node found"}]}}
          (let [node-data-map (into {} (map (fn [n] [(:id n) n]) nodes))
                {:keys [matrix grid-pos]} (build-matrix root-id children parents
                                                        edge-arg-names node-data-map)
                validation (validate-matrix matrix grid-pos)]
            {:grid-pos grid-pos
             :validation validation}))))))


;; =============================================================================
;; API HANDLER
;; =============================================================================

(defn get-layout-data
  "Compute layout from elements passed in request body.
   Request body should be JSON: {\"elements\": {\"nodes\": [...], \"edges\": [...]}}
   Each node needs: id, type, label
   Each edge needs: source, target, (optional) argName"
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
