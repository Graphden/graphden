(ns graphden.types.rules-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.rules :as r]))


;; -----------------------------------------------------------------------------
;; :assoc

(deftest assoc-builds-record-from-empty-map
  (testing "first key of an empty / unknown m → singleton record"
    (is (= {:name :text}
           (r/compute-return-type :assoc
                                  {:map   {:type :any :value {}}
                                   :key   {:type :text :value "name"}
                                   :value {:type :text :value "Alice"}}
                                  :any)))))


(deftest assoc-extends-known-record
  (testing "extending an existing record adds the field"
    (is (= {:name :text :age :int}
           (r/compute-return-type :assoc
                                  {:map   {:type {:name :text} :value nil}
                                   :key   {:type :text :value "age"}
                                   :value {:type :int :value 30}}
                                  :any)))))


(deftest assoc-overrides-existing-field
  (testing "writing the same key replaces the existing field type"
    (is (= {:name :text}
           (r/compute-return-type :assoc
                                  {:map   {:type {:name :int} :value nil}
                                   :key   {:type :text :value "name"}
                                   :value {:type :text :value "Alice"}}
                                  :any)))))


(deftest assoc-degrades-on-computed-key
  (testing ":key is a ref / non-literal → degrade to :jsonb"
    (is (= :jsonb
           (r/compute-return-type :assoc
                                  {:map   {:type {} :value {}}
                                   :key   {:type :text :value nil}      ; ref
                                   :value {:type :int :value 42}}
                                  :any)))))


(deftest assoc-keyword-key-also-works
  (testing "literal :keyword key normalises to its name"
    (is (= {:age :int}
           (r/compute-return-type :assoc
                                  {:map   {:type {} :value {}}
                                   :key   {:type :keyword :value :age}
                                   :value {:type :int :value 30}}
                                  :any)))))


;; -----------------------------------------------------------------------------
;; :dissoc

(deftest dissoc-removes-known-field
  (testing "removing a literal key from a known record"
    (is (= {:name :text}
           (r/compute-return-type :dissoc
                                  {:map {:type {:name :text :age :int}}
                                   :key {:type :text :value "age"}}
                                  :jsonb)))))


(deftest dissoc-degrades-on-computed-key
  (is (= :jsonb
         (r/compute-return-type :dissoc
                                {:map {:type {:name :text}}
                                 :key {:type :text :value nil}}
                                :jsonb))))


;; -----------------------------------------------------------------------------
;; :get

(deftest get-returns-field-type-for-known-record
  (testing "looking up a present field gives its type"
    (is (= :text
           (r/compute-return-type :get
                                  {:coll {:type {:name :text :age :int}}
                                   :key  {:type :text :value "name"}}
                                  :any)))))


(deftest get-throws-on-missing-field-typo
  (testing "missing field on a known record is a TYPO — throw with available fields"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"field :missing not found"
          (r/compute-return-type :get
                                 {:coll {:type {:name :text :age :int}}
                                  :key  {:type :text :value "missing"}}
                                 :any)))))


(deftest get-degrades-when-coll-not-known-record
  (testing "non-record coll: degrade to default (no info to typo-check)"
    (is (= :any
           (r/compute-return-type :get
                                  {:coll {:type :jsonb}
                                   :key  {:type :text :value "name"}}
                                  :any)))))


(deftest get-degrades-when-key-not-literal
  (testing "computed key: can't typo-check (value is unknown at sync)"
    (is (= :any
           (r/compute-return-type :get
                                  {:coll {:type {:name :text}}
                                   :key  {:type :text :value nil}}
                                  :any)))))


;; -----------------------------------------------------------------------------
;; :first / :rest / :cons — list-elem propagation

(deftest first-on-typed-list-returns-elem-type
  (is (= :int
         (r/compute-return-type :first
                                {:coll {:type [:list :int]}}
                                :any))))


(deftest first-on-untyped-coll-falls-back-to-default
  (is (= :any
         (r/compute-return-type :first
                                {:coll {:type :jsonb}}
                                :any))))


