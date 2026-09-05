(ns graphden.packages.web.service.impls
  "Implementation for the web/service base function — resolve a
   service fn to the address it answers on. The logic (row lookup,
   the addon resolver seam, the not-running error) lives in
   `graphden.services.endpoint`; the fn-graph templates
   (`:service-url`, `:service-get`, `:service-post`, …) compose this
   with `web/http-client`. Nothing here dials."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.endpoint :as endpoint]))


(defbase service-endpoint
  "Where the service named by `service` (a `:fn-ref` slot — the fn's
   identity, never evaluated) currently answers: `{:host :port :url}`."
  [service]
  (cr/record-effect! :db)
  (endpoint/resolve-endpoint ctx (request/require-storage ctx) service))


(def impls
  {:service-endpoint service-endpoint})
