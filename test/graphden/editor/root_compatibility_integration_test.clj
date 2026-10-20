(ns ^:integration ^:serial graphden.editor.root-compatibility-integration-test
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.type-check :as type-check]
    [graphden.editor.component-templates :as templates]
    [graphden.editor.components :as components]
    [graphden.editor.root-plan :as root-plan]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as record-types]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as ga]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as versioned]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- copied-definitions
  [module path edited-name edit]
  (mapv (fn [definition]
          (cond-> (assoc definition :namespace path)
            (= edited-name (:name definition)) edit))
        (:fns (edn/read-string (slurp (str "resources/packages/app/" module "/fns.edn"))))))


(defn- setup-selection!
  [ctx owner]
  (let [storage (:storage ctx)
        branch (versioned/current-branch-id storage)
        path (str "root-compat-" (random-uuid))
        menu (str path ".menu") recents (str path ".recents")
        roles (fn [ns-path prefix]
                (mapv (fn [role] [role (keyword ns-path (str prefix "-" (name role)))]) [:initial :update :view]))
        definitions
        (concat (copied-definitions "ui-account-menu" menu :settings-row #(assoc-in % [:args :children] ["Personal settings"]))
                (copied-definitions "ui-recents" recents :_caption #(assoc-in % [:args :then] "My trail"))
                (templates/component-definitions path "menu" :app.ui-account-menu/account-menu-view (roles menu "account-menu"))
                (templates/component-definitions path "recents" :app.ui-recents/recents-view (roles recents "recents"))
                [{:name :selection :namespace path :parent :list
                  :return-type :app.ui-components/ui-component-manifest
                  :args {:items [(keyword path "_menu-descriptor") (keyword path "_recents-descriptor")]}}])
        selected (ids/fn-id path :selection)]
    ((get (:base-fns ctx) :sync-fn-defs-branch!) {:branch-id branch :fn-defs (vec definitions)} ctx)
    (sp/create-entity storage :ui-pref {:owner-id owner :key "components"
                                        :value {:fn-id (str selected) :branch-id (str branch) :org (tenancy/current-org)}})
    (epoch/seed-watermark! storage)
    {:selected selected :menu (ids/fn-id menu :account-menu-view) :recents (ids/fn-id recents :recents-view)}))


(defn- run-views
  [plan]
  (let [script (str "const g=require('./resources/packages/app/ui-preview/browser-runtime.js');let s='';"
                    "process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>{"
                    "const plan=JSON.parse(s),supplied=Object.fromEntries(plan.inputs[plan.entries.root].required.map(n=>[n,new Map()]));"
                    "const root=g.createRuntime(plan,{operationLimit:1000000}).run('root',supplied);"
                    "const key=g.keyword,field=(m,n)=>m.get(key(n)),m=x=>new Map(Object.entries(x).map(([k,v])=>[key(k),v]));"
                    "const menu=field(root,'account-menu'),recents=field(root,'recents');"
                    "const state=g.invoke(field(menu,'initial'),m({}));"
                    "const menuView=g.invoke(field(menu,'view'),m({state,theme:m({accent:'#137c74','canvas-background':'#eff1f0'}),server:m({phase:'idle',label:'','branch-name':''})}));"
                    "const inputs=m({pins:[],trail:[],selected:null,searching:false});"
                    "const recentState=g.invoke(field(recents,'initial'),m({inputs}));"
                    "const recentView=g.invoke(field(recents,'view'),m({state:recentState,inputs}));"
                    "process.stdout.write(JSON.stringify([g.encode(menuView),g.encode(recentView)]));});")]
    (sh/sh "node" "-e" script :in (json/generate-string plan))))


(defn- export-root
  [ctx]
  (let [failure components/failure-response
        checker check/check-fn-def!
        checked-error (atom nil)]
    (with-redefs [check/check-fn-def!
                  (fn [& args]
                    (try (apply checker args)
                         (catch Exception error
                           (reset! checked-error (select-keys (ex-data error)
                                                              [:type :reason :fn-name :arg :expected :actual :parent-name]))
                           (when (= :bindings/widening-type (:type (ex-data error)))
                             (swap! checked-error assoc :message (Exception/.getMessage error)))
                           (throw error))))
                  components/failure-response
                  (fn [error]
                    (assoc (failure error) :diagnostic
                           (assoc (select-keys (ex-data error) [:type :reason :fn-id :slot-id :arg :name])
                                  :checker @checked-error)))]
      (root-plan/export-current ctx))))


(deftest existing-personal-copies-remain-selected-and-source-guarded
  (binding [router/*active-router-override* (atom nil)]
    (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx) owner (str (random-uuid))
          selected (setup-selection! ctx owner)]
      (with-redefs [tenancy/current-user-id (constantly owner)
                    tenancy/current-personal-namespace (constantly nil)]
        (let [result (export-root ctx)
              rendered (when (:ok result) (run-views (:plan result)))]
          (is (:ok result) (pr-str result))
          (is (zero? (:exit rendered -1)) (:err rendered))
          (is (re-find #"Personal settings" (:out rendered "")))
          (is (re-find #"My trail" (:out rendered ""))))
        (with-redefs [tenancy/current-user-id (constantly (str (random-uuid)))]
          (let [result (export-root ctx)]
            (is (:ok result) (pr-str result))
            (is (not-any? #(= (str (:menu selected)) (:id %)) (get-in result [:plan :functions])))))
        (let [read-branch versioned/current-branch-id]
          (with-redefs [versioned/current-branch-id (fn [storage]
                                                      (when (read-branch storage) (random-uuid)))]
            (is (false? (:ok (export-root ctx))))))
        (with-redefs [tenancy/browser-source-policy
                      (fn [_]
                        (fn [row & _]
                          (when (= (:id row) (:menu selected))
                            (throw (ex-info "Revoked personal source" {:type :authz/forbidden})))))]
          (let [result (export-root ctx)]
            (is (false? (:ok result)))
            (is (not (contains? result :plan)))))
        (writer/with-write [storage :graph]
                           (sp/delete-entity storage :fn (:selected selected)))
        (epoch/seed-watermark! (:storage ctx))
        (is (nil? (sp/read-entity (:storage ctx) :fn (:selected selected))))
        (let [result (export-root ctx)]
          (is (false? (:ok result)))
          (is (= :source-missing (get-in result [:diagnostic :reason])))
          (is (not (contains? result :plan))))))))


(deftest actual-jsonb-generic-callback-is-restored-only-from-its-exact-contract
  (let [storage (:storage (:ctx ga/*bootstrap*))
        declaration (first (filter #(= :assoc-fn (:name %))
                                   (:fns (edn/read-string (slurp "resources/packages/core/collections/fns.edn")))))
        expected (first (filter #(= :fn (:kind %))
                                (record-types/inline-fn-type-rows-from-form (get-in declaration [:args :value :type]))))
        stored (sp/read-entity storage :fn (:id expected))
        captured {:graph {:fns [stored] :slots [] :fn-slots [] :bindings [] :list-items []}
                  :namespaces []}
        read-checked #(sp/read-entity (#'snapshot/snapshot-storage %) :fn (:id expected))]
    (is (some? stored))
    (is (not= (:constraint expected) (:constraint stored)) "Actual PG preserves JSONB generic variable spellings as strings")
    (is (= (:constraint expected) (:constraint (read-checked captured))) "Trusted boot ownership plus exact canonical type restores checker variables")
    (is (= (:constraint stored) (:constraint (sp/read-entity storage :fn (:id expected)))) "Source rows remain unchanged")
    (is (= :type-contract-source-mismatch
           (try
             (read-checked (assoc-in captured [:graph :fns 0 :constraint 1 :arg] :b))
             nil
             (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))


(deftest actual-jsonb-account-styles-retain-their-element-contract
  (let [storage (:storage (:ctx ga/*bootstrap*))
        producer (ids/fn-id "app.ui-account-menu" :account-menu-styles)
        style-list (ids/fn-id "app.ui-account-menu" :_account-menu-styles)
        style (ids/fn-id "app.ui-account-menu" :_account-menu-style)]
    (is (= style-list (:return-type-fn-id (sp/read-entity storage :fn producer))))
    (is (= style (:element-fn-id (sp/read-entity storage :fn style-list))))
    (is (= :app.ui-account-menu/_account-menu-styles
           (:return-type (type-check/reconstruct-fn-def storage producer))))))
