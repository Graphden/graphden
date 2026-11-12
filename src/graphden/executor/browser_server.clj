(ns graphden.executor.browser-server
  "Opaque, short-lived server callable descriptors. Every invocation resolves
   the original source binding under fresh principal/session/branch policy."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.provider :as accounts]
    [graphden.auth.provider :as auth]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-binding :as root-binding]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server-execution :as server-execution]
    [graphden.executor.browser-server-site :as server-site]
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
    [graphden.executor.source-recipe-replay :as replay]
    [graphden.storage.binding-read :as binding-read]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private lifetime-ms 120000)
(def ^:private max-issuances 2048)
(defonce ^:private issuances (atom {}))


(def ^:dynamic ^:private *client-fingerprint-memo* nil)


(def ^:private fingerprint-container-kinds
  {clojure.lang.PersistentArrayMap :map clojure.lang.PersistentHashMap :map
   clojure.lang.PersistentVector :vector clojure.lang.APersistentVector$SubVector :vector
   clojure.lang.PersistentHashSet :set
   clojure.lang.PersistentList :sequence clojure.lang.PersistentList$EmptyList :sequence})


(def ^:private fingerprint-leaf-classes
  #{nil String Boolean Character java.util.UUID clojure.lang.Keyword clojure.lang.Symbol
    Long Integer Short Byte Double Float java.math.BigDecimal java.math.BigInteger
    clojure.lang.BigInt clojure.lang.Ratio})


(defn- fingerprint-map-key?
  [value]
  ;; canonical sorts map keys by their printed form. Distinct keywords can
  ;; print alike when a component contains '/'; stable-sort ties then expose
  ;; map iteration order. Admit only injectively printed keyword components.
  (or (string? value) (uuid? value)
      (and (keyword? value)
           (not (str/includes? (name value) "/"))
           (let [keyword-ns (namespace value)]
             (or (nil? keyword-ns) (not (str/includes? keyword-ns "/")))))))


(defn- same-fingerprint-input?
  "Conservative digest equivalence, not Clojure equality: vector/list and
   printed numeric representations differ. Unknown/mutable values always miss,
   even when identical. Call only under fingerprint-data's print bindings."
  [left right]
  (let [kind (get fingerprint-container-kinds (class left))]
    (and (= kind (get fingerprint-container-kinds (class right)))
         (case kind
           :map (and (= (count left) (count right))
                     (every? (fn [[k v]]
                               (when (fingerprint-map-key? k)
                                 (when-let [entry (find right k)]
                                   (and (fingerprint-map-key? (key entry))
                                        (same-fingerprint-input? k (key entry))
                                        (same-fingerprint-input? v (val entry)))))) left))
           :set (and (= (count left) (count right))
                     (every? (fn [item]
                               (and (contains? right item)
                                    (same-fingerprint-input? item (get right item)))) left))
           (:vector :sequence) (and (= (count left) (count right))
                                    (every? true? (map same-fingerprint-input? left right)))
           (and (contains? fingerprint-leaf-classes (class left))
                (contains? fingerprint-leaf-classes (class right))
                (or (identical? left right) (= (pr-str left) (pr-str right))))))))


(defn- client-fingerprint
  [input]
  (let [memo *client-fingerprint-memo*
        previous (when memo @memo)]
    (if (and (map? previous)
             (binding [*print-length* nil *print-level* nil *print-meta* false
                       *print-readably* true *print-dup* false *print-namespace-maps* false]
               (same-fingerprint-input? (:input previous) input)))
      (:digest previous)
      (let [digest (provenance/fingerprint-data input)]
        ;; A timed-out worker may still unwind. It cannot reopen the request's
        ;; closed memo or retain another captured graph after invoke returns.
        (when (and memo (not= ::closed previous))
          (compare-and-set! memo previous {:input input :digest digest}))
        digest))))


