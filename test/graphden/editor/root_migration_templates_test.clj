(ns graphden.editor.root-migration-templates-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.editor.root-legacy :as legacy]
    [graphden.editor.root-migration-templates :as templates]))


(defn- refusal-reason
  [configuration]
  (try (templates/definitions "migration-invalid" configuration)
       nil
       (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))


(deftest migration-declarations-reference-only-changed-role-identities
  (let [root "migration-probe"
        menu-view (random-uuid)
        catalog-view (random-uuid)
        selected {:account-menu {:initial (legacy/default-role-id :account-menu :initial)
                                 :view menu-view}
                  :tutorial-catalog {:view catalog-view}}
        {:keys [definitions references overrides]} (templates/definitions root selected)
        by-name (into {} (map (juxt :name identity)) definitions)]
    (is (= {:account-menu {:view menu-view}
            :tutorial-catalog {:view catalog-view}} overrides))
    (is (= {(keyword root "_migration-account-menu-view-source") menu-view
            (keyword root "_migration-tutorial-catalog-view-source") catalog-view}
           references))
    (is (= #{:_migration-account-menu-view-wrapper
             :_migration-tutorial-catalog-view-wrapper}
           (set (map :name (filter #(str/ends-with? (name (:name %)) "-wrapper") definitions)))))
    (is (= (keyword root "_migration-account-menu-view-source")
           (get-in by-name [:_migration-account-menu-view-base :parent])))
    (is (= (keyword root "_migration-account-menu-view-base")
           (get-in by-name [:_migration-account-menu-view-wrapper :args :map])))
    (is (= :app.ui-account-menu/_root-common-tree
           (get-in by-name [:_migration-account-menu-view-wrapper :args :value])))
    (is (= [:input]
           (get-in by-name [:_migration-account-menu-view-wrapper :lambda-params])))
    (is (= :call (get-in by-name [:editor-root :parent])))
    (is (= (count definitions) (count (distinct (map :name definitions)))))))


(deftest missing-or-unchanged-legacy-roles-inherit-the-shipped-root
  (let [empty-plan (templates/definitions "migration-empty" {})
        default-plan (templates/definitions
                       "migration-default"
                       {:fn-picker {:view (legacy/default-role-id :fn-picker :view)}})]
    (is (= {} (:overrides empty-plan) (:overrides default-plan)))
    (is (= [] (vec (keys (:references empty-plan)))))
    (is (= [:editor-root] (mapv :name (:definitions empty-plan))))
    (is (= :app.ui-config/component-editor-root
           (get-in empty-plan [:definitions 0 :args :func :ref])))))


(deftest known-components-outside-the-root-bridge-stay-with-the-ordinary-configuration
  (let [selected {:inspector {:view (random-uuid)}
                  :tutorial-catalog {:view (legacy/default-role-id :tutorial-catalog :view)}}]
    (is (= {} (:overrides (templates/definitions "migration-other" selected))))))


(deftest unsupported-legacy-roles-refuse-before-building-a-root
  (is (= :invalid-configuration
         (refusal-reason {:fn-picker {:view "not-a-uuid"}})))
  (is (= :invalid-configuration
         (refusal-reason {:tutorial-catalog {:update (random-uuid)}})))
  (is (= :invalid-configuration
         (refusal-reason {:unknown-component {:view (random-uuid)}})))
  (is (= :invalid-configuration
         (refusal-reason {:account-menu "not-a-role-map"}))))
