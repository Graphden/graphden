(ns graphden.crud.structured-input-bind
  "Authorize and check one named structured binding on a versioned owner."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.accounts.crypto :as crypto]
    [graphden.crud.entities :as entities]
    [graphden.crud.package-guard :as pkg]
    [graphden.crud.request :as request]
    [graphden.crud.structured-authoring :as authoring]
    [graphden.crud.structured-input :as structured]
    [graphden.crud.structured-run :as run]
    [graphden.input.access :as access]
    [graphden.input.authoring-principal :as principal]
    [graphden.input.context :as input]
    [graphden.input.migration :as migration]
    [graphden.input.read-view :as read-view]
    [graphden.input.storage :as input-storage]
    [graphden.input.surface :as surface]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.util.abort-shield :as shield]
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


(defn- command-id
  [id]
  (if (string? id) (parse-uuid id) id))


(defn- normalize-command
  [{:keys [fn-id path ref-fn-id] :as command}]
  (let [owner (command-id fn-id)
        reference? (contains? command :ref-fn-id)
        target (command-id ref-fn-id)]
    (when-not (and (uuid? owner) (vector? path) (<= 1 (count path) 64)
                   (not= reference? (contains? command :value))
                   (or (not reference?) (uuid? target))
                   (every? #{:fn-id :path :value :ref-fn-id :expected-state} (keys command)))
      (reject! :input/invalid-command "Supply a function, path and one literal or function reference"))
    (cond-> (assoc command :fn-id owner :path (mapv field-name path))
      reference? (assoc :ref-fn-id target))))


(defn- ordered-map-keys
  [value]
  (walk/postwalk (fn [v]
                   (if (map? v)
                     (into (sorted-map-by #(compare (migration/stable-edn %1) (migration/stable-edn %2))) v)
                     v))
                 value))


(defn preview-basis
  "Digest a value-free branch snapshot and the proposed command."
  [storage {:keys [fns fields members bindings markers]} command]
  (let [branch (vs/current-branch-id storage)
        base (vs/unwrap storage)
        version-anchors (fn [entity ids]
                          (let [versions (if (= entity :input-binding)
                                           (input-storage/binding-versions storage (update-vals bindings :fn-id))
                                           (res/resolve-version-ids base entity (vec ids) branch))]
                            (when-not (= (count ids) (count versions))
                              (reject! :input/stale-preview "Refresh the structured input before binding"))
                            versions))
        state [branch (tenancy/current-org) (:user-id (principal/current))
               (mapv (juxt :id :parent-ids) (sort-by (comp str :id) (vals fns)))
               (update-vals fields #(select-keys % [:name :type-fn-id :required]))
               (version-anchors :fn (keys fns))
               (version-anchors :fn-slot (map :id (mapcat val members)))
               (version-anchors :input-binding (keys bindings))
               markers (dissoc command :expected-state)]]
    (crypto/sha256-hex (migration/stable-edn (ordered-map-keys state)))))


(defn visible-leaf
  "Resolve a visible, free leaf in the function's own input scope."
  [graph path]
  (let [view (structured/project-analysis graph)
        wire (reduce (fn [node name] (some #(when (= name (:name %)) %) (:fields node))) view path)
        route (structured/free-leaf-route (get-in graph [:public-input :routes]) path)]
    (when-not (and (= :available (:status view)) wire (not (contains? wire :fields)) route)
      (reject! :input/leaf-unavailable "This input field is unavailable for binding"))
    route))


(defn- proposed-row
  [ctx graph {:keys [fn-id path ref-fn-id value]} id]
  (let [{[scope field-path :as address] :address} (visible-leaf graph path)
        form (reduce (fn [form key] {key form}) (if ref-fn-id [:ref ::target] [:value value]) (rseq path))
        bound (surface/bind-input ctx (:public-input graph) form {:name->id {::target ref-fn-id} :binding-id (constantly id)})
        {:keys [binding-id expression]} (get bound address)]
    (when-not (and (= id binding-id)
                   (if ref-fn-id
                     (and (= :ref (:kind expression)) (= ref-fn-id (:fn-id expression)))
                     (and (= :value (:kind expression)) (contains? expression :value))))
      (reject! :input/unproven-bind "The proposed binding could not be resolved"))
    (merge {:id id :fn-id fn-id :scope-path (second scope) :target-path field-path}
           (if ref-fn-id
             {:expression-kind "ref" :ref-fn-id ref-fn-id :value-present false}
             {:expression-kind "value" :value (:value expression) :value-present true}))))


(defn checked-row
  "Check the exact prospective row against the open field and its compiled postimage."
  [exec-ctx ctx {:keys [fn-id ref-fn-id] :as command} id]
  (let [row (input/call-with-types ctx #(proposed-row ctx (structured/analyze ctx fn-id) command id))
        after (assoc-in ctx [:bindings (:id row)] row)
        graph (if ref-fn-id (:analysis (run/runnable-input exec-ctx after fn-id)) (structured/analyze after fn-id))]
    (when-not (and (= :available (:status (input/call-with-types after #(structured/project-analysis graph))))
                   (or (nil? ref-fn-id)
                       (some #(and (= (:id row) (:binding-id %)) (= ref-fn-id (:target-fn-id %))
                                   (= :value (:mode %))) (:dependencies graph))))
      (reject! :input/unproven-bind "The type of this binding could not be verified"))
    row))


(defn reference-context
  "Read an authorized proposed target and owner on the same writer snapshot."
  [exec-ctx storage owner target]
  (when-let [guard (:execute-guard exec-ctx)] (guard exec-ctx target))
  (try
    (let [frozen (read-view/collect-roots storage [owner target] (tenancy/browser-source-policy storage))]
      (when-not (get-in frozen [:fns target :input-type-fn-id])
        (reject! :authz/forbidden "The selected reference is unavailable"))
      frozen)
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        (:input/unresolved-contract :storage-error/candidate-limit :storage-error/bounded-query-unsupported)
        (reject! :authz/forbidden "The selected reference is unavailable")
        (throw error)))))


(defn writable-context
  "Reauthorize the root and collect the sources visible on the writer connection."
  ([exec-ctx storage fn-id] (writable-context exec-ctx storage fn-id nil))
  ([exec-ctx storage fn-id detached]
   (writer/assert-write-authorized! storage :fn {} fn-id)
   (when-let [reason (pkg/write-rejection storage :fn {:id fn-id})]
     (reject! :authz/forbidden reason))
   (when-not (or access/*offline?* (and (:authenticated? (principal/current))
                                        (:input-type-fn-id (sp/read-entity storage :fn fn-id))))
     (reject! :authz/forbidden "Structured input editing is unavailable"))
   (let [policy (tenancy/browser-source-policy storage)
         frozen (if detached (read-view/collect-for-removal storage fn-id detached policy)
                    (read-view/collect storage fn-id policy))]
     (when-not (or access/*offline?* (authoring/editable-child? exec-ctx storage frozen fn-id))
       (reject! :authz/forbidden "Structured input editing is unavailable"))
     frozen)))


(defn- owner-plan
  [exec-ctx storage {:keys [fn-id ref-fn-id] :as command} apply?]
  (let [exec-ctx (assoc exec-ctx :storage storage)
        owner (writable-context exec-ctx storage fn-id)
        ctx (if ref-fn-id (reference-context exec-ctx storage fn-id ref-fn-id) owner)
        basis (preview-basis storage ctx command)
        _ (when (and apply? (not= basis (:expected-state command)))
            (reject! :input/stale-preview "Refresh the structured input before binding"))
        row (checked-row exec-ctx ctx command (random-uuid))
        proof (input-storage/binding-proof storage :bind row nil)]
    (input-storage/binding-call storage :check fn-id (:id row) proof #(writer/assert-write-authorized! storage :input-binding row nil))
    {:basis basis :row row :proof proof :owner (get-in ctx [:fns fn-id])}))


(defn with-input-write
  "Run a binding preview or create under the ordinary writer and binding lock."
  [storage f]
  (binding [vs/*enforce-require-merge?* true]
    (input-storage/with-binding-transaction! storage f)))


(defn- with-plan
  [exec-ctx storage command apply? f]
  (with-input-write storage #(f % (owner-plan exec-ctx % command apply?))))


(defn publish!
  "Publish a successful committed input mutation as a function invalidation."
  [ctx storage owner result]
  (try
    (entities/publish-write! ctx storage :fn owner)
    result
    (catch Exception _
      (assoc result :publication-warning "The binding committed. Refresh the editor to see it."))))


(defn preview
  "Compute an exact proposal on the current branch without writing any row."
  [ctx request]
  (let [{:keys [fn-id path] :as command} (normalize-command request)]
    (with-plan ctx (request/require-storage ctx) command false
      (fn [_ {:keys [basis]}] {:ok true :fn-id fn-id :path path :expected-state basis}))))


(defn bind!
  "Revalidate the proposed binding and its preview basis under the writer lock, then
   insert a binding version. The caller supplies the same command as preview."
  [ctx request]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            command (normalize-command request)
            {:keys [binding owner]} (with-plan ctx storage command true
                                      (fn [snapshot {:keys [row owner proof]}]
                                        {:binding (or (input-storage/binding-call
                                                        snapshot :write (:id owner) (:id row) proof
                                                        #(access/with-binding-write (:id owner) (:id row) (fn [] (sp/create-entity snapshot :input-binding row))))
                                                      (reject! :input/write-refused "The input binding was not written"))
                                         :owner owner}))
            result {:ok true :committed true :fn-id (:id owner) :binding-id (:id binding)}]
        (publish! ctx storage owner result)))))
