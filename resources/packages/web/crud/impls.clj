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
    [graphden.types.check :as types-check]
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
   the slot's declared type. Returns nil on success.

   Throws `clojure.lang.ExceptionInfo` with structured `:data` on
   mismatch — the executor surfaces the throw with the data intact so
   downstream consumers (editor red-ring rendering, MCP error
   handling) key off the canonical `:type` keyword. The throw is
   preserved rather than reshaped because that's the contract the
   create/update apply paths already depend on.

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
   as JSON, returns the raw string unchanged (matches the legacy
   `(try parse (catch _ raw))` swallow).

   Single-library boundary; the recursive re-keyword walk has shared-
   state-free recursion but isn't expressible as atomic graph
   primitives without graphden's `:fix` (not implemented yet). The
   walk's keyword-detection regex is the editor-side contract and
   shouldn't vary per user."
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
  [scope root-id]
  ;; Storage read (via `cached-or-load-graph` + an explicit `query-entities`
  ;; for namespaces). Without the record-effect! the runtime
  ;; `:runtime-effects` list returned by `/api/execute` would silently
  ;; drop `:db` for this call — the declared `:effects #{:db}` in fns.edn
  ;; says the effect IS there, the runtime audit must match.
  ;;
  ;; `scope :index` — only `{:fns :namespaces}`. Editor sidebar uses
  ;; this on initial load.
  ;;
  ;; `scope :subtree` + `root-id` — only the subgraph reachable from
  ;; `root-id`. ~50 KB typical for a per-fn editor view.
  ;;
  ;; Anything else (nil / :full) yields the unchanged full payload.
  (cr/record-effect! :db)
  (entities/list-all-graph-entities ctx scope root-id))


