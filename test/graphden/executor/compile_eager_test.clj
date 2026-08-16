(ns ^:serial graphden.executor.compile-eager-test
  "Tests for compile-eager's caching machinery — both the process-wide
   `compile-all` LRU (so sister callers reuse the same compiled
   closure map) and the per-execute DRY memo (so a fn-def that ref's
   the same child twice fires it once).

   `^:serial`: `compile-all-cache-hit-returns-identical-map-test`
   asserts `identical?` across two `compile-all` calls over the SAME
   process-wide LRU. A concurrent NS whose graph edit lands a
   compile-cache invalidation between the two calls evicts the entry,
   so the second call recompiles — a false failure. Same reason its
   sibling `compile-runtime-test` is pinned serial; running this NS
   before the parallel pool starts keeps another thread from mutating
   the shared cache mid-test.

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
    [graphden.executor.registry.core :as registry-core]
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
    (let [storage (setup/create-test-storage)]
      (try
        (let [lookups (build-trivial-lookups storage)
              ;; The FIRST compile over a graph records its rich-types as
              ;; a side effect (`record-rich-types!`), which shifts the
              ;; ambient snapshot that `compile-all-cache-key` hashes. If
              ;; a prior test left the registry so these types aren't
              ;; recorded yet, an un-warmed r1 keys off the pre-record
              ;; snapshot and r2 off the post-record one → a spurious
              ;; miss. Warm once to settle the (idempotent) record, THEN
              ;; reset the cache, so the measured pair both key off the
              ;; stable snapshot and genuinely hit.
              _warm (ce/compile-all lookups)
              _ (ce/reset-compile-all-cache!)
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


(deftest defer-handler-call-cache-disables-the-shared-memo-test
  ;; Route-collection routers (the tenancy control plane, incl.
  ;; `/api/my-tokens/list`) are built ONCE at boot via `execute-by-name`;
  ;; their graph-executed `:fn` handlers CAPTURE that build execute's ctx.
  ;; If the ctx carried a shared `::call-cache`, every later request through
  ;; such a handler would reuse it — freezing any no-free-arg child ref to
  ;; its first result and serving that to every principal (a cross-principal
  ;; data leak: one account's token list shown to all). `defer-handler-call-
  ;; cache` marks the build ctx so `run` primes NO shared cache and
  ;; PROPAGATES the flag to the captured handler ctx, so each later request
  ;; re-evaluates. Pinned here by the per-execute memo being OFF under the
  ;; flag: a child ref'd in two sibling slots fires TWICE, not once (the
  ;; control assertion is the normal-execute memo firing once — same setup
  ;; as `dry-memo-shares-result-across-sibling-refs-test`).
  (testing "under defer-handler-call-cache the shared per-execute memo is off"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:defer-counter {:args {:x :any}
                                                    :return-type :any
                                                    :impl (setup/fn-impl
                                                            [x]
                                                            (swap! call-count inc)
                                                            x)}}
                                   {:defer-add {:args {:a :int :b :int}
                                                :return-type :int
                                                :impl (setup/fn-impl [a b] (+ a b))}}])
        (fn-composition/sync-fns-to-storage!
          storage
          [{:name :defer-value :parent :defer-counter :args {:x 7}}
           {:name :defer-doubler :parent :defer-add
            :args {:a :defer-value :b :defer-value}}])
        (let [doubler (first (sp/query-entities storage :fn
                                                {:name "defer-doubler"}))
              ctx (exec/create-context {:storage storage})
              ;; control: a normal execute shares the child across siblings
              _ (reset! call-count 0)
              normal (exec/execute ctx (:id doubler) nil)
              normal-count @call-count
              ;; under the flag: no shared cache is primed → each ref fires
              _ (reset! call-count 0)
              deferred (exec/execute (cr/defer-handler-call-cache ctx)
                                     (:id doubler) nil)]
          (is (= 14 normal deferred) "same result with or without the flag")
          (is (= 1 normal-count)
              "control: normal execute memoises the shared child (fires once)")
          (is (= 2 @call-count)
              "defer-handler-call-cache disables the shared memo — each ref re-evaluates"))
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


;; ============================================================================
;; compile-all cache key — the rich-types dimension (8cbd2c6f)
;; ============================================================================

(deftest cache-key-discriminates-on-ambient-rich-types-test
  ;; The classcast class this prevents: `produces-callable?` (fed by the
  ;; swept `:return-type` entries) drives HOF-wrap decisions, so a caller
  ;; compiling under swept types must NOT be served a sibling\'s compile
  ;; made over the identical graph rows under unswept types. Guarded at
  ;; the integration level by the golden-clone suites that surfaced the
  ;; original 17 errors; this pins the contract directly on the key fn.
  (let [cache-key #'ce/compile-all-cache-key
        lookups {:fn-map {} :slot-map {} :fn-slots-by-fn {}
                 :bindings-by-fn {} :items-by-binding {} :base-fns {}}
        k-under (fn [rich-types]
                  (binding [registry-core/*rich-types-override* (atom rich-types)]
                    (cache-key lookups)))]
    (testing "identical graph, different ambient rich-types -> different keys"
      (is (not= (k-under {})
                (k-under {:some-fn-id {:return-type :int}}))))
    (testing "value-equal snapshots share a key even as distinct objects"
      (is (= (k-under {:a {:return-type :text}})
             (k-under {:a {:return-type :text}}))
          "sharing is by VALUE - equal swept snapshots reuse one compile"))
    (testing "the graph shape still discriminates as before"
      (binding [registry-core/*rich-types-override* (atom {})]
        (is (not= (cache-key lookups)
                  (cache-key (assoc lookups :base-fns {:add (fn [_ _])}))))))))


(deftest always-fresh-set-is-a-union-not-a-clobber-test
  ;; Primes run per-ctx (per branch / shard), each from its OWN graph
  ;; view. The old reset! semantics meant branch A's rebuild dropped
  ;; branch B's :time fns from the set until B's next rebuild — and an
  ;; optimistic rebuild losing the unchanged? race still clobbered the
  ;; set from its stale snapshot. Union is monotone and sound.
  (binding [ce/*always-fresh-fn-ids* (atom #{})]
    (let [id-a (random-uuid) id-b (random-uuid)]
      (ce/set-always-fresh-fn-ids! #{id-a})
      (ce/set-always-fresh-fn-ids! #{id-b})
      (is (= #{id-a id-b} @ce/*always-fresh-fn-ids*)
          "the second prime (another ctx's view) must not drop the first's ids"))))
