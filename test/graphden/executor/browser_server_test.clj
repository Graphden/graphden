(ns ^:serial graphden.executor.browser-server-test
  "Exercise authorization and response retirement independently of transport."
  (:require
    [clojure.test :refer [deftest is]]
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
  [f]
  (let [target (random-uuid)
        site [(random-uuid) (random-uuid)]
        branch (random-uuid)
        calls (atom [])
        snapshots (atom 0)
        allowed? (atom true)
        fresh-execute? (atom true)
        evidence (atom "source/binding/captures/contract")
        on-execute (atom (fn [] nil))
        storage (assoc (remote/from-bundle {:fn [{:id target}]}) :branch-id branch)
        ctx {:storage storage :invalidation-count (atom 0)
             :execute-guard (fn [_ id]
                              (swap! calls conj [:guard id runtime/*execute-authorized*])
                              (when-not @allowed?
                                (throw (ex-info "Private target" {:type :authz/forbidden}))))}
        request {:headers {"authorization" "Bearer first-session"}}
        captured (fn [& _]
                   {:evidence @evidence :calls {site target}
                    :snapshot {:graph {:fns [{:id target}]}} :targets {target {:input-type {:query :text}
                                                                               :output-type {:label :text :branch-name :text}}}})]
    (reset! @(ns-resolve 'graphden.executor.browser-server 'issuances) {})
    (binding [tenancy/*current-org* "team"
              tenancy/*current-principal* {:authenticated? true :user-id "owner-a" :role-ids ["reader-role"]}
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
                :allowed? allowed? :fresh-execute? fresh-execute? :evidence evidence :on-execute on-execute})))))))


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
        (reset! on-execute #(reset! roles []))
        (let [result (server/invoke-current ctx request input)]
          (is (false? (:ok result)))
          (is (nil? (:value result)))
          (is (= 2 @scopes))))))
  (fixture
    (fn [{:keys [ctx request input calls]}]
      (let [ctx (assoc ctx :request-scope (fn [& _] {:status 403 :headers {} :body "refused"}))]
        (is (false? (:ok (server/invoke-current ctx request input))))
        (is (empty? @calls))))))
