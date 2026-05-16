(ns graphden.packages.web.crud.impls
  "Implementations for web/crud base functions.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive.

   The only logic that remains here is the rendering code
   (`render-entity-*`, `render-fn-form`, `form-input-h`,
   `form-select-h`, `fn-field-specs`): it depends on the
   `graphden.packages.web.html.impls` *package*, which `src/` may not
   require, so it stays put."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.crud.entities :as entities]
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.web.html.impls :as html]
    [graphden.storage.protocol.core :as sp]))


;; === Context-aware Query Functions ===

(defbase list-entities
  [entity-type where]
  (entities/list-entities entity-type where ctx))


(defbase get-entity
  [entity-type id]
  (entities/get-entity entity-type id ctx))


(defbase create-entity
  [entity-type data]
  (entities/create-entity entity-type data ctx))


(defbase update-entity
  [entity-type id data]
  (entities/update-entity entity-type id data ctx))


(defbase delete-entity
  [entity-type id]
  (entities/delete-entity entity-type id ctx))


(defbase list-all-graph-entities
  []
  (entities/list-all-graph-entities ctx))


(defbase all-rich-types
  []
  (types-api/all-rich-types ctx))


;; === Type-API base functions ===

(defbase types-compatible
  "Single-pair subtype check. POST body: `{expected, candidate}` where
   each side is a type in the JSON shape produced by `/api/types`.
   Returns `{ok, expected, candidate, reason?}`. UI uses this to
   render type-mismatch explainers without re-implementing
   `subtype?` in JS."
  [request]
  (types-api/types-compatible request))


