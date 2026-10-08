(ns ^:integration ^:serial graphden.editor.component-creation-integration-test
  "The normal graph handlers create a fixed bundle in one JDBC transaction."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
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
    (is (= 4 (count (get-in reserved [:body :manifest :namespaces]))))
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
        (doseq [component ["account-menu" "fn-picker"]]
          (let [result (components/export-current ctx {:component component})]
            (is (:ok result) (pr-str result))
            (is (= (str id) (:selection-id result)))
            (is (= (str id) (get-in result [:roots :configuration-id])))
            (is (empty? (set/intersection
                          (into #{} (map :id) (get-in result [:plan :functions]))
                          (into #{} (mapcat #(map :id (:functions %)))
                                (remove #{manifest} manifests)))))))))))
