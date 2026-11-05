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
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.interface :as executor]
    [graphden.types.core :as types]))


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


(defbase _form-canonical-type
  [type-expr]
  (value-form/canonical-form-type type-expr))


(defbase _form-union-type?
  [type-expr]
  (types/union-type? type-expr))


(defbase _form-list-type?
  [type-expr]
  (types/list-type? type-expr))


(defbase _form-record-type?
  [type-expr]
  (types/record-type? type-expr))


(defbase _form-union-members
  [type-expr]
  (vec (types/union-members type-expr)))


(defbase _form-list-element
  [type-expr]
  (types/list-elem type-expr))


(defbase _form-refinement-base
  [type-expr]
  (types/refine-base type-expr))


(defbase _form-registry
  []
  (cr/record-effect! :db)
  (value-form/registry-pairs ctx))


(defbase _form-exact-target
  [registry type-expr]
  (value-form/exact-form-fn registry type-expr))


(defbase _form-leaf-target
  [registry type-expr]
  (value-form/pick-form-fn registry type-expr))


(defbase _form-run-target
  [target]
  (cr/record-effect! :db)
  (executor/execute-by-name ctx target {}))


(defbase _form-enum
  [type-expr]
  (value-form/enum-of type-expr))


(defbase _form-bounds
  [type-expr]
  (value-form/numeric-bounds type-expr))


(defbase _form-value-fits?
  [value type-expr]
  (value-form/value-fits? value type-expr))


(defbase _slot-type-provenance-facts
  "Read authorized canonical type tier facts in one shared batch. The graph
   chooses tier order and winner; visibility, marker restoration and inherited
   override resolution stay inside the canonical query."
  [parsed]
  (cr/record-effect! :db)
  (value-form/slot-type-provenance-facts (request/require-storage ctx) parsed))


;; The package loader pairs each base-fn declared in `fns.edn` with its
;; impl by looking up this `impls` map (keyword name -> impl fn).
(def impls
  {:_slot-effective-type-raw _slot-effective-type-raw
   :current-slot-value current-slot-value
   :_form-canonical-type {:impl _form-canonical-type :taint-propagate? true}
   :_form-union-type? {:impl _form-union-type? :taint-propagate? true}
   :_form-list-type? {:impl _form-list-type? :taint-propagate? true}
   :_form-record-type? {:impl _form-record-type? :taint-propagate? true}
   :_form-union-members {:impl _form-union-members :taint-propagate? true}
   :_form-list-element {:impl _form-list-element :taint-propagate? true}
   :_form-refinement-base {:impl _form-refinement-base :taint-propagate? true}
   :_form-registry {:impl _form-registry :taint-propagate? true}
   :_form-exact-target {:impl _form-exact-target :taint-propagate? true}
   :_form-leaf-target {:impl _form-leaf-target :taint-propagate? true}
   :_form-run-target {:impl _form-run-target :taint-propagate? true}
   :_form-enum {:impl _form-enum :taint-propagate? true}
   :_form-bounds {:impl _form-bounds :taint-propagate? true}
   :_form-value-fits? {:impl _form-value-fits? :taint-propagate? true}
   :_slot-type-provenance-facts _slot-type-provenance-facts})
