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
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.fn-execution.lookup :as fn-exec-lookup]
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.export :as export]
    [graphden.schema.graph.schema :as graph-schema]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.resolution :as resolution]))


(defbase get-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/get-entity entity-type id ctx))


(defbase delete-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/delete-entity entity-type id ctx))


(defbase revive-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/revive-entity entity-type id ctx))


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


(defbase with-graph-load-memo
  "Run one graph body against a request-local, epoch-aware branch-load memo.
   This shares the branch read across per-card lookups. A new memo ends with
   this call; an enclosing scope is reused."
  [body]
  (resolution/call-with-graph-load-memo body))


(defbase _free-arg-facts
  "One canonical public slot surface and its safe capture-target names."
  [fn-id]
  (cr/record-effect! :db)
  (fn-exec-lookup/free-arg-facts ctx fn-id))


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


(defbase fn-unread-bindings
  [fn-id]
  (cr/record-effect! :db)
  (if (some? fn-id) (cr/unread-bindings ctx fn-id) []))


(defbase graph-fn-defs-subtree
  [root-id]
  (cr/record-effect! :db)
  (if (some? root-id)
    (export/export-subtree (request/require-storage ctx) root-id
                           entities/apply-view-impl-filter)
    []))


;; The editor's graph reads — one projection each over the shared
;; graph-cache (`crud.entities.list`). Storage reads (the cache plus an
;; explicit `query-entities` for namespaces): each records `:db` so the
;; runtime effect audit matches the declared `:effects #{:db}`. Which one
;; a request wants is graph composition (`:all-entities-handler`).

(defbase graph-tree-facts
  []
  (cr/record-effect! :db)
  (entity-list/graph-tree-facts ctx))


(defbase project-graph-cohort
  [selector namespaces? capped?]
  (cr/record-effect! :db)
  (entity-list/project-graph-cohort ctx selector namespaces? capped?))


(defbase graph-index-facts
  []
  (cr/record-effect! :db)
  (entity-list/graph-index-facts ctx))


(defbase graph-subtree
  [root-id]
  (cr/record-effect! :db)
  (entities/graph-subtree ctx root-id))


(defbase graph-full
  []
  (cr/record-effect! :db)
  (entities/graph-full ctx))


(defbase strip-hidden-impl
  [graph]
  ;; Pure pass-through over the tenancy view-impl seam — identity until the
  ;; addon installs a filter (single-tenant sees everything). No :db of its
  ;; own; the installed filter reads grants in the trusted platform ctx.
  (entities/apply-view-impl-filter graph))


(defbase fn-impl-visible?
  [fn-id]
  (cr/record-effect! :db)
  (boolean (some-> (and fn-id (sp/read-entity (request/require-storage ctx) :fn fn-id))
                   entities/impl-visible?)))


(defbase all-rich-types
  []
  ;; Same as the `:graph-*` reads: `rich-types-with-type-rows` calls
  ;; `cached-or-load-graph` which reads the `:fn` / `:slot` / `:fn-slot`
  ;; tables. Declared `:effects #{:db}` in fns.edn; mirror at runtime.
  (cr/record-effect! :db)
  (types-api/all-rich-types ctx))


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
;; `rejected?` (below) is shared with every other `:if` handler.


(defbase _apply-create-atomic
  "The guarded create, rename, type-check and rollback share one writer."
  [parsed]
  (cr/record-effect! :db)
  (entities/apply-create-core parsed ctx))


(defbase _apply-update-atomic
  "The guarded update, rename, type-check and rollback share one writer."
  [parsed]
  (cr/record-effect! :db)
  (entities/apply-update-core parsed ctx))


(defbase _apply-seq-append-atomic
  "Refresh the binding and apply or reject under the same writer."
  [parsed seq-binding]
  (cr/record-effect! :db)
  (entities/apply-seq-append-core parsed seq-binding ctx))


(defbase _apply-seq-update-atomic
  "Refresh the item and apply or reject under the same writer."
  [parsed item]
  (cr/record-effect! :db)
  (entities/apply-seq-update-core parsed item ctx))


(defbase _apply-seq-move-atomic
  "Refresh the item and swap positions under the same writer."
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
   :revive-entity revive-entity
   :query-entities {:impl query-entities-fn :taint-propagate? true}
   :create-entity {:impl create-entity-fn :taint-propagate? true}
   :update-entity {:impl update-entity-fn :taint-propagate? true}
   :free-arg-slot-map free-arg-slot-map
   :with-graph-load-memo {:impl with-graph-load-memo :taint-propagate? true}
   :_free-arg-facts _free-arg-facts
   :service-blocking-free-args service-blocking-free-args
   :_graph-tree-facts graph-tree-facts
   :_project-graph-cohort project-graph-cohort
   :_graph-index-facts graph-index-facts
   :graph-subtree graph-subtree
   :graph-full graph-full
   :graph-fn-defs-subtree   graph-fn-defs-subtree
   :fn-unread-bindings      fn-unread-bindings
   :strip-hidden-impl {:impl strip-hidden-impl :taint-propagate? true}
   :fn-impl-visible? fn-impl-visible?
   :all-rich-types all-rich-types
   :fn-names-with-tag fn-names-with-tag
   :query-ref-many-owners query-ref-many-owners
   :value-kinds value-kinds
   :_apply-create-atomic {:impl _apply-create-atomic :taint-propagate? true}
   :_apply-update-atomic {:impl _apply-update-atomic :taint-propagate? true}
   :_apply-seq-append-atomic {:impl _apply-seq-append-atomic :taint-propagate? true}
   :_apply-seq-update-atomic {:impl _apply-seq-update-atomic :taint-propagate? true}
   :_apply-seq-move-atomic {:impl _apply-seq-move-atomic :taint-propagate? true}})
