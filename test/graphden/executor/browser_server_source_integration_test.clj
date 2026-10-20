(ns ^:integration ^:serial graphden.executor.browser-server-source-integration-test
  "Prove the shipped query closure against stored source and frozen types."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
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
