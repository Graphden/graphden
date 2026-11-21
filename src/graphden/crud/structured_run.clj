(ns graphden.crud.structured-run
  "Prepare named Run from authorized, versioned input rows and boot-owned native code."
  (:require
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.browser-server-values :as values]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as executor]
    [graphden.input.arguments :as arguments]
    [graphden.input.compile :as compile]
    [graphden.input.context :as input]
    [graphden.input.read-view :as view]
    [graphden.packages.owned :as owned]
    [graphden.types.check.provenance :as provenance]))


(defn- trusted-roots
  [ctx frozen]
  (let [base-implementations (:base-fns ctx)
        signatures (::owned/native-signatures (meta base-implementations))]
    (into {}
          (keep (fn [[id {:keys [name]}]]
                  (when-let [signature (get signatures id)]
                    (let [impl (get base-implementations (keyword name))]
                      (when (owned/owned-impl? id impl)
                        [id {:impl impl :signature signature}])))))
          (:fns frozen))))


(defn- prepare
  "The HTTP result and argument-history boundaries require plain value types.
   Prepare only completely checked pure graphs with finite public contracts."
  [ctx frozen root]
  (let [{:keys [analysis invoke]} (compile/compile-stored frozen root (trusted-roots ctx frozen))
        surface (:public-input analysis)
        result-type (input/call-with-types
                      frozen
                      (fn []
                        (doseq [{:keys [signature configured-contract]} (vals (:scopes analysis))]
                          (when-not (and (= #{} (:effects signature))
                                         (set? (:execution signature))
                                         (contains? (:execution signature) :jvm-server))
                            (input/reject! :unsupported-run-signature {}))
                          (values/contract (:type configured-contract))
                          (values/contract (:return signature)))
                        (values/contract (get-in analysis [:scopes [] :signature :return]))))]
    {:analysis analysis
     :invoke (executor/named-input-closure
               (fn [args context] (values/project result-type (invoke args context))))
     :surface surface
     :free-slots (into {} (map (fn [{:keys [name origin-field-id result-address]}]
                                 [name (or origin-field-id (peek (second result-address)))]))
                       (get-in surface [:root :fields]))}))


(defn rejection
  [error]
  (let [{:keys [type code]} (ex-data error)]
    (cond
      (= :authz/forbidden type)
      {:ok false :status :rejected :http-status 403 :error "Forbidden" :error-data {:reason :forbidden}}
      (and (= :input/compile-rejected type) (#{:unknown-input-field :record-input-required} code))
      {:ok false :status :rejected :http-status 400 :error "Invalid structured arguments"
       :error-data {:reason :invalid-structured-arguments :code code}}
      (contains? #{:input/unresolved-contract :input/compile-rejected :input/invalid-binding :input/invalid-row
                   :types/unsupported-validator :types/check-failed :browser-plan/unsupported
                   :storage-error/candidate-limit :storage-error/bounded-query-unsupported} type)
      {:ok false :status :rejected :http-status 400 :error "Structured input is unavailable"
       :error-data {:reason :structured-input-unavailable}}
      :else (throw error))))


(defn runnable-analysis
  [ctx frozen root]
  (try
    (when-let [guard (:execute-guard ctx)] (guard ctx root))
    (:analysis (prepare ctx frozen root))
    (catch clojure.lang.ExceptionInfo error (rejection error) nil)))


(defn- source-hash
  [{:keys [fns fields members bindings]}]
  ;; Compare source identities, field order and semantic values. Storage row
  ;; timestamps and the SQL order of parallel membership reads are immaterial.
  (let [source-row #(dissoc % :created-at :updated-at :deleted-at)]
    (provenance/fingerprint-data
      [(update-vals fns source-row) (update-vals fields source-row)
       (update-vals members #(->> % (sort-by (juxt :position :slot-id)) (mapv source-row)))
       (update-vals bindings source-row)])))


(defn plan
  "Reauthorize the frozen source when a queued run reaches its worker.
   A changed branch closure must be submitted again under its new contract."
  [ctx root args]
  (source/with-snapshot
    (:storage ctx)
    (fn [storage authorize!]
      (let [frozen (view/collect storage root authorize!)
            {:keys [invoke surface] :as prepared} (prepare ctx frozen root)
            graph-hash (source-hash frozen)]
        (arguments/route-values surface args)
        (assoc (dissoc prepared :analysis :surface) :fn-version-id (lookup/resolve-fn-version-id (assoc ctx :storage storage) root)
               :graph-hash graph-hash
               :invoke (executor/named-input-closure
                         (fn [args run-ctx]
                           (source/with-snapshot
                             (:storage run-ctx)
                             (fn [current policy]
                               (when-not (= graph-hash (source-hash (view/collect current root policy)))
                                 (input/reject! :stored-input-changed {}))))
                           (executor/with-fresh-call-cache #(invoke args run-ctx)))))))))
