(ns graphden.executor.context-test
  "Tests for `graphden.executor.context/create-context` — validation,
   defaults, and `current-time-ms`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.counters :as counters]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; Validation — missing / invalid options
;; ============================================================================

(deftest validate-storage-required
  (testing "nil storage throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Storage is required"
          (ctx/create-context {})))))


(deftest validate-storage-must-implement-protocol
  (testing "a non-storage object (missing ExecutionGraph) is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"storage must implement ExecutionGraph protocol"
          (ctx/create-context {:storage {:fake :storage}})))))


;; ============================================================================
;; Defaults — context fields are populated with expected sentinels
;; ============================================================================

(deftest create-context-defaults
  (let [storage (setup/create-test-storage)]
    (try
      (let [c (ctx/create-context {:storage storage})]
        (is (some? (:storage c)))
        (is (fn? (:clock c)))
        (is (some? (:base-fns c)) "base-fns defaults to the global registry")
        (is (instance? clojure.lang.Atom (:compiled-registry c)))
        (is (nil? @(:compiled-registry c)) "compiled-registry starts empty"))
      (finally
        (sp/close storage)))))


(deftest create-context-threads-fleet-seams
  ;; Regression: the fleet control-plane seams must survive create-context — a
  ;; missing key in the destructure silently drops the seam (as :fleet-command
  ;; once was), so `branch-router/dispatch` sees nil and the internal endpoint
  ;; 404s. Assert each seam threads through to the ctx.
  (let [storage (setup/create-test-storage)
        forward (fn [_ _ _] :forwarded)
        command (fn [_ _] :handled)
        app (fn [_ _] :app)]
    (try
      (let [c (ctx/create-context {:storage storage
                                   :fleet-forward forward
                                   :fleet-command command
                                   :app-router app})]
        (is (= forward (:fleet-forward c)) "fleet-forward seam preserved")
        (is (= command (:fleet-command c)) "fleet-command seam preserved")
        (is (= app (:app-router c)) "app-router seam preserved"))
      (finally
        (sp/close storage)))))


