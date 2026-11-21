(ns graphden.packages.listoperand.parsing.impls)


(def impls
  {:integer (:parse-int @(requiring-resolve 'graphden.packages.core.system.impls/impls))})
