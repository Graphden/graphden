(ns graphden.packages.core.collections-test
  "Unit tests for `core.collections` base-fn impls — direct
   `(fn [args ctx])` invocations, no bootstrap (pattern of
   `arithmetic_test.clj`).

   The module is the widest primitive surface in the graph: nearly
   every fn-def in every package reaches a collection op eventually,
   and several of these impls deliberately DIVERGE from bare
   clojure.core (`:conj` appends instead of prepending, `:into` vecs a
   seq destination, `:concat`/`:take`/`:sort`… return vectors, `:list`
   pointedly does NOT). Those divergences are the whole reason the
   impls exist, so they are what this namespace pins.

   The tail of the file covers the per-base-fn TYPE RULES that
   `graphden.types.rules-test` does not reach — `:select-keys`,
   `:zipmap`, `:update-vals`, `:update-keys`, `:flatten`,
   `:pairs->map`, plus the homogeneous-`[:map K V]` arms of `:assoc` /
   `:dissoc` / `:merge` and `:into`'s `:empty-map` arm."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "core" "collections"))


(defn- args*
  "Args map with every value wrapped in a delay — the shape the
   executor hands an impl. `delay` is a macro, so this can't be
   `update-vals`."
  [m]
  (into {} (map (fn [[k v]] [k (delay v)])) m))


(defn- call
  [kw arg-map]
  ((impls/impl-of kw) (args* arg-map) nil))


(defn- ex-type
  "`:type` of the ex-data thrown by `f`, or `::no-throw`."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(defn- rule
  "A type-rule `defn` out of the eval'd impls namespace."
  [sym]
  (let [v (some-> (find-ns 'graphden.packages.core.collections.impls)
                  (ns-resolve sym))]
    (when-not v (throw (ex-info (str "rule not found: " sym) {})))
    @v))


(defn- counting-seq
  "Unchunked lazy seq that bumps `counter` once per element realized."
  [counter coll]
  (lazy-seq
    (when (seq coll)
      (swap! counter inc)
      (cons (first coll) (counting-seq counter (rest coll))))))


;; =============================================================================
;; Element access — the nil / empty / missing-key arms
;; =============================================================================

(deftest first-and-rest-of-empty-and-nil
  (testing ":first of an empty or nil coll is nil, never a throw"
    (is (nil? (call :first {:coll []})))
    (is (nil? (call :first {:coll nil})))
    (is (= 1 (call :first {:coll [1 2]}))))
  (testing ":rest of an empty / one-element / nil coll is an EMPTY seq, not nil"
    ;; `:rest` feeds straight back into list ops; a nil here would turn
    ;; a graph loop over a drained list into a nil-pun instead of a
    ;; clean empty step.
    (is (= [] (call :rest {:coll []})))
    (is (= [] (call :rest {:coll [1]})))
    (is (= [] (call :rest {:coll nil})))
    (is (= [2 3] (call :rest {:coll [1 2 3]})))))


(deftest get-uses-default-only-for-absent-keys
  (testing "present key wins over the default"
    (is (= 1 (call :get {:coll {:a 1} :key :a :default :fallback}))))
  (testing "absent key, nil coll, and absent coll all yield the default"
    (is (= :fallback (call :get {:coll {:a 1} :key :zzz :default :fallback})))
    (is (= :fallback (call :get {:coll nil :key :a :default :fallback}))))
  (testing "no default bound → nil (not a throw)"
    (is (nil? (call :get {:coll {:a 1} :key :zzz})))
    (is (nil? (call :get {:coll nil :key :a}))))
  (testing "a key present with a nil VALUE returns nil, not the default"
    ;; `get` distinguishes absent from nil-valued; a graph that binds
    ;; `:default` must not see it for an explicitly-nil field.
    (is (nil? (call :get {:coll {:a nil} :key :a :default :fallback}))))
  (testing "vectors index by position"
    (is (= :b (call :get {:coll [:a :b] :key 1 :default :oob})))
    (is (= :oob (call :get {:coll [:a :b] :key 9 :default :oob})))))


(deftest get-in-walks-a-path-and-falls-back-whole
  (testing "a full path returns the leaf"
    (is (= 1 (call :get-in {:map {:a {:b 1}} :path [:a :b] :default :d}))))
  (testing "a path that breaks ANYWHERE yields the default, not a partial"
    (is (= :d (call :get-in {:map {:a {:b 1}} :path [:a :zzz] :default :d})))
    (is (= :d (call :get-in {:map {:a {:b 1}} :path [:zzz :b] :default :d})))
    (is (= :d (call :get-in {:map nil :path [:a :b] :default :d}))))
  (testing "no default → nil"
    (is (nil? (call :get-in {:map {:a {:b 1}} :path [:a :zzz]}))))
  (testing "an EMPTY path returns the map itself"
    (is (= {:a 1} (call :get-in {:map {:a 1} :path [] :default :d}))))
  (testing "vector indices are path segments too"
    (is (= :y (call :get-in {:map {:xs [:x :y]} :path [:xs 1] :default :d})))))


(deftest count-and-empty-are-nil-safe
  (testing ":count of nil is 0 — a missing list must not blow up an arity check"
    (is (zero? (call :count {:coll nil})))
    (is (zero? (call :count {:coll []})))
    (is (= 2 (call :count {:coll [1 2]})))
    (is (= 2 (call :count {:coll {:a 1 :b 2}})) ":count of a map counts ENTRIES")
    (is (= 3 (call :count {:coll "abc"})) ":count of a string counts CHARS"))
  (testing ":empty? of nil is true"
    (is (true? (call :empty? {:coll nil})))
    (is (true? (call :empty? {:coll []})))
    (is (false? (call :empty? {:coll [1]})))))


(deftest contains-tests-keys-not-values
  (testing "maps and sets test membership of the KEY"
    (is (true? (call :contains? {:coll {:a 1} :key :a})))
    (is (false? (call :contains? {:coll {:a 1} :key 1})))
    (is (true? (call :contains? {:coll #{:a} :key :a}))))
  (testing "on a vector the key is an INDEX, not an element"
    ;; The classic clojure.core trap, inherited verbatim; a graph that
    ;; wants element membership needs `:position-in`.
    (is (true? (call :contains? {:coll [10 20] :key 0})))
    (is (false? (call :contains? {:coll [10 20] :key 10}))))
  (testing "nil coll is false"
    (is (false? (call :contains? {:coll nil :key :a})))))


(deftest keys-and-vals-of-an-empty-map-are-nil
  ;; `(keys {})` is nil, NOT `()`. Downstream `:count` is nil-safe (0)
  ;; and `:first` is nil-safe, so nil is survivable — but anything
  ;; asserting a list shape must know.
  (is (nil? (call :keys {:map {}})))
  (is (nil? (call :vals {:map {}})))
  (is (nil? (call :keys {:map nil})))
  (is (= [:a] (call :keys {:map {:a 1}})))
  (is (= [1] (call :vals {:map {:a 1}}))))


(deftest position-in-reports-index-zero-as-zero-not-nil
  (testing "the first element's index is 0 — distinguishable from absent"
    (let [at-0 (call :position-in {:coll [:a :b] :value :a})]
      (is (zero? at-0))
      (is (some? at-0))))
  (is (= 1 (call :position-in {:coll [:a :b] :value :b})))
  (testing "absent → nil"
    (is (nil? (call :position-in {:coll [:a :b] :value :zzz})))
    (is (nil? (call :position-in {:coll [] :value :a}))))
  (testing "FIRST occurrence wins"
    (is (= 1 (call :position-in {:coll [:x :a :a] :value :a}))))
  (testing "a lazy seq works — the impl vecs a non-vector coll"
    (is (= 2 (call :position-in {:coll (map inc [0 1 2]) :value 3})))))


;; =============================================================================
;; Construction / mutation shapes the graph depends on
;; =============================================================================

(deftest conj-appends-to-a-seq-instead-of-prepending
  (testing "a list / lazy-seq destination APPENDS and comes back a vector"
    ;; Bare `conj` PREPENDS onto a seq. Every graph list built through
    ;; `:conj` would come out reversed without this arm.
    (is (= [1 2 3] (call :conj {:coll '(1 2) :item 3})))
    (is (vector? (call :conj {:coll '(1 2) :item 3})))
    (is (= [1 2 3] (call :conj {:coll (map inc [0 1]) :item 3}))))
  (testing "nil destination starts a VECTOR, not a list"
    (is (= [1] (call :conj {:coll nil :item 1})))
    (is (vector? (call :conj {:coll nil :item 1}))))
  (testing "vectors append as usual"
    (is (= [1 2] (call :conj {:coll [1] :item 2}))))
  (testing "maps and sets take a plain conj"
    (is (= {:a 1 :b 2} (call :conj {:coll {:a 1} :item [:b 2]})))
    (is (= #{1 2} (call :conj {:coll #{1} :item 2})))))


(deftest cons-prepends-and-is-nil-safe
  (is (= [0 1 2] (call :cons {:item 0 :coll [1 2]})))
  (is (= [0] (call :cons {:item 0 :coll nil}))))


(deftest assoc-on-nil-creates-a-map
  (testing "nil map is treated as {} — the `(or map {})` arm"
    ;; Without it `(assoc nil :a 1)` would still work in clojure.core,
    ;; but a nil-typed slot flowing through an assoc CHAIN would
    ;; produce nothing usable; the graph relies on assoc being a
    ;; total map-builder.
    (is (= {:a 1} (call :assoc {:map nil :key :a :value 1}))))
  (is (= {:a 1 :b 2} (call :assoc {:map {:a 1} :key :b :value 2})))
  (is (= {:a 2} (call :assoc {:map {:a 1} :key :a :value 2})))
  (testing "a nil VALUE is stored, not skipped"
    (is (= {:a nil} (call :assoc {:map {} :key :a :value nil})))
    (is (contains? (call :assoc {:map {} :key :a :value nil}) :a))))


(deftest dissoc-of-an-absent-key-is-a-no-op
  (is (= {:a 1} (call :dissoc {:map {:a 1} :key :zzz})))
  (is (= {} (call :dissoc {:map {:a 1} :key :a})))
  (is (nil? (call :dissoc {:map nil :key :a}))))


(deftest merge-is-last-wins-and-skips-nils
  (is (= {:a 1 :b 2} (call :merge {:maps [{:a 1} {:b 2}]})))
  (testing "later maps win on a key collision"
    (is (= {:a 2} (call :merge {:maps [{:a 1} {:a 2}]}))))
  (testing "nil members are skipped, an empty list merges to nil"
    (is (= {:a 1} (call :merge {:maps [{:a 1} nil]})))
    (is (nil? (call :merge {:maps []})))))


(deftest into-preserves-from-order-on-a-seq-destination
  (testing "a LIST destination would prepend each item — the impl vecs it"
    (is (= [1 2 3 4] (call :into {:to '(1 2) :from [3 4]})))
    (is (= [1 2 3 4] (call :into {:to (map inc [0 1]) :from [3 4]}))))
  (testing "a vector destination appends in order"
    (is (= [1 2 3] (call :into {:to [1] :from [2 3]}))))
  (testing "map / set destinations pass through untouched"
    (is (= {:a 1 :b 2} (call :into {:to {:a 1} :from [[:b 2]]})))
    (is (= {:a 1} (call :into {:to {} :from [[:a 1]]})))
    (is (= #{1 2} (call :into {:to #{1} :from [2]}))))
  (testing "nil from is a no-op"
    (is (= [1] (call :into {:to [1] :from nil})))))


(deftest assoc-in-creates-missing-intermediate-maps
  (testing "a nil or absent intermediate is CREATED, not an error"
    (is (= {:a {:b 1}} (call :assoc-in {:m nil :path [:a :b] :v 1})))
    (is (= {:a {:b 1}} (call :assoc-in {:m {} :path [:a :b] :v 1}))))
  (testing "siblings at every level survive"
    (is (= {:keep 0 :a {:keep 1 :b 2}}
           (call :assoc-in {:m {:keep 0 :a {:keep 1 :b 9}} :path [:a :b] :v 2}))))
  (testing "vector indices are assignable but not extendable"
    (is (= {:xs [:x :NEW]}
           (call :assoc-in {:m {:xs [:x :y]} :path [:xs 1] :v :NEW})))))


(deftest update-in-hands-the-callback-nil-for-a-missing-path
  (testing "existing value is passed to f"
    (is (= {:a {:b 2}} (call :update-in {:m {:a {:b 1}} :path [:a :b] :f inc}))))
  (testing "a missing path calls f with nil — f must be nil-tolerant"
    (is (= {:x :was-nil}
           (call :update-in {:m {} :path [:x] :f #(if (nil? %) :was-nil %)})))
    (is (= {:a {:b :was-nil}}
           (call :update-in {:m nil :path [:a :b] :f #(if (nil? %) :was-nil %)})))))


;; =============================================================================
;; Bounded generators — the refusal arms
;; =============================================================================

(deftest range-refuses-a-zero-step
  (testing "step 0 is an infinite loop — refused with :invalid-args"
    (let [d (try (call :range {:start 0 :end 5 :step 0})
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :execution-error/invalid-args (:type d)))
      (is (= {:step 0} (select-keys d [:step]))
          "the offending step travels in the ex-data"))))


(deftest range-produces-a-vector-and-an-empty-one-for-a-wrong-direction
  (is (= [0 1 2] (call :range {:start 0 :end 3 :step 1})))
  (is (vector? (call :range {:start 0 :end 3 :step 1})))
  (is (= [0 2 4] (call :range {:start 0 :end 5 :step 2})))
  (testing "a descending step counts down"
    (is (= [5 4 3] (call :range {:start 5 :end 2 :step -1}))))
  (testing "a step pointing AWAY from end yields [] — not a size error"
    ;; The size computation is 0 for a wrong-direction range, so the
    ;; max-size guard must not fire on it.
    (is (= [] (call :range {:start 0 :end 5 :step -1})))
    (is (= [] (call :range {:start 5 :end 0 :step 1})))
    (is (= [] (call :range {:start 3 :end 3 :step 1})))))


(deftest range-refuses-to-exceed-the-size-ceiling
  (binding [sp/*max-range-size* 3]
    (testing "exactly at the ceiling is ACCEPTED — the guard is `>`, not `>=`"
      (is (= [0 1 2] (call :range {:start 0 :end 3 :step 1}))))
    (testing "one past the ceiling is refused with :range-too-large + sizes"
      (is (= :execution-error/range-too-large
             (ex-type #(call :range {:start 0 :end 4 :step 1}))))
      (let [d (try (call :range {:start 0 :end 10 :step 1})
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= 10 (:size d)))
        (is (= 3 (:max-size d)))
        (is (= {:start 0 :end 10 :step 1} (select-keys d [:start :end :step])))))
    (testing "a big span with a big step stays under the ceiling"
      (is (= [0 100] (call :range {:start 0 :end 200 :step 100}))))))


(deftest repeat-refuses-negative-and-oversized-counts
  (is (= [:x :x] (call :repeat {:count 2 :item :x})))
  (is (vector? (call :repeat {:count 2 :item :x})))
  (testing "count 0 is a legitimate empty vector"
    (is (= [] (call :repeat {:count 0 :item :x}))))
  (testing "a negative count is refused with :invalid-args"
    (is (= :execution-error/invalid-args
           (ex-type #(call :repeat {:count -1 :item :x})))))
  (binding [sp/*max-repeat-size* 2]
    (testing "exactly at the ceiling is accepted, one past is :repeat-too-large"
      (is (= [:x :x] (call :repeat {:count 2 :item :x})))
      (is (= :execution-error/repeat-too-large
             (ex-type #(call :repeat {:count 3 :item :x})))))))


;; =============================================================================
;; Shape guarantees — what comes back vectorised, and what pointedly doesn't
;; =============================================================================

(deftest take-drop-reverse-sort-return-vectors
  (testing "vectors, so a downstream `:get` by index and `:count` are O(1)"
    (is (vector? (call :take {:count 1 :coll [1 2]})))
    (is (vector? (call :drop {:count 1 :coll [1 2]})))
    (is (vector? (call :reverse {:coll [1 2]})))
    (is (vector? (call :sort {:coll [2 1]}))))
  (testing ":take/:drop clamp instead of throwing"
    (is (= [1 2] (call :take {:count 9 :coll [1 2]})))
    (is (= [] (call :take {:count 0 :coll [1 2]})))
    (is (= [] (call :take {:count 2 :coll nil})))
    (is (= [] (call :drop {:count 9 :coll [1 2]})))
    (is (= [1 2] (call :drop {:count 0 :coll [1 2]})))
    (is (= [] (call :drop {:count 1 :coll nil}))))
  (is (= [2 1] (call :reverse {:coll [1 2]})))
  (is (= [1 2 3] (call :sort {:coll [3 1 2]})))
  (is (= ["a" "b"] (call :sort {:coll ["b" "a"]}))))


(deftest concat-returns-a-vector-and-flattens-exactly-one-level
  (is (= [1 2 3] (call :concat {:colls [[1 2] [3]]})))
  (is (vector? (call :concat {:colls [[1 2] [3]]})))
  (testing "nested content one level down is NOT unwrapped"
    (is (= [1 [2]] (call :concat {:colls [[1] [[2]]]}))))
  (testing "nil / empty members contribute nothing"
    (is (= [1] (call :concat {:colls [nil [1] []]})))
    (is (= [] (call :concat {:colls []})))))


(deftest flatten-goes-all-the-way-down-but-not-into-maps
  (is (= [1 2 3 4] (call :flatten {:coll [[1 [2 [3]]] 4]})))
  (is (vector? (call :flatten {:coll [[1] 2]})))
  (testing "maps are not sequential — flatten does NOT explode them"
    ;; `:flatten` over a list of records keeps the records intact,
    ;; which is what every fn-def mapping over rows relies on.
    (is (= [{:a 1}] (call :flatten {:coll [[{:a 1}]]})))
    (is (= [] (call :flatten {:coll {:a 1}}))))
  (is (= [] (call :flatten {:coll nil}))))


(deftest distinct-keeps-first-seen-order
  ;; Not sorted, not set-ordered: the FIRST occurrence's position is
  ;; kept, so `:distinct` is safe over an ordered result set.
  (is (= [3 1 2] (call :distinct {:coll [3 1 3 2 1 3]})))
  (is (vector? (call :distinct {:coll [1 1]})))
  (is (= [] (call :distinct {:coll []})))
  (is (= [nil] (call :distinct {:coll [nil nil]})) "nil is a value, not a gap"))


(deftest list-returns-its-items-unforced
  (testing "`:list` must NOT vec — laziness is what lets :cond short-circuit"
    (let [n (atom 0)
          items (counting-seq n [1 2 3])
          out ((impls/impl-of :list) {:items (delay items)} nil)]
      (is (zero? @n) "nothing realized on the way through")
      (is (identical? items out) "returned as-is, not copied into a vector")
      (is (= 1 (first out)))
      (is (= 1 @n) "forcing the head realizes exactly one element"))))


(deftest vec-coerces-any-collection-to-a-vector
  (is (= [1 2] (call :vec {:coll '(1 2)})))
  (is (vector? (call :vec {:coll (map inc [0 1])})))
  (is (= [1 2] (call :vec {:coll [1 2]})))
  (is (= [] (call :vec {:coll nil})))
  (testing "a map vecs into ENTRY pairs"
    (is (= [[:a 1]] (call :vec {:coll {:a 1}})))))


;; =============================================================================
;; Map projections
;; =============================================================================

(deftest select-keys-omits-absent-keys-rather-than-nil-filling
  ;; A caller distinguishing "field absent" from "field nil" (the
  ;; `:get` + `:default` pairing above) depends on this.
  (is (= {:a 1} (call :select-keys {:m {:a 1 :b 2} :ks [:a]})))
  (is (= {:a 1} (call :select-keys {:m {:a 1} :ks [:a :zzz]})))
  (is (= {} (call :select-keys {:m {:a 1} :ks []})))
  (is (= {} (call :select-keys {:m nil :ks [:a]}))))


(deftest zipmap-truncates-to-the-shorter-side
  (is (= {:a 1 :b 2} (call :zipmap {:keys [:a :b] :vals [1 2]})))
  (testing "extra keys and extra vals are both dropped silently"
    (is (= {:a 1} (call :zipmap {:keys [:a :b] :vals [1]})))
    (is (= {:a 1} (call :zipmap {:keys [:a] :vals [1 2]}))))
  (testing "a duplicate key keeps the LAST value"
    (is (= {:a 2} (call :zipmap {:keys [:a :a] :vals [1 2]}))))
  (is (= {} (call :zipmap {:keys [] :vals []}))))


(deftest update-vals-and-keys-preserve-the-other-half
  (is (= {:a 2 :b 3} (call :update-vals {:m {:a 1 :b 2} :f inc})))
  (is (= {} (call :update-vals {:m {} :f inc})))
  (is (= {"a" 1} (call :update-keys {:m {:a 1} :f name})))
  (testing "colliding keys keep the LAST value in map order"
    (is (= {:k 2} (call :update-keys {:m (array-map :a 1 :b 2)
                                      :f (constantly :k)}))))
  (is (= {} (call :update-keys {:m {} :f name}))))


(deftest pairs-to-map-accepts-lazy-pairs-and-is-last-wins
  (testing "a pair built via `:list` is a lazy SEQ — each entry is vec'd"
    ;; `(into {} [(seq [:a 1])])` throws without the per-entry `vec`.
    (is (= {:a 1 :b 2} (call :pairs->map {:entries [(seq [:a 1]) (list :b 2)]}))))
  (is (= {:a 1} (call :pairs->map {:entries [[:a 1]]})))
  (testing "a duplicate key keeps the last pair"
    (is (= {:a 2} (call :pairs->map {:entries [[:a 1] [:a 2]]}))))
  (is (= {} (call :pairs->map {:entries []})))
  (is (= {} (call :pairs->map {:entries nil}))))


(deftest postwalk-rewrites-every-node-including-map-keys
  (testing "bottom-up over nested structure"
    (is (= {:a [2 3]}
           (call :postwalk {:f #(if (number? %) (inc %) %) :coll {:a [1 2]}}))))
  (testing "KEYS are walked too — not just values"
    (is (= {:a! {:b! 1}}
           (call :postwalk {:f #(if (keyword? %) (keyword (str (name %) "!")) %)
                            :coll {:a {:b 1}}}))))
  (testing "vectors stay vectors, maps stay maps"
    (let [out (call :postwalk {:f identity :coll {:a [1]}})]
      (is (map? out))
      (is (vector? (:a out)))))
  (is (nil? (call :postwalk {:f identity :coll nil}))))


;; =============================================================================
;; Type rules `graphden.types.rules-test` does not reach
;; =============================================================================

(deftest select-keys-rule-narrows-a-record-to-the-picked-subset
  (let [r (rule 'select-keys-return-rule)]
    (testing "record + all-literal ks → the subset record"
      (is (= {:a :int}
             (r {:m {:type {:a :int :b :text}}
                 :ks {:value [{:value :a}]}}
                [:map :keyword :any]))))
    (testing "a homogeneous [:map K V] keeps its shape"
      (is (= [:map :text :int]
             (r {:m {:type [:map :text :int]} :ks {:value [{:value :a}]}}
                [:map :keyword :any]))))
    (testing "a non-literal (computed) ks degrades to the declared return"
      (is (= [:map :keyword :any]
             (r {:m {:type {:a :int}} :ks {:value [{:ref :some-fn}]}}
                [:map :keyword :any])))
      (is (= [:map :keyword :any]
             (r {:m {:type {:a :int}} :ks {}} [:map :keyword :any])))
      (is (= [:map :keyword :any]
             (r {:m {:type {:a :int}} :ks {:value []}} [:map :keyword :any]))
          "an EMPTY literal ks vector is not evidence of a subset"))))


(deftest zipmap-rule-rebuilds-a-record-from-literal-keys
  (let [r (rule 'zipmap-return-rule)]
    (testing "literal keys + a per-item val type → the exact record"
      ;; This is what keeps a Ring-response builder's record shape from
      ;; collapsing to the declared `[:map :keyword :any]`.
      (is (= {:status :int :body :text}
             (r {:keys {:value [{:value :status} {:value :body}]}
                 :vals {:elem-types [:int :text]}}
                [:map :keyword :any]))))
    (testing "a COUNT mismatch degrades — no partial record"
      (is (= [:map :keyword :any]
             (r {:keys {:value [{:value :status} {:value :body}]}
                 :vals {:elem-types [:int]}}
                [:map :keyword :any]))))
    (testing "one non-literal key degrades the whole result"
      (is (= [:map :keyword :any]
             (r {:keys {:value [{:value :status} {:ref :computed}]}
                 :vals {:elem-types [:int :text]}}
                [:map :keyword :any]))))
    (testing "no per-item val types → degrade"
      (is (= [:map :keyword :any]
             (r {:keys {:value [{:value :status}]} :vals {:type [:list :any]}}
                [:map :keyword :any]))))))


(deftest update-vals-and-keys-rules-swap-exactly-one-half
  (let [uv (rule 'update-vals-return-rule)
        uk (rule 'update-keys-return-rule)]
    (testing ":update-vals keeps K, takes the callback's return as V"
      (is (= [:map :keyword :text]
             (uv {:m {:type [:map :keyword :int]} :f {:type [:fn {} :text]}}
                 [:map :any :any]))))
    (testing ":update-keys takes the callback's return as K, keeps V"
      ;; The `:stringify-map-keys` fn-def is exactly this shape.
      (is (= [:map :text :int]
             (uk {:m {:type [:map :keyword :int]} :f {:type [:fn {} :text]}}
                 [:map :any :any]))))
    (testing "a RECORD input degrades — per-field widening isn't modelled"
      (is (= [:map :any :any]
             (uv {:m {:type {:a :int}} :f {:type [:fn {} :text]}}
                 [:map :any :any])))
      (is (= [:map :any :any]
             (uk {:m {:type {:a :int}} :f {:type [:fn {} :text]}}
                 [:map :any :any]))))
    (testing "an unknown callable return degrades"
      (is (= [:map :any :any]
             (uv {:m {:type [:map :keyword :int]} :f {:type :fn}}
                 [:map :any :any]))))))


(deftest flatten-rule-unnests-exactly-one-level
  (let [r (rule 'flatten-return-rule)]
    (is (= [:list :int]
           (r {:coll {:type [:list [:list :int]]}} [:list :any])))
    (testing "an already-flat list degrades to the declared [:list :any]"
      (is (= [:list :any] (r {:coll {:type [:list :int]}} [:list :any])))
      (is (= [:list :any] (r {:coll {:type :jsonb}} [:list :any])))
      (is (= [:list :any] (r {} [:list :any]))))
    (testing "triple nesting models only ONE level statically"
      (is (= [:list [:list :int]]
             (r {:coll {:type [:list [:list [:list :int]]]}} [:list :any]))))))


(deftest pairs-to-map-rule-is-all-or-nothing
  (let [r (rule 'pairs->map-return-rule)]
    (testing "literal `{:value [k v]}` entries rebuild the record"
      (is (= {:a :int :b :text}
             (r {:entries {:value [{:value [:a 1]} {:value [:b "x"]}]}} :jsonb))))
    (testing "a RAW [k v] vector item works too (the parser's literal form)"
      (is (= {:a :int} (r {:entries {:value [[:a 1]]}} :jsonb))))
    (testing "string keys coerce to field keywords"
      (is (= {:a :text} (r {:entries {:value [["a" "x"]]}} :jsonb))))
    (testing "ONE unrecognised entry degrades the WHOLE record — no guessing"
      (is (= :jsonb
             (r {:entries {:value [{:value [:a 1]} {:value 5}]}} :jsonb))))
    (testing "a non-literal KEY half degrades"
      (is (= :jsonb (r {:entries {:value [[1 2]]}} :jsonb))))
    (testing "a dynamic entries binding degrades"
      (is (= :jsonb (r {:entries {:ref :computed}} :jsonb)))
      (is (= :jsonb (r {:entries {:value []}} :jsonb)))
      (is (= :jsonb (r {} :jsonb))))))


(deftest homogeneous-map-arms-of-assoc-dissoc-merge-and-into
  (testing ":assoc into a [:map K V] widens V instead of collapsing to a record"
    (is (= [:map :text [:union :int :text]]
           ((rule 'assoc-return-rule)
            {:map {:type [:map :text :text]}
             :key {:value "x"}
             :value {:type :int}}
            :jsonb))))
  (testing ":dissoc from a [:map K V] leaves the same [:map K V]"
    (is (= [:map :text :int]
           ((rule 'dissoc-return-rule)
            {:map {:type [:map :text :int]} :key {:value "x"}}
            :jsonb))))
  (testing ":merge of same-shaped [:map K V]s keeps the tight shape"
    (is (= [:map :text :text]
           ((rule 'merge-return-rule)
            {:maps {:elem-types [[:map :text :text] [:map :text :text]]}}
            [:map :any :any])))
    (testing "DIFFERING key/val types degrade — no unsound join"
      (is (= [:map :any :any]
             ((rule 'merge-return-rule)
              {:maps {:elem-types [[:map :text :text] [:map :text :int]]}}
              [:map :any :any])))
      (is (= [:map :any :any]
             ((rule 'merge-return-rule)
              {:maps {:elem-types [[:map :text :text] {:a :int}]}}
              [:map :any :any])))))
  (testing ":into a literal {} is a fresh open map, not the declared union"
    (is (= [:map :any :any]
           ((rule 'into-return-rule) {:to {:type :empty-map}} :jsonb))))
  (testing ":into a record / [:map K V] destination keeps that shape"
    (is (= {:a :int} ((rule 'into-return-rule) {:to {:type {:a :int}}} :jsonb)))
    (is (= [:map :text :int]
           ((rule 'into-return-rule) {:to {:type [:map :text :int]}} :jsonb)))))
