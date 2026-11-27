(ns graphden.packages.inputfixture.numeric.impls)


(def impls
  ;; The loader evaluates impl files in dependency order.
  {:fixture-floor (:floor @(requiring-resolve 'graphden.packages.core.arithmetic.impls/impls))})
