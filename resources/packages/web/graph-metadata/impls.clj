(ns graphden.packages.web.graph-metadata.impls
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.graph.lookup :as lookup]))


(defbase find-function
  [namespace-path name principal-id org branch-id]
  (cr/record-effect! :db)
  (lookup/find-function ctx {:namespace-path namespace-path :name name
                             :principal-id principal-id :org org :branch-id branch-id}))


(def impls {:find-function find-function})
