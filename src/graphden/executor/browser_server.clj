(ns graphden.executor.browser-server
  "Opaque, short-lived server callable descriptors. Every invocation resolves
   the original source binding under fresh principal/session/branch policy."
  (:require
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.provider :as accounts]
    [graphden.auth.provider :as auth]
    [graphden.crud.fn-execution :as execution]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.editor.components :as components]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server-source :as server-source]
    [graphden.executor.browser-server-values :as values]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.context :as context]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private lifetime-ms 120000)
(def ^:private max-issuances 2048)
(def ^:private wall-ms 750)
(defonce ^:private issuances (atom {}))


(defn- reject!
  [reason]
  (throw (ex-info "Server call is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- scope
  [ctx request]
  {:principal tenancy/*current-principal*
   :org (tenancy/current-org)
   :branch (versioned/current-branch-id (:storage ctx))
   ;; This fingerprint never leaves server memory. Only the random issuance
   ;; identity is serialized; a browser cannot correlate auth carrier hashes.
   :session (some-> (accounts/request-token request) crypto/sha256-hex)})


(defn- normalized-graph
  [graph]
  (into {} (map (fn [[k rows]]
                  [k (if (vector? rows) (vec (sort-by (comp str :id) rows)) rows)])) graph))


(defn- anchor!
  [storage {:keys [fn-id slot-id ref-fn-id]}]
  (let [rows (sp/query-bounded-entities storage :binding {:fn-id fn-id :slot-id slot-id} 2)
        row (first rows)]
    (when-not (and (= 1 (count rows)) (= ref-fn-id (:ref-fn-id row))
                   (nil? (:resolver-fn-id row)) (nil? (:value row))
                   (not (:value-present row)) (not (:terminal row)))
      (reject! :server-binding-changed))
    row))


(defn- capture-client
  [ctx storage authorize! policy entries anchor]
  (let [anchor-row (anchor! storage anchor)
        graph (source/collect-client-closure storage (vals entries) authorize!)
        _ (doseq [id (vals entries)]
            (let [row (some #(when (= id (:id %)) %) (:fns graph))]
              (when-not row (reject! :server-root-missing))
              (authorize! row {:execute? true})))
        index (lookups/build-lookups graph)
        calls (into {}
                    (mapcat (fn [row]
                              (for [binding (bindings/collect-bindings (:id row) index)
                                    :when (and (capabilities/server-call-slot? (:slot-id binding))
                                               (= :ref (:kind binding)))]
                                [[(:id row) (:slot-id binding)] (:ref-id binding)])))
                    (:fns graph))
        targets (into {} (map (fn [target]
                                [target (server-source/capture ctx storage authorize! policy target)]))
                      (set (vals calls)))
        signatures (into {} (map (fn [[id evidence]] [id (:signature evidence)])) targets)
        captured {:graph graph :namespaces (source/collect-namespaces storage graph)
                  :server-signatures signatures}
        evidence (provenance/fingerprint-data
                   {:graph (normalized-graph graph) :anchor anchor-row
                    :targets (into {} (map (fn [[id proof]] [id (:fingerprint proof)])) targets)})]
    {:snapshot captured :targets targets :calls calls :evidence evidence}))


(defn- issue!
  [record]
  (let [now (System/currentTimeMillis)
        token (str (random-uuid))
        record (assoc record :expires (+ now lifetime-ms))]
    (swap! issuances
           (fn [entries]
             (let [fresh (into {} (filter (fn [[_ v]] (> (:expires v) now))) entries)]
               (when (>= (count fresh) max-issuances) (reject! :server-descriptor-limit))
               (assoc fresh token record))))
    {:token token}))


(defn export-client-plan
  "Export the selected root. Network targets stop at checked opaque terminals;
   their implementations and configured captures remain exclusively server-side."
  [ctx storage authorize! policy entries request anchor]
  (let [{:keys [snapshot calls targets evidence]}
        (capture-client ctx storage authorize! policy entries anchor)
        epoch (context/invalidation-epoch ctx)
        descriptors (into {}
                          (map (fn [[site target]]
                                 [site (issue! {:scope (scope ctx request) :anchor anchor
                                                :entries entries :site site :target target
                                                :evidence evidence :epoch epoch
                                                :contract (select-keys (get targets target)
                                                                       [:input-type :output-type])})]))
                          calls)]
    (snapshot/export-snapshot
      (assoc snapshot :server-plan-options {:server-descriptors descriptors})
      (:base-fns ctx) entries policy)))


(defn- current-auth!
  [ctx request saved]
  (when-not (= saved (scope ctx request)) (reject! :server-scope-changed))
  (when-let [provider (:auth-provider ctx)]
    (let [current (auth/authenticate provider request)]
      (when-not (= (select-keys (:principal saved) [:authenticated? :user-id :api-token? :token-scopes])
                   (select-keys current [:authenticated? :user-id :api-token? :token-scopes]))
        (reject! :server-session-changed)))))


(defn- execute-guard!
  [ctx target]
  ;; Explicitly reset the HTTP handler's already-authorized dynamic scope.
  ;; Source disclosure never supplies this separate execution permission.
  (binding [runtime/*execute-authorized* false]
    (when-let [guard (:execute-guard ctx)] (guard ctx target))))


(defn- current-call!
  [ctx storage authorize! policy issued]
  (let [captured (capture-client ctx storage authorize! policy (:entries issued) (:anchor issued))]
    (when-not (and (= (:evidence issued) (:evidence captured))
                   (= (:target issued) (get-in captured [:calls (:site issued)]))
                   (= (:epoch issued) (context/invalidation-epoch ctx)))
      (reject! :server-binding-changed))
    captured))


(defn- execute-current
  [ctx request issued input]
  (current-auth! ctx request (:scope issued))
  (execute-guard! ctx (:target issued))
  (let [policy (snapshot/capture-context-policy ctx)
        result
        (source/with-snapshot
          (:storage ctx)
          (fn [storage authorize!]
            (components/assert-current-policy! storage)
            (current-call! ctx storage authorize! policy issued)
            (let [target (:target issued)
                  row (sp/read-entity storage :fn target)
                  argument (values/input (get-in issued [:contract :input-type]) input)]
              (binding [runtime/*execute-authorized* false
                        persist/*max-execution-wall-ms* wall-ms]
                (execution/apply-execute
                  (assoc ctx :storage storage :allowed-effects #{:db})
                  {:fn-id target :args {:input argument} :persist? false :timeout-ms wall-ms}
                  row)))))]
    (when-not (and (= :succeeded (:status result)) (not (:tainted? result))
                   (every? #{:db} (:runtime-effects result)))
      (reject! :server-execution-failed))
    (current-auth! ctx request (:scope issued))
    (execute-guard! ctx (:target issued))
    ;; A new transaction is essential: the first repeatable-read snapshot
    ;; cannot observe a grant, graph or capture edit committed during execution.
    (source/with-snapshot
      (:storage ctx)
      (fn [storage authorize!]
        (components/assert-current-policy! storage)
        (current-call! ctx storage authorize! (snapshot/capture-context-policy ctx) issued)))
    {:ok true :value (wire/encode-result
                       (values/project (get-in issued [:contract :output-type]) (:result result)))}))


(defn invoke-current
  "Request accepts only opaque descriptor plus tagged data. No client target,
   capture, binding, principal or branch can be supplied as authority."
  [ctx request input]
  (try
    (when-not (and (map? input) (= #{:descriptor :input} (set (keys input)))
                   (map? (:descriptor input)) (= #{:token} (set (keys (:descriptor input))))
                   (string? (get-in input [:descriptor :token])))
      (reject! :server-request))
    (let [issued (get @issuances (get-in input [:descriptor :token]))]
      (when-not (and issued (> (:expires issued) (System/currentTimeMillis)))
        (reject! :server-descriptor-expired))
      (recheck/call-with-ctx-slices ctx #(execute-current ctx request issued (:input input))))
    (catch Exception error
      {:ok false :reason "Server call is unavailable."
       :http-status (if (= :authz/forbidden (:type (ex-data error))) 403 422)})))
