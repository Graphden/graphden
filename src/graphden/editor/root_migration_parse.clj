(ns graphden.editor.root-migration-parse
  "Parse-only vocabulary for the explicit personal root migration."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.editor.cohort-parser :as cohort]
    [graphden.editor.component-templates :as components]
    [graphden.editor.root-legacy :as legacy]
    [graphden.editor.root-migration-templates :as templates]))


(defn- packaged-definitions
  [path]
  (:fns (edn/read-string
          (slurp (io/resource (str "packages/" (str/replace path "." "/") "/fns.edn"))))))


(defn- interfaces
  "Fixed parser vocabulary. Component bodies are never imported: role
   sources remain UUID aliases, and only shipped callable and assoc-fn shapes
   are used to parse the new cohort."
  []
  (let [component-declarations
        (mapcat (fn [[_ {path :namespace :keys [roles]}]]
                  (let [definitions (packaged-definitions path)
                        wanted (into #{:root-component}
                                     (map #(keyword (str "_root-" (name %) "-assoc"))) roles)]
                    (map #(assoc % :namespace path)
                         (filter #(or (contains? wanted (:name %))
                                      (:type %) (:fn-type %)) definitions))))
                legacy/components)
        config-declarations
        (map #(assoc % :namespace "app.ui-config")
             (filter #(= :_editor-bootstrap-result (:name %))
                     (packaged-definitions "app.ui-config")))
        legacy-namespaces (into #{} (map :namespace) (vals legacy/components))
        bootstrap-namespaces (into #{} (keep namespace)
                                   (mapcat (comp vals :type) config-declarations))
        additional-types
        (mapcat (fn [path]
                  (map #(assoc % :namespace path)
                       (filter #(or (:type %) (:fn-type %))
                               (packaged-definitions path))))
                (sort (set/difference bootstrap-namespaces legacy-namespaces)))
        call-declaration
        (assoc (some #(when (= :call (:name %)) %)
                     (packaged-definitions "core.system"))
               :namespace "core.system")
        component-identities
        (map (fn [[_ {path :namespace :keys [root-component]}]]
               (components/identity-interface (keyword path (name root-component))))
             legacy/components)]
    (into (components/interface-definitions)
          (concat component-declarations additional-types config-declarations
                  [call-declaration] component-identities
                  [(components/identity-interface :app.ui-config/component-editor-root)
                   (components/identity-interface :app.ui-account-menu/_root-common-tree)]))))


(defn parse
  "Parse fresh wrappers and a new personal editor-root. The caller must check
   source and ACL for every original role UUID and installed dependency before
   using the returned records as a write proposal."
  [root configuration]
  (let [{:keys [definitions references overrides]} (templates/definitions root configuration)]
    (assoc (cohort/parse-cohort root :editor-root definitions references (interfaces))
           :overrides overrides)))
