(ns graphden.library.base-fns.web.crud
  "Storage CRUD base functions for web UI.

   ## 2-Entity Schema

   The system uses a minimal 2-entity schema:
   - fn: function entity (parent-id=nil for base-fn, parent-id set for composed)
   - arg: argument entity (source-id for inheritance, value/ref-id for data)

   Provides base functions that wrap storage protocol operations:
   - list-entities: Query entities of a given type
   - get-entity: Read a single entity by ID
   - create-entity: Create a new entity
   - update-entity: Update an existing entity
   - delete-entity: Delete an entity

   These functions require storage to be available in the execution context."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.executor.registry.macros :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [hiccup2.core]))


;; =============================================================================
;; Entity Query Functions
;; =============================================================================

(def list-entities-impl
  "Lists entities of a given type with optional filtering.

   Arguments:
   - entity-type: Keyword for entity type (:fn or :arg)
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
   - entity-type: Keyword for entity type (:fn or :arg)
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
   - entity-type: Keyword for entity type (:fn or :arg)
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
   - entity-type: Keyword for entity type (:fn or :arg)
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
   - entity-type: Keyword for entity type (:fn or :arg)
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
  "Lists all graph entities (fns and args).

   Returns:
   Map with keys :fns and :args, each containing a vector of entities.

   Requires :storage in execution context."
  {:args {}
   :return-type :jsonb
   :impl (fn [_args ctx]
           (if-let [storage (:storage ctx)]
             {:fns (vec (sp/query-entities storage :fn {}))
              :args (vec (sp/query-entities storage :arg {}))}
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

   Returns:
   Ring handler function that queries storage and returns JSON response."
  {:args {}
   :return-type :fn
   :impl (fn [_args ctx]
           (let [storage (:storage ctx)]
             (fn [_request]
               (if storage
                 (try
                   (let [result {:fns (vec (sp/query-entities storage :fn {}))
                                 :args (vec (sp/query-entities storage :arg {}))}]
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
    "arg" :arg
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


(defn- render-fn-details
  "Renders fn entity details."
  [entity]
  (let [is-base? (nil? (:parent-id entity))]
    [:div
     (render-field-row "ID" (:id entity))
     (render-field-row "Name" (when (:name entity) (name (:name entity))))
     (render-field-row "Type" (if is-base? "Base Function" "Composed Function"))
     (render-field-row "Parent ID" (:parent-id entity))
     (render-field-row "Return Type" (when (:return-type entity) (name (:return-type entity))))
     (render-field-row "Impl Hash" (:impl-hash entity))]))


(defn- render-arg-details
  "Renders arg entity details."
  [entity]
  (let [has-value? (or (some? (:value entity)) (some? (:ref-id entity)))
        is-inherited? (some? (:source-id entity))]
    [:div
     (render-field-row "ID" (:id entity))
     (render-field-row "Name" (when (:name entity) (name (:name entity))))
     (render-field-row "Fn ID" (:fn-id entity))
     (render-field-row "Type" (when (:type entity) (name (:type entity))))
     (render-field-row "Required" (if (:required entity) "Yes" "No"))
     (render-field-row "Is Fn" (if (:is-fn entity) "Yes" "No"))
     (render-field-row "Inherited" (if is-inherited? "Yes" "No"))
     (when is-inherited?
       (render-field-row "Source ID" (:source-id entity)))
     (render-field-row "Has Value" (if has-value? "Yes" "No"))
     (when has-value?
       [:div
        (render-field-row "Value" (when (:value entity) (pr-str (:value entity))))
        (render-field-row "Ref ID" (:ref-id entity))])]))


(defn- render-entity-details
  "Renders entity details based on type."
  [entity-type-str entity]
  (case entity-type-str
    "fn" (render-fn-details entity)
    "arg" (render-arg-details entity)
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
  [entity all-fns]
  (let [editing? (some? entity)
        parent-options (mapv (fn [f] [(:id f) (name (:name f))]) all-fns)]
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
      [:label {:for "parent-id"} "Parent (optional - leave empty for base fn)"]
      [:select {:name "parent-id" :id "parent-id"}
       [:option {:value ""} "None (Base Function)"]
       (for [[id label] parent-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:parent-id entity)))}
          label])]]
     [:div {:style "display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px;"}
      [:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
      [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]]))


(defn- render-arg-form
  "Renders form for arg entity."
  [entity all-fns all-args]
  (let [editing? (some? entity)
        fn-options (mapv (fn [f] [(:id f) (name (:name f))]) all-fns)
        arg-options (mapv (fn [a] [(:id a) (name (:name a))]) all-args)]
    [:form {:hx-post (if editing?
                       (str "/api/entities/arg/" (:id entity))
                       "/api/entities/arg")
            :hx-target "#modal-content"
            :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     [:div {:class "form-group"}
      [:label {:for "name"} "Name"]
      [:input {:type "text" :name "name" :id "name" :required true
               :value (when (and entity (:name entity)) (name (:name entity)))}]]
     [:div {:class "form-group"}
      [:label {:for "fn-id"} "Function"]
      [:select {:name "fn-id" :id "fn-id" :required true}
       [:option {:value ""} "Select function..."]
       (for [[id label] fn-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:fn-id entity)))}
          label])]]
     [:div {:class "form-group"}
      [:label {:for "source-id"} "Source Arg (for inheritance)"]
      [:select {:name "source-id" :id "source-id"}
       [:option {:value ""} "None (primary arg)"]
       (for [[id label] arg-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:source-id entity)))}
          label])]]
     [:div {:class "form-group"}
      [:label {:for "type"} "Type"]
      [:select {:name "type" :id "type" :required true}
       (for [t ["int" "text" "bool" "uuid" "jsonb" "any" "fn"]]
         [:option {:value t
                   :selected (and entity (:type entity) (= t (name (:type entity))))}
          t])]]
     [:div {:class "form-group"}
      [:label {:for "value"} "Value (JSON)"]
      [:textarea {:name "value" :id "value" :rows 3}
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
                           all-fns (vec (sp/query-entities storage :fn {}))
                           all-args (vec (sp/query-entities storage :arg {}))
                           form-html (case entity-type-str
                                       "fn" (render-fn-form entity all-fns)
                                       "arg" (render-arg-form entity all-fns all-args)
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
                                         "fn" (cond-> {:name (keyword (:name form-data))}
                                                (not (str/blank? (:parent-id form-data)))
                                                (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data))))
                                         "arg" (cond-> {:name (keyword (:name form-data))
                                                        :fn-id (java.util.UUID/fromString (:fn-id form-data))
                                                        :type (keyword (:type form-data))}
                                                 (not (str/blank? (:source-id form-data)))
                                                 (assoc :source-id (java.util.UUID/fromString (:source-id form-data)))
                                                 (not (str/blank? (:value form-data)))
                                                 (assoc :value (json/parse-string (:value form-data) true)))
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
  "All CRUD base function definitions."
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
   :get-path-param get-path-param
   :get-query-param get-query-param
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