(deftest create-context-coerces-executor-orgs-and-byo
  (let [storage (setup/create-test-storage)]
    (try
      (testing "a collection executor-orgs is coerced to a set (shard membership)"
        (let [c (ctx/create-context {:storage storage :executor-orgs ["public" "acme"]})]
          (is (= #{"public" "acme"} (:executor-orgs c)))))
      (testing "a predicate executor-orgs passes through unchanged (hash-shard)"
        (let [pred (fn [o] (= "acme" o))
              c (ctx/create-context {:storage storage :executor-orgs pred})]
          (is (= pred (:executor-orgs c)))))
      (testing "byo-executor? true is threaded onto the ctx"
        (let [c (ctx/create-context {:storage storage :byo-executor? true})]
          (is (true? (:byo-executor? c)))))
      (finally
        (sp/close storage)))))


(deftest create-context-custom-clock
  (testing "custom clock threads through as :clock and is sampled on demand"
    (let [storage (setup/create-test-storage)
          fake-time (atom 1000)
          clock (fn [] @fake-time)]
      (try
        (let [c (ctx/create-context {:storage storage :clock clock})]
          (is (= clock (:clock c)))
          (is (= 1000 (ctx/current-time-ms c)))
          (reset! fake-time 9999)
          (is (= 9999 (ctx/current-time-ms c))
              "clock is sampled on every call, not snapshotted"))
        (finally
          (sp/close storage))))))


(deftest create-context-explicit-base-fns
  (let [storage (setup/create-test-storage)
        custom {:custom (fn [_ _] :custom)}]
    (try
      (let [c (ctx/create-context {:storage storage :base-fns custom})]
        (is (= custom (:base-fns c))))
      (finally
        (sp/close storage)))))


(deftest invalidate-graph-cache-resets-atom
  (testing "with a :graph-cache atom set, invalidate clears it to nil"
    (let [cache (atom {:fns [{:id 1}] :args []})]
      (ctx/invalidate-graph-cache! {:graph-cache cache})
      (is (nil? @cache))))
  (testing "without a :graph-cache (test ctx without the atom) — no-op"
    ;; Should not throw — the when-let guard skips when the key is absent.
    (is (nil? (ctx/invalidate-graph-cache! {})))))


(deftest invalidate-delta-only-recompiles-affected
  (testing "passing changed-fn-ids to invalidate-graph-cache! preserves entries OUTSIDE the blast radius — fn-ids unrelated to the change keep the same compiled closure object instead of being recompiled from scratch (legacy 1-arity path threw the whole map away)."
    (let [storage (setup/create-test-storage)]
      (try
        (setup/setup-add-function! storage)
        (let [c (exec/create-context {:storage storage})
              ;; Force initial registry build via the lazy fallback,
              ;; then capture the per-fn closures and the reverse-deps
              ;; index for comparison after the delta call.
              reg-before ((requiring-resolve
                            'graphden.executor.compile-runtime/registry)
                          c)
              deps-before (some-> c :compile-deps deref)
              ;; Touch a single fn-id that the test storage does NOT
              ;; have — the blast set is empty, so every entry must
              ;; be reused as-is. This is the cleanest signal that
              ;; the delta path didn't accidentally drop the world.
              novel-id (random-uuid)]
          (is (some? reg-before) "registry got built")
          (is (some? deps-before) ":compile-deps populated alongside :compiled-registry")
          (is (pos? (count reg-before)) "test storage has at least one compilable fn")
          (ctx/invalidate-graph-cache! c #{novel-id})
          (let [reg-after @(:compiled-registry c)]
            (is (some? reg-after) "registry stays populated after delta invalidation")
            (is (= (set (keys reg-before)) (set (keys reg-after)))
                "no entries were dropped")
            (is (every? (fn [k]
                          (identical? (get reg-before k) (get reg-after k)))
                        (keys reg-before))
                "untouched fn-ids preserve their compiled closure objects (no recompile)")))
        (finally
          (sp/close storage))))))


(deftest invalidate-delta-blast-radius
  (testing "delta invalidation walks reverse-deps. Touching a child fn leaves its parent untouched (parent never depends on the child); touching the parent forces every descendant to recompile (closures inherited bindings, and `:produces-callable?`-style flags baked from the parent's rich-type entry)."
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [base-fn composed-fn]} (setup/setup-add-function! storage)
              c (exec/create-context {:storage storage})
              before ((requiring-resolve
                        'graphden.executor.compile-runtime/registry) c)
              base-id (:id base-fn)
              composed-id (:id composed-fn)
              base-before (get before base-id)
              composed-before (get before composed-id)]
          (is (some? base-before) "base-fn closure compiled at startup")
          (is (some? composed-before) "composed-fn closure compiled at startup")

          ;; Touch the CHILD: parent must keep its closure (no
          ;; reverse-dep edge child → parent), child gets recompiled.
          (ctx/invalidate-graph-cache! c #{composed-id})
          (let [after-child @(:compiled-registry c)]
            (is (identical? base-before (get after-child base-id))
                "base-fn closure unchanged when only the child mutates")
            (is (not (identical? composed-before (get after-child composed-id)))
                "composed-fn closure DID get recompiled (its bindings or own row mutated)"))

          ;; Touch the PARENT: every descendant must also be
          ;; recompiled because they inherit the parent's bindings.
          (let [reg-mid @(:compiled-registry c)
                composed-mid (get reg-mid composed-id)]
            (ctx/invalidate-graph-cache! c #{base-id})
            (let [after-parent @(:compiled-registry c)]
              (is (not (identical? composed-mid (get after-parent composed-id)))
                  "descendant recompiled when parent mutates (reverse-dep walk)"))))
        (finally
          (sp/close storage))))))


(deftest invalidate-delta-purges-deleted-fn-ids
  (testing "when a fn-id named in `changed-fn-ids` no longer has a row in storage (just got deleted), `delta-recompile!` must dissoc its stale closure from `:compiled-registry` — otherwise the next `execute` would find a closure for a fn that no longer exists and throw a confusing error from inside the compiled code rather than the clean fn-not-found path."
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [composed-fn]} (setup/setup-add-function! storage)
              c (exec/create-context {:storage storage})
              _ (exec/execute c (:id composed-fn) {:a 1 :b 2})
              before @(:compiled-registry c)
              gone-id (:id composed-fn)]
          (is (contains? before gone-id)
              "registry has the composed fn before deletion")
          (sp/delete-entity storage :fn gone-id)
          ;; Caller names the deleted id; delta path runs.
          (ctx/invalidate-graph-cache! c #{gone-id})
          (let [after @(:compiled-registry c)]
            (is (not (contains? after gone-id))
                "deleted fn's stale closure was dissoc'd from the registry")))
        (finally
          (sp/close storage))))))


(deftest invalidate-full-clear-on-empty-fn-ids
  (testing "invalidate-graph-cache! with no fn-ids (legacy 1-arity OR explicit nil) is stale-while-revalidate on a WARM ctx: it KEEPS serving the (now stale) registry and flags it, rather than nil-ing the holder and blocking the next reader behind a ~50s cold compile."
    (let [storage (setup/create-test-storage)]
      (try
        (let [c (exec/create-context {:storage storage})
              _ ((requiring-resolve
                   'graphden.executor.compile-runtime/registry) c)]
          (is (some? @(:compiled-registry c)) "registry populated by initial build")
          (ctx/invalidate-graph-cache! c)
          (is (some? @(:compiled-registry c))
              "1-arity full-clear KEEPS the stale registry (no pod-wide hang)")
          (is (true? @(:registry-stale? c))
              "…and flags it for background revalidation"))
        (finally
          (sp/close storage))))))


(defn- blind-storage
  "A runtime handle that can see NOTHING — the shape of the tenancy addon's
   org-scoped `(:storage ctx)` for rows the request's org does not own. Any
   read a cloud-shaped ctx must do through the privileged `:compile-storage`
   handle answers empty here, so a test that keeps working against it has
   proven the read went through the right handle."
  []
  (reify
    sp/StorageCRUD
    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-entities [_ _ _] [])

    (query-entities [_ _ _ _] [])

    (query-latest-per-group [_ _ _ _] [])


    sp/StorageBatchCRUD

    (create-entities [_ _ _] nil)

    (read-entities [_ _ _] {})

    (update-entities [_ _ _] nil)

    (upsert-entities [_ _ _] nil)

    (delete-entities [_ _ _] nil)

    (query-ref-many-owners [_ _ _ _] [])))


(deftest splice-reads-through-the-privileged-handle
  (testing "the graph cache holds the FULL org-agnostic graph (readers slice it per org), so the splice must re-read the changed fns through `:compile-storage` — the privileged handle — not `(:storage ctx)`. With an org-scoped runtime handle, a fn the request's org cannot see came back EMPTY and the splice (which drops the changed ids before re-adding what it read) ERASED it from the shared cache. Measured on a tenancy stack: write a binding, and `?scope=search` for the owning fn answered 0 hits for ~1-2s — which is what makes the editor toast “Function not found” for the fn you just edited."
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [composed-fn]} (setup/setup-add-function! storage)
              fn-id (:id composed-fn)
              ;; A runtime handle that sees NOTHING — the shape an org-scoped
              ;; storage has for another org's rows.
              ;; A runtime handle that sees NOTHING — the shape an org-scoped
              ;; storage has for another org's rows. The context is built with
              ;; the real storage (it is validated on create) and the blind one
              ;; is swapped in for the invalidation, which is exactly the
              ;; asymmetry tenancy produces: privileged compile handle, scoped
              ;; runtime handle.
              blind (blind-storage)
              c (assoc (exec/create-context {:storage storage})
                       :compile-storage storage)
              scoped (assoc c :storage blind)]
          ;; Prime the cache the way a read does.
          (reset! (:graph-cache c)
                  {:fns [composed-fn] :slots [] :fn-slots []
                   :bindings [] :list-items []})
          (ctx/invalidate-graph-cache! scoped #{fn-id})
          (is (some #(= fn-id (:id %)) (:fns @(:graph-cache c)))
              "the fn survives the splice — it was re-read with the privileged handle"))
        (finally
          (sp/close storage))))))


