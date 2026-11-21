(ns graphden.packages.closedinputfixture.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase apply-text [payload]
  ((:callback payload) (:item payload)))


(def impls {:closed-apply-text apply-text})
