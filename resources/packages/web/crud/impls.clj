(ns graphden.packages.web.crud.impls
  "Implementations for web/crud base functions.

   Context-aware functions receive ctx as second argument.
   Pure functions receive only args map."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.web.html.impls :as html]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


;; === Helpers ===

(defn- parse-query-string
  [s]
  (when (and s (not (str/blank? s)))
    (into {} (for [pair (str/split s #"&")
                   :let [[k v] (str/split pair #"=" 2)]
                   :when k]
               [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))))


(defn- require-storage
  [ctx]
  (or (:storage ctx)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage}))))


(defn- entity-type-from-string
  [s]
  (case s "fn" :fn "arg" :arg nil))


(defn- extract-entity-params
  "Extracts type-str, id-str, entity-type from request path-params."
  [request]
  (let [type-str (get-in request [:path-params :type])
        id-str (get-in request [:path-params :id])]
    {:type-str type-str
     :id-str id-str
     :entity-type (entity-type-from-string type-str)}))


;; === Context-aware Query Functions ===

(defbase list-entities
  [entity-type where]
  (vec (sp/query-entities (require-storage ctx) (keyword entity-type) (or where {}))))


(defbase get-entity
  [entity-type id]
  (sp/read-entity (require-storage ctx) (keyword entity-type) id))


(defbase create-entity
  [entity-type data]
  (let [result (sp/create-entity (require-storage ctx) (keyword entity-type) data)]
    (exec-ctx/invalidate-graph-cache! ctx)
    result))


(defbase update-entity
  [entity-type id data]
  (let [result (sp/update-entity (require-storage ctx) (keyword entity-type) id data)]
    (exec-ctx/invalidate-graph-cache! ctx)
    result))


(defbase delete-entity
  [entity-type id]
  (sp/delete-entity (require-storage ctx) (keyword entity-type) id)
  (exec-ctx/invalidate-graph-cache! ctx)
  true)


(defbase list-all-graph-entities
  []
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


(defn- render-fn-form
  [entity all-fns]
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


(defn- render-arg-form
  [entity all-fns all-args]
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
  (let [storage (require-storage ctx)
        {:keys [type-str id-str entity-type]} (extract-entity-params request)]
    (if (and entity-type id-str)
      (if-let [entity (sp/read-entity storage entity-type (java.util.UUID/fromString id-str))]
        [:div
         [:div {:style "margin-bottom: 12px;"}
          (html/badge {:badge-text type-str :badge-type type-str})]
         (html/entity-field-rows {:entity entity
                                  :field-specs (case type-str "fn" fn-field-specs "arg" arg-field-specs)})
         (render-entity-actions {:entity-type type-str :entity-id id-str})]
        [:p {:class "error"} "Entity not found"])
      [:p {:class "error"} "Invalid request"])))


(defbase render-entity-form-view
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str id-str entity-type]} (extract-entity-params request)]
    (if entity-type
      (let [entity (when id-str (sp/read-entity storage entity-type (java.util.UUID/fromString id-str)))
            all-fns (vec (sp/query-entities storage :fn {}))
            all-args (vec (sp/query-entities storage :arg {}))]
        [:div
         [:h4 (str (if entity "Edit " "Create ") type-str)]
         (case type-str
           "fn" (render-fn-form entity all-fns)
           "arg" (render-arg-form entity all-fns all-args)
           [:p "Not implemented"])])
      [:p {:class "error"} "Invalid entity type"])))


;; === Form Parsing (pure) ===

(defbase parse-fn-from-form
  [form-data]
  (cond-> {:name (keyword (:name form-data))}
    (not (str/blank? (:parent-id form-data)))
    (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))))


(defbase parse-arg-from-form
  [form-data]
  (cond-> {:name (keyword (:name form-data))
           :fn-id (java.util.UUID/fromString (:fn-id form-data))
           :type (keyword (:type form-data))}
    (not (str/blank? (:source-id form-data)))
    (assoc :source-id (java.util.UUID/fromString (:source-id form-data)))
    (not (str/blank? (:value form-data)))
    (assoc :value (json/parse-string (:value form-data) true))))


;; === Action Handlers (context-aware) ===

(defbase process-create-entity
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str entity-type]} (extract-entity-params request)
        form-data (when (:body request)
                    (into {} (map (fn [[k v]] [(keyword k) v])
                                  (parse-query-string (:body request)))))]
    (if (and entity-type form-data)
      (let [entity-data (case type-str
                          "fn" (parse-fn-from-form {:form-data form-data})
                          "arg" (parse-arg-from-form {:form-data form-data})
                          nil)
            created (when entity-data (sp/create-entity storage entity-type entity-data))]
        (if created
          (do (exec-ctx/invalidate-graph-cache! ctx)
              {:status 200 :headers {"HX-Trigger" "entityCreated"}
               :body "<p>Entity created successfully</p>"})
          {:status 400 :body "<p class=\"error\">Failed to create entity</p>"}))
      {:status 400 :body "<p class=\"error\">Invalid request</p>"})))


(defbase process-delete-entity
  [request]
  (let [storage (require-storage ctx)
        {:keys [id-str entity-type]} (extract-entity-params request)]
    (if (and entity-type id-str)
      (do (sp/delete-entity storage entity-type (java.util.UUID/fromString id-str))
          (exec-ctx/invalidate-graph-cache! ctx)
          {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""})
      {:status 400 :body "<p class=\"error\">Invalid request</p>"})))


;; === Sequence operations =====================================================
;; Sequences live as anchor args (type=:sequence, source-id → base-fn template)
;; with a next_arg_id chain of item args. These helpers find the anchor, walk
;; the chain, and perform insert/remove/reorder by rewiring one or two refs.

