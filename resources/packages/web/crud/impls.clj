(ns graphden.packages.web.crud.impls
  "Implementations for web/crud base functions.

   Context-aware functions receive ctx as second argument.
   Pure functions receive only args map."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.packages.web.html.impls :as html]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [hiccup2.core])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


;; === Context-aware Query Functions ===

(defn list-entities
  [{:keys [entity-type where]} ctx]
  (let [storage (:storage ctx)
        etype (keyword @entity-type)
        conditions (when where @where)]
    (if storage
      (vec (sp/query-entities storage etype (or conditions {})))
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))))


(defn get-entity
  [{:keys [entity-type id]} ctx]
  (let [storage (:storage ctx)
        etype (keyword @entity-type)
        entity-id @id]
    (if storage
      (sp/read-entity storage etype entity-id)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))))


(defn create-entity
  [{:keys [entity-type data]} ctx]
  (let [storage (:storage ctx)
        etype (keyword @entity-type)
        entity-data @data]
    (if storage
      (sp/create-entity storage etype entity-data)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))))


(defn update-entity
  [{:keys [entity-type id data]} ctx]
  (let [storage (:storage ctx)
        etype (keyword @entity-type)
        entity-id @id
        entity-data @data]
    (if storage
      (sp/update-entity storage etype entity-id entity-data)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))))


(defn delete-entity
  [{:keys [entity-type id]} ctx]
  (let [storage (:storage ctx)
        etype (keyword @entity-type)
        entity-id @id]
    (if storage
      (do
        (sp/delete-entity storage etype entity-id)
        true)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage})))))


(defn list-all-graph-entities
  [_args ctx]
  (if-let [storage (:storage ctx)]
    ;; Use optimized batch query for VersionedStorage
    (let [base (if (instance? VersionedStorage storage)
                 (vs/query-all-graph-entities storage)
                 {:fns (vec (sp/query-entities storage :fn {}))
                  :args (vec (sp/query-entities storage :arg {}))})]
      ;; Also include namespace entities for frontend display
      (assoc base :namespaces (vec (sp/query-entities storage :ns {}))))
    (throw (ex-info "Storage not available in context"
                    {:type :execution-error/missing-storage}))))


;; === Handler Generators (Context-aware) ===
;; Note: all-entities-json-handler is now a fn-def in fns.edn
;; using :make-json-response-handler composition


(defn- entity-type-from-string
  [s]
  (case s
    "fn" :fn
    "arg" :arg
    nil))


(defn- render-entity-badge
  [entity-type-str]
  (html/badge {:badge-text entity-type-str :badge-type entity-type-str}))


;; Field specs for rendering - matches fn-defs in fns.edn
(def ^:private fn-field-specs
  [["ID" :id]
   ["Name" :name :keyword-to-str]
   ["Parent ID" :parent-id]
   ["Return Type" :return-type :keyword-to-str]
   ["Impl Hash" :impl-hash]])


(def ^:private arg-field-specs
  [["ID" :id]
   ["Name" :name :keyword-to-str]
   ["Fn ID" :fn-id]
   ["Type" :type :keyword-to-str]
   ["Required" :required :bool-to-yesno]
   ["Is Fn" :is-fn :bool-to-yesno]
   ["Source ID" :source-id]
   ["Value" :value :pr-str]
   ["Ref ID" :ref-id]])


(defn- render-entity-details
  "Renders entity details using entity-field-rows with field specs."
  [entity-type-str entity]
  (case entity-type-str
    "fn" (html/entity-field-rows {:entity entity :field-specs fn-field-specs})
    "arg" (html/entity-field-rows {:entity entity :field-specs arg-field-specs})
    [:p "Unknown entity type"]))


