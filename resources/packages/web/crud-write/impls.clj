(ns graphden.packages.web.crud-write.impls
  "Implementations for the `web.crud` entity create / update pipeline.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.validation :as validation]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


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
  "On-demand single-binding type validator. Wraps
   `type-check/type-check-binding-direct!` which runs the full graphden
   type system against the binding's `:value` / `:ref-fn-id` against
   the slot's declared type. Returns nil on success or `{:reason
   <message>}` on a type mismatch (it never throws for a mismatch).

   Error-tolerance Phase 2 unhooked this from the create/update
   Stage-2 `:cond` chains — binding writes proceed and the post-write
   aggregate check inside the `try-apply-*` cores records failures as
   per-branch diagnostics instead. Compose this where a verdict
   WITHOUT a write is wanted (pre-flight validation); it records
   nothing in the diagnostics store.

   `id` is an existing binding row's id (the check then sees the
   merged post-write state); nil to validate standalone entity-data."
  [entity-data id]
  (cr/record-effect! :db)
  (when entity-data
    (tc/type-check-binding-direct! (request/require-storage ctx) entity-data id)))


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:chain-has-process-effect? chain-has-process-effect?
   :write-rej write-rej
   :type-check-binding-rej type-check-binding-rej})
