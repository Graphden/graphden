(ns graphden.graph.lookup
  "Authenticated metadata lookup over one authorized immutable graph view."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.browser-source :as source]
    [graphden.graph.metadata :as metadata]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(defn find-function
  "Arguments describe selection, never authority. Organization, principal and
   branch must equal the authenticated ingress. Return an actual authorized
   fn identity only; never execute it or read implementation data."
  [ctx arguments]
  (source/with-snapshot
    (request/require-storage ctx)
    (fn [storage authorize!]
      (let [scope (assoc (select-keys arguments [:namespace-path :name])
                         :principal-id (tenancy/current-user-id)
                         :org (tenancy/current-org)
                         :branch-id (versioned/current-branch-id storage))]
        (metadata/lookup-function (metadata/function-lookup storage authorize! scope) arguments)))))
