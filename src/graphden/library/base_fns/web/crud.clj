(ns graphden.library.base-fns.web.crud
  "Storage CRUD base functions for web UI.

   Provides base functions that wrap storage protocol operations,
   making them accessible from fn-defs:

   - list-entities: Query entities of a given type
   - get-entity: Read a single entity by ID
   - create-entity: Create a new entity
   - update-entity: Update an existing entity
   - delete-entity: Delete an entity

   These functions require storage to be available in the execution context.
   The storage is typically injected at system startup.

   ## Context Dependency

   These base functions use the execution context to access storage.
   This is one of the rare cases where ctx is used in a base function.

   ## Security Note

   These are low-level CRUD operations. Access control should be
   implemented at the route/handler level before calling these functions."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.executor.registry.macros :refer [defbase]]
    [graphden.storage.protocol.interface :as sp]
    [hiccup2.core]))


;; =============================================================================
;; Entity Query Functions
;; =============================================================================

(def list-entities-impl
  "Lists entities of a given type with optional filtering.

   Arguments:
   - entity-type: Keyword for entity type (:fn, :fn-schema, :arg-schema, :arg-value, :call-site)
   - where: Optional where clause map for filtering

   Returns:
   Vector of entity maps.

   Requires :storage in execution context."
  {:args {:entity-type :text
          :where {:type :jsonb :required false}}
   :return-type :jsonb
   :impl (fn [{:keys [entity-type where]} ctx]
           (let [storage (:storage ctx)
                 etype (keyword @entity-type)
                 conditions (when where @where)]
             (if storage
               (vec (sp/query-entities storage etype (or conditions {})))
               (throw (ex-info "Storage not available in context"
                               {:type :execution-error/missing-storage})))))})


(def get-entity-impl
  "Gets a single entity by ID.

   Arguments:
   - entity-type: Keyword for entity type
   - id: UUID of the entity

   Returns:
   Entity map or nil if not found.

   Requires :storage in execution context."
  {:args {:entity-type :text
          :id :uuid}
   :return-type :jsonb
   :impl (fn [{:keys [entity-type id]} ctx]
           (let [storage (:storage ctx)
                 etype (keyword @entity-type)
                 entity-id @id]
             (if storage
               (sp/read-entity storage etype entity-id)
               (throw (ex-info "Storage not available in context"
                               {:type :execution-error/missing-storage})))))})


(def create-entity-impl
  "Creates a new entity.

   Arguments:
   - entity-type: Keyword for entity type
   - data: Map of entity data (without ID, will be generated)

   Returns:
   Created entity map with generated ID.

   Requires :storage in execution context."
  {:args {:entity-type :text
          :data :jsonb}
   :return-type :jsonb
   :impl (fn [{:keys [entity-type data]} ctx]
           (let [storage (:storage ctx)
                 etype (keyword @entity-type)
                 entity-data @data]
             (if storage
               (sp/create-entity storage etype entity-data)
               (throw (ex-info "Storage not available in context"
                               {:type :execution-error/missing-storage})))))})


(def update-entity-impl
  "Updates an existing entity.

   Arguments:
   - entity-type: Keyword for entity type
   - id: UUID of the entity to update
   - data: Map of fields to update

   Returns:
   Updated entity map.

   Requires :storage in execution context."
  {:args {:entity-type :text
          :id :uuid
          :data :jsonb}
   :return-type :jsonb
   :impl (fn [{:keys [entity-type id data]} ctx]
           (let [storage (:storage ctx)
                 etype (keyword @entity-type)
                 entity-id @id
                 entity-data @data]
             (if storage
               (sp/update-entity storage etype entity-id entity-data)
               (throw (ex-info "Storage not available in context"
                               {:type :execution-error/missing-storage})))))})


