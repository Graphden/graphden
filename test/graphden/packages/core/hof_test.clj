(ns graphden.packages.core.hof-test
  "Unit tests for `core.hof` base-fn impls — direct `(fn [args ctx])`
   invocations, no bootstrap (pattern of `arithmetic_test.clj`).

   A `:fn`-typed arg reaches an impl as an already-wrapped single-arg
   callable, and `rt/resolve-arg` only calls a value that carries the
   `::thunk` marker — so a PLAIN Clojure fn passed in these tests
   travels through untouched, exactly as a compiled HOF wrapper does.

   Three contracts here are load-bearing far outside this module and
   are what the file mostly pins:
     - `:map` / `:filter` are EAGER and return a SEQ (never a vector):
       eager so per-element callbacks run inside the execution scope's
       effect-trace / cancel bindings, seq so hiccup SPLICES the
       children instead of reading the result as one `[tag attrs]`.
     - the reducer convention of `:reduce` and `:transduce` — the
       callback takes ONE vector `[acc item]`, not two args.
     - `:find-first` returns a falsy match, where a `some`-based
       implementation would skip past it."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "core" "hof"))


(defn- args*
  "Args map with every value wrapped in a delay — the shape the
   executor hands an impl. `delay` is a macro, so this can't be
   `update-vals`."
  [m]
  (into {} (map (fn [[k v]] [k (delay v)])) m))


(defn- call
  [kw arg-map]
  ((impls/impl-of kw) (args* arg-map) nil))


;; =============================================================================
;; :map / :filter — eager, seq-returning
;; =============================================================================

(deftest map-runs-every-callback-before-returning
  (testing "EAGER — an unrealized lazy seq would escape the effect scope"
    ;; A bare `(map f coll)` is realized later, during result encoding:
    ;; a throwing callback is then swallowed by the size-cap encoder and
    ;; mislabeled `:succeeded nil`, and any effect runs ungated.
    (let [calls (atom 0)
          out (call :map {:func (fn [x] (swap! calls inc) (* 2 x))
                          :coll [1 2 3]})]
      (is (= 3 @calls) "all three callbacks ran before the impl returned")
      (is (= [2 4 6] out))))
  (testing "the result is a SEQ, not a vector — hiccup splices seqs"
    (let [out (call :map {:func identity :coll [1 2]})]
      (is (seq? out))
      (is (not (vector? out)))))
  (testing "nil coll maps to an empty seq, not nil"
    (let [out (call :map {:func identity :coll nil})]
      (is (some? out))
      (is (empty? out)))))


(deftest filter-is-eager-seq-returning-and-keeps-order
  (let [calls (atom 0)
        out (call :filter {:pred (fn [x] (swap! calls inc) (even? x))
                           :coll [1 2 3 4]})]
    (is (= 4 @calls) "the predicate ran over every element eagerly")
    (is (= [2 4] out) "matches keep their relative order")
    (is (seq? out))
    (is (not (vector? out))))
  (testing "a predicate matching nothing yields an empty seq"
    (is (empty? (call :filter {:pred (constantly false) :coll [1 2]}))))
  (testing "nil coll → empty"
    (is (empty? (call :filter {:pred identity :coll nil}))))
  (testing "falsy ELEMENTS are dropped by a truthiness predicate"
    (is (= [1] (call :filter {:pred identity :coll [nil 1 false]})))))


;; =============================================================================
;; Transducer arms — the no-coll forms
;; =============================================================================

(deftest map-xf-and-filter-xf-return-transducers-not-results
  (testing ":map-xf takes NO coll — it returns a transducer to compose"
    (let [xf (call :map-xf {:func inc})]
      (is (fn? xf))
      (is (= [2 3] (into [] xf [1 2])))))
  (testing ":filter-xf likewise"
    (let [xf (call :filter-xf {:pred even?})]
      (is (fn? xf))
      (is (= [2 4] (into [] xf [1 2 3 4])))))
  (testing "the transducer is LAZY — building it runs no callback"
    (let [calls (atom 0)
          xf (call :map-xf {:func (fn [x] (swap! calls inc) x)})]
      (is (zero? @calls))
      (is (= [1 2] (into [] xf [1 2])))
      (is (= 2 @calls)))))


(deftest comp-composes-right-to-left-and-empty-is-identity
  (testing "rightmost transform applies FIRST — clojure.core/comp order"
    (let [f (call :comp {:functions [inc #(* 2 %)]})]
      (is (= 7 (f 3)) "(* 2 3) then inc")))
  (testing "an empty function list composes to identity, not an error"
    (is (= :x ((call :comp {:functions []}) :x))))
  (testing "transducers compose too — filter then map, in reading order"
    (let [xf (call :comp {:functions [(call :filter-xf {:pred even?})
                                      (call :map-xf {:func inc})]})]
      (is (= [3 5] (into [] xf [1 2 3 4]))))))


;; =============================================================================
;; Reducers — the one-vector-arg callback convention
;; =============================================================================

(deftest reduce-hands-the-callback-one-vector-of-acc-and-item
  (testing "the callback receives ONE arg: the [acc item] pair"
    ;; Graph callables are single-arg by construction (hof-wrap), so
    ;; every reducing fn-def destructures a pair. A two-arg reducer
    ;; here would be an arity error at runtime.
    (let [seen (atom [])]
      (call :reduce {:func (fn [pair] (swap! seen conj pair) (first pair))
                     :init :seed
                     :coll [1 2]})
      (is (= [[:seed 1] [:seed 2]] @seen))
      (is (every? vector? @seen))
      (is (every? #(= 2 (count %)) @seen))))
  (is (= 6 (call :reduce {:func (fn [[acc item]] (+ acc item))
                          :init 0
                          :coll [1 2 3]})))
  (testing "an empty or nil coll returns init WITHOUT calling the reducer"
    (let [calls (atom 0)
          f (fn [_] (swap! calls inc) :never)]
      (is (= :seed (call :reduce {:func f :init :seed :coll []})))
      (is (= :seed (call :reduce {:func f :init :seed :coll nil})))
      (is (zero? @calls))))
  (testing "a nil init is a legitimate seed, not 'no init'"
    (is (= [nil 1] (call :reduce {:func identity :init nil :coll [1]})))))


(deftest transduce-uses-the-same-one-vector-reducer-and-completes
  (testing "transducer + [acc item] reducer + init"
    (is (= 9 (call :transduce {:transducer (map inc)
                               :reducer (fn [[acc item]] (+ acc item))
                               :init 0
                               :coll [1 2 3]}))))
  (testing "a `:filter-xf`/`:map-xf` pair drives it — the graph pipeline"
    ;; [1 2 3 4] → filter even → [2 4] → map inc → [3 5] → sum 8.
    (is (= 8 (call :transduce {:transducer (call :comp
                                                 {:functions
                                                  [(call :filter-xf {:pred even?})
                                                   (call :map-xf {:func inc})]})
                               :reducer (fn [[acc item]] (+ acc item))
                               :init 0
                               :coll [1 2 3 4]}))))
  (testing "the completing (1-arity) step passes the accumulator through"
    ;; A stateful transducer flushes its partial batch through the
    ;; 2-arity at completion; the 1-arity must NOT re-wrap or drop it.
    (is (= [[1 2] [3]]
           (call :transduce {:transducer (partition-all 2)
                             :reducer (fn [[acc item]] (conj acc item))
                             :init []
                             :coll [1 2 3]}))))
  (testing "an empty coll returns init untouched"
    (is (= :seed (call :transduce {:transducer (map inc)
                                   :reducer (fn [_] :never)
                                   :init :seed
                                   :coll []})))))


;; =============================================================================
;; Search / predicate HOFs
;; =============================================================================

(deftest some-returns-the-predicates-value-not-the-element
  (testing "the PREDICATE's return is the result — clojure.core/some"
    (is (= :found (call :some {:pred (fn [x] (when (even? x) :found))
                               :coll [1 2 3]})))
    (is (true? (call :some {:pred even? :coll [1 2]}))))
  (testing "no match / empty / nil coll → nil"
    (is (nil? (call :some {:pred even? :coll [1 3]})))
    (is (nil? (call :some {:pred even? :coll []})))
    (is (nil? (call :some {:pred even? :coll nil}))))
  (testing "it short-circuits on the first truthy value"
    (let [calls (atom 0)]
      (call :some {:pred (fn [x] (swap! calls inc) (even? x)) :coll [1 2 3 4]})
      (is (= 2 @calls)))))


(deftest every-is-vacuously-true-on-empty-and-nil
  (is (true? (call :every? {:pred even? :coll [2 4]})))
  (is (false? (call :every? {:pred even? :coll [2 3]})))
  (testing "no elements means no counterexample — true, not nil/false"
    (is (true? (call :every? {:pred even? :coll []})))
    (is (true? (call :every? {:pred even? :coll nil}))))
  (testing "it short-circuits on the first failure"
    (let [calls (atom 0)]
      (call :every? {:pred (fn [x] (swap! calls inc) (even? x)) :coll [1 2 3]})
      (is (= 1 @calls)))))


(deftest find-first-returns-a-falsy-match-instead-of-skipping-it
  (testing "a matching element that is itself FALSE is returned"
    ;; A `(some #(when (pred %) %) …)` implementation reads the falsy
    ;; return as "keep going" and hands back a later element or nil.
    (let [out (call :find-first {:pred false? :coll [1 false 2]})]
      (is (false? out))
      (is (some? out) "false, not nil — the two must stay distinct")))
  (testing "a matching element that is NIL is returned as nil"
    (is (nil? (call :find-first {:pred nil? :coll [1 nil 2]}))))
  (testing "the FIRST match wins"
    (is (= 2 (call :find-first {:pred even? :coll [1 2 4]}))))
  (testing "no match / empty / nil coll → nil"
    (is (nil? (call :find-first {:pred even? :coll [1 3]})))
    (is (nil? (call :find-first {:pred even? :coll []})))
    (is (nil? (call :find-first {:pred even? :coll nil}))))
  (testing "it stops at the match — later elements are never tested"
    (let [calls (atom 0)]
      (call :find-first {:pred (fn [x] (swap! calls inc) (even? x))
                         :coll [1 2 3 4]})
      (is (= 2 @calls)))))


;; =============================================================================
;; Grouping / ordering / capture
;; =============================================================================

(deftest group-by-buckets-into-order-preserving-vectors
  (is (= {false [1 3] true [2 4]}
         (call :group-by {:key-fn even? :coll [1 2 3 4]})))
  (testing "each bucket is a VECTOR in first-seen order"
    (let [out (call :group-by {:key-fn :k :coll [{:k :a :n 1} {:k :a :n 2}]})]
      (is (vector? (get out :a)))
      (is (= [1 2] (mapv :n (get out :a))))))
  (testing "a nil key is a real bucket, not a dropped element"
    (is (= {nil [{:x 1}]} (call :group-by {:key-fn :missing :coll [{:x 1}]}))))
  (testing "empty / nil coll → {}"
    (is (= {} (call :group-by {:key-fn even? :coll []})))
    (is (= {} (call :group-by {:key-fn even? :coll nil})))))


(deftest sort-by-returns-a-vector-and-takes-a-keyword-key-fn
  (testing "a bare keyword works as the key-fn — keywords are callables"
    (is (= [{:n 1} {:n 2}]
           (call :sort-by {:key-fn :n :coll [{:n 2} {:n 1}]}))))
  (testing "a plain fn works too"
    (is (= [3 -2 1] (call :sort-by {:key-fn #(- (abs %)) :coll [1 -2 3]}))))
  (testing "the result is a VECTOR, not a lazy seq"
    (is (vector? (call :sort-by {:key-fn identity :coll [2 1]}))))
  (testing "sort is STABLE — equal keys keep input order"
    (is (= [[:a 1] [:b 1]]
           (call :sort-by {:key-fn second :coll [[:a 1] [:b 1]]}))))
  (testing "empty / nil coll → []"
    (is (= [] (call :sort-by {:key-fn identity :coll []})))
    (is (= [] (call :sort-by {:key-fn identity :coll nil})))))


(deftest constantly-ignores-the-item-and-yields-the-captured-value
  (testing "the captured `:value` is returned whatever the item is"
    ;; The graph's way to build a constant callback for a HOF slot.
    (is (= :captured (call :constantly {:value :captured :_item 1})))
    (is (= :captured (call :constantly {:value :captured :_item nil}))))
  (testing "a nil captured value is returned as nil, not skipped"
    (is (nil? (call :constantly {:value nil :_item :anything})))))
