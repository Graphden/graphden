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
    [graphden.executor.runtime :as rt]
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


;; ============================================================================
;; apply-hof-translation — pure logic (Phase 5 HOF wrap-time slot-id propagation)
;; ============================================================================
;;
;; Two regressions live in this surface:
;;
;;   - 21c27588: `if-let` skipped falsy values; a caller passing `:body nil`
;;     or `:flag false` past a HOF boundary lost its slot-id route. Fix:
;;     presence-based (`contains?`) copy gate.
;;
;;   - dcc11101: when an outer env-binding had already written a `rt/thunk`
;;     under the same ext-name, translation copied that thunk under R's
;;     slot-id; R's reader found it via slot-id, forced it, re-entered
;;     `call-with-cache` on the same ref → StackOverflow. Production-only
;;     (caught by `bb rebuild` smoke, not `bb test`). Fix: skip thunks at
;;     copy.
;;
;; `apply-hof-translation` is `defn-` so we reach it via the var.

(def ^:private apply-hof-translation
  #'graphden.executor.compile-eager/apply-hof-translation)


(deftest apply-hof-translation-empty-translation-passes-fa-through-test
  (let [fa {:a 1 :b 2}]
    (is (identical? fa (apply-hof-translation fa {})))))


(deftest apply-hof-translation-copies-value-under-slot-id-test
  (testing "an ext-name key in fa lands under its R-slot-id"
    (is (= {:body "hi" :slot-body "hi"}
           (apply-hof-translation {:body "hi"} {:slot-body :body})))))


(deftest apply-hof-translation-presence-not-truthiness-test
  (testing "false/nil values copy under the slot-id (regression for 21c27588)"
    (is (= {:flag false :slot-flag false}
           (apply-hof-translation {:flag false} {:slot-flag :flag})))
    (is (= {:body nil :slot-body nil}
           (apply-hof-translation {:body nil} {:slot-body :body})))))


(deftest apply-hof-translation-absent-source-is-noop-test
  (testing "missing ext-name key in fa → no slot-id key written"
    (is (= {:other 1} (apply-hof-translation {:other 1} {:slot-body :body})))))


(deftest apply-hof-translation-preserves-existing-slot-id-key-test
  (testing "if r-sid is already present, don't overwrite"
    (is (= {:body "outer" :slot-body "inner"}
           (apply-hof-translation {:body "outer" :slot-body "inner"}
                                  {:slot-body :body})))))


(deftest apply-hof-translation-skips-thunks-test
  (testing "thunk under ext-name is NOT copied to slot-id (regression for dcc11101)"
    (let [t (rt/thunk (fn [] (throw (ex-info "should not be forced" {}))))
          fa {:body t}
          result (apply-hof-translation fa {:slot-body :body})]
      (is (not (contains? result :slot-body))
          "thunk under :body must not appear under :slot-body — otherwise R's
           slot-id reader finds it, forces it, and the env-binding chain
           re-enters call-with-cache for the same ref → StackOverflow")
      (is (identical? t (get result :body))
          "thunk stays under its original ext-name (caller's env-binding
           still writes / reads it via the name-fallback path)"))))


(deftest apply-hof-translation-mixed-thunk-and-value-test
  (testing "thunk-skip is per-entry — a sibling plain value still copies"
    (let [t (rt/thunk (fn [] :unreachable))
          fa {:body t :flag false}
          result (apply-hof-translation fa
                                        {:slot-body :body :slot-flag :flag})]
      (is (not (contains? result :slot-body)))
      (is (false? (get result :slot-flag))))))
