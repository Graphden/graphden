(ns graphden.editor.config-templates
  "Small ordinary configuration compositions. Legacy roots remain real
   dependencies; component implementations and their closures are not copied."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-templates :as components]
    [graphden.executor.composition.core :as composition]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]))


(defn- module
  [path]
  (edn/read-string (slurp (io/resource (str "packages/" path "/fns.edn")))))


(defn interfaces
  "Canonical parse declarations only. Publication separately authorizes the
   installed dependency rows and verifies their source/type certificates."
  []
  (let [{all-fns :fns path :namespace} (module "app/ui-config")
        ;; Configuration templates consume identity manifests, not browser
        ;; bootstrap implementations or their callable record contracts.
        fns (remove #(contains? #{:_editor-bootstrap-result :legacy-editor-root
                                  :_root-account-menu :component-editor-root :editor-root}
                                (:name %)) all-fns)
        collections (module "core/collections")
        identity-targets (filter #(and (keyword? %) (namespace %))
                                 (keep #(get-in % [:args :value :ref]) fns))
        entries (map components/identity-interface identity-targets)]
    (into (components/interface-definitions)
          (concat entries (map #(assoc % :namespace path) fns)
                  (map #(assoc % :namespace "core.collections")
                       (filter #(contains? #{:into :merge} (:name %)) (:fns collections)))
                  (map #(assoc % :namespace "web.graph-metadata")
                       (:fns (module "web/graph-metadata")))))))


(defn- legacy-record
  [root reference]
  (let [roles [[:account-menu :initial :_legacy-menu-initial]
               [:account-menu :update :_legacy-menu-update]
               [:account-menu :view :_legacy-menu-view]
               [:fn-picker :view :_legacy-picker-view]]
        local #(keyword (str "_legacy-" (name %1) "-" (name %2)))
        entries (mapv (fn [[group role _]]
                        {:name (local group role) :namespace root :parent :core.collections/zipmap
                         :return-type :app.ui-config/_component-entry
                         :args {:keys [{:value :role} {:value :fn-id}]
                                :vals [{:value role} (keyword root (str (name (local group role)) "-identity"))]}})
                      roles)
        identities (mapv (fn [[group role getter]]
                           {:name (keyword (str (name (local group role)) "-identity"))
                            :namespace root :parent (keyword "app.ui-config" (name getter))
                            :args {:coll {:ref reference}}}) roles)
        groups (mapv (fn [group]
                       {:name (keyword (str "_legacy-" (name group) "-entries")) :namespace root
                        :parent :core.collections/list :return-type :app.ui-config/_component-entries
                        :args {:items (mapv (fn [[entry-group role]] (keyword root (name (local entry-group role))))
                                            (filter #(= group (first %)) roles))}})
                     [:account-menu :fn-picker])]
    (into (into entries identities)
          (conj groups
                {:name :_legacy-components :namespace root :parent :core.collections/zipmap
                 :args {:keys [:app.ui-config/_account-menu-view-id :app.ui-config/_fn-picker-view-id]
                        :vals [(keyword root "_legacy-account-menu-entries")
                               (keyword root "_legacy-fn-picker-entries")]}}))))


(defn definitions
  "Use a fresh parser namespace/identity cohort, then persist in the exact
   authorized home. Never derive cross-organization identity from a home path.
   The legacy reference is an internal alias for one source-authorized UUID."
  [root legacy-reference legacy-record?]
  (if-not legacy-reference
    [{:name :editor :namespace root :parent :app.ui-config/editor-defaults}]
    (into (cond
            (= :map legacy-record?) []
            legacy-record? (legacy-record root legacy-reference)
            :else [{:name :_legacy-descriptor-pairs :namespace root :parent :core.hof/map
                    :args {:coll {:ref legacy-reference} :func {:ref :app.ui-config/_descriptor-pair}}}
                   {:name :_legacy-components :namespace root :parent :core.collections/into
                    :args {:to {:value {}} :from (keyword root "_legacy-descriptor-pairs")}}])
          [{:name :editor :namespace root :parent :core.collections/merge
            :args {:maps [:app.ui-config/editor-defaults
                          (if (= :map legacy-record?) {:ref legacy-reference} (keyword root "_legacy-components"))]}}])))


(defn- parse-cohort
  [root function-definitions references]
  (let [declarations (interfaces)
        index (fn [value-fn]
                (reduce (fn [result {:keys [name] path :namespace :as definition}]
                          (assoc result name (value-fn definition)
                                 (keyword path (clojure.core/name name)) (value-fn definition)))
                        {} declarations))
        reference-names (into references (map (fn [[reference id]] [(keyword (name reference)) id])) references)
        names (merge (ids/primitive-fn-ids) (index #(ids/fn-id (:namespace %) (:name %))) reference-names)
        suffix (last (str/split root #"\."))
        local-names (into {} (keep (fn [{:keys [name]}]
                                     (when (not= :editor name)
                                       [name (keyword (str (clojure.core/name name) "-" suffix))]))) function-definitions)
        rewrite (fn [reference]
                  (if (or (nil? (namespace reference)) (= root (namespace reference)))
                    (if-let [renamed (get local-names (keyword (name reference)))]
                      (if (namespace reference) (keyword root (name renamed)) renamed)
                      reference)
                    reference))
        fresh-definitions (mapv #(assoc (rebase/rewrite-references % rewrite)
                                        :name (get local-names (:name %) (:name %))) function-definitions)
        records (composition/fn-defs->records fresh-definitions names (index identity))]
    {:definitions fresh-definitions :records records :root-id (ids/fn-id root :editor)
     :reference-identities references}))


(defn parse
  "Parse a fresh configuration cohort. Actual source-authorized UUID aliases
   are parser-only: no component implementation or shared helper is copied."
  ([root legacy-id legacy-record?]
   (parse root legacy-id legacy-record? nil))
  ([root legacy-id legacy-record? prior-id]
   (when (or (and legacy-id (not (uuid? legacy-id)))
             (and prior-id (or (not (uuid? prior-id)) (nil? legacy-id)))
             (not (contains? #{nil true false :map} legacy-record?)))
     (throw (ex-info "Invalid configuration composition"
                     {:type :browser-plan/unsupported :reason :invalid-configuration})))
   (let [reference (when legacy-id (keyword root "_retained-manifest"))
         prior-reference (when prior-id (keyword root "_prior-editor"))
         cohort-definitions (mapv (fn [definition]
                                    (if (and prior-id (= :editor (:name definition)))
                                      (update-in definition [:args :maps]
                                                 #(into [(first %) {:ref prior-reference}] (rest %)))
                                      definition))
                                  (definitions root reference legacy-record?))
         references (cond-> {} reference (assoc reference legacy-id)
                            prior-reference (assoc prior-reference prior-id))]
     (assoc (parse-cohort root cohort-definitions references) :retained-reference reference))))


(defn personalize
  "Create only ordinary role children and descriptor constructors. Parents
   are actual current role identities; existing edits remain inherited."
  [root component roles prior-id]
  (let [ordered (filterv #(contains? roles %) [:initial :update :view])]
    (when-not (and (contains? config/component-contracts component)
                   (= (get-in config/component-contracts [component :roles]) (set ordered) (set (keys roles)))
                   (every? uuid? (vals roles)) (or (nil? prior-id) (uuid? prior-id)))
      (throw (ex-info "Invalid component inheritance"
                      {:type :browser-plan/unsupported :reason :invalid-configuration})))
    (let [local #(keyword root (str (name component) "-" (name %)))
          role-reference #(keyword root (str "_role-parent-" (name %)))
          identity-name #(keyword root (str "_role-identity-" (name %)))
          entry-name #(keyword root (str "_role-entry-" (name %)))
          prior-reference (when prior-id (keyword root "_prior-editor"))
          cohort-definitions (vec
                               (concat
                                 (map (fn [role]
                                        {:name (keyword (name (local role))) :namespace root
                                         :parent (role-reference role)}) ordered)
                                 (map (fn [role]
                                        {:name (keyword (name (identity-name role))) :namespace root
                                         :parent :core.logic/const :return-type :fn-ref
                                         :args {:value {:ref (local role) :type :fn-ref}}}) ordered)
                                 (map (fn [role]
                                        {:name (keyword (name (entry-name role))) :namespace root
                                         :parent :core.collections/zipmap :return-type :app.ui-config/_component-entry
                                         :args {:keys [{:value :role} {:value :fn-id}]
                                                :vals [{:value role} (identity-name role)]}}) ordered)
                                 [{:name :_personal-entries :namespace root :parent :core.collections/list
                                   :return-type :app.ui-config/_component-entries
                                   :args {:items (mapv entry-name ordered)}}
                                  {:name :_personal-components :namespace root :parent :core.collections/zipmap
                                   :args {:keys [(keyword "app.ui-config" (str "_" (name component) "-view-id"))]
                                          :vals [(keyword root "_personal-entries")]}}
                                  {:name :editor :namespace root :parent :core.collections/merge
                                   :args {:maps (cond-> [:app.ui-config/editor-defaults]
                                                  prior-reference (conj {:ref prior-reference})
                                                  true (conj (keyword root "_personal-components")))}}]))
          references (cond-> (into {} (map (fn [[role id]] [(role-reference role) id])) roles)
                       prior-reference (assoc prior-reference prior-id))]
      (parse-cohort root cohort-definitions references))))
