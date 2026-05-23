(ns graphden.executor.compile.renames-test
  "Tests for `graphden.executor.compile.renames` — free-arg name
   translation and HOF lambda-param classification.

   `apply-renames` is pure; the rest walk the graph, so those tests
   build a real graph and `l/build-lookups` over it."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- lookups-for
  [storage]
  (l/build-lookups
    {:fns        (sp/query-entities storage :fn {})
     :slots      (sp/query-entities storage :slot {})
     :fn-slots   (sp/query-entities storage :fn-slot {})
     :bindings   (sp/query-entities storage :binding {})
     :list-items (sp/query-entities storage :binding-list-item {})}))


;; ============================================================================
;; apply-renames — pure
;; ============================================================================

(deftest apply-renames-test
  (testing "each {R-name → F-name} entry exposes F's value under R's name"
    (is (= {:x 1 :b 2} (r/apply-renames {:a 1 :b 2} {:x :a}))))

  (testing "an entry whose F-name is absent is a no-op; extra keys pass through"
    (is (= {:b 2} (r/apply-renames {:b 2} {:x :a})))
    (is (= {:a 1} (r/apply-renames {:a 1} {}))))

  (testing "multiple renames are applied together"
    (is (= {:x 1 :y 2} (r/apply-renames {:a 1 :b 2} {:x :a :y :b})))))


;; ============================================================================
;; deep-free-ext-names
;; ============================================================================