(defbase all-rich-types
  []
  ;; Same as `list-all-graph-entities`: `rich-types-with-type-rows` calls
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

;; `:_types-compatible-parsed` is now a graph fn-def — `:parse-json-body`
;; + two `:get`/`:json-to-type` pairs zipped into the `:expected` /
;; `:candidate` shape. The new atomic `:json-to-type` primitive wraps
;; `types-api/json->type`'s single-method recursive decoder.


;; `:_types-compatible-validation` is now a graph `:cond` fn-def
;; (`web/crud` fns.edn) — two nil-guards each yielding their static
;; rejection envelope.

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


(defbase rich-return-of-fn
  "Rich-types registry view of the named fn's `:return` field. Takes
   a fn-id, reads the fn-row to get the name, looks up the registry
   entry. nil for anonymous fns / not-yet-registered / unknown."
  [fn-id]
  (cr/record-effect! :db)
  (when fn-id
    (let [storage (request/require-storage ctx)
          fn-row  (sp/read-entity storage :fn fn-id)]
      (when (:name fn-row)
        (:return (registry/rich-type-of (keyword (:name fn-row))))))))


;; `:_types-compatible-apply` is now a graph fn-def — see fns.edn.
;; Pure composition over `:subtype?` + `:if`-wrapped
;; `:describe-type-mismatch` + envelope `:zipmap` / `:assoc`.


;; `:_types-candidates-parsed` is now a graph fn-def — `:parse-json-body`
;; + per-field reader chains (`:get` + `:json-to-type` for `:expected`,
;; `:get` + `:map :str-to-keyword` + `:if :some?` + `:to-set` for
;; `:allowed-effects`, `:get` + `:if :some?` + `:to-str` for
;; `:name-prefix`). New atomic primitive: `:to-set` (wraps
;; `clojure.core/set`).


;; `:_types-candidates-validation` is now a graph `:if` fn-def
;; (`web/crud` fns.edn) — one nil-guard yielding the rejection
;; envelope.

;; `:_types-candidates-apply` is now a graph fn-def — see fns.edn.
;; Pure composition over `:all-rich-types` snapshot + per-row
;; predicate chain (`:and (:not :type-row?) :subtype? :_effects-ok?
;; :_name-prefix-ok?`) + `:map → :filter :some? → :sort-by → :zipmap`.
;; Admins can vary the per-row reshape by reparenting
;; `:_types-candidates-row` (e.g. add `:description`, drop `:effects`).


;; `:_types-usages-parsed` is now a graph fn-def — `:parse-json-body` +
;; `:get :type-fn-id` + `:to-str` + `:parse-uuid`.  The shape preserves
;; the original `(when raw (try uuid-parse (catch _ nil)))` semantics
;; via `:to-str` of nil = "" and `:parse-uuid` of "" = nil.


;; `:_types-usages-validation` is now a graph `:if` fn-def
;; (`web/crud` fns.edn) — one nil-guard yielding the rejection
;; envelope.

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
   `[:refine base [:and …]]` constraint vectors. Graphden has no
   recursion primitive yet (`:fix` is on the recursion-design
   roadmap — see `docs/RECURSION.md`), so the walk has to live in
   Clojure with cycle-tracking shared state. Decomposing the 5
   non-recursive categories independently would split the response
   shape across Clojure + graph and double the round-trip cost
   (each category needs the cached-graph snapshot) without giving
   admins meaningful tuning surface for the recursive one.

   The sibling `parse-constraint` defbase is documented the same way —
   both wait on graph-level recursion."
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

;; `:_create-record-type-parsed` is now a graph fn-def — `:parse-json-body`
;; + per-field reader chains (`:get` + `:if :some?` + `:to-str` for `:name`,
;; `:get` + `:to-str` + `:parse-uuid` for `:ns-id`, plain `:get` for
;; `:description`, `:vec` of `:get` for `:fields`).


;; C19: `_create-record-type-validation` is now a `:cond` fn-def
;; (`web/crud` fns.edn). Per-field extractors + predicates + static
;; error consts replace the Clojure body that lived here. The apply
;; stage stays in Clojure because it's a single multi-row transaction
;; with rollback — skill §3 exception.


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


;; `:_create-list-type-parsed` is now a graph fn-def — same shape as
;; `:_create-record-type-parsed`, swapping `:fields` for the plain
;; `:element-type` field read.


;; C20: `_create-list-type-validation` is now a `:cond` fn-def (same
;; shape as C19's record-type validation).


;; Same body/rollback split for create-list-type — the rollback
;; defbase is shared with record-type (`:_apply-create-record-type-
;; rollback`) since the rollback logic is type-agnostic.
(defbase _apply-create-list-type-body
  [parsed journal]
  (cr/record-effect! :db)
  (entities/apply-create-list-type-body parsed journal ctx))


;; `:_update-record-type-parsed` is now a graph fn-def — adds
;; `:fn-id` (parse-uuid of `:id`) and `:has-description?`
;; (`:contains?` of the body) on top of the create-record-type
;; reader chain.


;; C21: `_update-record-type-validation` is now a `:cond` fn-def
;; (`web/crud` fns.edn) with three guards including a storage read.
;; The dynamic-error builder below cites the absent fn-id.


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

;; `:parse-fn-from-form` is now a graph fn-def — see fns.edn. Composes
;; per-field fragments (same pattern as the other parsers) plus two
;; new primitives — `:resolve-type-fn-id` (UUID-or-name resolution
;; over the `:return-type` / `:base-fn-id` / `:element-fn-id`
;; fields) and `:parse-constraint` (JSON + recursive keyword-restore
;; walk).


;; `:parse-ns-from-form` is now a graph fn-def — see fns.edn.
;; Pure composition: `:merge` of three per-field fragments
;; (`:if :contains?` → `:zipmap`-built single-key map, else `{}`).


;; `:parse-slot-from-form` is now a graph fn-def — see fns.edn.
;; Same per-field-fragment pattern.


;; `:parse-fn-slot-from-form` is now a graph fn-def — see fns.edn.
;; Same per-field-fragment pattern as `:parse-ns-from-form`.


;; `:parse-binding-from-form` is now a graph fn-def — see fns.edn.
;; Per-field-fragment pattern; 11 fragments.


;; `:parse-binding-list-item-from-form` is now a graph fn-def — see
;; fns.edn. Same per-field-fragment pattern.


;; === Action Handlers (context-aware) ===
;; `process-create-entity` / `process-update-entity` are graph fn-defs
;; (`web/crud` fns.edn) — an `:if` over `parse → validate`, branching
;; to a 400 or to the apply (write) stage. These base-fns are the
;; pipeline stages; `_rejected?` / `_rejection-response` are shared by
;; both handlers.

;; `:_create-parsed` is now a graph fn-def — see fns.edn. Composes
;; `:extract-entity-params` + body→form-data parse + per-type
;; `:cond` dispatch wrapped in `:try` (parse-error capture). The
;; per-type-parser fn-defs (`:parse-fn-from-form`, …) are referenced
;; by name; adding a new entity type means adding both a parser
;; fn-def AND a clause to the dispatch.


;; === C22 — :_create-validation is now a graph :cond fn-def in fns.edn,
;; composing one defbase per write-time guard. Each guard returns
;; `{:reason …}` on rejection or nil; the :cond short-circuits on the
;; first non-nil. Same shape as C19/C20/C21 with the parsed-arg
;; propagation pattern.

;; `:_create-entity-type-rej` / `:_create-form-data-rej` /
;; `:_create-parse-error-rej` are now graph fn-defs — see fns.edn.
;; Each is a pure `:if` over a `:nil?` / `:some?` predicate + `:zipmap`
;; reason envelope; no new base-fns.


;; `:_create-fn-slot-rename-rej` is now a graph fn-def — see fns.edn.
;; Pure composition over `:get-entity` (lazily-gated DB reads) +
;; `:and` predicates + `:pr-str` for the dynamic message.


;; `:_create-service-free-args-rej` is now a graph fn-def — see fns.edn.
;; Composes `:free-arg-slot-map` + `:keys` + `:not :empty?` + `:str`
;; envelope; pure graph, no new base-fns beyond `:free-arg-slot-map`
;; which lives in this file's defbase set.


;; `:_create-service-no-process-rej` is now a graph fn-def — see fns.edn.
;; Composes the new `:chain-has-process-effect?` primitive +
;; `:get-entity` (for the fn-name citation) + `:pr-str` + envelope.


;; `:_create-write-rej` and `:_create-binding-type-rej` are now graph
;; fn-defs — see fns.edn. Both compose over the new `:write-rej` /
;; `:type-check-binding-rej` atomic primitives (the same the update
;; chain uses).


;; `:_create-apply` is now a graph fn-def — see fns.edn. The §3.3
;; transactional unit (create + Phase-6c rename-slot + post-check +
;; rollback) lives inside the `:try-apply-create` primitive defined
;; below.


;; `:_update-parsed` is now a graph fn-def — see fns.edn. Mirrors
;; `:_create-parsed` structure plus id-str/id-uuid + a guarded
;; `:get-entity` pre-read. Reuses the create-side `:try`-wrapped
;; per-type dispatch (`:_create-entity-data-or-error`).


;; `:_update-validation` is now a graph fn-def — see fns.edn. It's a
;; `:cond` over 4 simple guards (3 reused from create-side rej-
;; builders) + tail `:coalesce` of :write-rej and (lazily)
;; :type-check-binding-rej against the merged post-write view.


;; `:_update-apply` is now a graph fn-def — see fns.edn. Composes
;; over `:try-apply-update` + `:invalidate-after-write` + `:notify-
;; after-write` for the success branch.


;; `:_rejected?` is now a graph fn-def — see fns.edn.


;; === Delete-entity primitives (C5 decomposition) ===
;; `:process-delete-entity` is now a `:cond` graph fn-def in fns.edn.
;; Four distinct rejection paths + the success path:
;;
;; - 400 invalid request (entity-type or id parse fail)
;; - 409 secret fn-def (admin path goes through /api/secrets/:fn-id)
;; - 409 fn in use (other fns reference it)
;; - 409 ns non-empty (still has sub-ns or fns)
;; - 200 delete + invalidate
;;
;; The two 409-with-dynamic-reason rejections (fn-in-use, ns-non-empty)
;; have a separate "reason-or-nil" base-fn fed into a predicate AND
;; into the error-builder base-fn — so the reason computes once and
;; is shared by both consumers.

;; `:_delete-parsed` is now a graph fn-def — see fns.edn. Composes
;; the new `:extract-entity-params` primitive + `:parse-uuid` +
;; `:zipmap` envelope.


;; `:_delete-request-invalid?` is now a graph fn-def — see fns.edn.


;; `:_delete-fn-is-secret?` is now a graph fn-def — see fns.edn. The
;; Clojure-side `entities/delete-fn-secret?` stays for direct callers
;; (currently none after Phase 4 decomp) until the next sweep removes
;; it.


;; `:_delete-fn-in-use-reason` is now a graph fn-def — see fns.edn.


;; `:_delete-fn-in-use?` is now a graph fn-def — see fns.edn.


;; `:_delete-err-fn-in-use` is now a graph fn-def — see fns.edn.
;; Reuses the new public `:html-error-response` builder.


;; `:_delete-ns-non-empty-reason` is now a graph fn-def — see fns.edn.


;; `:_delete-ns-non-empty?` is now a graph fn-def — see fns.edn.


;; `:_delete-err-ns-non-empty` is now a graph fn-def — see fns.edn.
;; Reuses the new public `:html-error-response` builder.


;; `:_delete-apply` is now a graph fn-def — see fns.edn. Composes
;; over `:get-entity` + `:delete-entity` + `:invalidate-after-write`
;; + `:notify-after-write` with the side effects sequenced via `:do`.


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
   Phase-6c rename-slot side-effect (binding writes only). Returns
   a uniform `{:updated <id>}` on success or `{:error <msg>}` on
   write failure. Rename-slot failure is logged but never escalated
   (matches legacy behaviour — the binding row is still useful
   without the renamed view).

   Unlike create, update has no post-write type-check + rollback —
   so this is §3.1 (single library boundary + one conditional side
   effect), not §3.3."
  [entity-type id-uuid entity-data type-str form-data]
  (cr/record-effect! :db)
  (entities/apply-update-core {:entity-type (keyword entity-type)
                               :id-uuid id-uuid
                               :entity-data entity-data
                               :type-str type-str
                               :form-data form-data}
                              ctx))


;; === Sequence operations ===

;; === Sequence-append primitives (C3 decomposition) ===
;; `:process-sequence-append` is now a `:cond` graph fn-def in fns.edn
;; that composes these atoms. Same shape as C2 (sequence-remove): one
;; parse, two upfront guard predicates, one read-only loader + a
;; not-found predicate over its result, and a single apply that runs
;; the side-effecting body. Lazy `:cond` means the synthetic-binding
;; materialization + the actual append only run when every guard
;; passes.
;;
;; - `_seq-append-parsed`         — `{:fn-id <uuid|nil> :body <map|nil>}`.
;; - `_seq-append-fn-id-invalid?` — guard #1, 400.
;; - `_seq-append-body-invalid?`  — guard #2, 400.
;; - `_seq-append-load-binding`   — read-only sequence-binding resolution.
;; - `_seq-append-no-seq-slot?`   — guard #3, 404.
;; - `_seq-append-apply`          — materialize-if-synthetic + write + 200
;;                                  (or data-dependent 400 from write-rej).

;; `:_seq-append-parsed` is now a graph fn-def — see fns.edn.


;; `:_seq-append-fn-id-invalid?` / `:_seq-append-body-invalid?` are
;; now graph fn-defs — see fns.edn.


(defbase _seq-append-load-binding
  [parsed]
  (cr/record-effect! :db)
  (entities/find-seq-append-binding parsed ctx))


;; `:_seq-append-no-seq-slot?` is now a graph fn-def — see fns.edn.


;; `:_seq-append-apply` is now a graph fn-def — see fns.edn. Composes
;; `:try-apply-seq-append` (§3.3 atomic write unit) + dispatch on the
;; `{:created}`/`{:error}` shape + `:invalidate-graph-cache` + 200 JSON.
(defbase try-apply-seq-append
  "§3.3 core of sequence-append: materialise synthetic binding,
   compute next position, run pre-write validation, write the row.
   Returns `{:created <item-id> :position <int> :fn-id <fn-id>}` or
   `{:error <reason>}` (pre-write rejection)."
  [parsed seq-binding]
  (cr/record-effect! :db)
  (entities/apply-seq-append-core parsed seq-binding ctx))


;; === Sequence-remove primitives (C2 decomposition) ===
;; `:process-sequence-remove` is now a `:cond` graph fn-def in fns.edn
;; that composes these atoms. Each clause is `[predicate 400/404-response]`;
;; the trailing `[:value true]` clause runs `:_seq-remove-apply`. `:cond`
;; is lazy, so the DB delete runs only when both guards pass.
;;
;; - `_seq-remove-parsed` — `{:item-id <uuid|nil>}` from the URI path.
;; - `_seq-remove-item-id-invalid?` — guard #1, 400.
;; - `_seq-remove-load-item`        — load the binding-list-item row.
;; - `_seq-remove-item-not-found?`  — guard #2, 404.
;; - `_seq-remove-apply`            — delete + invalidate, 200.

;; `:_seq-remove-parsed` is now a graph fn-def — see fns.edn.


;; `:_seq-remove-item-id-invalid?` is now a graph fn-def — see fns.edn.


(defbase _seq-remove-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-remove-item parsed ctx))


