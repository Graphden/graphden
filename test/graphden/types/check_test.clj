(ns ^:serial graphden.types.check-test
  "FLAKY UNDER PARALLEL: this ns shares `core-base-fns` (a JVM-wide
   `defonce` from `loader/load-packages`) with the type-check sweep
   and its `:each` fixture seeds `:int-add` / `:map` / `:filter`
   synthetic shapes through `record-rich-types!`. The seeding is
   isolated to this NS's thread-local override, but under heavy
   parallel scheduling 3 tests (`check-all-defs-stops-at-first-mismatch`,
   `refinement-base-mismatch-throws`, `binding-type-widening-via-union-rejected`)
   intermittently see `:int-add`'s rich-type resolve to a permissive
   default instead of the seeded `{:a :int :b :int}` shape — the
   expected `:type-check failed` doesn't fire. Cause not fully
   characterised; pin to serial until the underlying race is found."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.logging :as log]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.types.check :as check]
    [graphden.types.core :as types-core]))


;; The check namespace reads from the rich-types registry. Each test
;; runs against the real core base-fn registry — so the per-base-fn
;; type-rules (`:return-type-rule` etc., declared in each base-fn's
;; impls.clj) resolve — plus a few synthetic signatures the type-var /
;; HOF tests need. No DB / sync; `load-packages` only eval's the
;; package resources.
(defonce ^:private core-base-fns
  (:base-fn-defs (loader/load-packages ["core"])))


;; `with-isolated-rich-types` keeps the synthetic `:map` / `:filter`
;; / `:int-add` shapes this ns writes via `record-rich-types!` from
;; leaking into sibling integration tests (e.g. execute-http-test
;; would crash with `AFunction$1 cannot be cast to Associative`
;; deep in compile-eager when the simplified `:map` shape replaced
;; the production-rich one mid-suite).
(use-fixtures :once exec/with-isolated-rich-types)


(use-fixtures :each
  exec/with-clean-registry
  (fn [test-fn]
    ;; Built-ins normally registered by the package loader at boot —
    ;; tests here run outside that path, so seed manually.
    (types-core/clear-aliases!)
    (types-core/register-type-alias! :positive-int     [:refine :int     [:> 0]])
    (types-core/register-type-alias! :non-negative-int [:refine :int     [:>= 0]])
    (types-core/register-type-alias! :non-empty-text   [:refine :text    [:not= ""]])
    (test-fn))
  (fn [t]
    ;; Real core base-fns first — their per-base-fn type-rules ride in
    ;; through `record-rich-types!` so the type-checker resolves them.
    (doseq [[fn-name fn-def] core-base-fns]
      (registry/record-rich-types! fn-name fn-def))
    ;; Synthetic signatures layered on top (overriding on collision)
    ;; for the type-var / HOF tests.
    (registry/record-rich-types! :int-add
                                 {:args {:a :int :b :int}
                                  :return-type :int})
    (registry/record-rich-types! :map
                                 {:args {:func {:type [:fn {:item 'a} 'b]}
                                         :coll {:type [:list 'a]}}
                                  :return-type [:list 'b]})
    (registry/record-rich-types! :filter
                                 {:args {:pred {:type [:fn {:item 'a} :bool]}
                                         :coll {:type [:list 'a]}}
                                  :return-type [:list 'a]})
    (registry/record-rich-types! :get-text
                                 {:args {} :return-type :text})
    (registry/record-rich-types! :get-int
                                 {:args {} :return-type :int})
    (t)))


(deftest classify-literal-test
  (is (= :int     (check/classify-literal 42)))
  (is (= :float   (check/classify-literal 3.14)))
  (is (= :text    (check/classify-literal "hi")))
  (is (= :bool    (check/classify-literal true)))
  (is (= :null    (check/classify-literal nil)))
  (is (= :keyword (check/classify-literal :kw)))
  (testing "vectors classify structurally as [:list T]"
    (is (= [:list :int] (check/classify-literal [1 2 3])))
    (is (= [:list :any] (check/classify-literal [1 "two"])) "mixed elems → :any")
    (is (= [:list :any] (check/classify-literal []))        "empty → :any elem"))
  (testing "keyword-keyed maps classify structurally as record-types"
    (is (= {:a :int}          (check/classify-literal {:a 1})))
    (is (= {:a :int :b :text} (check/classify-literal {:a 1 :b "x"})))
    (is (= {:a {:b :int}}     (check/classify-literal {:a {:b 1}})) "recurses"))
  (testing "string-keyed homogeneous-value maps classify as [:map :text V]"
    (is (= [:map :text :int]  (check/classify-literal {"a" 1})))
    (is (= [:map :text :text] (check/classify-literal {"Content-Type" "text/html"}))
        "headers-shaped literal classifies against [:map :text :text]"))
  (testing "string-keyed heterogeneous-value maps fall back to :jsonb"
    (is (= :jsonb (check/classify-literal {"a" 1 "b" "x"}))
        "values disagree → :jsonb, the conservative catch-all"))
  (testing "mixed-keyed map stays :jsonb"
    (is (= :jsonb (check/classify-literal {:a 1 "b" 2}))))
  (testing "empty map classifies as :empty-map — vacuous-truth sentinel"
    ;; `(empty? v) → :empty-map` instead of `:jsonb` so a `{}` literal
    ;; subtypes any structural map shape (`[:map K V]`, record-type,
    ;; `:jsonb`) without bouncing off the `:jsonb ⊄ [:map …]` rule.
    ;; Drove `(74 → 63)` failures on the topo-sorted sweep. See
    ;; `docs/TYPE_CHECK_BACKLOG.md` § "In-session pass (2026-06-07)".
    (is (= :empty-map (check/classify-literal {})))))


(deftest accepts-matching-literals
  (testing "int-add with two int literals — passes"
    (is (some? (check/check-fn-def! {:name :add-five
                                     :parent :int-add
                                     :args {:a 5 :b 10}})))))


(deftest rejects-text-where-int-expected
  (testing "literal :text into :int slot"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad
                                :parent :int-add
                                :args {:a "hello" :b 5}})))))


(deftest effects-declared-on-composed-fn-is-noop
  (testing "composed fn-def's declared :effects is silently dropped — the rich-type records the COMPUTED set. Sync-time WARN is logged but not asserted here (the log channel runs through SLF4J and is hard to intercept). Authors who want a binding contract should use :expects-effects."
    (registry/record-rich-types! :pure-base
                                 {:args {:x :int} :return-type :int})
    (check/check-fn-def! {:name :under-claimed
                          :parent :pure-base
                          :args {:x 1}
                          :effects #{:db}})  ; lies — pure-base is pure
    (is (= #{} (:effects (registry/rich-type-of :under-claimed)))
        "rich-type records the COMPUTED #{} pure, not the declared #{:db}")))


(deftest rejects-branch-local-widening
  (testing "descendant cannot set `:branch-local? false` when ancestor is sticky-local — mirrors `:required` monotonicity"
    ;; Seed: parent base-fn is effective-branch-local true (mirrors
    ;; the `:http-server` / `:secret-leaf` / `:schedule` / `:env`
    ;; seeds in fns.edn). The type-checker reads `:branch-local?`
    ;; off rich-types-registry — `record-rich-types!` propagates
    ;; the flag from parent to child at registration time, but the
    ;; widening guard fires BEFORE the registry entry for the new
    ;; def is written, so the parent's flag is what matters.
    (registry/record-rich-types! :sticky-parent
                                 {:args {} :return-type :int
                                  :branch-local? true})
    (try
      (check/check-fn-def! {:name :sticky-child
                            :parent :sticky-parent
                            :branch-local? false})
      (is false "should have thrown — :branch-local? false widens true ancestor")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :types/branch-local-widening-forbidden (:type d)))
          (is (= :sticky-child (:fn-name d)))
          (is (= :sticky-parent (:parent-name d)))))))
  (testing "leaving `:branch-local?` absent (inherit) is allowed under a sticky ancestor"
    (registry/record-rich-types! :sticky-parent-2
                                 {:args {} :return-type :int
                                  :branch-local? true})
    (is (some? (check/check-fn-def!
                 {:name :inheriting-child
                  :parent :sticky-parent-2}))
        "no :branch-local? key in the descendant → effective true via inheritance"))
  (testing "explicit `:branch-local? true` on a sticky descendant is fine (redundant but not widening)"
    (registry/record-rich-types! :sticky-parent-3
                                 {:args {} :return-type :int
                                  :branch-local? true})
    (is (some? (check/check-fn-def!
                 {:name :double-local-child
                  :parent :sticky-parent-3
                  :branch-local? true}))))
  (testing "`:branch-local? false` is fine when no ancestor is sticky (no widening to forbid)"
    (registry/record-rich-types! :plain-parent
                                 {:args {} :return-type :int})
    (is (some? (check/check-fn-def!
                 {:name :explicit-non-local-child
                  :parent :plain-parent
                  :branch-local? false}))))
  (testing "MI: any single sticky parent is enough to trigger widening rejection"
    (registry/record-rich-types! :mi-plain
                                 {:args {} :return-type :int})
    (registry/record-rich-types! :mi-sticky
                                 {:args {} :return-type :int
                                  :branch-local? true})
    (try
      (check/check-fn-def! {:name :mi-widener
                            :parents [:mi-plain :mi-sticky]
                            :branch-local? false})
      (is false "should have thrown — `:mi-sticky` parent forces effective true")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :types/branch-local-widening-forbidden (:type d)))
          (is (= :mi-sticky (:parent-name d))
              "widening guard names the FIRST sticky ancestor it finds"))))))


(deftest rejects-type-override-widening
  (testing "`{:ref :_x :type T}` where T is NOT a subtype of `:_x`'s declared return — the override is supposed to be a narrowing claim (e.g. asserting non-nil in a guarded path), not a widening / incompatible lie"
    (registry/record-rich-types! :text-ref
                                 {:args {} :return-type :text})
    (try
      (check/check-fn-def! {:name :lie
                            :parent :int-add
                            :args {:a {:ref :text-ref :type :int}
                                   :b 5}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/type-override-widens (:type d)))
          (is (= :a (:arg-name d)))))))
  (testing "subtype narrowing (nullable-text → :text override) passes"
    (registry/record-rich-types! :nullable-text-ref
                                 {:args {} :return-type [:union :null :text]})
    (registry/record-rich-types! :takes-text
                                 {:args {:s :text} :return-type :text})
    (is (some? (check/check-fn-def!
                 {:name :ok-narrowing
                  :parent :takes-text
                  :args {:s {:ref :nullable-text-ref :type :text}}}))))
  (testing "value-binding `{:value V :type T}` where (classify V) is NOT a subtype of T — also rejected (text literal claimed as :int)"
    (try
      (check/check-fn-def! {:name :literal-lie
                            :parent :int-add
                            :args {:a {:value "hello" :type :int}
                                   :b 5}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/type-override-widens (:type d)))
          (is (= :literal-value (:actual-source d))))))))


(deftest rejects-ref-on-sequence-slot-with-elem-type-mismatch
  (testing "sequence-slot bound to a ref whose return is [:list T'] with T'≠T is rejected (previously deferred under `deferred-binding?`'s loose 'sequence slot expects vector' arm, a silent footgun for typevar conflicts like :filter :pred :int-pred :coll :text-list-fn)"
    (registry/record-rich-types! :_text-list-source
                                 {:args {} :return-type [:list :text]})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :mismatched-coll
                                :parent :filter
                                :args {:pred :int-add
                                       :coll :_text-list-source}})))))


