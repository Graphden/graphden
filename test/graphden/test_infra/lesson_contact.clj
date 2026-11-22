(ns graphden.test-infra.lesson-contact
  "Operator pin for the optional lesson on independent contact records."
  (:require
    [graphden.packages.records.ids :as ids]))


(def root (ids/fn-id "lesson-contact.structured-input" :lesson-contact-route))


(def admission
  (let [contact {:name :text :address {:city :text}}]
    {:module ["lesson-contact" "structured-input"] :fn-id root
     :abi-fn-id (ids/fn-id "lesson-contact.native" :contact-route-native)
     :record-contract {:sender contact :recipient contact}
     :sha256 "3ac94222a4c8baf5f54e3b9a362859de99211e45172f2e2ca1fd151cf6110c5e"}))
