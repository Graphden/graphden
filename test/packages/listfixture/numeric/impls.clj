(ns graphden.packages.listfixture.numeric.impls)


(def impls
  {:list-difference (:sub @(requiring-resolve 'graphden.packages.core.arithmetic.impls/impls))})
