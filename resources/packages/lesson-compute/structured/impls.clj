(ns graphden.packages.lesson-compute.structured.impls)


(def impls
  {:lesson-computed-greeting (:deliver-greeting @(requiring-resolve 'graphden.packages.lesson-compute.native.impls/impls))})