(deftest rest-preserves-list-elem-type
  (is (= [:list :text]
         (r/compute-return-type :rest
                                {:coll {:type [:list :text]}}
                                :jsonb))))


(deftest cons-preserves-list-elem-type
  (is (= [:list :int]
         (r/compute-return-type :cons
                                {:item {:type :int}
                                 :coll {:type [:list :int]}}
                                :jsonb))))


;; -----------------------------------------------------------------------------
;; :keys / :vals — record introspection

(deftest keys-on-known-record-returns-list-of-keyword
  (is (= [:list :keyword]
         (r/compute-return-type :keys
                                {:map {:type {:name :text :age :int}}}
                                :jsonb))))


(deftest vals-on-uniform-record-returns-precise-elem-type
  (is (= [:list :text]
         (r/compute-return-type :vals
                                {:map {:type {:name :text :nick :text}}}
                                :jsonb))))


(deftest vals-on-mixed-record-degrades-to-list-any
  (is (= [:list :any]
         (r/compute-return-type :vals
                                {:map {:type {:name :text :age :int}}}
                                :jsonb))))


(deftest keys-on-non-record-falls-back-to-default
  (is (= :jsonb
         (r/compute-return-type :keys
                                {:map {:type :jsonb}}
                                :jsonb))))


;; -----------------------------------------------------------------------------
;; :get-in — literal-path walk over nested records

(deftest get-in-walks-nested-record
  (is (= :text
         (r/compute-return-type :get-in
                                {:map  {:type {:user {:name :text :age :int}}}
                                 :path {:type :sequence :value [:user :name]}}
                                :any))))


(deftest get-in-falls-back-on-missing-path-segment
  (is (= :any
         (r/compute-return-type :get-in
                                {:map  {:type {:user {:name :text}}}
                                 :path {:type :sequence :value [:user :missing]}}
                                :any))))


(deftest get-in-falls-back-on-non-literal-path
  (is (= :any
         (r/compute-return-type :get-in
                                {:map  {:type {:user {:name :text}}}
                                 :path {:type :sequence :value nil}}
                                :any))))


;; -----------------------------------------------------------------------------
;; :take / :drop / :reverse / :sort / :distinct — preserve list elem-type

(deftest take-preserves-list-elem-type
  (is (= [:list :int]
         (r/compute-return-type :take
                                {:count {:type :int} :coll {:type [:list :int]}}
                                :jsonb))))


(deftest drop-preserves-list-elem-type
  (is (= [:list :text]
         (r/compute-return-type :drop
                                {:count {:type :int} :coll {:type [:list :text]}}
                                :jsonb))))


(deftest reverse-preserves-list-elem-type
  (is (= [:list :int]
         (r/compute-return-type :reverse
                                {:coll {:type [:list :int]}}
                                :jsonb))))


(deftest sort-and-distinct-preserve-elem-type
  (is (= [:list :int]
         (r/compute-return-type :sort
                                {:coll {:type [:list :int]}} :jsonb)))
  (is (= [:list :int]
         (r/compute-return-type :distinct
                                {:coll {:type [:list :int]}} :jsonb))))


;; -----------------------------------------------------------------------------
;; :concat — list of lists → list

(deftest concat-of-list-of-list-of-T-returns-list-of-T
  (is (= [:list :int]
         (r/compute-return-type :concat
                                {:colls {:type [:list [:list :int]]}}
                                :jsonb))))


(deftest concat-of-shallow-list-falls-back
  (is (= :jsonb
         (r/compute-return-type :concat
                                {:colls {:type [:list :int]}}
                                :jsonb))))


;; -----------------------------------------------------------------------------
;; Arithmetic narrowing :numeric → :int when every operand is :int

(deftest add-on-list-of-int-narrows-to-int
  (is (= :int
         (r/compute-return-type :add
                                {:nums {:type [:list :int]}}
                                :numeric))))


(deftest add-on-list-of-numeric-stays-numeric
  (is (= :numeric
         (r/compute-return-type :add
                                {:nums {:type [:list :numeric]}}
                                :numeric))))