(def delete-entity-impl
  "Deletes an entity by ID.

   Arguments:
   - entity-type: Keyword for entity type
   - id: UUID of the entity to delete

   Returns:
   true if deleted, false if not found.

   Requires :storage in execution context."
  {:args {:entity-type :text
          :id :uuid}
   :return-type :bool
   :impl (fn [{:keys [entity-type id]} ctx]
           (let [storage (:storage ctx)
                 etype (keyword @entity-type)
                 entity-id @id]
             (if storage
               (do
                 (sp/delete-entity storage etype entity-id)
                 true)
               (throw (ex-info "Storage not available in context"
                               {:type :execution-error/missing-storage})))))})


;; =============================================================================
;; Batch Operations
;; =============================================================================

(def list-all-graph-entities-impl
  "Lists all graph entities (fn-schemas, fns, arg-schemas, arg-values, call-sites).

   Returns:
   Map with keys :fn-schemas, :fns, :arg-schemas, :arg-values, :call-sites,
   each containing a vector of entities.

   Requires :storage in execution context."
  {:args {}
   :return-type :jsonb
   :impl (fn [_args ctx]
           (if-let [storage (:storage ctx)]
             {:fn-schemas (vec (sp/query-entities storage :fn-schema {}))
              :fns (vec (sp/query-entities storage :fn {}))
              :arg-schemas (vec (sp/query-entities storage :arg-schema {}))
              :arg-values (vec (sp/query-entities storage :arg-value {}))
              :call-sites (vec (sp/query-entities storage :call-site {}))}
             (throw (ex-info "Storage not available in context"
                             {:type :execution-error/missing-storage}))))})


;; =============================================================================
;; Request Parameter Helpers
;; =============================================================================

(defbase get-path-param
  "Extracts a path parameter from request.

   Arguments:
   - request: Ring request map
   - param: Parameter name (keyword or string)

   Returns:
   Parameter value or nil if not found."
  {:args {:request :jsonb
          :param :text}
   :return-type :any}
  (let [param-key (keyword param)]
    (get-in request [:path-params param-key])))


