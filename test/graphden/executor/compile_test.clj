(ns ^{:clj-kondo/config (quote {:linters {:shadowed-var {:level :off}, :unused-binding {:level :off}}})} graphden.executor.compile-test
  "Tests for the compile executor. Shadowed-var and unused-binding are
   disabled here because `defbase`'s destructure legitimately binds names
   that match core vars (`test`, `name`, `key`) and some tests bind args
   to exercise shadow semantics that kondo can't statically trace."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile :as c]
    [graphden.executor.defbase :refer [defbase]]))


;; ============================================================================
;; Sample impls
;; ============================================================================

(defbase add [a b]
  (+ a b))


(defbase identity-fn [value]
  value)


;; ============================================================================
;; Helpers for building synthetic graphs in tests
;; ============================================================================

(defn- mk-fn
  ([id fn-name] (mk-fn id fn-name nil))
  ([id fn-name parent-id]
   {:id id
    :name fn-name
    :parent-ids (when parent-id [parent-id])}))


(defn- mk-primary-arg
  "Primary arg (belongs to a base-fn)."
  [id fn-id arg-name]
  {:id id :fn-id fn-id :name arg-name :source-id nil})


(defn- mk-binding-arg
  "Binding arg on a composed fn — points at a parent's arg via source-id,
   sets :value or :ref-id. `opts` may include :name (rename), :value, :ref-id."
  [id fn-id source-id opts]
  (merge {:id id :fn-id fn-id :source-id source-id} opts))


;; ============================================================================
;; Tests — base-fn compile
;; ============================================================================

(deftest base-fn-compile
  (testing "compiling a base-fn wraps impl to accept free-args directly"
    (let [add-id (random-uuid)
          a-arg (mk-primary-arg (random-uuid) add-id "a")
          b-arg (mk-primary-arg (random-uuid) add-id "b")
          fns [(mk-fn add-id :add)]
          args [a-arg b-arg]
          base-fns {:add add}
          compiled (c/compile-all {:fns fns :args args :base-fns base-fns} nil)
          add-closure (get compiled add-id)]
      (is (fn? add-closure))
      (is (= 5 (add-closure compiled {:a 2 :b 3}))))))


;; ============================================================================
;; Tests — literal bindings
;; ============================================================================

(deftest literal-binding
  (testing "composed fn with one literal binding uses the literal"
    (let [add-id (random-uuid)
          a-arg (mk-primary-arg (random-uuid) add-id "a")
          b-arg (mk-primary-arg (random-uuid) add-id "b")

          add-10-id (random-uuid)
          add-10-a (mk-binding-arg (random-uuid) add-10-id (:id a-arg) {:value 10})

          fns [(mk-fn add-id :add)
               (mk-fn add-10-id :add-10 add-id)]
          args [a-arg b-arg add-10-a]
          compiled (c/compile-all {:fns fns :args args :base-fns {:add add}} nil)
          add-10 (get compiled add-10-id)]
      (is (= 15 (add-10 compiled {:b 5})))
      (is (= 20 (add-10 compiled {:b 10}))))))


(deftest both-literals
  (testing "composed fn with all slots bound to literals — free-args ignored"
    (let [add-id (random-uuid)
          a-arg (mk-primary-arg (random-uuid) add-id "a")
          b-arg (mk-primary-arg (random-uuid) add-id "b")

          seven-id (random-uuid)
          bind-a (mk-binding-arg (random-uuid) seven-id (:id a-arg) {:value 3})
          bind-b (mk-binding-arg (random-uuid) seven-id (:id b-arg) {:value 4})

          fns [(mk-fn add-id :add)
               (mk-fn seven-id :seven add-id)]
          args [a-arg b-arg bind-a bind-b]
          compiled (c/compile-all {:fns fns :args args :base-fns {:add add}} nil)
          seven (get compiled seven-id)]
      (is (= 7 (seven compiled {}))))))


;; ============================================================================
;; Tests — free args (no binding)
;; ============================================================================

