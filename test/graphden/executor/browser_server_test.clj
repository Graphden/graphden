(ns ^:serial graphden.executor.browser-server-test
  "Exercise authorization and response retirement independently of transport."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.auth.provider :as auth]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.editor.components :as components]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.storage.remote.core :as remote]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]))


(defn- fixture
  [f & [options]]
  (let [target (random-uuid)
        site [(random-uuid) (random-uuid)]
        branch (random-uuid)
        calls (atom [])
        snapshots (atom 0)
        allowed? (atom true)
        fresh-execute? (atom true)
        evidence (atom "source/binding/captures/contract")
        call-target (atom target)
        on-execute (atom (fn [] nil))
        storage (assoc (remote/from-bundle {:fn [{:id target}]}) :branch-id branch)
        ctx {:storage storage :invalidation-count (atom 0)
             :auth-provider (:auth-provider options)
             :execute-guard (fn [_ id]
                              (swap! calls conj [:guard id runtime/*execute-authorized*])
                              (when-not @allowed?
                                (throw (ex-info "Private target" {:type :authz/forbidden}))))}
        request {:headers {"authorization" "Bearer first-session"}}
        captured (fn [& _]
                   {:evidence @evidence :calls {site @call-target}
                    :snapshot {:graph {:fns [{:id target}]}} :targets {target {:input-type {:query :text}
                                                                               :output-type {:label :text :branch-name :text}}}})]
    (reset! @(ns-resolve 'graphden.executor.browser-server 'issuances) {})
    (binding [tenancy/*current-org* "team"
              tenancy/*current-principal* (get options :principal
                                               {:authenticated? true :user-id "owner-a"
                                                :role-ids ["reader-role"]})
              runtime/*execute-authorized* true]
      (with-redefs-fn
        {(ns-resolve 'graphden.executor.browser-server 'capture-client) captured
         #'source/with-snapshot (fn [st consume]
                                  (swap! snapshots inc)
                                  (consume st (fn [row & [permissions]]
                                                (when (and (= target (:id row)) (:execute? permissions)
                                                           (not @fresh-execute?))
                                                  (throw (ex-info "Revoked" {:type :authz/forbidden}))))))
         #'snapshot/export-snapshot (fn [captured _ _ _]
                                      (get-in captured [:server-plan-options :server-descriptors]))
         #'snapshot/capture-context-policy (fn [_] {})
         #'components/assert-current-policy! (fn [_] nil)
         #'recheck/call-with-ctx-slices (fn [_ thunk] (thunk))
         (ns-resolve 'graphden.executor.browser-server 'read-only-context)
         (fn [exec-ctx _ _] (assoc exec-ctx :allowed-effects #{:db}))
         #'persist/redact-outcome (fn [_ result] result)
         #'runtime/execute
         (fn [exec-ctx target arguments]
           (swap! calls conj [:run target runtime/*execute-authorized*
                              (:allowed-effects exec-ctx) arguments])
           (runtime/record-effect! :db)
           (@on-execute)
           {:label "Branch" :branch-name "main" :secret "never serialize"})}
        (fn []
          (let [descriptors (server/export-client-plan ctx storage (constantly nil) {}
                                                       {:root (first site)} request {})
                descriptor (get descriptors site)
                input {:descriptor descriptor :input (wire/encode-value {:query ""})}]
            (f {:ctx ctx :request request :input input :calls calls :snapshots snapshots
                :allowed? allowed? :fresh-execute? fresh-execute? :evidence evidence
                :call-target call-target :on-execute on-execute})))))))


(deftest only-an-authorized-unchanged-call-receives-the-stale-epoch-code
  (doseq [stage [:before :after]]
    (fixture
      (fn [{:keys [ctx request input calls on-execute]}]
        (let [invalidate #(swap! (:invalidation-count ctx) inc)]
          (if (= :before stage) (invalidate) (reset! on-execute invalidate))
          (let [result (server/invoke-current ctx request input)]
            (is (= {:ok false :reason "Server call is unavailable."
                    :http-status 422 :code "server-call-stale"} result))
            (is (= (if (= :before stage) 0 1)
                   (count (filter #(= :run (first %)) @calls))))))))))


(deftest epoch-changes-never-disguise-revocation-or-source-changes-as-stale
  (doseq [stage [:before :after]
          change [:execute :evidence :target]]
    (fixture
      (fn [{:keys [ctx request input fresh-execute? evidence call-target on-execute]}]
        (let [mutate #(do (swap! (:invalidation-count ctx) inc)
                          (case change
                            :execute (reset! fresh-execute? false)
                            :evidence (reset! evidence "changed source or captures")
                            :target (reset! call-target (random-uuid))))]
          (if (= :before stage) (mutate) (reset! on-execute mutate))
          (let [result (server/invoke-current ctx request input)]
            (is (= (if (= :execute change) 403 422) (:http-status result)))
            (is (false? (:ok result)))
            (is (nil? (:value result)))
            (is (not (contains? result :code)))))))))


(deftest stale-epoch-does-not-classify-session-or-unverified-policy-failures
  (fixture
    (fn [{:keys [ctx input]}]
      (swap! (:invalidation-count ctx) inc)
      (let [result (server/invoke-current ctx {:headers {"authorization" "Bearer next-session"}} input)]
        (is (= 422 (:http-status result)))
        (is (not (contains? result :code))))))
  (fixture
    (fn [{:keys [ctx request input]}]
      (swap! (:invalidation-count ctx) inc)
      (with-redefs [components/assert-current-policy!
                    (fn [_]
                      (throw (ex-info "Policy unpublished"
                                      {:type :browser-plan/unsupported :reason :policy-refresh-required})))]
        (let [result (server/invoke-current ctx request input)]
          (is (= 422 (:http-status result)))
          (is (not (contains? result :code))))))))


(deftest source-issuance-does-not-grant-execution-or-export-captures
  (fixture
    (fn [{:keys [ctx request input calls allowed?]}]
      (is (= #{:token} (set (keys (:descriptor input)))))
      (is (empty? @calls) "source export did not execute or confer an execute grant")
      (reset! allowed? false)
      (is (= 403 (:http-status (server/invoke-current ctx request input))))
      (is (= 1 (count @calls)))
      (is (= [:guard false] [(ffirst @calls) (last (first @calls))])))))


(deftest execution-resets-the-inherited-guard-and-projects-private-extra-fields
  (fixture
    (fn [{:keys [ctx request input calls snapshots]}]
      (let [result (server/invoke-current ctx request input)]
        (is (true? (:ok result)))
        (is (= {:label "Branch" :branch-name "main"} (wire/decode-value (:value result))))
        (is (= [:guard :run :guard] (mapv first @calls)))
        (is (every? false? (map #(nth % 2) @calls)))
        (is (= 2 @snapshots) "response is admitted against a new transaction")))))


(deftest forged-descriptors-and-extra-authority-never-execute
  (fixture
    (fn [{:keys [ctx request input calls]}]
      (doseq [forged [(assoc input :target (str (random-uuid)))
                      (assoc input :captures {:label "forged"})
                      (assoc input :descriptor {:token (str (random-uuid))})
                      (assoc-in input [:descriptor :target] (str (random-uuid)))
                      (assoc input :input (wire/encode-value {:query "" :capture "forged"}))]]
        (is (false? (:ok (server/invoke-current ctx request forged)))))
      (is (not-any? #(= :run (first %)) @calls)))))


(deftest foreign-principal-session-organization-and-branch-never-execute
  (fixture
    (fn [{:keys [ctx request input calls]}]
      (binding [tenancy/*current-principal* {:authenticated? true :user-id "foreign"}]
        (is (false? (:ok (server/invoke-current ctx request input)))))
      (binding [tenancy/*current-org* "foreign"]
        (is (false? (:ok (server/invoke-current ctx request input)))))
      (is (false? (:ok (server/invoke-current ctx {:headers {"authorization" "Bearer next-session"}} input))))
      (is (false? (:ok (server/invoke-current (assoc-in ctx [:storage :branch-id] (random-uuid)) request input))))
      (is (empty? @calls)))))


(deftest edited-capture-or-root-proof-retires-an-issued-descriptor
  (fixture
    (fn [{:keys [ctx request input calls evidence]}]
      (reset! evidence "changed server captures")
      (is (false? (:ok (server/invoke-current ctx request input))))
      (is (not-any? #(= :run (first %)) @calls)))))


(deftest revocation-and-source-change-during-execution-retire-the-response
  (fixture
    (fn [{:keys [ctx request input allowed? on-execute]}]
      (reset! on-execute #(reset! allowed? false))
      (let [result (server/invoke-current ctx request input)]
        (is (= 403 (:http-status result)))
        (is (nil? (:value result))))))
  (fixture
    (fn [{:keys [ctx request input evidence on-execute]}]
      (reset! on-execute #(reset! evidence "changed during execution"))
      (let [result (server/invoke-current ctx request input)]
        (is (false? (:ok result)))
        (is (nil? (:value result)))))))


(deftest fresh-target-source-policy-vetoes-a-memoized-execute-guard
  (fixture
    (fn [{:keys [ctx request input fresh-execute? on-execute]}]
      ;; The request-local execute guard continues to allow. Only a newly
      ;; created snapshot policy can see the target's revoked execute grant.
      (reset! on-execute #(reset! fresh-execute? false))
      (let [result (server/invoke-current ctx request input)]
        (is (= 403 (:http-status result)))
        (is (nil? (:value result)))))))


(deftest fresh-auth-subject-and-short-circuiting-request-scope-retire-responses
  (fixture
    (fn [{:keys [ctx request input on-execute]}]
      (let [roles (atom ["reader-role"])
            scopes (atom 0)
            ctx (assoc ctx :request-scope
                       (fn [_ received thunk]
                         (is (identical? request received))
                         (swap! scopes inc)
                         (binding [tenancy/*current-principal*
                                   (assoc tenancy/*current-principal* :role-ids @roles)]
                           (thunk))))]
        (reset! on-execute #(do (swap! (:invalidation-count ctx) inc)
                                (reset! roles [])))
        (let [result (server/invoke-current ctx request input)]
          (is (false? (:ok result)))
          (is (nil? (:value result)))
          (is (not (contains? result :code)))
          (is (= 2 @scopes))))))
  (fixture
    (fn [{:keys [ctx request input calls]}]
      (let [ctx (assoc ctx :request-scope (fn [& _] {:status 403 :headers {} :body "refused"}))]
        (is (false? (:ok (server/invoke-current ctx request input))))
        (is (empty? @calls))))))


(deftest single-token-authentication-does-not-require-a-tenancy-principal
  (fixture
    (fn [{:keys [ctx request input calls]}]
      ;; A Ring :principal is client/request data, not the tenancy binding or
      ;; provider result. It cannot change either authenticated authority.
      (let [request (assoc request :principal {:authenticated? true :user-id "forged"})
            result (server/invoke-current ctx request input)]
        (is (true? (:ok result)))
        (is (= {:label "Branch" :branch-name "main"} (wire/decode-value (:value result))))
        (is (= 1 (count (filter #(= :run (first %)) @calls)))))
      (doseq [request [{} {:headers {"authorization" "Bearer wrong-session"}}]]
        (let [result (server/invoke-current ctx
                                            (assoc request :principal {:authenticated? true}) input)]
          (is (false? (:ok result)))
          (is (nil? (:value result)))
          (is (not (contains? result :code)))))
      (is (= 1 (count (filter #(= :run (first %)) @calls)))))
    {:principal nil :auth-provider (auth/single-token-provider "first-session")}))


(deftest configured-provider-must-authenticate-the-original-request-at-issuance
  (doseq [subject [nil {} {:authenticated? false} {:authenticated? "true"}]]
    (let [provider (reify auth/AuthProvider
                     (authenticate [_ _] subject))
          error (try
                  (fixture (fn [_] (is false "invalid provider subject cannot issue a descriptor"))
                           {:principal nil :auth-provider provider})
                  (catch clojure.lang.ExceptionInfo error (ex-data error)))]
      (is (= {:type :browser-plan/unsupported :reason :server-session-changed} error))))
  (fixture
    (fn [{:keys [ctx request input]}]
      (is (true? (:ok (server/invoke-current ctx request input)))))
    {:principal nil :auth-provider nil}))


(deftest provider-subject-changes-before-or-after-execution-retire-the-response
  (doseq [stage [:before :after]
          changed [nil {} {:authenticated? false}
                   {:authenticated? true :user-id "other"}
                   {:authenticated? true :api-token? true :token-scopes #{:read}}]]
    (let [subject (atom {:authenticated? true})
          provider (reify auth/AuthProvider
                     (authenticate [_ _] @subject))]
      (fixture
        (fn [{:keys [ctx request input calls on-execute]}]
          (if (= :before stage)
            (reset! subject changed)
            (reset! on-execute #(reset! subject changed)))
          (let [result (server/invoke-current ctx request input)]
            (is (false? (:ok result)))
            (is (nil? (:value result)))
            (is (not (contains? result :code)))
            (is (= (if (= :before stage) 0 1)
                   (count (filter #(= :run (first %)) @calls))))))
        {:principal nil :auth-provider provider}))))


(deftest provider-presence-and-tenancy-subject-are-independent-authority
  (fixture
    (fn [{:keys [ctx request input calls]}]
      (let [result (server/invoke-current (dissoc ctx :auth-provider) request input)]
        (is (false? (:ok result)))
        (is (nil? (:value result))))
      ;; Even a newly configured provider accepting a replacement bearer
      ;; cannot transfer an issuance from the original authenticated carrier.
      (let [result (server/invoke-current
                     (assoc ctx :auth-provider (auth/single-token-provider "next-session"))
                     {:headers {"authorization" "Bearer next-session"}} input)]
        (is (false? (:ok result)))
        (is (nil? (:value result))))
      (is (empty? @calls)))
    {:principal nil :auth-provider (auth/single-token-provider "first-session")})
  (fixture
    (fn [{:keys [ctx request input calls]}]
      (let [result (server/invoke-current
                     (assoc ctx :auth-provider (auth/single-token-provider "first-session")) request input)]
        (is (false? (:ok result)))
        (is (nil? (:value result))))
      (is (empty? @calls)))
    {:principal nil})
  (let [provider (reify auth/AuthProvider
                   (authenticate [_ _] {:authenticated? true :user-id "other"}))
        error (try
                (fixture (fn [_] (is false "provider cannot impersonate the bound tenancy subject"))
                         {:auth-provider provider})
                (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (is (= :server-session-changed (:reason error)))))


(deftest authenticated-provider-does-not-replace-fresh-tenancy-authority
  (doseq [stage [:before :after]
          change [:roles :org :session]]
    (fixture
      (fn [{:keys [ctx request input on-execute calls]}]
        (let [changed? (atom (= :before stage))
              ctx (assoc ctx :request-scope
                         (fn [_ received thunk]
                           (is (identical? request received))
                           (let [principal (cond-> tenancy/*current-principal*
                                             (and @changed? (= change :roles)) (assoc :role-ids [])
                                             (and @changed? (= change :session)) (assoc :session-id "replaced"))]
                             (binding [tenancy/*current-principal* principal
                                       tenancy/*current-org* (if (and @changed? (= change :org))
                                                               "other-team" tenancy/*current-org*)]
                               (thunk)))))]
          (reset! on-execute #(reset! changed? true))
          (let [result (server/invoke-current ctx request input)]
            (is (false? (:ok result)))
            (is (nil? (:value result)))
            (is (not (contains? result :code)))
            (is (= (if (= :before stage) 0 1)
                   (count (filter #(= :run (first %)) @calls)))))))
      {:auth-provider (reify auth/AuthProvider
                        (authenticate [_ _] {:authenticated? true :user-id "owner-a"}))})))
