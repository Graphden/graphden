(ns graphden.layout.core
  "Layout algorithm + request parsing (Stages 3-7 of the layout
   pipeline).

   Builds on `graphden.layout.graph` (Stages 1-2). Holds:

   - graph-structure derivation (`build-graph-info`);
   - the grid-placement algorithm (`layout-graph` and its matrix ops);
   - layout validation;
   - request parsing (`parse-layout-request`, delegating the JSON
     body→map step to `graphden.crud.request/read-json-body`);
   - the public orchestrator `compute-layout` — runs the
     build→layout→validate pipeline and returns
     `{:nodes :edges :grid-pos :validation}`, the exact shape the
     `get-layout-data` defbase emitted before this extraction.

   `compute-layout-matrix` stays public for the layout tests, which
   feed it hand-built node/edge lists."
  (:require
    [graphden.crud.request :as request]
    [graphden.layout.graph :as lgraph]))


;; =============================================================================
;; LAYOUT ALGORITHM
;; =============================================================================

(defn build-graph-info
  "Build graph structure from nodes and edges for layout."
  [nodes edges]
  (let [children (reduce (fn [m e]
                           (update m (get-in e [:data :source]) (fnil conj []) (get-in e [:data :target])))
                         {} edges)
        parents-map (reduce (fn [m e]
                              (update m (get-in e [:data :target]) (fnil conj []) (get-in e [:data :source])))
                            {} edges)
        shared-nodes (->> parents-map
                          (filter (fn [[_ ps]] (> (count ps) 1)))
                          (map first)
                          (into #{}))
        node-data-map (into {} (map (fn [n] [(get-in n [:data :id]) (:data n)]) nodes))]
    {:children children
     :parents parents-map
     :shared-nodes shared-nodes
     :node-data-map node-data-map}))


(defn find-root-node
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
           (java.util.List/.indexOf child-ids cid)])
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


(defn parse-layout-request
  "Parse request body into {:root-id UUID, :expansions parsed-map}.
   Throws on missing root-id. The JSON body→map coercion (InputStream
   / string / already-parsed map → keywordized map) is delegated to
   `graphden.crud.request/read-json-body` — the layout endpoint and
   the types API share that logic."
  [request]
  (let [body (request/read-json-body request)
        root-id-str (:root-id body)]
    (when-not root-id-str
      (throw (ex-info "Request body must contain 'root-id'"
                      {:type :execution-error/invalid-args})))
    {:root-id (java.util.UUID/fromString root-id-str)
     :expansions (parse-expansions (:expansions body {}))}))


(defn build-elements
  "Stage A of the layout pipeline. Takes the loaded graph entities
   (the `{:fns :slots …}` snapshot already enriched with `:args` —
   see `graphden.layout.graph/load-graph-entities-uncached`) plus the
   request's `:root-id` and parsed `:expansions`, and produces the
   cytoscape `{:nodes [...] :edges [...]}` for that subgraph.

   Throws `:execution-error/not-found` when `root-id` isn't in the
   graph."
  [graph-entities root-id expansions]
  (let [lookups (lgraph/cached-build-lookups graph-entities)]
    (when-not (get (:fn-map lookups) root-id)
      (throw (ex-info "Root function not found"
                      {:type :execution-error/not-found
                       :root-id root-id})))
    (lgraph/build-graph-elements root-id expansions lookups)))


(defn place-elements
  "Stage B of the layout pipeline. Grid-places the `{:nodes :edges}`
   produced by `build-elements` and returns the full layout response
   `{:nodes :edges :grid-pos :validation}` — `:nodes` / `:edges` are
   passed through unchanged."
  [{:keys [nodes edges] :as elements}]
  (let [graph-info (build-graph-info nodes edges)
        root-node (find-root-node nodes edges)
        matrix (if root-node
                 (layout-graph (get-in root-node [:data :id]) graph-info)
                 (empty-matrix))
        validation (validate-layout matrix)]
    (assoc elements
           :grid-pos (:positions matrix)
           :validation validation)))


(defn compute-layout
  "Orchestrate the full layout pipeline for a parsed request:
   Stage A (`build-elements`) then Stage B (`place-elements`).
   Returns `{:nodes [...] :edges [...] :grid-pos {...}
   :validation {...}}`. Throws `:execution-error/not-found` when
   `root-id` isn't in the graph."
  [graph-entities root-id expansions]
  (place-elements (build-elements graph-entities root-id expansions)))
