(ns graphden.types.rules-test
  "Unit tests for the per-base-fn type-rules. The rules used to live as
   name-dispatched `defmethod`s in `graphden.types.rules`; they are now
   plain `defn`s next to each base-fn's `defbase` in its impls.clj.
   Those impls.clj files are resources the package loader eval's — not
   classpath namespaces — so we load the core package once and resolve
   the rule fns from the resulting namespaces."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]))


;; Eval the core package's impls.clj resources so their namespaces
;; (and the type-rule fns inside them) exist.
(defonce ^:private _core-loaded (loader/load-packages ["core"]))


(defn- rule
  "Resolve a migrated type-rule fn from an eval'd impls namespace."
  [ns-sym fn-sym]
  (let [v (some-> (find-ns ns-sym) (ns-resolve fn-sym))]
    (when-not v
      (throw (ex-info (str "type-rule not found: " ns-sym "/" fn-sym) {})))
    @v))


(def ^:private return-rules
  (let [c 'graphden.packages.core.collections.impls
        a 'graphden.packages.core.arithmetic.impls
        l 'graphden.packages.core.logic.impls
        s 'graphden.packages.core.system.impls]
    {:assoc     (rule c 'assoc-return-rule)
     :dissoc    (rule c 'dissoc-return-rule)
     :get       (rule c 'get-return-rule)
     :merge     (rule c 'merge-return-rule)
     :update-in (rule c 'update-in-return-rule)
     :first     (rule c 'first-return-rule)
     :rest      (rule c 'rest-return-rule)
     :cons      (rule c 'cons-return-rule)
     :take      (rule c 'take-return-rule)
     :drop      (rule c 'drop-return-rule)
     :reverse   (rule c 'reverse-return-rule)
     :sort      (rule c 'sort-return-rule)
     :distinct  (rule c 'distinct-return-rule)
     :keys      (rule c 'keys-return-rule)
     :vals      (rule c 'vals-return-rule)
     :concat    (rule c 'concat-return-rule)
     :list      (rule c 'list-return-rule)
     :conj      (rule c 'conj-return-rule)
     :into      (rule c 'into-return-rule)
     :assoc-in  (rule c 'assoc-in-return-rule)
     :get-in    (rule c 'get-in-return-rule)
     :add       (rule a 'add-return-rule)
     :sub       (rule a 'sub-return-rule)
     :mul       (rule a 'mul-return-rule)
     :mod       (rule a 'mod-return-rule)
     :neg       (rule a 'neg-return-rule)
     :abs       (rule a 'abs-return-rule)
     :invoke    (rule s 'invoke-return-rule)
     :const     (rule l 'const-return-rule)}))


(defn- compute-return-type
  "Test shim — dispatch a return-type rule by base-fn name, mirroring
   the registry lookup the type-checker does at runtime. Base-fns with
   no rule (e.g. `:if`, `:int-add`) pass `default-ret` through."
  [base-fn-name bindings-info default-ret]
  (if-let [r (return-rules base-fn-name)]
    (r bindings-info default-ret)
    default-ret))


(defn- compute-slot-types
  [base-fn-name bindings-info]
  (if (= :update-in base-fn-name)
    ((rule 'graphden.packages.core.collections.impls 'update-in-slot-rule)
     bindings-info)
    {}))


(defn- compute-nav-types
  [base-fn-name bindings-info]
  (if (= :update-in base-fn-name)
    ((rule 'graphden.packages.core.collections.impls 'update-in-nav-rule)
     bindings-info)
    {}))


;; -----------------------------------------------------------------------------
;; :assoc

(deftest assoc-builds-record-from-empty-map
  (testing "first key of an empty / unknown m → singleton record"
    (is (= {:name :text}
           (compute-return-type :assoc
                                {:map   {:type :any :value {}}
                                 :key   {:type :text :value "name"}
                                 :value {:type :text :value "Alice"}}
                                :any)))))


(deftest assoc-extends-known-record
  (testing "extending an existing record adds the field"
    (is (= {:name :text :age :int}
           (compute-return-type :assoc
                                {:map   {:type {:name :text} :value nil}
                                 :key   {:type :text :value "age"}
                                 :value {:type :int :value 30}}
                                :any)))))


(deftest assoc-overrides-existing-field
  (testing "writing the same key replaces the existing field type"
    (is (= {:name :text}
           (compute-return-type :assoc
                                {:map   {:type {:name :int} :value nil}
                                 :key   {:type :text :value "name"}
                                 :value {:type :text :value "Alice"}}
                                :any)))))


(deftest assoc-degrades-on-computed-key
  (testing ":key is a ref / non-literal → degrade to :jsonb"
    (is (= :jsonb
           (compute-return-type :assoc
                                {:map   {:type {} :value {}}
                                 :key   {:type :text :value nil}      ; ref
                                 :value {:type :int :value 42}}
                                :any)))))


(deftest assoc-keyword-key-also-works
  (testing "literal :keyword key normalises to its name"
    (is (= {:age :int}
           (compute-return-type :assoc
                                {:map   {:type {} :value {}}
                                 :key   {:type :keyword :value :age}
                                 :value {:type :int :value 30}}
                                :any)))))


;; -----------------------------------------------------------------------------
;; :dissoc

(deftest dissoc-removes-known-field
  (testing "removing a literal key from a known record"
    (is (= {:name :text}
           (compute-return-type :dissoc
                                {:map {:type {:name :text :age :int}}
                                 :key {:type :text :value "age"}}
                                :jsonb)))))


(deftest dissoc-degrades-on-computed-key
  (is (= :jsonb
         (compute-return-type :dissoc
                              {:map {:type {:name :text}}
                               :key {:type :text :value nil}}
                              :jsonb))))


;; -----------------------------------------------------------------------------
;; :get

(deftest get-returns-field-type-for-known-record
  (testing "looking up a present field gives its type"
    (is (= :text
           (compute-return-type :get
                                {:coll {:type {:name :text :age :int}}
                                 :key  {:type :text :value "name"}}
                                :any)))))


(deftest get-throws-on-missing-field-typo
  (testing "missing field on a known record is a TYPO — throw with available fields"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"field :missing not found"
          (compute-return-type :get
                               {:coll {:type {:name :text :age :int}}
                                :key  {:type :text :value "missing"}}
                               :any)))))


(deftest get-degrades-when-coll-not-known-record
  (testing "non-record coll: degrade to default (no info to typo-check)"
    (is (= :any
           (compute-return-type :get
                                {:coll {:type :jsonb}
                                 :key  {:type :text :value "name"}}
                                :any)))))


(deftest get-degrades-when-key-not-literal
  (testing "computed key: can't typo-check (value is unknown at sync)"
    (is (= :any
           (compute-return-type :get
                                {:coll {:type {:name :text}}
                                 :key  {:type :text :value nil}}
                                :any)))))


(deftest get-with-default-on-missing-field-returns-default-type
  (testing "missing field BUT :default supplied — intentional, not a typo"
    (is (= :text
           (compute-return-type :get
                                {:coll    {:type {:name :text}}
                                 :key     {:type :text :value "missing"}
                                 :default {:type :text :value "fallback"}}
                                :any)))))


;; -----------------------------------------------------------------------------
;; :update-in — return preserves m's shape; literal :path validated
;; against m's record structure (typo-catching, mirrors :get).

(deftest update-in-returns-m-shape
  (is (= {:headers :jsonb :status :int}
         (compute-return-type :update-in
                              {:m {:type {:headers :jsonb :status :int}}}
                              :any))))


(deftest update-in-accepts-valid-path-segment
  (testing "path segment naming a present field — no throw"
    (is (= {:headers :jsonb}
           (compute-return-type :update-in
                                {:m    {:type {:headers :jsonb}}
                                 :path {:value [{:value :headers :literal? true}]}}
                                :any)))))


(deftest update-in-throws-on-missing-path-segment
  (testing "path segment naming an absent field — typo, throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"path segment :hdrs not found"
          (compute-return-type :update-in
                               {:m    {:type {:headers :jsonb}}
                                :path {:value [{:value :hdrs :literal? true}]}}
                               :any)))))


(deftest update-in-path-stops-at-non-record-level
  (testing "a deeper segment into a :jsonb sub-level isn't validated"
    (is (= {:headers :jsonb}
           (compute-return-type :update-in
                                {:m    {:type {:headers :jsonb}}
                                 :path {:value [{:value :headers :literal? true}
                                                {:value :anything :literal? true}]}}
                                :any)))))


(deftest update-in-path-skipped-when-m-not-record
  (testing "m generic — nothing to validate the path against"
    (is (= :any
           (compute-return-type :update-in
                                {:m    {:type :any}
                                 :path {:value [{:value :whatever :literal? true}]}}
                                :any)))))


(deftest update-in-slot-types-narrows-path
  (testing "m is a known record → :path narrows to [:list :keyword]"
    (is (= {:path [:list :keyword]}
           (compute-slot-types :update-in {:m {:type {:headers :jsonb}}}))))
  (testing "m generic → no slot narrowing"
    (is (= {} (compute-slot-types :update-in {:m {:type :any}})))))


(deftest update-in-nav-types-hands-over-m-structure
  (testing "m is a known record → :path navigates that record's shape"
    (is (= {:path {:headers :jsonb :status :int}}
           (compute-nav-types
             :update-in
             {:m {:type {:headers :jsonb :status :int}}}))))
  (testing "m generic / open map → nothing to navigate"
    (is (= {} (compute-nav-types :update-in {:m {:type :any}})))
    (is (= {} (compute-nav-types :update-in {:m {:type :jsonb}})))))


;; -----------------------------------------------------------------------------
;; :first / :rest / :cons — list-elem propagation

(deftest first-on-typed-list-returns-elem-type
  (is (= :int
         (compute-return-type :first
                              {:coll {:type [:list :int]}}
                              :any))))


(deftest first-on-untyped-coll-falls-back-to-default
  (is (= :any
         (compute-return-type :first
                              {:coll {:type :jsonb}}
                              :any))))


(deftest rest-preserves-list-elem-type
  (is (= [:list :text]
         (compute-return-type :rest
                              {:coll {:type [:list :text]}}
                              :jsonb))))


(deftest cons-preserves-list-elem-type
  (is (= [:list :int]
         (compute-return-type :cons
                              {:item {:type :int}
                               :coll {:type [:list :int]}}
                              :jsonb))))


;; -----------------------------------------------------------------------------
;; :keys / :vals — record introspection

(deftest keys-on-known-record-returns-list-of-keyword
  (is (= [:list :keyword]
         (compute-return-type :keys
                              {:map {:type {:name :text :age :int}}}
                              :jsonb))))


(deftest vals-on-uniform-record-returns-precise-elem-type
  (is (= [:list :text]
         (compute-return-type :vals
                              {:map {:type {:name :text :nick :text}}}
                              :jsonb))))


(deftest vals-on-mixed-record-degrades-to-list-any
  (is (= [:list :any]
         (compute-return-type :vals
                              {:map {:type {:name :text :age :int}}}
                              :jsonb))))


(deftest keys-on-non-record-falls-back-to-default
  (is (= :jsonb
         (compute-return-type :keys
                              {:map {:type :jsonb}}
                              :jsonb))))


;; -----------------------------------------------------------------------------
;; :get-in — literal-path walk over nested records

(deftest get-in-walks-nested-record
  (is (= :text
         (compute-return-type :get-in
                              {:map  {:type {:user {:name :text :age :int}}}
                               :path {:type :sequence :value [:user :name]}}
                              :any))))


(deftest get-in-falls-back-on-missing-path-segment
  (is (= :any
         (compute-return-type :get-in
                              {:map  {:type {:user {:name :text}}}
                               :path {:type :sequence :value [:user :missing]}}
                              :any))))


(deftest get-in-falls-back-on-non-literal-path
  (is (= :any
         (compute-return-type :get-in
                              {:map  {:type {:user {:name :text}}}
                               :path {:type :sequence :value nil}}
                              :any))))


;; -----------------------------------------------------------------------------
;; :take / :drop / :reverse / :sort / :distinct — preserve list elem-type

(deftest take-preserves-list-elem-type
  (is (= [:list :int]
         (compute-return-type :take
                              {:count {:type :int} :coll {:type [:list :int]}}
                              :jsonb))))


(deftest drop-preserves-list-elem-type
  (is (= [:list :text]
         (compute-return-type :drop
                              {:count {:type :int} :coll {:type [:list :text]}}
                              :jsonb))))


(deftest reverse-preserves-list-elem-type
  (is (= [:list :int]
         (compute-return-type :reverse
                              {:coll {:type [:list :int]}}
                              :jsonb))))


(deftest sort-and-distinct-preserve-elem-type
  (is (= [:list :int]
         (compute-return-type :sort
                              {:coll {:type [:list :int]}} :jsonb)))
  (is (= [:list :int]
         (compute-return-type :distinct
                              {:coll {:type [:list :int]}} :jsonb))))


;; -----------------------------------------------------------------------------
;; :concat — list of lists → list

(deftest concat-of-list-of-list-of-T-returns-list-of-T
  (is (= [:list :int]
         (compute-return-type :concat
                              {:colls {:type [:list [:list :int]]}}
                              :jsonb))))


(deftest concat-of-shallow-list-falls-back
  (is (= :jsonb
         (compute-return-type :concat
                              {:colls {:type [:list :int]}}
                              :jsonb))))


;; -----------------------------------------------------------------------------
;; Arithmetic narrowing :numeric → :int when every operand is :int

(deftest add-on-list-of-int-narrows-to-int
  (is (= :int
         (compute-return-type :add
                              {:nums {:type [:list :int]}}
                              :numeric))))


(deftest add-on-list-of-numeric-stays-numeric
  (is (= :numeric
         (compute-return-type :add
                              {:nums {:type [:list :numeric]}}
                              :numeric))))


(deftest sub-mul-narrow-on-int-list
  (is (= :int
         (compute-return-type :sub
                              {:nums {:type [:list :int]}} :numeric)))
  (is (= :int
         (compute-return-type :mul
                              {:nums {:type [:list :int]}} :numeric))))


(deftest mod-narrows-on-int-int
  (is (= :int
         (compute-return-type :mod
                              {:dividend {:type :int} :divisor {:type :int}}
                              :numeric))))


(deftest mod-stays-numeric-on-mixed
  (is (= :numeric
         (compute-return-type :mod
                              {:dividend {:type :int} :divisor {:type :numeric}}
                              :numeric))))


(deftest neg-abs-narrow-on-int
  (is (= :int
         (compute-return-type :neg
                              {:number {:type :int}} :numeric)))
  (is (= :int
         (compute-return-type :abs
                              {:number {:type :int}} :numeric))))


;; -----------------------------------------------------------------------------
;; :into — preserves destination list elem-type

(deftest into-preserves-list-elem-type
  (is (= [:list :int]
         (compute-return-type :into
                              {:to {:type [:list :int]} :from {:type :jsonb}}
                              :jsonb))))


(deftest into-falls-back-on-jsonb-target
  (is (= :jsonb
         (compute-return-type :into
                              {:to {:type :jsonb} :from {:type :jsonb}}
                              :jsonb))))


;; -----------------------------------------------------------------------------
;; :assoc-in — walk a literal path and update the deepest field

(deftest assoc-in-updates-deep-field-on-known-record
  (is (= {:user {:name :text :age :int}}
         (compute-return-type :assoc-in
                              {:m {:type {:user {:name :text :age :int}}}
                               :path {:type :sequence :value [:user :age]}
                               :v {:type :int :value 42}}
                              :any))))


(deftest assoc-in-extends-known-record-with-new-field
  (is (= {:user {:name :text :age :int}}
         (compute-return-type :assoc-in
                              {:m {:type {:user {:name :text}}}
                               :path {:type :sequence :value [:user :age]}
                               :v {:type :int :value 42}}
                              :any))))


(deftest assoc-in-builds-record-on-empty-input
  (is (= {:user {:name :text}}
         (compute-return-type :assoc-in
                              {:m {:type :any}
                               :path {:type :sequence :value [:user :name]}
                               :v {:type :text :value "Alice"}}
                              :any))))


(deftest assoc-in-falls-back-on-non-literal-path
  (is (= :any
         (compute-return-type :assoc-in
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
           (compute-return-type :int-add {} :int)))))


;; -----------------------------------------------------------------------------
;; :dissoc / :merge edge cases — small gaps in earlier coverage.

(deftest dissoc-on-jsonb-falls-back-to-default
  (testing ":dissoc with literal key but non-record m → default-ret"
    (is (= :jsonb
           (compute-return-type :dissoc
                                {:map {:type :jsonb}
                                 :key {:type :text :value "name"}}
                                :jsonb)))))


(deftest merge-passes-through-maps-type-or-default
  (testing ":merge returns :maps's type when a list/sequence shape is known"
    (is (= [:list :any]
           (compute-return-type :merge
                                {:maps {:type [:list :any]}}
                                :jsonb))))
  (testing ":merge degrades to default when :maps has no type info"
    (is (= :jsonb
           (compute-return-type :merge
                                {}
                                :jsonb)))))
