(ns graphden.packages.web.crud.impls
  "Implementations for web/crud base functions.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.crud.entities :as entities]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.lookup :as fn-exec-lookup]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry]
    [graphden.schema.graph.schema :as graph-schema]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check.literals :as types-lit]
    [graphden.types.core :as types]))


;; === Context-aware Query Functions ===

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


(defbase chain-has-process-effect?
  "True iff `fn-id` itself or any ancestor (via `parent-ids`) declares
   the `:process` effect in its rich-type entry. The runtime uses this
   to gate `:service` registration: a fn-def whose own rich-type lookup
   missed (failed type-check on first sync) can still qualify when an
   ancestor declared the effect — the chain walk is the lenient layer.

   Single-library boundary over `entities/chain-has-process-effect?`
   — the walk has cycle-tracking shared state (`seen` set) that is the
   §3.3 invariant carve-out. False for nil fn-id."
  [fn-id]
  (cr/record-effect! :db)
  (boolean (when (some? fn-id)
             (entities/chain-has-process-effect?
               (request/require-storage ctx) fn-id))))


(defbase write-rej
  "Stage-2 write-time validator for create/update entity flows. Wraps
   `crud.validation/write-rej` which runs the generic cycle / MI /
   value-override / `:list-closed` rejection chain against the supplied
   entity row (in the update path, the merged post-write view). Returns
   `{:reason \"…\"}` on rejection or nil when the write is acceptable.

   Single-library boundary; the underlying validator composes multiple
   recursive checks against the graph (cycle detection through
   `binding.ref-fn-id`, MI compatibility against existing parent
   set, etc.) — a §3.3 algorithm with invariants. Admins can layer
   additional graph-level guards on either side of this call but
   shouldn't try to decompose the cycle-detector itself.

   `entity-type` arrives as a string (URL segment); the helper expects
   the canonical keyword form."
  [entity-type entity-data]
  (cr/record-effect! :db)
  (when entity-data
    (validation/write-rej (request/require-storage ctx)
                          (keyword entity-type)
                          entity-data)))


(defbase type-check-binding-rej
  "Stage-2 type-checker for `:binding` create/update flows. Wraps
   `type-check/type-check-binding-direct!` which runs the full graphden
   type system against the binding's `:value` / `:ref-fn-id` against
   the slot's declared type. Returns nil on success or `{:reason
   <message>}` on a type mismatch (it never throws for a mismatch) —
   the create/update Stage-2 `:cond` surfaces that `:reason` as a 400.

   `id` is the existing binding row's id on UPDATE (so the check sees
   the merged post-write state); pass nil on CREATE."
  [entity-data id]
  (cr/record-effect! :db)
  (when entity-data
    (tc/type-check-binding-direct! (request/require-storage ctx) entity-data id)))


(defbase query-param
  "Pull a named query-string parameter from a Ring request. Tolerates
   both reitit's enriched `:query-params` shape (string-or-keyword
   keyed) AND raw http-kit requests that carry only `:query-string`.
   Returns nil when the parameter is absent.

   Single-library boundary; the multi-source fallback is infra noise
   not user logic. Admins compose URL-handling primitives over the
   resulting string."
  [request param-name]
  (fn-exec/query-param request param-name))


(defbase extract-entity-params
  "Pull `{:type-str :id-str :entity-type}` out of a Ring request.
   Prefers reitit's `:path-params` (set by enrich-request); falls
   back to URI segment parsing for the http-kit passthrough path
   (`:branch-routing-wrap` and friends invoke handlers with the raw
   request map). `:entity-type` is the canonical keyword for the
   URL segment (`fn` → `:fn`, …) or nil when the segment doesn't
   match a known entity type.

   Single-library boundary over `request/extract-entity-params`.
   The dual-source merge (path-params + URI fallback) is infra
   compensation for the per-handler-routing variance, not user
   logic — admins can compose new entity-type→keyword mappings at
   the graph layer over the resulting `:type-str` field."
  [request]
  (request/extract-entity-params request))


(defbase resolve-type-fn-id
  "Resolve a type-row reference to its fn-id. `v` is either a raw UUID
   string (returned as-is after parse) or a fn name like
   `\"ring-response-shape\"` (resolved via `query-fn-by-name`). nil on
   blank input. Throws `:crud/unknown-type-ref` ExceptionInfo when a
   non-blank name doesn't match a fn-row.

   Used by the `parse-fn-from-form` form parser to coerce the
   `:return-type` / `:base-fn-id` / `:element-fn-id` form fields into
   the FK shape storage expects. Single-library boundary over
   `tc/resolve-type-fn-id` — the dual-shape (UUID OR name) is
   intrinsic to the editor's wire format, not user logic to vary."
  [v]
  (cr/record-effect! :db)
  (tc/resolve-type-fn-id (request/require-storage ctx) v))


