(ns graphden.packages.lesson-compute.builder.impls)


(def impls
  {:build-greeting-profile (:build-greeting-record @(requiring-resolve 'graphden.packages.lesson-compute.native.impls/impls))})
