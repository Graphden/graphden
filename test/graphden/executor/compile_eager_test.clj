(ns graphden.executor.compile-eager-test
  "Tests for compile-eager's caching machinery — both the process-wide
   `compile-all` LRU (so sister callers reuse the same compiled
   closure map) and the per-execute DRY memo (so a fn-def that ref's
   the same child twice fires it once).

   The DRY-memo wire test in `integration_test/full-execution-with-
   fn-usages-test` asserts the high-level promise (`call-count == 1`
   for a fn ref'd twice in one execute). These tests pin down the
   lower-level levers — `set-always-fresh-fn-ids!`, `compile-all`
   cache hit, `reset-compile-all-cache!` — so a future refactor that
   removes them breaks here visibly rather than re-introducing the
   regression they prevent."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry :as registry-base]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; compile-all LRU cache
;; ============================================================================

(defn- build-trivial-lookups
  "Tiny lookups that compile to a single base-fn registry — enough
   to drive `compile-all` end-to-end without spinning up a full
   package set."
  [storage]
  (exec/register-base-fn! :ce-pure-const (setup/fn-impl [x] x))
  (let [base (setup/create-base-fn! storage "ce-pure-const")
        slot (setup/create-slot! storage "x" :any)
        _ (setup/attach-slot! storage (:id base) (:id slot) 0)
        fns (sp/query-entities storage :fn {})
        slots (sp/query-entities storage :slot {})
        fn-slots (sp/query-entities storage :fn-slot {})
        bindings (sp/query-entities storage :binding {})
        list-items (sp/query-entities storage :binding-list-item {})]
    (assoc (l/build-lookups
             {:fns fns :slots slots :fn-slots fn-slots
              :bindings bindings :list-items list-items})
           :base-fns (registry-base/get-default-registry))))


(deftest compile-all-cache-hit-returns-identical-map-test
  (testing "two compile-all calls over the same graph return the SAME closure map"
    ;; Cache hits return the cached value identity-equal. If the LRU
    ;; gets refactored to a deep-copy or to invalidate on every read,
    ;; this test catches it.
    (ce/reset-compile-all-cache!)
    (let [storage (setup/create-test-storage)]
      (try
        (let [lookups (build-trivial-lookups storage)
              r1 (ce/compile-all lookups)
              r2 (ce/compile-all lookups)]
          (is (= r1 r2) "equal")
          (is (identical? r1 r2) "identity-equal — cache hit, no recompile"))
        (finally
          (sp/close storage))))))


(deftest reset-compile-all-cache!-drops-entries-test
  (testing "after reset!, the next compile-all returns a fresh map"
    (ce/reset-compile-all-cache!)
    (let [storage (setup/create-test-storage)]
      (try
        (let [lookups (build-trivial-lookups storage)
              r1 (ce/compile-all lookups)
              _ (ce/reset-compile-all-cache!)
              r2 (ce/compile-all lookups)]
          ;; Map values are freshly-allocated Clojure closures; map
          ;; `=` compares values, and closure `=` is identity, so two
          ;; recompiles yield maps that aren't `=`. The fn-id set
          ;; (keys) must still match — same graph compiles to the
          ;; same closure population.
          (is (= (set (keys r1)) (set (keys r2))) "same fn-id population")
          (is (not (identical? r1 r2))
              "identity-different — reset! forced a recompile"))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; Per-execute DRY memo + always-fresh bypass
;; ============================================================================

(deftest dry-memo-shares-result-across-sibling-refs-test
  ;; Mirrors `integration_test/full-execution-with-fn-usages-test` —
  ;; co-located here because the contract belongs to compile-eager's
  ;; memo, not the integration layer. If this regresses, side-effecting
  ;; handlers fire twice; the integration test would surface it as a
  ;; unique-violation rather than a clear call-count delta.
  (testing "a fn ref'd in two sibling slots fires its impl once per execute"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:dry-counter {:args {:x :any}
                                                  :return-type :any
                                                  :impl (setup/fn-impl
                                                          [x]
                                                          (swap! call-count inc)
                                                          x)}}
                                   {:dry-add {:args {:a :int :b :int}
                                              :return-type :int
                                              :impl (setup/fn-impl [a b] (+ a b))}}])
        (fn-composition/sync-fns-to-storage!
          storage
          [{:name :dry-value :parent :dry-counter :args {:x 7}}
           {:name :dry-doubler :parent :dry-add
            :args {:a :dry-value :b :dry-value}}])
        (let [doubler (first (sp/query-entities storage :fn
                                                {:name "dry-doubler"}))
              ctx (exec/create-context {:storage storage})
              result (exec/execute ctx (:id doubler) nil)]
          (is (= 14 result) "both sibling refs read the same memoised value")
          (is (= 1 @call-count)
              "exactly one impl invocation per execute, shared between siblings"))
        (finally (sp/close storage))))))


(deftest always-fresh-bypasses-the-dry-memo-test
  ;; `:time` and `:random` impls must fire fresh on every read — two
  ;; clock reads in one request must produce different timestamps.
  ;; `compile_runtime`'s `prime-always-fresh!` scans rich-types for
  ;; `:effects` ∩ `#{:time :random}` after every rebuild and pushes
  ;; the resulting fn-id set into compile-eager's `set-always-fresh-
  ;; fn-ids!`. The DRY memo's `call-with-cache` short-circuits any
  ;; fn-id in that set so siblings refs see fresh values.
  (testing "a `:time`-effecting fn ref'd twice fires both times"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:fresh-tick {:args {:x :any}
                                                 :return-type :any
                                                 ;; Declared `:time` effect →
                                                 ;; prime-always-fresh! picks
                                                 ;; this fn up at rebuild!
                                                 ;; time and registers it as
                                                 ;; always-fresh.
                                                 :effects #{:time}
                                                 :impl (setup/fn-impl
                                                         [x]
                                                         (swap! call-count inc)
                                                         x)}}
                                   {:fresh-add {:args {:a :int :b :int}
                                                :return-type :int
                                                :impl (setup/fn-impl [a b] (+ a b))}}])
        (fn-composition/sync-fns-to-storage!
          storage
          [{:name :fresh-doubler :parent :fresh-add
            :args {:a :fresh-tick :b :fresh-tick}}])
        (let [doubler (first (sp/query-entities storage :fn
                                                {:name "fresh-doubler"}))
              ctx (exec/create-context {:storage storage})]
          (cr/rebuild! ctx)
          (let [_result (exec/execute ctx (:id doubler) {:x 11})]
            (is (= 2 @call-count)
                "always-fresh fn fires once per ref site, not once per execute")))
        (finally
          (sp/close storage)
          (ce/set-always-fresh-fn-ids! #{}))))))


(deftest dry-memo-isolated-per-execute-test
  ;; The memo lives in `ctx` under `::call-cache` and is installed
  ;; fresh by the top-level closure on every execute. Two consecutive
  ;; `exec/execute` calls must therefore each see their OWN counter
  ;; increment — the cache from call 1 cannot leak into call 2.
  (testing "the call-cache is per-execute, not process-wide"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:iso-counter {:args {:x :any}
                                                  :return-type :any
                                                  :impl (setup/fn-impl
                                                          [x]
                                                          (swap! call-count inc)
                                                          x)}}])
        (fn-composition/sync-fns-to-storage!
          storage
          [{:name :iso-value :parent :iso-counter :args {:x 3}}])
        (let [value-row (first (sp/query-entities storage :fn
                                                  {:name "iso-value"}))
              ctx (exec/create-context {:storage storage})]
          (exec/execute ctx (:id value-row) nil)
          (exec/execute ctx (:id value-row) nil)
          (exec/execute ctx (:id value-row) nil)
          (is (= 3 @call-count)
              "every execute installs a fresh memo — no cross-execute sharing"))
        (finally (sp/close storage))))))
