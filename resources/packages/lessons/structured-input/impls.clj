(ns graphden.packages.lessons.structured-input.impls)


(def impls
  {:lesson-profile (:profile-value @(requiring-resolve 'graphden.packages.lessons.native.impls/impls))})
