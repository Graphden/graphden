(ns ^:integration ^:serial graphden.executor.browser-server-source-integration-test
  "Prove the shipped query closure against stored source and frozen types."
  (:require
    [cheshire.core :as json]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.accounts.crypto :as crypto]
    [graphden.editor.components :as components]
    [graphden.editor.root-plan :as root-plan]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-server-source :as server-source]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as compiled]
    [graphden.executor.interface :as exec]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as record-types]
    [graphden.packages.sync :as packages]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(deftest shipped-account-query-has-a-closed-read-only-contract
  (let [ctx (:ctx ga/*bootstrap*)
        target (ids/fn-id "app.ui-account-menu" :_account-menu-branch-query)]
    (is (identical? @(find-var 'graphden.packages.storage.branches.impls/current-branch-name)
                    (get (:base-fns ctx) :current-branch-name))
        "Sibling golden-clone contexts retain the freshly loaded trusted reader")
    (recheck/call-with-ctx-slices
      ctx
      (fn []
        (let [policy (snapshot/capture-context-policy ctx)
              authorizations (atom [])
              proof (source/with-snapshot
                      (:storage ctx)
                      (fn [storage _]
                        (server-source/capture
                          ctx storage
                          (fn [row permissions]
                            (swap! authorizations conj [(:id row) permissions])
                            (when-not (and (= target (:id row))
                                           (= {:execute? true :server-target? true} permissions))
                              (throw (ex-info "No source grant" {:type :authz/forbidden}))))
                          policy target)))]
          (is (= [[target {:execute? true :server-target? true}]] @authorizations))
          (is (= {:query :text} (:input-type proof)))
          (is (= {:label :text :branch-name :text} (:output-type proof)))
          (is (string? (:fingerprint proof)))
          (is (= {:label "Branch" :branch-name "main"}
                 (exec/execute ctx target {:input {:query ""}})))
          (is (= {:label "Branch" :branch-name ""}
                 (exec/execute ctx target {:input {:query "never-a-branch"}}))))))))


(deftest actual-descriptor-invocation-does-not-write-execution-audit-in-read-only-snapshot
  (let [ctx (:ctx ga/*bootstrap*)
        query (ids/fn-id "app.ui-account-menu" :account-menu-query)
        handler (ids/fn-id "app.editor" :_editor-handler)
        root-slot (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root)
        binding (first (sp/query-entities (:storage ctx) :binding {:fn-id handler :slot-id root-slot}))
        request {:request-method :post :uri "/api/ui/server-call" :headers {"host" "localhost"}}
        before (count (sp/query-entities (:storage ctx) :fn-execution {}))]
    (epoch/seed-watermark! (:storage ctx))
    (recheck/call-with-ctx-slices
      ctx
      (fn []
        (let [policy (snapshot/capture-context-policy ctx)
              descriptors (with-redefs [snapshot/export-snapshot
                                        (fn [captured _ _ _]
                                          (get-in captured [:server-plan-options :server-descriptors]))]
                            (source/with-snapshot
                              (:storage ctx)
                              (fn [storage authorize!]
                                (server/export-client-plan ctx storage authorize! policy {:query query} request
                                                           {:fn-id handler :slot-id root-slot
                                                            :ref-fn-id (:ref-fn-id binding)}))))
              descriptor (get descriptors [query (ids/slot-id (ids/fn-id "core.system" :server-call) :func)])
              result (server/invoke-current ctx request {:descriptor descriptor
                                                         :input (wire/encode-value {:query ""})})]
          (is (true? (:ok result)) (pr-str result))
          (when (:ok result)
            (is (= {:label "Branch" :branch-name "main"} (wire/decode-value (:value result)))))
          (is (= before (count (sp/query-entities (:storage ctx) :fn-execution {})))))))))


(deftest golden-clone-retains-package-type-ownership-without-owning-tenant-restores
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        boot-type #uuid "68219998-9b7e-5d68-a727-b5a2b5ee6921"
        tenant-shape [:fn {(keyword (str "tenant-" (random-uuid))) :text} :text]
        tenant-type (:id (first (record-types/inline-fn-type-rows-from-form tenant-shape)))]
    (is (owned/owned-fn-id? boot-type) "The golden clone uses the real trusted boot ownership registry")
    (is (not (owned/owned-fn-id? tenant-type)))
    (packages/sync-bundle! storage [{:namespace "tenant-restore" :name :callback-container
                                     :type {:callback tenant-shape}}])
    (is (some? (sp/read-entity storage :fn tenant-type)))
    (compiled/rebuild! ctx)
    (is (owned/owned-fn-id? boot-type))
    (is (not (owned/owned-fn-id? tenant-type)) "Restoring graph data cannot promote tenant types to trusted package ownership")))


(deftest actual-root-wire-retains-an-opaque-query-without-server-body-or-captures
  (let [ctx (:ctx ga/*bootstrap*)
        carrier "private-root-session-carrier"
        bearer "private-root-bearer-carrier"
        content "private-root-request-content"
        request {:request-method :post :uri "/api/ui/server-call"
                 :headers {"host" "localhost" "authorization" (str "Bearer " bearer)}
                 :cookies {"gd_session" {:value carrier}}
                 :body {:private [:secret content]} :query-params {"private" content}}
        target (ids/fn-id "app.ui-account-menu" :_account-menu-branch-query)
        _ (epoch/seed-watermark! (:storage ctx))
        failure components/failure-response
        result (with-redefs [components/failure-response
                             (fn [error]
                               (assoc (failure error) :diagnostic
                                      (select-keys (ex-data error) [:type :reason :fn-id :slot-id :arg :name])))]
                 (root-plan/export-current ctx request))]
    (is (:ok result) (pr-str result))
    (when (:ok result)
      (let [serialized (json/generate-string (:plan result))
            program (str "const g=require('./resources/packages/app/ui-preview/browser-runtime.js');let text='';"
                         "process.stdin.on('data',x=>text+=x);process.stdin.on('end',()=>{"
                         "const plan=JSON.parse(text),supplied=Object.fromEntries(plan.inputs[plan.entries.root].required.map(n=>[n,new Map()]));"
                         "const root=g.createRuntime(plan,{operationLimit:1000000,"
                         "serverCall:(descriptor,input)=>({descriptor,input:g.encode(input)})}).run('root',supplied);"
                         "const menu=root.get(g.keyword('account-menu'));"
                         "const query=menu.get(g.keyword('query'));"
                         "if(!g.isCallable(query))throw Error('Missing retained account query');"
                         "process.stdout.write(JSON.stringify(g.invoke(query,new Map([[g.keyword('query'),'']]))));});")
            invoked (sh/sh "node" "-e" program :in serialized)]
        (doseq [private [carrier bearer content (crypto/sha256-hex carrier) (crypto/sha256-hex bearer)]]
          (is (not (str/includes? serialized private)) "Request values and auth fingerprints remain exclusively server-side"))
        (is (not (str/includes? serialized (str target))))
        (is (not (str/includes? serialized (json/generate-string ["string" "Branch"]))))
        (is (zero? (:exit invoked)) (:err invoked))
        (when (zero? (:exit invoked))
          (let [input (json/parse-string (:out invoked) true)
                response (server/invoke-current ctx request input)]
            (is (= #{:token} (set (keys (:descriptor input)))))
            (is (:ok response) (pr-str response))
            (is (= {:label "Branch" :branch-name "main"} (some-> (:value response) wire/decode-value)))))))))
