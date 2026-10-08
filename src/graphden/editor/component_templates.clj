(ns graphden.editor.component-templates
  "Copy only the installed personal UI templates. No client EDN, database
   user code, implementation text or arbitrary asset path enters this bundle."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]))


(def ^:private abi-names
  (into {}
        (for [[path names] [["core.logic" [:const :if :equal?]]
                            ["core.collections" [:list :get :assoc :zipmap :count]]
                            ["core.arithmetic" [:add :mod]]
                            ["core.hof" [:map]]
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
                 "core/collections" ["core.collections" #{:list :get :assoc :zipmap :count}]
                 "core/arithmetic" ["core.arithmetic" #{:add :mod}]
                 "core/hof" ["core.hof" #{:map}]
                 "web/html" ["web.html" #{:hiccup :hiccup-node}]
                 "core/refinements" ["core.refinements" (set (keys public-type-names))]
                 "app/ui-components" ["app.ui-components" #{:ui-components :_ui-components-identities}]}]
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
        configuration {:name :ui :namespace root :parent :app.ui-components/ui-components
                       :description "Your editor UI graphs. Entry identities select the menu and picker; callbacks and navigation remain host-owned."
                       :args {:menu-initial (keyword menu "account-menu-initial")
                              :menu-update (keyword menu "account-menu-update")
                              :menu-view (keyword menu "account-menu-view")
                              :picker-view (keyword picker "picker-view")}}]
    (when-not color-const
      (throw (ex-info "Installed UI templates are unavailable" {:type :browser-plan/unsupported})))
    (into [(assoc color-const :namespace theme) configuration]
          (concat theme-definitions menu-definitions (copy-module "app/ui-fn-picker" picker)))))


(defn descriptor
  "Exact identities for catalog navigation and pre-apply cleanup registration."
  [root]
  {:configuration-id (str (ids/fn-id root :ui))
   :theme-id (str (ids/fn-id (str root ".theme") :theme))
   :menu-id (str (ids/fn-id (str root ".menu") :account-menu-view))
   :menu-update-id (str (ids/fn-id (str root ".menu") :account-menu-update))
   :picker-id (str (ids/fn-id (str root ".picker") :picker-view))})