(defbase get-query-param
  "Extracts a query parameter from request.

   Arguments:
   - request: Ring request map
   - param: Parameter name (string)
   - default: Default value if not found (optional)

   Returns:
   Parameter value or default."
  {:args {:request :jsonb
          :param :text
          :default {:type :any :required false}}
   :return-type :any}
  (let [query-string (:query-string request)
        params (when query-string
                 (into {}
                       (for [pair (clojure.string/split query-string #"&")
                             :let [[k v] (str/split pair #"=" 2)]
                             :when k]
                         [k (or v "")])))]
    (get params param default)))


(defbase parse-form-body
  "Parses URL-encoded form body from request.

   Arguments:
   - request: Ring request map

   Returns:
   Map of form field names to values."
  {:args {:request :jsonb}
   :return-type :jsonb}
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (if (and body (str/includes? content-type "application/x-www-form-urlencoded"))
      (into {}
            (for [pair (str/split body #"&")
                  :let [[k v] (str/split pair #"=" 2)]
                  :when k]
              [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))
      {})))


(defbase parse-json-body
  "Parses JSON body from request.

   Arguments:
   - request: Ring request map

   Returns:
   Parsed JSON as Clojure data."
  {:args {:request :jsonb}
   :return-type :jsonb}
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (when (and body (str/includes? content-type "application/json"))
      (json/parse-string body true))))


(defbase str-to-uuid
  "Parses a string to UUID.

   Arguments:
   - s: String representation of UUID

   Returns:
   UUID or nil if invalid."
  {:args {:s :text}
   :return-type :uuid}
  (try
    (java.util.UUID/fromString s)
    (catch Exception _
      nil)))


;; =============================================================================
;; Dynamic JSON Handler for CRUD Operations
;; =============================================================================

(def all-entities-json-handler-impl
  "Creates a Ring handler that returns all graph entities as JSON.

   This handler is executed per-request and uses storage from context.

   Returns:
   Ring handler function that queries storage and returns JSON response."
  {:args {}
   :return-type :fn
   :impl (fn [_args ctx]
           (let [storage (:storage ctx)]
             (fn [_request]
               (if storage
                 (try
                   (let [result {:fn_schemas (vec (sp/query-entities storage :fn-schema {}))
                                 :fns (vec (sp/query-entities storage :fn {}))
                                 :arg_schemas (vec (sp/query-entities storage :arg-schema {}))
                                 :arg_values (vec (sp/query-entities storage :arg-value {}))
                                 :call_sites (vec (sp/query-entities storage :call-site {}))}]
                     {:status 200
                      :headers {"Content-Type" "application/json"}
                      :body (json/generate-string result)})
                   (catch Exception e
                     {:status 500
                      :headers {"Content-Type" "application/json"}
                      :body (json/generate-string
                              {:error (ex-message e)
                               :type (str (:type (ex-data e)))})}))
                 {:status 500
                  :headers {"Content-Type" "application/json"}
                  :body (json/generate-string
                          {:error "Storage not available"})}))))})


;; =============================================================================
;; Entity Details Handler
;; =============================================================================

(defn- entity-type-from-string
  "Converts string entity type to keyword."
  [s]
  (case s
    "fn" :fn
    "fn-schema" :fn-schema
    "arg-schema" :arg-schema
    "arg-value" :arg-value
    "call-site" :call-site
    nil))


(defn- render-field-row
  "Renders a single field row for entity details."
  [label value]
  [:div {:class "field-row"}
   [:span {:class "field-label"} label]
   [:span {:class "field-value"} (if (nil? value) "-" (str value))]])


(defn- render-entity-badge
  "Renders entity type badge."
  [entity-type-str]
  (let [badge-class (str "badge badge-" entity-type-str)]
    [:span {:class badge-class} entity-type-str]))


(defn- render-fn-schema-details
  "Renders fn-schema entity details."
  [entity]
  [:div
   (render-field-row "ID" (:id entity))
   (render-field-row "Name" (name (:name entity)))
   (render-field-row "Return Type" (when (:returned-type entity) (name (:returned-type entity))))
   (render-field-row "Base Fn" (when (:base-fn-name entity) (name (:base-fn-name entity))))
   (render-field-row "Is Base?" (if (:base-fn-name entity) "Yes" "No"))])


(defn- render-fn-details
  "Renders fn entity details."
  [entity]
  [:div
   (render-field-row "ID" (:id entity))
   (render-field-row "Name" (name (:name entity)))
   (render-field-row "Schema ID" (:fn-schema-id entity))])


(defn- render-arg-schema-details
  "Renders arg-schema entity details."
  [entity]
  [:div
   (render-field-row "ID" (:id entity))
   (render-field-row "Name" (name (:name entity)))
   (render-field-row "Type" (when (:type entity) (name (:type entity))))
   (render-field-row "Required" (if (:required entity) "Yes" "No"))
   (render-field-row "Fn Schema ID" (:fn-schema-id entity))])


(defn- render-arg-value-details
  "Renders arg-value entity details."
  [entity]
  (let [value (:value entity)
        is-ref (and (map? value) (or (:fn-id value) (:call-site-id value)))
        display-value (cond
                        (and (map? value) (:fn-id value))
                        [:span "ref<fn:" [:code (str (:fn-id value))] ">"]
                        (and (map? value) (:call-site-id value))
                        [:span "ref<call-site:" [:code (str (:call-site-id value))] ">"]
                        :else
                        [:code (pr-str value)])]
    [:div
     (render-field-row "ID" (:id entity))
     [:div {:class "field-row"}
      [:span {:class "field-label"} "Value"]
      [:span {:class "field-value"} display-value]]
     (render-field-row "Is Reference" (if is-ref "Yes" "No"))
     (render-field-row "Owner Fn ID" (:owner-fn-id entity))
     (render-field-row "Arg Schema ID" (:arg-schema-id entity))]))


(defn- render-call-site-details
  "Renders call-site entity details."
  [entity]
  [:div
   (render-field-row "ID" (:id entity))
   (render-field-row "Name" (when (:name entity) (name (:name entity))))
   (render-field-row "Fn ID" (:fn-id entity))])


(defn- render-entity-details
  "Renders entity details based on type."
  [entity-type-str entity]
  (case entity-type-str
    "fn-schema" (render-fn-schema-details entity)
    "fn" (render-fn-details entity)
    "arg-schema" (render-arg-schema-details entity)
    "arg-value" (render-arg-value-details entity)
    "call-site" (render-call-site-details entity)
    [:p "Unknown entity type"]))


(def entity-details-handler-impl
  "Creates a Ring handler that returns entity details as HTMX partial.

   Expects path params: :type and :id"
  {:args {}
   :return-type :fn
   :impl (fn [_args ctx]
           (let [storage (:storage ctx)]
             (fn [request]
               (let [entity-type-str (get-in request [:path-params :type])
                     entity-id-str (get-in request [:path-params :id])
                     entity-type (entity-type-from-string entity-type-str)]
                 (if (and storage entity-type entity-id-str)
                   (try
                     (let [entity-id (java.util.UUID/fromString entity-id-str)
                           entity (sp/read-entity storage entity-type entity-id)]
                       (if entity
                         {:status 200
                          :headers {"Content-Type" "text/html; charset=utf-8"}
                          :body (str
                                  (hiccup2.core/html
                                    [:div
                                     [:div {:style "margin-bottom: 12px;"}
                                      (render-entity-badge entity-type-str)]
                                     (render-entity-details entity-type-str entity)
                                     [:div {:style "margin-top: 16px; display: flex; gap: 8px;"}
                                      [:button {:class "btn btn-primary"
                                                :hx-get (str "/partials/entity-form/" entity-type-str "/" entity-id-str)
                                                :hx-target "#details-content"
                                                :hx-swap "innerHTML"}
                                       "Edit"]
                                      [:button {:class "btn btn-danger"
                                                :hx-delete (str "/api/entities/" entity-type-str "/" entity-id-str)
                                                :hx-confirm "Are you sure you want to delete this entity?"
                                                :hx-target "#details-panel"
                                                :hx-swap "outerHTML"
                                                :_ "on htmx:afterRequest trigger entityDeleted on body"}
                                       "Delete"]]]))}
                         {:status 404
                          :headers {"Content-Type" "text/html; charset=utf-8"}
                          :body "<p class=\"error\">Entity not found</p>"}))
                     (catch Exception e
                       {:status 500
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body (str "<p class=\"error\">Error: " (ex-message e) "</p>")}))
                   {:status 400
                    :headers {"Content-Type" "text/html; charset=utf-8"}
                    :body "<p class=\"error\">Invalid request</p>"})))))})


;; =============================================================================
;; Entity Form Handler
;; =============================================================================

(defn- render-fn-form
  "Renders form for fn entity."
  [entity fn-schemas]
  (let [editing? (some? entity)
        schema-options (mapv (fn [fs] [(:id fs) (name (:name fs))]) fn-schemas)]
    [:form {:hx-post (if editing?
                       (str "/api/entities/fn/" (:id entity))
                       "/api/entities/fn")
            :hx-target "#modal-content"
            :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     [:div {:class "form-group"}
      [:label {:for "name"} "Name"]
      [:input {:type "text" :name "name" :id "name" :required true
               :value (when entity (name (:name entity)))}]]
     [:div {:class "form-group"}
      [:label {:for "fn-schema-id"} "Schema"]
      [:select {:name "fn-schema-id" :id "fn-schema-id" :required true}
       [:option {:value ""} "Select schema..."]
       (for [[id label] schema-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:fn-schema-id entity)))}
          label])]]
     [:div {:style "display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px;"}
      [:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
      [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]]))


(defn- render-call-site-form
  "Renders form for call-site entity."
  [entity fns]
  (let [editing? (some? entity)
        fn-options (mapv (fn [f] [(:id f) (name (:name f))]) fns)]
    [:form {:hx-post (if editing?
                       (str "/api/entities/call-site/" (:id entity))
                       "/api/entities/call-site")
            :hx-target "#modal-content"
            :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     [:div {:class "form-group"}
      [:label {:for "name"} "Name (optional)"]
      [:input {:type "text" :name "name" :id "name"
               :value (when (and entity (:name entity)) (name (:name entity)))}]]
     [:div {:class "form-group"}
      [:label {:for "fn-id"} "Function"]
      [:select {:name "fn-id" :id "fn-id" :required true}
       [:option {:value ""} "Select function..."]
       (for [[id label] fn-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:fn-id entity)))}
          label])]]
     [:div {:style "display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px;"}
      [:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
      [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]]))


(defn- render-arg-value-form
  "Renders form for arg-value entity."
  [entity fns arg-schemas]
  (let [editing? (some? entity)
        fn-options (mapv (fn [f] [(:id f) (name (:name f))]) fns)
        arg-schema-options (mapv (fn [as] [(:id as) (name (:name as))]) arg-schemas)]
    [:form {:hx-post (if editing?
                       (str "/api/entities/arg-value/" (:id entity))
                       "/api/entities/arg-value")
            :hx-target "#modal-content"
            :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     [:div {:class "form-group"}
      [:label {:for "owner-fn-id"} "Owner Function"]
      [:select {:name "owner-fn-id" :id "owner-fn-id" :required true}
       [:option {:value ""} "Select function..."]
       (for [[id label] fn-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:owner-fn-id entity)))}
          label])]]
     [:div {:class "form-group"}
      [:label {:for "arg-schema-id"} "Argument Schema"]
      [:select {:name "arg-schema-id" :id "arg-schema-id" :required true}
       [:option {:value ""} "Select argument..."]
       (for [[id label] arg-schema-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:arg-schema-id entity)))}
          label])]]
     [:div {:class "form-group"}
      [:label {:for "value"} "Value (JSON)"]
      [:textarea {:name "value" :id "value" :rows 3 :required true}
       (when entity (json/generate-string (:value entity)))]]
     [:div {:style "display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px;"}
      [:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
      [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]]))


