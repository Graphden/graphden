(ns ^:integration ^:serial graphden.executor.browser-snapshot-integration-test
  "Pause a real PostgreSQL snapshot between graph tables while CRUD commits.
   Serial because the barrier replaces the graph-read boundary globally."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.editor.components :as components]
    [graphden.editor.root-plan :as root-plan]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.browser-preview :as preview]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.owned :as owned]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.account-menu-cases :as account-menu]
    [graphden.test-infra.golden-app :as ga]
    [graphden.types.check :as check]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- setup-copy!
  [ns-path]
  (let [ctx (assoc (:ctx ga/*bootstrap*)
                   :rich-types-atom (registry/fork-rich-types-atom (registry/active-rich-types-atom))
                   :per-org-rich-atom (registry/fork-per-org-rich-atom (registry/active-per-org-rich-atom)))
        id (records/fn-id ns-path :leaf)]
    ((get (:base-fns ctx) :sync-fn-defs-branch!)
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


(defn- export-editor-root
  [ctx]
  ;; Direct fixture exports omit the real request router's epoch admission.
  (epoch/seed-watermark! (:storage ctx))
  (root-plan/export-current ctx))


(deftest editor-root-is-selected-from-handler-binding
  (binding [br/*active-router-override* (atom nil)]
    (let [ctx (:ctx ga/*bootstrap*)
          failure-response components/failure-response
          checker check/check-fn-def!
          checked-error (atom nil)
          _ (is (owned/owned-impl? (ids/fn-id "storage.branches" :current-branch-name)
                                   (get (:base-fns ctx) :current-branch-name))
                "Golden-clone context retains a trusted package reader")
          result (with-redefs [check/check-fn-def!
                               (fn [& args]
                                 (try (apply checker args)
                                      (catch Exception error
                                        (reset! checked-error
                                                (assoc (select-keys (ex-data error)
                                                                    [:type :reason :fn-name :arg :expected :actual :parent-name])
                                                       :message (Exception/.getMessage error)))
                                        (throw error))))
                               components/failure-response
                               (fn [error]
                                 (assoc (failure-response error) :test-error
                                        (assoc (select-keys (ex-data error) [:type :reason :fn-id])
                                               :checker @checked-error)))]
                   (export-editor-root ctx))]
      (is (true? (:ok result))
          (pr-str {:result result
                   :selector-bindings
                   (mapv #(select-keys % [:slot-id :ref-fn-id :value-present :terminal
                                          :list-append :list-closed :resolver-fn-id])
                         (sp/query-entities (:storage ctx) :binding
                                            {:fn-id (ids/fn-id "app.editor" :_editor-handler)}))}))
      (is (= (str (ids/fn-id "app.ui-config" :editor-root))
             (get-in result [:plan :entries "root"])))
      (is (some #(= "call" (:op %)) (get-in result [:plan :primitives])))
      (is (not-any? #(= (str (ids/fn-id "app.editor" :page)) (:id %))
                    (get-in result [:plan :functions]))))))


(deftest editor-root-contract-preserves-the-executable-http-router
  (binding [br/*active-router-override* (atom nil)]
    (let [ctx (:ctx ga/*bootstrap*)]
      (epoch/seed-watermark! (:storage ctx))
      (is (fn? (exec/execute ctx (ids/fn-id "app.server" :_router) {})))
      (let [response (ga/exec-handler :_editor-root-plan-handler
                                      {:request-method :get :uri "/api/ui/root/plan"})
            result (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (true? (:ok result)))))))


(deftest editor-root-source-visibility-does-not-grant-execute
  (binding [br/*active-router-override* (atom nil)]
    (let [root (ids/fn-id "app.ui-config" :editor-root)
          handler (ids/fn-id "app.editor" :_editor-handler)
          checked (atom [])
          policy (fn [_]
                   (fn [row & [permissions]]
                     (swap! checked conj [(:id row) permissions])
                     ;; The private handler body is never browser source.
                     (when (or (= handler (:id row))
                               (and (= root (:id row)) (:execute? permissions)))
                       (throw (ex-info "Source-only principal" {:type :authz/forbidden})))))
          result (with-redefs [tenancy/browser-source-policy policy]
                   (export-editor-root (:ctx ga/*bootstrap*)))]
      (is (= 403 (:http-status result)))
      (is (some #(= [root {:execute? true}] %) @checked))
      (is (not-any? #(= handler (first %)) @checked))
      (is (not (contains? result :plan))))))


(deftest editor-root-does-not-publish-the-private-selector-handler
  (binding [br/*active-router-override* (atom nil)]
    (let [handler (ids/fn-id "app.editor" :_editor-handler)
          policy (fn [_]
                   (fn [row & _]
                     (when (= handler (:id row))
                       (throw (ex-info "Handler source denied" {:type :authz/forbidden})))))
          result (with-redefs [tenancy/browser-source-policy policy]
                   (export-editor-root (:ctx ga/*bootstrap*)))]
      (is (true? (:ok result)) (pr-str result))
      (is (not-any? #(= (str handler) (:id %)) (get-in result [:plan :functions]))))))


(deftest editor-root-and-captured-dependencies-still-require-source
  (binding [br/*active-router-override* (atom nil)]
    (doseq [denied [(ids/fn-id "app.ui-config" :editor-root)
                    (ids/fn-id "app.ui-config" :legacy-editor-root)]]
      (let [policy (fn [_]
                     (fn [row & _]
                       (when (= denied (:id row))
                         (throw (ex-info "Source denied" {:type :authz/forbidden})))))
            result (with-redefs [tenancy/browser-source-policy policy]
                     (export-editor-root (:ctx ga/*bootstrap*)))]
        (is (= 403 (:http-status result)))
        (is (not (contains? result :plan)))))))


(deftest editor-root-rejects-unsupported-selector-binding-forms
  (binding [br/*active-router-override* (atom nil)]
    (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
          handler (ids/fn-id "app.editor" :_editor-handler)
          slot (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root)
          selector-slot (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root-selector)
          binding (first (sp/query-entities storage :binding {:fn-id handler :slot-id slot}))
          selector (first (sp/query-entities storage :binding {:fn-id handler :slot-id selector-slot}))]
      (doseq [change [{:value-present true} {:terminal true} {:list-append true}
                      {:list-closed true} {:resolver-fn-id (ids/fn-id "core.logic" :const)}
                      {:type-override-fn-id (ids/fn-id "core.logic" :const)}]]
        (try
          (sp/update-entity storage :binding (:id binding) change)
          (let [result (export-editor-root ctx)]
            (is (= 422 (:http-status result)))
            (is (not (contains? result :plan))))
          (finally
            (sp/update-entity storage :binding (:id binding) (select-keys binding (keys change))))))
      (doseq [change [{:ref-fn-id (random-uuid)} {:value-present true}]]
        (try
          (sp/update-entity storage :binding (:id selector) change)
          (let [result (export-editor-root ctx)]
            (is (= 422 (:http-status result)))
            (is (not (contains? result :plan))))
          (finally
            (sp/update-entity storage :binding (:id selector) (select-keys selector (keys change)))))))))


(deftest editor-root-rejects-a-missing-principal-before-selection
  (let [result (with-redefs [tenancy/browser-source-policy
                             (fn [_] (throw (ex-info "Missing principal" {:type :authz/forbidden})))]
                 (export-editor-root (:ctx ga/*bootstrap*)))]
    (is (= 403 (:http-status result)))
    (is (not (contains? result :plan)))))


(deftest editor-root-epoch-change-cannot-certify-an-export
  (binding [br/*active-router-override* (atom nil)]
    (let [epoch (atom 0)
          result (with-redefs [context/invalidation-epoch (fn [_] (swap! epoch inc))]
                   (export-editor-root (:ctx ga/*bootstrap*)))]
      (is (false? (:ok result)))
      (is (= "policy-refresh-required" (:code result)))
      (is (not (contains? result :plan))))))


(deftest editor-root-stale-watermark-refuses-before-source-collection
  (binding [br/*active-router-override* (atom nil)]
    (let [collected? (atom false)
          collect source/collect-closure
          result (with-redefs [epoch/validated-watermark (constantly 0)
                               source/collect-closure
                               (fn [& args] (reset! collected? true) (apply collect args))]
                   (root-plan/export-current (:ctx ga/*bootstrap*)))]
      (is (= "policy-refresh-required" (:code result)))
      (is (false? @collected?))
      (is (not (contains? result :plan))))))


(deftest editor-root-revocation-is-checked-on-the-next-export
  (binding [br/*active-router-override* (atom nil)]
    (let [allowed? (atom true)
          policy (fn [_]
                   (let [allowed @allowed?]
                     (fn [_ & _]
                       (when-not allowed
                         (throw (ex-info "Grant revoked" {:type :authz/forbidden}))))))]
      (with-redefs [tenancy/browser-source-policy policy]
        (is (true? (:ok (export-editor-root (:ctx ga/*bootstrap*))))))
      (reset! allowed? false)
      (with-redefs [tenancy/browser-source-policy policy]
        (let [result (export-editor-root (:ctx ga/*bootstrap*))]
          (is (= 403 (:http-status result)))
          (is (not (contains? result :plan))))))))


(deftest changing-only-handler-root-binding-selects-another-browser-graph
  (binding [br/*active-router-override* (atom nil)]
    (let [ctx (assoc (:ctx ga/*bootstrap*)
                     :rich-types-atom (registry/fork-rich-types-atom (registry/active-rich-types-atom))
                     :per-org-rich-atom (registry/fork-per-org-rich-atom (registry/active-per-org-rich-atom)))
          storage (:storage ctx)
          handler (ids/fn-id "app.editor" :_editor-handler)
          slot (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root)
          binding (first (sp/query-entities storage :binding {:fn-id handler :slot-id slot}))
          root (ids/fn-id "snapshot.root" :alternate)]
      ((get (:base-fns ctx) :sync-fn-defs-branch!)
       {:branch-id (vs/current-branch-id storage)
        :fn-defs [{:name :_root-bootstrap-body :namespace "snapshot.root" :parent :const :lambda-params []
                   :args {:value {:value {:legacy true}}}}
                  {:name :alternate :namespace "snapshot.root" :parent :call
                   :args {:func {:ref :snapshot.root/_root-bootstrap-body}
                          :arg {:as :request :type :keyword-map}}}]} ctx)
      (try
        (sp/update-entity storage :binding (:id binding) {:ref-fn-id root})
        (let [result (export-editor-root ctx)]
          (is (true? (:ok result)) (pr-str result))
          (is (= (str root) (get-in result [:plan :entries "root"])))
          (is (= "request" (:argument-name result))))
        (finally
          (sp/update-entity storage :binding (:id binding) {:ref-fn-id (:ref-fn-id binding)}))))))


(deftest graph-bound-selector-uses-an-authorized-personal-hof
  (binding [br/*active-router-override* (atom nil)
            tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
    (let [ctx (assoc (:ctx ga/*bootstrap*)
                     :rich-types-atom (registry/fork-rich-types-atom (registry/active-rich-types-atom))
                     :per-org-rich-atom (registry/fork-per-org-rich-atom (registry/active-per-org-rich-atom)))
          storage (:storage ctx)
          home-name (str "personal-root-" (random-uuid))
          home (sp/create-entity storage :ns {:name home-name})
          root (ids/fn-id home-name :editor-root)]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      ((get (:base-fns ctx) :sync-fn-defs-branch!)
       {:branch-id (vs/current-branch-id storage)
        :fn-defs [{:name :_body :namespace home-name :parent :const :lambda-params []
                   :args {:value {:value {:legacy true}}}}
                  {:name :editor-root :namespace home-name :parent :call
                   :args {:func {:ref (keyword home-name "_body")}
                          :arg {:as :request :type :keyword-map}}}]} ctx)
      (epoch/seed-watermark! storage)
      (let [result (export-editor-root ctx)]
        (is (:ok result) (pr-str result))
        (is (= (str root) (get-in result [:plan :entries "root"])))
        (is (= "request" (:argument-name result)))
        (is (nil? (:component-selection-id result))))
      (with-redefs [tenancy/browser-source-policy
                    (fn [_]
                      (fn [row & _]
                        (when (= root (:id row))
                          (throw (ex-info "Revoked root" {:type :authz/forbidden})))))]
        (let [result (export-editor-root ctx)]
          (is (= 403 (:http-status result)))
          (is (not (contains? result :plan)))))
      (sp/update-entity storage :fn root {:name "retired-editor-root"})
      (epoch/seed-watermark! storage)
      (let [result (export-editor-root ctx)]
        (is (:ok result) (pr-str result))
        (is (= (str (ids/fn-id "app.ui-config" :editor-root))
               (get-in result [:plan :entries "root"]))))
      (is (= (:id home) (:namespace-id (sp/read-entity storage :fn root)))))))


(deftest crud-between-table-reads-cannot-mix-graph-versions-or-live-policy
  (binding [br/*active-router-override* (atom nil)
            diag/*diagnostics-override* (atom {})]
    (let [{:keys [ctx id binding] :as copy} (setup-copy! "snapshot.concurrent")
          first-table-read (promise)
          writer (future
                   (if (= :ready (deref first-table-read 30000 :timeout))
                     (edit-value! copy "after")
                     {:error :reader-timeout}))
          collect source/collect-closure
          ;; Pin the actual collector's transaction with its first function
          ;; read, then commit CRUD before it reads bindings and namespaces.
          read-between-writes
          (fn [storage roots authorize!]
            (when-not (sp/read-entity storage :fn id)
              (throw (ex-info "Snapshot root missing" {})))
            (deliver first-table-read :ready)
            (let [result (deref writer 30000 ::timeout)]
              (when-not (= (:id binding) (:updated result))
                (throw (ex-info "Concurrent CRUD did not complete" {:result result}))))
            (collect storage roots authorize!))]
      (try
        (let [result (with-redefs [source/collect-closure read-between-writes]
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
      ((get (:base-fns ctx) :sync-fn-defs-branch!)
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
