(ns graphden.packages.optionalinput.structured.impls)


(def impls
  {:optional-label
   (:render-nullable-label @(requiring-resolve 'graphden.packages.optionalinput.native.impls/impls))})
