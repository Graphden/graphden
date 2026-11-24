(ns graphden.test-infra.lesson-compute
  "Operator pins for the renderer, builder and the callback with an exposed mark."
  (:require
    [graphden.packages.records.ids :as ids]))


(def root (ids/fn-id "lesson-compute.structured" :lesson-computed-greeting))
(def builder (ids/fn-id "lesson-compute.builder" :build-greeting-profile))
(def callback (ids/fn-id "lesson-compute.callback" :decorate-greeting-input))


(def admissions
  [{:module ["lesson-compute" "structured"] :fn-id root
    :abi-fn-id (ids/fn-id "lesson-compute.native" :deliver-greeting)
    :record-contract {:profile {:name :text}
                      :style {:suffix :text :callback [:fn {:string :text} :text #{}]}}
    :sha256 "cda5c20ff1d13433b5b3555c0146e6d5748e4a6d90bd1713593bec84e9b95bde"}
   {:module ["lesson-compute" "builder"] :fn-id builder
    :abi-fn-id (ids/fn-id "lesson-compute.native" :build-greeting-record)
    :record-contract {:profile {:name :text}}
    :sha256 "8562f35d1c08f3f56aaa143d6eea08f22976ee420922e4c7b37fe4a526b42e52"}
   {:module ["lesson-compute" "callback"] :fn-id (ids/fn-id "lesson-compute.callback" :decorate-greeting-input)
    :abi-fn-id (ids/fn-id "lesson-compute.native" :decorate-greeting)
    :sha256 "43a299cd036335f4985e01db9f21264d04bc8f76ca80176223ff6956484cb410"}])