(defn- find-sequence-anchor
  "Given a fn-id, returns the anchor arg (the one with type=:sequence).
   Assumes at most one sequence slot per fn (true for :list and :pairs->map)."
  [storage fn-id]
  (->> (sp/query-entities storage :arg {:fn-id fn-id})
       (some #(when (= :sequence (:type %)) %))))


(defn- walk-chain-from
  "Returns the ordered vector of item arg entities starting at `start-arg-id`
   (walks next-arg-id). Uses the supplied by-id index for O(1) lookup."
  [by-id start-arg-id]
  (loop [cur start-arg-id
         acc []
         depth 0]
    (cond
      (or (nil? cur) (> depth 10000)) acc
      :else
      (let [item (get by-id cur)]
        (if (nil? item)
          acc
          (recur (:next-arg-id item) (conj acc item) (inc depth)))))))


(defn- resolve-payload
  "Parses a sequence-op request body into {:value … :ref-id …}.
   Body shapes supported:
     {\"ref\":  \"fn-uuid-string\"} — ref to a fn by id
     {\"ref-name\": \"my-fn\"}      — ref resolved by fn-name
     {\"value\": <any JSON>}        — literal value"
  [storage body]
  (cond
    (contains? body :ref)
    {:value nil :ref-id (java.util.UUID/fromString (:ref body))}

    (contains? body :ref-name)
    (if-let [target (first (sp/query-entities storage :fn {:name (:ref-name body)}))]
      {:value nil :ref-id (:id target)}
      (throw (ex-info (str "Fn not found by name: " (:ref-name body))
                      {:type :sequence-op/fn-not-found :ref-name (:ref-name body)})))

    (contains? body :value)
    {:value (:value body) :ref-id nil}

    :else
    (throw (ex-info "Sequence op body requires :ref, :ref-name, or :value"
                    {:type :sequence-op/invalid-body :body body}))))


(defbase process-sequence-append
  "POST /api/sequence/:fn-id/append
   Body: {\"ref\"|\"ref-name\"|\"value\": …}
   Appends one item to the sequence of fn :fn-id."
  [request]
  (let [storage (require-storage ctx)
        fn-id-str (get-in request [:path-params :fn-id])
        fn-id (try (java.util.UUID/fromString fn-id-str) (catch Exception _ nil))
        body (when (:body request)
               (try (json/parse-string (:body request) true) (catch Exception _ nil)))]
    (cond
      (nil? fn-id)
      {:status 400 :body "<p class=\"error\">Invalid fn-id</p>"}

      (nil? body)
      {:status 400 :body "<p class=\"error\">JSON body required</p>"}

      :else
      (if-let [anchor (find-sequence-anchor storage fn-id)]
        (let [all-args (sp/query-entities storage :arg {:fn-id fn-id})
              by-id (into {} (map (juxt :id identity)) all-args)
              chain (walk-chain-from by-id (:next-arg-id anchor))
              tail (last chain)
              prev-id (if tail (:id tail) (:id anchor))
              {:keys [value ref-id]} (resolve-payload storage body)
              new-item {:id (random-uuid)
                        :fn-id fn-id
                        :source-id nil
                        :name nil
                        :type (or (:type anchor) :any)
                        :value value
                        :ref-id ref-id
                        :is-fn nil
                        :next-arg-id nil
                        :prev-arg-id prev-id}]
          (sp/create-entity storage :arg new-item)
          (if tail
            (sp/update-entities storage :arg
                                [(assoc tail :next-arg-id (:id new-item))])
            (sp/update-entities storage :arg
                                [(assoc anchor :next-arg-id (:id new-item))]))
          (exec-ctx/invalidate-graph-cache! ctx)
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:arg-id (:id new-item)
                                        :position (count chain)})})
        {:status 404 :body "<p class=\"error\">Fn has no sequence arg</p>"}))))


(defbase process-sequence-remove
  "DELETE /api/sequence/item/:item-id
   Removes one item, rewiring the predecessor's next-arg-id to the removed
   item's next-arg-id, and the successor's prev-arg-id back to the
   predecessor. Both lookups are O(1) via prev-arg-id/next-arg-id."
  [request]
  (let [storage (require-storage ctx)
        item-id-str (get-in request [:path-params :item-id])
        item-id (try (java.util.UUID/fromString item-id-str) (catch Exception _ nil))]
    (if (nil? item-id)
      {:status 400 :body "<p class=\"error\">Invalid item-id</p>"}
      (let [item (sp/read-entity storage :arg item-id)]
        (if (nil? item)
          {:status 404 :body "<p class=\"error\">Item not found</p>"}
          (let [prev-id (:prev-arg-id item)
                next-id (:next-arg-id item)
                predecessor (when prev-id (sp/read-entity storage :arg prev-id))
                successor (when next-id (sp/read-entity storage :arg next-id))
                updates (cond-> []
                          predecessor (conj (assoc predecessor :next-arg-id next-id))
                          successor (conj (assoc successor :prev-arg-id prev-id)))]
            (when (seq updates)
              (sp/update-entities storage :arg updates))
            (sp/delete-entity storage :arg item-id)
            (exec-ctx/invalidate-graph-cache! ctx)
            {:status 200 :body ""}))))))


;; === Pure Functions ===

(defbase parse-form-body
  [request]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (if (and body (str/includes? content-type "application/x-www-form-urlencoded"))
      (or (parse-query-string body) {})
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
   :render-entity-details-view render-entity-details-view
   :render-entity-form-view render-entity-form-view
   :process-create-entity process-create-entity
   :process-delete-entity process-delete-entity
   :process-sequence-append process-sequence-append
   :process-sequence-remove process-sequence-remove
   :render-entity-actions render-entity-actions
   :parse-fn-from-form parse-fn-from-form
   :parse-arg-from-form parse-arg-from-form
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
