(ns graphden.executor.browser-preview
  "The self-hosted preview boundary. Stored rows and derived visibility come
   from one database snapshot. This is deliberately unavailable with tenancy."
  (:require
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.registry.core :as registry]
    [graphden.tenancy.context :as tenancy]))


(defn export-current
  [ctx entries]
  ;; This must precede ANY privileged graph read, including registry warmup.
  (when (tenancy/tenancy-addon-active?)
    (throw (ex-info "Browser preview is limited to self-hosted installations"
                    {:type :browser-plan/unsupported :reason :tenancy-disabled})))
  (when-not (and (map? entries) (seq entries)
                 (every? #{:initial :update :view} (keys entries))
                 (every? uuid? (vals entries)))
    (throw (ex-info "Valid browser entry identities are required"
                    {:type :browser-plan/unsupported :reason :invalid-entries})))
  (let [rich @(or (:rich-types-atom ctx) (registry/active-rich-types-atom))
        policy (snapshot/capture-policy rich)
        captured (snapshot/read-snapshot (:storage ctx))]
    (snapshot/export-snapshot captured (:base-fns ctx) entries policy)))
