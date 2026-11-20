(ns graphden.crud.structured-input
  "Viewer-safe structured input projection for the standard graph subtree."
  (:require
    [graphden.executor.browser-source :as source]
    [graphden.input.analysis :as analysis]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.read-view :as read-view]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


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


(defn- wire-node
  [{:keys [type fields result-type] :as node}]
  (when-not (or (concealed-type? result-type)
                (if fields (concealed-container? type) (concealed-type? type)))
    (let [info (select-keys node [:kind :name :required :required-now?])]
      (if fields
        (cond-> (assoc info :fields (into [] (keep wire-node) fields))
          (= :reference-inputs (:kind node)) (dissoc :required :required-now?))
        (assoc info :type type)))))


(defn project
  "Serialize only remaining field names, types and requiredness. Keep bound
   values, address ledgers, hidden fields and runtime contracts on the server."
  [ctx root]
  (when (get-in ctx [:fns root :input-type-fn-id])
    (let [ctx (assoc ctx :signatures (signature-view ctx))
          graph (analysis/analyze-effective-input ctx root {:check-expression check/check-input-binding})
          incomplete? (or (not (get-in graph [:coverage :recursive-availability]))
                          (some #(#{:graph-callable :callable-producer} (:mode %)) (:dependencies graph)))]
      (if incomplete?
        {:status :unavailable}
        (some-> (get-in graph [:public-input :root]) wire-node (assoc :status :available))))))


(defn read-input
  "Recheck the visible root in the same snapshot as its source permissions
   and v2 closure. Failed authorization omits the projection entirely."
  [storage root]
  (try
    (source/with-snapshot storage
                          (fn [snapshot authorize!]
                            (project (read-view/collect snapshot root authorize!) root)))
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        :authz/forbidden nil
        (:input/unresolved-contract :input/invalid-binding :input/invalid-row
                                    :types/check-failed :browser-plan/unsupported
                                    :storage-error/candidate-limit :storage-error/bounded-query-unsupported)
        {:status :unavailable}
        (throw error)))))
