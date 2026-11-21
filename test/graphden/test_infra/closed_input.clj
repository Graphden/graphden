(ns graphden.test-infra.closed-input
  "Shared pin for the shipped test-only closed callable witness."
  (:require
    [graphden.packages.records.ids :as ids]))


(def root (ids/fn-id "closedinputfixture.structured" :fixture-closed-apply-text))
(def callback [:fn {:string :text} :text #{}])


(def admission
  {:module ["closedinputfixture" "structured"] :fn-id root
   :abi-fn-id (ids/fn-id "closedinputfixture.native" :closed-apply-text)
   :record-contract {:payload {:item :text :callback callback}}
   :sha256 "a0e5d0a97c71c37d3220323443b6c0c6ff9158f16b85297bf3b79dbcdaecec40"})
