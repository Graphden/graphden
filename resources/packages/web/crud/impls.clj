(ns graphden.packages.web.crud.impls
  "Implementations for the shared `web.crud` primitives — the graph-lookup boundaries every other crud module composes over.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.crud.fn-execution.lookup :as fn-exec-lookup]
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.export :as export]
    [graphden.schema.graph.schema :as graph-schema]
    [graphden.storage.protocol.core :as sp]))


(defbase get-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/get-entity entity-type id ctx))


(defbase delete-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/delete-entity entity-type id ctx))


(defbase query-entities-fn
  [entity-type where]
  (cr/record-effect! :db)
  (entities/list-entities entity-type where ctx))


(defbase create-entity-fn
  [entity-type data]
  (cr/record-effect! :db)
  (entities/create-entity entity-type data ctx))


(defbase update-entity-fn
  [entity-type id data]
  (cr/record-effect! :db)
  (entities/update-entity entity-type id data ctx))


(defbase free-arg-slot-map
  "`{arg-name → slot-id}` for `fn-id`'s free args, BFS'd across the
   inheritance chain + transitive captured-args of referenced fn-graphs.
   Returns `{}` for nil fn-id (defensive — callers usually guard for
   `:some? fn-id` upstream).

   Single-library boundary over `lookup/free-arg-slot-map`. The BFS
   itself is a graph-traversal algorithm with shared state (cycle-
   tracking set) that doesn't decompose cleanly into atomic graph
   primitives — exactly the §3.3 invariant carve-out.

   Lives in `web/crud` so CRUD-write-time guards
   (`:_create-service-free-args-rej`) can reference it without forcing
   a downstream-package dependency. `app/execution`'s validation chain
   reuses it transitively via the `app → web` package edge."
  [fn-id]
  (cr/record-effect! :db)
  (if (some? fn-id)
    (fn-exec-lookup/free-arg-slot-map ctx fn-id)
    {}))


(defbase service-blocking-free-args
  "`{arg-name → slot-id}` for only the free args that would prevent `fn-id`
   from being STARTED as a service — the service-ability projection of
   `free-arg-slot-map` (drops callback subtrees whose free args the deferred
   invoker supplies per invocation). `{}` for nil fn-id.

   Single-library boundary over `lookup/service-blocking-free-args`; used by
   the `:service` create-guard so a listener/whole-app fn (all its free args
   below its `:handler` HOF slot) is not falsely rejected, while a genuinely
   unstartable fn still is."
  [fn-id]
  (cr/record-effect! :db)
  (if (some? fn-id)
    (fn-exec-lookup/service-blocking-free-args ctx fn-id)
    {}))


(defbase graph-fn-defs-subtree
  [root-id]
  (cr/record-effect! :db)
  (if (some? root-id)
    (export/export-subtree (request/require-storage ctx) root-id)
    []))


(defbase list-all-graph-entities
  [scope root-id namespace-id q]
  ;; Storage read (via `cached-or-load-graph` + an explicit `query-entities`
  ;; for namespaces). Without the record-effect! the runtime
  ;; `:runtime-effects` list returned by `/api/execute` would silently
  ;; drop `:db` for this call — the declared `:effects #{:db}` in fns.edn
  ;; says the effect IS there, the runtime audit must match.
  ;;
  ;; `scope :tree` — `{:namespaces :counts}`, the O(namespaces) sidebar
  ;; init. `scope :namespace` + `namespace-id` — one namespace's light fn
  ;; rows (lazy expand). `scope :search` + `q` — name-substring matches,
  ;; capped (filter box + pickers + name resolution).
  ;;
  ;; `scope :index` — `{:fns :namespaces}` (legacy full-fns sidebar pull).
  ;; `scope :subtree` + `root-id` — the subgraph reachable from `root-id`.
  ;; Anything else (nil / :full) yields the unchanged full payload.
  (cr/record-effect! :db)
  (entities/list-all-graph-entities ctx scope root-id namespace-id q))


