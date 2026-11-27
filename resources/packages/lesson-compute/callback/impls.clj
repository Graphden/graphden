(ns graphden.packages.lesson-compute.callback.impls)


(def impls
  {:decorate-greeting-input (:decorate-greeting @(requiring-resolve 'graphden.packages.lesson-compute.native.impls/impls))})