(deftest free-args-flow-through
  (testing "unbound args on composed fn read from caller's free-args under base-name"
    (let [add-id (random-uuid)
          a-arg (mk-primary-arg (random-uuid) add-id "a")
          b-arg (mk-primary-arg (random-uuid) add-id "b")
          passthrough-id (random-uuid)    ; no bindings — inherits add fully
          fns [(mk-fn add-id :add)
               (mk-fn passthrough-id :passthrough add-id)]
          args [a-arg b-arg]
          compiled (c/compile-all {:fns fns :args args :base-fns {:add add}} nil)
          passthrough (get compiled passthrough-id)]
      (is (= 9 (passthrough compiled {:a 4 :b 5}))))))


;; ============================================================================
;; Tests — :as renaming
;; ============================================================================

(deftest rename-via-as
  (testing "arg with :as renames what caller provides; impl still sees base-name"
    (let [identity-id (random-uuid)
          value-arg (mk-primary-arg (random-uuid) identity-id "value")

          ring-req-id (random-uuid)
          ;; on ring-req: name=:request, source-id=:identity.value.id, no binding
          rename-arg (mk-binding-arg (random-uuid) ring-req-id (:id value-arg)
                                     {:name "request"})

          fns [(mk-fn identity-id :identity-fn)
               (mk-fn ring-req-id :ring-request identity-id)]
          args [value-arg rename-arg]
          compiled (c/compile-all {:fns fns :args args :base-fns {:identity-fn identity-fn}} nil)
          ring-req (get compiled ring-req-id)]
      (is (= :hello (ring-req compiled {:request :hello})))
      (is (nil? (ring-req compiled {:value :hello})) "original name no longer visible to caller"))))


;; ============================================================================
;; Tests — lookups and inheritance-chain
;; ============================================================================

(deftest inheritance-chain-walks-first-parent
  (let [a (random-uuid), b (random-uuid), c (random-uuid)
        fns [(mk-fn a :a) (mk-fn b :b a) (mk-fn c :c b)]
        {:keys [fn-map]} (c/build-lookups fns [])]
    (is (= [c b a] (#'c/inheritance-chain c fn-map)))
    (is (= :a (:name (c/base-fn-of c fn-map))))
    (is (= :a (:name (c/base-fn-of b fn-map))))
    (is (= :a (:name (c/base-fn-of a fn-map))))))


(deftest terminal-primary-id-walks-source-chain
  (let [p (random-uuid), child (random-uuid), gc (random-uuid)
        args [{:id p :fn-id (random-uuid) :source-id nil :name "p"}
              {:id child :fn-id (random-uuid) :source-id p :name "c"}
              {:id gc :fn-id (random-uuid) :source-id child :name "gc"}]
        {:keys [arg-map]} (c/build-lookups [] args)]
    (is (= p (c/terminal-primary-id gc arg-map)))
    (is (= p (c/terminal-primary-id child arg-map)))
    (is (= p (c/terminal-primary-id p arg-map)))))


;; ============================================================================
;; Stage 4 — ref resolution + laziness
;; ============================================================================

(defbase return-42 []
  42)


(defbase if-fn [test then else]
  (if test then else))


(deftest ref-binding-resolves-via-all-fns
  (testing "composed fn with :ref-id binding calls the target through all-fns"
    (let [ret42-id (random-uuid)
          add-id (random-uuid)
          a-arg (mk-primary-arg (random-uuid) add-id "a")
          b-arg (mk-primary-arg (random-uuid) add-id "b")

          ;; add-via-42: :a bound to ref :return-42, :b stays free
          wrapper-id (random-uuid)
          bind-a (mk-binding-arg (random-uuid) wrapper-id (:id a-arg) {:ref-id ret42-id})

          fns [(mk-fn ret42-id :return-42)
               (mk-fn add-id :add)
               (mk-fn wrapper-id :wrapper add-id)]
          args [a-arg b-arg bind-a]
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:return-42 return-42 :add add}} nil)
          wrapper (get compiled wrapper-id)]
      (is (= 45 (wrapper compiled {:b 3})))
      (is (= 50 (wrapper compiled {:b 8}))))))


