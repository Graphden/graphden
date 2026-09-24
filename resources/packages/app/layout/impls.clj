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
    [clojure.string :as str]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.layout.builder-helpers :as bh]
    [graphden.layout.core :as layout]
    [graphden.layout.graph :as lgraph]
    [graphden.layout.strip-facts :as strip-facts]
    [graphden.versioning.branch-local :as branch-local]))


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
  "Single library call — build graph `{:nodes :edges}` for the
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


(defbase _layout-strip-facts-apply
  "Single library call — annotate every fn-node's `:data` with the
   strip facts (`:returnTypeAlias` / `:ruleOwner` / `:branchLocal`)
   the editor's bottom-of-card strips render. See
   `graphden.layout.strip-facts`."
  [elements graph]
  (strip-facts/annotate elements graph))


;; === Registry ===

(defn- seal-note
  "One line for the Inspector row: what is decided on the slot, and by whom."
  [{:keys [sealedByName listClosedByName requiredByName]} optional?]
  (str/join " · "
            (cond-> []
              sealedByName (conj (str "sealed in " sealedByName))
              listClosedByName (conj (str "list closed in " listClosedByName))
              (and optional? requiredByName) (conj (str "required since " requiredByName))
              (and optional? (not requiredByName)) (conj "optional"))))


(defbase _fn-branch-local-seed
  "Whether `fn-id` is branch-local and who made it so — `{:own bool
   :seed \"name\"}` when the fn or an ancestor carries `:branch-local?
   true` (`branch-local/branch-local-seed`, the walk the card's 📍 strip
   reads through layout strip-facts), nil when nothing in the chain
   does. The Inspector's Overview prints it so compact cards — which
   hide the metadata strips — still show and set the flag."
  [fn-id]
  (when fn-id
    (let [fns-by-id (:fn-map (lgraph/cached-build-lookups (load-graph-entities ctx)))
          seed (branch-local/branch-local-seed fns-by-id fn-id)]
      (when seed
        {:own (= (:id seed) fn-id) :seed (:name seed)}))))


(defbase _fn-slot-seals
  "The seals on every slot of `fn-id`'s inheritance chain, keyed by the
   slot's effective name — the Inspector's Bindings tab prints the
   `:note` beside each row. Per slot: `:sealed-by` / `:list-closed-by` /
   `:required-by` (the NAME of the deciding fn, from `edge-seal-fields`
   — the same walk the canvas lock badge shows), `:optional?` (declared
   `:required false`) and the `:note`. One cached graph read."
  [fn-id]
  (when fn-id
    (let [lookups (lgraph/cached-build-lookups (load-graph-entities ctx))
          {:keys [args-by-fn slot-map]} lookups]
      (into {}
            (keep (fn [arg]
                    (let [seals (bh/edge-seal-fields lookups (:id arg))
                          slot (get slot-map (:slot-id arg))
                          optional? (false? (:required slot))]
                      (when (or (seq seals) optional?)
                        [(keyword (or (:name arg) (:name slot)))
                         (cond-> {:optional? optional? :note (seal-note seals optional?)}
                           (:sealedByName seals) (assoc :sealed-by (:sealedByName seals))
                           (:listClosedByName seals) (assoc :list-closed-by (:listClosedByName seals))
                           (:requiredByName seals) (assoc :required-by (:requiredByName seals)))]))))
            (get args-by-fn fn-id)))))


(def impls
  {:_fn-branch-local-seed _fn-branch-local-seed
   :_fn-slot-seals _fn-slot-seals
   :_load-graph-cached _load-graph-cached
   :_parse-layout-body _parse-layout-body
   :_layout-build-apply _layout-build-apply
   :_layout-place-apply _layout-place-apply
   :_layout-strip-facts-apply _layout-strip-facts-apply})
