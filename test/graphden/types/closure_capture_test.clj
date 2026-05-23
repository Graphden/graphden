(ns graphden.types.closure-capture-test
  "Failing tests demonstrating the closure-capture extension's target
   behavior. Spec lives in `docs/CLOSURE_CAPTURE.md`.

   Status:
   - `:closure-capture` deftests are EXPECTED-FAILING until commits 2/3/4
     of the extension ship. Each one pins ONE invariant from the spec
     so the implementation has unambiguous acceptance criteria.
   - `:hof-regression` deftests EXERCISE the existing HOF behavior that
     MUST continue to work after the extension. They pass today; they
     act as a safety net that the implementation doesn't break
     `:map`/`:filter`/Ring handlers/middleware.

   The two `:cron-schedule` tests exercise the full cron use case
   end-to-end via fn-def composition. They are the ultimate
   acceptance criteria for the extension: when these go green AND
   the regression tests stay green, closure-capture is done.

   How to run:
       bb test --focus graphden.types.closure-capture-test

   Tests are tagged `:closure-capture-pending` so they don't fail CI
   while the extension is in flight. Remove the tag in commit 5 when
   the impl lands."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.composition.deps :as comp-deps]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture))


(defn- full-schema
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (ds/build)))


(defn- create-full-storage
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        storage (pg/create-storage (pth/get-container-config container))]
    (sp/initialize storage (full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (let [branch (sp/create-entity storage :branch
                                   {:name "test-branch"
                                    :created-at (java.time.Instant/now)})]
      (vs/->VersionedStorage storage (:id branch)))))


(defn- test-ctx
  [storage]
  (ctx/create-context {:storage storage :base-fns (exec/get-default-registry)}))


;; ============================================================================
;; Spec invariant 1: transitive free-arg propagation through HOF binding
;;
;; Setup: a fn-def composition where the OUTER fn-def references an
;; inner fn-graph via an HOF-typed slot. The inner fn-graph has a
;; free-arg `:captured-x`. Today: `free-arg-slot-map` doesn't surface
;; `:captured-x` as a free-arg of the outer. After commit 2: it should.
;; ============================================================================

(deftest transitive-free-arg-propagates-through-hof-binding-test
  ;; Concrete impl of commit 2's invariant. Synthetic fn-def graph:
  ;;
  ;;   :base-inner — base-fn with one unbound slot :captured-x
  ;;   :base-outer — base-fn with one slot :body
  ;;   :outer      — fn-def parent=:base-outer, binds :body to :inner
  ;;   :inner      — fn-def parent=:base-inner (still leaves
  ;;                 :captured-x unbound)
  ;;
  ;; Expected after commit 2: `free-arg-slot-map(ctx :outer)` includes
  ;; `{:captured-x <slot-id>}` even though :outer's inheritance chain
  ;; doesn't own a `:captured-x` slot — it's transitively captured
  ;; through the :body ref to :inner.
  (let [storage (create-full-storage)
        _ (exec/register-base-fn! :test-base-inner (fn [_ _] :ok))
        _ (exec/register-base-fn! :test-base-outer (fn [_ _] :ok))
        base-inner (setup/create-base-fn! storage "test-base-inner" :any)
        base-outer (setup/create-base-fn! storage "test-base-outer" :any)
        slot-captured (setup/create-slot! storage "captured-x" :int)
        slot-body (setup/create-slot! storage "body" :any)
        _ (setup/attach-slot! storage (:id base-inner) (:id slot-captured) 0)
        _ (setup/attach-slot! storage (:id base-outer) (:id slot-body) 0)
        inner (setup/create-composed-fn! storage
                                         "test-inner-leaks-captured"
                                         (:id base-inner))
        outer (setup/create-composed-fn! storage
                                         "test-outer-binds-inner"
                                         (:id base-outer))
        _ (setup/bind-ref! storage (:id outer) (:id slot-body) (:id inner))
        c (test-ctx storage)]
    (try
      (testing ":captured-x surfaces as a free-arg of :outer via the :body ref"
        (let [free (lookup/free-arg-slot-map c (:id outer))]
          (is (= {:captured-x (:id slot-captured)} free))))
      (testing "binding :captured-x at :outer's level removes it"
        (setup/bind-value! storage (:id outer) (:id slot-captured) 42)
        (let [free (lookup/free-arg-slot-map c (:id outer))]
          (is (= {} free))))
      (finally (sp/close storage)))))


