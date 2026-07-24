(ns graphden.types.rules-test
  "Unit tests for the per-base-fn type-rules. The rules used to live as
   name-dispatched `defmethod`s in `graphden.types.rules`; they are now
   plain `defn`s next to each base-fn's `defbase` in its impls.clj.
   Those impls.clj files are resources the package loader eval's — not
   classpath namespaces — so we load the core package once and resolve
   the rule fns from the resulting namespaces."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.types.check :as check]
    [graphden.types.core :as t]))


;; Eval the core package's impls.clj resources so their namespaces
;; (and the type-rule fns inside them) exist.
(defonce ^:private _core-loaded (loader/load-packages ["core"]))


;; Registry snapshot for the drift guard below — isolated so the
;; recorded core entries can't leak into sibling test namespaces.
(use-fixtures :once
  (fn [t]
    (binding [t/*type-aliases-override* (atom {})]
      ;; Aliases first — base-fn arg declarations reference
      ;; refinement aliases (`:path-segment`, `:non-negative-int` …)
      ;; that `record-rich-types!` validates. Same two-step as
      ;; check-test's fixture, both sides thread-isolated.
      ((requiring-resolve 'graphden.system.core/register-type-aliases!)
       (:fn-defs _core-loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs _core-loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (t))))))


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
     :cond      (rule l 'cond-return-rule)
     :case      (rule l 'case-return-rule)
     :coalesce  (rule l 'coalesce-return-rule)}))


(def ^:private signature-entries
  "Declared signatures for base-fns whose hand rules were DELETED in
   favour of the checker's declared-signature fallback — the shim
   routes them through `check/signature-return` so the old per-rule
   assertions now exercise the engine against the same declarations
   the loader records (mirrored from core fns.edn)."
  {:first    {:return [:union :null 'a] :args {:coll [:list 'a]}}
   :rest     {:return [:list 'a] :args {:coll [:list 'a]}}
   :cons     {:return [:list 'a] :args {:item 'a :coll [:list 'a]}}
   :take     {:return [:list 'a] :args {:count :non-negative-int :coll [:list 'a]}}
   :drop     {:return [:list 'a] :args {:count :non-negative-int :coll [:list 'a]}}
   :reverse  {:return [:list 'a] :args {:coll [:list 'a]}}
   :sort     {:return [:list 'a] :args {:coll [:list 'a]}}
   :distinct {:return [:list 'a] :args {:coll [:list 'a]}}
   :vec      {:return [:list 'a] :args {:coll [:list 'a]}}
   :range    {:return [:list :int] :args {:start :int :end :int :step :int}}
   :repeat   {:return [:list 'a] :args {:count :non-negative-int :item 'a}}
   :const    {:return 'a :args {:value 'a}}})


(deftest signature-entries-match-the-loaded-declarations
  ;; Drift guard (audit-3): `signature-entries` hand-mirrors core
  ;; fns.edn declarations; without this check a changed declaration
  ;; keeps the shim green against a STALE map (the mirror-drift class).
  ;; Registry entries store per-arg rich types under :args and the
  ;; alias-resolved return under :return.
  (doseq [[fn-name {:keys [return args]}] signature-entries]
    (let [entry (registry/rich-type-of fn-name)]
      (is (some? entry) (str fn-name " is in the loaded registry"))
      (is (= (t/resolve-alias return) (:return entry))
          (str fn-name " return matches the declaration"))
      (doseq [[arg-name t] args]
        (is (= (t/resolve-alias t) (get-in entry [:args arg-name]))
            (str fn-name " arg " arg-name " matches"))))))


(defn- compute-return-type
  "Test shim — dispatch a return-type rule by base-fn name, mirroring
   the registry lookup the type-checker does at runtime: hand rule
   first, declared-signature fallback second, `default-ret` through
   otherwise."
  [base-fn-name bindings-info default-ret]
  (if-let [r (return-rules base-fn-name)]
    (r bindings-info default-ret)
    (if-let [entry (signature-entries base-fn-name)]
      (check/signature-return entry bindings-info default-ret)
      default-ret)))


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


(deftest assoc-keeps-inherited-record-when-map-opaque
  (testing ":map isn't a visible record but default-ret is — assoc into the inherited record"
    ;; A descendant of a record-typed assoc-chain (e.g. :ring-response,
    ;; which declares :ring-response-shape) re-fires this rule with
    ;; :map surfacing as :any. The inherited record must be kept and
    ;; extended, NOT collapsed to a fresh one-field record — a
    ;; return-type rule must never widen the type it inherited.
    (is (= {:status :int :headers :jsonb :body :text}
           (compute-return-type :assoc
                                {:map   {:type :any :value nil}
                                 :key   {:type :text :value "body"}
                                 :value {:type :text :value "x"}}
                                {:status :int :headers :jsonb :body :text})))
    (testing "a new field is assoc'd into the inherited record"
      (is (= {:status :int :extra :bool}
             (compute-return-type :assoc
                                  {:map   {:type :jsonb :value nil}
                                   :key   {:type :text :value "extra"}
                                   :value {:type :bool :value true}}
                                  {:status :int}))))
    (testing ":map's own record still wins over the inherited one"
      (is (= {:a :int :body :text}
             (compute-return-type :assoc
                                  {:map   {:type {:a :int} :value nil}
                                   :key   {:type :text :value "body"}
                                   :value {:type :text :value "x"}}
                                  {:status :int :body :int}))))))


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


(deftest get-missing-field-with-free-default-still-throws
  (testing "a parent-fallback :default entry (no :value / :ref) is NOT a
            real binding — the missing-field typo throw must still fire"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"field :missing not found"
          (compute-return-type :get
                               {:coll    {:type {:name :text}}
                                :key     {:type :text :value "missing"}
                                :default {:type :text :value nil}}
                               :any)))))


(deftest get-on-map-type-is-nullable
  (testing ":get of a [:map K V] — key may be absent → [:union :null V]"
    (is (= (t/make-union [:null :int])
           (compute-return-type :get
                                {:coll {:type [:map :keyword :int]}
                                 :key  {:type :keyword :value :k}}
                                :any)))))


(deftest get-on-map-type-with-default-unions-default
  (testing ":get of a [:map K V] with a bound :default → [:union V default]"
    (is (= (t/make-union [:int :text])
           (compute-return-type :get
                                {:coll    {:type [:map :keyword :int]}
                                 :key     {:type :keyword :value :k}
                                 :default {:type :text :value "x"}}
                                :any)))))


(deftest get-on-union-of-null-and-record-narrows-through-branches
  (testing "[:union :null record] with a key in record + :default nil →
           [:union :null field-type] (was :any before union handling)"
    ;; This is the production-shape case: `:resolve-branch-ref` etc.
    ;; return `[:union :null record]`; consumers `:get :default nil` a
    ;; field on the row. Pre-extension the union opaqued to :any.
    (is (= (t/make-union [:null :text])
           (compute-return-type :get
                                {:coll    {:type [:union :null {:name :text :age :int}]}
                                 :key     {:type :text :value "name"}
                                 :default {:type :null :value nil}}
                                :any)))))


(deftest get-on-union-of-two-records-unions-field-types
  (testing "[:union record-A record-B] with key present in BOTH branches →
           [:union A[key] B[key]]"
    (is (= (t/make-union [:text :int])
           (compute-return-type :get
                                {:coll {:type [:union {:k :text} {:k :int}]}
                                 :key  {:type :text :value "k"}}
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
                                 :path {:value [{:value :headers}]}}
                                :any)))))


(deftest update-in-throws-on-missing-path-segment
  (testing "path segment naming an absent field — typo, throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"path segment :hdrs not found"
          (compute-return-type :update-in
                               {:m    {:type {:headers :jsonb}}
                                :path {:value [{:value :hdrs}]}}
                               :any)))))


(deftest update-in-path-stops-at-non-record-level
  (testing "a deeper segment into a :jsonb sub-level isn't validated"
    (is (= {:headers :jsonb}
           (compute-return-type :update-in
                                {:m    {:type {:headers :jsonb}}
                                 :path {:value [{:value :headers}
                                                {:value :anything}]}}
                                :any)))))


(deftest update-in-path-skipped-when-m-not-record
  (testing "m generic — nothing to validate the path against"
    (is (= :any
           (compute-return-type :update-in
                                {:m    {:type :any}
                                 :path {:value [{:value :whatever}]}}
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

(deftest first-on-typed-list-returns-nullable-elem-type
  ;; `(first [])` is nil, so `:first` over `[:list T]` is `[:union :null T]`.
  (is (= (t/make-union [:null :int])
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


(deftest get-in-through-map-intermediate-is-nullable
  (testing "any `[:map K V]` step in the path → key may be absent → nullable result"
    ;; record-of-map-of-int: walk `:cfg` (record present) then a map
    ;; key (V = :int; may be absent) → [:union :null :int].
    (is (= (t/make-union [:null :int])
           (compute-return-type :get-in
                                {:map  {:type {:cfg [:map :keyword :int]}}
                                 :path {:type :sequence
                                        :value [:cfg :limit]}}
                                :any)))))


(deftest get-in-top-level-map-is-nullable
  (testing "top-level [:map K V] → single-segment get-in is nullable too"
    (is (= (t/make-union [:null :text])
           (compute-return-type :get-in
                                {:map  {:type [:map :keyword :text]}
                                 :path {:type :sequence :value [:k]}}
                                :any)))))


(deftest get-in-through-union-of-null-and-record-narrows
  (testing "[:union :null record] entry → [:union :null field-type]"
    ;; Mirrors :get's union narrowing — same production-shape case
    ;; for the deep-walk variant. Without this, the entire union
    ;; opaqued out to default-ret (:any) because neither
    ;; record-type? nor map-type? fired on the union itself.
    (is (= (t/make-union [:null :text])
           (compute-return-type :get-in
                                {:map  {:type [:union :null {:user {:name :text}}]}
                                 :path {:type :sequence :value [:user :name]}}
                                :any)))))


(deftest get-in-through-mid-walk-union-fans-out
  (testing "a union encountered mid-walk recurses each branch with the remaining path"
    ;; Outer record's :v is a union of two records; both have a :k
    ;; field, but with different types. The walk continues through
    ;; the union and unions the leaf types.
    (is (= (t/make-union [:text :int])
           (compute-return-type :get-in
                                {:map  {:type {:v [:union {:k :text} {:k :int}]}}
                                 :path {:type :sequence :value [:v :k]}}
                                :any)))))


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


(deftest assoc-in-preserves-top-level-map-shape
  (testing "top-level [:map K V] stays [:map K V] (assoc-in fills nil-safe)"
    (is (= [:map :keyword :int]
           (compute-return-type :assoc-in
                                {:m    {:type [:map :keyword :int]}
                                 :path {:type :sequence :value [:k]}
                                 :v    {:type :int :value 42}}
                                :any)))))


(deftest assoc-in-preserves-map-shape-via-record-field
  (testing "record with a [:map K V] field — assoc-in into the map keeps
            the record AND the field's [:map K V] shape"
    (is (= {:cfg [:map :keyword :int]}
           (compute-return-type :assoc-in
                                {:m    {:type {:cfg [:map :keyword :int]}}
                                 :path {:type :sequence :value [:cfg :limit]}
                                 :v    {:type :int :value 10}}
                                :any)))))


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


(deftest merge-degrades-to-default-on-heterogeneous-input
  (testing ":merge degrades to default when :maps has no per-item type info"
    ;; `[:list T]` describes the SHAPE OF THE INPUT (a list of values),
    ;; not the shape of merge's RESULT. The rule must NOT pass the list
    ;; type through to the return — `:merge` always returns a map.
    (is (= :jsonb
           (compute-return-type :merge
                                {:maps {:type [:list :any]}}
                                :jsonb))))
  (testing ":merge degrades to default when no maps info at all"
    (is (= :jsonb
           (compute-return-type :merge
                                {}
                                :jsonb))))
  (testing "all-record per-item types produce the merged record shape"
    (is (= {:a :int :b :text}
           (compute-return-type :merge
                                {:maps {:type [:list :any]
                                        :elem-types [{:a :int} {:b :text}]}}
                                :jsonb)))))


;; -----------------------------------------------------------------------------
;; :range / :repeat

(deftest range-always-builds-an-int-list
  ;; `:range` needs no rule at all — its declared return is the
  ;; concrete `[:list :int]`, which IS the checker's static return
  ;; (the shim's `default-ret` models that); the engine passes it
  ;; through untouched.
  (is (= [:list :int]
         (compute-return-type :range {} [:list :int]))))


(deftest repeat-builds-list-of-item-type
  (testing "result is [:list <item-type>]"
    (is (= [:list :int]
           (compute-return-type :repeat {:item {:type :int}} :jsonb)))
    (is (= [:list :text]
           (compute-return-type :repeat {:item {:type :text}} :jsonb))))
  (testing "untyped item — nothing binds, the static return stands
            (production static is the subst-resolved declaration)"
    (is (= [:list :any]
           (compute-return-type :repeat {} [:list :any])))))


;; -----------------------------------------------------------------------------
;; :cond — return = union of the result-position (odd-index) branch
;; types, plus :null when no literal-`true` test makes the cond
;; exhaustive (it can otherwise fall through every clause → nil).

(deftest cond-unions-result-branches-with-null-when-not-exhaustive
  (testing "no literal-true test → cond can fall through → :null joins the union"
    (is (= (t/make-union [:int :text :null])
           (compute-return-type
             :cond
             {:clauses {:elem-types [:bool :text :bool :int]
                        :value [:pred-a {:value "x"} :pred-b {:value 1}]}}
             :any)))))


(deftest cond-exhaustive-true-test-drops-null
  (testing "a literal `true` else-test makes the cond exhaustive — no :null"
    (is (= (t/make-union [:int :text])
           (compute-return-type
             :cond
             {:clauses {:elem-types [:bool :text :bool :int]
                        :value [:pred-a {:value "x"} {:value true} {:value 1}]}}
             :any)))))


(deftest cond-collapses-homogeneous-exhaustive-results
  (testing "all results same type + exhaustive → that type, no union"
    (is (= :text
           (compute-return-type
             :cond
             {:clauses {:elem-types [:bool :text :bool :text]
                        :value [:pred-a {:value "x"} {:value true} {:value "y"}]}}
             :any)))))


(deftest cond-falls-back-without-elem-types
  (testing ":clauses an opaque ref (no per-item types) → default-ret"
    (is (= :any
           (compute-return-type :cond {:clauses {:type :jsonb}} :any)))))


;; -----------------------------------------------------------------------------
;; :case — return = union of clause-value types with :default's.

(deftest case-unions-literal-clause-values
  (testing "literal clauses map (a record) → its vals unioned with :default"
    (is (= (t/make-union [:int :text :bool])
           (compute-return-type
             :case
             {:clauses {:type {:a :int :b :text}}
              :default {:type :bool}}
             :any)))))


(deftest case-unions-map-typed-clauses
  (testing "[:map K V] clauses → V unioned with :default"
    (is (= (t/make-union [:int :text])
           (compute-return-type
             :case
             {:clauses {:type [:map :keyword :int]}
              :default {:type :text}}
             :any)))))


(deftest case-falls-back-on-opaque-clauses
  (testing ":clauses neither a record nor a [:map …] → default-ret"
    (is (= :any
           (compute-return-type
             :case
             {:clauses {:type :jsonb} :default {:type :bool}}
             :any)))))


;; -----------------------------------------------------------------------------
;; :cond runtime — `cond-fn` over a flat `[test result …]` clause seq.
;; Items arrive as delays (`:lazy-seq-args`); these drive the impl
;; directly with hand-built delay seqs. Happy-path multi-branch dispatch
;; and the lazy short-circuit are covered by `compile-packages-test`'s
;; `cond-case-execution-test` / `lazy-short-circuit-test`.

(def ^:private cond-fn-impl
  (rule 'graphden.packages.core.logic.impls 'cond-fn))


(deftest cond-fn-picks-first-truthy-result
  (testing "a falsy clause is stepped past; the next truthy result wins"
    (is (= "yes"
           (cond-fn-impl {:clauses (list (delay false) (delay "no")
                                         (delay true)  (delay "yes"))}
                         nil)))))


(deftest cond-fn-no-match-returns-nil
  (testing "every test falsy → nil"
    (is (nil? (cond-fn-impl {:clauses (list (delay false) (delay "a")
                                            (delay false) (delay "b"))}
                            nil))))
  (testing "empty clause seq → nil"
    (is (nil? (cond-fn-impl {:clauses (list)} nil))))
  (testing "odd-length clauses (dangling test, no result) → nil"
    (is (nil? (cond-fn-impl {:clauses (list (delay false) (delay "a")
                                            (delay false))}
                            nil)))))


;; -----------------------------------------------------------------------------
;; :coalesce — the null-eliminator: strips :null from :value's type,
;; unions the rest with :default's.

(deftest coalesce-strips-null-from-value
  (testing "[:union :null T] value + T default → T (null eliminated)"
    (is (= :text
           (compute-return-type
             :coalesce
             {:value   {:type (t/make-union [:null :text])}
              :default {:type :text}}
             :any)))))


(deftest coalesce-unions-value-and-default
  (testing "non-null value + differently-typed default → union of both"
    (is (= (t/make-union [:int :text])
           (compute-return-type
             :coalesce
             {:value {:type :int} :default {:type :text}}
             :any)))))


(deftest coalesce-statically-nil-value-yields-default
  (testing ":value typed exactly :null → result is :default's type"
    (is (= :text
           (compute-return-type
             :coalesce
             {:value {:type :null} :default {:type :text}}
             :any)))))
