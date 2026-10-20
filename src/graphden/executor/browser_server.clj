(ns graphden.executor.browser-server
  "Opaque, short-lived server callable descriptors. Every invocation resolves
   the original source binding under fresh principal/session/branch policy."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.provider :as accounts]
    [graphden.auth.provider :as auth]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.editor.components :as components]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server-execution :as server-execution]
    [graphden.executor.browser-server-source :as server-source]
    [graphden.executor.browser-server-values :as values]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile-eager :as eager]
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
(defonce ^:private issuances (atom {}))


(defn- reject!
  [reason]
  (throw (ex-info "Server call is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- scope
  [ctx request]
  {:principal (some-> tenancy/*current-principal* (update :role-ids set))
   :org (tenancy/current-org)
   :branch (versioned/current-branch-id (:storage ctx))
   ;; This fingerprint never leaves server memory. Only the random issuance
   ;; identity is serialized; a browser cannot correlate auth carrier hashes.
   :session (some-> (accounts/request-token request) crypto/sha256-hex)})


(defn- call-with-fresh-scope
  "Re-enter the trusted original auth scope. A short-circuiting 403/421/429
   cannot manufacture a successful internal invocation."
  [ctx request f]
  (if-let [request-scope (:request-scope ctx)]
    (let [result (volatile! ::not-called)
          receipt (str (random-uuid))
          response (request-scope
                     ctx request
                     (fn []
                       (vreset! result (f))
                       {:status 204 :headers {"X-Graphden-Server-Scope" receipt} :body ""}))]
      (when-not (and (not= ::not-called @result) (= 204 (:status response))
                     (= receipt (get-in response [:headers "X-Graphden-Server-Scope"])))
        (reject! :server-scope-changed))
      @result)
    (if (tenancy/tenancy-addon-active?)
      (reject! :server-scope-unavailable)
      (f))))


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
                   (= (:target issued) (get-in captured [:calls (:site issued)])))
      (reject! :server-binding-changed))
    (let [row (some #(when (= (:target issued) (:id %)) %) (get-in captured [:snapshot :graph :fns]))]
      (when-not row (reject! :server-target-missing))
      (authorize! row {:execute? true :server-target? true}))
    ;; A stale epoch is recoverable only after the source, target and fresh
    ;; execute policy still match. Revocation never becomes a stale reply.
    (when-not (= (:epoch issued) (context/invalidation-epoch ctx))
      (reject! :server-epoch-changed))
    captured))


(defn- read-only-context
  [ctx storage graph]
  ;; Compile exactly the source closure that was just certified, rather than
  ;; borrowing a mutable whole-branch registry between proof and invocation.
  (let [index (assoc (lookups/build-lookups graph) :base-fns (:base-fns ctx))
        allowed (cond-> #{:db}
                  (:allowed-effects ctx) (set/intersection (set (:allowed-effects ctx)))
                  (not= tenancy/public-org (tenancy/current-org))
                  (set/intersection (set (runtime/cloud-allowed-effects-for (tenancy/current-org)))))]
    (assoc ctx :storage storage :allowed-effects allowed
           :graph-cache (atom graph) :compiled-registry (atom (eager/compile-all index))
           :registry-stale? (atom false))))


(defn- run-certified
  [ctx request issued input]
  (current-auth! ctx request (:scope issued))
  (execute-guard! ctx (:target issued))
  (let [policy (snapshot/capture-context-policy ctx)]
    (source/with-snapshot
      (:storage ctx)
      (fn [storage authorize!]
        (components/assert-current-policy! storage)
        (let [captured (current-call! ctx storage authorize! policy issued)
              target (:target issued)
              graph (get-in captured [:targets target :graph])
              argument (values/input (get-in issued [:contract :input-type]) input)
              exec-ctx (read-only-context ctx storage graph)
              trace (atom #{})]
          (binding [runtime/*execute-authorized* false runtime/*effect-trace* trace
                    runtime/*allowed-effects* (:allowed-effects exec-ctx)
                    runtime/*path-trace* nil]
            (runtime/with-fresh-call-cache
              (fn []
                (let [outcome (persist/redact-outcome
                                target {:status :succeeded
                                        :result (runtime/execute exec-ctx target {:input argument})})]
                  (when (:tainted? outcome) (reject! :server-execution-failed))
                  ;; Materialize under the SAME effect/deadline/cache scope,
                  ;; before this worker can release its own snapshot.
                  (let [encoded (wire/encode-result
                                  (values/project (get-in issued [:contract :output-type]) (:result outcome)))]
                    (runtime/check-cancel!)
                    (when (not-every? #{:db} @trace) (reject! :server-execution-failed))
                    {:ok true :value encoded}))))))))))


(defn- execute-current
  [ctx request issued input]
  (current-auth! ctx request (:scope issued))
  (let [result (server-execution/run
                 ctx #(call-with-fresh-scope ctx request (fn [] (run-certified ctx request issued input))))]
    (call-with-fresh-scope
      ctx request
      (fn []
        (current-auth! ctx request (:scope issued))
        (execute-guard! ctx (:target issued))
        ;; A new transaction and a newly resolved auth subject are essential:
        ;; the worker snapshot cannot observe a grant, role or source revocation.
        (source/with-snapshot
          (:storage ctx)
          (fn [storage authorize!]
            (components/assert-current-policy! storage)
            (current-call! ctx storage authorize! (snapshot/capture-context-policy ctx) issued)))))
    result))


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
      (log/warn "Read-only server call refused"
                {:type :browser-server/refused
                 :reason (if (= :authz/forbidden (:type (ex-data error))) :forbidden :unavailable)})
      (cond-> {:ok false :reason "Server call is unavailable."
               :http-status (if (= :authz/forbidden (:type (ex-data error))) 403 422)}
        (= {:type :browser-plan/unsupported :reason :server-epoch-changed} (ex-data error))
        (assoc :code "server-call-stale")))))
