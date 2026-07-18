(ns graphden.packages.app.forms.impls
  "Implementations for the app/forms base functions — the
   `/api/value-form` parse / validate / apply stages.

   Each `defbase` is a thin shim delegating to
   `graphden.crud.value-form`; the implicit `ctx` symbol is passed
   through where storage / the executor registry are needed."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.value-form :as value-form]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _slot-effective-type-raw
  "Resolve the effective type of the slot identified by `parsed`
   (binding-id or fn-id+slot-id). nil when the slot can't be located
   — the public `:resolve-slot-effective-type` fn-def coalesces the
   nil to `:any` in the graph. §3.1 library boundary."
  [parsed]
  (cr/record-effect! :db)
  (value-form/resolve-slot-effective-type
    (request/require-storage ctx) parsed))


(defbase current-slot-value
  "Pre-read the slot's currently-bound value (used to seed the form).
   Returns whatever the storage has — `nil` is a valid 'no binding
   yet' state. §3.1 library boundary."
  [parsed]
  (cr/record-effect! :db)
  (value-form/current-value (request/require-storage ctx) parsed))


(defbase resolve-form-fn
  "Classify a structural type into a form-descriptor tree
   (`value-form/resolve-form`) — pure, one interface call. The
   type→descriptor classification is the seam a custom form pipeline
   composes against."
  [type-expr]
  (value-form/resolve-form type-expr))


(defbase build-form-fn
  "§3.3 type-aware form-renderer over a `resolve-form` descriptor.
   Returns a hiccup vector for the form control(s), seeded with
   `current-value`. The dispatch is recursive over the descriptor
   tree (`:record`/`:union`/`:list`/`:leaf`), each branch carrying
   its own per-field shape — splitting across graph nodes would
   scatter the per-type renderer for no admin-extensibility win."
  [form current-value]
  (cr/record-effect! :db)
  (value-form/build-form ctx form "" nil current-value))


(defbase slot-type-provenance
  "Atomic library boundary over `value-form/slot-type-provenance` —
   the 4-tier resolution chain + inheritance chain for the type at a
   `(fn-id, slot-id, binding-id)` edit site. Mirrors the editor's JS
   `slotTypeProvenance` so the mismatch-explainer + dedicated provenance
   popover can show users WHERE the expected type came from without
   recomputing locally. nil for list-item rows / unresolved sites."
  [parsed]
  (cr/record-effect! :db)
  (value-form/slot-type-provenance (request/require-storage ctx) parsed))


;; The package loader pairs each base-fn declared in `fns.edn` with its
;; impl by looking up this `impls` map (keyword name -> impl fn).
(def impls
  {:_slot-effective-type-raw _slot-effective-type-raw
   :current-slot-value current-slot-value
   :resolve-form resolve-form-fn
   :build-form build-form-fn
   :slot-type-provenance slot-type-provenance})
