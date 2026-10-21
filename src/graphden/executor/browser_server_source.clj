(ns graphden.executor.browser-server-source
  "Prove the first server callable is a closed read-only graph. Native
   implementation identities, never authored effects alone, establish reads."
  (:require
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server-values :as values]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check.provenance :as provenance]))


(def ^:private reader-id (ids/fn-id "storage.branches" :current-branch-name))


(defn- reject!
  [reason]
  (throw (ex-info "Server callable is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- native!
  [ctx row]
  (let [id (:id row)
        signature (registry/rich-type-of-id id)
        reader? (= reader-id id)]
    (when-not (and (owned/owned-fn-id? id)
                   (or reader?
                       (and (capabilities/browser-op id)
                            (not= capabilities/server-call-id id)
                            (= #{} (:effects signature))))
                   (owned/owned-impl? id (get (:base-fns ctx) (keyword (:name row)))))
      (reject! :server-not-read-only))))


(defn- closed-captures!
  [target graph]
  (let [index (lookups/build-lookups graph)
        free (surface/public-free-entries target index)]
    ;; Server configuration must be stored on the target. No browser frame,
    ;; dynamic producer, callable input or surrounding capture can supply it.
    (when-not (and (= 1 (count free))
                   (= :input (:ext-name (first free))))
      (reject! :server-captures))
    (doseq [binding (:bindings graph)]
      (when (:resolver-fn-id binding)
        (reject! :server-captures))
      (when (some? (:value binding)) (wire/encode-value (:value binding))))))


(defn capture
  "Read/check the complete server closure inside the caller's snapshot.
   Returns server-only evidence and a frozen input/output contract."
  [ctx storage authorize! policy target]
  (let [row (sp/read-entity storage :fn target)
        _ (when-not row (reject! :server-target-missing))
        ;; Only the target execute grant authorizes this private inspection.
        ;; Dependency rows never enter the browser source/export graph.
        _ (authorize! row {:execute? true :server-target? true})
        graph (source/collect-closure storage [target] (constantly nil))
        signature (get-in policy [:rich :by-id target])
        captured {:graph graph :namespaces (source/collect-namespaces storage graph)}
        natives (filter #(and (empty? (:parent-ids %)) (:return-type-fn-id %)) (:fns graph))]
    (doseq [row natives] (native! ctx row))
    (when-not (and signature (= [:input] (:lambda-params signature))
                   (set? (:execution signature))
                   (contains? (:execution signature) :jvm-server)
                   (set? (:effects signature))
                   (every? #{:db} (:effects signature)))
      (reject! :server-contract))
    (closed-captures! target graph)
    (let [input-type (values/contract (get-in signature [:args :input]))
          output-type (values/contract (:return signature))
          native-signatures (select-keys (get-in policy [:rich :by-id]) (map :id natives))
          captured (assoc captured :server-signatures native-signatures)]
      ;; Fresh reconstruction and frozen marker provenance must both permit
      ;; every row. A signature copied from another source is insufficient.
      (snapshot/validate-source! captured target policy)
      {:target target :graph graph :signature signature :input-type input-type :output-type output-type
       :fingerprint (provenance/fingerprint-data
                      {:graph (into {} (map (fn [[k rows]] [k (vec (sort-by (comp str :id) rows))])) graph)
                       :contract [input-type output-type]
                       :native-impls (into {} (map (fn [row]
                                                     [(:id row) (get (:base-fns ctx) (keyword (:name row)))])) natives)})})))
