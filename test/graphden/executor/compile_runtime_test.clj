(ns graphden.executor.compile-runtime-test
  "Parallel-safe: the read-graph call counter goes through the
   thread-local `cr/*impl-override*` seam (`binding`) instead of
   `with-redefs` — a root rebind was process-global and pinned this NS
   `^:serial` (a concurrent NS's rebuild/delta landing in the window
   incremented the counter and flaked the zero-reads assertion;
   serial-reduction batch 4).

   Tests for the public compile-runtime API — the surface `exec/` delegates to.

   Focuses on branches that core-level tests don't always hit directly:
   - `execute` with a callable `fn-id` (legacy HOF pattern)
   - `execute-by-name` with string vs keyword storage name codec
   - `make-single-arg-callable` fn-pass-through and free-arg shape dispatch
   - `registry` / `rebuild!` lifecycle"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.type-check :as typecheck]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.compile.bindings]
    [graphden.executor.compile.lookups]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.types.core :as types]))


(use-fixtures :once (setup/create-container-fixture))


(deftest org-alias-snapshot-isolates-same-named-types-per-org
  ;; §4 Risk-2 fix: two orgs each define a type `Foo` differently. The global
  ;; alias registry collapses both to one `:Foo` (last-write-wins), but the
  ;; per-org slice keeps them apart, and `org-alias-snapshot` hands each org its
  ;; OWN `Foo` plus the public/untenanted aliases — never the other org's.
  (binding [cr/*per-org-aliases-override* (atom {})
            types/*type-aliases-override* (atom {})]
    (cr/register-type-aliases-from-db!
      {:fns [{:id "int" :name "int"}
             {:id "text" :name "text"}
             {:id "fa" :name "Foo" :element-fn-id "int" :org-id "A"}
             {:id "fb" :name "Foo" :element-fn-id "text" :org-id "B"}
             {:id "bar" :name "Bar" :element-fn-id "int"}] ; org-id nil → public/untenanted
       :slots []
       :fn-slots []})
    (let [view-a (cr/org-alias-snapshot "public" "A")
          view-b (cr/org-alias-snapshot "public" "B")]
      (testing "each org sees ITS OWN Foo, not the other's"
        (is (= [:list :int] (:Foo view-a)))
        (is (= [:list :text] (:Foo view-b))))
      (testing "the untenanted / public alias is visible to every org"
        (is (= [:list :int] (:Bar view-a)))
        (is (= [:list :int] (:Bar view-b)))))))


(deftest with-org-alias-view-filters-resolution-for-a-tenant
  ;; The shared helper (used by the type-check guards AND the read-display paths
  ;; via the request-scope) makes `resolve-alias` org-filtered for a tenant and
  ;; leaves platform/public on the global registry.
  (binding [cr/*per-org-aliases-override* (atom {"A" {:Foo [:list :int]}})
            types/*type-aliases-override* (atom {})] ; global: no Foo
    (testing "a tenant resolves its OWN per-org alias"
      (tc/with-org "A"
                   (typecheck/with-org-alias-view*
                     (fn [] (is (= [:list :int] (types/resolve-alias :Foo)))))))
    (testing "platform / public is NOT given the tenant's alias"
      (tc/with-org tc/public-org
                   (typecheck/with-org-alias-view*
                     (fn [] (is (not= [:list :int] (types/resolve-alias :Foo)))))))))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; `registry` / `rebuild!` lifecycle
;; ============================================================================

(deftest registry-auto-builds-on-first-access
  (testing "a fresh context has nil registry; accessing builds it on demand"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
        (setup/setup-add-function! storage)
        (let [ctx (exec/create-context {:storage storage})]
          (is (nil? @(:compiled-registry ctx)) "fresh — nothing compiled yet")
          (let [reg (cr/registry ctx)]
            (is (map? reg))
            (is (pos? (count reg)) "registry contains compiled fns"))
          (is (some? @(:compiled-registry ctx)) "cached after first access"))
        (finally
          (sp/close storage))))))


(deftest registry-nil-without-atom
  (testing "context without `:compiled-registry` atom returns nil"
    (is (nil? (cr/registry {:storage :mock})))))


(deftest rebuild-replaces-current-registry
  (testing "manual `rebuild!` re-reads storage and installs into ctx"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
        (setup/setup-add-function! storage)
        (let [ctx (exec/create-context {:storage storage})
              reg-1 (cr/registry ctx)
              reg-2 (cr/rebuild! ctx)]
          (is (= (set (keys reg-1)) (set (keys reg-2)))
              "same fns in both builds")
          ;; compile-eager closures are ctx-INDEPENDENT and cached
          ;; by graph-shape (compile-all LRU), so an unchanged graph
          ;; produces the SAME closure map across rebuilds —
          ;; identity-equality is the cache hit signal, not a bug.
          (is (= reg-2 @(:compiled-registry ctx))
              "rebuild's return value matches what landed in the atom"))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `execute` — fn? branch (callable fn-id)
;; ============================================================================

(deftest execute-with-callable-fn-id
  (testing "callable fn-id with single-entry named-args: unwrap value, invoke"
    (let [called-with (atom nil)
          callable (fn [v] (reset! called-with v) (str "got:" v))]
      (is (= "got:hello" (cr/execute {} callable {:x "hello"})))
      (is (= "hello" @called-with)
          "single value unwrapped from map, passed to callable")))

  (testing "callable fn-id with empty named-args: invoke with the empty map"
    (let [called-with (atom :sentinel)
          callable (fn [v] (reset! called-with v) v)]
      (cr/execute {} callable {})
      (is (= {} @called-with))))

  (testing "callable fn-id with multi-entry named-args: pass the whole map"
    (let [called-with (atom nil)
          callable (fn [v] (reset! called-with v) v)]
      (cr/execute {} callable {:a 1 :b 2})
      (is (= {:a 1 :b 2} @called-with)))))


(deftest execute-throws-when-fn-id-missing
  (testing "non-callable, unknown fn-id throws `:fn-not-found`"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})
              bogus-id (random-uuid)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function not found"
                (cr/execute ctx bogus-id {}))))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `execute-by-name` — storage-name codec tolerance
;; ============================================================================

(deftest execute-by-name-finds-by-string
  (testing "string fn-name matches a storage entry"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-val (fn [_ _] 77))
        (setup/create-base-fn! storage "const-val" :int)
        (let [ctx (exec/create-context {:storage storage})]
          (is (= 77 (cr/execute-by-name ctx "const-val" nil))))
        (finally
          (sp/close storage))))))


(deftest execute-by-name-missing-fn-throws
  (testing "unknown fn-name throws `:fn-not-found`"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function 'nope' not found"
                (cr/execute-by-name ctx "nope" nil))))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `make-single-arg-callable`
;; ============================================================================

(deftest single-arg-callable-passes-fn-through
  (testing "when `fn-id` is already a fn, return it unchanged"
    (let [f (fn [x] (* 2 x))]
      (is (identical? f (cr/make-single-arg-callable {} f))))))


(deftest single-arg-callable-missing-fn-throws
  (testing "UUID that's not in the compiled registry throws"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function not found"
                (cr/make-single-arg-callable ctx (random-uuid)))))
        (finally
          (sp/close storage))))))


(deftest single-arg-callable-one-free-arg
  (testing "single-free-arg target: callable routes item under that arg name"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :double (setup/fn-impl [x] (* 2 x)))
        (let [base-fn (setup/create-base-fn! storage "double" :int)
              _ (setup/create-arg! storage (:id base-fn)
                                   {:name "x" :type :int :required true})
              composed (setup/create-composed-fn! storage "my-double" (:id base-fn))
              ctx (exec/create-context {:storage storage})
              call (cr/make-single-arg-callable ctx (:id composed))]
          (is (= 10 (call 5))))
        (finally
          (sp/close storage))))))


(deftest single-arg-callable-zero-free-args-variadic
  (testing "0-free-arg target: callable accepts and ignores input"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-zero (fn [_ _] 0))
        (let [base-fn (setup/create-base-fn! storage "const-zero" :int)
              composed (setup/create-composed-fn! storage "z" (:id base-fn))
              ctx (exec/create-context {:storage storage})
              call (cr/make-single-arg-callable ctx (:id composed))]
          (is (zero? (call :whatever)))
          (is (zero? (call :something-else-entirely))))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; binding-level :required narrowing — descendant flips inherited optional
;; to required, verified via the executor's lookups
;; ============================================================================

(deftest binding-required-narrowing-flows-into-effective-required
  (testing "child's `:required true` binding narrows parent's optional slot"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :id-fn (setup/fn-impl [x] x))
        (let [base-fn (setup/create-base-fn! storage "id-fn" :int)
              ;; Slot defaults to :required false on the base-fn.
              slot (sp/create-entity storage :slot
                                     {:name "x"
                                      :type-fn-id (get setup/primitive-fn-ids :int)
                                      :required false})
              _ (setup/attach-slot! storage (:id base-fn) (:id slot) 0)
              ;; Plain composed — inherits the optional slot, no
              ;; narrowing. classify-slot should report required=false.
              composed (setup/create-composed-fn! storage "passthrough" (:id base-fn))
              ;; Narrowing composed — :required true binding lifts it.
              narrowed (setup/create-composed-fn! storage "must-have-x" (:id base-fn))
              _ (sp/create-entity storage :binding
                                  {:fn-id (:id narrowed)
                                   :slot-id (:id slot)
                                   :required true})
              ctx (exec/create-context {:storage storage})
              storage' (:storage ctx)
              graph (#'graphden.executor.compile-runtime/read-graph storage')
              lookups (#'graphden.executor.compile.lookups/build-lookups graph)
              passthrough-bindings (#'graphden.executor.compile.bindings/collect-bindings
                                    (:id composed) lookups)
              narrowed-bindings (#'graphden.executor.compile.bindings/collect-bindings
                                 (:id narrowed) lookups)]
          (is (false? (:required (first passthrough-bindings)))
              "no narrowing → free-arg keeps slot's :required false")
          (is (true? (:required (first narrowed-bindings)))
              "binding's :required true overrides slot's :required false"))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; concurrent delta-recompile — write-discipline regression
;; ============================================================================
;;
;; CRUD impls call `invalidate-graph-cache!` synchronously on the http-kit
;; worker thread (see `crud/entities.clj`'s `notify-after-write!`), so two
;; concurrent client requests CAN land in `delta-recompile!` against the
;; same `:compiled-registry` atom. Previously the holder write was
;; `reset!` over a read-modify-write computed outside the swap — two
;; threads could both read `@holder`, both compute their merged map, and
;; the last `reset!` would silently drop the other's `new-entries`,
;; causing `/api/execute` against a just-modified fn to return the
;; pre-mutation closure until something else invalidated it again. The
;; fix moves the read-modify-write inside `swap!` (CAS-retry) — same
;; pattern the `:compiled-templates` swap below already uses.
;;
;; The race window in the real code is microseconds (one `into` + one
;; `merge` between deref and write), so even N=64 threads on the latch
;; rarely overlap reliably enough to lose updates — that means this test
;; functions as a CONCURRENT-EXERCISE smoke (no exceptions, result
;; complete) rather than a strict race detector. The actual write-
;; discipline guarantee lives in the `swap!` call in
;; `compile-runtime/delta-recompile!`; reverting it to `reset!` is the
;; failure mode this test is here to remind future reviewers about
;; (the comment in the source explains why).

(deftest concurrent-delta-recompile-preserves-all-entries
  (testing "N parallel delta-recompiles each with a distinct seed all land in the registry"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
        (let [;; One :add base-fn + N composed children — each child has
              ;; the same dependency shape so the delta-recompile blast
              ;; for any one of them is small (just itself).
              base-fn (setup/create-base-fn! storage "add" :int)
              slot-a (setup/create-slot! storage "a" :int)
              slot-b (setup/create-slot! storage "b" :int)
              _ (setup/attach-slot! storage (:id base-fn) (:id slot-a) 0)
              _ (setup/attach-slot! storage (:id base-fn) (:id slot-b) 1)
              n 64
              composed-ids (mapv (fn [i]
                                   (:id (setup/create-composed-fn!
                                          storage
                                          (str "race-child-" i)
                                          (:id base-fn))))
                                 (range n))
              ctx (exec/create-context {:storage storage})
              _ (cr/rebuild! ctx)
              delta! (fn [fid]
                       (#'graphden.executor.compile-runtime/delta-recompile!
                        ctx #{fid}))
              run-round
              (fn []
                ;; One round: N workers race through the latch, each
                ;; calling delta-recompile with a distinct seed. Returns
                ;; the set of fn-ids missing from the registry after
                ;; everyone finishes — empty means no lost update.
                (let [start (java.util.concurrent.CountDownLatch. 1)
                      done (java.util.concurrent.CountDownLatch. n)
                      errors (atom [])
                      workers (mapv (fn [fid]
                                      (Thread.
                                        ^Runnable
                                        (fn []
                                          (try
                                            (java.util.concurrent.CountDownLatch/.await start)
                                            (delta! fid)
                                            (catch Exception t
                                              (swap! errors conj t))
                                            (finally
                                              (java.util.concurrent.CountDownLatch/.countDown done))))))
                                    composed-ids)]
                  (doseq [t workers] (Thread/.start t))
                  (java.util.concurrent.CountDownLatch/.countDown start)
                  (when-not (java.util.concurrent.CountDownLatch/.await
                              done 30 java.util.concurrent.TimeUnit/SECONDS)
                    (throw (ex-info "delta-recompile workers timed out" {})))
                  {:errors @errors
                   :missing (set (remove (set (keys @(:compiled-registry ctx)))
                                         composed-ids))}))
              ;; Multiple rounds: with `reset!`-based read-modify-write
              ;; the race surfaces probabilistically (depends on thread
              ;; scheduling — a couple of overlaps per round is typical,
              ;; but can be zero). Running 5 rounds accumulates the
              ;; chance under the bug while the fixed code passes every
              ;; round trivially.
              rounds 5
              results (vec (repeatedly rounds run-round))
              total-errors (mapcat :errors results)
              total-missing (reduce into #{} (map :missing results))]
          (is (empty? total-errors)
              (str "no worker threw across " rounds " rounds; got "
                   (count total-errors) " exceptions"))
          (is (empty? total-missing)
              (str "every concurrently-recompiled fn-id is present in "
                   "the registry across " rounds " rounds; lost "
                   (count total-missing) " of " n
                   " distinct fn-ids under the race")))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; Phase 2 — public-API translator (`translate-named-args`)
;; ============================================================================

(deftest translate-named-args-no-op-empty-test
  (testing "nil and empty args short-circuit unchanged"
    (is (nil? (cr/translate-named-args :fn-id nil {})))
    (is (= {} (cr/translate-named-args :fn-id {} {})))))


(deftest translate-named-args-emits-dual-keys-test
  (testing "single walker entry → write under BOTH ext-name and slot-id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "txa-base"
                                     :slots [{:name "x" :type :int}]})
              composed (setup/build-fn! storage
                                        {:name "txa-c" :parent base})
              graph (#'graphden.executor.compile-runtime/read-graph storage)
              lookups (#'graphden.executor.compile.lookups/build-lookups graph)
              translated (cr/translate-named-args (-> composed :fn :id)
                                                  {:x 42} lookups)
              x-slot-id (-> base :slots (get "x") :id)]
          (is (= 42 (get translated :x)) "name key preserved")
          (is (= 42 (get translated x-slot-id)) "slot-id key added")
          (is (= 2 (count translated))))
        (finally (sp/close storage))))))


(deftest translate-named-args-unknown-name-passes-through-test
  (testing "names not in walker entries pass through unchanged"
    ;; `execute` is lenient — `execute-with-named-args` upstream does
    ;; the strict unknown-name validation. Tests calling `execute`
    ;; directly with extra keys must continue to work.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "txu-base"
                                     :slots [{:name "x" :type :int}]})
              composed (setup/build-fn! storage
                                        {:name "txu-c" :parent base})
              graph (#'graphden.executor.compile-runtime/read-graph storage)
              lookups (#'graphden.executor.compile.lookups/build-lookups graph)
              translated (cr/translate-named-args (-> composed :fn :id)
                                                  {:nonsense 999} lookups)]
          (is (= {:nonsense 999} translated)
              "unknown :nonsense passes through with no slot-id companion"))
        (finally (sp/close storage))))))


(deftest translate-named-args-multi-slot-writes-all-test
  (testing "name reaching multiple chain-leaf slots writes value under each slot-id"
    ;; This is NOT a real collision — most fn-graphs have a single
    ;; caller-name reaching multiple inner consumers (every `:get`
    ;; reading the same name contributes its own root-slot id). The
    ;; translator writes under EVERY matching slot-id so each inner
    ;; consumer's chain-leaf-keyed read in Phase 4 finds the value.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-g (setup/build-fn! storage
                                      {:name "txmulti-base-g"
                                       :slots [{:name "x" :type :int}]})
              fn-g   (setup/build-fn! storage
                                      {:name "txmulti-g" :parent base-g})
              base-f (setup/build-fn! storage
                                      {:name "txmulti-base-f"
                                       :slots [{:name "x" :type :int}
                                               {:name "y" :type :int}]})
              fn-f   (setup/build-fn! storage
                                      {:name "txmulti-f"
                                       :parent base-f
                                       :bindings {"y" {:ref fn-g}}})
              graph (#'graphden.executor.compile-runtime/read-graph storage)
              lookups (#'graphden.executor.compile.lookups/build-lookups graph)
              translated (cr/translate-named-args (-> fn-f :fn :id)
                                                  {:x 1} lookups)
              sid-f-x (-> base-f :slots (get "x") :id)
              sid-g-x (-> base-g :slots (get "x") :id)]
          (is (= 1 (get translated :x)) "ext-name :x preserved")
          (is (= 1 (get translated sid-f-x)) "F's :x slot-id carries value")
          (is (= 1 (get translated sid-g-x)) "G's :x slot-id also carries value")
          (is (= 3 (count translated))
              "one name key + two slot-id keys"))
        (finally (sp/close storage))))))


;; ── delta-recompile reuses the graph cache instead of re-reading Postgres ────
;;
;; `invalidate-graph-cache!` splices `:graph-cache` immediately before calling
;; `delta-recompile!`, and that cache is the same shape delta-recompile needs.
;; Re-reading the whole graph out of storage to recompile a handful of fns was
;; the single most expensive thing on the write path: measured at 4137 fns, a
;; `:fn` create spent 477 ms of its 918 ms in that one read, for a blast radius
;; of one. This pins the reuse: with a warm, equivalent cache, delta-recompile
;; must not call `read-graph` at all — and must still produce the right closure.
(deftest delta-recompile-reuses-graph-cache-test
  (let [storage (setup/create-test-storage)]
    (try
      (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
      (let [base-fn (setup/create-base-fn! storage "add" :int)
            slot-a (setup/create-slot! storage "a" :int)
            slot-b (setup/create-slot! storage "b" :int)
            _ (setup/attach-slot! storage (:id base-fn) (:id slot-a) 0)
            _ (setup/attach-slot! storage (:id base-fn) (:id slot-b) 1)
            child-id (:id (setup/create-composed-fn! storage "cache-child" (:id base-fn)))
            ctx (exec/create-context {:storage storage})
            _ (cr/rebuild! ctx)
            ;; Warm the cache with exactly what read-graph would have returned —
            ;; the same handle, no shard filter, so the reuse guard admits it.
            _ (ectx/fill-graph-cache! ctx (#'graphden.executor.compile-runtime/read-graph storage))
            reads (atom 0)
            ;; The root fn value — its body re-checks `*impl-override*` on
            ;; entry, so the counting stub must re-bind the seam to nil
            ;; around the delegate call or it would recurse into itself.
            orig @#'graphden.executor.compile-runtime/read-graph]
        (testing "a warm, equivalent cache means zero storage reads"
          (binding [cr/*impl-override*
                    {:read-graph (fn [& args]
                                   (swap! reads inc)
                                   (binding [cr/*impl-override* nil]
                                     (apply orig args)))}]
            (#'graphden.executor.compile-runtime/delta-recompile! ctx #{child-id}))
          (is (zero? @reads)
              "delta-recompile re-read the whole graph despite a warm cache"))

        (testing "and the recompiled closure is still correct"
          (is (contains? @(:compiled-registry ctx) child-id)
              "the child fn is compiled into the registry")
          (is (= 7 (exec/execute ctx child-id {:a 3 :b 4}))
              "the closure compiled from the cache computes the same result")))
      (finally (sp/close storage)))))


(deftest run-with-timeout-executor-path
  ;; P1.3: `run-with-timeout` with an `executor` submits to that bounded pool
  ;; and QUEUES / sheds under load (→ ::rejected) instead of piling unbounded
  ;; soloExecutor threads. The 2-arity (no executor) keeps the legacy path.
  (let [pool (java.util.concurrent.Executors/newFixedThreadPool 1)]
    (try
      (testing "executor path returns the thunk value"
        (is (= 42 (cr/run-with-timeout 1000 (fn [] 42) pool))))
      (testing "overrun on the executor path → ::timeout"
        (is (identical? ::cr/timeout
                        (cr/run-with-timeout 50 (fn [] (Thread/sleep 500) :late) pool))))
      (testing "throw on the executor path → ::error"
        (is (identical? ::cr/error
                        (cr/run-with-timeout 1000 (fn [] (throw (ex-info "boom" {}))) pool))))
      (finally (java.util.concurrent.ExecutorService/.shutdownNow pool))))
  (testing "a saturated (shut-down) executor → ::rejected, not a crash"
    (let [pool (java.util.concurrent.Executors/newFixedThreadPool 1)]
      (java.util.concurrent.ExecutorService/.shutdown pool)
      (is (identical? ::cr/rejected
                      (cr/run-with-timeout 1000 (fn [] 1) pool)))))
  (testing "no executor → the legacy soloExecutor path is unchanged"
    (is (= 7 (cr/run-with-timeout 1000 (fn [] 7))))
    (is (identical? ::cr/timeout
                    (cr/run-with-timeout 50 (fn [] (Thread/sleep 500) :late))))))
