(ns graphden.packages.reftext.upper.impls)


(def impls {:reference-uppercase (get @(requiring-resolve 'graphden.packages.core.strings.impls/impls) :str-upper)})
