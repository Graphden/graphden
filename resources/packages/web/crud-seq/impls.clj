(ns graphden.packages.web.crud-seq.impls
  "Implementations for the `web.crud` sequence-binding ops and the tighten chain.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.crud.package-guard :as package-guard]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _seq-append-load-binding
  [parsed]
  (cr/record-effect! :db)
  (entities/find-seq-append-binding parsed ctx))


(defbase _seq-remove-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-remove-item parsed ctx))


(defbase pkg-delete-guard-reason
  "Reason string when deleting `row` of `entity-type` would damage a
   package-synced fn (the fn row itself, or a binding-family row it
   owns); nil when the delete is fine. §3.1 thin wrapper over
   `crud.package-guard/delete-rejection`."
  [entity-type row]
  (cr/record-effect! :db)
  (package-guard/delete-rejection (request/require-storage ctx)
                                  (keyword entity-type) row))


;; `:_seq-update-item-id-invalid?` / `:_seq-update-body-invalid?` are
;; now graph fn-defs — see fns.edn.


(defbase _seq-update-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-update-item parsed ctx))


(defbase _seq-move-load-item
  [parsed]
  (cr/record-effect! :db)
  (entities/load-seq-update-item parsed ctx))


(defbase try-apply-tighten
  "§3.3 core of tighten-fn-effects: narrows the fn-typed binding's
   effective type. Returns `{:status :reason :result}` from
   `tighten-fn-type-impl!` — graph dispatches on `:status`."
  [parsed]
  (cr/record-effect! :db)
  (entities/apply-tighten-core parsed ctx))


;; === Pure Functions ===
;; Genuine minimal primitives — kept inline; no heavy logic to extract.


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:_seq-append-load-binding _seq-append-load-binding
   :_seq-remove-load-item _seq-remove-load-item
   :pkg-delete-guard-reason pkg-delete-guard-reason
   :_seq-update-load-item _seq-update-load-item
   :_seq-move-load-item _seq-move-load-item
   :try-apply-tighten try-apply-tighten})