(defn- reject!
  [reason]
  (throw (ex-info "Server call is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(def ^:private provider-subject-keys
  [:authenticated? :user-id :api-token? :token-scopes])


(defn- provider-subject
  [ctx request principal]
  (when-let [provider (:auth-provider ctx)]
    (let [authenticated (auth/authenticate provider request)]
      (when-not (and (map? authenticated) (true? (:authenticated? authenticated)))
        (reject! :server-session-changed))
      (let [subject (select-keys authenticated provider-subject-keys)]
        ;; Core's single-token provider has no tenancy principal. When a
        ;; tenancy scope exists, its authenticated subject must still agree.
        (when (and principal (not= subject (select-keys principal provider-subject-keys)))
          (reject! :server-session-changed))
        subject))))


(defn- scope
  [ctx request]
  {:principal (some-> tenancy/*current-principal* (update :role-ids set))
   :provider-subject (provider-subject ctx request tenancy/*current-principal*)
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


(defn- capture-root-anchor
  [storage authorize! policy]
  (let [{handler-root :root :keys [root-row selector-row]} (root-binding/capture! storage)
        choice (resolution/root-choice storage authorize! policy)
        selected-root (root-binding/selected-root handler-root choice)]
    {:anchor {:handler-root handler-root :selected-root selected-root}
     :evidence {:root-binding root-row :selector-binding selector-row
                :selected-root selected-root}}))


(defn- anchor!
  [storage authorize! policy {:keys [fn-id slot-id ref-fn-id handler-root selected-root] :as anchor}]
  (if (contains? anchor :selected-root)
    (let [captured (capture-root-anchor storage authorize! policy)]
      (when-not (= {:handler-root handler-root :selected-root selected-root} (:anchor captured))
        (reject! :server-binding-changed))
      (:evidence captured))
    (let [rows (binding-read/exact-slot (binding-read/owner-bindings storage fn-id) slot-id)
          row (first rows)]
      (when-not (and (= 1 (count rows)) (= ref-fn-id (:ref-fn-id row))
                     (nil? (:resolver-fn-id row)) (nil? (:value row))
                     (not (:value-present row)) (not (:terminal row)))
        (reject! :server-binding-changed))
      row)))


(defn- capture-client-source-value
  [ctx storage authorize! policy entries anchor-row]
  (let [graph (source/collect-client-closure storage (vals entries) authorize!)
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
        evidence (client-fingerprint
                   {:graph (normalized-graph graph) :anchor anchor-row
                    :targets (into {} (map (fn [[id proof]] [id (:fingerprint proof)])) targets)})]
    {:snapshot captured :targets targets :calls calls :evidence evidence :anchor-evidence anchor-row}))


(defn- capture-client-source
  [ctx storage authorize! policy entries anchor-row]
  (replay/capture storage authorize! entries
                  (fn [fresh-storage fresh-authorize!]
                    (capture-client-source-value ctx fresh-storage fresh-authorize! policy entries anchor-row))))


(defn- capture-client
  [ctx storage authorize! policy entries anchor]
  (capture-client-source ctx storage authorize! policy entries
                         (anchor! storage authorize! policy anchor)))


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


(defn- export-captured-plan
  [ctx request policy entries anchor {:keys [snapshot calls targets evidence anchor-evidence]}]
  (let [epoch (context/invalidation-epoch ctx)
        site-proofs (server-site/prepare-all (:graph snapshot) entries calls targets anchor-evidence)
        descriptors (into {}
                          (map (fn [[site target]]
                                 [site (issue! {:scope (scope ctx request) :anchor anchor
                                                :entries entries :site site :target target
                                                :site-proof (get site-proofs site)
                                                :evidence evidence :epoch epoch
                                                :contract (select-keys (get targets target)
                                                                       [:input-type :output-type])})]))
                          calls)]
    (snapshot/export-snapshot
      (assoc snapshot :server-plan-options {:server-descriptors descriptors})
      (:base-fns ctx) entries policy)))


(defn export-client-plan
  "Export the selected root. Network targets stop at checked opaque terminals;
   their implementations and configured captures remain exclusively server-side."
  [ctx storage authorize! policy entries request anchor]
  (export-captured-plan ctx request policy entries anchor
                        (capture-client ctx storage authorize! policy entries anchor)))


(defn call-with-root-export
  "Resolve the trusted root once inside the caller's immutable snapshot.
   The callback receives its identity and a zero-argument exporter; no caller
   can supply prevalidated anchor evidence. Invocations still recapture it."
  [ctx storage authorize! policy request consume]
  (let [{:keys [anchor evidence]} (capture-root-anchor storage authorize! policy)
        root (:selected-root anchor)
        entries {:root root}]
    (consume root
             #(export-captured-plan
                ctx request policy entries anchor
                (capture-client-source ctx storage authorize! policy entries evidence)))))


(defn- current-auth!
  [ctx request saved]
  (let [current (scope ctx request)]
    (when-not (= (:provider-subject saved) (:provider-subject current))
      (reject! :server-session-changed))
    (when-not (= saved current) (reject! :server-scope-changed))))


(defn- execute-guard!
  [ctx target]
  ;; Explicitly reset the HTTP handler's already-authorized dynamic scope.
  ;; Source disclosure never supplies this separate execution permission.
  (binding [runtime/*execute-authorized* false]
    (when-let [guard (:execute-guard ctx)] (guard ctx target))))


(defn- capture-site
  "Refresh only the selected issued path and target in this new snapshot.
   Witnesses establish provenance, never permissions: every path owner and
   selected target is freshly authorized before its owned source is read."
  [ctx storage authorize! policy issued]
  (let [{:keys [ids evidence target-fingerprint]} (:site-proof issued)
        anchor-evidence (anchor! storage authorize! policy (:anchor issued))
        graph (source/capture-nodes storage ids authorize!)
        expected (server-site/fingerprint graph ids anchor-evidence target-fingerprint)]
    (when-not (= evidence expected) (reject! :server-binding-changed))
    (doseq [id (vals (:entries issued))]
      (let [row (some #(when (= id (:id %)) %) (:fns graph))]
        (when-not row (reject! :server-root-missing))
        (authorize! row {:execute? true})))
    (let [[owner slot] (:site issued)
          index (lookups/build-lookups graph)
          binding (filterv #(= slot (:slot-id %)) (bindings/collect-bindings owner index))
          target (:target issued)]
      (when-not (and (= 1 (count binding)) (= :ref (:kind (first binding)))
                     (= target (:ref-id (first binding))))
        (reject! :server-binding-changed))
      (let [target-source (server-source/capture ctx storage authorize! policy target)]
        (when-not (and (= target-fingerprint (:fingerprint target-source))
                       (= (:contract issued) (select-keys target-source [:input-type :output-type])))
          (reject! :server-binding-changed))
        {:snapshot {:graph (update graph :fns conj
                                   (some #(when (= target (:id %)) %) (:fns (:graph target-source))))}
         :targets {target target-source} :calls {(:site issued) target} :evidence evidence}))))


(defn- current-call!
  [ctx storage authorize! policy issued]
  (let [captured (if (:site-proof issued)
                   (capture-site ctx storage authorize! policy issued)
                   (capture-client ctx storage authorize! policy (:entries issued) (:anchor issued)))
        evidence (or (get-in issued [:site-proof :evidence]) (:evidence issued))]
    (when-not (and (= evidence (:evidence captured))
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


(defn- refusal-log-reason
  "Return only fixed, non-sensitive categories; never log arbitrary exception data."
  [error]
  (let [{:keys [type reason]} (ex-data error)]
    (case type
      :authz/forbidden :forbidden
      :browser-plan/unsupported
      (case reason
        :server-timeout :server-timeout
        :server-capacity :server-capacity
        :server-epoch-changed :server-epoch-changed
        :unavailable)
      :unavailable)))


(defn- invoke-current-value
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
      (let [memo (atom nil)]
        (binding [*client-fingerprint-memo* memo]
          (try
            (recheck/call-with-ctx-slices ctx #(execute-current ctx request issued (:input input)))
            (finally (reset! memo ::closed))))))
    (catch Exception error
      (log/warn "Read-only server call refused"
                {:type :browser-server/refused
                 :reason (refusal-log-reason error)})
      (components/failure-facts error))))


(defn invoke-current
  "Invoke an opaque current descriptor. Optional source recipes publish only
   after execution AND the fresh caller-side postcheck have succeeded."
  [ctx request input]
  (replay/call-with-operation ctx #(invoke-current-value ctx request input)))
