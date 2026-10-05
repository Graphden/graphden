(ns ^:integration ^:serial graphden.executor.browser-snapshot-integration-test
  "Pause a real PostgreSQL snapshot between graph tables while CRUD commits.
   Serial because the barrier replaces the graph-read boundary globally."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.browser-preview :as preview]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.test-infra.account-menu-cases :as account-menu]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.graph-rows :as graph-rows]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once
  (ga/fixture (ns-name *ns*))
  (impls/impls-fixture "storage" "branches"))


(defn- setup-copy!
  [ns-path]
  (let [ctx (assoc (:ctx ga/*bootstrap*)
                   :rich-types-atom (registry/fork-rich-types-atom (registry/active-rich-types-atom))
                   :per-org-rich-atom (registry/fork-per-org-rich-atom (registry/active-per-org-rich-atom)))
        id (records/fn-id ns-path :leaf)]
    ((impls/impl-of :sync-fn-defs-branch!)
     {:branch-id (vs/current-branch-id (:storage ctx))
      :fn-defs [{:name :leaf :namespace ns-path :parent :const
                 :args {:value {:value "before"}}}]} ctx)
    {:ctx ctx :id id
     :binding (first (sp/query-entities (:storage ctx) :binding {:fn-id id}))}))


(defn- edit-value!
  [{:keys [ctx binding]} value]
  (recheck/call-with-ctx-slices
    ctx
    #(entities/apply-update-core
       {:entity-type :binding :type-str "binding" :id-uuid (:id binding)
        :form-data {} :entity-data {:value value}} ctx)))


(defn- exported-value
  [result id]
  (let [leaf (first (filter #(= (str id) (:id %)) (:functions result)))]
    (plan/decode-value (get-in leaf [:args 0 :expr :value]))))


(deftest crud-between-table-reads-cannot-mix-graph-versions-or-live-policy
  (binding [br/*active-router-override* (atom nil)
            diag/*diagnostics-override* (atom {})]
    (let [{:keys [ctx id binding] :as copy} (setup-copy! "snapshot.concurrent")
          first-table-read (promise)
          writer (future
                   (if (= :ready (deref first-table-read 30000 :timeout))
                     (edit-value! copy "after")
                     {:error :reader-timeout}))
          ;; This uses the same five-table protocol as graph-rows/read-all;
          ;; the sole change is a deterministic scheduling barrier.
          read-between-writes
          (fn [storage]
            (let [fns (vec (sp/query-entities storage :fn {}))]
              (deliver first-table-read :ready)
              (let [result (deref writer 30000 ::timeout)]
                (when-not (= (:id binding) (:updated result))
                  (throw (ex-info "Concurrent CRUD did not complete" {:result result}))))
              {:fns fns
               :slots (vec (sp/query-entities storage :slot {}))
               :fn-slots (vec (sp/query-entities storage :fn-slot {}))
               :bindings (vec (sp/query-entities storage :binding {}))
               :list-items (vec (sp/query-entities storage :binding-list-item {}))}))]
      (try
        (let [result (with-redefs [graph-rows/read-all read-between-writes]
                       (preview/export-current ctx {:view id}))]
          (is (= "before" (exported-value result id))
              "the read transaction and its frozen original policy describe the old graph")
          (is (= (:id binding) (:updated @writer)))
          (is (= "after" (:value (sp/read-entity (:storage ctx) :binding (:id binding)))))
          (is (= "after" (exported-value (preview/export-current ctx {:view id}) id))
              "the following export sees the committed edit and its new checked source"))
        (finally
          (deliver first-table-read :cancelled)
          (future-cancel writer))))))


(deftest new-signature-cannot-authorize-an-old-cached-graph
  (binding [br/*active-router-override* (atom nil)
            diag/*diagnostics-override* (atom {})]
    (let [{:keys [ctx id binding] :as copy} (setup-copy! "snapshot.stale")
          old (snapshot/read-snapshot (:storage ctx))]
      (is (= (:id binding) (:updated (edit-value! copy "replacement"))))
      (let [error (try
                    (snapshot/export-snapshot old (:base-fns ctx) {:view id}
                                              (snapshot/capture-policy @(:rich-types-atom ctx)))
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :type-source-mismatch (:reason error)))
        (is (= id (:fn-id error)))
        (is (= #{:type :reason :fn-id} (set (keys error))))))))


(deftest ordinary-account-menu-import-exports-through-the-http-handler-immediately
  (binding [br/*active-router-override* (atom nil)
            diag/*diagnostics-override* (atom {})]
    (let [ctx (:ctx ga/*bootstrap*)
          graph-ns "snapshot.account-menu"
          definitions (:fns (edn/read-string (slurp "resources/packages/app/ui-account-menu/fns.edn")))
          entries (into {} (map (fn [[entry n]] [entry (records/fn-id graph-ns n)]))
                        {:initial :account-menu-initial :update :account-menu-update :view :account-menu-view})
          query (str/join "&" (map (fn [[entry id]] (str (name entry) "=" id)) entries))]
      ((impls/impl-of :sync-fn-defs-branch!)
       {:branch-id (vs/current-branch-id (:storage ctx))
        :fn-defs (mapv #(assoc % :namespace graph-ns) definitions)} ctx)
      ;; No initial graph execution or manual type warmup occurs before this
      ;; ordinary handler. Its query-param/parse-uuid graphs must pass values,
      ;; never identities of the parsing functions, to the export boundary.
      (let [response (ga/exec-handler :_ui-preview-plan-handler
                                      {:request-method :get :uri "/ui-preview/plan" :query-string query})
            exported (json/parse-string (:body response) true)]
        (is (= 200 (:status response)) exported)
        (is (= (update-vals entries str) (:entries exported)))
        (is (= #{"const" "list" "get" "assoc" "zipmap" "if" "equal?" "add" "mod" "count" "hiccup"}
               (set (map :op (:primitives exported))))))
      (doseq [{:keys [entry inputs expected]} (account-menu/cases)]
        (let [response (ga/exec-handler
                         :execute-handler
                         {:request-method :post :uri "/api/execute"
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string
                                  {:fn-id (str (records/fn-id graph-ns entry))
                                   :args inputs :timeout-ms 10000})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)) body)
          (is (= "succeeded" (:status body)) body)
          (is (= (json/parse-string (json/generate-string expected) true)
                 (:result body)) (str entry " " inputs))))
      (let [response (ga/exec-handler :_ui-preview-plan-handler
                                      {:request-method :get :uri "/ui-preview/plan"
                                       :query-string "initial=not-a-uuid"})
            body (json/parse-string (:body response) true)]
        (is (= 422 (:status response)))
        (is (= {:type "browser-plan/unsupported" :reason "invalid-entries"} body))))))
