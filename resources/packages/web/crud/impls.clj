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

(defbase _delete-parsed
  [request]
  (entities/parse-delete-entity-request request))


(defbase _delete-request-invalid?
  [parsed]
  (or (nil? (:entity-type parsed)) (nil? (:id parsed))))


(defbase _delete-fn-is-secret?
  [parsed]
  (entities/delete-fn-secret? parsed ctx))


(defbase _delete-fn-in-use-reason
  [parsed]
  (entities/delete-fn-in-use-reason parsed ctx))


(defbase _delete-fn-in-use?
  [fn-in-use-reason]
  (some? fn-in-use-reason))


(defbase _delete-err-fn-in-use
  [fn-in-use-reason]
  (entities/delete-err-with-reason fn-in-use-reason))


(defbase _delete-ns-non-empty-reason
  [parsed]
  (entities/delete-ns-non-empty-reason parsed ctx))


(defbase _delete-ns-non-empty?
  [ns-non-empty-reason]
  (some? ns-non-empty-reason))


(defbase _delete-err-ns-non-empty
  [ns-non-empty-reason]
  (entities/delete-err-with-reason ns-non-empty-reason))


(defbase _delete-apply
  [parsed]
  (entities/apply-delete-entity parsed ctx))


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

(defbase _seq-append-parsed
  [request]
  (entities/parse-seq-append-request request))


(defbase _seq-append-fn-id-invalid?
  [parsed]
  (nil? (:fn-id parsed)))


(defbase _seq-append-body-invalid?
  [parsed]
  (nil? (:body parsed)))


(defbase _seq-append-load-binding
  [parsed]
  (entities/find-seq-append-binding parsed ctx))


(defbase _seq-append-no-seq-slot?
  [seq-binding]
  (nil? seq-binding))


(defbase _seq-append-apply
  [parsed seq-binding]
  (entities/apply-seq-append parsed seq-binding ctx))


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

(defbase _seq-remove-parsed
  [request]
  (entities/parse-seq-remove-request request))


(defbase _seq-remove-item-id-invalid?
  [parsed]
  (nil? (:item-id parsed)))


(defbase _seq-remove-load-item
  [parsed]
  (entities/load-seq-remove-item parsed ctx))


(defbase _seq-remove-item-not-found?
  [item]
  (nil? item))


(defbase _seq-remove-apply
  [parsed item]
  (entities/apply-seq-remove parsed item ctx))


;; === Sequence-update primitives (C4 decomposition) ===
;; `:process-sequence-update` is now a `:cond` graph fn-def in fns.edn.
;; Same shape as C2 + C3 — parse / two upfront guards / read-only load
;; / not-found guard / apply (which carries the data-dependent write-rej
;; 400 internally).

(defbase _seq-update-parsed
  [request]
  (entities/parse-seq-update-request request))


(defbase _seq-update-item-id-invalid?
  [parsed]
  (nil? (:item-id parsed)))


(defbase _seq-update-body-invalid?
  [parsed]
  (nil? (:body parsed)))


(defbase _seq-update-load-item
  [parsed]
  (entities/load-seq-update-item parsed ctx))


(defbase _seq-update-item-not-found?
  [item]
  (nil? item))


(defbase _seq-update-apply
  [parsed item]
  (entities/apply-seq-update parsed item ctx))


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
   :_delete-parsed _delete-parsed
   :_delete-request-invalid? _delete-request-invalid?
   :_delete-fn-is-secret? _delete-fn-is-secret?
   :_delete-fn-in-use-reason _delete-fn-in-use-reason
   :_delete-fn-in-use? _delete-fn-in-use?
   :_delete-err-fn-in-use _delete-err-fn-in-use
   :_delete-ns-non-empty-reason _delete-ns-non-empty-reason
   :_delete-ns-non-empty? _delete-ns-non-empty?
   :_delete-err-ns-non-empty _delete-err-ns-non-empty
   :_delete-apply _delete-apply
   :_seq-append-parsed _seq-append-parsed
   :_seq-append-fn-id-invalid? _seq-append-fn-id-invalid?
   :_seq-append-body-invalid? _seq-append-body-invalid?
   :_seq-append-load-binding _seq-append-load-binding
   :_seq-append-no-seq-slot? _seq-append-no-seq-slot?
   :_seq-append-apply _seq-append-apply
   :_seq-remove-parsed _seq-remove-parsed
   :_seq-remove-item-id-invalid? _seq-remove-item-id-invalid?
   :_seq-remove-load-item _seq-remove-load-item
   :_seq-remove-item-not-found? _seq-remove-item-not-found?
   :_seq-remove-apply _seq-remove-apply
   :_seq-update-parsed _seq-update-parsed
   :_seq-update-item-id-invalid? _seq-update-item-id-invalid?
   :_seq-update-body-invalid? _seq-update-body-invalid?
   :_seq-update-load-item _seq-update-load-item
   :_seq-update-item-not-found? _seq-update-item-not-found?
   :_seq-update-apply _seq-update-apply
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