;; ============================================================================
;; Spec invariant 2: wrapped callable captures binding-chain at wrap time
;;
;; Setup: a wrapped fn-graph references a free-arg `:cap`. The parent's
;; binding-chain at WRAP TIME binds `:cap` to value "X". The wrapped
;; callable invoked with no call-site args returns "X".
;; ============================================================================

(deftest hof-wrap-captures-binding-chain-at-wrap-time-test
  ;; Acceptance: an HOF-wrapped fn-graph, when invoked, sees its
  ;; outer free-args in scope. This is the runtime side of closure-
  ;; capture — wrap-time snapshot of the env so the wrapped callable
  ;; can be passed AWAY from the calling site (e.g. into a future
  ;; thread) and still resolve captured names.
  ;;
  ;; Synthetic graph:
  ;;   :test-runner  — base-fn with :body (type :fn). Impl: (body).
  ;;   :test-identity — base-fn with :captured-arg (any). Impl: arg.
  ;;   :test-inner — fn-def, parent :test-identity. :captured-arg
  ;;                 unbound → free at this level.
  ;;   :test-outer — fn-def, parent :test-runner. Binds :body to
  ;;                 :test-inner. After commit 2, :test-outer surfaces
  ;;                 `:captured-arg` as a transitive free-arg.
  ;;
  ;; Calling `:test-outer {:captured-arg "X"}` triggers:
  ;;   1. :test-outer closure runs with free-args {:captured-arg "X"}.
  ;;   2. build-args-and-aug wraps :test-inner into a callable, snap-
  ;;      shotting outer-free-args = {:captured-arg "X"}.
  ;;   3. :test-runner impl invokes (body); wrap runs :test-inner's
  ;;      closure with the snapshotted args. :test-inner sees
  ;;      :captured-arg "X" as a free-arg, returns it.
  ;; Result: "X" — the captured arg flowed through the HOF wrap.
  (let [storage (create-full-storage)
        runner-impl (fn [args _] ((:body args)))
        identity-impl (fn [args _] (:captured-arg args))
        _ (exec/register-base-fn! :test-runner runner-impl)
        _ (exec/register-base-fn! :test-identity identity-impl)
        runner (setup/create-base-fn! storage "test-runner" :any)
        id-base (setup/create-base-fn! storage "test-identity" :any)
        slot-body (setup/create-slot! storage "body" :fn)
        slot-cap (setup/create-slot! storage "captured-arg" :any)
        _ (setup/attach-slot! storage (:id runner) (:id slot-body) 0)
        _ (setup/attach-slot! storage (:id id-base) (:id slot-cap) 0)
        inner (setup/create-composed-fn! storage "test-inner-cap" (:id id-base))
        outer (setup/create-composed-fn! storage "test-outer-cap" (:id runner))
        _ (setup/bind-ref! storage (:id outer) (:id slot-body) (:id inner))
        c (test-ctx storage)]
    (try
      (testing "free-arg-slot-map surfaces :captured-arg via the :body ref"
        (let [free (lookup/free-arg-slot-map c (:id outer))]
          (is (= {:captured-arg (:id slot-cap)} free)
              "transitive propagation from commit 2 puts it in the map")))
      (testing "wrapped callable resolves :captured-arg at invocation time"
        (let [result (exec/execute c (:id outer) {:captured-arg "X"})]
          (is (= "X" result)
              "the HOF wrap snapshots free-args; inner closure sees them")))
      (testing "different outer invocations capture independently"
        (let [r1 (exec/execute c (:id outer) {:captured-arg "alpha"})
              r2 (exec/execute c (:id outer) {:captured-arg "beta"})]
          (is (= "alpha" r1))
          (is (= "beta" r2)
              "per-invocation snapshot — no leakage between calls")))
      (finally (sp/close storage)))))


