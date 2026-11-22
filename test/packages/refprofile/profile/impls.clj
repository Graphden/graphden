(ns graphden.packages.refprofile.profile.impls)


(def impls
  {:reference-profile (:profile-value @(requiring-resolve 'graphden.packages.lessons.native.impls/impls))})
