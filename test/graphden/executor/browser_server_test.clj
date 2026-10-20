(ns ^:serial graphden.executor.browser-server-test
  "Exercise authorization and response retirement independently of transport."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.fn-execution :as execution]
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
                    :snapshot {} :targets {target {:input-type {:query :text}
                                                   :output-type {:label :text :branch-name :text}}}})]
    (reset! @(ns-resolve 'graphden.executor.browser-server 'issuances) {})
    (binding [tenancy/*current-org* "team"
              tenancy/*current-principal* {:authenticated? true :user-id "owner-a"}
              runtime/*execute-authorized* true]
      (with-redefs-fn
        {(ns-resolve 'graphden.executor.browser-server 'capture-client) captured
         #'source/with-snapshot (fn [st consume]
                                  (swap! snapshots inc)
                                  (consume st (constantly nil)))
         #'snapshot/export-snapshot (fn [captured _ _ _]
                                      (get-in captured [:server-plan-options :server-descriptors]))
         #'snapshot/capture-context-policy (fn [_] {})
         #'components/assert-current-policy! (fn [_] nil)
         #'recheck/call-with-ctx-slices (fn [_ thunk] (thunk))
         #'execution/apply-execute
         (fn [exec-ctx parsed row]
           (swap! calls conj [:run (:id row) runtime/*execute-authorized*
                              (:allowed-effects exec-ctx) (:args parsed)])
           (@on-execute)
           {:status :succeeded :runtime-effects #{:db}
            :result {:label "Branch" :branch-name "main" :secret "never serialize"}})}
        (fn []
          (let [descriptors (server/export-client-plan ctx storage (constantly nil) {}
                                                       {:root (first site)} request {})
                descriptor (get descriptors site)
                input {:descriptor descriptor :input (wire/encode-value {:query ""})}]
            (f {:ctx ctx :request request :input input :calls calls :snapshots snapshots
                :allowed? allowed? :evidence evidence :on-execute on-execute})))))))


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
                      (assoc input :descriptor (assoc (:descriptor input) :target (str (random-uuid))))
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
