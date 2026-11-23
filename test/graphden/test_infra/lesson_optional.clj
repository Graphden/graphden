(ns graphden.test-infra.lesson-optional
  "Operator pin for the nullable text lesson."
  (:require
    [graphden.packages.records.ids :as ids]))


(def root (ids/fn-id "optionalinput.structured" :optional-label))


(def admission
  {:module ["optionalinput" "structured"] :fn-id root
   :abi-fn-id (ids/fn-id "optionalinput.native" :render-nullable-label)
   :sha256 "12b784c5cbec2c467ebcebfe18eb22babaf5fe7d6eb210689e0557ebcf4d8319"})