;; `:_seq-remove-item-not-found?` is now a graph fn-def — see fns.edn.


;; `:_seq-remove-apply` is now a graph fn-def — see fns.edn. Composes
;; `:delete-entity` (binding-list-item) → `:invalidate-after-write` →
;; 200 response via `:do`.


;; === Sequence-update primitives (C4 decomposition) ===
;; `:process-sequence-update` is now a `:cond` graph fn-def in fns.edn.
;; Same shape as C2 + C3 — parse / two upfront guards / read-only load
;; / not-found guard / apply (which carries the data-dependent write-rej
;; 400 internally).

;; `:_seq-update-parsed` is now a graph fn-def — see fns.edn.


;; `:_seq-update-item-id-invalid?` / `:_seq-update-body-invalid?` are
;; now graph fn-defs — see fns.edn.


(defbase _seq-update-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-update-item parsed ctx))


;; `:_seq-update-item-not-found?` is now a graph fn-def — see fns.edn.


;; `:_seq-update-apply` is now a graph fn-def — see fns.edn.
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

;; `:_tighten-parsed` is now a graph fn-def — see fns.edn. Composes
;; URL path-params/segment fallback + :parse-uuid + :parse-json-body
;; + per-field fragments for the :delta + final :zipmap shape.


;; `:_tighten-binding-id-invalid?` is now a graph fn-def — see fns.edn.


