(ns graphden.packages.app.layout.impls
  "Graph layout calculation — thin defbase shims.

   API: POST /api/graph/layout
   Input: {root-id: uuid, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}

   The whole layout algorithm lives in `graphden.layout.graph`
   (Stages 1-2 — slot-view synthesis, data loading, graph building)
   and `graphden.layout.core` (Stages 3-7 — placement, validation,
   request parsing, the `compute-layout` orchestrator).

   The `parse → load → build → place` pipeline that produces a layout
   is itself a composition, so it is expressed as graph fn-defs
   (`:_compute-layout` and `:get-layout-data` in `fns.edn`) gluing the
   base-fns below. Each base-fn stays a minimal primitive: read `ctx`
   and/or delegate to one `graphden.layout.*` call."
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


(defbase/defbase _parse-layout-request
  "Parse the internal Ring request body into `{:root-id :expansions}`.
   Thin wrapper around `layout/parse-layout-request`."
  [request]
  (layout/parse-layout-request request))


(defbase/defbase _load-graph-cached
  "Load every graph entity for layout, served from the per-context
   cache when warm. Context-aware: no args, pulls storage + cache
   from `ctx`."
  []
  (load-graph-entities ctx))


(defbase/defbase _layout-build-elements
  "Stage A — build cytoscape `{:nodes :edges}` for the requested
   subgraph. `graph` is the entity snapshot from `_load-graph-cached`;
   `parsed` is `{:root-id :expansions}` from `_parse-layout-request`."
  [graph parsed]
  (layout/build-elements graph (:root-id parsed) (:expansions parsed)))


(defbase/defbase _layout-place
  "Stage B — grid-place the `{:nodes :edges}` from
   `_layout-build-elements` into the full layout response
   `{:nodes :edges :grid-pos :validation}`."
  [elements]
  (layout/place-elements elements))


;; === Registry ===

(def impls
  {:_parse-layout-request _parse-layout-request
   :_load-graph-cached _load-graph-cached
   :_layout-build-elements _layout-build-elements
   :_layout-place _layout-place})
