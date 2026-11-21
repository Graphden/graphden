(ns graphden.test-infra.lesson-profile
  "Operator pin for the optional structured profile fixture."
  (:require
    [graphden.packages.records.ids :as ids]))


(def root (ids/fn-id "lessons.structured-input" :lesson-profile))


(def admission
  {:module ["lessons" "structured-input"] :fn-id root
   :abi-fn-id (ids/fn-id "lessons.native" :profile-value)
   :sha256 "245dcb532fe0d45601cf3d9afa01b330697fe0a461a6c3a346c7bb5f8ef9fcc3"
   :record-contract {:profile {:id :text :name :text :email :text}}})
