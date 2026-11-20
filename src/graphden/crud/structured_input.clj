(ns graphden.crud.structured-input
  "Viewer-safe structured input projection for the standard graph subtree."
  (:require
    [graphden.crud.package-guard :as pkg]
    [graphden.executor.browser-source :as source]
    [graphden.input.access :as access]
    [graphden.input.analysis :as analysis]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.read-view :as read-view]
    [graphden.storage.graph-writer :as writer]
    [graphden.types.check :as check]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]))


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


(defn project-analysis
  "Serialize remaining field names, types and requiredness. The fixture can
   opt into named bind paths verified by the checked contract."
  ([graph] (project-analysis graph false))
  ([graph editable?]
   (when graph
     (if (or (not= :checked (:status graph))
             (seq (:obligations graph))
             (not (get-in graph [:coverage :recursive-availability]))
             (some #(#{:graph-callable :callable-producer} (:mode %)) (:dependencies graph)))
       {:status :unavailable}
       (let [{:keys [root routes]} (:public-input graph)]
         (when root
           (some-> (wire-node (when editable? routes) [] root) (assoc :status :available))))))))


(defn project
  [ctx root]
  (project-analysis (analyze ctx root)))


(defn- fixture-editable?
  [storage root]
  (when (and access/*offline?* (vs/versioned-storage? storage)
             (not (pkg/package-owned-fn? storage root)))
    (try
      (binding [vs/*enforce-require-merge?* true]
        (writer/assert-write-authorized! storage :fn {} root)
        true)
      (catch clojure.lang.ExceptionInfo error
        (case (:type (ex-data error))
          (:authz/forbidden :authz/branch-protected :branch/merge-required) false
          (throw error))))))


(defn read-input
  "Recheck the visible root in the same snapshot as its source permissions
   and v2 closure. Failed authorization omits the projection entirely."
  [storage root]
  (try
    (source/with-snapshot storage
                          (fn [snapshot authorize!]
                            (let [graph (analyze (read-view/collect snapshot root authorize!) root)]
                              (project-analysis graph (fixture-editable? snapshot root)))))
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        :authz/forbidden nil
        (:input/unresolved-contract :input/invalid-binding :input/invalid-row
                                    :types/check-failed :browser-plan/unsupported
                                    :storage-error/candidate-limit :storage-error/bounded-query-unsupported)
        {:status :unavailable}
        (throw error)))))
