(ns graphden.crud.structured-input
  "Viewer-safe structured input projection for the standard graph subtree."
  (:require
    [graphden.crud.package-guard :as pkg]
    [graphden.crud.structured-authoring :as authoring]
    [graphden.crud.structured-run :as run]
    [graphden.executor.browser-source :as source]
    [graphden.input.access :as access]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.read-view :as read-view]
    [graphden.input.rows :as rows]
    [graphden.storage.graph-writer :as writer]
    [graphden.types.check :as check]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


(defn- signature-view
  [ctx]
  ;; A stored explicit return type supports value-reference analysis. Richer
  ;; inferred callable contracts need their own snapshot evidence.
  (let [targets (into #{} (mapcat #(keep % [:ref-fn-id :resolver-fn-id])) (vals (:bindings ctx)))]
    (into {}
          (keep (fn [id]
                  (when-let [return-id (get-in ctx [:fns id :return-type-fn-id])]
                    [id {:return (:type (descriptor/resolve-descriptor ctx return-id))}])))
          targets)))


(defn- concealed-type?
  [type]
  (or (types/contains-secret? type) (types/contains-hide-result-marker? type)))


(defn- concealed-container?
  [type]
  (cond
    (types/refine-type? type) (recur (types/refine-base type))
    (types/marker-type? type) (or (concealed-type? [(types/marker-tag type) :any])
                                  (recur (types/marker-inner type)))
    :else false))


(defn free-leaf-route
  "Select an own, free field addressed by the declared public names."
  [routes path]
  (let [route (get routes path)]
    (when (and (<= 1 (count path) 64) (= :input-field (:kind route))
               (:whole-bindable? route) (empty? (get-in route [:address 0 1])))
      route)))


(defn- wire-node
  [editable-routes path {:keys [type fields result-type] :as node}]
  (when-not (or (concealed-type? result-type)
                (if fields (concealed-container? type) (concealed-type? type)))
    (let [info (select-keys node [:kind :name :required :required-now?])]
      (if fields
        (cond-> (assoc info :fields (into [] (keep #(wire-node editable-routes (conj path (:name %)) %)) fields))
          (= :reference-inputs (:kind node)) (dissoc :required :required-now?))
        (cond-> (assoc info :type type)
          (free-leaf-route editable-routes path) (assoc :bind-path path))))))


(defn analyze
  "Check a frozen stored declaration with its witnessed return types."
  [ctx root]
  (when (get-in ctx [:fns root :input-type-fn-id])
    (analysis/analyze-effective-input (assoc ctx :signatures (signature-view ctx)) root
                                      {:check-expression check/check-input-binding})))


(defn- checked-projection?
  [graph]
  (and (= :checked (:status graph))
       (empty? (:obligations graph))
       (get-in graph [:coverage :recursive-availability])
       (not-any? #(#{:graph-callable :callable-producer} (:mode %)) (:dependencies graph))))


(defn project-analysis
  "Serialize remaining field names, types and requiredness. Editable projections
   include named bind paths verified by the checked contract."
  ([graph] (project-analysis graph false))
  ([graph editable?]
   (when graph
     (if (checked-projection? graph)
       (let [{:keys [root routes]} (:public-input graph)]
         (when root
           (some-> (wire-node (when editable? routes) [] root) (assoc :status :available))))
       {:status :unavailable}))))


(defn project
  [ctx root]
  (context/call-with-types ctx #(project-analysis (analyze ctx root))))


(defn own-literal-row
  "An unscoped literal authored on this owner."
  [ctx root id]
  (let [row (some-> (get-in ctx [:bindings id]) rows/normalize)]
    (when (and (= root (:fn-id row)) (empty? (:scope-path row))
               (= "value" (:expression-kind row)) (nil? (:resolver-fn-id row)))
      row)))


(defn- occupied-leaf
  [ctx root scope address path node declared]
  (let [{:keys [binding-id origin-fn-id expected-type]} (get-in scope [:bindings address])
        row (when (= root origin-fn-id) (own-literal-row ctx root binding-id))]
    (when (and row (= (second address) (:target-path row))
               (<= 1 (count path) 64) (nil? (:fields declared))
               (= :checked (get-in scope [:proofs address :status]))
               (some? expected-type) (= expected-type (:type node))
               (not-any? concealed-type? [expected-type (:type declared)]))
      {:path path :type expected-type :binding-id binding-id})))


(defn- visible-occupied-leaves
  [ctx {:keys [fn-id hidden-input-addresses] :as graph}]
  (let [{:keys [configured-contract declared-contract] :as scope} (get-in graph [:scopes []])]
    (letfn [(visit
              [ids names node declared]
              (let [address [[fn-id []] ids]]
                (when (and node declared (some? (:type declared)) (some? (:type node))
                           (not (contains? hidden-input-addresses address)))
                  (if-let [fields (:fields node)]
                    (when-not (or (concealed-container? (:type node))
                                  (concealed-container? (:type declared)))
                      (let [declared-fields (into {} (map (juxt :id identity)) (:fields declared))]
                        (mapcat (fn [{:keys [id name] :as child}]
                                  (visit (conj ids id) (conj names name) child (get declared-fields id)))
                                fields)))
                    (when-let [fact (occupied-leaf ctx fn-id scope address names node declared)]
                      [fact])))))]
      (vec (visit [] [] configured-contract declared-contract)))))


(defn- occupied-editor-fields
  [storage ctx graph]
  (let [fields (visible-occupied-leaves ctx graph)
        versions (res/resolve-version-ids (vs/unwrap storage) :input-binding
                                          (mapv :binding-id fields) (vs/current-branch-id storage))]
    (into [] (keep (fn [field]
                     (when-let [version (get versions (:binding-id field))]
                       (assoc field :version-id version)))) fields)))


(defn editable-binding?
  "Recheck that the live preimage still contains this visible own leaf."
  [ctx root id]
  (context/call-with-types
    ctx
    (fn []
      (let [graph (analyze ctx root)]
        (and (checked-projection? graph)
             (some #(= id (:binding-id %)) (visible-occupied-leaves ctx graph)))))))


(defn- editing-authorized?
  [exec-ctx storage ctx root]
  (when (and (or access/*offline?* (authoring/editable-child? exec-ctx storage ctx root)) (vs/versioned-storage? storage)
             (not (pkg/package-owned-fn? storage root)))
    (try
      (binding [vs/*enforce-require-merge?* true]
        (writer/assert-write-authorized! storage :fn {} root)
        (writer/assert-write-authorized! storage :input-binding {:fn-id root} nil)
        true)
      (catch clojure.lang.ExceptionInfo error
        (case (:type (ex-data error))
          (:authz/forbidden :authz/branch-protected :branch/merge-required) false
          (throw error))))))


(defn- repeat-basis
  [storage {:keys [free-slots graph-hash]} fields]
  (let [branch-id (vs/current-branch-id storage)
        names (mapv :name fields)
        slots (select-keys free-slots names)
        ids (vals slots)]
    (when (and (uuid? branch-id) (string? graph-hash)
               (= (count names) (count free-slots) (count slots) (count (set ids)))
               (every? uuid? ids))
      {:branch-id branch-id :graph-hash graph-hash :slots slots})))


(defn read-input
  "Recheck the visible root in the same snapshot as its source permissions
   and v2 closure. Set `editor?` false for a read-only consumer."
  ([storage root] (read-input storage root true))
  ([storage root editor?] (read-input storage root editor? nil))
  ([storage root editor? exec-ctx]
   (try
     (source/with-snapshot storage
                           (fn [snapshot authorize!]
                             (let [ctx (read-view/collect snapshot root authorize!)
                                   runnable (when exec-ctx (run/runnable-input exec-ctx ctx root))
                                   graph (or (:analysis runnable) (analyze ctx root))
                                   editable? (and editor? (editing-authorized? exec-ctx snapshot ctx root))]
                               (context/call-with-types
                                 ctx
                                 (fn []
                                   (let [view (project-analysis graph editable?)
                                         runnable? (and runnable (= :available (:status view)))
                                         basis (when runnable? (repeat-basis snapshot runnable (:fields view)))]
                                     (cond-> view
                                       runnable? (assoc :run-available true)
                                       (and editor? runnable?) (merge (authoring/root-extend-offer exec-ctx snapshot ctx root))
                                       basis (assoc :run-repeat basis)
                                       (and editable? (= :available (:status view)))
                                       (assoc :occupied (occupied-editor-fields snapshot ctx graph)))))))))
     (catch clojure.lang.ExceptionInfo error
       (case (:type (ex-data error))
         :authz/forbidden nil
         (:input/unresolved-contract :input/invalid-binding :input/invalid-row
                                     :types/check-failed :browser-plan/unsupported
                                     :storage-error/candidate-limit :storage-error/bounded-query-unsupported)
         {:status :unavailable}
         (throw error))))))
