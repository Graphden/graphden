(ns graphden.packages.app.layout.impls
  "Graph layout calculation — atomic library-boundary defbases.

   API: POST /api/graph/layout
   Input: {root-id: uuid, expansions: {fn-id: level, ...}}
   Output: {nodes: [...], edges: [...], grid-pos: {...}, validation: {...}}

   The whole layout algorithm lives in `graphden.layout.*`:
   `data` (Stage 1 — slot-view synthesis, data loading, lookup maps),
   `bindings` (classifier-item constructors + sequence-anchor helpers),
   `builder-helpers` (pure helpers used by `build-graph-elements`),
   `graph` (Stage 2 — `process-*` walkers, post-processing,
   `build-graph-elements` orchestrator), `core` (Stages 3-7 —
   placement, validation, request parsing, `compute-layout`).

   The `parse → load → build → place` pipeline that produces a layout
   is expressed as graph fn-defs in `fns.edn`. Even the error-handling
   shape — multi-catch dispatch on exception class for parse, single
   `ExceptionInfo` catch for build, pass-through guards on upstream
   `{:ok false}` payloads — is graph composition via `:try` / `:case` /
   `:if`. Each base-fn here is ONE library call: load entities from
   storage, parse a request, build the cytoscape element lists, or
   grid-place them."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
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
      ;; Cache miss → an actual storage read. Record `:db` HERE (not
      ;; unconditionally) so the effect trace + gate see the DB access
      ;; only when it really happens — a warm-cache hit performs no
      ;; effect. Closes the coverage gap the effect-gate audit found.
      (do
        (cr/record-effect! :db)
        (let [data (lgraph/load-graph-entities-uncached (:storage ctx))]
          (exec-ctx/fill-graph-cache! ctx data)
          data))))


(defbase _load-graph-cached
  "Load every graph entity for layout, served from the per-context
   cache when warm. Context-aware: no args, pulls storage + cache
   from `ctx`."
  []
  (load-graph-entities ctx))


(defbase _parse-layout-body
  "Single library call — parse the Ring request body into
   `{:root-id :expansions}`. Thrown by `layout/parse-layout-request`:
   `JsonParseException` (malformed JSON), `ExceptionInfo` (missing
   `:root-id`), `IllegalArgumentException` (bad UUID). The graph's
   `:try` + class-name dispatch in `fns.edn` turns each into the
   appropriate `{:ok false :error}` shape."
  [request]
  (layout/parse-layout-request request))


(defbase _layout-build-apply
  "Single library call — build cytoscape `{:nodes :edges}` for the
   requested subgraph. Throws `ExceptionInfo` (`:execution-error/not-
   found`) when `:root-id` doesn't resolve; the graph's `:try` turns
   that into `{:ok false :error}`."
  [graph parsed]
  (layout/build-elements graph (:root-id parsed) (:expansions parsed)))


(defbase _layout-place-apply
  "Single library call — grid-place the `{:nodes :edges}` from
   `:_layout-build-elements` into the full layout response
   `{:nodes :edges :grid-pos :validation}`."
  [elements]
  (layout/place-elements elements))


;; === Registry ===

(def impls
  {:_load-graph-cached _load-graph-cached
   :_parse-layout-body _parse-layout-body
   :_layout-build-apply _layout-build-apply
   :_layout-place-apply _layout-place-apply})
