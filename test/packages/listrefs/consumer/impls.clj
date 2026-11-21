(ns graphden.packages.listrefs.consumer.impls)


(def impls
  {:first-parsed (:first-integer @(requiring-resolve 'graphden.packages.listrefs.native.impls/impls))})
