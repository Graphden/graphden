(ns graphden.editor.component-templates
  "Copy only the installed personal UI templates. No client EDN, database
   user code, implementation text or arbitrary asset path enters this bundle."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.editor.component-config :as config]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]))


(def ^:private abi-names
  (into {}
        (for [[path names] [["core.logic" [:const :if :equal?]]
                            ["core.collections" [:list :get :assoc :zipmap :count :take :concat]]
                            ["core.arithmetic" [:add :mod]]
                            ["core.hof" [:map :filter]]
                            ["core.strings" [:str :str-starts-with?]]
                            ["web.html" [:hiccup]]]
              name names]
          [name (keyword path (clojure.core/name name))])))


(def ^:private public-type-names
  (into {} (map #(vector % (keyword "core.refinements" (name %))))
        [:color :non-negative-int :text-map :keyword-map :text-keyed-map :keyword-or-text]))


(defn- installed-definitions
  [module]
  (:fns (-> (str "packages/" module "/fns.edn") io/resource slurp edn/read-string)))


(defn interface-definitions
  "Fixed parse-time interfaces, not a dump of tenant definitions. The import
   resolves slots against these shipped declarations, then checks every actual
   installed dependency under source authorization before writing anything."
  []
  (let [modules {"core/logic" ["core.logic" #{:const :if :equal?}]
                 "core/collections" ["core.collections" #{:list :get :assoc :zipmap :count :take :concat}]
                 "core/arithmetic" ["core.arithmetic" #{:add :mod}]
                 "core/hof" ["core.hof" #{:map :filter}]
                 "core/strings" ["core.strings" #{:str :str-starts-with?}]
                 "web/html" ["web.html" #{:hiccup :hiccup-node}]
                 "core/refinements" ["core.refinements" (set (keys public-type-names))]
                 "app/ui-account-menu" ["app.ui-account-menu" #{:account-menu-view}]
                 "app/ui-fn-picker" ["app.ui-fn-picker" #{:picker-view}]
                 "app/ui-recents" ["app.ui-recents" #{:recents-view}]
                 "app/ui-inspector" ["app.ui-inspector" #{:inspector-view}]
                 "app/ui-components" ["app.ui-components" #{:ui-components :_ui-components-identities :ui-component-entry :ui-component-entries
                                                            :ui-component-descriptor :ui-component-manifest}]}]
    (into []
          (mapcat (fn [[module [path names]]]
                    (map #(assoc % :namespace path)
                         (filter #(contains? names (:name %)) (installed-definitions module)))))
          modules)))


(defn- copy-module
  [module destination]
  (let [definitions (installed-definitions module)
        local-names (set (map :name definitions))
        fixed-names (merge public-type-names abi-names)
        qualify (fn [reference]
                  (if (contains? local-names reference) reference
                      (get fixed-names reference reference)))]
    (mapv #(assoc (rebase/rewrite-references % qualify) :namespace destination) definitions)))


(defn component-definitions
  "Ordinary descriptor graphs with terminal identity wrappers."
  [root prefix shipped roles]
  (let [local (fn [suffix] (keyword (str "_" prefix "-" suffix)))
        reference (fn [suffix] (keyword root (name (local suffix))))
        definition (fn [suffix parent args]
                     (cond-> {:name (local suffix) :namespace root :parent parent :args args}
                       (= parent :core.logic/const) (assoc :return-type :uuid)
                       (= suffix "entries") (assoc :return-type :app.ui-components/ui-component-entries)
                       (= suffix "descriptor") (assoc :return-type :app.ui-components/ui-component-descriptor)
                       (str/ends-with? suffix "-entry") (assoc :return-type :app.ui-components/ui-component-entry)))]
    (into [(definition "component-id" :core.logic/const
             {:value {:ref shipped :type :fn-ref}})
           (definition "entries" :core.collections/list
             {:items (mapv #(reference (str (name (first %)) "-entry")) roles)})
           (definition "descriptor" :core.collections/zipmap
             {:keys [{:value :component-id} {:value :entries}]
              :vals [(reference "component-id") (reference "entries")]})]
          (mapcat (fn [[role target]]
                    [(definition (str (name role) "-id") :core.logic/const
                       {:value {:ref target :type :fn-ref}})
                     (definition (str (name role) "-entry") :core.collections/zipmap
                       {:keys [{:value :role} {:value :fn-id}]
                        :vals [{:value role} (reference (str (name role) "-id"))]})]) roles))))


(defn definitions
  "Fixed ordinary named graphs, grouped under a fresh writable personal root.
   Color wrappers inherit the canonical registered color type, preserving the
   existing typed form. Shared menu colors reference the copied theme leaves."
  [root]
  (let [theme (str root ".theme")
        menu (str root ".menu")
        picker (str root ".picker")
        color-const (->> (installed-definitions "core/refinements")
                         (filter #(= :color-const (:name %))) first)
        color-parent (keyword theme "color-const")
        theme-definitions (copy-module "app/ui-theme-template" theme)
        theme-definitions (mapv #(rebase/rewrite-references
                                   % (fn [reference]
                                       (if (= :core.refinements/color-const reference)
                                         color-parent reference))) theme-definitions)
        menu-definitions (mapv (fn [definition]
                                 (let [definition (rebase/rewrite-references
                                                    definition #(if (= :core.refinements/color-const %)
                                                                  color-parent %))]
                                   (case (:name definition)
                                     :theme-accent (assoc definition :args {:value (keyword theme "theme-accent-color")})
                                     :theme-canvas-background (assoc definition :args {:value (keyword theme "theme-canvas-color")})
                                     :account-menu-hover (assoc definition :args {:value "#f8fafc"})
                                     definition)))
                               (copy-module "app/ui-account-menu" menu))
        recents (str root ".recents")
        inspector (str root ".inspector")
        descriptors (concat (component-definitions root "inspector" :app.ui-inspector/inspector-view
                                                   [[:initial (keyword inspector "inspector-initial")]
                                                    [:update (keyword inspector "inspector-update")]
                                                    [:view (keyword inspector "inspector-view")]])
                            (component-definitions root "menu" :app.ui-account-menu/account-menu-view
                                                   [[:initial (keyword menu "account-menu-initial")]
                                                    [:update (keyword menu "account-menu-update")]
                                                    [:view (keyword menu "account-menu-view")]])
                            (component-definitions root "picker" :app.ui-fn-picker/picker-view
                                                   [[:view (keyword picker "picker-view")]])
                            (component-definitions root "recents" :app.ui-recents/recents-view
                                                   [[:initial (keyword recents "recents-initial")]
                                                    [:update (keyword recents "recents-update")]
                                                    [:view (keyword recents "recents-view")]]))
        configuration {:name :ui :namespace root :parent :core.collections/list
                       :return-type :app.ui-components/ui-component-manifest
                       :description "Your ordinary component manifest. Each descriptor selects entry identities; browser effects remain host-owned."
                       :args {:items [(keyword root "_menu-descriptor")
                                      (keyword root "_picker-descriptor")
                                      (keyword root "_recents-descriptor")
                                      (keyword root "_inspector-descriptor")]}}]
    (when-not color-const
      (throw (ex-info "Installed UI templates are unavailable" {:type :browser-plan/unsupported})))
    (into [(assoc color-const :namespace theme) configuration]
          (concat descriptors theme-definitions menu-definitions (copy-module "app/ui-fn-picker" picker)
                  (copy-module "app/ui-recents" recents) (copy-module "app/ui-inspector" inspector)))))


(def namespace-suffixes
  "Owned child namespaces reserved for the installed personal template bundle."
  ["theme" "menu" "picker" "recents" "inspector"])


(def groups
  "Fixed creation choices and their personal namespace/descriptor prefixes."
  {:account-menu "menu" :fn-picker "picker" :recents "recents" :inspector "inspector"})


(defn selected-definitions
  "Copy only the chosen shipped component and its owned dependencies. Existing
   authored manifests are referenced by a new concat root, never rewritten.
   Legacy entries are adapted explicitly without copying their target bodies."
  [root group retained legacy]
  (let [prefix (get groups group)
        copied (definitions root)
        configuration (first (filter #(= :ui (:name %)) copied))
        new-list (assoc configuration :name :_new-components
                        :args {:items [(keyword root (str "_" prefix "-descriptor"))]})
        legacy-defs (mapcat (fn [[component roles]]
                              (component-definitions root (str "retained-" (get groups component))
                                                     (:reference (get config/component-contracts component))
                                                     roles)) legacy)
        legacy-list {:name :_retained-components :namespace root :parent :core.collections/list
                     :return-type :app.ui-components/ui-component-manifest
                     :args {:items (mapv #(keyword root (str "_retained-" (get groups %) "-descriptor"))
                                         (keys legacy))}}
        existing (or retained (when (seq legacy) (keyword root "_retained-components")))
        configuration (if existing
                        (assoc configuration :parent :core.collections/concat
                               :args {:colls [existing (keyword root "_new-components")]})
                        (assoc configuration :args (:args new-list)))
        all (into (vec (remove #(= :ui (:name %)) copied))
                  (concat [configuration] (when existing [new-list]) legacy-defs
                          (when (seq legacy) [legacy-list])))
        indexed (into {} (map #(vector (keyword (:namespace %) (name (:name %))) %)) all)]
    (loop [pending #{(keyword root "ui")} seen #{}]
      (if (empty? pending)
        (filterv #(contains? seen (keyword (:namespace %) (name (:name %)))) all)
        (let [reference (first pending)
              definition (get indexed reference)
              references (atom #{})]
          (rebase/rewrite-references definition
                                     (fn [ref]
                                       (let [qualified (if (namespace ref) ref (keyword (:namespace definition) (name ref)))]
                                         (when (contains? indexed qualified) (swap! references conj qualified)))
                                       ref))
          (recur (into (disj pending reference) (remove (conj seen reference) @references))
                 (conj seen reference)))))))


(defn descriptor
  "Exact identities for catalog navigation and pre-apply cleanup registration."
  [root]
  {:configuration-id (str (ids/fn-id root :ui))
   :theme-id (str (ids/fn-id (str root ".theme") :theme))
   :menu-id (str (ids/fn-id (str root ".menu") :account-menu-view))
   :menu-update-id (str (ids/fn-id (str root ".menu") :account-menu-update))
   :picker-id (str (ids/fn-id (str root ".picker") :picker-view))
   :inspector-id (str (ids/fn-id (str root ".inspector") :inspector-view))
   :inspector-initial-id (str (ids/fn-id (str root ".inspector") :inspector-initial))
   :inspector-update-id (str (ids/fn-id (str root ".inspector") :inspector-update))
   :recents-id (str (ids/fn-id (str root ".recents") :recents-view))
   :recents-initial-id (str (ids/fn-id (str root ".recents") :recents-initial))
   :recents-update-id (str (ids/fn-id (str root ".recents") :recents-update))})