(defn render-entity-actions
  "Renders action buttons (Edit/Delete) for entity as hiccup."
  [{:keys [entity-type entity-id]}]
  [:div {:style "margin-top: 16px; display: flex; gap: 8px;"}
   [:button {:class "btn btn-primary"
             :hx-get (str "/partials/entity-form/" entity-type "/" entity-id)
             :hx-target "#details-content"
             :hx-swap "innerHTML"}
    "Edit"]
   [:button {:class "btn btn-danger"
             :hx-delete (str "/api/entities/" entity-type "/" entity-id)
             :hx-confirm "Are you sure you want to delete this entity?"
             :hx-target "#details-panel"
             :hx-swap "outerHTML"
             :_ "on htmx:afterRequest trigger entityDeleted on body"}
    "Delete"]])


(defn render-entity-details-view
  "Renders entity details view as hiccup.
   Extracts entity type and id from request path params, fetches entity from storage.
   Returns hiccup structure that will be rendered to HTML by make-html-response-handler."
  [{:keys [request]} ctx]
  (let [storage (:storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-id-str (get-in request [:path-params :id])
        entity-type (entity-type-from-string entity-type-str)]
    (if (and storage entity-type entity-id-str)
      (let [entity-id (java.util.UUID/fromString entity-id-str)
            entity (sp/read-entity storage entity-type entity-id)]
        (if entity
          [:div
           [:div {:style "margin-bottom: 12px;"}
            (render-entity-badge entity-type-str)]
           (render-entity-details entity-type-str entity)
           (render-entity-actions {:entity-type entity-type-str :entity-id entity-id-str})]
          [:p {:class "error"} "Entity not found"]))
      [:p {:class "error"} "Invalid request"])))


(defn- render-fn-form
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
      [:label {:for "parent-id"} "Parent (optional)"]
      [:select {:name "parent-id" :id "parent-id"}
       [:option {:value ""} "None"]
       (for [[id label] parent-options]
         [:option {:value (str id)
                   :selected (and entity (= id (:parent-id entity)))}
          label])]]
     [:div {:style "display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px;"}
      [:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
      [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]]))