(defbase parse-constraint
  "JSON-parse a constraint-shape string and recursively re-keywordise
   constraint-head identifiers (`:union`, `:variant`, `:and`, `:or`,
   `:>=`, etc.) plus type-name members (`:null`, `:int`, …) so the
   downstream type-checker sees Clojure keywords.

   Returns nil on blank input. On non-blank input that fails to parse
   as JSON, returns the raw string unchanged (parse failure is
   swallowed).

   Kept as ONE base-fn deliberately — NOT for lack of recursion
   (`:fix` shipped; the walk WOULD express as a graph): this is a
   self-contained wire-format parser (JSON string → typed constraint
   vector), the same carve-out class as `:pick-encoding`'s RFC
   negotiation. The keyword-detection regex is the editor-side wire
   contract and must not vary per user — splitting it across graph
   nodes would hand out exactly the tuning surface the contract
   forbids."
  [raw]
  (when-not (str/blank? raw)
    (let [parsed (try (json/parse-string raw)
                      (catch Exception _ raw))]
      (letfn [(re-kw
                [x]
                (cond
                  (and (string? x)
                       (or (str/starts-with? x ":")
                           (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" x)
                           (re-matches #"[!<>=]+" x)))
                  (keyword (str/replace-first x #"^:" ""))
                  (or (vector? x) (sequential? x)) (mapv re-kw x)
                  :else x))]
        (re-kw parsed)))))


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


(defbase all-rich-types
  []
  ;; Same as `list-all-graph-entities`: `rich-types-with-type-rows` calls
  ;; `cached-or-load-graph` which reads the `:fn` / `:slot` / `:fn-slot`
  ;; tables. Declared `:effects #{:db}` in fns.edn; mirror at runtime.
  (cr/record-effect! :db)
  (types-api/all-rich-types ctx))


(defbase api-rich-types
  [fn-name]
  ;; Wire-shaping layer over the same src helper `all-rich-types` wraps
  ;; (`rich-types-with-type-rows` — shared PRIVATE helper, not a hidden
  ;; base-fn→base-fn edge): strips the heavy per-entry fields from the
  ;; bulk payload, or returns one full entry when `fn-name` is set (the
  ;; `?fn=<name>` backfill). Kept as one base-fn deliberately: this is
  ;; the measured hot path of finding K (docs/PERF_BUDGETS.md) —
  ;; re-fetched after every mutation; a per-entry graph HOF strip over
  ;; ~4000 entries would re-add tens of ms per editor round-trip to a
  ;; path that was fought down 57%. The omitted-field list is the
  ;; editor wire contract, not per-user tuning surface.
  (cr/record-effect! :db)
  (types-api/api-rich-types ctx fn-name))


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


(defbase json-to-type-fn
  "Decode the JSON wire-shape of a type expression back to its
   Clojure AST. Inverse of cheshire's default Clojure → JSON
   encoding; refinement constraints decode base-aware so literal-
   string predicates (`[:not= \"\"]`) stay strings instead of being
   re-keywordized. Atomic wrapper over `types-api/json->type` —
   single recursive deserializer, no admin-extensible composition."
  [json]
  (types-api/json->type json))


(defbase to-set-fn
  "Coerce a sequence (or any seqable) into a Clojure set
   (`clojure.core/set`). Atomic library boundary. Useful when a
   downstream consumer uses the result as a containment predicate
   (`every? the-set xs`, `contains?`-style guards) — vector or list
   would silently mis-behave there."
  [coll]
  (set coll))


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


(defbase subtype?-fn
  "Atomic library boundary over `types/subtype?` — true iff
   `candidate` is a subtype of `expected` under graphden's type
   hierarchy. The subtype algorithm itself (refinement / list /
   union / record / fn-shape recursion) is §3.3 invariant logic
   that lives in `types/core`."
  [expected candidate]
  (types/subtype? candidate expected))


(defbase describe-type-mismatch-fn
  "Atomic library boundary over `types-api/describe-mismatch` —
   one-line human-readable explanation of why `candidate` ⊄ `expected`.
   Used by the editor's type-picker tooltip; admins can vary the
   wording downstream by composing on top, but the dispatch cases
   themselves are §3.3 algorithm."
  [expected candidate]
  (types-api/describe-mismatch expected candidate))


(defbase classify-literal
  "Atomic library boundary over `types.check/classify-literal` —
   infer the structural type expression of a literal Clojure value.
   nil for shapes the classifier doesn't recognise; callers fall
   back to `:any`."
  [value]
  (types-lit/classify-literal value))


(defbase diff-value-against-type
  "Atomic library boundary over `types.check/diff-value-against-type` —
   leaf-level disagreements between `value` and `expected` as
   `[{:path :expected :actual}, …]`. Empty vector when the value
   satisfies the type."
  [value expected]
  (types-lit/diff-value-against-type value expected))


(defbase closed-enum-of
  "Atomic library boundary over `types.check/closed-enum-of` —
   returns `{:base :members}` when `expected` resolves to a closed-
   enum refinement (`[:refine base [:in [members]]]`), nil otherwise.
   Members are sorted + colon-prefixed for `:keyword`-based enums."
  [expected]
  (types-lit/closed-enum-of expected))


(defbase fn-type-bound-effects
  "Atomic library boundary over `types.check/fn-type-bound-effects` —
   returns a vec of effect-name strings (without the leading colon)
   when `expected` is a `[:fn args ret eff]` with concrete eff, nil
   otherwise (no constraint to surface)."
  [expected]
  (types-lit/fn-type-bound-effects expected))


(defbase rich-type-of-name
  "One registry lookup — the full rich-type entry for the named fn
   (nil-safe: nil / unknown names → nil). `:rich-return-of-fn`
   (fns.edn) composes this with `:get-entity` + `:get` to go
   fn-id → row → name → entry → `:return`."
  [fn-name]
  (when fn-name
    (registry/rich-type-of (keyword fn-name))))


(defbase rule-owner-of-name
  "Atomic library boundary over `registry/rule-owner-of` — the name of
   the base-fn whose `:return-type-rule` computed the named fn's
   return type; nil when the fn is unknown, is itself a base-fn, or
   its root ancestor carries no rule. The walk itself lives next to
   `registry/root-base-fn-name` (single source of truth); the layout
   strip-facts pass calls the same fn."
  [fn-name]
  (registry/rule-owner-of fn-name))


(defbase type-name-kinds
  "Sorted `{:name :kind}` rows for the editor's type-name datalist —
   named type-rows classified via `compute-fn-role` plus the bare
   primitives. Single library call into `types-api/type-name-kinds`."
  []
  (cr/record-effect! :db)
  (types-api/type-name-kinds ctx))


(defbase compatible-type-names
  "`type-name-kinds` rows filtered to the names that can legally
   narrow `expected` — one alias-aware `subtype?` sweep server-side.
   Single library call into `types-api/compatible-type-names`."
  [expected]
  (cr/record-effect! :db)
  (types-api/compatible-type-names ctx expected))


(defbase _types-usages-apply
  "Stage 3 of types-usages — walk the graph for every reference to the
   target type-row. Returns `{:ok :type-fn-id :type-name :count :usages}`
   where `:usages` is a vec of `{:fn-id :fn-name :role :kind …}` entries
   spanning 6 categories: `:base-of`, `:element-of`, `:return-of`,
   `:union-branch` / `:variant-branch` / `:fn-type-arg-or-return`
   (constraint uses), `:slot-of`, `:binding-of`.

   §3.3 algorithm — the constraint-uses category requires
   `constraint-contains-type-ref?` which is a RECURSIVE walk into
   nested `[:union …]` / `[:variant …]` / `[:fn args ret]` /
   `[:refine base [:and …]]` constraint vectors — a cycle-tracking
   shared-state walk, the §3.3 carve-out proper (unchanged by `:fix`
   shipping: `:fix` gives structural recursion, not shared
   cycle-state across mutually-recursive walks). Decomposing the 5
   non-recursive categories independently would split the response
   shape across Clojure + graph and double the round-trip cost
   (each category needs the cached-graph snapshot) without giving
   admins meaningful tuning surface for the recursive one.

   The sibling `parse-constraint` defbase stays one unit for a
   different reason — it is a self-contained wire-format parse
   contract (see its docstring)."
  [parsed]
  (cr/record-effect! :db)
  (types-api/apply-types-usages parsed ctx))


;; === Type-row compound handlers ===
;; `process-create-record-type` / `process-create-list-type` /
;; `process-update-record-type` are `:if` graph fn-defs (`web/crud`
;; fns.edn) — an `:if` over the validation result, branching to the
;; `{:ok false :error}` rejection or to the transactional apply.
;; These base-fns are the parse / validate / apply stages; `_rejected?`
;; (below) is shared with the entity create/update handlers.



;; Body / rollback split for create-record-type — body and rollback
;; share the same journal atom via wrap-time capture at the `:try`
;; call site (see `:_create-record-type-apply` in fns.edn). The body
;; throws on storage failure; rollback derefs the journal and
;; replays in reverse.
(defbase _apply-create-record-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-create-record-type-body parsed journal ctx))


(defbase _apply-create-record-type-rollback
  [journal exception]
  (cr/record-effect! :db)
  (entities/apply-create-rollback journal exception ctx))


;; Same body/rollback split for create-list-type — the rollback
;; defbase is shared with record-type (`:_apply-create-record-type-
;; rollback`) since the rollback logic is type-agnostic.
(defbase _apply-create-list-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-create-list-type-body parsed journal ctx))


;; `:_update-record-type-apply` is a graph `:try` fn-def in fns.edn
;; composing `:_apply-update-record-type-body` + `-rollback` over a
;; shared `:atom` journal. Each side is one thin defbase below.

(defbase _apply-update-record-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-update-record-type-body parsed journal ctx))


(defbase _apply-update-record-type-rollback
  [journal exception]
  (cr/record-effect! :db)
  (entities/apply-update-record-type-rollback journal exception ctx))


;; === Form data parsing base functions ===



;; === Action Handlers (context-aware) ===
;; `process-create-entity` / `process-update-entity` are graph fn-defs
;; (`web/crud` fns.edn) — an `:if` over `parse → validate`, branching
;; to a 400 or to the apply (write) stage. These base-fns are the
;; pipeline stages; `_rejected?` / `_rejection-response` are shared by
;; both handlers.



;; `:_create-write-rej` and `:_create-binding-type-rej` are now graph
;; fn-defs — see fns.edn. Both compose over the new `:write-rej` /
;; `:type-check-binding-rej` atomic primitives (the same the update
;; chain uses).



(defbase try-apply-create
  "§3.3 atomic core of the create-apply flow: capability gate +
   `sp/create-entity` + Phase-6c rename-slot side-effect + post-
   create whole-fn type-check + on-failure rollback. Returns a
   uniform `{:created <id>}` on success or `{:error <human-msg>}`
   on any failure (cap-gate, write-time error, post-check rejection).

   The §3.3 invariant is the type-check ↔ rollback pair: both must
   see the SAME just-created row id. Splitting them across graph
   nodes would risk a stale-row read if `:graph-cache` invalidation
   races between the steps. Keeping the pair inside one defbase
   keeps that contract verifiable from one place.

   The outer graph composition runs the cheap rejection clauses
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
   rename-slot side-effect (binding writes only). Returns
   a uniform `{:updated <id>}` on success or `{:error <msg>}` on
   write failure. Rename-slot failure is logged but never escalated
   (the binding row is still useful without the renamed view).

   This IS the `_apply` stage of the already-decomposed
   parse→validate→apply update flow (C2-C4): the write, its
   error-envelope, and the write-dependent rename-slot side effect
   are one coupled unit — the same class as the other `_apply` cores
   this file keeps. Unlike create it has no post-write type-check +
   rollback journal, so no `:try`-journal graph shape applies."
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


(defbase _seq-append-load-binding
  [parsed]
  (cr/record-effect! :db)
  (entities/find-seq-append-binding parsed ctx))


(defbase try-apply-seq-append
  "§3.3 core of sequence-append: materialise synthetic binding,
   compute next position, run pre-write validation, write the row.
   Returns `{:created <item-id> :position <int> :fn-id <fn-id>}` or
   `{:error <reason>}` (pre-write rejection)."
  [parsed seq-binding]
  (cr/record-effect! :db)
  (entities/apply-seq-append-core parsed seq-binding ctx))


(defbase _seq-remove-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-remove-item parsed ctx))


;; `:_seq-update-item-id-invalid?` / `:_seq-update-body-invalid?` are
;; now graph fn-defs — see fns.edn.


(defbase _seq-update-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-update-item parsed ctx))


(defbase try-apply-seq-update
  "§3.3 core of sequence-update: resolve payload, run pre-write
   validation, write the row. Returns `{:updated <item-id>}` or
   `{:error <reason>}`."
  [parsed item]
  (cr/record-effect! :db)
  (entities/apply-seq-update-core parsed item ctx))


;; === Tighten fn-typed binding effects ===
;; The validation chain + success path is a `:cond` graph fn-def
;; (`:process-tighten-binding-effects` in fns.edn). These base-fns are
;; its primitives: one parse, four guard predicates, one apply.



;; `:_tighten-effects-invalid?` / `:_tighten-args-invalid?` are now
;; graph fn-defs — see fns.edn.



(defbase try-apply-tighten
  "§3.3 core of tighten-fn-effects: narrows the fn-typed binding's
   effective type. Returns `{:status :reason :result}` from
   `tighten-fn-type-impl!` — graph dispatches on `:status`."
  [parsed]
  (cr/record-effect! :db)
  (entities/apply-tighten-core parsed ctx))


;; === Pure Functions ===
;; Genuine minimal primitives — kept inline; no heavy logic to extract.


(defbase str-to-uuid
  [string]
  (try
    (java.util.UUID/fromString string)
    (catch Exception _ nil)))


;; === Registry ===

(def impls
  {:get-entity get-entity
   :delete-entity delete-entity
   :query-entities query-entities-fn
   :create-entity create-entity-fn
   :update-entity update-entity-fn
   :free-arg-slot-map free-arg-slot-map
   :service-blocking-free-args service-blocking-free-args
   :list-all-graph-entities list-all-graph-entities
   :all-rich-types all-rich-types
   :api-rich-types api-rich-types
   :fn-names-with-tag fn-names-with-tag
   :query-ref-many-owners query-ref-many-owners
   :json-to-type json-to-type-fn
   :to-set to-set-fn
   :value-kinds value-kinds
   :subtype? subtype?-fn
   :describe-type-mismatch describe-type-mismatch-fn
   :classify-literal classify-literal
   :diff-value-against-type diff-value-against-type
   :closed-enum-of closed-enum-of
   :fn-type-bound-effects fn-type-bound-effects
   :rich-type-of-name rich-type-of-name
   :rule-owner-of-name rule-owner-of-name
   :type-name-kinds type-name-kinds
   :compatible-type-names compatible-type-names
   :_types-usages-apply _types-usages-apply
   :_apply-create-record-type-body _apply-create-record-type-body
   :_apply-create-record-type-rollback _apply-create-record-type-rollback
   :_apply-create-list-type-body _apply-create-list-type-body
   :_apply-update-record-type-body _apply-update-record-type-body
   :_apply-update-record-type-rollback _apply-update-record-type-rollback
   :chain-has-process-effect? chain-has-process-effect?
   :write-rej write-rej
   :type-check-binding-rej type-check-binding-rej
   :extract-entity-params extract-entity-params
   :query-param query-param
   :resolve-type-fn-id resolve-type-fn-id
   :parse-constraint parse-constraint
   :try-apply-create try-apply-create
   :try-apply-update try-apply-update
   :_seq-append-load-binding _seq-append-load-binding
   :try-apply-seq-append try-apply-seq-append
   :_seq-remove-load-item _seq-remove-load-item
   :_seq-update-load-item _seq-update-load-item
   :try-apply-seq-update try-apply-seq-update
   :try-apply-tighten try-apply-tighten
   :str-to-uuid str-to-uuid})
