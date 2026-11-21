(ns graphden.input.source-rich-types-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source :as source]
    [graphden.input.source-named-types :as named]
    [graphden.input.source-rich-types :as rich]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
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
  (is (rich/collection-return? (ids/primitive-fn-id :sequence) [:list 'b]))
  (is (rich/collection-return? (ids/primitive-fn-id :jsonb) [:map 'k 'v]))
  (is (rich/collection-return? (ids/primitive-fn-id :any) [:union [:map 'a :any] [:list :any]]))
  (is (not (rich/collection-return? (ids/primitive-fn-id :jsonb) [:union [:map 'a :any] [:list :any]])))
  (is (not (rich/collection-return? (ids/primitive-fn-id :text) [:list 'b])))
  (is (not (rich/collection-return? (ids/primitive-fn-id :sequence) [:list :unverified-typedef]))))


(deftest callback-type-dependencies-pin-qualified-source-identities
  (let [definitions [{:namespace "core.refinements" :name :positive-int :refine {:base :int :constraint [:> 0]}}
                     {:namespace "other" :name :positive-int :refine {:base :text :constraint [:!= ""]}}]
        prepared (parse/prepare-module definitions {} {})
        entries (:definitions (source/verify-module (source/plan-module definitions prepared) prepared))
        by-id (into {} (map (juxt :identity identity)) entries)
        package {:prepared prepared :declarations (into {} (map (juxt :fn-id identity)) (vals (:defs-by-name prepared)))}
        resolve (named/lookup package by-id)
        frozen (named/evidence {} resolve {:positive-int :core.refinements/positive-int})
        id (ids/fn-id "core.refinements" :positive-int)]
    (is (= id (get-in frozen [:alias-ids :positive-int]) (get-in frozen [:alias-ids :core.refinements/positive-int])))
    (is (= [:refine :int [:> 0]] (get-in frozen [:aliases :positive-int])))
    (is (= #{id} (set (keys (:type-origins frozen)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source and row evidence"
          (named/evidence {} resolve {:positive-int :missing/positive-int})))))
