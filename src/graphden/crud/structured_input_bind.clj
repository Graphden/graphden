(ns graphden.crud.structured-input-bind
  "Fixture-gated preview and creation of one structured literal binding."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.accounts.crypto :as crypto]
    [graphden.crud.entities :as entities]
    [graphden.crud.package-guard :as pkg]
    [graphden.crud.request :as request]
    [graphden.crud.structured-input :as structured]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.input.read-view :as read-view]
    [graphden.input.storage :as input-storage]
    [graphden.input.surface :as surface]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


(defn- reject!
  [kind message]
  (throw (ex-info message {:type kind})))


(defn- field-name
  [segment]
  (when-not (and (or (keyword? segment) (string? segment))
                 (not (str/blank? (str segment))))
    (reject! :input/invalid-command "Supply a path of declared input names"))
  (if (keyword? segment) segment (keyword segment)))


(defn- normalize-command
  [{:keys [fn-id path] :as command}]
  (let [id (if (string? fn-id) (parse-uuid fn-id) fn-id)]
    (when-not (and (uuid? id) (vector? path) (<= 1 (count path) 64)
                   (contains? command :value)
                   (every? #{:fn-id :path :value :expected-state} (keys command)))
      (reject! :input/invalid-command "Supply a function, path and explicit literal value"))
    (assoc command :fn-id id :path (mapv field-name path))))


(defn- ordered-map-keys
  [value]
  (walk/postwalk (fn [v]
                   (if (map? v)
                     (into (sorted-map-by #(compare (migration/stable-edn %1) (migration/stable-edn %2))) v)
                     v))
                 value))


(defn- preview-basis
  [storage {:keys [fns fields members bindings markers]} command]
  (let [branch (vs/current-branch-id storage)
        base (vs/unwrap storage)
        version-anchors (fn [entity ids]
                          (let [versions (res/resolve-version-ids base entity (vec ids) branch)]
                            (when-not (= (count ids) (count versions))
                              (reject! :input/stale-preview "Refresh the structured input before binding"))
                            versions))
        state [branch (tenancy/current-org) (:user-id tenancy/*current-principal*)
               (mapv (juxt :id :parent-ids) (sort-by (comp str :id) (vals fns)))
               (update-vals fields #(select-keys % [:name :type-fn-id :required]))
               (version-anchors :fn (keys fns))
               (version-anchors :fn-slot (map :id (mapcat val members)))
               (version-anchors :input-binding (keys bindings))
               markers (dissoc command :expected-state)]]
    (crypto/sha256-hex (migration/stable-edn (ordered-map-keys state)))))


(defn- visible-leaf
  [graph path]
  (let [view (structured/project-analysis graph)
        wire (reduce (fn [node name] (some #(when (= name (:name %)) %) (:fields node))) view path)
        route (get-in graph [:public-input :routes path])]
    (when-not (and (= :available (:status view)) wire (not (contains? wire :fields))
                   (= :input-field (:kind route)) (:whole-bindable? route)
                   (empty? (get-in route [:address 0 1])))
      (reject! :input/leaf-unavailable "This input field is unavailable for binding"))
    route))


(defn- checked-row
  [ctx {:keys [fn-id path value]}]
  (let [graph (structured/analyze ctx fn-id)
        {[scope field-path :as address] :address} (visible-leaf graph path)
        id (random-uuid)
        form (reduce (fn [form key] {key form}) [:value value] (rseq path))
        bound (surface/bind-input ctx (:public-input graph) form {:name->id {} :binding-id (constantly id)})
        {:keys [binding-id expression]} (get bound address)
        _ (when-not (and (= id binding-id) (= :value (:kind expression)) (contains? expression :value))
            (reject! :input/unproven-bind "The proposed literal could not be resolved"))
        row {:id id :fn-id fn-id :scope-path (second scope) :target-path field-path
             :expression-kind "value" :value (:value expression) :value-present true}
        postimage (structured/project (assoc-in ctx [:bindings id] row) fn-id)]
    (when-not (= :available (:status postimage))
      (reject! :input/unproven-bind "The type of this binding could not be verified"))
    row))


(defn- owner-plan
  [storage {:keys [fn-id] :as command} apply?]
  (writer/assert-write-authorized! storage :fn {} fn-id)
  (when-let [reason (pkg/write-rejection storage :fn {:id fn-id})]
    (reject! :authz/forbidden reason))
  (let [ctx (read-view/collect storage fn-id (tenancy/browser-source-policy storage))
        basis (preview-basis storage ctx command)
        _ (when (and apply? (not= basis (:expected-state command)))
            (reject! :input/stale-preview "Refresh the structured input before binding"))
        row (checked-row ctx command)]
    (writer/assert-write-authorized! storage :input-binding row nil)
    {:basis basis :row row :owner (get-in ctx [:fns fn-id])}))


(defn- with-plan
  [storage {:keys [fn-id] :as command} apply? f]
  (access/assert-write! :input-binding {})
  (when-not (and (vs/versioned-storage? storage) (uuid? (vs/current-branch-id storage)))
    (reject! :input/version-required "Structured bindings require a versioned branch"))
  (tx/assert-owns-commit! storage)
  (binding [vs/*enforce-require-merge?* true]
    (writer/with-write [storage {:entity :fn :ids [fn-id]}]
                       (input-storage/lock-bindings! storage)
                       (binding [res/*branch-chain-cache* (atom {})
                                 branch-local/*storage-caches-override* (atom {})]
                         (res/call-with-fresh-memos #(f storage (owner-plan storage command apply?)))))))


(defn preview
  "Compute an exact proposal on the current branch without writing any row."
  [ctx request]
  (let [{:keys [fn-id path] :as command} (normalize-command request)]
    (with-plan (request/require-storage ctx) command false
      (fn [_ {:keys [basis]}] {:ok true :fn-id fn-id :path path :expected-state basis}))))


(defn bind!
  "Revalidate one literal and its preview basis under the writer lock, then
   insert a binding version. The caller supplies the same command as preview."
  [ctx request]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            command (normalize-command request)
            {:keys [binding owner]} (with-plan storage command true
                                      (fn [snapshot {:keys [row owner]}]
                                        {:binding (or (sp/create-entity snapshot :input-binding row)
                                                      (reject! :input/write-refused "The input binding was not written"))
                                         :owner owner}))
            result {:ok true :committed true :fn-id (:id owner) :binding-id (:id binding)}]
        (try
          (entities/publish-write! ctx storage :fn owner)
          result
          (catch Exception _
            (assoc result :publication-warning "The binding committed. Refresh the editor to see it.")))))))
