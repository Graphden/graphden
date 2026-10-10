(ns ^:integration ^:serial graphden.editor.component-creation-integration-test
  "The normal graph handlers create a fixed bundle in one JDBC transaction."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.packages.records.ids :as records]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *destination-id* nil)


(defn- writable-destination-fixture
  [test-fn]
  (let [storage (:storage ga/*bootstrap*)
        destination (sp/create-entity storage :ns
                                      {:name (str "component-test-" (random-uuid))})]
    (binding [*destination-id* (:id destination)]
      (test-fn))))


(use-fixtures :once
  (ga/fixture (ns-name *ns*))
  (impls/impls-fixture "storage" "branches")
  writable-destination-fixture)


(defn- submit
  [handler input]
  (let [response (ga/exec-handler handler
                                  {:request-method :post :uri "/api/ui/components/create"
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string input)})]
    {:status (:status response) :body (json/parse-string (:body response) true)}))


(defn- preview
  []
  (submit :_ui-components-create-preview-handler {:owner "anonymous" :namespace-id (str *destination-id*)}))


(defn- apply-preview
  [reserved]
  (submit :_ui-components-create-apply-handler (get-in reserved [:body :request])))


(defn- ids
  [storage entity]
  (set (map :id (sp/query-entities storage entity {}))))


(defn- export-diagnosed
  "Keep diagnostic type shapes in test assertions, never endpoint responses."
  [ctx component]
  (let [diagnostics (atom [])
        watched [#'source/collect-manifest #'config/configuration
                 #'snapshot/validate-source! #'snapshot/export-snapshot #'check/check-fn-def!]
        wrappers (into {} (map (fn [v]
                                 [v (let [original @v]
                                      (fn [& args]
                                        (try (apply original args)
                                             (catch Exception error
                                               (swap! diagnostics conj
                                                      {:stage (:name (meta v))
                                                       :error (select-keys (ex-data error)
                                                                           [:type :reason :fn-id :fn-name :arg-name
                                                                            :expected :actual :declared :computed])})
                                               (throw error)))))])) watched)]
    {:result (with-redefs-fn wrappers #(components/export-current ctx {:component component}))
     :component component :diagnostics @diagnostics}))


(deftest created-manifest-exports-its-recents-controller
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        created (apply-preview (preview))
        manifest (get-in created [:body :manifest])
        id (get-in manifest [:roots :configuration-id])]
    (is (:ok (:body created)) (pr-str created))
    (if-let [prior (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "components"}))]
      (sp/update-entity storage :ui-pref (:id prior)
                        {:value {:fn-id id :branch-id (str (vs/current-branch-id storage)) :org "public"}})
      (sp/create-entity storage :ui-pref
                        {:owner-id "anonymous" :key "components" :org-id "public"
                         :value {:fn-id id :branch-id (str (vs/current-branch-id storage)) :org "public"}
                         :updated-at (java.time.Instant/now)}))
    (epoch/seed-watermark! storage)
    (let [{:keys [result] :as diagnosed} (export-diagnosed ctx "recents")]
      (is (:ok result) (pr-str diagnosed)))))


(defn- graph-identities
  [storage]
  (into {} (map (fn [entity] [entity (ids storage entity)]))
        [:ns :fn :slot :fn-slot :binding :binding-list-item]))


(deftest preview-is-read-only-and-apply-is-exact-create-only
  (let [storage (:storage ga/*bootstrap*)
        before (graph-identities storage)
        source (sp/read-entity storage :fn config/configuration-id)
        reserved (preview)]
    (is (= 200 (:status reserved)) (pr-str reserved))
    (is (true? (get-in reserved [:body :ok])))
    (is (= before (graph-identities storage)))
    (is (= 5 (count (get-in reserved [:body :manifest :namespaces]))))
    (let [created (apply-preview reserved)
          manifest (get-in created [:body :manifest])]
      (is (= 200 (:status created)) (pr-str created))
      (is (true? (get-in created [:body :committed])))
      (is (= (get-in reserved [:body :manifest]) manifest))
      (doseq [row (:functions manifest)]
        (let [actual (sp/read-entity storage :fn (parse-uuid (:id row)))]
          (is (= (:name row) (:name actual)))
          (is (= (parse-uuid (:namespace-id row)) (:namespace-id actual)))))
      (is (= source (sp/read-entity storage :fn config/configuration-id)))
      (let [after (graph-identities storage)
            duplicate (apply-preview reserved)]
        (is (= 409 (:status duplicate)))
        (is (false? (get-in duplicate [:body :committed])))
        (is (= after (graph-identities storage)))))))


(deftest exact-manifest-or-session-change-refuses-before-writing
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        command (get-in reserved [:body :request])
        before (graph-identities storage)]
    (doseq [changed [(assoc command :expected-state "changed")
                     (assoc command :root-id (str (random-uuid)))
                     (assoc command :owner "other-account")
                     (assoc command :branch-id (str (random-uuid)))
                     (assoc command :fn-defs [])]]
      (let [result (submit :_ui-components-create-apply-handler changed)]
        (is (false? (get-in result [:body :ok])))
        (is (false? (get-in result [:body :committed])))
        (is (= before (graph-identities storage)))))))


(deftest mid-bundle-sql-failure-rolls-back-namespaces-and-every-owned-row
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        before (graph-identities storage)
        writes (atom 0)
        original @#'pg-crud/create-entity]
    (binding [pg-crud/*create-entity-override*
              (fn [datasource entity data fields]
                (when (and (= entity :binding) (= 3 (swap! writes inc)))
                  (throw (ex-info "Injected SQL failure after namespace and function writes" {})))
                (binding [pg-crud/*create-entity-override* nil]
                  (original datasource entity data fields)))]
      (let [result (apply-preview reserved)]
        (is (= 500 (:status result)) "A rolled-back internal SQL failure is not an authorization refusal")
        (is (false? (get-in result [:body :committed])))
        (is (= before (graph-identities storage)))))
    (is (pos? @writes))
    (is (:ok (:body (apply-preview reserved))) "same reservation can be retried after rollback")))


(deftest quota-refusal-returns-429-and-rolls-back-the-bundle
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        before (graph-identities storage)
        original @#'pg-crud/create-entity]
    (binding [pg-crud/*create-entity-override*
              (fn [datasource entity data fields]
                (when (= entity :fn)
                  (throw (ex-info "Private quota diagnostic"
                                  {:type :quota/entity-limit :org "private-org" :entity :fn})))
                (binding [pg-crud/*create-entity-override* nil]
                  (original datasource entity data fields)))]
      (let [result (apply-preview reserved)]
        (is (= 429 (:status result)))
        (is (= {:ok false :committed false
                :reason "Your plan's graph limit has been reached. Free capacity or upgrade your plan before creating UI graphs."}
               (:body result)))
        (is (= before (graph-identities storage)))))))


(deftest two-independent-copies-export-only-the-selected-component
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        manifests (mapv (fn [_] (get-in (apply-preview (preview)) [:body :manifest])) (range 2))
        roots (mapv #(get-in % [:roots :configuration-id]) manifests)]
    (is (apply not= roots))
    (doseq [manifest manifests]
      (let [id (parse-uuid (get-in manifest [:roots :configuration-id]))
            preference {:fn-id (str id) :branch-id (str (vs/current-branch-id storage)) :org "public"}
            prior (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "components"}))]
        (if prior
          (sp/update-entity storage :ui-pref (:id prior) {:value preference})
          (sp/create-entity storage :ui-pref {:owner-id "anonymous" :key "components" :value preference
                                              :org-id "public" :updated-at (java.time.Instant/now)}))
        ;; Publication updated this ctx's graph/type slices. No concurrent
        ;; writer exists in this serial fixture; certify that exact basis.
        (epoch/seed-watermark! storage)
        (doseq [component ["account-menu" "fn-picker" "recents"]]
          (let [queries (atom [])
                original-query sp/query-bounded-entities
                {:keys [result] :as diagnosed} (with-redefs [sp/query-bounded-entities
                                                             (fn [s entity where maximum]
                                                               (swap! queries conj [entity where])
                                                               (original-query s entity where maximum))]
                                                 (export-diagnosed ctx component))
                body-ids (into #{} (map parse-uuid)
                               (case component
                                 "account-menu" [(get-in manifest [:roots :picker-id])
                                                 (get-in manifest [:roots :recents-id])
                                                 (get-in manifest [:roots :recents-initial-id])
                                                 (get-in manifest [:roots :recents-update-id])]
                                 "fn-picker" [(get-in manifest [:roots :menu-id])
                                              (get-in manifest [:roots :menu-update-id])
                                              (get-in manifest [:roots :recents-id])
                                              (get-in manifest [:roots :recents-initial-id])
                                              (get-in manifest [:roots :recents-update-id])]
                                 "recents" [(get-in manifest [:roots :menu-id])
                                            (get-in manifest [:roots :menu-update-id])
                                            (get-in manifest [:roots :picker-id])]))]
            (is (not-any? (fn [[entity where]]
                            (and (contains? #{:binding :fn-slot} entity)
                                 (seq (set/intersection body-ids (set (:fn-id where)))))) @queries)
                "nonselected component identities never cause body reads")
            (is (:ok result) (pr-str diagnosed))
            (is (= (str id) (:selection-id result)))
            (is (= (str id) (get-in result [:roots :configuration-id])))
            (is (empty? (set/intersection
                          (into #{} (map :id) (get-in result [:plan :functions]))
                          (into #{} (mapcat #(map :id (:functions %)))
                                (remove #{manifest} manifests)))))))))))


(deftest legacy-four-slot-preferences-remain-functional-and-missing-components-fallback
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        manifest (get-in (apply-preview (preview)) [:body :manifest])
        root (:path (first (:namespaces manifest)))
        id (records/fn-id root :legacy-ui)
        definition {:name :legacy-ui :namespace root :parent :app.ui-components/ui-components
                    :args {:menu-initial (keyword (str root ".menu") "account-menu-initial")
                           :menu-update (keyword (str root ".menu") "account-menu-update")
                           :menu-view (keyword (str root ".menu") "account-menu-view")
                           :picker-view (keyword (str root ".picker") "picker-view")}}
        preference {:fn-id (str id) :branch-id (str (vs/current-branch-id storage)) :org "public"}]
    ((impls/impl-of :sync-fn-defs-branch!)
     {:branch-id (vs/current-branch-id storage) :fn-defs [definition]} ctx)
    (if-let [prior (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "components"}))]
      (sp/update-entity storage :ui-pref (:id prior) {:value preference})
      (sp/create-entity storage :ui-pref {:owner-id "anonymous" :key "components" :value preference
                                          :org-id "public" :updated-at (java.time.Instant/now)}))
    (epoch/seed-watermark! storage)
    (doseq [component ["account-menu" "fn-picker"]]
      (is (:ok (components/export-current ctx {:component component}))))
    (is (= "component-missing" (:code (components/export-current ctx {:component "recents"}))))
    (is (= "builtin" (:fallback (components/export-current ctx {:component "recents"}))))))