(def entity-form-handler-impl
  "Creates a Ring handler that returns entity form as HTMX partial.

   Expects path params: :type and optionally :id (for editing)"
  {:args {}
   :return-type :fn
   :impl (fn [_args ctx]
           (let [storage (:storage ctx)]
             (fn [request]
               (let [entity-type-str (get-in request [:path-params :type])
                     entity-id-str (get-in request [:path-params :id])
                     entity-type (entity-type-from-string entity-type-str)]
                 (if (and storage entity-type)
                   (try
                     (let [entity (when entity-id-str
                                    (sp/read-entity storage entity-type
                                                    (java.util.UUID/fromString entity-id-str)))
                           fn-schemas (vec (sp/query-entities storage :fn-schema {}))
                           fns (vec (sp/query-entities storage :fn {}))
                           arg-schemas (vec (sp/query-entities storage :arg-schema {}))
                           form-html (case entity-type-str
                                       "fn" (render-fn-form entity fn-schemas)
                                       "call-site" (render-call-site-form entity fns)
                                       "arg-value" (render-arg-value-form entity fns arg-schemas)
                                       [:p "Forms for " entity-type-str " not yet implemented"])]
                       {:status 200
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body (str (hiccup2.core/html
                                     [:div
                                      [:h4 (if entity
                                             (str "Edit " entity-type-str)
                                             (str "Create " entity-type-str))]
                                      form-html]))})
                     (catch Exception e
                       {:status 500
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body (str "<p class=\"error\">Error: " (ex-message e) "</p>")}))
                   {:status 400
                    :headers {"Content-Type" "text/html; charset=utf-8"}
                    :body "<p class=\"error\">Invalid entity type</p>"})))))})