(deftest rejects-mi-slot-value-conflict
  (testing "two parents binding the SAME slot to different values — error names the conflict so last-wins silent shadow doesn't bite"
    (registry/record-rich-types-raw! :a-pins-x-to-foo
                                     {:return :text
                                      :args {}
                                      :resolved-bindings {:x {:type :text :value "foo" :value-present true}}})
    (registry/record-rich-types-raw! :b-pins-x-to-bar
                                     {:return :text
                                      :args {}
                                      :resolved-bindings {:x {:type :text :value "bar" :value-present true}}})
    (try
      (check/check-fn-def! {:name :mi-value-conflict
                            :parents [:a-pins-x-to-foo :b-pins-x-to-bar]
                            :args {}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/mi-slot-value-conflict (:type d)))
          (is (= :x (:slot-name d))))))))


(deftest pb-decl-not-mi-value-conflict
  (testing "PB' own-slot decl (`{:type T}` no value) does NOT trigger MI value-conflict against a sibling's real binding — distinguished from `{:value nil}` via `:value-present` flag"
    ;; Parent A: PB' own-slot decl — surfaces as `{:type :text :value nil}` (no `:value-present`).
    (registry/record-rich-types-raw! :a-pb-decl-x
                                     {:return :text
                                      :args {}
                                      :resolved-bindings {:x {:type :text :value nil}}})
    ;; Parent B: real ref-binding on the same slot.
    (registry/record-rich-types-raw! :b-binds-x-by-ref
                                     {:return :text
                                      :args {}
                                      :resolved-bindings {:x {:type :text :value nil :ref :some-fn}}})
    ;; Child inherits both — PB' decl should DEFER to the real binding,
    ;; no conflict. Without the asymmetry fix, the PB' `{:value nil}`
    ;; entry would falsely conflict with B's ref pin.
    (is (some? (check/check-fn-def! {:name :child-mixes-pb-and-ref
                                     :parents [:a-pb-decl-x :b-binds-x-by-ref]
                                     :args {}}))
        "PB' decl should not conflict with sibling's real ref-binding")))


(deftest rejects-mi-explicit-nil-vs-real-binding
  (testing "Author writes `{:value nil}` (literal nil binding, `:value-present true`) on one parent and `{:value :foo}` on another — that IS a real conflict, must trip"
    (registry/record-rich-types-raw! :a-binds-x-to-nil
                                     {:return :text
                                      :args {}
                                      :resolved-bindings {:x {:type :text :value nil :value-present true}}})
    (registry/record-rich-types-raw! :b-binds-x-to-foo
                                     {:return :text
                                      :args {}
                                      :resolved-bindings {:x {:type :text :value "foo" :value-present true}}})
    (try
      (check/check-fn-def! {:name :mi-nil-vs-foo
                            :parents [:a-binds-x-to-nil :b-binds-x-to-foo]
                            :args {}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/mi-slot-value-conflict (:type d)))
          (is (= :x (:slot-name d))))))))


(deftest rejects-mi-slot-type-conflict
  (testing "two parents with same slot name but incompatible types — error names the conflict at sync time"
    (registry/record-rich-types! :parent-int-slot
                                 {:args {:x :int} :return-type :int})
    (registry/record-rich-types! :parent-text-slot
                                 {:args {:x :text} :return-type :int})
    (try
      (check/check-fn-def! {:name :mi-bad
                            :parents [:parent-int-slot :parent-text-slot]
                            :args {}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/mi-slot-type-conflict (:type d)))
          (is (= :x (:slot-name d))))))))


(deftest rejects-literal-bound-to-fn-slot
  (testing "binding a literal text to a fn-typed slot — the value can't be invoked, error catches the bug at sync time"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":types/literal-bound-to-fn-slot|literal value, not a callable"
          (check/check-fn-def! {:name :bad
                                :parent :filter
                                :args {:pred {:value "hello"}
                                       :coll {:value [1 2 3]}}})))
    (try
      (check/check-fn-def! {:name :bad
                            :parent :map
                            :args {:func {:value 42}
                                   :coll {:value [1 2 3]}}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :types/literal-bound-to-fn-slot (:type d)))
          (is (= :int (:actual d))))))))