(defbase types-candidates
  "Enumerate every fn whose return type is a subtype of `expected`,
   optionally further filtered. POST body:
     {expected: <type>,
      effects?: [\"db\" \"env\" …]   ; allowed-effect set; candidates
                                     ; with effects ⊆ this pass
      name-prefix?: \"app.server\"   ; namespace / name prefix filter}
   Returns `{count, candidates: [{name, return, args, effects}, ...]}`
   sorted alphabetically."
  [request]
  (types-api/types-candidates request ctx))


(defbase types-usages
  "Find every place a type-row is referenced. POST body
   `{type-fn-id: <uuid-string>}`. Returns `{ok, type-fn-id,
   type-name, usages: [{fn-id, fn-name, role, kind, slot-name?},
   …]}` — one entry per usage."
  [request]
  (types-api/types-usages request ctx))


(defbase process-create-record-type
  "Atomically create a record type-row: one fn-row + N slot-rows + N
   fn-slot-junctions. JSON body shape:
     {namespace-id?, name, description?,
      fields: [{name, type, description?, required?}, …]}
   `type` per field accepts a name (`\"int\"` / `\"ring-request-shape\"`)
   or a UUID. On any sub-write failure the partial fn-row is
   deleted (best-effort rollback) and the error surfaces to the
   caller via `{:ok false :error \"…\"}`."
  [request]
  (entities/process-create-record-type request ctx))


(defbase process-create-list-type
  "Atomically create a list type-row: one fn-row with `element-fn-id`
   plus the synthesised `items` slot. JSON body:
     {namespace-id?, name, description?, element-type}
   `element-type` accepts a type-name or a UUID."
  [request]
  (entities/process-create-list-type request ctx))


(defbase process-update-record-type
  "Update an existing record type-row by computing the diff of the
   submitted field list against the row's current fn-slots, then
   atomically applying it. JSON body:
     {id, name?, description?,
      fields: [{name, type, description?, required?}, …]}
   Atomicity: there's no with-transaction at the protocol layer, so
   the impl journals every write and rewinds on failure. Caveat: this
   does NOT garbage-collect orphaned slots (slot rows are shared)."
  [request]
  (entities/process-update-record-type request ctx))


;; === Rendering Helpers (stay in this package — depend on web.html) ===
;; NOTE: fn-field-specs duplicates the `:fn-field-specs` fn-def in
;; fns.edn — they must stay in sync. The duplication exists because
;; base-fn impls cannot resolve fn-defs at runtime.

(def ^:private fn-field-specs
  [["ID" :id] ["Name" :name :keyword-to-str] ["Parent ID" :parent-id]
   ["Return Type" :return-type :keyword-to-str] ["Impl Hash" :impl-hash]])


(defn- form-input-h
  [{:keys [field-name label-text field-value extra-attrs]}]
  [:div {:class "form-group"}
   [:label {:for field-name} label-text]
   [:input (merge {:type "text" :name field-name :id field-name}
                  (when field-value {:value field-value})
                  extra-attrs)]])


(defn- form-select-h
  [{:keys [field-name label-text options selected-value extra-attrs]}]
  [:div {:class "form-group"}
   [:label {:for field-name} label-text]
   (into [:select (merge {:name field-name :id field-name} extra-attrs)]
         (for [[v l] options]
           [:option (cond-> {:value v} (= v selected-value) (assoc :selected true)) l]))])


(defn- render-fn-form
  [entity all-fns]
  (let [editing? (some? entity)
        parent-options (into [["" "None"]]
                             (->> all-fns
                                  (filter :name)
                                  (mapv (fn [f] [(str (:id f)) (name (:name f))]))))]
    [:form {:hx-post (if editing? (str "/api/entities/fn/" (:id entity)) "/api/entities/fn")
            :hx-target "#modal-content" :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     (form-input-h {:field-name "name" :label-text "Name"
                    :field-value (when entity (name (:name entity)))
                    :extra-attrs {:required true}})
     (form-select-h {:field-name "parent-id" :label-text "Parent (optional)"
                     :options parent-options
                     :selected-value (when entity (str (:parent-id entity)))})
     (html/button-row {:buttons [[:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
                                 [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]
                       :style {:display "flex" :gap "8px" :justify-content "flex-end" :margin-top "16px"}})]))


;; === Render View Functions (context-aware) ===

(defbase render-entity-actions
  [entity-type entity-id]
  [:div {:style "margin-top: 16px; display: flex; gap: 8px;"}
   [:button {:class "btn btn-primary"
             :hx-get (str "/partials/entity-form/" entity-type "/" entity-id)
             :hx-target "#details-content" :hx-swap "innerHTML"} "Edit"]
   [:button {:class "btn btn-danger"
             :hx-delete (str "/api/entities/" entity-type "/" entity-id)
             :hx-confirm "Are you sure you want to delete this entity?"
             :hx-target "#details-panel" :hx-swap "outerHTML"
             :_ "on htmx:afterRequest trigger entityDeleted on body"} "Delete"]])


(defbase render-entity-details-view
  [request]
  (let [storage (request/require-storage ctx)
        {:keys [type-str id-str entity-type]} (request/extract-entity-params request)]
    (if (and entity-type id-str)
      (if-let [entity (sp/read-entity storage entity-type (java.util.UUID/fromString id-str))]
        [:div
         [:div {:style "margin-bottom: 12px;"}
          (html/badge {:badge-text type-str :badge-type type-str})]
         (when (= type-str "fn")
           (html/entity-field-rows {:entity entity :field-specs fn-field-specs}))
         (render-entity-actions {:entity-type type-str :entity-id id-str})]
        [:p {:class "error"} "Entity not found"])
      [:p {:class "error"} "Invalid request"])))


(defbase render-entity-form-view
  [request]
  (let [storage (request/require-storage ctx)
        {:keys [type-str id-str entity-type]} (request/extract-entity-params request)]
    (if entity-type
      (let [entity (when id-str (sp/read-entity storage entity-type (java.util.UUID/fromString id-str)))
            all-fns (vec (sp/query-entities storage :fn {}))]
        [:div
         [:h4 (str (if entity "Edit " "Create ") type-str)]
         (case type-str
           "fn" (render-fn-form entity all-fns)
           [:p "Not implemented"])])
      [:p {:class "error"} "Invalid entity type"])))


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

(defbase process-create-entity
  [request]
  (entities/process-create-entity request ctx))


(defbase process-update-entity
  "PUT /api/entities/:type/:id — updates an entity from a form-encoded
   body. Mirror of `process-create-entity` but goes through
   `update-entity` and requires both `:type` and `:id` URI segments."
  [request]
  (entities/process-update-entity request ctx))


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

(defbase process-tighten-binding-effects
  "POST /api/bindings/:binding-id/tighten-fn-effects
   Body: `{\"args\"?: {…}, \"ret\"?: T, \"effects\"?: [\"db\" …]}`

   For an fn-typed binding, narrow the slot's effective type by
   selectively replacing `args`, `ret`, or `effects`. Any subset
   may be supplied; omitted components keep their current value."
  [request]
  (entities/process-tighten-binding-effects request ctx))


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
   :types-compatible types-compatible
   :types-candidates types-candidates
   :types-usages types-usages
   :process-create-record-type process-create-record-type
   :process-create-list-type process-create-list-type
   :process-update-record-type process-update-record-type
   :render-entity-details-view render-entity-details-view
   :render-entity-form-view render-entity-form-view
   :process-create-entity process-create-entity
   :process-update-entity process-update-entity
   :process-delete-entity process-delete-entity
   :process-sequence-append process-sequence-append
   :process-sequence-remove process-sequence-remove
   :process-sequence-update process-sequence-update
   :process-tighten-binding-effects process-tighten-binding-effects
   :render-entity-actions render-entity-actions
   :parse-fn-from-form parse-fn-from-form
   :parse-ns-from-form parse-ns-from-form
   :parse-slot-from-form parse-slot-from-form
   :parse-fn-slot-from-form parse-fn-slot-from-form
   :parse-binding-from-form parse-binding-from-form
   :parse-binding-list-item-from-form parse-binding-list-item-from-form
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
