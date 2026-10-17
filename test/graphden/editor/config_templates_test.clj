(ns graphden.editor.config-templates-test
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-templates :as components]
    [graphden.editor.config-templates :as templates]
    [graphden.executor.composition.core :as composition]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- graph
  [definitions]
  (let [interfaces (templates/interfaces)
        index (fn [value-fn]
                (reduce (fn [result {:keys [name] path :namespace :as definition}]
                          (assoc result name (value-fn definition)
                                 (keyword path (clojure.core/name name)) (value-fn definition)))
                        {} interfaces))
        names (merge (ids/primitive-fn-ids) (index #(ids/fn-id (:namespace %) (:name %))))
        shared (filter #(= "app.ui-config" (:namespace %)) interfaces)
        declarations (filter #(or (contains? #{"core.logic" "core.collections" "core.hof" "web.graph-metadata"}
                                             (:namespace %))
                                  (and (= "app.ui-components" (:namespace %))
                                       (contains? #{:ui-components :_ui-components-identities} (:name %)))) interfaces)
        records (concat (ids/boot-primitive-records)
                        (parse/parse-module declarations names (index identity))
                        (composition/fn-defs->records (into (vec shared) definitions) names (index identity)))]
    (bundle/graph records)))


(deftest legacy-list-composition-retains-whole-root-and-shared-defaults
  (let [legacy "legacy.fixture"
        root "fresh.configuration"
        roles {:initial :app.ui-account-menu/account-menu-initial
               :update :app.ui-account-menu/account-menu-update :view :app.ui-fn-picker/picker-view}
        definitions (into (components/component-definitions legacy "menu" :app.ui-account-menu/account-menu-view roles)
                          (concat [{:name :manifest :namespace legacy :parent :core.collections/list
                                    :args {:items [(keyword legacy "_menu-descriptor")]}}]
                                  (templates/definitions root (keyword legacy "manifest") false)))
        source (graph definitions)
        editor-id (ids/fn-id root :editor)
        configuration (config/configuration source editor-id)]
    (is (= (set (keys config/component-contracts)) (set (keys configuration))))
    (is (= :list (:format (config/configuration-info source (ids/fn-id legacy :manifest)))))
    (is (= :map (:format (config/configuration-info source editor-id))))
    (is (= (ids/fn-id "app.ui-fn-picker" :picker-view) (get-in configuration [:account-menu :view])))
    (is (= (ids/fn-id "app.ui-recents" :recents-view) (get-in configuration [:recents :view])))
    (is (= (ids/fn-id "app.ui-tutorial-catalog" :catalog-view)
           (get-in configuration [:tutorial-catalog :view])))
    (let [changed (mapv #(if (= :_menu-view-id (:name %))
                           (assoc-in % [:args :value :ref] :app.ui-inspector/inspector-view) %) definitions)]
      (is (= (ids/fn-id "app.ui-inspector" :inspector-view)
             (get-in (config/configuration (graph changed) editor-id) [:account-menu :view]))
          "Later authored legacy edits are observed through the original root reference"))))


(deftest historical-record-composition-retains-field-identities
  (let [legacy "legacy.record"
        root "fresh.record-configuration"
        definitions (into [{:name :legacy :namespace legacy :parent :app.ui-components/ui-components
                            :args {:menu-initial {:ref :app.ui-account-menu/account-menu-initial}
                                   :menu-update {:ref :app.ui-account-menu/account-menu-update}
                                   :menu-view {:ref :app.ui-fn-picker/picker-view}
                                   :picker-view {:ref :app.ui-inspector/inspector-view}}}]
                          (templates/definitions root (keyword legacy "legacy") true))
        editor-id (ids/fn-id root :editor)
        configuration (config/configuration (graph definitions) editor-id)]
    (is (= (set (keys config/component-contracts)) (set (keys configuration))))
    (is (= :record (:format (config/configuration-info (graph definitions) (ids/fn-id legacy :legacy)))))
    (is (= (ids/fn-id "app.ui-fn-picker" :picker-view) (get-in configuration [:account-menu :view])))
    (is (= (ids/fn-id "app.ui-inspector" :inspector-view) (get-in configuration [:fn-picker :view])))
    (let [changed (assoc-in definitions [0 :args :menu-view :ref] :app.ui-account-menu/account-menu-view)]
      (is (= (ids/fn-id "app.ui-account-menu" :account-menu-view)
             (get-in (config/configuration (graph changed) editor-id) [:account-menu :view]))))))


(deftest prepared-cohorts-retain-actual-identities-without-home-name-collisions
  (let [retained (random-uuid)
        first-copy (templates/parse (str "config." (random-uuid)) retained false)
        second-copy (templates/parse (str "config." (random-uuid)) retained false)
        functions #(filter (fn [row] (= :fn (:kind row))) (:records %))
        private-names #(into #{} (comp (map :name) (remove #{"editor"})) (functions %))]
    (is (not= (:root-id first-copy) (:root-id second-copy)))
    (is (empty? (set/intersection (private-names first-copy) (private-names second-copy))))
    (is (some #(= retained (:ref-fn-id %)) (:records first-copy)))
    (is (not-any? #(= retained (:id %)) (functions first-copy)))
    (is (thrown? clojure.lang.ExceptionInfo (templates/parse "config.invalid" (str retained) false)))))


(deftest ordinary-overlay-retains-prior-composition-and-replaces-only-selected-group
  (let [legacy "overlay.legacy"
        prior "overlay.prior"
        override "overlay.override"
        root "overlay.next"
        menu-roles {:initial :app.ui-account-menu/account-menu-initial
                    :update :app.ui-account-menu/account-menu-update :view :app.ui-fn-picker/picker-view}
        prior-definitions (vec (concat
                                 (components/component-definitions legacy "menu" :app.ui-account-menu/account-menu-view menu-roles)
                                 [{:name :manifest :namespace legacy :parent :core.collections/list
                                   :args {:items [(keyword legacy "_menu-descriptor")]}}]
                                 (templates/definitions prior (keyword legacy "manifest") false)))
        override-definitions (conj (components/component-definitions override "picker" :app.ui-fn-picker/picker-view
                                                                     {:view :app.ui-inspector/inspector-view})
                                   {:name :manifest :namespace override :parent :core.collections/list
                                    :args {:items [(keyword override "_picker-descriptor")]}})
        parsed (templates/parse root (ids/fn-id override :manifest) false (ids/fn-id prior :editor))
        source (merge-with (fn [left right]
                             (vec (vals (into {} (map (juxt :id identity)) (concat left right)))))
                           (graph prior-definitions) (graph override-definitions) (bundle/graph (:records parsed)))
        configuration (config/configuration source (:root-id parsed))]
    (is (= (ids/fn-id "app.ui-fn-picker" :picker-view) (get-in configuration [:account-menu :view])))
    (is (= (ids/fn-id "app.ui-inspector" :inspector-view) (get-in configuration [:fn-picker :view])))
    (is (= (ids/fn-id "app.ui-recents" :recents-view) (get-in configuration [:recents :view])))
    (is (= (set (keys config/component-contracts)) (set (keys configuration))))
    (is (some #(= (ids/fn-id prior :editor) (:ref-fn-id %)) (:records parsed)))
    (is (not-any? #(= (ids/fn-id prior :editor) (:id %)) (:records parsed)))))


(deftest map-overlay-needs-only-one-new-composition-root
  (let [prior (random-uuid)
        override (random-uuid)
        parsed (templates/parse "overlay.map" override :map prior)]
    (is (= [(:root-id parsed)] (mapv :id (filter #(= :fn (:kind %)) (:records parsed)))))
    (is (= #{prior override} (set (vals (:reference-identities parsed)))))
    (is (every? (set (keep :ref-fn-id (:records parsed))) [prior override]))))


(deftest personalizing-one-group-creates-role-children-not-a-body-closure-copy
  (doseq [[component roles expected-count]
          [[:account-menu (zipmap [:initial :update :view] (repeatedly 3 random-uuid)) 12]
           [:fn-picker {:view (random-uuid)} 6]]]
    (let [prior (random-uuid)
          parsed (templates/personalize (str "personal." (random-uuid)) component roles prior)
          functions (filter #(= :fn (:kind %)) (:records parsed))
          children (filter #(some (set (vals roles)) (:parent-ids %)) functions)
          child-ids (set (map :id children))]
      (is (= expected-count (count functions)))
      (is (= (count roles) (count children)))
      (is (= (set (vals roles)) (set (mapcat :parent-ids children))))
      (is (not-any? #(contains? child-ids (:fn-id %)) (filter #(= :binding (:kind %)) (:records parsed))))
      (is (empty? (set/intersection (set (vals roles)) (set (map :id functions)))))
      (is (contains? (set (vals (:reference-identities parsed))) prior)))))


(deftest configuration-role-interfaces-do-not-import-private-bodies
  (let [interfaces (templates/interfaces)]
    (doseq [reference [:app.ui-canvas-inheritance/inheritance-view
                       :app.ui-canvas-inheritance/inheritance-initial
                       :app.ui-canvas-metadata/metadata-view
                       :app.ui-canvas-type-structure/type-structure-view
                       :app.ui-canvas-type-structure/type-structure-initial
                       :app.ui-canvas-bindings/bindings-view]
            :let [matches (filter #(= reference (keyword (:namespace %) (name (:name %)))) interfaces)]]
      (is (seq matches))
      (is (every? #(= #{:name :namespace} (set (keys %))) matches)))))
