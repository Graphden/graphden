(ns graphden.packages.closedinputfixture.structured.impls)


(def impls
  {:fixture-closed-apply-text
   (:closed-apply-text @(requiring-resolve 'graphden.packages.closedinputfixture.native.impls/impls))})
