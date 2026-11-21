(ns graphden.crud.structured-input-change
  "Distinct edit and remove commands over an own structured literal."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.structured-input :as structured]
    [graphden.crud.structured-input-bind :as bind]
    [graphden.input.access :as access]
    [graphden.input.storage :as input-storage]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


(defn- reject!
  [kind message]
  (throw (ex-info message {:type kind})))


(defn- command-id
  [value]
  (or (cond (uuid? value) value (string? value) (parse-uuid value))
      (reject! :input/invalid-command "Supply a valid function, binding and version identity")))


(defn- normalize-command
  [action phase body]
  (let [fields (cond-> #{:fn-id :binding-id}
                 (= action :edit) (conj :value)
                 (= phase :apply) (conj :expected-version :expected-state))]
    (when-not (and (#{:edit :remove} action) (map? body) (= fields (set (keys body))))
      (reject! :input/invalid-command "Supply exactly the fields for this input change"))
    (cond-> (-> body (update :fn-id command-id) (update :binding-id command-id))
      (= phase :apply) (update :expected-version command-id))))


(defn- own-literal
  [ctx fn-id id]
  (or (structured/own-literal-row ctx fn-id id)
      (reject! :input/leaf-unavailable "This input binding is unavailable for editing")))


(defn- checked-change!
  [action ctx {:keys [fn-id binding-id value]} row]
  (when-not (structured/editable-binding? ctx fn-id binding-id)
    (reject! :input/leaf-unavailable "This input binding is unavailable for editing"))
  (let [without (update ctx :bindings dissoc binding-id)
        opened (structured/analyze without fn-id)
        address [[fn-id []] (:target-path row)]
        path (some #(when (= address (:address (val %))) (key %))
                   (get-in opened [:public-input :routes]))]
    (bind/visible-leaf opened path)
    (when (= action :edit)
      (when (= (:value row) value)
        (reject! :input/no-change "Supply a different literal value"))
      (let [edited (assoc-in ctx [:bindings binding-id :value] value)]
        (when-not (= :available (:status (structured/project edited fn-id)))
          (reject! :input/unproven-bind "The type of this binding could not be verified"))))))


(defn- plan
  [ctx storage action {:keys [fn-id binding-id expected-state] :as command} version]
  (let [row (own-literal ctx fn-id binding-id)
        _ (writer/assert-write-authorized! storage :input-binding {} binding-id)
        basis (bind/preview-basis storage ctx (-> command (dissoc :expected-version) (assoc :action action)))]
    (when (and version (not= expected-state basis))
      (reject! :input/stale-preview "Refresh the structured input before editing"))
    (checked-change! action ctx command row)
    {:owner (get-in ctx [:fns fn-id]) :expected-state basis
     :expected-version (or version
                           (:id (res/resolve-version (vs/unwrap storage) :input-binding binding-id
                                                     (vs/current-branch-id storage))))}))


(defn preview
  "Issue a branch- and action-bound preview for editing or removing an own literal."
  [ctx action request]
  (let [storage (request/require-storage ctx)
        {:keys [fn-id binding-id] :as command} (normalize-command action :preview request)]
    (bind/with-input-write storage
                           (fn [snapshot]
                             (merge {:ok true :action action :fn-id fn-id :binding-id binding-id}
                                    (select-keys (plan (bind/writable-context ctx snapshot fn-id) snapshot action command nil) [:expected-state :expected-version]))))))


(defn- apply-plan!
  [frozen storage action {:keys [fn-id binding-id value] :as command} version]
  (let [{:keys [owner]} (plan frozen storage action command (:id version))
        result (access/with-binding-write fn-id binding-id
                                          #(case action
                                             :edit (sp/update-entity storage :input-binding binding-id {:value value})
                                             :remove (binding [vs/*tombstone-delete?* true]
                                                       (sp/delete-entity storage :input-binding binding-id))))]
    (when-not result
      (reject! :input/write-refused "The input binding was not changed"))
    owner))


(defn apply!
  "Reauthorize and commit the exact edit or removal approved by preview."
  [ctx action request]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            {:keys [fn-id binding-id expected-version] :as command} (normalize-command action :apply request)
            owner (bind/with-input-write storage
                                         (fn [snapshot]
                                           (let [frozen (bind/writable-context ctx snapshot fn-id)]
                                             (own-literal frozen fn-id binding-id)
                                             (writer/assert-write-authorized! snapshot :input-binding {} binding-id)
                                             (apply-plan! frozen snapshot action command
                                                          (input-storage/current-binding! snapshot binding-id expected-version)))))]
        (bind/publish! ctx storage owner
                       {:ok true :committed true :action action :fn-id fn-id :binding-id binding-id})))))
