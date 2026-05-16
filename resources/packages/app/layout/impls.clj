(ns graphden.packages.app.layout.impls
  "Graph layout calculation — thin defbase shim.

   API: POST /api/graph/layout
   Input: {root-id: uuid, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}

   The whole layout algorithm lives in `graphden.layout.graph`
   (Stages 1-2 — slot-view synthesis, data loading, graph building)
   and `graphden.layout.core` (Stages 3-7 — placement, validation,
   request parsing, the `compute-layout` orchestrator) so this
   base-fn impl stays a minimal primitive: read storage from `ctx`,
   parse the request, load the graph, delegate."
  (:require
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :as defbase]
    [graphden.layout.core :as layout]
    [graphden.layout.graph :as lgraph]))


(defn- load-graph-entities
  "Graph entities are loaded ONCE per executor context and cached on
   `(:graph-cache ctx)`. Layout runs on every hover-preview + click; a
   full `query-all-graph-entities` takes ~130ms on the current graph,
   so we cannot re-query per request. Invalidation is driven by CRUD
   mutation defbase's calling `invalidate-graph-cache!` after writing."
  [ctx]
  (or (some-> (exec-ctx/cached-graph ctx) lgraph/ensure-synth-args)
      (let [data (lgraph/load-graph-entities-uncached (:storage ctx))]
        (exec-ctx/fill-graph-cache! ctx data)
        data)))


(defbase/defbase get-layout-data
  "Compute layout from root-id and expansions.
   Input (from request body): {root-id: uuid-string, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}"
  [request]
  (let [storage (:storage ctx)]
    (when-not storage
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))
    (let [{:keys [root-id expansions]} (layout/parse-layout-request request)]
      (layout/compute-layout (load-graph-entities ctx) root-id expansions))))


;; === Registry ===

(def impls
  {:get-layout-data get-layout-data})
