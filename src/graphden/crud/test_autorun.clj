(ns graphden.crud.test-autorun
  "Write-triggered auto-run of affected tests (Roadmap Block 3.1,
   phase 2).

   When a graph write lands, `crud.entities/invalidate!` already knows
   the affected fn-id seeds and the branch. This ns rides that hook as
   a third best-effort sibling (next to the branch-ctx sweep and the
   service-restart blast): compute the REVERSE transitive closure of
   the seeds over the ctx's `:compile-deps` index (the same
   `transitive-blast` the service restart uses), intersect with the
   branch's test fns, and re-run the intersection in the background.

   Safety gates, in order:
   - only tests whose recorded effect closure is EMPTY auto-run —
     an effectful test (`:network`, `:db`, …) may only run from the
     explicit Run button. Unknown closure (not yet type-checked)
     counts as NOT pure.
   - the executing ctx carries `:allowed-effects #{}`, so a hidden
     effect the static closure missed throws
     `:execution/forbidden-effect` instead of silently firing
     (mirrors the tenancy effect gate's don't-trust-the-static-set
     stance).
   - a per-write cap (`*max-auto-run*`) bounds the blast; dropped
     tests keep their stale status (honest — they simply didn't run).
   - runs are debounced per `[org branch]` (`debounce-ms`), so an
     edit burst coalesces into one background pass.

   The runner future CONVEYS the writing request's dynamic bindings
   (org context included), so tests execute exactly as the writer
   would run them — the inverse of the reconciler's platform-org
   trap: here inheriting the writer's org is the correct scoping.

   Cold ctx (`:compile-deps` nil) → no-op, same contract as the
   service-restart blast. When the run finishes, a best-effort
   `test:updated` NOTIFY nudges SSE listeners (wake-on-writes panels
   re-render on any event; the explicit emit covers runs that outlive
   the write's own debounced tick)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.compile.deps :as compile-deps]
    [graphden.executor.registry.core :as registry]
    [graphden.tenancy.context :as tc]))


(def ^:dynamic *auto-run?*
  "Off-switch for the write-triggered auto-run (tests bind it false;
   operators can flip it from a REPL)."
  true)


(def ^:dynamic *max-auto-run*
  "Per-pass cap on auto-run tests. Tests beyond the cap are dropped
   with a warn and keep their stale status."
  25)


(def ^:private debounce-ms 500)


(defn- pure-test?
  "True iff the fn's RECORDED effect closure is empty. nil registry
   entry (never type-checked) is NOT pure — conservative."
  [fn-id]
  (when-let [info (registry/rich-type-of-id fn-id)]
    (empty? (:effects info))))


(defn affected-test-ids
  "Pure selection: ids of `test-rows` whose id falls in the reverse
   transitive closure of `seeds` over `reverse-deps`, filtered by
   `pure?` (defaults to the registry effect-closure check). Empty on a
   cold index or empty seeds."
  ([reverse-deps test-rows seeds]
   (affected-test-ids reverse-deps test-rows seeds pure-test?))
  ([reverse-deps test-rows seeds pure?]
   (if-not (and (seq seeds) (seq reverse-deps) (seq test-rows))
     []
     (let [blast (compile-deps/transitive-blast reverse-deps (set seeds))]
       (into []
             (comp (filter #(contains? blast (:id %)))
                   (filter #(pure? (:id %)))
                   (map :id))
             test-rows)))))


;; {[org branch-id] {:seeds #{uuid} :runner? bool}} — pending SEEDS per
;; scope. `:runner?` marks a live debounce future; followers only add
;; seeds. Selection (reverse closure ∩ tests ∩ pure) happens INSIDE the
;; runner future, never on the write path — the perf budget on
;; `:sql/create-fn` counts every synchronous round trip, and the
;; tests-namespace discovery query must not be one of them.
(defonce ^:private pending (atom {}))


(defn- drain!
  "Atomically take (and clear) the pending seeds for `key`. Returns
   the drained set."
  [key]
  (let [[old _] (swap-vals! pending #(assoc-in % [key :seeds] #{}))]
    (get-in old [key :seeds] #{})))


(defn- try-release!
  "Release the runner claim IFF no seeds accumulated for `key` in the
   meantime — the check and the release are one atomic swap, so a
   follower that just queued seeds under the still-set `:runner?` flag
   is never orphaned. True when released; false = more work waits."
  [key]
  (let [[_ new] (swap-vals! pending
                            (fn [m]
                              (if (seq (get-in m [key :seeds]))
                                m
                                (dissoc m key))))]
    (not (contains? new key))))


(defn- run-pending!
  "Debounce, then loop: drain accumulated seeds → select affected
   tests (reverse closure ∩ branch tests ∩ pure) → run → repeat until
   the atomic release finds nothing queued. Every throw is swallowed-
   and-logged — this runs on a background future off a user's CRUD
   write, and ALL storage reads (tests discovery included) happen
   here, off the write path."
  [ctx key branch-id]
  (try
    (loop []
      (Thread/sleep debounce-ms)
      (let [seeds (drain! key)
            reverse-deps (some-> (:compile-deps ctx) deref :reverse-deps)
            affected (when (seq seeds)
                       (affected-test-ids reverse-deps
                                          (test-runs/test-fn-rows ctx)
                                          seeds))
            capped (vec (take *max-auto-run* affected))]
        (when (< (count capped) (count affected))
          (log/warn "test auto-run cap hit — dropped tests keep their stale status"
                    {:cap *max-auto-run* :dropped (- (count affected) (count capped))}))
        (when (seq capped)
          ;; `:allowed-effects #{}` — the runtime backstop; a hidden
          ;; effect throws instead of firing, and the test records as
          ;; failed. Static selection already excluded declared-effect
          ;; tests.
          (test-runs/run-tests! (assoc ctx :allowed-effects #{})
                                {:fn-ids capped})
          (when-let [emit (:notify-emitter ctx)]
            (try (emit {:kind :test :op :updated :id ""
                        :branch-id (some-> branch-id str)})
                 (catch Exception _ nil)))))
      (when-not (try-release! key)
        (recur)))
    (catch Exception e
      (swap! pending dissoc key)
      (log/warn e "test auto-run pass failed" {:key key}))))


(defn schedule-affected!
  "The `invalidate!` hook: queue the write's fn-id `seeds` and ensure
   one debounced background runner per `[org branch]` scope. DELIBERATELY
   O(1) and SQL-free on the write path — an atom swap plus (at most) one
   future spawn; the blast selection and discovery reads run inside the
   runner. The `future` (not a raw thread) conveys the writer's dynamic
   bindings, org context included. nil/empty seeds (unknown blast) →
   no-op: auto-running the whole suite on an unclassified write is
   exactly the surprise this gate exists to prevent. Cold ctx (no
   compile-deps reverse index) → no-op, same contract as the
   service-restart blast."
  [ctx seeds branch-id]
  (when (and *auto-run?* (seq seeds)
             (some-> (:compile-deps ctx) deref :reverse-deps seq))
    (let [key [(tc/current-org) branch-id]
          [old _] (swap-vals! pending
                              (fn [m]
                                (-> m
                                    (update-in [key :seeds] (fnil into #{}) seeds)
                                    (assoc-in [key :runner?] true))))]
      (when-not (get-in old [key :runner?])
        (future (run-pending! ctx key branch-id)))
      (count seeds))))
