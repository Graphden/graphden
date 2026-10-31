(ns graphden.editor.root-migration-selection
  "Capture authenticated ordinary or legacy component selection for the
   explicit personal HOF root migration."
  (:require
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(defn- reject!
  [reason]
  (throw (ex-info "Personal root migration selection is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn capture
  "Capture the ordinary configuration and the owner's prior preference state."
  [storage authorize! policy]
  (let [current (resolution/capture storage authorize! policy)]
    (if (not= resolution/defaults-id (:id current))
      current
      (let [rows (sp/query-entities storage :ui-pref
                                    {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})]
        (when (> (count rows) 1) (reject! :invalid-selection))
        (if (some? (:value (first rows)))
          (let [id (components/selected-id-from-rows storage rows)
                row (sp/read-entity storage :fn id)]
            (when-not row (reject! :source-missing))
            (authorize! row {:execute? true})
            (let [graph (source/collect-manifest storage id authorize!)
                  captured {:graph graph :namespaces (source/collect-namespaces storage graph)}]
              (snapshot/validate-source! captured id policy)
              (-> current
                  (assoc :id id :configuration (config/configuration graph id)
                         :snapshot captured
                         :legacy-preference {:present true :value (:value (first rows))})
                  (dissoc :revision))))
          (assoc current :legacy-preference {:present (boolean (first rows))
                                             :value (:value (first rows))}))))))