(deftest delta-recompile-takes-the-cache-on-a-cloud-shaped-ctx
  (testing "a delta recompile uses the primed `:graph-cache` instead of re-reading the whole graph — ALSO when `(:storage ctx)` is the addon's org-scoped handle and not the privileged `:compile-storage`. The cache is the full org-agnostic graph on every deployment (`splice-reads-through-the-privileged-handle`), so demanding the two handles be the same object only made every cloud write pay a full graph read: 1.7 s of a 1.85 s merge on production, 2026-09-03."
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [composed-fn]} (setup/setup-add-function! storage)
              composed-id (:id composed-fn)
              c (assoc (exec/create-context {:storage storage})
                       :compile-storage storage)
              _ (exec/execute c composed-id {:a 1 :b 2})
              before-closure (get @(:compiled-registry c) composed-id)
              scoped (assoc c :storage (blind-storage))
              before (counters/snapshot)]
          (is (some? @(:graph-cache c)) "the compile primed the cache")
          (ctx/invalidate-graph-cache! scoped #{composed-id})
          (let [delta (counters/delta-since before)]
            (is (= 1 (get delta :registry/delta-recompile 0)) "it WAS a delta")
            (is (zero? (get delta :registry/delta-read-graph 0))
                "and it did not read the graph out of storage")
            (is (zero? (get delta :registry/delta-fell-back-to-rebuild 0))))
          (is (not (identical? before-closure (get @(:compiled-registry c) composed-id)))
              "the changed fn was recompiled from the cached graph")
          (is (= 3 (exec/execute scoped composed-id {:a 1 :b 2}))
              "and still runs"))
        (finally
          (sp/close storage))))))