(deftest if-fn-short-circuits-via-clojure-if
  (testing ":if only runs the chosen branch's ref — thanks to Clojure's native if"
    (let [if-id (random-uuid)
          test-arg (mk-primary-arg (random-uuid) if-id "test")
          then-arg (mk-primary-arg (random-uuid) if-id "then")
          else-arg (mk-primary-arg (random-uuid) if-id "else")

          ;; Side-effect-tracking base-fns. They have a free arg (:nonce) to
          ;; ensure constant folding doesn't eagerly precompute them at compile
          ;; time — that would bump the counter before the test resets it.
          then-counter (atom 0)
          else-counter (atom 0)
          bump-then (fn [_args _] (swap! then-counter inc) :then-value)
          bump-else (fn [_args _] (swap! else-counter inc) :else-value)

          then-fn-id (random-uuid)
          else-fn-id (random-uuid)
          then-nonce-arg (mk-primary-arg (random-uuid) then-fn-id "nonce")
          else-nonce-arg (mk-primary-arg (random-uuid) else-fn-id "nonce")

          ;; Two composed fns: cond-true-fn binds :test=true, :then→bump-then, :else→bump-else.
          ;; cond-false-fn is identical but with :test=false.
          mk-cond (fn [test-value]
                    (let [cond-id (random-uuid)]
                      {:fn-id cond-id
                       :fn-entity (mk-fn cond-id :cond-fn if-id)
                       :args [(mk-binding-arg (random-uuid) cond-id (:id test-arg) {:value test-value})
                              (mk-binding-arg (random-uuid) cond-id (:id then-arg) {:ref-id then-fn-id})
                              (mk-binding-arg (random-uuid) cond-id (:id else-arg) {:ref-id else-fn-id})]}))

          true-case (mk-cond true)
          false-case (mk-cond false)

          fns [(mk-fn if-id :if-fn)
               (mk-fn then-fn-id :bump-then)
               (mk-fn else-fn-id :bump-else)
               (:fn-entity true-case)
               (:fn-entity false-case)]
          args (concat [test-arg then-arg else-arg then-nonce-arg else-nonce-arg]
                       (:args true-case)
                       (:args false-case))
          base-fns {:if-fn if-fn :bump-then bump-then :bump-else bump-else}
          compiled (c/compile-all {:fns fns :args args :base-fns base-fns} nil)]

      (testing "true branch runs :then ref only"
        (reset! then-counter 0)
        (reset! else-counter 0)
        (is (= :then-value ((get compiled (:fn-id true-case)) compiled {})))
        (is (= 1 @then-counter))
        (is (zero? @else-counter)))

      (testing "false branch runs :else ref only"
        (reset! then-counter 0)
        (reset! else-counter 0)
        (is (= :else-value ((get compiled (:fn-id false-case)) compiled {})))
        (is (zero? @then-counter))
        (is (= 1 @else-counter))))))


(deftest ref-with-free-args-propagates-env
  (testing "ref's thunk receives the caller's free-args so propagated inputs flow through"
    ;; identity-fn → composed fn that wraps it → caller provides :value in free-args.
    (let [identity-id (random-uuid)
          value-arg (mk-primary-arg (random-uuid) identity-id "value")

          ;; wrapper-fn: parent is another composed fn that references identity.
          wrapper-id (random-uuid)
          add-id (random-uuid)
          a-arg (mk-primary-arg (random-uuid) add-id "a")
          b-arg (mk-primary-arg (random-uuid) add-id "b")
          bind-a-to-id (mk-binding-arg (random-uuid) wrapper-id (:id a-arg) {:ref-id identity-id})

          fns [(mk-fn identity-id :identity-fn)
               (mk-fn add-id :add)
               (mk-fn wrapper-id :wrapper add-id)]
          args [value-arg a-arg b-arg bind-a-to-id]
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:identity-fn identity-fn :add add}} nil)
          wrapper (get compiled wrapper-id)]
      ;; :value propagates to identity-fn via the ref thunk. :b is wrapper's own free arg.
      (is (= 15 (wrapper compiled {:value 10 :b 5}))))))