;; =============================================================================
;; CRUD API Handlers
;; =============================================================================

(def create-entity-api-handler-impl
  "Creates a Ring handler for POST /api/entities/:type"
  {:args {}
   :return-type :fn
   :impl (fn [_args ctx]
           (let [storage (:storage ctx)]
             (fn [request]
               (let [entity-type-str (get-in request [:path-params :type])
                     entity-type (entity-type-from-string entity-type-str)
                     body (:body request)
                     form-data (when body
                                 (into {}
                                       (for [pair (str/split body #"&")
                                             :let [[k v] (str/split pair #"=" 2)]
                                             :when k]
                                         [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")])))]
                 (if (and storage entity-type form-data)
                   (try
                     (let [entity-data (case entity-type-str
                                         "fn" {:name (keyword (:name form-data))
                                               :fn-schema-id (java.util.UUID/fromString (:fn-schema-id form-data))}
                                         "call-site" (cond-> {:fn-id (java.util.UUID/fromString (:fn-id form-data))}
                                                       (not (str/blank? (:name form-data)))
                                                       (assoc :name (keyword (:name form-data))))
                                         "arg-value" {:owner-fn-id (java.util.UUID/fromString (:owner-fn-id form-data))
                                                      :arg-schema-id (java.util.UUID/fromString (:arg-schema-id form-data))
                                                      :value (json/parse-string (:value form-data) true)}
                                         nil)
                           created (when entity-data
                                     (sp/create-entity storage entity-type entity-data))]
                       (if created
                         {:status 200
                          :headers {"Content-Type" "text/html; charset=utf-8"
                                    "HX-Trigger" "entityCreated"}
                          :body "<p>Entity created successfully</p>"}
                         {:status 400
                          :headers {"Content-Type" "text/html; charset=utf-8"}
                          :body "<p class=\"error\">Failed to create entity</p>"}))
                     (catch Exception e
                       {:status 500
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body (str "<p class=\"error\">Error: " (ex-message e) "</p>")}))
                   {:status 400
                    :headers {"Content-Type" "text/html; charset=utf-8"}
                    :body "<p class=\"error\">Invalid request</p>"})))))})


(def delete-entity-api-handler-impl
  "Creates a Ring handler for DELETE /api/entities/:type/:id"
  {:args {}
   :return-type :fn
   :impl (fn [_args ctx]
           (let [storage (:storage ctx)]
             (fn [request]
               (let [entity-type-str (get-in request [:path-params :type])
                     entity-id-str (get-in request [:path-params :id])
                     entity-type (entity-type-from-string entity-type-str)]
                 (if (and storage entity-type entity-id-str)
                   (try
                     (let [entity-id (java.util.UUID/fromString entity-id-str)]
                       (sp/delete-entity storage entity-type entity-id)
                       {:status 200
                        :headers {"Content-Type" "text/html; charset=utf-8"
                                  "HX-Trigger" "entityDeleted"}
                        :body ""})
                     (catch Exception e
                       {:status 500
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body (str "<p class=\"error\">Error: " (ex-message e) "</p>")}))
                   {:status 400
                    :headers {"Content-Type" "text/html; charset=utf-8"}
                    :body "<p class=\"error\">Invalid request</p>"})))))})


;; =============================================================================
;; Exports
;; =============================================================================

(def all-defs
  "All CRUD base function definitions.

   Note: get-path-param was removed - use composition:
   - get-in (from collections) + str-to-keyword (from strings)
   - Example: (get-in request [:path-params (str-to-keyword param-name)])"
  {:list-entities list-entities-impl
   :get-entity get-entity-impl
   :create-entity create-entity-impl
   :update-entity update-entity-impl
   :delete-entity delete-entity-impl
   :list-all-graph-entities list-all-graph-entities-impl
   :all-entities-json-handler all-entities-json-handler-impl
   :entity-details-handler entity-details-handler-impl
   :entity-form-handler entity-form-handler-impl
   :create-entity-api-handler create-entity-api-handler-impl
   :delete-entity-api-handler delete-entity-api-handler-impl
   ;; get-path-param removed - compose with get-in + str-to-keyword
   :get-query-param get-query-param
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