(deftest deep-free-ext-names-test
  (testing "an unbound slot surfaces as a deep-free name; a bound one does not"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "dfn-base"
                                     :slots [{:name "a" :type :int}
                                             {:name "b" :type :int}]})
              fn1 (setup/build-fn! storage
                                   {:name "dfn-1"
                                    :parent base
                                    :bindings {"a" {:value 10}}})]
          ;; a is value-bound, b is free → only b surfaces.
          (is (= [:b] (r/deep-free-ext-names (-> fn1 :fn :id)
                                             (lookups-for storage)))))
        (finally (sp/close storage)))))

  (testing "deep-free walks across a non-HOF ref into the ref-target's free args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; D exposes a free slot `inner`.
              base-d (setup/build-fn! storage
                                      {:name "dfn-base-d"
                                       :slots [{:name "inner" :type :int}]})
              d      (setup/build-fn! storage
                                      {:name "dfn-d" :parent base-d})
              ;; C binds its slot `s` to a ref of D.
              base-c (setup/build-fn! storage
                                      {:name "dfn-base-c"
                                       :slots [{:name "s" :type :int}]})
              c      (setup/build-fn! storage
                                      {:name "dfn-c"
                                       :parent base-c
                                       :bindings {"s" {:ref d}}})]
          ;; C's own slot s is ref-bound; D's `inner` bubbles up.
          (is (= [:inner] (r/deep-free-ext-names (-> c :fn :id)
                                                 (lookups-for storage)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; hof-lambda-params
;; ============================================================================

(deftest hof-lambda-params-test
  (testing "a deep-free name nobody supplies is a lambda-param of the HOF target"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; R exposes free `x`.
              base-r (setup/build-fn! storage
                                      {:name "hlp-base-r"
                                       :slots [{:name "x" :type :int}]})
              r-fn   (setup/build-fn! storage
                                      {:name "hlp-r" :parent base-r})
              ;; F is built on an unrelated base — neither owns nor supplies `x`.
              base-f (setup/build-fn! storage
                                      {:name "hlp-base-f"
                                       :slots [{:name "y" :type :int}]})
              f-fn   (setup/build-fn! storage
                                      {:name "hlp-f" :parent base-f})]
          ;; Nothing in F's world supplies x → x is a per-call lambda-param.
          ;; Pass nil slot-id/b-row — neither r-fn nor f-fn carries a
          ;; bound slot at this test boundary (we're directly probing
          ;; the helper, not exercising a real bind site). nil slot-id
          ;; falls back to the legacy heuristic (no structural shape).
          (is (= [:x] (r/hof-lambda-params (-> r-fn :fn :id)
                                           nil nil
                                           (-> f-fn :fn :id)
                                           (lookups-for storage)))))
        (finally (sp/close storage)))))

  (testing "a name a caller-relative supplies is captured, not a lambda-param"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "hlp2-base"
                                     :slots [{:name "x" :type :int}]})
              r-fn (setup/build-fn! storage
                                    {:name "hlp2-r" :parent base})
              f-fn (setup/build-fn! storage
                                    {:name "hlp2-f"
                                     :parent base
                                     :bindings {"x" {:value 99}}})]
          ;; f binds x → x flows in from the closure → no lambda-params.
          (is (= [] (r/hof-lambda-params (-> r-fn :fn :id)
                                         nil nil
                                         (-> f-fn :fn :id)
                                         (lookups-for storage)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; HOF :is-fn boundary — pins both `deep-free-ext-names` and
;; `find-slot-id-in-tree` :ref guard branches (line 55/96 in renames.clj).
;;
;; Without this test the walker only ever sees plain `:ref` bindings where
;; `:is-fn` is false, so the `(when-not (:is-fn bnd) …)` guard's truthy
;; arm is never hit and cloverage flags the line as half-covered.
;; ============================================================================

(deftest hof-ref-is-a-boundary-test
  (testing ":is-fn ref binding stops the walker from descending into the target"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; Inner fn `T` exposes a free slot `:inner-free`.
              base-t  (setup/create-base-fn! storage "bdy-base-t")
              s-free  (setup/create-slot! storage "inner-free" :int)
              _       (setup/attach-slot! storage (:id base-t) (:id s-free) 0)
              t-fn    (setup/create-composed-fn! storage "bdy-t" (:id base-t))
              ;; Outer fn `F` has a slot typed as the `:fn` primitive
              ;; (HOF marker) and binds it to a ref of T. The binding's
              ;; `:is-fn` flag becomes true → `deep-free-ext-names`'s
              ;; `:ref` branch hits the BOUNDARY arm and skips T's frees.
              base-f  (setup/create-base-fn! storage "bdy-base-f")
              s-callee (setup/create-slot! storage "callee" :fn)
              _       (setup/attach-slot! storage (:id base-f) (:id s-callee) 0)
              f-fn    (setup/create-composed-fn! storage "bdy-f" (:id base-f))
              _       (setup/bind-ref! storage (:id f-fn) (:id s-callee) (:id t-fn))]
          (testing "T's free args do NOT bubble up through the HOF boundary"
            (is (= [] (r/deep-free-ext-names (:id f-fn) (lookups-for storage)))
                "the :is-fn=true ref guard suppresses recursion into T"))
          (testing "find-slot-id-in-tree (via hof-lambda-params) honors the same guard"
            ;; hof-lambda-params runs find-slot-id-in-tree against every
            ;; deep-free name of an outer HOF-target. Nothing in F's tree
            ;; supplies `inner-free`, so it becomes a lambda-param of T
            ;; when called from F.
            (is (= [:inner-free]
                   (r/hof-lambda-params (:id t-fn) nil nil (:id f-fn)
                                        (lookups-for storage))))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Seq-binding deep-free emission — pins the `:seq` arm of
;; `deep-free-ext-names` (lines 57-73). The walker treats a list-item's
;; literal `{:as :name}` map as a positional rename that re-exposes
;; `:name` as a free arg of the binding's owner.
;; ============================================================================

(deftest deep-free-emits-positional-renames-from-seq-items-test
  (testing "seq-binding with `{:as :outer-name}` items exposes those names as free"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; F's `items` slot is a list — bind with :list-append true
              ;; so classify-slot lands on the :seq branch.
              base    (setup/create-base-fn! storage "seq-base")
              s-list  (setup/create-slot! storage "items" :int)
              _       (setup/attach-slot! storage (:id base) (:id s-list) 0)
              f-fn    (setup/create-composed-fn! storage "seq-f" (:id base))
              list-bn (sp/create-entity storage :binding
                                        {:fn-id (:id f-fn)
                                         :slot-id (:id s-list)
                                         :list-append true
                                         :override-kind :fixed})]
          ;; Two list items, both literal maps with `:as` → each re-
          ;; exposes its `:as` keyword as a deep-free name of F.
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 0
                             :value {:as "alpha"} :literal nil})
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 1
                             :value {:as "beta"}  :literal nil})
          ;; A third item with `:literal true` MUST be skipped — the
          ;; predicate is `(not (:literal item))`.
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 2
                             :value {:as "skip-me"} :literal true})
          (is (= [:alpha :beta]
                 (r/deep-free-ext-names (:id f-fn) (lookups-for storage)))
              "literal :as maps surface; :literal=true items are excluded"))
        (finally (sp/close storage))))))