(defn- render-arg-form
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


(defn render-entity-form-view
  "Renders entity form view as hiccup.
   Extracts entity type and optional id from request path params.
   Returns hiccup structure that will be rendered to HTML by make-html-response-handler."
  [{:keys [request]} ctx]
  (let [storage (:storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-id-str (get-in request [:path-params :id])
        entity-type (entity-type-from-string entity-type-str)]
    (if (and storage entity-type)
      (let [entity (when entity-id-str
                     (sp/read-entity storage entity-type
                                     (java.util.UUID/fromString entity-id-str)))
            all-fns (vec (sp/query-entities storage :fn {}))
            all-args (vec (sp/query-entities storage :arg {}))
            form-html (case entity-type-str
                        "fn" (render-fn-form entity all-fns)
                        "arg" (render-arg-form entity all-fns all-args)
                        [:p "Forms for " entity-type-str " not yet implemented"])]
        [:div
         [:h4 (if entity
                (str "Edit " entity-type-str)
                (str "Create " entity-type-str))]
         form-html])
      [:p {:class "error"} "Invalid entity type"])))


(defn parse-fn-from-form
  "Parses fn entity data from form submission."
  [{:keys [form-data]}]
  (cond-> {:name (keyword (:name form-data))}
    (not (str/blank? (:parent-id form-data)))
    (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))))


(defn parse-arg-from-form
  "Parses arg entity data from form submission."
  [{:keys [form-data]}]
  (cond-> {:name (keyword (:name form-data))
           :fn-id (java.util.UUID/fromString (:fn-id form-data))
           :type (keyword (:type form-data))}
    (not (str/blank? (:source-id form-data)))
    (assoc :source-id (java.util.UUID/fromString (:source-id form-data)))
    (not (str/blank? (:value form-data)))
    (assoc :value (json/parse-string (:value form-data) true))))


(defn- parse-entity-from-form
  "Dispatches to appropriate parser based on entity type."
  [entity-type-str form-data]
  (case entity-type-str
    "fn" (parse-fn-from-form {:form-data form-data})
    "arg" (parse-arg-from-form {:form-data form-data})
    nil))


(defn process-create-entity
  "Processes create entity request.
   Returns {:status :headers :body} for make-html-action-handler."
  [{:keys [request]} ctx]
  (let [storage (:storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-type (entity-type-from-string entity-type-str)
        body (:body request)
        form-data (when body
                    (into {}
                          (for [pair (str/split body #"&")
                                :let [[k v] (str/split pair #"=" 2)]
                                :when k]
                            [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")])))]
    (if (and storage entity-type form-data)
      (let [entity-data (parse-entity-from-form entity-type-str form-data)
            created (when entity-data
                      (sp/create-entity storage entity-type entity-data))]
        (if created
          {:status 200
           :headers {"HX-Trigger" "entityCreated"}
           :body "<p>Entity created successfully</p>"}
          {:status 400
           :body "<p class=\"error\">Failed to create entity</p>"}))
      {:status 400
       :body "<p class=\"error\">Invalid request</p>"})))


(defn process-delete-entity
  "Processes delete entity request.
   Returns {:status :headers :body} for make-html-action-handler with dynamic-response."
  [{:keys [request]} ctx]
  (let [storage (:storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-id-str (get-in request [:path-params :id])
        entity-type (entity-type-from-string entity-type-str)]
    (if (and storage entity-type entity-id-str)
      (let [entity-id (java.util.UUID/fromString entity-id-str)]
        (sp/delete-entity storage entity-type entity-id)
        {:status 200
         :headers {"HX-Trigger" "entityDeleted"}
         :body ""})
      {:status 400
       :body "<p class=\"error\">Invalid request</p>"})))


;; === Pure Functions ===

(defn get-path-param
  [{:keys [request param-name]}]
  (let [param-key (keyword param-name)]
    (get-in request [:path-params param-key])))


(defn get-query-param
  [{:keys [request param-name default]}]
  (let [query-string (:query-string request)
        params (when query-string
                 (into {}
                       (for [pair (str/split query-string #"&")
                             :let [[k v] (str/split pair #"=" 2)]
                             :when k]
                         [k (or v "")])))]
    (get params param-name default)))


(defn parse-form-body
  [{:keys [request]}]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (if (and body (str/includes? content-type "application/x-www-form-urlencoded"))
      (into {}
            (for [pair (str/split body #"&")
                  :let [[k v] (str/split pair #"=" 2)]
                  :when k]
              [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))
      {})))


(defn parse-json-body
  [{:keys [request]}]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (when (and body (str/includes? content-type "application/json"))
      (json/parse-string body true))))


(defn str-to-uuid
  [{:keys [string]}]
  (try
    (java.util.UUID/fromString string)
    (catch Exception _
      nil)))


;; === Registry ===
;; Context-aware functions are marked with :ctx metadata

(def impls
  {:list-entities (with-meta list-entities {:ctx true})
   :get-entity (with-meta get-entity {:ctx true})
   :create-entity (with-meta create-entity {:ctx true})
   :update-entity (with-meta update-entity {:ctx true})
   :delete-entity (with-meta delete-entity {:ctx true})
   :list-all-graph-entities (with-meta list-all-graph-entities {:ctx true})
   ;; Render/action functions for fn-def handlers
   :render-entity-details-view (with-meta render-entity-details-view {:ctx true})
   :render-entity-form-view (with-meta render-entity-form-view {:ctx true})
   :process-create-entity (with-meta process-create-entity {:ctx true})
   :process-delete-entity (with-meta process-delete-entity {:ctx true})
   ;; Pure render function (render-fn-details and render-arg-details are now fn-defs)
   :render-entity-actions render-entity-actions
   ;; Form parsing functions
   :parse-fn-from-form parse-fn-from-form
   :parse-arg-from-form parse-arg-from-form
   ;; Pure helper functions
   :get-path-param get-path-param
   :get-query-param get-query-param
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