(deftest rejects-unknown-effect-category
  (testing "expects-effects with typo (`:do` for `:db`) is rejected at sync time"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":bindings/unknown-effect-category|unknown effect category"
          (check/check-fn-def! {:name :bad-effects
                                :parent :int-add
                                :args {:a 5 :b 10}
                                :expects-effects #{:do}})))
    (try
      (check/check-fn-def! {:name :bad-effects
                            :parent :int-add
                            :args {:a 5 :b 10}
                            :effects #{:netowrk}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/unknown-effect-category (:type d)))
          (is (= #{:netowrk} (:unknown-categories d))))))))


(deftest rejects-typo-slot-name
  (testing "binding key not in parent's slot set is a typo — error suggests nearest match"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)did you mean"
          (check/check-fn-def! {:name :typo
                                :parent :int-add
                                :args {:c 5 :b 10}})))
    (try
      (check/check-fn-def! {:name :typo
                            :parent :int-add
                            :args {:c 5 :b 10}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :bindings/unknown-slot (:type d)))
          (is (= :c (:arg-name d)))
          (is (#{:a :b} (:suggestion d))
              (str "suggestion should be :a or :b, got " (pr-str (:suggestion d)))))))))


(deftest error-message-contains-fn-def-context
  (testing "error message names the fn-def, the binding, and gives a hint"
    (try
      (check/check-fn-def! {:name :bad-add
                            :parent :int-add
                            :args {:a "hello"}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [msg (Throwable/.getMessage e)
              data (ex-data e)]
          (is (re-find #":bad-add" msg) "names the fn-def being checked")
          (is (re-find #":a" msg) "names the failing arg")
          (is (re-find #"\"hello\"" msg) "shows the literal binding")
          (is (re-find #":int-add" msg) "names the parent")
          (is (re-find #":int" msg) "shows the expected type")
          (is (re-find #":text" msg) "shows the actual type")
          (is (= "hello" (:binding data)) "binding preserved in ex-data")
          (is (= :int (:expected data)))
          (is (= :text (:actual data))))))))


(deftest accepts-ref-with-matching-return-type
  (testing "fn-ref returning :int → fed into :int slot"
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :int-add
                                     :args {:a :get-int :b 7}})))))


(deftest rejects-ref-with-wrong-return-type
  (testing "fn-ref returning :text → fed into :int slot"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad
                                :parent :int-add
                                :args {:a :get-text :b 7}})))))


(deftest filter-rejects-int-returning-pred
  ;; #14b lit up structural fn-type unification for ref bindings.
  ;; :filter expects [:fn {:item a} :bool]; :int-add's structural type
  ;; is [:fn {:a :int :b :int} :int]. Cardinality mismatch (1 vs 2)
  ;; OR return-type mismatch (:bool vs :int) — either way, fail.
  (testing "filter expects :bool-returning callable; int-returning ref-fn rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad
                                :parent :filter
                                :args {:pred :int-add}})))))


(deftest map-accepts-shape-matching-pred
  (testing ":map :func :str-upper-stub — matching shape, passes"
    ;; Single-arg fn returning :text. :map expects [:fn {:item a} b];
    ;; unify binds 'a=:text 'b=:text.
    (registry/record-rich-types! :str-upper-stub
                                 {:args {:string :text}
                                  :return-type :text})
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :map
                                     :args {:func :str-upper-stub}})))))


(deftest hof-effect-constraint-rejects-effectful-callback
  (testing ":filter requires a PURE predicate (`#{}` constraint) — :env-reading-pred is rejected"
    ;; Override the seeded :filter to use the new 4-element fn-type
    ;; with an effect constraint, mirroring core/hof/fns.edn.
    (registry/record-rich-types! :filter-pure
                                 {:args {:pred {:type [:fn {:item 'a} :bool #{}]}
                                         :coll {:type [:list 'a]}}
                                  :return-type [:list 'a]})
    ;; Seed a predicate that DOES read env — same shape as a real
    ;; ring-handler-side guard but with :env effect tagged.
    (registry/record-rich-types-raw!
      :env-flag-pred
      {:args {:item :any} :return :bool :effects #{:env}})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad
                                :parent :filter-pure
                                :args {:pred :env-flag-pred}}))
        ":env-tainted predicate violates :pred's #{} (pure) constraint")))


(deftest hof-effect-constraint-accepts-pure-callback
  (testing ":filter accepts pure predicates (effects #{} ⊆ #{})"
    (registry/record-rich-types! :filter-pure
                                 {:args {:pred {:type [:fn {:item 'a} :bool #{}]}
                                         :coll {:type [:list 'a]}}
                                  :return-type [:list 'a]})
    (registry/record-rich-types-raw!
      :pure-bool-pred
      {:args {:item :any} :return :bool :effects #{}})
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :filter-pure
                                     :args {:pred :pure-bool-pred}}))
        "pure predicate satisfies the constraint")))


(deftest hof-effect-constraint-accepts-unannotated-callback
  (testing "callback without :effects in the registry is PURE — accepted by a pure slot"
    ;; A rich-types entry that lacks an `:effects` key reads as nil
    ;; on the actual side. graphden computes effects totally —
    ;; `compute-effects` runs on every fn-def and treats an absent
    ;; `:effects` as `#{}`, and `record-result!` only stores the key
    ;; when non-empty — so a missing effect set IS `#{}`
    ;; (computed-pure), not \"unknown\". `effects-compatible?` reads
    ;; nil sub-effects as pure, consistent with `compute-effects`;
    ;; the opposite would make a `#{}` slot unsatisfiable by any
    ;; ordinary pure fn (`:some?`, `:add`, …).
    (registry/record-rich-types! :filter-pure
                                 {:args {:pred {:type [:fn {:item 'a} :bool #{}]}
                                         :coll {:type [:list 'a]}}
                                  :return-type [:list 'a]})
    (registry/record-rich-types-raw!
      :unannotated-pred
      {:args {:item :any} :return :bool})
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :filter-pure
                                     :args {:pred :unannotated-pred}}))
        "absent :effects is computed-pure, satisfies the #{} constraint")))


;; -----------------------------------------------------------------------------
;; `has-type-var?` — gates whether check-binding! falls back from
;; subtype to unify. Each compound-type arm covers a real bug class:
;; without the arm, a ref-binding whose unification would have bound a
;; type-var gets rejected by the strict-subtype check that doesn't
;; reason about type-vars at all. Test every compound shape so a
;; future refactor can't silently drop an arm.
;; -----------------------------------------------------------------------------

(deftest has-type-var-covers-every-compound-shape
  (let [htv? @#'check/has-type-var?]
    (testing "leaf cases"
      (is (htv? 'a))
      (is (not (htv? :int)))
      (is (not (htv? :any)))
      (is (not (htv? nil))))
    (testing ":map — type-var in key OR value"
      (is (htv? [:map 'a :int]))
      (is (htv? [:map :keyword 'b]))
      (is (not (htv? [:map :keyword :int]))))
    (testing ":tuple — type-var at any position"
      (is (htv? [:tuple 'a :int]))
      (is (htv? [:tuple :int 'b :text]))
      (is (not (htv? [:tuple :int :text]))))
    (testing ":refine — type-var in the base"
      (is (htv? [:refine 'a [:> 0]]))
      (is (not (htv? [:refine :int [:> 0]]))))
    (testing ":union — type-var in any member"
      (is (htv? [:union :null 'a]))
      (is (htv? [:union 'a :int]))
      (is (not (htv? [:union :null :int]))))
    (testing ":list / :fn / record (regression baseline)"
      (is (htv? [:list 'a]))
      (is (htv? [:fn {:item 'a} 'b]))
      (is (htv? {:k 'a}))
      (is (not (htv? [:list :int])))
      (is (not (htv? [:fn {:item :int} :bool]))))))


(deftest unknown-parent-skips-check
  (testing "non-base-fn parent or unknown parent → no-op"
    ;; :no-such-parent isn't seeded; check returns nil, no throw.
    (is (nil? (check/check-fn-def! {:name :ok
                                    :parent :no-such-parent
                                    :args {:x 5}})))))


(deftest unknown-arg-name-rejected
  (testing "binding to a non-existent slot of parent throws — previously this was silently dropped (the binding had no runtime effect), which made typos like `:assoc :m :_x` (correct slot is `:map`) misbehave invisibly. The new check enforces every `:args` key is either a parent slot, a closure-capture seed, or a type-row field."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":bindings/unknown-slot|neither a slot"
          (check/check-fn-def! {:name :bad
                                :parent :int-add
                                :args {:not-a-real-arg 99}})))))


(deftest rename-binding-skipped
  (testing "{:as :new-name} is a rename, not a value flow — type-check skips"
    ;; Even when the parent slot is :int, the rename can't be checked
    ;; without seeing the caller's binding. Skipped.
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :int-add
                                     :args {:a {:as :renamed}}})))))


(deftest sequence-binding-int-add-skipped
  (testing "vector binding to a non-sequence slot — sequence-item check is gated on slot type, falls through"
    ;; :int-add :a is :int (scalar). My checker doesn't even reach
    ;; the sequence branch because the slot isn't sequence-typed.
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :int-add
                                     :args {:a [1 2 3]}})))))


(deftest sequence-items-checked-against-element-type
  (testing "[:list :int] slot accepts a vector of ints"
    (registry/record-rich-types! :sum-ints
                                 {:args {:nums {:type [:list :int]}}
                                  :return-type :int})
    (is (some? (check/check-fn-def! {:name :ok-sum
                                     :parent :sum-ints
                                     :args {:nums [1 2 3]}}))))

  (testing "[:list :int] slot rejects a string item — caught at sync time"
    ;; Same parent as the success case, but with a :text item mixed in.
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad-sum
                                :parent :sum-ints
                                :args {:nums [1 "two" 3]}}))))

  (testing "[:list :int] slot accepts a fn-ref item whose return is :int"
    (is (some? (check/check-fn-def! {:name :ok-sum-with-ref
                                     :parent :sum-ints
                                     :args {:nums [1 :get-int 3]}}))))

  (testing "[:list :int] slot rejects a fn-ref item whose return is :text"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad-sum-with-text-ref
                                :parent :sum-ints
                                :args {:nums [1 :get-text 3]}})))))


;; -----------------------------------------------------------------------------
;; Phase 4 step 2 — literal vs refinement at sync time
;; -----------------------------------------------------------------------------

(deftest refinement-accepts-literal-satisfying-constraint
  (testing ":positive-int slot accepts a positive int literal at sync time"
    (registry/record-rich-types! :pos-int-fn
                                 {:args {:n {:type :positive-int}}
                                  :return-type :int})
    (is (some? (check/check-fn-def! {:name :ok :parent :pos-int-fn :args {:n 5}})))))


(deftest refinement-rejects-literal-violating-constraint
  (testing ":positive-int slot rejects 0 / negative literals — caught at sync"
    (registry/record-rich-types! :pos-int-fn
                                 {:args {:n {:type :positive-int}}
                                  :return-type :int})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)refinement constraint failed"
          (check/check-fn-def! {:name :bad :parent :pos-int-fn :args {:n -5}})))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)refinement constraint failed"
          (check/check-fn-def! {:name :bad-zero :parent :pos-int-fn :args {:n 0}})))))


(deftest ensure-flow-narrows-int-to-positive-int
  (testing ":ensure-positive-int feeds a generic :int into a :positive-int slot"
    ;; Stub the validator so its rich-type matches the production
    ;; base-fn (return-type = :positive-int alias resolved).
    (registry/record-rich-types! :ensure-positive-int
                                 {:args {:value {:type :int}}
                                  :return-type :positive-int})
    (registry/record-rich-types! :sqrt-pos
                                 {:args {:n {:type :positive-int}}
                                  :return-type :float})
    ;; A fn-def whose `:n` is bound to the validator's output —
    ;; computed-return of :ensured = :positive-int → matches sqrt's slot.
    (registry/record-rich-types! :get-int-stub
                                 {:args {} :return-type :int})
    (is (some? (check/check-fn-def! {:name :ensured
                                     :parent :ensure-positive-int
                                     :args {:value :get-int-stub}})))
    (is (some? (check/check-fn-def! {:name :ok-sqrt
                                     :parent :sqrt-pos
                                     :args {:n :ensured}})))))


(deftest non-empty-text-checks-string-equality
  (testing ":non-empty-text rejects empty literal"
    (registry/record-rich-types! :greet
                                 {:args {:name {:type :non-empty-text}}
                                  :return-type :text})
    (is (some? (check/check-fn-def! {:name :ok :parent :greet :args {:name "Alice"}})))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)refinement constraint failed"
          (check/check-fn-def! {:name :bad :parent :greet :args {:name ""}})))))


(deftest sequence-untyped-slot-accepts-anything
  (testing ":sequence primitive (no element type) accepts mixed items"
    (registry/record-rich-types! :sum-anything
                                 {:args {:nums {:type :sequence}}
                                  :return-type :any})
    (is (some? (check/check-fn-def! {:name :ok
                                     :parent :sum-anything
                                     :args {:nums [1 "two" :get-int]}})))))


(deftest check-all-defs-stops-at-first-mismatch
  (testing "the first failing fn raises; preceding ones still ran"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-all-defs!
            [{:name :good :parent :int-add :args {:a 1 :b 2}}
             {:name :bad  :parent :int-add :args {:a "no" :b 2}}])))))


(deftest source-location-shown-in-type-error
  (testing "fn-def carrying :source-file / :source-line surfaces them in the message"
    (registry/record-rich-types! :greet
                                 {:args {:name {:type :text}}
                                  :return-type :text})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          ;; Order matters: the location prefix lands BEFORE the
          ;; "Type-check failed in fn-def" line.
          #"(?s)at packages/example/fns\.edn:42.*Type-check failed in fn-def"
          (check/check-fn-def!
            {:name        :bad-greet
             :parent      :greet
             :args        {:name 123}
             :source-file "packages/example/fns.edn"
             :source-line 42})))))


(deftest ancestor-source-shown-in-type-error
  (testing "parent's source-file/line is inlined next to the expected line"
    (registry/record-rich-types! :greet-loc
                                 {:args        {:name {:type :text}}
                                  :return-type :text
                                  :source-file "packages/greetings/fns.edn"
                                  :source-line 7})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?s)parent :greet-loc \(packages/greetings/fns\.edn:7\) expects:"
          (check/check-fn-def!
            {:name   :bad-greet-loc
             :parent :greet-loc
             :args   {:name 123}}))))
  (testing "ref-binding's actual line carries the ref's source-info too"
    (registry/record-rich-types! :wants-int
                                 {:args        {:n {:type :int}}
                                  :return-type :int
                                  :source-file "packages/math/fns.edn"
                                  :source-line 12})
    (registry/record-rich-types! :gives-text
                                 {:args        {}
                                  :return-type :text
                                  :source-file "packages/strings/fns.edn"
                                  :source-line 99})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?s)actual:\s+:text \(packages/strings/fns\.edn:99\)"
          (check/check-fn-def!
            {:name   :bad-int-via-ref
             :parent :wants-int
             :args   {:n :gives-text}})))))


(deftest effects-propagate-from-parent
  (testing "fn-def whose parent has :effects inherits the same set"
    (registry/record-rich-types! :do-io
                                 {:args {:x {:type :int}}
                                  :return-type :int
                                  :effects #{:io}})
    (check/check-fn-def! {:name :wraps-do-io :parent :do-io :args {:x 1}})
    (is (= #{:io} (:effects (registry/rich-type-of :wraps-do-io))))
    (is (true? (registry/effectful-rich-type? (registry/rich-type-of :wraps-do-io))))))


(deftest effects-union-from-multiple-refs
  (testing "pure parent + two refs each with distinct effects ⇒ union"
    (registry/record-rich-types! :pure-pair
                                 {:args {:a {:type :int} :b {:type :int}}
                                  :return-type :int})
    (registry/record-rich-types! :reads-env  {:args {} :return-type :int :effects #{:env}})
    (registry/record-rich-types! :reads-time {:args {} :return-type :int :effects #{:time}})
    (check/check-fn-def!
      {:name   :env-and-time
       :parent :pure-pair
       :args   {:a :reads-env :b :reads-time}})
    (is (= #{:env :time}
           (:effects (registry/rich-type-of :env-and-time))))))


(deftest effects-union-across-three-mi-parents
  (testing "MI fn-def with ≥3 parents — child's effects = union of every parent's"
    (registry/record-rich-types! :mi-eff-db
                                 {:args {:x {:type :int}}
                                  :return-type :int
                                  :effects #{:db}})
    (registry/record-rich-types! :mi-eff-env
                                 {:args {:y {:type :int}}
                                  :return-type :int
                                  :effects #{:env}})
    (registry/record-rich-types! :mi-eff-io-network
                                 {:args {:z {:type :int}}
                                  :return-type :int
                                  :effects #{:io :network}})
    (check/check-fn-def!
      {:name :mi-effect-triple
       :parents [:mi-eff-db :mi-eff-env :mi-eff-io-network]
       :args {:x 1 :y 2 :z 3}})
    ;; compute-effects taints across the parent chain: child sees
    ;; every parent's :effects category, unioned. With 3 parents
    ;; each declaring disjoint effects the child's :effects is the
    ;; full union — under-declaration via MI is the regression we
    ;; want pinned.
    (is (= #{:db :env :io :network}
           (:effects (registry/rich-type-of :mi-effect-triple))))))


(deftest pure-fn-def-stays-pure
  (testing "pure parent + pure ref ⇒ fn-def carries explicit empty :effects"
    (registry/record-rich-types! :pure-id-2
                                 {:args {:x {:type :int}} :return-type :int})
    (check/check-fn-def! {:name :pure-composed :parent :pure-id-2 :args {:x 7}})
    ;; Post-P8: :effects is always stored; pure fns carry an explicit
    ;; #{} marker rather than relying on key-absence.
    (is (= #{} (:effects (registry/rich-type-of :pure-composed))))
    (is (false? (registry/effectful-rich-type? (registry/rich-type-of :pure-composed))))))


(deftest mi-fn-def-merges-parent-args-and-takes-first-return
  (testing "fn-def with :parents [...] gets union args + first parent's return"
    (registry/record-rich-types! :base-l
                                 {:args {:x {:type :int}} :return-type :int})
    (registry/record-rich-types! :base-r
                                 {:args {:y {:type :text}} :return-type :int})
    (check/check-fn-def!
      {:name :mi-child
       :parents [:base-l :base-r]
       :args {}})
    (let [info (registry/rich-type-of :mi-child)]
      (testing "args from BOTH parents propagate as free args"
        (is (= :int  (get-in info [:args :x])))
        (is (= :text (get-in info [:args :y]))))
      (testing "return-type taken from first parent"
        (is (= :int (:return info)))))))


(deftest expects-effects-rejects-drift
  (testing ":expects-effects #{:io} but child pulls in :db ⇒ sync-time rejection"
    (registry/record-rich-types! :db-base
                                 {:args {} :return-type :int :effects #{:db}})
    (registry/record-rich-types! :pure-host
                                 {:args {:x {:type :int}} :return-type :int})
    ;; Drift used to be a soft WARN; the contract is now strict —
    ;; a fn-def declaring `:expects-effects #{:io}` whose closure
    ;; actually produces `#{:db}` is a real bug the system catches
    ;; immediately on sync.
    (let [thrown (try
                   (check/check-fn-def!
                     {:name :uses-db
                      :parent :pure-host
                      :args {:x :db-base}
                      :expects-effects #{:io}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "drift triggers an exception, not just a log")
      (is (= :types/expects-effects-drift (:type (ex-data thrown))))
      (is (= #{:db} (:unexpected (ex-data thrown)))
          "ex-data names the categories that exceeded the contract"))))


(deftest expects-effects-passes-when-subset
  (testing ":expects-effects accepts proper subset / equal sets"
    (registry/record-rich-types! :env-base
                                 {:args {} :return-type :int :effects #{:env}})
    (registry/record-rich-types! :pure-host-2
                                 {:args {:x {:type :int}} :return-type :int})
    (let [warnings (atom [])
          orig (deref #'log/log*)]
      (with-redefs [log/log*
                    (fn [logger level throwable msg]
                      (when (= :warn level) (swap! warnings conj msg))
                      (orig logger level throwable msg))]
        (check/check-fn-def!
          {:name :uses-env-only
           :parent :pure-host-2
           :args {:x :env-base}
           :expects-effects #{:env :db :io}}))     ; broader set
      (is (empty? @warnings) "no warnings when computed ⊆ declared"))))


(deftest mi-effects-still-union
  (testing "MI fn-def unions :effects from every parent"
    (registry/record-rich-types! :env-base   {:args {} :return-type :int :effects #{:env}})
    (registry/record-rich-types! :db-base    {:args {} :return-type :int :effects #{:db}})
    (check/check-fn-def!
      {:name :mi-effects
       :parents [:env-base :db-base]
       :args {}})
    (is (= #{:env :db} (:effects (registry/rich-type-of :mi-effects))))))


(deftest legacy-effectful-bool-is-ignored
  ;; `:effectful? true` was a 1-bit legacy flag that normalised to
  ;; `:effects #{:effect}`. The generic `:effect` category was
  ;; retired in favour of the six named categories — every effectful
  ;; base-fn now names its specific category (db / env / io /
  ;; network / time / random). The legacy boolean is silently
  ;; dropped: a fn-def using only the deprecated shim now reads as
  ;; pure (which is the correct outcome — authors must port to a
  ;; specific category).
  (testing "an EDN-declared `:effectful? true` is silently dropped"
    (registry/record-rich-types! :legacy-bool
                                 {:args {} :return-type :int :effectful? true})
    (let [info (registry/rich-type-of :legacy-bool)]
      ;; `:effects` is always recorded as the computed set; legacy
      ;; `:effectful? true` doesn't add a category, so the set is
      ;; empty — the correct "computed-pure" representation.
      (is (= #{} (:effects info))
          "Empty :effects set — the legacy generic shim was retired.")
      (is (not (registry/effectful-rich-type? info))))))


(deftest source-location-recorded-in-registry
  (testing "successful fn-def stashes its :source-file / :source-line"
    (registry/record-rich-types! :id-fn
                                 {:args {:x {:type :int}}
                                  :return-type :int})
    (check/check-fn-def!
      {:name        :ok-id
       :parent      :id-fn
       :args        {:x 7}
       :source-file "packages/example/fns.edn"
       :source-line 17})
    (let [entry (registry/rich-type-of :ok-id)]
      (is (= "packages/example/fns.edn" (:source-file entry)))
      (is (= 17 (:source-line entry))))))


(deftest literal-satisfies-and-or-compound-constraints
  (testing ":and — every child must hold"
    (is (true?  (check/literal-satisfies-refinement? 50 [:and [:>= 0] [:<= 100]])))
    (is (false? (check/literal-satisfies-refinement? -1 [:and [:>= 0] [:<= 100]])))
    (is (false? (check/literal-satisfies-refinement? 101 [:and [:>= 0] [:<= 100]]))))
  (testing ":or — at least one must hold"
    (is (true?  (check/literal-satisfies-refinement? 0   [:or [:= 0] [:= 1]])))
    (is (true?  (check/literal-satisfies-refinement? 1   [:or [:= 0] [:= 1]])))
    (is (false? (check/literal-satisfies-refinement? 7   [:or [:= 0] [:= 1]]))))
  (testing "empty :and is true; empty :or is false"
    (is (true?  (check/literal-satisfies-refinement? 0 [:and])))
    (is (false? (check/literal-satisfies-refinement? 0 [:or]))))
  (testing "compound with mixed decidable + :unknown"
    ;; :matches is currently :unknown (regex shape); :and decisive false
    ;; from the second clause overrides the unknown.
    (is (false? (check/literal-satisfies-refinement? "x" [:and [:matches #"."] [:= "y"]])))
    ;; :or with one true short-circuits to true even if a sibling is unknown
    (is (true?  (check/literal-satisfies-refinement? "x" [:or  [:matches #"."] [:= "x"]])))))


(deftest literal-satisfies-atomic-ops
  (testing "every atomic op decides numeric literals correctly"
    (is (true?  (check/literal-satisfies-refinement? 5  [:>  0])))
    (is (false? (check/literal-satisfies-refinement? 0  [:>  0])))
    (is (true?  (check/literal-satisfies-refinement? 5  [:>= 5])))
    (is (false? (check/literal-satisfies-refinement? 4  [:>= 5])))
    (is (true?  (check/literal-satisfies-refinement? 3  [:<  5])))
    (is (false? (check/literal-satisfies-refinement? 5  [:<  5])))
    (is (true?  (check/literal-satisfies-refinement? 5  [:<= 5])))
    (is (false? (check/literal-satisfies-refinement? 6  [:<= 5])))
    (is (true?  (check/literal-satisfies-refinement? 5  [:=  5])))
    (is (false? (check/literal-satisfies-refinement? "" [:not= ""]))))
  (testing ":in membership decides on a set"
    (is (true?  (check/literal-satisfies-refinement? :ok  [:in #{:ok :err}])))
    (is (false? (check/literal-satisfies-refinement? :nope [:in #{:ok :err}]))))
  (testing ":matches regex defers (non-statically-decidable)"
    (is (= :unknown (check/literal-satisfies-refinement? "abc" [:matches #"."]))))
  (testing "non-vector / unknown-shape / bad-arity constraints defer"
    (is (= :unknown (check/literal-satisfies-refinement? 5 nil)))
    (is (= :unknown (check/literal-satisfies-refinement? 5 [:bogus])))
    (is (= :unknown (check/literal-satisfies-refinement? 5 [:>])))
    (is (= :unknown (check/literal-satisfies-refinement? 5 [:> 0 1])))))


;; -----------------------------------------------------------------------------
;; describe-binding / hint-for-actual surface in error MESSAGE strings.
;; Cover each binding shape by triggering a mismatch and asserting the
;; message text. Keeps the formatting code paths exercised.

(deftest error-message-renders-each-binding-shape
  (testing "literal binding → '(literal 42)' shape"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"\(literal \"hello\"\)"
          (check/check-fn-def! {:name :a :parent :int-add :args {:a "hello"}}))))

  (testing "fn-ref binding → 'fn-ref → :name'"
    (registry/record-rich-types! :returns-text
                                 {:args {} :return-type :text})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"fn-ref → :returns-text"
          (check/check-fn-def! {:name :b :parent :int-add :args {:a :returns-text}}))))

  ;; NOTE: `{:ref X}` and bare-vector bindings get DEFERRED by
  ;; `deferred-binding?` — they keep the slot free for the next
  ;; caller — so they don't trigger throws here. The
  ;; `describe-binding` cases for those shapes can only fire if a
  ;; future code path lands them at check-binding! directly.
  )


;; -----------------------------------------------------------------------------
;; Sequence-item shapes inside a literal-vector binding — `:value`,
;; `:ref`, `:as`, bare keyword, plain literal. Each routes through
;; `sequence-item-actual-type`.

(deftest sequence-item-shapes-each-classify
  (registry/record-rich-types! :sum-ints
                               {:args {:nums [:list :int]}
                                :return-type :int})
  (registry/record-rich-types! :returns-int
                               {:args {} :return-type :int})
  (registry/record-rich-types! :returns-text
                               {:args {} :return-type :text})

  (testing "literal-int items pass"
    (is (some? (check/check-fn-def! {:name :ok-1 :parent :sum-ints
                                     :args {:nums [1 2 3]}}))))
  (testing "{:value 7} items pass"
    (is (some? (check/check-fn-def! {:name :ok-2 :parent :sum-ints
                                     :args {:nums [{:value 1} {:value 2}]}}))))
  (testing "bare keyword items take ref's :return"
    (is (some? (check/check-fn-def! {:name :ok-3 :parent :sum-ints
                                     :args {:nums [:returns-int]}}))))
  (testing "{:ref :name} items take the named ref's :return"
    (is (some? (check/check-fn-def! {:name :ok-4 :parent :sum-ints
                                     :args {:nums [{:ref :returns-int}]}}))))
  (testing "{:as :x} item-rename surfaces as :any (caller-supplied)"
    (is (some? (check/check-fn-def! {:name :ok-5 :parent :sum-ints
                                     :args {:nums [{:as :x}]}}))))
  (testing ":text-returning ref items trip the elem check"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad :parent :sum-ints
                                :args {:nums [:returns-text]}})))))


;; -----------------------------------------------------------------------------
;; item-free-args lift — sequence items contribute their refs' free-args
;; (or, for `{:as :x}`, an :any-typed slot at the call site).

(deftest sequence-item-free-args-flow-up
  (testing "vector item that is a fn-ref lifts the ref's free args"
    (registry/record-rich-types! :id-on-int
                                 {:args {:x {:type :int}} :return-type :int})
    ;; Compose a fn-def whose item is a ref of :id-on-int — the ref's
    ;; :x free arg should surface in the registered fn-def's :args.
    (registry/record-rich-types! :sum-anything
                                 {:args {:nums [:list :any]}
                                  :return-type :int})
    (check/check-fn-def!
      {:name :uses-it
       :parent :sum-anything
       :args {:nums [:id-on-int]}})
    (let [info (registry/rich-type-of :uses-it)]
      (is (contains? (:args info) :x)
          "free arg :x lifts from the sequence-item ref")))

  (testing "vector item with {:as :name} introduces a free slot of :any"
    (registry/record-rich-types! :sum-anything-2
                                 {:args {:nums [:list :any]}
                                  :return-type :int})
    (check/check-fn-def!
      {:name :uses-rename
       :parent :sum-anything-2
       :args {:nums [{:as :z}]}})
    (let [info (registry/rich-type-of :uses-rename)]
      (is (contains? (:args info) :z)
          "rename adds a free slot under the new name"))))


;; -----------------------------------------------------------------------------
;; Refinement on a literal that satisfies the BASE but FAILS the constraint.
;; This is a different code path from :base-not-ok (which throws when the
;; classified literal type isn't a subtype of the refinement's base).

(deftest refinement-base-mismatch-throws
  (testing ":positive-int slot with a TEXT literal — base mismatch path"
    (registry/record-rich-types! :need-pos
                                 {:args {:n {:type :positive-int}}
                                  :return-type :int})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)type-check failed"
          (check/check-fn-def! {:name :wrong-base
                                :parent :need-pos
                                :args {:n "not-a-number"}})))))


;; -----------------------------------------------------------------------------
;; Passthrough polymorphism — :value carries 'a all the way to return-type.
;; Covers `:constantly` / `:const` / `:identity` after the type-var fix.

(deftest passthrough-pins-return-to-bound-literal
  (testing "binding :value to an int literal narrows the child's return-type to :int"
    (registry/record-rich-types! :passthrough
                                 {:args {:value {:type 'a}
                                         :_item {:type :any :required false}}
                                  :return-type 'a})
    (check/check-fn-def! {:name :always-42 :parent :passthrough :args {:value 42}})
    (is (= :int (:return (registry/rich-type-of :always-42))))))


(deftest passthrough-pins-return-to-fn-ref-return
  (testing "binding :value to a fn-ref propagates the ref's return-type up"
    (registry/record-rich-types! :passthrough
                                 {:args {:value {:type 'a}
                                         :_item {:type :any :required false}}
                                  :return-type 'a})
    ;; :get-text is seeded by the global fixture (return :text).
    (check/check-fn-def! {:name :always-text :parent :passthrough :args {:value :get-text}})
    (is (= :text (:return (registry/rich-type-of :always-text))))))


(deftest passthrough-rename-preserves-type-var
  (testing "{:as :renamed} keeps 'a free — child still polymorphic"
    (registry/record-rich-types! :passthrough
                                 {:args {:value {:type 'a}
                                         :_item {:type :any :required false}}
                                  :return-type 'a})
    (check/check-fn-def! {:name :renamed-passthrough
                          :parent :passthrough
                          :args {:value {:as :response}}})
    (let [info (registry/rich-type-of :renamed-passthrough)]
      ;; Args are stored as {arg-name rich-type} directly (no spec wrapper).
      (is (types-core/type-var? (get-in info [:args :response])))
      (is (types-core/type-var? (:return info))))))


;; -----------------------------------------------------------------------------
;; Record-builder via :assoc chain — verify that the `:assoc` rule fires
;; through walk-to-root (immediate parent is a composition, not :assoc
;; itself) and that bogus :get keys on a known-record :coll get rejected
;; at sync time. End-to-end sanity for the type-builder UX.

(deftest assoc-empty-chain-builds-record-via-walk-to-root
  (testing "child of :assoc-empty (which is parent :assoc) gets :assoc's record-builder rule"
    ;; Seed :assoc as a base-fn and :assoc-empty as a fn-def whose
    ;; :primary-parent is :assoc — same shape as the production setup.
    (registry/record-rich-types! :assoc
                                 {:args {:map :any :key :any :value :any}
                                  :return-type :any
                                  :return-type-rule (:return-type-rule (core-base-fns :assoc))})
    (check/check-fn-def! {:name :assoc-empty
                          :parent :assoc
                          :args {:map {}}})
    (check/check-fn-def! {:name :singleton-record
                          :parent :assoc-empty
                          :args {:key "name" :value "Alice"}})
    (is (= {:name :text}
           (:return (registry/rich-type-of :singleton-record)))
        "rule on :assoc fires even when immediate parent is :assoc-empty")))


(deftest get-rejects-bogus-field-on-known-record
  (testing ":get with a literal key not in the :coll's record type throws at sync"
    (registry/record-rich-types! :get
                                 {:args {:coll :jsonb :key :any}
                                  :return-type :any
                                  :return-type-rule (:return-type-rule (core-base-fns :get))})
    (registry/record-rich-types! :produces-user-record
                                 {:args {} :return-type {:name :text :age :int}})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"field :nonexistent not found"
          (check/check-fn-def! {:name :wrong-get
                                :parent :get
                                :args {:coll :produces-user-record
                                       :key  "nonexistent"}})))))


;; -----------------------------------------------------------------------------
;; Optional declared :return-type on fn-defs — verified against computed.

(deftest declared-return-type-accepted-when-computed-subtypes
  (testing "fn-def's declared :return-type is accepted when computed ⊆ declared"
    (registry/record-rich-types! :assoc
                                 {:args {:map :any :key :any :value :any}
                                  :return-type :any
                                  :return-type-rule (:return-type-rule (core-base-fns :assoc))})
    ;; Computed: {:status :int}. Declared: :jsonb (a wider type — record ⊆ jsonb).
    (check/check-fn-def! {:name :status-builder
                          :parent :assoc
                          :args {:map {} :key "status" :value 200}
                          :return-type :jsonb})
    ;; The recorded type is the DECLARED one (the user's contract).
    (is (= :jsonb (:return (registry/rich-type-of :status-builder))))))


(deftest declared-return-type-rejected-when-computed-incompatible
  (testing "fn-def's declared :return-type fails when computed isn't a subtype"
    (registry/record-rich-types! :assoc
                                 {:args {:map :any :key :any :value :any}
                                  :return-type :any})
    ;; Computed: {:status :int}. Declared: :int — record is NOT a subtype of int.
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"declares :return-type"
          (check/check-fn-def! {:name :wrong-assertion
                                :parent :assoc
                                :args {:map {} :key "status" :value 200}
                                :return-type :int})))))


(deftest declared-return-type-narrowing-assertion-mode
  (testing "structural declared type narrower than computed is accepted as an author runtime assertion"
    ;; Mimic the :_create-parsed pattern: parent's rule yields a wider/looser
    ;; record (some fields :any, some nullable). Author asserts a tighter
    ;; record. The rule's view (each declared field ⊆ matching computed
    ;; field) makes the narrowing sound; runtime guarantees come from
    ;; upstream validation, declared is the post-validation contract.
    (registry/record-rich-types!
      :parsed-base
      {:args {} :return-type {:entity-type :any :id [:union :null :uuid]}})
    (check/check-fn-def! {:name :tightened-parsed
                          :parent :parsed-base
                          :args {}
                          :return-type {:entity-type :keyword :id :uuid}})
    (is (= {:entity-type :keyword :id :uuid}
           (:return (registry/rich-type-of :tightened-parsed)))
        "registry records the declared (tightened) shape")))


(deftest declared-return-type-narrowing-assertion-stays-sound
  (testing "narrowing-assertion only accepts when declared ⊆ computed; unrelated narrowing still rejects"
    (registry/record-rich-types!
      :returns-int-or-null
      {:args {} :return-type [:union :null :int]})
    ;; :keyword is NOT a subtype of [:union :null :int]. Author lying.
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"declares :return-type"
          (check/check-fn-def! {:name :wrong-narrowing
                                :parent :returns-int-or-null
                                :args {}
                                :return-type :keyword})))))


(deftest typevar-binds-to-any-shape-actual
  (testing ":if's `:then a` slot bound to an actual `[:map :any :any]` BINDS the typevar instead of silent-passing"
    ;; Without the fix, structural-any actual silent-passed against a
    ;; typevar slot, leaving `a` unbound in the substitution. `:if`'s
    ;; declared `[:union a b]` return then surfaced a literal "a"
    ;; typevar string in the registry, polluting downstream consumers.
    (registry/record-rich-types!
      :returns-loose-map
      {:args {} :return-type [:map :any :any]})
    (registry/record-rich-types!
      :returns-record
      {:args {} :return-type {:k :int}})
    ;; Use :if as the parent; its :then/:else are independent typevars
    ;; and the return is their union.
    (let [recorded (check/check-fn-def! {:name :branched
                                         :parent :if
                                         :args {:test true
                                                :then :returns-loose-map
                                                :else :returns-record}})
          info (registry/rich-type-of :branched)
          ret (:return info)]
      (is (some? recorded))
      ;; The recorded return must NOT contain a raw typevar — both
      ;; branches' types should be present in the resolved union (after
      ;; absorption it may collapse, but no bare typevar leaks).
      (is (not (some types-core/type-var? (tree-seq coll? seq ret)))
          (str "no typevar leaks; got " (pr-str ret))))))


;; -----------------------------------------------------------------------------
;; :required widening rejection — bindings cannot widen required → optional

(deftest binding-required-false-rejected-as-widening
  (testing "binding with :required false on any slot is rejected — widening forbidden"
    (registry/record-rich-types! :base-with-opt
                                 {:args {:flag :bool}
                                  :return-type :bool})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)widen"
          (check/check-fn-def! {:name :tries-to-widen
                                :parent :base-with-opt
                                :args {:flag {:required false}}})))
    (let [thrown (try
                   (check/check-fn-def! {:name :tries-to-widen
                                         :parent :base-with-opt
                                         :args {:flag {:required false}}})
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bindings/widening-required (:type (ex-data thrown)))
          "tagged with the dedicated :bindings/widening-required type")
      (is (= :flag (:arg-name (ex-data thrown)))
          "diagnostic names the offending slot"))))


(deftest binding-required-true-accepted-as-narrowing
  (testing "binding with :required true is accepted — narrowing optional → required is allowed"
    (registry/record-rich-types! :base-with-opt
                                 {:args {:flag :bool}
                                  :return-type :bool})
    (is (some? (check/check-fn-def! {:name :narrows-flag
                                     :parent :base-with-opt
                                     :args {:flag {:required true}}}))
        ":required true on its own (no value, no ref) is a valid narrowing")))


;; -----------------------------------------------------------------------------
;; :type widening rejection — bindings cannot widen the inherited slot type

(deftest binding-type-override-widening-rejected
  (testing "{:type :any} on an :int slot is rejected — widening forbidden"
    (registry/record-rich-types! :wants-int
                                 {:args {:n {:type :int}}
                                  :return-type :int})
    (let [thrown (try
                   (check/check-fn-def! {:name :widens-n
                                         :parent :wants-int
                                         :args {:n {:type :any}}})
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bindings/widening-type (:type (ex-data thrown)))
          "tagged with the dedicated :bindings/widening-type category")
      (is (re-find #"cannot widen the inherited type" (ex-message thrown))
          "diagnostic explicitly names the widening direction"))))


(deftest binding-type-override-narrowing-accepted
  (testing "{:type :positive-int} on an :int slot is accepted — narrowing is allowed"
    (registry/record-rich-types! :wants-int-2
                                 {:args {:n {:type :int}}
                                  :return-type :int})
    (is (some? (check/check-fn-def! {:name :narrows-to-positive
                                     :parent :wants-int-2
                                     :args {:n {:type :positive-int}}}))
        ":positive-int ⊆ :int — valid narrowing")))


(deftest binding-type-multi-level-widening-rejected
  (testing "grandchild can't widen back to :int after parent narrowed to :positive-int"
    ;; Grandparent has :int slot
    (registry/record-rich-types! :gp-wants-int
                                 {:args {:n {:type :int}}
                                  :return-type :int})
    ;; Parent narrows to :positive-int — valid
    (registry/record-rich-types! :p-narrows-positive
                                 {:args {:n {:type :positive-int}}
                                  :return-type :int})
    ;; Grandchild trying to widen :positive-int → :int — invalid.
    ;; check-binding-monotonicity! compares the child's :type override
    ;; against its IMMEDIATE parent's resolved slot type, which already
    ;; reflects the upstream narrowing.
    (let [thrown (try
                   (check/check-fn-def! {:name :gc-widens-to-int
                                         :parent :p-narrows-positive
                                         :args {:n {:type :int}}})
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bindings/widening-type (:type (ex-data thrown)))
          "widening at the grandchild against the narrowed grandparent → reject")
      ;; Alias resolution flattens :positive-int to its structural form
      ;; before comparison, so the diagnostic carries the refine vector.
      (is (re-find #":refine :int" (ex-message thrown))
          "diagnostic names the narrower (refinement) type that the override tried to widen"))))


(deftest binding-type-widening-via-union-rejected
  (testing "{:type [:union :int :null]} on an :int slot is rejected — nullability widens"
    (registry/record-rich-types! :wants-strict-int
                                 {:args {:n {:type :int}}
                                  :return-type :int})
    (let [thrown (try
                   (check/check-fn-def! {:name :widens-via-union
                                         :parent :wants-strict-int
                                         :args {:n {:type [:union :int :null]}}})
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bindings/widening-type (:type (ex-data thrown)))
          "adding :null to a non-null slot is widening, not narrowing"))))


(deftest binding-type-narrowing-via-union-accepted
  (testing "{:type :int} on a [:union :int :null] slot is accepted — null-stripping is narrowing"
    (registry/record-rich-types! :wants-nullable
                                 {:args {:n {:type [:union :int :null]}}
                                  :return-type [:union :int :null]})
    (is (some? (check/check-fn-def! {:name :strips-null
                                     :parent :wants-nullable
                                     :args {:n {:type :int}}}))
        ":int ⊆ [:union :int :null] — dropping the null branch is narrowing")))


(deftest binding-type-override-on-type-var-slot-skipped
  (testing "type-var slots skip the monotonicity check (unification handles them later)"
    (registry/record-rich-types! :wants-polymorphic
                                 {:args {:value {:type 'a}}
                                  :return-type 'a})
    ;; Pinning a polymorphic slot to a concrete type is the standard
    ;; way to instantiate a parametric base-fn — must not be flagged
    ;; as widening even though 'a ≠ :int by literal comparison.
    (is (some? (check/check-fn-def! {:name :instantiates-polymorphic
                                     :parent :wants-polymorphic
                                     :args {:value {:type :int}}}))
        "binding {:type :int} on a type-var slot is instantiation, not widening")))


(deftest binding-required-false-rejected-under-mi
  (testing "MI fn-def cannot widen to :required false even with multiple parents"
    ;; Two parents, neither knows about :required narrowing — but the
    ;; widening rule fires regardless of how the slot got there. The
    ;; rule is "no binding writes :required false", full stop.
    (registry/record-rich-types! :parent-one
                                 {:args {:flag :bool}
                                  :return-type :bool})
    (registry/record-rich-types! :parent-two
                                 {:args {:other :int}
                                  :return-type :int})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)widen"
          (check/check-fn-def! {:name :mi-tries-widen
                                :parents [:parent-one :parent-two]
                                :args {:flag {:required false}}}))
        "MI doesn't unlock widening — the structural rule fires uniformly")))


(deftest mi-fn-def-inherits-parents-resolved-bindings
  (testing "an MI fn-def merges :resolved-bindings from every parent"
    ;; Each parent carries a binding the other lacks — accumulated up
    ;; its own chain. The MI child must inherit BOTH: without this a
    ;; return-type rule re-firing on the child (or a descendant) loses
    ;; slots bound deeper up the chain — the bug that collapsed the
    ;; Ring-response record to :jsonb for the `:r404`/`:r405`/`:r500`
    ;; multiple-inheritance presets.
    (registry/record-rich-types-raw!
      :mi-rb-parent-a
      {:return :int :args {} :resolved-bindings {:x {:type :int :value 1}}})
    (registry/record-rich-types-raw!
      :mi-rb-parent-b
      {:return :int :args {} :resolved-bindings {:y {:type :text :value "z"}}})
    (check/check-fn-def! {:name :mi-rb-child
                          :parents [:mi-rb-parent-a :mi-rb-parent-b]})
    (let [rb (:resolved-bindings (registry/rich-type-of :mi-rb-child))]
      (is (contains? rb :x) "binding from the first parent survives")
      (is (contains? rb :y) "binding from the second parent survives"))))


(deftest mi-fn-def-drops-slot-bound-by-a-sibling-parent
  (testing "a slot left free by one MI parent but bound by another is NOT free in the child"
    ;; closer-fn-wins MI: parent-b's binding of :s applies to the
    ;; child, so :s must not survive as a free arg even though
    ;; parent-a still lists it. merge-mi-parent-infos subtracts the
    ;; union of every parent's :resolved-bindings keys from the merged
    ;; free-arg map.
    (registry/record-rich-types-raw!
      :mi-a-parent {:return :int :args {:s :int :a :int}})
    (registry/record-rich-types-raw!
      :mi-b-parent {:return :int :args {:b :text}
                    :resolved-bindings {:s {:type :int :value 1}}})
    (check/check-fn-def! {:name :mi-drop-child
                          :parents [:mi-a-parent :mi-b-parent]})
    (let [args (:args (registry/rich-type-of :mi-drop-child))]
      (is (not (contains? args :s))
          ":s — bound by mi-b-parent — does not re-leak as a free arg")
      (is (contains? args :a) ":a (unbound by every parent) stays free")
      (is (contains? args :b) ":b (unbound by every parent) stays free"))))


(deftest hof-slot-ref-free-args-split-call-site-vs-captured
  ;; Closure-capture (docs/CLOSURE_CAPTURE.md commits 2–4):
  ;; - call-site args of an HOF slot (declared in the slot's structural
  ;;   `[:fn {ARGS} _]` shape) are supplied per-invocation by the
  ;;   parent's impl and DO NOT lift.
  ;; - everything else in the wrapped ref's free args is CAPTURED and
  ;;   DOES lift onto the outer fn-def's surface.
  ;; - bare `:fn` primitive has no structural shape → call-site is
  ;;   empty → every free arg is captured.
  (registry/record-rich-types-raw!
    :hb-parent-bare {:return :any :args {:func :fn :coll :any}})
  (registry/record-rich-types-raw!
    :hb-parent-struct {:return :any
                       :args {:func [:fn {:item :int} :bool] :coll :any}})
  (registry/record-rich-types-raw!
    :hb-ref-cap-only {:return :bool :args {:r-free :int}})
  (registry/record-rich-types-raw!
    :hb-ref-with-callsite {:return :bool
                           :args {:item :int :r-free :int}})

  (testing "bare :fn slot — ref's free args are ALL captured and lift"
    (check/check-fn-def! {:name :hb-on-bare-fn-slot
                          :parent :hb-parent-bare
                          :args {:func :hb-ref-cap-only}})
    (let [args (:args (registry/rich-type-of :hb-on-bare-fn-slot))]
      (is (contains? args :r-free)
          "bare :fn has empty structural shape → all ref args captured")
      (is (contains? args :coll) "the unbound non-fn slot stays free")))

  (testing "structural [:fn {ARGS} _] slot — call-site args do NOT lift"
    (check/check-fn-def! {:name :hb-on-struct-fn-slot
                          :parent :hb-parent-struct
                          :args {:func :hb-ref-with-callsite}})
    (let [args (:args (registry/rich-type-of :hb-on-struct-fn-slot))]
      (is (not (contains? args :item))
          ":item is in slot's call-site shape → consumed by hof-wrap")
      (is (contains? args :r-free)
          ":r-free is NOT in slot's call-site shape → captured, lifts")))

  (testing "ref bound to a NON-fn slot lifts all its free args (contrast)"
    (check/check-fn-def! {:name :hb-on-coll-slot
                          :parent :hb-parent-bare
                          :args {:coll :hb-ref-cap-only}})
    (let [args (:args (registry/rich-type-of :hb-on-coll-slot))]
      (is (contains? args :r-free)
          "non-HOF slot — closure-capture doesn't apply, all args lift"))))


(deftest get-real-path-rejects-missing-record-field-typo
  ;; :get of a known record with a literal key that isn't a field, and
  ;; NO :default — a typo. get-return-rule should throw at
  ;; check-fn-def! time. This exercises the REAL bindings-info path
  ;; (not the rules_test shim): parent-arg fallback injects a :default
  ;; entry into bindings-info, so the rule must tell "bound" from
  ;; "free" to keep typo-detection alive.
  (testing ":get with a typo'd literal key and no :default is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"not found in record"
          (check/check-fn-def!
            {:name :typo-get :parent :get
             :args {:coll {:value {:a 1}}
                    :key {:value :nope}}})))))


;; ============================================================================
;; Secret taint propagation (T2)
;; ============================================================================

(defn- register-propagate-stub!
  "Canonical shape any T3 string-op will use: a slot declared as
   `[:secret :text]` so BOTH plain `:text` and `[:secret :text]` can
   flow in (the slot is secret-aware; plain values auto-promote on
   entry), plus a `:return-type-rule` that propagates the taint to
   the result iff any actual binding was already secret-marked."
  []
  (registry/record-rich-types! :propagate-stub
                               {:args {:s {:type [:secret :text]}}
                                :return-type :text
                                :return-type-rule (fn [bi default-ret]
                                                    (types-core/taint-with-secret-if-tainted
                                                      bi default-ret))}))


(deftest secret-tainted-input-bubbles-into-recorded-return
  (testing "an arg ref'd to a :secret-returning fn taints the fn-def's return"
    (registry/record-rich-types! :get-secret-stub
                                 {:args {} :return-type [:secret :text]})
    (register-propagate-stub!)

    (check/check-fn-def! {:name :tainted-via-ref
                          :parent :propagate-stub
                          :args {:s :get-secret-stub}})

    (testing "recorded return-type is :secret(:text) even though declared was :text"
      (is (= [:secret :text]
             (:return (registry/rich-type-of :tainted-via-ref)))))))


(deftest secret-untainted-input-leaves-return-plain
  (testing "no secret in inputs → static return verbatim, no auto-wrap"
    (registry/record-rich-types! :clean-text-stub
                                 {:args {} :return-type :text})
    (register-propagate-stub!)
    (check/check-fn-def! {:name :clean-via-ref
                          :parent :propagate-stub
                          :args {:s :clean-text-stub}})
    (is (= :text (:return (registry/rich-type-of :clean-via-ref))))))


(deftest enforce-declared-return-allows-secret-taint-over-plain-declaration
  ;; Direct test of `enforce-declared-return!` semantics — a fn-def
  ;; that PINS its declared return to plain `:text` MUST still type-check
  ;; when the rule-computed return is `[:secret :text]`. The marker is
  ;; propagation metadata, not a widening; without this exemption every
  ;; tainted fn would have to declare `[:secret …]` explicitly, breaking
  ;; the "declare base type, let propagation lift it" model.
  (registry/record-rich-types! :get-secret-stub
                               {:args {} :return-type [:secret :text]})
  (register-propagate-stub!)
  (check/check-fn-def! {:name :pinned-text-with-secret-input
                        :parent :propagate-stub
                        :return-type :text
                        :args {:s :get-secret-stub}})
  (is (= [:secret :text]
         (:return (registry/rich-type-of :pinned-text-with-secret-input)))))


(deftest secret-tainted-result-cannot-flow-into-plain-text-slot
  ;; The downstream check — once a fn-def's return is `:secret(:text)`,
  ;; passing IT as a binding to a slot typed plain `:text` must REJECT.
  ;; This is the structural enforcement that closes the "compose with
  ;; arbitrary string op, get secret in result" leak.
  (registry/record-rich-types! :get-secret-stub
                               {:args {} :return-type [:secret :text]})
  (registry/record-rich-types! :plain-text-sink
                               {:args {:s {:type :text}}
                                :return-type :int})
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo #"(?i)type-check failed"
        (check/check-fn-def! {:name :leak-attempt
                              :parent :plain-text-sink
                              :args {:s :get-secret-stub}}))))


(deftest secret-tainted-result-flows-into-secret-aware-slot
  ;; Inverse — a sink that DECLARES the slot as :secret(:text) accepts
  ;; a secret-returning ref.
  (registry/record-rich-types! :get-secret-stub
                               {:args {} :return-type [:secret :text]})
  (registry/record-rich-types! :secret-aware-sink
                               {:args {:token {:type [:secret :text]}}
                                :return-type :int})
  (is (some? (check/check-fn-def! {:name :auth-call
                                   :parent :secret-aware-sink
                                   :args {:token :get-secret-stub}}))))


(deftest plain-text-promotes-into-secret-aware-slot
  ;; Auto-promote on entry — a sink with a `[:secret :text]` slot
  ;; accepts a literal `:text` binding too, and the propagation rule
  ;; correctly leaves the return untainted (the binding was actually
  ;; plain; the slot's secret-typing only marks "this slot can hold
  ;; secrets, treat its contents as such").
  (register-propagate-stub!)
  (check/check-fn-def! {:name :plain-into-secret-slot
                        :parent :propagate-stub
                        :args {:s {:value "literal-text"}}})
  (is (= :text (:return (registry/rich-type-of :plain-into-secret-slot)))))
