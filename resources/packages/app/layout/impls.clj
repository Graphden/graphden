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
   storage, parse a request, build the graph element lists, or
   grid-place them."
  (:require
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.value-repr :as value-repr]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.layout.builder-helpers :as bh]
    [graphden.layout.core :as layout]
    [graphden.layout.graph :as lgraph]
    [graphden.layout.strip-facts :as strip-facts]))


(defn- load-graph-entities
  "Graph entities are loaded ONCE per executor context and cached on
   `(:graph-cache ctx)`. Layout runs on every hover-preview + click; a
   full `query-all-graph-entities` takes ~130ms on the current graph,
   so we cannot re-query per request. Invalidation is driven by CRUD
   mutation defbase's calling `invalidate-graph-cache!` after writing."
  [ctx]
  ;; Record `:db` only on a real cache miss (the effect trace + gate see
  ;; the DB access only when it happens — the effect-gate audit's coverage
  ;; fix), then delegate to the shared org-visibility-sliced reader: the
  ;; raw cache is org-AGNOSTIC, and layout must see exactly the viewer's
  ;; own + public rows like every other graph read (`org-visible-slice`).
  (when-not (exec-ctx/cached-graph ctx)
    (cr/record-effect! :db))
  (lgraph/ensure-synth-args (types-api/cached-or-load-graph ctx)))


(defbase _load-graph-cached
  "Load every graph entity for layout, served from the per-context
   cache when warm. Context-aware: no args, pulls storage + cache
   from `ctx`."
  []
  (load-graph-entities ctx))


(defbase _layout-read-json-body
  "Decode the Ring body with the canonical JSON request codec."
  [request]
  (request/read-json-body request))


(defbase _layout-parse-expansions
  "Canonical expansion-spec codec, preserving per-call-site string node ids."
  [expansions]
  (layout/parse-expansions expansions))


(defbase _layout-parse-uuid
  "Strict UUID codec; malformed input preserves IllegalArgumentException."
  [value]
  (java.util.UUID/fromString value))


(defbase _layout-value-class
  "Host value class for the request's existing diagnostic contract."
  [value]
  (Class/.getSimpleName (class value)))


(defbase _layout-build-apply
  "Single library call — build graph `{:nodes :edges}` for the
   requested subgraph, as the viewer may see it (a fn whose
   composition is hidden draws as its signature). Throws `ExceptionInfo` (`:execution-error/not-
   found`) when `:root-id` doesn't resolve; the graph's `:try` turns
   that into `{:ok false :error}`."
  [graph parsed]
  (layout/build-elements-for-viewer graph (:root-id parsed) (:expansions parsed)))


(defbase _layout-place-apply
  "Single library call — grid-place the `{:nodes :edges}` from
   `:_layout-build-elements` into the full layout response
   `{:nodes :edges :grid-pos :validation}`."
  [elements]
  (layout/place-elements elements))


(defbase _layout-safe-strip-facts
  "Compute canonical concealed-chain-safe facts without attaching them to nodes."
  [elements graph]
  (strip-facts/facts-by-fn elements graph))


(defbase _layout-literal-previews
  "Evaluate a bounded canonical batch of safe previews, without attaching node fields."
  [elements graph]
  (value-repr/literal-preview-entries ctx elements graph))


;; === Registry ===

(defbase _fn-branch-local-seed
  "Whether `fn-id` is branch-local and who made it so — `{:own bool
   :seed \"name\"}` when the fn or an ancestor carries `:branch-local?
   true` (`strip-facts/viewer-branch-local`, the walk the card's 📍
   strip reads — the seed unnamed when it sits beyond a fn whose
   composition the viewer may not see), nil when nothing in the chain
   does. The Inspector's Overview prints it so compact cards — which
   hide the metadata strips — still show and set the flag."
  [fn-id]
  (when fn-id
    (let [fns-by-id (:fn-map (lgraph/cached-build-lookups (load-graph-entities ctx)))]
      (strip-facts/viewer-branch-local
        fns-by-id (entity-list/conceal-parents fns-by-id [fn-id]) fn-id))))


(defbase _fn-slot-seal-facts
  "Read effective slot identities and inherited seals through the viewer's
   concealed graph. Only authorized names leave the canonical seal walk."
  [fn-id]
  (if-not fn-id
    []
    (let [lookups (lgraph/cached-build-lookups
                    (:graph (layout/viewer-graph (load-graph-entities ctx) [fn-id])))
          {:keys [args-by-fn slot-map]} lookups]
      (mapv (fn [arg]
              (let [seals (bh/edge-seal-fields lookups (:id arg))
                    slot (get slot-map (:slot-id arg))]
                [(keyword (or (:name arg) (:name slot)))
                 (cond-> {:optional? (false? (:required slot))}
                   (:sealedByName seals) (assoc :sealed-by (:sealedByName seals))
                   (:listClosedByName seals) (assoc :list-closed-by (:listClosedByName seals))
                   (:requiredByName seals) (assoc :required-by (:requiredByName seals)))]))
            (get args-by-fn fn-id)))))


(def impls
  {:_fn-branch-local-seed _fn-branch-local-seed
   :_fn-slot-seal-facts _fn-slot-seal-facts
   :_load-graph-cached _load-graph-cached
   :_layout-read-json-body {:impl _layout-read-json-body :taint-propagate? true}
   :_layout-parse-expansions {:impl _layout-parse-expansions :taint-propagate? true}
   :_layout-parse-uuid {:impl _layout-parse-uuid :taint-propagate? true}
   :_layout-value-class {:impl _layout-value-class :taint-propagate? true}
   :_layout-build-apply {:impl _layout-build-apply :taint-propagate? true}
   :_layout-literal-previews {:impl _layout-literal-previews :taint-propagate? true}
   :_layout-place-apply {:impl _layout-place-apply :taint-propagate? true}
   :_layout-safe-strip-facts {:impl _layout-safe-strip-facts :taint-propagate? true}})
