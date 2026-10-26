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
        (for [[path names] [["core.logic" [:const :if :equal? :nil? :coalesce]]
                            ["core.collections" [:list :get :assoc :zipmap :count :take :concat :vals]]
                            ["core.arithmetic" [:add :mod :lte]]
                            ["core.hof" [:map :filter :group-by :partition-by :sort-by]]
                            ["core.strings" [:str :str-starts-with? :str-lower :str-trim :str-contains? :str-clip]]
                            ["web.html" [:hiccup]]]
              name names]
          [name (keyword path (clojure.core/name name))])))


(def ^:private public-type-names
  (into {} (map #(vector % (keyword "core.refinements" (name %))))
        [:nullable-text :color :non-negative-int :text-map :keyword-map :text-keyed-map :keyword-or-text]))


(defn- installed-definitions
  [module]
  (:fns (-> (str "packages/" module "/fns.edn") io/resource slurp edn/read-string)))


(defn identity-interface
  "Parse-only identity metadata. It supplies no callable slots or body authority."
  [reference]
  {:namespace (namespace reference) :name (keyword (name reference))})


(defn interface-definitions
  "Fixed parse-time interfaces, not a dump of tenant definitions. The import
   resolves slots against these shipped declarations, then checks every actual
   installed dependency under source authorization before writing anything."
  []
  (let [modules {"core/logic" ["core.logic" #{:const :if :equal? :nil? :coalesce}]
                 "core/collections" ["core.collections" #{:list :get :assoc :assoc-fn :zipmap :count :take :concat :vals}]
                 "core/arithmetic" ["core.arithmetic" #{:add :mod :lte}]
                 "core/hof" ["core.hof" #{:map :filter :group-by :partition-by :sort-by}]
                 "core/strings" ["core.strings" #{:str :str-starts-with? :str-lower :str-trim :str-contains? :str-clip}]
                 "web/html" ["web.html" #{:hiccup :hiccup-node}]
                 "core/refinements" ["core.refinements" (set (keys public-type-names))]
                 "app/ui-canvas" ["app.ui-canvas" #{:canvas-view-fields}]
                 "app/ui-components" ["app.ui-components" #{:ui-components :_ui-components-identities :ui-component-entry :ui-component-entries
                                                            :ui-component-descriptor :ui-component-manifest}]}
        structural (mapcat (fn [[module [path names]]]
                             (map #(assoc % :namespace path)
                                  (filter #(contains? names (:name %)) (installed-definitions module))))
                           modules)
        ;; Installed component discriminators are only const/fn-ref targets in
        ;; this fixed template parser. Do not import their omitted body refs
        ;; into the structural interface index. Actual source authorization
        ;; reads the installed graph separately before any write/export.
        identities (map (comp identity-interface :reference val) config/component-contracts)]
    (into (vec structural) identities)))


(defn definition-closure
  "Select local authored dependencies by reference positions, never literals."
  [definitions roots]
  (rebase/definition-closure definitions roots))


(defn- copy-module
  [module destination]
  (let [definitions (installed-definitions module)
        local-names (set (map :name definitions))
        fixed-names (merge public-type-names abi-names)
        source-path (str/replace module "/" ".")
        qualify (fn [reference]
                  (let [local (if (= source-path (namespace reference))
                                (keyword (name reference)) reference)]
                    (if (contains? local-names local) (keyword destination (name local))
                        (get fixed-names reference reference))))]
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


(defn- personal-menu-definitions
  [theme menu]
  (let [color-parent (keyword theme "color-const")]
    (mapv (fn [definition]
            (let [definition (rebase/rewrite-references
                               definition #(if (= :core.refinements/color-const %)
                                             color-parent %))]
              (case (:name definition)
                :theme-accent (assoc definition :args {:value (keyword theme "theme-accent-color")})
                :theme-canvas-background (assoc definition :args {:value (keyword theme "theme-canvas-color")})
                :account-menu-hover (assoc definition :args {:value "#f8fafc"})
                definition)))
          (copy-module "app/ui-account-menu" menu))))


(defn- component-descriptors
  [root]
  (let [components [["explorer" :app.ui-explorer/explorer-toolbar-view
                     [[:initial "explorer-toolbar-initial"] [:update "explorer-toolbar-update"]
                      [:view "explorer-toolbar-view"]]]
                    ["inspector" :app.ui-inspector/inspector-view
                     [[:initial "inspector-initial"] [:update "inspector-update"] [:view "inspector-view"]]]
                    ["menu" :app.ui-account-menu/account-menu-view
                     [[:initial "account-menu-initial"] [:update "account-menu-update"] [:view "account-menu-view"]]]
                    ["picker" :app.ui-fn-picker/picker-view [[:view "picker-view"]]]
                    ["recents" :app.ui-recents/recents-view
                     [[:initial "recents-initial"] [:update "recents-update"] [:view "recents-view"]]]
                    ["tutorial-catalog" :app.ui-tutorial-catalog/catalog-view [[:view "catalog-view"]]]
                    ["execution-status" :app.ui-execution/status-view [[:view "status-view"]]]
                    ["execution-path" :app.ui-execution/path-view [[:view "path-view"]]]
                    ["feedback" :app.ui-feedback/feedback-view [[:view "feedback-view"]]]
                    ["keymap" :app.ui-keymap/keymap-view [[:view "keymap-view"]]]
                    ["canvas" :app.ui-canvas/canvas-view [[:view "canvas-view"]]]
                    ["canvas-values" :app.ui-canvas-values/canvas-values-view [[:view "canvas-values-view"]]]
                    ["canvas-inheritance" :app.ui-canvas-inheritance/inheritance-view
                     [[:initial "inheritance-initial"] [:view "inheritance-view"]]]
                    ["canvas-metadata" :app.ui-canvas-metadata/metadata-view [[:view "metadata-view"]]]
                    ["canvas-type-structure" :app.ui-canvas-type-structure/type-structure-view
                     [[:initial "type-structure-initial"] [:view "type-structure-view"]]]
                    ["canvas-bindings" :app.ui-canvas-bindings/bindings-view [[:view "bindings-view"]]]]]
    (mapcat (fn [[prefix shipped roles]]
              (component-definitions root prefix shipped
                                     (mapv (fn [[role name]]
                                             [role (keyword (str root "." prefix) name)]) roles)))
            components)))


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
        menu-definitions (personal-menu-definitions theme menu)
        recents (str root ".recents")
        inspector (str root ".inspector")
        explorer (str root ".explorer")
        tutorial-catalog (str root ".tutorial-catalog")
        execution-status (str root ".execution-status")
        execution-path (str root ".execution-path")
        feedback (str root ".feedback")
        keymap (str root ".keymap")
        canvas (str root ".canvas")
        canvas-values (str root ".canvas-values")
        canvas-inheritance (str root ".canvas-inheritance")
        canvas-metadata (str root ".canvas-metadata")
        canvas-type-structure (str root ".canvas-type-structure")
        canvas-bindings (str root ".canvas-bindings")
        descriptors (component-descriptors root)
        configuration {:name :ui :namespace root :parent :core.collections/list
                       :return-type :app.ui-components/ui-component-manifest
                       :description "Your ordinary component manifest. Each descriptor selects entry identities; browser effects remain host-owned."
                       :args {:items [(keyword root "_menu-descriptor")
                                      (keyword root "_picker-descriptor")
                                      (keyword root "_recents-descriptor")
                                      (keyword root "_inspector-descriptor")
                                      (keyword root "_explorer-descriptor")
                                      (keyword root "_tutorial-catalog-descriptor")
                                      (keyword root "_execution-status-descriptor")
                                      (keyword root "_execution-path-descriptor")
                                      (keyword root "_feedback-descriptor")
                                      (keyword root "_keymap-descriptor")
                                      (keyword root "_canvas-descriptor")
                                      (keyword root "_canvas-values-descriptor")
                                      (keyword root "_canvas-inheritance-descriptor")
                                      (keyword root "_canvas-metadata-descriptor")
                                      (keyword root "_canvas-type-structure-descriptor")
                                      (keyword root "_canvas-bindings-descriptor")]}}]
    (when-not color-const
      (throw (ex-info "Installed UI templates are unavailable" {:type :browser-plan/unsupported})))
    ;; A full personal configuration copies every legacy component role plus
    ;; its theme. New shipped root/query constructors are separate entry roots,
    ;; not dependencies of these personal components or their ownership receipt.
    (definition-closure
      (into [(assoc color-const :namespace theme) configuration]
            (concat descriptors theme-definitions menu-definitions (copy-module "app/ui-fn-picker" picker)
                    (copy-module "app/ui-recents" recents) (copy-module "app/ui-inspector" inspector)
                    (copy-module "app/ui-explorer" explorer) (copy-module "app/ui-tutorial-catalog" tutorial-catalog)
                    (copy-module "app/ui-execution" execution-status)
                    (copy-module "app/ui-execution" execution-path)
                    (copy-module "app/ui-feedback" feedback)
                    (copy-module "app/ui-keymap" keymap)
                    (copy-module "app/ui-canvas" canvas) (copy-module "app/ui-canvas-values" canvas-values)
                    (copy-module "app/ui-canvas-inheritance" canvas-inheritance)
                    (copy-module "app/ui-canvas-metadata" canvas-metadata)
                    (copy-module "app/ui-canvas-type-structure" canvas-type-structure)
                    (copy-module "app/ui-canvas-bindings" canvas-bindings)))
      [(keyword root "ui") (keyword theme "theme")])))


(def namespace-suffixes
  "Owned child namespaces reserved for the installed personal template bundle."
  ["theme" "menu" "picker" "recents" "inspector" "explorer" "tutorial-catalog" "execution-status" "execution-path" "feedback" "keymap" "canvas" "canvas-values" "canvas-inheritance" "canvas-metadata" "canvas-type-structure" "canvas-bindings"])


(def groups
  "Fixed creation choices and their personal namespace/descriptor prefixes."
  {:account-menu "menu" :fn-picker "picker" :recents "recents" :inspector "inspector" :explorer "explorer"
   :tutorial-catalog "tutorial-catalog" :execution-status "execution-status" :execution-path "execution-path" :feedback "feedback" :keymap "keymap" :canvas "canvas" :canvas-values "canvas-values" :canvas-inheritance "canvas-inheritance" :canvas-metadata "canvas-metadata" :canvas-type-structure "canvas-type-structure" :canvas-bindings "canvas-bindings"})


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
                          (when (seq legacy) [legacy-list])))]
    (definition-closure all [(keyword root "ui")])))


(defn descriptor
  "Exact identities for catalog navigation and pre-apply cleanup registration."
  [root]
  {:explorer-id (str (ids/fn-id (str root ".explorer") :explorer-toolbar-view))
   :explorer-initial-id (str (ids/fn-id (str root ".explorer") :explorer-toolbar-initial))
   :explorer-update-id (str (ids/fn-id (str root ".explorer") :explorer-toolbar-update))
   :configuration-id (str (ids/fn-id root :ui))
   :theme-id (str (ids/fn-id (str root ".theme") :theme))
   :menu-id (str (ids/fn-id (str root ".menu") :account-menu-view))
   :menu-update-id (str (ids/fn-id (str root ".menu") :account-menu-update))
   :picker-id (str (ids/fn-id (str root ".picker") :picker-view))
   :inspector-id (str (ids/fn-id (str root ".inspector") :inspector-view))
   :inspector-initial-id (str (ids/fn-id (str root ".inspector") :inspector-initial))
   :inspector-update-id (str (ids/fn-id (str root ".inspector") :inspector-update))
   :recents-id (str (ids/fn-id (str root ".recents") :recents-view))
   :recents-initial-id (str (ids/fn-id (str root ".recents") :recents-initial))
   :recents-update-id (str (ids/fn-id (str root ".recents") :recents-update))
   :canvas-id (str (ids/fn-id (str root ".canvas") :canvas-view))
   :canvas-values-id (str (ids/fn-id (str root ".canvas-values") :canvas-values-view))
   :canvas-inheritance-id (str (ids/fn-id (str root ".canvas-inheritance") :inheritance-view))
   :canvas-metadata-id (str (ids/fn-id (str root ".canvas-metadata") :metadata-view))
   :canvas-type-structure-id (str (ids/fn-id (str root ".canvas-type-structure") :type-structure-view))
   :canvas-type-structure-initial-id (str (ids/fn-id (str root ".canvas-type-structure") :type-structure-initial))
   :canvas-bindings-id (str (ids/fn-id (str root ".canvas-bindings") :bindings-view))
   :tutorial-catalog-id (str (ids/fn-id (str root ".tutorial-catalog") :catalog-view))
   :execution-status-id (str (ids/fn-id (str root ".execution-status") :status-view))
   :execution-path-id (str (ids/fn-id (str root ".execution-path") :path-view))
   :feedback-id (str (ids/fn-id (str root ".feedback") :feedback-view))
   :keymap-id (str (ids/fn-id (str root ".keymap") :keymap-view))})