(defbase strip-hidden-impl
  [graph]
  ;; Pure pass-through over the tenancy view-impl seam — identity until the
  ;; addon installs a filter (single-tenant sees everything). No :db of its
  ;; own; the installed filter reads grants in the trusted platform ctx.
  (entities/apply-view-impl-filter graph))


(defbase all-rich-types
  []
  ;; Same as `list-all-graph-entities`: `rich-types-with-type-rows` calls
  ;; `cached-or-load-graph` which reads the `:fn` / `:slot` / `:fn-slot`
  ;; tables. Declared `:effects #{:db}` in fns.edn; mirror at runtime.
  (cr/record-effect! :db)
  (types-api/all-rich-types ctx))


(defbase api-rich-types
  []
  ;; Wire-shaping layer over the same src helper `all-rich-types` wraps
  ;; (`rich-types-with-type-rows` — shared PRIVATE helper, not a hidden
  ;; base-fn→base-fn edge): strips the heavy per-entry fields from the
  ;; bulk payload. Kept as one base-fn deliberately: this is the
  ;; measured hot path of finding K (docs/PERF_BUDGETS.md) — re-fetched
  ;; after every mutation; a per-entry graph HOF strip over ~4000
  ;; entries would re-add tens of ms per editor round-trip to a path
  ;; that was fought down 57%. The omitted-field list is the
  ;; documented contract (`bulk-omitted-fields`).
  (cr/record-effect! :db)
  (types-api/api-rich-types ctx))


(defbase fn-names-with-tag
  "Set of fn-NAMES (as text/strings) declared with the given `tag` in
   their `:tags`. Single library call over
   `registry/fn-names-with-tag`. Used by callers that need to find
   tagged base-fns declaratively instead of hardcoding name strings:
   e.g. the secret-leaf lookup chain queries by `:secret-shape` tag
   so admins can swap which base-fn is the leaf shape by editing
   `fns.edn`-tags only.

   Keyword names from the registry are stringified so the result
   composes cleanly into `:list-entities :where {:name <vec-of-strs>}`."
  [tag]
  (mapv name (registry/fn-names-with-tag (keyword tag))))


(defbase query-ref-many-owners
  "Find all rows of `:entity-type` whose `:ref-many-field` (a vector
   of ids) contains `:target-id`. Single storage call (`sp/query-
   ref-many-owners`). Returns a vector of owner ids. Reusable: secret
   inventory (`:parent-ids` containing secret-leaf-id), reverse
   reference lookups, parent-graph traversals."
  [entity-type ref-many-field target-id]
  (cr/record-effect! :db)
  (sp/query-ref-many-owners (request/require-storage ctx)
                            (keyword entity-type)
                            (keyword ref-many-field)
                            target-id))


(defbase value-kinds
  "The `value_kind` schema enum — ordered list of primitive type-tag
   strings (`\"int\"`, `\"text\"`, …) a binding value / slot can carry.
   The editor's type-pickers read this instead of hard-coding the list."
  []
  (mapv name graph-schema/value-kinds))


;; === Type-API base functions ===
;; `types-compatible` / `types-candidates` / `types-usages` are `:if`
;; graph fn-defs (`web/crud` fns.edn) — an `:if` over the validation
;; result, branching to the `{:ok false :error}` rejection or to the
;; computation. These base-fns are the parse / validate / apply stages;
;; `_rejected?` (below) is shared with every other `:if` handler.


(defbase try-apply-create
  "§3.3 atomic core of the create-apply flow: capability gate +
   `sp/create-entity` + Phase-6c rename-slot side-effect + post-
   create whole-fn type-check. Returns a uniform `{:created <id>}`
   on success — plus `:type-warnings [<diagnostic> …]` when the write
   landed but the owning fn now fails the aggregate type-check
   (error-tolerance Phase 2: the row is KEPT, the failure recorded as
   a per-branch diagnostic) — or `{:error <human-msg>}` on a write
   failure (cap-gate, storage error).

   The outer graph composition runs the STRUCTURAL rejection clauses
   (validation, secret-fn guard) BEFORE this primitive fires, then
   dispatches on the returned shape and runs invalidate / notify /
   response."
  [entity-type entity-data type-str form-data]
  (cr/record-effect! :db)
  (entities/apply-create-core {:entity-type (keyword entity-type)
                               :entity-data entity-data
                               :type-str type-str
                               :form-data form-data}
                              ctx))


