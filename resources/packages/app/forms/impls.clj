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


;; `:_value-form-parsed` and `:_value-form-validation` are now graph
;; fn-defs — see fns.edn. The parsed shape is :zipmap of 4 :parse-uuid
;; results off the JSON body; validation is :if over :or :some?/:and
;; :some? + :const rejection envelope.


;; `:_value-form-apply` is now a graph fn-def — see fns.edn. The
;; §3.3 type-aware form-renderer stays atomic; the outer slot-type
;; lookup + value pre-read + response shape are graph composition.
(defbase resolve-slot-effective-type
  "Resolve the effective type of the slot identified by `parsed`
   (binding-id or fn-id+slot-id). Returns the type keyword/vector,
   falling back to `:any` when the slot can't be located. §3.1
   library boundary."
  [parsed]
  (cr/record-effect! :db)
  (or (value-form/resolve-slot-effective-type
        (request/require-storage ctx) parsed)
      :any))


(defbase current-slot-value
  "Pre-read the slot's currently-bound value (used to seed the form).
   Returns whatever the storage has — `nil` is a valid 'no binding
   yet' state. §3.1 library boundary."
  [parsed]
  (cr/record-effect! :db)
  (value-form/current-value (request/require-storage ctx) parsed))


(defbase build-value-form
  "§3.3 type-aware form-renderer. Returns a hiccup vector describing
   the form control(s) for `eff-type`, seeded with `current-value`.
   The dispatch is recursive over `:union`/`:variant`/`:list`/
   `:record`/`[:refine base …]` etc., each branch carrying its own
   per-field shape — splitting across graph nodes would scatter the
   per-type renderer for no admin-extensibility win."
  [eff-type current-value]
  (cr/record-effect! :db)
  (value-form/build-form ctx (value-form/resolve-form eff-type)
                         "" nil current-value))


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
  {:resolve-slot-effective-type resolve-slot-effective-type
   :current-slot-value current-slot-value
   :build-value-form build-value-form
   :slot-type-provenance slot-type-provenance})
