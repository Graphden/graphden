(ns graphden.packages.web.crud.impls
  "Implementations for web/crud base functions.

   Context-aware functions receive ctx as second argument.
   Pure functions receive only args map."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.packages.core.strings.impls :as strings]
    [graphden.packages.web.html.impls :as html]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


;; === Helpers ===

(defn- require-storage [ctx]
  (or (:storage ctx)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage}))))

(defn- entity-type-from-string [s]
  (case s "fn" :fn "arg" :arg nil))


;; === Context-aware Query Functions ===

(defn list-entities
  [{:keys [entity-type where]} ctx]
  (vec (sp/query-entities (require-storage ctx) (keyword @entity-type) (or (when where @where) {}))))

(defn get-entity
  [{:keys [entity-type id]} ctx]
  (sp/read-entity (require-storage ctx) (keyword @entity-type) @id))

(defn create-entity
  [{:keys [entity-type data]} ctx]
  (sp/create-entity (require-storage ctx) (keyword @entity-type) @data))

(defn update-entity
  [{:keys [entity-type id data]} ctx]
  (sp/update-entity (require-storage ctx) (keyword @entity-type) @id @data))

(defn delete-entity
  [{:keys [entity-type id]} ctx]
  (sp/delete-entity (require-storage ctx) (keyword @entity-type) @id)
  true)

(defn list-all-graph-entities
  [_args ctx]
  (let [storage (require-storage ctx)
        base (if (instance? VersionedStorage storage)
               (vs/query-all-graph-entities storage)
               {:fns (vec (sp/query-entities storage :fn {}))
                :args (vec (sp/query-entities storage :arg {}))})]
    (assoc base :namespaces (vec (sp/query-entities storage :ns {})))))


;; === Rendering Helpers (private) ===
;; NOTE: fn-field-specs and arg-field-specs duplicate the fn-defs in fns.edn
;; (:fn-field-specs, :arg-field-specs). They must stay in sync. The duplication
;; exists because base-fn impls cannot resolve fn-defs at runtime.

(def ^:private fn-field-specs
  [["ID" :id] ["Name" :name :keyword-to-str] ["Parent ID" :parent-id]
   ["Return Type" :return-type :keyword-to-str] ["Impl Hash" :impl-hash]])

(def ^:private arg-field-specs
  [["ID" :id] ["Name" :name :keyword-to-str] ["Fn ID" :fn-id]
   ["Type" :type :keyword-to-str] ["Required" :required :bool-to-yesno]
   ["Is Fn" :is-fn :bool-to-yesno] ["Source ID" :source-id]
   ["Value" :value :pr-str] ["Ref ID" :ref-id]])

