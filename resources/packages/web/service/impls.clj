(ns graphden.packages.web.service.impls
  "Primitive reads for the graph-composed service endpoint policy.
   The fn-graph templates
   (`:service-url`, `:service-get`, `:service-post`, …) compose this
   with `web/http-client`. Nothing here dials."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.endpoint :as endpoint]))


(defbase service-rows
  "Enabled service rows for this fn on the caller's branch."
  [service]
  (cr/record-effect! :db)
  (endpoint/service-rows-for (request/require-storage ctx) service))


(defbase service-instance-facts
  "One scoped instance read; ordering and rendering stay in the graph."
  [rows]
  (cr/record-effect! :db)
  (endpoint/instance-facts (request/require-storage ctx) rows (or (:executor-id ctx) "local")))


(defbase temporary-service-endpoint
  "Finite HTTP publication resolver seam; nil when not published."
  [service]
  (cr/record-effect! :db)
  (when-let [resolver @endpoint/temporary-resolver]
    (resolver ctx service)))


(defbase addon-service-endpoint
  "Deployment addon resolver seam; nil when no app route is active."
  [service]
  (cr/record-effect! :db)
  (when-let [resolver @endpoint/resolver]
    (resolver ctx service)))


(def impls
  {:_service-rows service-rows
   :_service-instance-facts service-instance-facts
   :_temporary-service-endpoint temporary-service-endpoint
   :_addon-service-endpoint addon-service-endpoint})
