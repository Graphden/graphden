(ns graphden.packages.nesteddefaultfixture.structured.impls)


(def impls
  {:defaulted-label-pair (:describe-label-pair @(requiring-resolve 'graphden.packages.nesteddefaultfixture.native.impls/impls))})
