(ns graphden.packages.lesson-contact.structured-input.impls)


(def impls
  {:lesson-contact-route (:contact-route-native @(requiring-resolve 'graphden.packages.lesson-contact.native.impls/impls))})
