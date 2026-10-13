(ns graphden.graph.personal-home
  "Explicit setup of the authority's generic personal graph namespace.
   Discovery is read-only; setup never creates a configuration or grants."
  (:require
    [graphden.graph.metadata :as metadata]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(defn ensure-current!
  "Return the existing authorized home, or materialize the addon-less shared
   operator profile through decorated CRUD. Configured tenancy homes must
   already exist: their trusted account lifecycle owns provisioning."
  [storage]
  (writer/with-write [storage :graph]
                     (let [home (tenancy/current-personal-namespace)
                           path (:namespace-path home)]
                       (or (when-let [row (when path (metadata/namespace-row storage path (tenancy/current-org)))]
                             (with-meta row {::created? false}))
                           (when (and (= :single-operator (:source home)) (= "personal" path))
                             (with-meta (sp/create-entity storage :ns {:name "personal"}) {::created? true}))
                           (throw (ex-info "Personal graph namespace is unavailable"
                                           {:type :personal-home/unavailable :reason :personal-home-unavailable}))))))
