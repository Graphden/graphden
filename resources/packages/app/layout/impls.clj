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
  [sorted-children shared-info current-node-id parents path-lengths reserved-for-lower]
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
  "Build a horizontal branch starting from node-id."
  [start-node-id children placed node-data-map shared-info
   reserved-for-lower parents path-lengths]
  (loop [branch []
         current start-node-id]
    (if (or (nil? current) (contains? placed current))
      branch
      (if (and (contains? reserved-for-lower current)
               ;; Not lower branch targeting this
               true)
        branch
        (let [new-branch (conj branch current)
              node-children (get children current [])
              sorted-children (sort-children-by-priority node-children node-data-map)
              adjusted-children (if (seq (:shared-nodes shared-info))
                                 (adjust-priority-for-shared
                                   sorted-children shared-info current
                                   parents path-lengths reserved-for-lower)
                                 sorted-children)
              ;; Find next unplaced child
              next-child (first (filter
                                  (fn [c]
                                    (and (not (contains? placed c))
                                         (not (contains? reserved-for-lower c))))
                                  adjusted-children))]
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
  "Build the complete layout matrix."
  [root-id children parents edge-arg-names node-data-map]
  (let [shared-info (analyze-shared-arguments children parents)
        state (atom {:matrix (create-matrix-state)
                     :grid-pos {}
                     :placed #{}
                     :reserved-for-lower (:shared-nodes shared-info)
                     :deferred-children {}})]

    (letfn [(place-branch-and-children [start-node-id start-col min-row]
              (let [branch (build-horizontal-branch
                             start-node-id children
                             (:placed @state) node-data-map shared-info
                             (:reserved-for-lower @state) parents
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
                                  adjusted-children (if (seq (:shared-nodes shared-info))
                                                      (adjust-priority-for-shared
                                                        sorted-children shared-info node-id
                                                        parents (:path-lengths shared-info)
                                                        (:reserved-for-lower @state))
                                                      sorted-children)
                                  child-row-atom (atom (inc node-row))]
                              (doseq [child-id adjusted-children]
                                (when (and (not= child-id branch-continuation)
                                           (not (contains? (:placed @state) child-id))
                                           (not (contains? (:reserved-for-lower @state) child-id)))
                                  (let [child-col (inc node-col)]
                                    (place-branch-and-children child-id child-col @child-row-atom)
                                    (when-let [child-pos (get-in @state [:grid-pos child-id])]
                                      ;; Draw edge to child
                                      (let [arg-name (get edge-arg-names (str node-id "->" child-id) "")]
                                        (if (= (:row child-pos) node-row)
                                          ;; Horizontal edge
                                          (doseq [c (range node-col (:col child-pos))]
                                            (when-not (has-h-edge-at? (:matrix @state) node-row c)
                                              (swap! state update :matrix place-h-edge node-row c
                                                     (if (= c node-col) arg-name ""))))
                                          ;; Vertical + horizontal edge
                                          (do
                                            (doseq [r (range (inc node-row) (inc (:row child-pos)))]
                                              (swap! state update :matrix place-v-edge r node-col))
                                            (doseq [c (range node-col (:col child-pos))]
                                              (when-not (has-h-edge-at? (:matrix @state) (:row child-pos) c)
                                                (swap! state update :matrix place-h-edge (:row child-pos) c
                                                       (if (= c node-col) arg-name "")))))))
                                      (reset! child-row-atom (inc (:row child-pos)))
                                      (reset! max-row-used (max @max-row-used (:row child-pos)))))))))))
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