;; ============================================================================
;; Stage 5 — HOF (:is-fn) args
;; ============================================================================

(defbase map-fn [func coll]
  (mapv func coll))


(defbase reduce-fn [func init coll]
  (reduce (fn [acc item] (func [acc item])) init coll))


(defbase inc-fn [x]
  (inc x))


(defbase pair-sum [pair]
  (+ (first pair) (second pair)))


(deftest hof-single-free-arg
  (testing "(:fn arg → single-free-arg callable) — map over inc-fn"
    (let [inc-id (random-uuid)
          x-arg (mk-primary-arg (random-uuid) inc-id "x")

          map-id (random-uuid)
          func-arg (mk-primary-arg (random-uuid) map-id "func")
          coll-arg (mk-primary-arg (random-uuid) map-id "coll")

          ;; map-inc composed fn: binds :func → :inc-fn with :is-fn true
          map-inc-id (random-uuid)
          bind-func (mk-binding-arg (random-uuid) map-inc-id (:id func-arg)
                                    {:ref-id inc-id :is-fn true})

          fns [(mk-fn inc-id :inc-fn)
               (mk-fn map-id :map-fn)
               (mk-fn map-inc-id :map-inc map-id)]
          args [x-arg func-arg coll-arg bind-func]
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:inc-fn inc-fn :map-fn map-fn}} nil)
          map-inc (get compiled map-inc-id)]
      (is (= [2 3 4] (map-inc compiled {:coll [1 2 3]}))))))


;; ============================================================================
;; Stage 6 — constant folding
;; ============================================================================

(defbase const-fn
  "Base fn that returns a fixed value — used to build 'constant' composed fns."
  [v]
  v)


(deftest constant-folding-of-literal-binding
  (testing "fn with only literal bindings folds to (constantly v)"
    (let [const-id (random-uuid)
          v-arg (mk-primary-arg (random-uuid) const-id "v")

          hello-id (random-uuid)
          bind-v (mk-binding-arg (random-uuid) hello-id (:id v-arg) {:value "hello"})

          fns [(mk-fn const-id :const-fn)
               (mk-fn hello-id :hello const-id)]
          args [v-arg bind-v]
          call-count (atom 0)
          instrumented-const (fn [args ctx]
                               (swap! call-count inc)
                               (const-fn args ctx))
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:const-fn instrumented-const}} nil)
          hello (get compiled hello-id)]
      ;; The fold should have invoked const-fn once at compile time.
      (is (= 1 @call-count) "called exactly once during folding")
      ;; Subsequent invocations return the precomputed value without calling const-fn.
      (is (= "hello" (hello compiled {})))
      (is (= "hello" (hello compiled {})))
      (is (= 1 @call-count) "no further calls — fold replaced with (constantly v)"))))


(deftest constant-folding-through-ref-chain
  (testing "fn whose only ref-binding points to a constant folds too"
    (let [const-id (random-uuid)
          v-arg (mk-primary-arg (random-uuid) const-id "v")

          ;; :forty-two — constant 42
          forty-two-id (random-uuid)
          bind-42 (mk-binding-arg (random-uuid) forty-two-id (:id v-arg) {:value 42})

          ;; :wrap-42 — binds v via ref to :forty-two
          wrap-id (random-uuid)
          bind-wrap (mk-binding-arg (random-uuid) wrap-id (:id v-arg) {:ref-id forty-two-id})

          fns [(mk-fn const-id :const-fn)
               (mk-fn forty-two-id :forty-two const-id)
               (mk-fn wrap-id :wrap wrap-id)]
          ;; Fix: :wrap's parent should be :const-fn, not itself.
          fns (-> fns
                  (pop)
                  (conj (mk-fn wrap-id :wrap const-id)))
          args [v-arg bind-42 bind-wrap]
          call-count (atom 0)
          instrumented (fn [args ctx]
                         (swap! call-count inc)
                         (const-fn args ctx))
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:const-fn instrumented}} nil)
          wrap (get compiled wrap-id)]
      ;; Both :forty-two and :wrap folded; const-fn invoked exactly twice.
      (is (= 2 @call-count))
      (is (= 42 (wrap compiled {})))
      (is (= 2 @call-count) "no further calls"))))


