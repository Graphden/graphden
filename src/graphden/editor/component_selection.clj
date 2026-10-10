(ns graphden.editor.component-selection
  "Authoritative preference/source proof for immutable component additions."
  (:require
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(defn capture
  "Read the current owner's selection and validate its bounded manifest source.
   The caller freezes policy before entering its graph-writer transaction. No
   existing component body is collected for an addition."
  [storage group authorize! policy root]
  (let [rows (sp/query-entities storage :ui-pref
                                {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})
        expected {:present (boolean (seq rows)) :value (:value (first rows))}]
    (when (> (count rows) 1)
      (throw (ex-info "Invalid component selection" {:reason :invalid-selection :http-status 422})))
    (if (nil? (:value expected))
      {:expected expected :names {}}
      (let [id (components/selected-id-from-rows storage rows)
            graph (source/collect-manifest storage id authorize!)
            namespaces (source/collect-namespaces storage graph)
            _ (components/assert-current-policy! storage)
            _ (snapshot/validate-source! {:graph graph :namespaces namespaces} id policy)
            configuration (config/configuration graph id)
            legacy? (some #{config/configuration-id}
                          (lookups/inheritance-chain* id (lookups/build-lookups graph)))
            ;; Parser-only aliases cannot collide with shipped interfaces or
            ;; existing definitions: this fresh root has no rows yet. Records
            ;; retain actual UUIDs, and publication checks reconstructed DB
            ;; source, not these import-time aliases (including anonymous refs).
            reference #(keyword root (str "_retained-target-" %))
            legacy (when legacy?
                     (into {} (map (fn [[component roles]]
                                     [component (into {} (map (fn [[role _]]
                                                                [role (reference (get roles role))])) roles)]))
                           configuration))
            names (if legacy?
                    (into {} (mapcat (fn [[component roles]]
                                       (map (fn [[role target]] [(get-in legacy [component role]) target]) roles)))
                          configuration)
                    {(reference id) id})]
        (when (contains? configuration group)
          (throw (ex-info "Component already exists" {:reason :component-present :http-status 409})))
        {:expected expected :id id :graph graph :namespaces namespaces
         :retained (when-not legacy? (reference id)) :legacy legacy :names names}))))
