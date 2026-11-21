(ns graphden.packages.lessonpair.structured.impls)


(def impls
  {:lesson-contact-route
   (:contact-route-native @(requiring-resolve 'graphden.packages.lessonpair.native.impls/impls))})
