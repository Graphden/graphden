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
  (is (rich/erased-return? (ids/primitive-fn-id :sequence) [:list 'b]))
  (is (rich/erased-return? (ids/primitive-fn-id :jsonb) [:map 'k 'v]))
  (is (rich/erased-return? (ids/primitive-fn-id :any) [:union [:map 'a :any] [:list :any]]))
  (is (not (rich/erased-return? (ids/primitive-fn-id :jsonb) [:union [:map 'a :any] [:list :any]])))
  (is (not (rich/erased-return? (ids/primitive-fn-id :text) [:list 'b])))
  (is (not (rich/erased-return? (ids/primitive-fn-id :sequence) [:list :unverified-typedef]))))


(deftest scalar-return-evidence-does-not-invent-a-generic-or-bottom-storage-kind
  (is (rich/erased-return? (ids/primitive-fn-id :any) 'a))
  (is (rich/erased-return? (ids/primitive-fn-id :any) :never))
  (is (not (rich/erased-return? (ids/primitive-fn-id :text) 'a)))
  (is (not (rich/erased-return? (ids/primitive-fn-id :text) :never)))
  (is (not (rich/erased-return? (ids/primitive-fn-id :any) :unverified-typedef))))


(deftest erased-union-return-requires-frozen-named-members
  (let [form [:union :null :fixture/entry]
        nested [:union :null {:groups [:list {:group-id :text :change-id :fixture/entry}]}]
        aliases {:fixture/entry (ids/fn-id "fixture" :entry)}
        any-id (ids/primitive-fn-id :any)]
    (is (rich/erased-return? any-id form aliases))
    (is (not (rich/erased-return? any-id form)))
    (is (not (rich/erased-return? (ids/primitive-fn-id :jsonb) form aliases)))
    (is (rich/erased-return? any-id nested aliases))
    (is (not (rich/erased-return? any-id nested)))
    (is (not (rich/erased-return? (ids/primitive-fn-id :jsonb) nested aliases)))
    (is (not (rich/erased-return? any-id [:union :null {"groups" :text}] aliases)))))


(deftest inline-record-return-requires-the-exact-stored-fields
  (let [type {:status :text :message :nullable-text}
        alias-id (ids/fn-id "fixture" :nullable-text)
        aliases (assoc (ids/primitive-fn-ids) :nullable-text alias-id)
        [record :as rows] (parse/inline-record-rows-for-return-type type aliases)
        return-id (:id record)
        field-id (ids/slot-id return-id :message)
        rows-by-id (group-by :id rows)]
    (is (rich/inline-record-return? rows-by-id return-id type aliases))
    (is (not (rich/inline-record-return? rows-by-id return-id type (dissoc aliases :nullable-text))))
    (is (not (rich/inline-record-return? rows-by-id return-id (assoc type :message :text) aliases)))
    (is (not (rich/inline-record-return? (assoc-in rows-by-id [field-id 0 :type-fn-id] (ids/primitive-fn-id :any))
                                         return-id type aliases)))))


(deftest callback-type-dependencies-pin-qualified-source-identities
  (let [definitions [{:namespace "core.refinements" :name :positive-int :refine {:base :int :constraint [:> 0]}}
                     {:namespace "other" :name :positive-int :refine {:base :text :constraint [:!= ""]}}]
        prepared (parse/prepare-module definitions {} {})
        entries (:definitions (source/verify-module (source/plan-module definitions prepared) prepared))
        by-id (into {} (map (juxt :identity identity)) entries)
        package {:prepared prepared :declarations (into {} (map (juxt :fn-id identity)) (vals (:defs-by-name prepared)))}
        lookup-type (named/lookup package by-id)
        frozen (named/evidence {} lookup-type {:positive-int :core.refinements/positive-int})
        id (ids/fn-id "core.refinements" :positive-int)]
    (is (= id (get-in frozen [:alias-ids :positive-int]) (get-in frozen [:alias-ids :core.refinements/positive-int])))
    (is (= [:refine :int [:> 0]] (get-in frozen [:aliases :positive-int])))
    (is (= #{id} (set (keys (:type-origins frozen)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source and row evidence"
          (named/evidence {} lookup-type {:positive-int :missing/positive-int})))))


(deftest named-callable-leaves-do-not-change-the-authored-anonymous-id
  (let [type [:fn {:arg :request} :response]
        [row] (records/inline-fn-type-rows-from-form type)
        id (:id row)
        rows {id [row]}
        named-ids {:request (ids/fn-id "fixture" :request) :response (ids/fn-id "fixture" :response)}]
    (is (rich/authored-callable-field? rows id type))
    (is (not (rich/callable-field? rows id type)))
    (is (rich/callable-field? rows id type named-ids))
    (is (not (rich/callable-field? rows id type (dissoc named-ids :response))))
    (is (not (rich/callable-field? rows id [:fn {:arg :fixture/request} :fixture/response] named-ids)))
    (is (not (rich/callable-field? rows (ids/primitive-fn-id :fn) type named-ids)))
    (is (not (rich/callable-field? {id [(assoc row :constraint [:fn {:arg :text} :text])]} id type named-ids)))))