(deftest constant-folding-skipped-for-free-args
  (testing "fn with any free arg is not folded"
    (let [const-id (random-uuid)
          v-arg (mk-primary-arg (random-uuid) const-id "v")

          identity-id (random-uuid)  ; passes through :v from free-args
          fns [(mk-fn const-id :const-fn)
               (mk-fn identity-id :passthrough const-id)]
          args [v-arg]                ; no binding — :v is free
          call-count (atom 0)
          instrumented (fn [args ctx]
                         (swap! call-count inc)
                         (const-fn args ctx))
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:const-fn instrumented}} nil)
          passthrough (get compiled identity-id)]
      (is (zero? @call-count) "not folded — :v is free")
      (is (= :runtime-value (passthrough compiled {:v :runtime-value})))
      (is (= 1 @call-count)))))


(deftest hof-multi-free-arg-via-vec
  (testing "(:fn arg → multi-free-arg callable, vec convention) — reduce with pair-sum"
    ;; Because our reduce-fn base impl does `(func [acc item])`, the user fn needs
    ;; to accept a pair via a single free arg (pair). We pass pair-sum that destructures.
    (let [pair-sum-id (random-uuid)
          pair-arg (mk-primary-arg (random-uuid) pair-sum-id "pair")

          red-id (random-uuid)
          func-arg (mk-primary-arg (random-uuid) red-id "func")
          init-arg (mk-primary-arg (random-uuid) red-id "init")
          coll-arg (mk-primary-arg (random-uuid) red-id "coll")

          sum-id (random-uuid)
          bind-func (mk-binding-arg (random-uuid) sum-id (:id func-arg)
                                    {:ref-id pair-sum-id :is-fn true})
          bind-init (mk-binding-arg (random-uuid) sum-id (:id init-arg) {:value 0})

          fns [(mk-fn pair-sum-id :pair-sum)
               (mk-fn red-id :reduce-fn)
               (mk-fn sum-id :sum red-id)]
          args [pair-arg func-arg init-arg coll-arg bind-func bind-init]
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:pair-sum pair-sum :reduce-fn reduce-fn}} nil)
          sum-fn (get compiled sum-id)]
      (is (= 10 (sum-fn compiled {:coll [1 2 3 4]}))))))


;; ============================================================================
;; Stage 8 — MI + rename: call-site free-args translation
;;
;; Reproduces the `:route → :pair → :conj` pattern from resources/packages/
;; app/common/fns.edn. The issue: `:conj` is invoked TWICE along the chain
;; (once via `:pair-1` as a ref on `:pair.coll`, once at `:pair`'s own level
;; for the outer append). Free args propagate through ref boundaries with
;; renames at each level, and the ref callee expects the original name —
;; so call-site translation is required.
;;
;; Shape:
;;   :conj        (base)                  primaries: coll, item
;;   :conj-empty  parent :conj            binds coll=[]              (free: item)
;;   :pair-1      parent :conj-empty      renames item→item1         (free: item1)
;;   :pair        parent :conj            binds coll=:pair-1,
;;                                         renames item→item2       (free: item1, item2)
;;   :route       parent :pair            binds item2=:method-map,
;;                                         renames item1→path       (free: path, …)
;; ============================================================================

(defbase conj-fn [coll item]
  (conj coll item))


