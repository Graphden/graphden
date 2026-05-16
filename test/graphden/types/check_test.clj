(ns graphden.types.check-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.logging :as log]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.types.check :as check]
    [graphden.types.core :as types-core]))


;; The check namespace reads from the rich-types registry. Each test
;; seeds a few base-fn signatures, runs the check, and asserts the
;; outcome. We use a tiny synthetic registry — no DB / sync needed.

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
    ;; Seed only the base-fns we need for these tests.
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
  (testing "string/mixed-keyed maps and empty maps stay :jsonb"
    (is (= :jsonb (check/classify-literal {"a" 1})))
    (is (= :jsonb (check/classify-literal {:a 1 "b" 2})))
    (is (= :jsonb (check/classify-literal {})))))


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


(deftest hof-effect-constraint-rejects-unannotated-callback
  (testing "callback without :effects in registry is treated as \"unknown effects\" — rejected from a pure slot"
    ;; A rich-types entry that lacks an `:effects` key (raw test
    ;; data, or a fn-def whose check-fn-def! hasn't run yet) reads
    ;; as nil on the actual side. `effects-compatible?` treats nil
    ;; sub vs concrete sup as a REJECTION — \"if I can't prove you're
    ;; pure, I assume the worst\". Same defensive stance as Haskell's
    ;; \"unknown monadic context can't be lifted into pure\". In
    ;; practice every fn-def gets :effects populated by check-fn-def!,
    ;; so this case only fires for raw test data.
    (registry/record-rich-types! :filter-pure
                                 {:args {:pred {:type [:fn {:item 'a} :bool #{}]}
                                         :coll {:type [:list 'a]}}
                                  :return-type [:list 'a]})
    (registry/record-rich-types-raw!
      :unannotated-pred
      {:args {:item :any} :return :bool})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :bad
                                :parent :filter-pure
                                :args {:pred :unannotated-pred}}))
        "unannotated effects ≢ provably pure")))


(deftest unknown-parent-skips-check
  (testing "non-base-fn parent or unknown parent → no-op"
    ;; :no-such-parent isn't seeded; check returns nil, no throw.
    (is (nil? (check/check-fn-def! {:name :ok
                                    :parent :no-such-parent
                                    :args {:x 5}})))))


(deftest unknown-arg-name-skips-check
  (testing "binding to a non-existent arg of parent — type check skips it (composition layer rejects later)"
    (is (some? (check/check-fn-def! {:name :ok
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


(deftest pure-fn-def-stays-pure
  (testing "pure parent + pure ref ⇒ fn-def has no :effects entry"
    (registry/record-rich-types! :pure-id-2
                                 {:args {:x {:type :int}} :return-type :int})
    (check/check-fn-def! {:name :pure-composed :parent :pure-id-2 :args {:x 7}})
    (is (nil? (:effects (registry/rich-type-of :pure-composed))))
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


(deftest legacy-effectful-bool-normalises-to-effect-set
  (testing "an EDN-declared `:effectful? true` becomes `:effects #{:effect}`"
    (registry/record-rich-types! :legacy-bool
                                 {:args {} :return-type :int :effectful? true})
    (let [info (registry/rich-type-of :legacy-bool)]
      (is (= #{:effect} (:effects info)))
      (is (registry/effectful-rich-type? info)))))


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
                                  :return-type :any})
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
                                  :return-type :any})
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
                                  :return-type :any})
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
