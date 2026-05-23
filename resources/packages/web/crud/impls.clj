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
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.schema.graph.schema :as graph-schema]))


;; === Context-aware Query Functions ===

(defbase list-entities
  [entity-type where]
  (cr/record-effect! :db)
  (entities/list-entities entity-type where ctx))


(defbase get-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/get-entity entity-type id ctx))


(defbase create-entity
  [entity-type data]
  (cr/record-effect! :db)
  (entities/create-entity entity-type data ctx))


(defbase update-entity
  [entity-type id data]
  (cr/record-effect! :db)
  (entities/update-entity entity-type id data ctx))


(defbase delete-entity
  [entity-type id]
  (cr/record-effect! :db)
  (entities/delete-entity entity-type id ctx))


(defbase list-all-graph-entities
  []
  (entities/list-all-graph-entities ctx))


(defbase all-rich-types
  []
  (types-api/all-rich-types ctx))


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

(defbase _types-compatible-parsed
  [request]
  (types-api/parse-types-compatible-request request))


(defbase _types-compatible-validation
  [parsed]
  (types-api/validate-types-compatible parsed))


(defbase _types-compatible-apply
  [parsed]
  (types-api/apply-types-compatible parsed))


(defbase _types-candidates-parsed
  [request]
  (types-api/parse-types-candidates-request request))


(defbase _types-candidates-validation
  [parsed]
  (types-api/validate-types-candidates parsed))


(defbase _types-candidates-apply
  [parsed]
  (types-api/apply-types-candidates parsed ctx))


(defbase _types-usages-parsed
  [request]
  (types-api/parse-types-usages-request request))


(defbase _types-usages-validation
  [parsed]
  (types-api/validate-types-usages parsed))


(defbase _types-usages-apply
  [parsed]
  (types-api/apply-types-usages parsed ctx))


;; === Type-row compound handlers ===
;; `process-create-record-type` / `process-create-list-type` /
;; `process-update-record-type` are `:if` graph fn-defs (`web/crud`
;; fns.edn) — an `:if` over the validation result, branching to the
;; `{:ok false :error}` rejection or to the transactional apply.
;; These base-fns are the parse / validate / apply stages; `_rejected?`
;; (below) is shared with the entity create/update handlers.

(defbase _create-record-type-parsed
  [request]
  (entities/parse-create-record-type request))


(defbase _create-record-type-validation
  [parsed]
  (entities/validate-create-record-type parsed))


(defbase _create-record-type-apply
  [parsed]
  (entities/apply-create-record-type parsed ctx))


(defbase _create-list-type-parsed
  [request]
  (entities/parse-create-list-type request))


(defbase _create-list-type-validation
  [parsed]
  (entities/validate-create-list-type parsed))


(defbase _create-list-type-apply
  [parsed]
  (entities/apply-create-list-type parsed ctx))


(defbase _update-record-type-parsed
  [request]
  (entities/parse-update-record-type request))


(defbase _update-record-type-validation
  [parsed]
  (entities/validate-update-record-type parsed ctx))


(defbase _update-record-type-apply
  [parsed]
  (entities/apply-update-record-type parsed ctx))


;; === Form data parsing base functions ===

(defbase parse-fn-from-form
  [form-data]
  (entities/parse-fn-from-form form-data ctx))


(defbase parse-ns-from-form
  [form-data]
  (entities/parse-ns-from-form form-data))


(defbase parse-slot-from-form
  "Form-data → slot-row fields. `:type-fn-id` is the slot's declared
   type (a fn-id pointing at a primitive / refinement / record). All
   slot fields except `:id` (auto-generated) and `:name` are optional
   on update."
  [form-data]
  (entities/parse-slot-from-form form-data))


(defbase parse-fn-slot-from-form
  "Form-data → fn-slot junction row fields. Both refs are required on
   create; `:position` is optional (defaults to 0)."
  [form-data]
  (entities/parse-fn-slot-from-form form-data))


(defbase parse-binding-from-form
  "Form-data → binding-row fields. Empty-as-clear convention applies
   to every nullable slot so an editor can drop an override by sending
   an empty form value."
  [form-data]
  (entities/parse-binding-from-form form-data))


(defbase parse-binding-list-item-from-form
  "Form-data → binding-list-item row fields. `:binding-id` and
   `:position` are required for create; the value is either a literal
   `:value` (JSON-decoded) or a `:ref-fn-id`, but not both."
  [form-data]
  (entities/parse-binding-list-item-from-form form-data))


;; === Action Handlers (context-aware) ===
;; `process-create-entity` / `process-update-entity` are graph fn-defs
;; (`web/crud` fns.edn) — an `:if` over `parse → validate`, branching
;; to a 400 or to the apply (write) stage. These base-fns are the
;; pipeline stages; `_rejected?` / `_rejection-response` are shared by
;; both handlers.

(defbase _create-parsed
  [request]
  (entities/parse-create-request request ctx))


(defbase _create-validation
  [parsed]
  (entities/validate-create parsed ctx))


(defbase _create-apply
  [parsed]
  (entities/apply-create parsed ctx))


(defbase _update-parsed
  [request]
  (entities/parse-update-request request ctx))


(defbase _update-validation
  [parsed]
  (entities/validate-update parsed ctx))