(defn- render-fn-form [entity all-fns]
  (let [editing? (some? entity)
        parent-options (into [["" "None"]]
                             (mapv (fn [f] [(str (:id f)) (name (:name f))]) all-fns))]
    [:form {:hx-post (if editing? (str "/api/entities/fn/" (:id entity)) "/api/entities/fn")
            :hx-target "#modal-content" :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     (html/form-input {:field-name "name" :label-text "Name"
                       :field-value (when entity (name (:name entity)))
                       :extra-attrs {:required true}})
     (html/form-select {:field-name "parent-id" :label-text "Parent (optional)"
                        :options parent-options
                        :selected-value (when entity (str (:parent-id entity)))})
     (html/button-row {:buttons [[:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
                                 [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]
                       :style {:display "flex" :gap "8px" :justify-content "flex-end" :margin-top "16px"}})]))

(defn- render-arg-form [entity all-fns all-args]
  (let [editing? (some? entity)
        fn-options (into [["" "Select function..."]]
                         (mapv (fn [f] [(str (:id f)) (name (:name f))]) all-fns))
        arg-options (into [["" "None (primary arg)"]]
                          (mapv (fn [a] [(str (:id a)) (name (:name a))]) all-args))
        type-options (mapv (fn [t] [t t]) ["int" "text" "bool" "uuid" "jsonb" "any" "fn"])]
    [:form {:hx-post (if editing? (str "/api/entities/arg/" (:id entity)) "/api/entities/arg")
            :hx-target "#modal-content" :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     (html/form-input {:field-name "name" :label-text "Name"
                       :field-value (when (and entity (:name entity)) (name (:name entity)))
                       :extra-attrs {:required true}})
     (html/form-select {:field-name "fn-id" :label-text "Function"
                        :options fn-options
                        :selected-value (when entity (str (:fn-id entity)))
                        :extra-attrs {:required true}})
     (html/form-select {:field-name "source-id" :label-text "Source Arg (for inheritance)"
                        :options arg-options
                        :selected-value (when entity (str (:source-id entity)))})
     (html/form-select {:field-name "type" :label-text "Type"
                        :options type-options
                        :selected-value (when (and entity (:type entity)) (name (:type entity)))
                        :extra-attrs {:required true}})
     (html/form-input {:field-name "value" :label-text "Value (JSON)"
                       :field-value (when entity (json/generate-string (:value entity)))})
     (html/button-row {:buttons [[:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
                                 [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]
                       :style {:display "flex" :gap "8px" :justify-content "flex-end" :margin-top "16px"}})]))


;; === Render View Functions (context-aware) ===

(defn render-entity-actions
  [{:keys [entity-type entity-id]}]
  [:div {:style "margin-top: 16px; display: flex; gap: 8px;"}
   [:button {:class "btn btn-primary"
             :hx-get (str "/partials/entity-form/" entity-type "/" entity-id)
             :hx-target "#details-content" :hx-swap "innerHTML"} "Edit"]
   [:button {:class "btn btn-danger"
             :hx-delete (str "/api/entities/" entity-type "/" entity-id)
             :hx-confirm "Are you sure you want to delete this entity?"
             :hx-target "#details-panel" :hx-swap "outerHTML"
             :_ "on htmx:afterRequest trigger entityDeleted on body"} "Delete"]])

(defn render-entity-details-view
  [{:keys [request]} ctx]
  (let [storage (require-storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-id-str (get-in request [:path-params :id])
        entity-type (entity-type-from-string entity-type-str)]
    (if (and entity-type entity-id-str)
      (let [entity (sp/read-entity storage entity-type (java.util.UUID/fromString entity-id-str))]
        (if entity
          [:div
           [:div {:style "margin-bottom: 12px;"}
            (html/badge {:badge-text entity-type-str :badge-type entity-type-str})]
           (html/entity-field-rows {:entity entity
                                    :field-specs (case entity-type-str
                                                   "fn" fn-field-specs
                                                   "arg" arg-field-specs)})
           (render-entity-actions {:entity-type entity-type-str :entity-id entity-id-str})]
          [:p {:class "error"} "Entity not found"]))
      [:p {:class "error"} "Invalid request"])))

(defn render-entity-form-view
  [{:keys [request]} ctx]
  (let [storage (require-storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-id-str (get-in request [:path-params :id])
        entity-type (entity-type-from-string entity-type-str)]
    (if entity-type
      (let [entity (when entity-id-str
                     (sp/read-entity storage entity-type (java.util.UUID/fromString entity-id-str)))
            all-fns (vec (sp/query-entities storage :fn {}))
            all-args (vec (sp/query-entities storage :arg {}))]
        [:div
         [:h4 (str (if entity "Edit " "Create ") entity-type-str)]
         (case entity-type-str
           "fn" (render-fn-form entity all-fns)
           "arg" (render-arg-form entity all-fns all-args)
           [:p "Not implemented"])])
      [:p {:class "error"} "Invalid entity type"])))


;; === Form Parsing (pure) ===

(defn parse-fn-from-form
  [{:keys [form-data]}]
  (cond-> {:name (keyword (:name form-data))}
    (not (str/blank? (:parent-id form-data)))
    (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))))

(defn parse-arg-from-form
  [{:keys [form-data]}]
  (cond-> {:name (keyword (:name form-data))
           :fn-id (java.util.UUID/fromString (:fn-id form-data))
           :type (keyword (:type form-data))}
    (not (str/blank? (:source-id form-data)))
    (assoc :source-id (java.util.UUID/fromString (:source-id form-data)))
    (not (str/blank? (:value form-data)))
    (assoc :value (json/parse-string (:value form-data) true))))


;; === Action Handlers (context-aware) ===

(defn process-create-entity
  [{:keys [request]} ctx]
  (let [storage (require-storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-type (entity-type-from-string entity-type-str)
        form-data (when (:body request)
                    (let [parsed (strings/parse-query-string-fn {:string (:body request)})]
                      (into {} (map (fn [[k v]] [(keyword k) v]) parsed))))]
    (if (and entity-type form-data)
      (let [entity-data (case entity-type-str
                          "fn" (parse-fn-from-form {:form-data form-data})
                          "arg" (parse-arg-from-form {:form-data form-data})
                          nil)
            created (when entity-data (sp/create-entity storage entity-type entity-data))]
        (if created
          {:status 200 :headers {"HX-Trigger" "entityCreated"}
           :body "<p>Entity created successfully</p>"}
          {:status 400 :body "<p class=\"error\">Failed to create entity</p>"}))
      {:status 400 :body "<p class=\"error\">Invalid request</p>"})))

(defn process-delete-entity
  [{:keys [request]} ctx]
  (let [storage (require-storage ctx)
        entity-type-str (get-in request [:path-params :type])
        entity-id-str (get-in request [:path-params :id])
        entity-type (entity-type-from-string entity-type-str)]
    (if (and entity-type entity-id-str)
      (do (sp/delete-entity storage entity-type (java.util.UUID/fromString entity-id-str))
          {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""})
      {:status 400 :body "<p class=\"error\">Invalid request</p>"})))


;; === Pure Functions ===

(defn get-path-param
  [{:keys [request param-name]}]
  (get-in request [:path-params (keyword param-name)]))

(defn get-query-param
  [{:keys [request param-name default]}]
  (let [params (strings/parse-query-string-fn {:string (:query-string request)})]
    (get params param-name default)))

(defn parse-form-body
  [{:keys [request]}]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (if (and body (str/includes? content-type "application/x-www-form-urlencoded"))
      (or (strings/parse-query-string-fn {:string body}) {})
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
    (catch Exception _ nil)))


;; === Registry ===

(def impls
  {:list-entities (with-meta list-entities {:ctx true})
   :get-entity (with-meta get-entity {:ctx true})
   :create-entity (with-meta create-entity {:ctx true})
   :update-entity (with-meta update-entity {:ctx true})
   :delete-entity (with-meta delete-entity {:ctx true})
   :list-all-graph-entities (with-meta list-all-graph-entities {:ctx true})
   :render-entity-details-view (with-meta render-entity-details-view {:ctx true})
   :render-entity-form-view (with-meta render-entity-form-view {:ctx true})
   :process-create-entity (with-meta process-create-entity {:ctx true})
   :process-delete-entity (with-meta process-delete-entity {:ctx true})
   :render-entity-actions render-entity-actions
   :parse-fn-from-form parse-fn-from-form
   :parse-arg-from-form parse-arg-from-form
   :get-path-param get-path-param
   :get-query-param get-query-param
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