(defbase try-apply-update
  "Atomic core of the update-apply flow: `sp/update-entity` +
   rename-slot side-effect (binding writes only) + post-write
   whole-fn type-check for binding-shaped updates. Returns a uniform
   `{:updated <id>}` on success — plus `:type-warnings [<diagnostic>
   …]` when the write landed but the owning fn now fails the
   aggregate type-check (error-tolerance Phase 2: recorded as a
   per-branch diagnostic, never rolled back) — or `{:error <msg>}`
   on write failure. Rename-slot failure is logged but never
   escalated (the binding row is still useful without the renamed
   view).

   This IS the `_apply` stage of the already-decomposed
   parse→validate→apply update flow (C2-C4): the write, its
   error-envelope, and the write-dependent rename-slot side effect
   are one coupled unit — the same class as the other `_apply` cores
   this file keeps."
  [entity-type id-uuid entity-data type-str form-data]
  (cr/record-effect! :db)
  (entities/apply-update-core {:entity-type (keyword entity-type)
                               :id-uuid id-uuid
                               :entity-data entity-data
                               :type-str type-str
                               :form-data form-data}
                              ctx))


;; === Sequence operations ===


;; `:_seq-append-fn-id-invalid?` / `:_seq-append-body-invalid?` are
;; now graph fn-defs — see fns.edn.


(defbase try-apply-seq-append
  "§3.3 core of sequence-append: materialise synthetic binding,
   compute next position, run pre-write validation, write the row.
   Returns `{:created <item-id> :position <int> :fn-id <fn-id>}` or
   `{:error <reason>}` (pre-write rejection)."
  [parsed seq-binding]
  (cr/record-effect! :db)
  (entities/apply-seq-append-core parsed seq-binding ctx))


(defbase try-apply-seq-update
  "§3.3 core of sequence-update: resolve payload, run pre-write
   validation, write the row. Returns `{:updated <item-id>}` or
   `{:error <reason>}`."
  [parsed item]
  (cr/record-effect! :db)
  (entities/apply-seq-update-core parsed item ctx))


(defbase try-apply-seq-move
  "§3.3 core of sequence-move: swap the item with its up/down
   neighbour through a free temp position. Returns
   `{:moved <item-id> :position <int>}` or `{:error <reason>}`."
  [parsed item]
  (cr/record-effect! :db)
  (entities/apply-seq-move-core parsed item ctx))


;; === Tighten fn-typed binding effects ===
;; The validation chain + success path is a `:cond` graph fn-def
;; (`:process-tighten-binding-effects` in fns.edn). These base-fns are
;; its primitives: one parse, four guard predicates, one apply.



;; `:_tighten-effects-invalid?` / `:_tighten-args-invalid?` are now
;; graph fn-defs — see fns.edn.


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:get-entity get-entity
   :delete-entity delete-entity
   :query-entities query-entities-fn
   :create-entity create-entity-fn
   :update-entity update-entity-fn
   :free-arg-slot-map free-arg-slot-map
   :service-blocking-free-args service-blocking-free-args
   :list-all-graph-entities list-all-graph-entities
   :graph-fn-defs-subtree   graph-fn-defs-subtree
   :strip-hidden-impl strip-hidden-impl
   :all-rich-types all-rich-types
   :api-rich-types api-rich-types
   :fn-names-with-tag fn-names-with-tag
   :query-ref-many-owners query-ref-many-owners
   :value-kinds value-kinds
   :try-apply-create try-apply-create
   :try-apply-update try-apply-update
   :try-apply-seq-append try-apply-seq-append
   :try-apply-seq-update try-apply-seq-update
   :try-apply-seq-move try-apply-seq-move})