(deftest sub-mul-narrow-on-int-list
  (is (= :int
         (r/compute-return-type :sub
                                {:nums {:type [:list :int]}} :numeric)))
  (is (= :int
         (r/compute-return-type :mul
                                {:nums {:type [:list :int]}} :numeric))))


(deftest mod-narrows-on-int-int
  (is (= :int
         (r/compute-return-type :mod
                                {:dividend {:type :int} :divisor {:type :int}}
                                :numeric))))


(deftest mod-stays-numeric-on-mixed
  (is (= :numeric
         (r/compute-return-type :mod
                                {:dividend {:type :int} :divisor {:type :numeric}}
                                :numeric))))


(deftest neg-abs-narrow-on-int
  (is (= :int
         (r/compute-return-type :neg
                                {:number {:type :int}} :numeric)))
  (is (= :int
         (r/compute-return-type :abs
                                {:number {:type :int}} :numeric))))


;; -----------------------------------------------------------------------------
;; :into — preserves destination list elem-type

(deftest into-preserves-list-elem-type
  (is (= [:list :int]
         (r/compute-return-type :into
                                {:to {:type [:list :int]} :from {:type :jsonb}}
                                :jsonb))))


(deftest into-falls-back-on-jsonb-target
  (is (= :jsonb
         (r/compute-return-type :into
                                {:to {:type :jsonb} :from {:type :jsonb}}
                                :jsonb))))


;; -----------------------------------------------------------------------------
;; :assoc-in — walk a literal path and update the deepest field

(deftest assoc-in-updates-deep-field-on-known-record
  (is (= {:user {:name :text :age :int}}
         (r/compute-return-type :assoc-in
                                {:m {:type {:user {:name :text :age :int}}}
                                 :path {:type :sequence :value [:user :age]}
                                 :v {:type :int :value 42}}
                                :any))))


(deftest assoc-in-extends-known-record-with-new-field
  (is (= {:user {:name :text :age :int}}
         (r/compute-return-type :assoc-in
                                {:m {:type {:user {:name :text}}}
                                 :path {:type :sequence :value [:user :age]}
                                 :v {:type :int :value 42}}
                                :any))))


(deftest assoc-in-builds-record-on-empty-input
  (is (= {:user {:name :text}}
         (r/compute-return-type :assoc-in
                                {:m {:type :any}
                                 :path {:type :sequence :value [:user :name]}
                                 :v {:type :text :value "Alice"}}
                                :any))))


(deftest assoc-in-falls-back-on-non-literal-path
  (is (= :any
         (r/compute-return-type :assoc-in
                                {:m {:type {:user {:name :text}}}
                                 :path {:type :sequence :value nil}
                                 :v {:type :int :value 1}}
                                :any))))


;; -----------------------------------------------------------------------------
;; :if — handled by type-var polymorphism in the declaration
;; (`:then 'a :else 'a → 'a`), no `compute-return-type :if` rule.
;; The previous union-based rule was deleted because it fought the
;; type-var unification: matching branches now narrow to the shared
;; type, mismatched branches now fail at sync time (per the
;; "защищает от ошибок" goal).


;; -----------------------------------------------------------------------------
;; :default

(deftest default-passes-through
  (testing "fns without a custom rule return the static return verbatim"
    (is (= :int
           (r/compute-return-type :int-add {} :int)))))


;; -----------------------------------------------------------------------------
;; :dissoc / :merge edge cases — small gaps in earlier coverage.

(deftest dissoc-on-jsonb-falls-back-to-default
  (testing ":dissoc with literal key but non-record m → default-ret"
    (is (= :jsonb
           (r/compute-return-type :dissoc
                                  {:map {:type :jsonb}
                                   :key {:type :text :value "name"}}
                                  :jsonb)))))


(deftest merge-passes-through-maps-type-or-default
  (testing ":merge returns :maps's type when a list/sequence shape is known"
    (is (= [:list :any]
           (r/compute-return-type :merge
                                  {:maps {:type [:list :any]}}
                                  :jsonb))))
  (testing ":merge degrades to default when :maps has no type info"
    (is (= :jsonb
           (r/compute-return-type :merge
                                  {}
                                  :jsonb)))))
