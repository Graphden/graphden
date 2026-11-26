(ns graphden.packages.inputfixture.power.impls)


(def impls
  {:fixture-power (:pow @(requiring-resolve 'graphden.packages.core.arithmetic.impls/impls))})
