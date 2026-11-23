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
    [graphden.types.check.provenance :as provenance]
    [graphden.types.core :as types]
    [graphden.types.validator :as validator]))


(defn- trusted-roots
  [ctx frozen]
  (let [base-implementations (:base-fns ctx)
        signatures (::owned/native-signatures (meta base-implementations))
        builders (::owned/list-implementations (meta base-implementations))]
    (into (into {} (filter (fn [[id _]] (and (get-in frozen [:fns id]) (owned/owned-fn-id? id)))) builders)
          (keep (fn [[id {:keys [name]}]]
                  (when-let [signature (get signatures id)]
                    (let [impl (get base-implementations (keyword name))]
                      (when (owned/owned-impl? id impl)
                        [id {:impl impl :signature signature}])))))
          (:fns frozen))))


(defn- authorize-dependencies!
  [ctx ids]
  (when-let [guard (:execute-guard ctx)]
    (run! #(guard ctx %) ids)))


(defn- check-configured-fields!
  "Only a closure already lowered at this exact address stays inside the JVM.
   Public arguments, captured values and the result keep their wire contracts."
  [callable-allowed? scope path {:keys [fields type]}]
  (cond
    (and (types/record-type? type) fields)
    (doseq [field fields] (check-configured-fields! callable-allowed? scope (conj path (:id field)) field))

    (and (types/fn-type? type) (callable-allowed? scope path) (= #{} (types/fn-effects type)))
    (do (run! values/contract (vals (types/fn-args type)))
        (values/contract (types/fn-ret type)))

    :else (values/contract type)))


(defn- check-jvm-signature!
  [{:keys [effects execution]}]
  (when-not (and (= #{} effects) (set? execution) (contains? execution :jvm-server))
    (input/reject! :unsupported-run-signature {})))


(defn- project-result
  [t value]
  ;; HTTP Run emits ordinary JSON. Preserve a finite scalar in its declared
  ;; numeric type. The tagged browser protocol bounds composite results.
  (if (and (float? value) (Double/isFinite (double value))
           (loop [base t]
             (if (types/refine-type? base)
               (recur (types/refine-base base))
               (contains? #{:numeric :float} base))))
    (validator/validate! t value)
    (values/project t value)))


(defn- prepare
  "The HTTP result and argument-history boundaries require plain value types.
   Prepare only completely checked pure graphs with finite public contracts."
  [ctx frozen root]
  (let [{:keys [analysis invoke]} (compile/compile-stored frozen root (trusted-roots ctx frozen))
        targets (into #{} (map :target-fn-id) (:dependencies analysis))
        _ (authorize-dependencies! ctx targets)
        surface (:public-input analysis)
        result-type (input/call-with-types
                      frozen
                      (fn []
                        (values/contract (get-in surface [:root :type]))
                        (doseq [[use-path {:keys [signature configured-contract]}] (:scopes analysis)]
                          (check-jvm-signature! signature)
                          (check-configured-fields! #(contains? (:closures analysis) [%1 %2])
                                                    [root use-path] [] configured-contract)
                          (values/contract (:return signature)))
                        (values/contract (get-in analysis [:scopes [] :signature :return]))))]
    {:analysis (assoc analysis :compiled-input? true) :targets targets
     :invoke (executor/named-input-closure
               (fn [args context] (project-result result-type (invoke args context))))
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


(defn- source-hash
  [{:keys [fns fields members bindings]}]
  ;; Compare source identities, field order and semantic values. Storage row
  ;; timestamps and the SQL order of parallel membership reads are immaterial.
  (let [source-row #(dissoc % :created-at :updated-at :deleted-at)]
    (provenance/fingerprint-data
      [(update-vals fns source-row) (update-vals fields source-row)
       (update-vals members #(->> % (sort-by (juxt :position :slot-id)) (mapv source-row)))
       (update-vals bindings source-row)])))


(defn runnable-input
  "Return an authorized input analysis and Repeat basis from the same frozen source."
  [ctx frozen root]
  (try
    (when-let [guard (:execute-guard ctx)] (guard ctx root))
    (let [prepared (prepare ctx frozen root)]
      (assoc (select-keys prepared [:analysis :free-slots]) :graph-hash (source-hash frozen)))
    (catch clojure.lang.ExceptionInfo error (rejection error) nil)))


(defn authorable-package-root?
  "Check an open, pure package template before allowing a child to fill it.
   An unbound callable stays unavailable to the public execution transport."
  [ctx frozen root]
  (try
    (when-let [guard (:execute-guard ctx)] (guard ctx root))
    (when-not (some #(= root (:fn-id %)) (vals (:bindings frozen)))
      (let [{:keys [analysis]} (compile/analyze-stored frozen root (trusted-roots ctx frozen))
            {:keys [signature configured-contract]} (get-in analysis [:scopes []])]
        (when (and (= :checked (:status analysis)) (= 1 (count (:scopes analysis)))
                   (empty? (:dependencies analysis)))
          (input/call-with-types
            frozen
            (fn []
              (check-jvm-signature! signature)
              (check-configured-fields! (constantly true) [root []] [] configured-contract)
              (values/contract (:return signature))
              true)))))
    (catch clojure.lang.ExceptionInfo error (rejection error) nil)))


(defn plan
  "Reauthorize the frozen source when a queued run reaches its worker.
   A changed branch closure must be submitted again under its new contract."
  [ctx root args]
  (source/with-snapshot
    (:storage ctx)
    (fn [storage authorize!]
      (let [frozen (view/collect storage root authorize!)
            {:keys [invoke surface targets] :as prepared} (prepare (assoc ctx :storage storage) frozen root)
            graph-hash (source-hash frozen)]
        (arguments/route-values surface args)
        (assoc (dissoc prepared :analysis :surface :targets) :fn-version-id (lookup/resolve-fn-version-id (assoc ctx :storage storage) root)
               :graph-hash graph-hash
               :invoke (executor/named-input-closure
                         (fn [args run-ctx]
                           (source/with-snapshot
                             (:storage run-ctx)
                             (fn [current policy]
                               (when-not (= graph-hash (source-hash (view/collect current root policy)))
                                 (input/reject! :stored-input-changed {}))
                               (authorize-dependencies! (assoc run-ctx :storage current) targets)))
                           (executor/with-fresh-call-cache #(invoke args run-ctx)))))))))
