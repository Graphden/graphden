(ns graphden.system.branch-router.epoch
  "Graph-epoch lazy validation (audit-6) of a BranchRouter's cached ctxs.

   Freshness self-heal: every graph-shaped write bumps a Postgres
   sequence BEFORE the write (storage.postgres.graph-epoch). The eager
   invalidate + NOTIFY remain latency optimizations; when either is
   skipped (client abort on the request thread, a write path with no
   NOTIFY, a lost NOTIFY), the router discovers it here — on context
   fetch (`validate-graph-epoch!`, called by `branch-router/entry-for`) —
   and heals every cached ctx once. Eager paths call
   `note-graph-epoch-validated!` after finishing so their own writes
   never trigger the heal."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry-core]
    [graphden.storage.postgres.graph-epoch :as pg-epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.cache :as cache]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.util.counters :as counters]
    [graphden.versioning.storage.core :as vs]))


(defonce ^{:doc "Pod-wide epoch state: {:w watermark :read {:value :at}}.
  :w = the newest epoch through which EVERY effect is known applied to
  this pod's caches; :read = the TTL-cached global sequence read.
  Advancing :w requires the whole (w, global] range to be accounted
  for by the handle's ledger (audit-7 FINDING 1: the old scalar
  max-advance silently skipped past interleaved foreign epochs whose
  NOTIFY was lost). Tests isolate via *epoch-state-override* (wired
  into the parallel plugin's isolation-vars)."}
  global-epoch-state
  (atom {:w 0 :read {:value nil :at 0}}))


(def ^:dynamic *epoch-state-override* nil)


(defn epoch-state-seed
  "Fresh per-thread epoch state for the parallel test plugin's
   isolation binding."
  []
  {:w 0 :read {:value nil :at 0}})


(defn- epoch-state
  []
  (or *epoch-state-override* global-epoch-state))


(defn seed-watermark!
  "Advance the watermark to the sequence's current value — for a ctx that
   was just built from the CURRENT graph (the router's default-branch
   seed), so the first request does not spuriously heal over bumps (boot
   sync's) the build already absorbed."
  [base-storage]
  (swap! (epoch-state) update :w max (or (pg-epoch/current base-storage) 0)))


(defn reset-epoch-state!
  "Forget everything this pod (or, under the parallel test plugin, this
   NS-thread) knows about the graph-epoch sequence: watermark back to 0,
   the TTL-cached global read dropped. For the test helper that DROPS
   the schema between deftests — the sequence restarts at 1 while the
   thread's state still says `w=11, read=11 (fresh)`, so the NEXT
   router's first dispatch trusts the cached read, and the one after
   it (TTL expired) sees a 'regression' and heals — dropping the
   branch ctx whose handler the test had just swapped in
   (`dispatch-routes-to-per-branch-ctx-end-to-end-test`, main CI
   ). Never called in production: a real sequence restart is
   a DB restore, and the regression path is the right answer there."
  []
  (reset! (epoch-state) (epoch-state-seed)))


(def ^:dynamic *epoch-check-ttl-ms*
  "Floor between two sequence reads — bounds the heal's staleness
   window AND its hot-path cost to one tiny SELECT per TTL. Dynamic so
   tests can force immediate checks."
  1000)


(def ^:dynamic *epoch-heal-grace-ms*
  "How long an UN-NOTED local bump may age before it is treated as an
   aborted eager path and healed. This no longer suppresses healing of
   FOREIGN gaps — a missed sibling write heals immediately regardless
   of local write activity (the first design's 10s blanket suppression
   was the amplifier that let local notes bury foreign epochs).

   Must exceed the abort-shield join budget (30 s): a write that is
   merely SLOW — still inside its request, its note still to come — must
   never read as aborted, because the heal it would trigger stalls the
   next writes past the budget, whose un-noted bumps trigger the next
   heal (the e2e heal storm: one 27 s namespace move, then a
   heal every 30 s until the stack died)."
  45000)


(defn note-graph-epoch-validated!
  "Eager-invalidation tail: mark this request's bumps APPLIED in the
   handle ledger (drains `pg-epoch/*request-bump-log*`; 2-arity takes
   explicit values for off-thread tails like the merge post-commit).
   Never advances the watermark — the validator does, and only when
   the whole range is accounted for. Forgetting a call site ages the
   bump past grace and costs one spurious heal, never a wrong result."
  ([storage]
   (pg-epoch/note-applied! (or (:base-storage storage) storage)))
  ([storage vs]
   (pg-epoch/note-applied! (or (:base-storage storage) storage) vs)))


(defn note-graph-epoch-covered!
  "NOTIFY-handler tail: the sibling's event carried the writer's exact
   bump values and the delta was applied locally — mark them covered."
  [storage vs]
  (pg-epoch/cover-foreign! (or (:base-storage storage) storage) vs))


(defn- global-epoch-cached
  [base-storage]
  (let [state (epoch-state)
        now (System/currentTimeMillis)
        {:keys [value at]} (:read @state)]
    (if (and value (< (- now at) *epoch-check-ttl-ms*))
      value
      (let [v (pg-epoch/current base-storage)]
        ;; nil (degraded / missing sequence) is cached too — without
        ;; this a degraded DB pays a failing SELECT per request.
        (swap! state assoc :read {:value v :at now})
        v))))


(defonce ^:private epoch-heal-monitor (Object.))


(def ^:dynamic *epoch-heal-sync?*
  "Test hook: run the heal's rebuild work inline instead of on the
   background thread, so assertions don't race it."
  false)


(defn- heal-refresh-entry!
  "One entry's stale-while-revalidate refresh for `heal-stale-ctxs!`.
   A DELETED branch (its epoch bump is what woke the heal) is dropped
   like the local delete path (`cache/invalidate!`) — rebuilding would resurrect a phantom
   ctx and leave the name→id ref-cache pointing at a dead registry.
   A live entry rebuilds under ITS OWN registry slices: two OPTIMISTIC
   attempts (compile outside the lock, swap only if the epoch didn't
   move mid-compile — a moved epoch means a delta already patched the
   live registry and our snapshot would clobber it), then a blocking
   rebuild as the correctness fallback under continuous writes."
  [router base default-branch-id bid entry]
  (if (and (not= bid default-branch-id)
           (nil? (sp/read-entity base :branch bid)))
    (cache/invalidate! router bid)
    (when-let [c (:ctx entry)]
      (recheck/call-with-ctx-slices
        c
        (fn []
          (try
            (loop [attempt 1]
              (let [e0 (pg-epoch/current base)
                    swapped? (cr/rebuild-optimistic!
                               c #(= e0 (pg-epoch/current base)))]
                (when-not swapped?
                  (if (< attempt 2)
                    (recur (inc attempt))
                    (cr/rebuild! c)))))
            (catch Exception e
              (log/warn e "graph-epoch heal: ctx rebuild failed"))))))))


(defn- heal-stale-ctxs!
  "An epoch in (w, global] is neither locally-noted nor NOTIFY-covered:
   somebody's write reached the DB without this pod applying its
   invalidation.

   STALE-WHILE-REVALIDATE for the BASE ctx: rebuild it on a BACKGROUND
   thread instead of nil-ing its registry — `cr/rebuild!` reads the
   graph fresh, compiles, and only then swaps the atoms, so requests
   keep serving the (stale) registry for the rebuild's duration
   instead of queueing behind a cold compile. The first heal design
   full-cleared, and one heal mid-e2e took /health down past its 60s
   ceiling — availability must survive the freshness backstop.
   Staleness is bounded by one rebuild.

   Every OTHER cached branch ctx is DROPPED, not rebuilt: the next
   request for that branch builds it fresh (the graph-identical fast
   path copies the now-fresh base by value; a branch with its own
   changes compiles once, on demand). Rebuilding every cached entry
   made a heal cost O(cached branches) full compiles — merged source
   branches stay forever (main resolves through them), so an e2e run
   or a busy workspace holds dozens of them, and one heal became
   minutes of compile that stalled writes past the abort budget,
   whose un-noted bumps triggered the next heal.

   Base first, then drop the rest — including entries installed while
   the base rebuilt (they copied the pre-swap base). Serialized on a
   monitor so two heals can't interleave. The watermark advances
   immediately — the heal is now in flight and a re-trigger would
   only duplicate it."
  [{:keys [handlers default-branch-id] :as router} base global]
  (locking epoch-heal-monitor
    (let [state (epoch-state)]
      (when (> global (:w @state))
        (counters/count! :pg-epoch/heal)
        (log/info "graph-epoch heal: background rebuild of cached ctxs"
                  {:validated (:w @state) :global global})
        (pg-epoch/prune! base global)
        (swap! state assoc :w global)
        (let [snap @handlers
              refresh! (fn [bid entry]
                         (heal-refresh-entry! router base default-branch-id
                                              bid entry))
              pinned (cache/pinned-branches)
              work (fn []
                     (when-let [e (get snap default-branch-id)]
                       (refresh! default-branch-id e))
                     ;; Every non-base entry — the snapshot's AND those
                     ;; installed while the base rebuilt (they copied the
                     ;; pre-swap base) — is dropped; its next request
                     ;; rebuilds it against the fresh base. PINNED entries
                     ;; (a branch with a running service) are refreshed in
                     ;; place instead: the service holds that ctx by
                     ;; reference, so dropping it would strand the service
                     ;; on a stale registry while requests built another.
                     (doseq [bid (keys @handlers)]
                       (when (not= bid default-branch-id)
                         (if (contains? pinned bid)
                           (when-let [e (get @handlers bid)] (refresh! bid e))
                           (cache/invalidate! router bid)))))
              ;; Convey ONLY the test-isolation registry overrides onto the
              ;; heal thread — NOT bound-fn* (that would drag per-request
              ;; bindings like the tenant org into a background rebuild).
              ;; Without this a heal fired from an isolated test thread
              ;; rebuilt ctxs against an EMPTY rich-types registry: base-fn
              ;; markers (`:lazy-seq-args` on `:cond` &c.) vanished and the
              ;; recompiled closures evaluated cond clauses EAGERLY — the
              ;; the "/api" update-keys ClassCast poisoning. In
              ;; production the per-ctx binding below (each ctx's own
              ;; rich-types slice) overrides these ambient captures anyway —
              ;; they matter only for ctxs built before slice-tagging.
              rt-override registry-core/*rich-types-override*
              per-org-override registry-core/*per-org-rich-override*
              work (fn []
                     (binding [registry-core/*rich-types-override* rt-override
                               registry-core/*per-org-rich-override* per-org-override]
                       (work)))
              t (Thread. ^Runnable work "graph-epoch-heal")]
          (if *epoch-heal-sync?*
            (work)
            (do (Thread/.setDaemon t true)
                (Thread/.start t))))))))


(defn validate-graph-epoch!
  "Fetch-time check. Classify every epoch in (w, global] against the
   handle ledger: a FOREIGN gap or an ABORTED local bump heals now; a
   fully applied range advances the watermark; young un-noted local
   bumps wait (their eager invalidate is in flight). A global BELOW
   the watermark means the sequence regressed (DB restore under a
   live JVM) — reseed + heal rather than going silently dead. nil
   global (no pool / missing sequence) skips: cannot validate, eager
   paths remain the only mechanism — the pre-epoch behavior."
  [{:keys [base-ctx] :as router}]
  (let [base (vs/unwrap (:storage base-ctx))
        state (epoch-state)]
    (when-let [global (global-epoch-cached base)]
      (let [w (:w @state)]
        (cond
          (< global w)
          (do (log/warn "graph-epoch regression — sequence restarted below the watermark; reseeding + healing"
                        {:watermark w :global global})
              (swap! state assoc :w -1)
              (heal-stale-ctxs! router base global))

          (> global w)
          (let [statuses (pg-epoch/classify-range base w global *epoch-heal-grace-ms*)]
            (cond
              (or (:foreign statuses) (:aborted statuses))
              (do
                ;; WHY — an aborted epoch names the entity whose write
                ;; never reached its note (a missing call site, or a
                ;; write that outlived the grace); a foreign one is a
                ;; sibling pod's write this pod's NOTIFY missed.
                (log/info "graph-epoch heal reason"
                          (assoc (pg-epoch/explain-range base w global *epoch-heal-grace-ms*)
                                 :watermark w :global global))
                (heal-stale-ctxs! router base global))

              (:pending statuses)
              nil ; eager invalidations in flight — check again next TTL

              :else ; everything applied/covered — advance without healing
              (do (pg-epoch/prune! base global)
                  (swap! state update :w max global)))))))))