(deftest mi-rename-pair-propagation
  (testing "pair-1 + pair: free args named via rename at pair-1 level"
    (let [conj-id (random-uuid)
          coll-arg (mk-primary-arg (random-uuid) conj-id "coll")
          item-arg (mk-primary-arg (random-uuid) conj-id "item")

          ;; :conj-empty — parent :conj, coll=[]
          conj-empty-id (random-uuid)
          ce-coll (mk-binding-arg (random-uuid) conj-empty-id (:id coll-arg) {:value []})
          ;; :conj-empty propagates `:conj.item` upward as a free slot.
          ce-item-prop (mk-binding-arg (random-uuid) conj-empty-id (:id item-arg) {})

          ;; :pair-1 — parent :conj-empty, renames item→item1
          pair1-id (random-uuid)
          p1-item (mk-binding-arg (random-uuid) pair1-id (:id ce-item-prop) {:name "item1"})

          ;; :pair — parent :conj, coll=:pair-1 ref, item→item2
          pair-id (random-uuid)
          p-coll (mk-binding-arg (random-uuid) pair-id (:id coll-arg) {:ref-id pair1-id})
          p-item (mk-binding-arg (random-uuid) pair-id (:id item-arg) {:name "item2"})
          ;; :pair also propagates :pair-1's `:item1` free slot as its own free arg.
          p-item1-prop (mk-binding-arg (random-uuid) pair-id (:id p1-item) {})

          fns [(mk-fn conj-id :conj-fn)
               (mk-fn conj-empty-id :conj-empty conj-id)
               (mk-fn pair1-id :pair-1 conj-empty-id)
               (mk-fn pair-id :pair conj-id)]
          args [coll-arg item-arg ce-coll ce-item-prop p1-item p-coll p-item p-item1-prop]
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:conj-fn conj-fn}} nil)
          pair (get compiled pair-id)]
      (is (= ["a" "b"] (pair compiled {:item1 "a" :item2 "b"}))
          ":pair should build [item1, item2] — :pair-1 ref receives item1")))

  (testing "route (pair + rename item1→path): translates :path back to :item1 when calling :pair-1"
    (let [conj-id (random-uuid)
          coll-arg (mk-primary-arg (random-uuid) conj-id "coll")
          item-arg (mk-primary-arg (random-uuid) conj-id "item")

          conj-empty-id (random-uuid)
          ce-coll (mk-binding-arg (random-uuid) conj-empty-id (:id coll-arg) {:value []})
          ce-item-prop (mk-binding-arg (random-uuid) conj-empty-id (:id item-arg) {})

          pair1-id (random-uuid)
          p1-item (mk-binding-arg (random-uuid) pair1-id (:id ce-item-prop) {:name "item1"})

          pair-id (random-uuid)
          p-coll (mk-binding-arg (random-uuid) pair-id (:id coll-arg) {:ref-id pair1-id})
          p-item (mk-binding-arg (random-uuid) pair-id (:id item-arg) {:name "item2"})
          p-item1-prop (mk-binding-arg (random-uuid) pair-id (:id p1-item) {})

          ;; :method-map — trivial: a fn returning a literal map via :const-fn.
          const-id (random-uuid)
          v-arg (mk-primary-arg (random-uuid) const-id "v")
          mm-id (random-uuid)
          mm-v (mk-binding-arg (random-uuid) mm-id (:id v-arg) {:value {"m" :bound}})

          ;; :route — parent :pair, binds item2→:method-map ref, renames item1→path
          route-id (random-uuid)
          r-item2 (mk-binding-arg (random-uuid) route-id (:id p-item) {:ref-id mm-id})
          r-path (mk-binding-arg (random-uuid) route-id (:id p-item1-prop) {:name "path"})

          fns [(mk-fn conj-id :conj-fn)
               (mk-fn conj-empty-id :conj-empty conj-id)
               (mk-fn pair1-id :pair-1 conj-empty-id)
               (mk-fn pair-id :pair conj-id)
               (mk-fn const-id :const-fn)
               (mk-fn mm-id :method-map const-id)
               (mk-fn route-id :route pair-id)]
          args [coll-arg item-arg ce-coll ce-item-prop p1-item
                p-coll p-item p-item1-prop v-arg mm-v r-item2 r-path]
          compiled (c/compile-all {:fns fns :args args
                                   :base-fns {:conj-fn conj-fn :const-fn const-fn}} nil)
          route (get compiled route-id)]
      (is (= ["/h" {"m" :bound}] (route compiled {:path "/h"}))
          ":route's :path free arg must reach :pair-1 as :item1"))))