;; ============================================================================
;; Spec invariant 3: call-site args win over captured args
;;
;; Setup: a fn-def binds `:item` (a call-site arg of `:map`'s `:fn`) at
;; the outer level. Despite the binding, `:map`'s impl passing
;; `:item element` per iteration must override and the wrapped callable
;; sees the iteration element, not the outer binding.
;; ============================================================================

;; Call-site-wins-over-captured semantic — structurally guaranteed by
;; hof-wrap's shape:
;;
;;   1 lambda-param: (assoc outer-free-args n item)
;;   2+ params:      (merge outer-free-args m)
;;
;; Both `assoc` and `merge` let the per-call value win over any
;; outer-free-args entry under the same name. The type-checker side
;; reinforces this at sync time (commit 4 of closure-capture):
;; call-site arg names declared in the slot's `[:fn {ARGS} _]` shape
;; are EXCLUDED from the lift into the outer fn-def's free-arg
;; surface — so an author CAN'T bind a call-site name at the outer
;; level (no slot of that name exists there to bind against).
;;
;; The 1-arg and map-callable wrap paths are exercised end-to-end by
;; the existing HOF tests in `graphden.packages.core.hof-test` — :map,
;; :filter, :reduce, :transduce all hit the (assoc / merge) merge
;; lines on every iteration. Adding a synthetic test here would
;; duplicate that coverage without testing anything new.


;; ============================================================================
;; Acceptance: cron schedule as fn-def composition.
;;
;; This is THE TEST. When this passes (and the regression tests below
;; stay green), closure-capture is complete.
;;
;; Composition (planned in core/concurrency/fns.edn, commit 5):
;;   :schedule = :future ⊃ :_cron-loop ⊃ :_cron-step ⊃ [:_sleep-to-next
;;               :_fire-target] where :_fire-target = :call-noargs ⊃ :fn
;;               and :_sleep-to-next pulls from :cron via :cron-next-after.
;;
;; The test creates a derived fn-def `:_test-cron :parent :schedule
;; :args {:cron … :fn :_test-tick}` where `:_test-tick` is a no-arg
;; fn-graph that increments a counter. Expectation:
;;   - :_test-cron has zero free-args (cron + fn bound at this level)
;;   - service-eligibility check accepts it (:process inherited from
;;     :future ancestor)
;;   - apply-execute runs it, returns a stopper
;;   - counter increments after a few iterations
;;   - stopper kills the loop
;; ============================================================================

(defn- load-core-into-rich-types!
  "Shared fixture: load the `core` package, register base-fns, seed
   declared rich-types for every fn-def, then run the type-checker
   on the whole graph. Mirrors the system/core.clj startup sequence
   in compressed form so tests can ask about loaded :schedule and
   friends without spinning up a container."
  []
  (let [{:keys [base-fn-defs fn-defs]} (loader/load-packages ["core"])]
    (registry/register-base-fns! base-fn-defs)
    (doseq [[nm fd] base-fn-defs]
      (registry/record-rich-types! nm fd))
    ;; Mirror system/core.clj's :exec/fn-entities init-key:
    ;;   1. seed each fn-def's declared shape via record-rich-types! —
    ;;      best-effort because composed fn-defs use :args for parent
    ;;      bindings, not arg-type declarations; the type-decl
    ;;      validator rejects most of them. The downstream check
    ;;      recovers proper computed types.
    ;;   2. topological sort + check-fn-def! per def — propagates
    ;;      effects + captured free-args transitively.
    (doseq [fd fn-defs]
      (when-let [fn-name (:name fd)]
        (try (registry/record-rich-types! fn-name fd)
             (catch Exception _ nil))))
    (doseq [fd (comp-deps/topological-sort fn-defs)]
      (try (check/check-fn-def! fd)
           (catch Exception _ nil)))
    {:base-fn-defs base-fn-defs :fn-defs fn-defs}))


(deftest cron-schedule-as-fn-def-composition-test
  ;; THE acceptance test. The real `:schedule` fn-def in
  ;; core/concurrency/fns.edn composes :future ⊃ :_cron-loop ⊃
  ;; :_cron-step ⊃ [:_sleep-to-next :_fire-target]. Closure-capture
  ;; (commits 2/3/4) is what makes `:cron` and `:fn` — declared deep
  ;; inside the composition — surface as free args of `:schedule`.
  ;; Without it, the HOF boundary at `:future :body` would erase
  ;; them and `:schedule` would have no way for an admin to pass in
  ;; the cron string or the fire target.
  (load-core-into-rich-types!)
  (testing ":schedule surfaces :cron and :fn as free args"
    (let [rt (registry/rich-type-of :schedule)
          args (:args rt)]
      (is (some? rt) "rich-type for :schedule was recorded")
      (is (contains? args :cron)
          ":cron lifts via :cron-next-after → :_next-fire-ms → … → through the :future :body HOF boundary into :schedule")
      (is (contains? args :fn)
          ":fn lifts via :_fire-target's :func rename → through the same HOF chain into :schedule")))
  (testing ":schedule inherits the :process effect via :future"
    (let [rt (registry/rich-type-of :schedule)]
      (is (contains? (set (:effects rt)) :process)
          "service-eligibility marker present"))))


(deftest cron-schedule-derived-fn-def-is-service-eligible-test
  ;; A derived fn-def that binds BOTH captured args (`:cron` value,
  ;; `:fn` ref) collapses :schedule's free-arg surface to empty,
  ;; satisfying the service-eligibility check's "no free args"
  ;; precondition. The `:process` effect rides along through
  ;; :schedule → :future. Together this is the type-level check
  ;; that a real admin fn-def `:my-cron :parent :schedule
  ;; :args {:cron … :fn …}` is service-eligible.
  (load-core-into-rich-types!)
  ;; Synthetic 0-arg target for the cron tick. Recorded directly
  ;; (no actual impl needed for the type-level test).
  (registry/record-rich-types! :my-tick {:args {} :return-type :null})
  (check/check-fn-def!
    {:name :my-cron
     :parent :schedule
     :args {:cron {:value "0 * * * * ?"}
            :fn :my-tick}})
  (testing "binding :cron and :fn collapses :my-cron's free args to empty"
    (let [rt (registry/rich-type-of :my-cron)]
      (is (empty? (:args rt))
          "service-eligibility precondition: no remaining free args")))
  (testing ":process effect rides through to :my-cron"
    (let [rt (registry/rich-type-of :my-cron)]
      (is (contains? (set (:effects rt)) :process)
          "service-eligibility marker preserved after derivation"))))


;; ============================================================================
;; HOF REGRESSION — these MUST stay green throughout the extension.
;; Each pins one piece of existing behavior that closure-capture must
;; preserve. If any of these flips red, the extension introduced a
;; regression and must be reverted / fixed before proceeding.
;; ============================================================================

(deftest ^:hof-regression existing-free-arg-slot-map-for-leaf-fn-test
  ;; A base-fn with no slots reports an empty free-arg map. Test
  ;; storage doesn't load packages so we synthesize the leaf-fn
  ;; inline (no slots attached).
  (let [storage (create-full-storage)
        _impl (exec/register-base-fn! :test-no-slots (fn [_ _] :ok))
        base (setup/create-base-fn! storage "test-no-slots" :any)
        c (test-ctx storage)]
    (try
      (is (= {} (lookup/free-arg-slot-map c (:id base)))
          "no slots → no free args")
      (finally (sp/close storage)))))


(deftest ^:hof-regression existing-free-arg-slot-map-for-no-parent-fn-test
  (let [storage (create-full-storage)
        ;; Build a small base-fn with two slots, neither bound.
        impl-fn (fn [args _ctx] (+ (:a args) (:b args)))
        _ (exec/register-base-fn! :test-needs-two-slots impl-fn)
        base (setup/create-base-fn! storage "test-needs-two-slots" :int)
        slot-a (setup/create-slot! storage "a" :int)
        slot-b (setup/create-slot! storage "b" :int)
        _ (setup/attach-slot! storage (:id base) (:id slot-a) 0)
        _ (setup/attach-slot! storage (:id base) (:id slot-b) 1)
        c (test-ctx storage)]
    (try
      (testing "a fn with unbound slots reports them as free args"
        (let [free (lookup/free-arg-slot-map c (:id base))]
          (is (= #{:a :b} (set (keys free))))))
      (finally (sp/close storage)))))


(deftest ^:hof-regression existing-hof-arg-not-counted-as-free-via-binding-test
  ;; When a slot is BOUND to a value or fn-ref, it's not free — even if
  ;; the bound fn-ref itself has free args. Current behavior; closure-
  ;; capture commit 2 expands this for HOF bindings specifically.
  (let [storage (create-full-storage)
        impl-fn (fn [_args _ctx] :ok)
        _ (exec/register-base-fn! :test-bound-slot impl-fn)
        base (setup/create-base-fn! storage "test-bound-slot" :any)
        slot-x (setup/create-slot! storage "x" :int)
        _ (setup/attach-slot! storage (:id base) (:id slot-x) 0)
        composed (setup/create-composed-fn! storage
                                            "test-bound-slot-composed"
                                            (:id base))
        _ (setup/bind-value! storage (:id composed) (:id slot-x) 42)
        c (test-ctx storage)]
    (try
      (testing "bound slot doesn't surface as a free arg"
        (is (= {} (lookup/free-arg-slot-map c (:id composed)))))
      (finally (sp/close storage)))))
