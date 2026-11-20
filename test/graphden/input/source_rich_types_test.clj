(ns graphden.input.source-rich-types-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-rich-types :as rich]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as records]
    [graphden.types.core :as types]))


(deftest callable-field-requires-the-exact-anonymous-row
  (let [type [:fn {:item 'a} 'b]
        [row] (records/inline-fn-type-rows-from-form type)
        id (:id row)
        stale (assoc row :constraint [:fn {:item :text} :int])]
    (is (rich/callable-field? {id [row row]} id type))
    (is (not (rich/callable-field? {} id type)))
    (is (not (rich/callable-field? {id [row stale]} id type)))
    (is (not (rich/callable-field? {id [row]} id (types/resolve-alias type))))
    (is (not (rich/callable-field? {id [row]} (ids/primitive-fn-id :fn) type)))))


(deftest unsupported-callback-value-type-still-needs-verified-evidence
  (let [type [:fn {:item :unverified-typedef} 'b]
        [row] (records/inline-fn-type-rows-from-form type)]
    (is (not (rich/callable-field? {(:id row) [row]} (:id row) type)))))


(deftest collection-return-shape-must-match-the-physical-return-fk
  (is (rich/list-return? (ids/primitive-fn-id :sequence) [:list 'b]))
  (is (not (rich/list-return? (ids/primitive-fn-id :text) [:list 'b])))
  (is (not (rich/list-return? (ids/primitive-fn-id :sequence) [:list :unverified-typedef]))))