;; `:_tighten-effects-invalid?` / `:_tighten-args-invalid?` are now
;; graph fn-defs — see fns.edn.


;; `:_tighten-delta-empty?` is now a graph fn-def — see fns.edn.


;; `:_tighten-apply` is now a graph fn-def — see fns.edn.
(defbase try-apply-tighten
  "§3.3 core of tighten-fn-effects: narrows the fn-typed binding's
   effective type. Returns `{:status :reason :result}` from
   `tighten-fn-type-impl!` — graph dispatches on `:status`."
  [parsed]
  (cr/record-effect! :db)
  (entities/apply-tighten-core parsed ctx))


;; === Pure Functions ===
;; Genuine minimal primitives — kept inline; no heavy logic to extract.

;; `:parse-form-body` and `:parse-json-body` are now graph fn-defs —
;; see fns.edn. Pure composition over :get/:get-in + :and :some?
;; :str-contains? + :parse-query-string / :parse-json. Admins extend
;; the content-type allowlist by editing the predicate fragments.


(defbase str-to-uuid
  [string]
  (try
    (java.util.UUID/fromString string)
    (catch Exception _ nil)))


;; === Registry ===

(def impls
  {:get-entity get-entity
   :delete-entity delete-entity
   :free-arg-slot-map free-arg-slot-map
   :list-all-graph-entities list-all-graph-entities
   :all-rich-types all-rich-types
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
   :rich-return-of-fn rich-return-of-fn
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