(defbase _update-apply
  [parsed]
  (entities/apply-update parsed ctx))


(defbase _rejected?
  "True when a validate-* stage produced a rejection."
  [validation]
  (some? validation))


(defbase _rejection-response
  "Render a validate-* rejection as a 400 partial Ring response."
  [validation]
  {:status 400
   :body (str "<p class=\"error\">" (:reason validation) "</p>")})


(defbase process-delete-entity
  [request]
  (entities/process-delete-entity request ctx))


;; === Sequence operations ===

(defbase process-sequence-append
  "POST /api/sequence/append/:fn-id
   Body: {\"ref\"|\"ref-name\"|\"value\": …}
   Appends one item to the sequence binding of fn :fn-id. Creates an
   empty `:list-append true` binding if the fn doesn't yet have one."
  [request]
  (entities/process-sequence-append request ctx))


(defbase process-sequence-remove
  "DELETE /api/sequence/item/:item-id
   Removes one binding-list-item. Positions of remaining items are
   left as-is (no compaction); editor reads items sorted by position
   so a hole is harmless."
  [request]
  (entities/process-sequence-remove request ctx))


(defbase process-sequence-update
  "PUT /api/sequence/item/:item-id
   Body: {\"ref\"|\"ref-name\"|\"value\": …}
   Replaces the value/ref of one existing binding-list-item — the
   in-place edit counterpart of append/remove."
  [request]
  (entities/process-sequence-update request ctx))


;; === Tighten fn-typed binding effects ===
;; The validation chain + success path is a `:cond` graph fn-def
;; (`:process-tighten-binding-effects` in fns.edn). These base-fns are
;; its primitives: one parse, four guard predicates, one apply.

(defbase _tighten-parsed
  [request]
  (entities/parse-tighten-request request))


(defbase _tighten-binding-id-invalid?
  [parsed]
  (nil? (:binding-id parsed)))


(defbase _tighten-effects-invalid?
  [parsed]
  (let [e (:effects-val parsed)]
    (and (some? e) (not (sequential? e)))))


(defbase _tighten-args-invalid?
  [parsed]
  (let [a (:args-val parsed)]
    (and (some? a) (not (map? a)))))


(defbase _tighten-delta-empty?
  [parsed]
  (empty? (:delta parsed)))


(defbase _tighten-apply
  [parsed]
  (entities/apply-tighten parsed ctx))


;; === Pure Functions ===
;; Genuine minimal primitives — kept inline; no heavy logic to extract.

(defbase parse-form-body
  [request]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (if (and body (str/includes? content-type "application/x-www-form-urlencoded"))
      (or (request/parse-query-string body) {})
      {})))


(defbase parse-json-body
  [request]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (when (and body (str/includes? content-type "application/json"))
      (json/parse-string body true))))


(defbase str-to-uuid
  [string]
  (try
    (java.util.UUID/fromString string)
    (catch Exception _ nil)))


;; === Registry ===

(def impls
  {:list-entities list-entities
   :get-entity get-entity
   :create-entity create-entity
   :update-entity update-entity
   :delete-entity delete-entity
   :list-all-graph-entities list-all-graph-entities
   :all-rich-types all-rich-types
   :value-kinds value-kinds
   :_types-compatible-parsed _types-compatible-parsed
   :_types-compatible-validation _types-compatible-validation
   :_types-compatible-apply _types-compatible-apply
   :_types-candidates-parsed _types-candidates-parsed
   :_types-candidates-validation _types-candidates-validation
   :_types-candidates-apply _types-candidates-apply
   :_types-usages-parsed _types-usages-parsed
   :_types-usages-validation _types-usages-validation
   :_types-usages-apply _types-usages-apply
   :_create-record-type-parsed _create-record-type-parsed
   :_create-record-type-validation _create-record-type-validation
   :_create-record-type-apply _create-record-type-apply
   :_create-list-type-parsed _create-list-type-parsed
   :_create-list-type-validation _create-list-type-validation
   :_create-list-type-apply _create-list-type-apply
   :_update-record-type-parsed _update-record-type-parsed
   :_update-record-type-validation _update-record-type-validation
   :_update-record-type-apply _update-record-type-apply
   :_create-parsed _create-parsed
   :_create-validation _create-validation
   :_create-apply _create-apply
   :_update-parsed _update-parsed
   :_update-validation _update-validation
   :_update-apply _update-apply
   :_rejected? _rejected?
   :_rejection-response _rejection-response
   :process-delete-entity process-delete-entity
   :process-sequence-append process-sequence-append
   :process-sequence-remove process-sequence-remove
   :process-sequence-update process-sequence-update
   :_tighten-parsed _tighten-parsed
   :_tighten-binding-id-invalid? _tighten-binding-id-invalid?
   :_tighten-effects-invalid? _tighten-effects-invalid?
   :_tighten-args-invalid? _tighten-args-invalid?
   :_tighten-delta-empty? _tighten-delta-empty?
   :_tighten-apply _tighten-apply
   :parse-fn-from-form parse-fn-from-form
   :parse-ns-from-form parse-ns-from-form
   :parse-slot-from-form parse-slot-from-form
   :parse-fn-slot-from-form parse-fn-slot-from-form
   :parse-binding-from-form parse-binding-from-form
   :parse-binding-list-item-from-form parse-binding-list-item-from-form
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
