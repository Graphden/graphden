(ns ^:integration ^:serial graphden.editor.root-migration-integration-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-migration :as migration]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as ga]
    [graphden.versioning.storage.core :as versioned]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(deftest preview-creates-nothing-and-apply-selects-a-personal-root-once
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "root-migration-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (let [preview (migration/preview! ctx {})
            request (:request preview)
            receipt (:receipt preview)
            created-ids (mapv parse-uuid (:functions receipt))]
        (is (empty? (sp/read-entities storage :fn created-ids)))
        (is (= [] (:preserved-callbacks receipt)))
        (is (empty? (sp/query-entities storage :fn {:namespace-id (:id home)})))
        (let [applied (migration/apply! ctx request)]
          (is (= (:id receipt) (str (:id applied))))
          (is (true? (:committed applied)))
          (is (= (set created-ids)
                 (set (keys (sp/read-entities storage :fn created-ids)))))
          (epoch/seed-watermark! storage)
          (let [policy (snapshot/capture-context-policy ctx)
                authorize! (tenancy/browser-source-policy storage)]
            (is (= (:id applied) (:id (resolution/root-choice storage authorize! policy)))))
          (is (= :already-migrated
                 (try (migration/apply! ctx request) nil
                      (catch clojure.lang.ExceptionInfo error
                        (:reason (ex-data error)))))))))))


(deftest migration-retains-an-authored-callback-and-its-ordinary-root
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "authored-root-migration-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (let [policy (snapshot/capture-context-policy ctx)
            current (source/with-snapshot storage #(resolution/capture %1 %2 policy))
            authored (activation/personalize!
                       ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                            :expected-configuration-revision (:revision current)})
            callback-id (:entry-id authored)
            callback (sp/read-entity storage :fn callback-id)
            ordinary-root (sp/read-entity storage :fn (:id authored))]
        (epoch/seed-watermark! storage)
        (let [preview (migration/preview! ctx {})
              receipt (:receipt preview)]
          (is (= (str (:id authored)) (:selection-id receipt)))
          (is (= [(str callback-id)] (:preserved-callbacks receipt)))
          (let [original-policy tenancy/browser-source-policy
                denied? (atom false)]
            (with-redefs [tenancy/browser-source-policy
                          (fn [storage]
                            (let [authorize! (original-policy storage)]
                              (fn [row & options]
                                (if (= callback-id (:id row))
                                  (do (reset! denied? true)
                                      (throw (ex-info "Callback grant revoked" {:type :authz/forbidden})))
                                  (apply authorize! row options)))))]
              (is (= :authz/forbidden
                     (try (migration/apply! ctx (:request preview)) nil
                          (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))))
            (is @denied?)
            (is (empty? (sp/read-entities storage :fn (mapv parse-uuid (:functions receipt))))))
          (let [applied (migration/apply! ctx (:request preview))]
            (is (not= (:id authored) (:id applied)))
            (is (= callback (sp/read-entity storage :fn callback-id)))
            (is (= ordinary-root (sp/read-entity storage :fn (:id authored))))
            (is (= (:id home) (:namespace-id (sp/read-entity storage :fn (:id applied)))))))))))


(deftest changed-ordinary-selection-invalidates-the-root-proposal
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "stale-root-migration-" (random-uuid))
        _home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (let [preview (migration/preview! ctx {})
            current (source/with-snapshot
                      storage #(resolution/capture %1 %2 (snapshot/capture-context-policy ctx)))
            _ (activation/personalize!
                ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                     :expected-configuration-revision (:revision current)})]
        (epoch/seed-watermark! storage)
        (is (= :stale-configuration
               (try (migration/apply! ctx (:request preview)) nil
                    (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
        (is (empty? (sp/read-entities storage :fn
                                      (mapv parse-uuid (get-in preview [:receipt :functions])))))))))


(deftest legacy-preference-is-preserved-after-explicit-root-migration
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "legacy-root-migration-" (random-uuid))
        _home (sp/create-entity storage :ns {:name home-name})
        preference (sp/create-entity
                     storage :ui-pref
                     {:owner-id (tenancy/current-user-id) :key "components"
                      :org-id (tenancy/current-org)
                      :value {:fn-id (str resolution/defaults-id) :org (tenancy/current-org)
                              :branch-id (str (versioned/current-branch-id storage))}})]
    (try
      (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
        (tenancy/install-personal-namespace-fn!
          (constantly {:namespace-path home-name :source :fixture :isolated? false}))
        (epoch/seed-watermark! storage)
        (let [preview (migration/preview! ctx {})
              applied (migration/apply! ctx (:request preview))]
          (is (= (str resolution/defaults-id) (get-in preview [:receipt :selection-id])))
          (is (true? (:committed applied)))
          (is (= preference (sp/read-entity storage :ui-pref (:id preference))))))
      (finally (sp/delete-entity storage :ui-pref (:id preference))))))


(deftest a-root-proposal-cannot-be-applied-by-another-principal
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-a (sp/create-entity storage :ns {:name (str "root-user-a-" (random-uuid))})
        home-b (sp/create-entity storage :ns {:name (str "root-user-b-" (random-uuid))})
        user-a (str "root-a-" (random-uuid))
        user-b (str "root-b-" (random-uuid))]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (fn []
          {:namespace-path (if (= user-a (tenancy/current-user-id))
                             (:name home-a) (:name home-b))
           :source :fixture :isolated? false}))
      (binding [tenancy/*current-principal* {:authenticated? true :user-id user-a}]
        (epoch/seed-watermark! storage)
        (let [preview (migration/preview! ctx {})]
          (binding [tenancy/*current-principal* {:authenticated? true :user-id user-b}]
            (epoch/seed-watermark! storage)
            (is (= :stale-configuration
                   (try (migration/apply! ctx (:request preview)) nil
                        (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
            (is (empty? (sp/query-entities storage :fn {:namespace-id (:id home-b)}))))
          (epoch/seed-watermark! storage)
          (is (true? (:committed (migration/apply! ctx (:request preview))))))))))
