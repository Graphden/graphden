(ns graphden.util.counters
  "Process-global counters for STRUCTURAL events — a compiled-registry
   full-clear, a fixture bootstrap, a type-check sweep.

   Why counters and not timers
   ===========================
   Every performance regression this project has actually shipped a fix for was
   structural, not temporal — a count that moved, not a clock that slipped:

     9d66adc0  a write that changed no closure dropped the compiled registry
     0b74f1dc  a `:service` write full-cleared the compiled registry
     d925ec35  a divergent branch's ctx was full-rebuilt, not delta-compiled
     9c1b882a  a write dropped the graph cache instead of splicing it
     6840f542  the type-check sweep ran once per NS instead of once per JVM

   Meanwhile both attempts to chase a *timing* win failed: `docs/PERF_NOTES.md`
   records one point-fix that measured \"~5% — inside the run's std-dev, i.e.
   noise\", and a `snake->kw` memoisation that made the suite SLOWER and was
   reverted. The lesson is in the ledger: the regressions we can catch are the
   ones that change a count.

   A count is machine-independent by construction. `1` on an idle laptop is `1`
   on a loaded CI box, at load average 75, on ubuntu-latest. So it can be
   asserted exactly — no tolerance band, no normalisation, no flake. That is
   what makes `perf/budgets.edn` gateable where a wall-clock baseline could only
   ever be advisory.

   Cost
   ====
   One `swap!` per counted event, and every event named here is rare by nature:
   a write, a cache miss, a bootstrap. Nothing per-node or per-row is counted.
   The executor's hot path is deliberately NOT instrumented — PERF_NOTES is
   explicit that the suite is CRUD/compile/HTTP-bound rather than execute-bound,
   so a counter read per node would be a real cost on a ~1 µs/node budget in
   exchange for a number we have no use for.

   Global, not dynamic
   ===================
   A `^:dynamic` var would be free when unbound, but it cannot see the threads
   that matter: the NOTIFY listener invalidates the registry from its own
   thread, and kaocha runs namespaces on a pool. A plain atom counts every
   thread's events, which is the whole point. Reading is snapshot-and-diff
   (`delta-since`), so concurrent counting never needs a lock.")


(def ^:private counters
  "{event-keyword → long}. Process-global and monotonic for the JVM's life;
   callers diff two snapshots rather than resetting, so two readers can't
   invalidate each other's window."
  (atom {}))


(def ^:private gauges
  "{event-keyword → number}. Observations, not events — see the Gauges section
   below for why they do not share the counters map."
  (atom {}))


(defn count!
  "Record `n` (default 1) occurrences of `event`. Thread-safe; returns nil so
   it can never be mistaken for a value-producing call in a threading form."
  ([event] (count! event 1))
  ([event n]
   (swap! counters update event (fnil + 0) n)
   nil))


(defn snapshot
  "The current counts as an immutable map. Take one before a scenario and pass
   it to `delta-since` after."
  []
  @counters)


(defn delta-since
  "Counts accrued since `snap`, as `{event n}`, omitting events that did not
   move. This — not an absolute reading — is what a scenario asserts on: the
   JVM's totals depend on whatever ran before, the delta does not."
  [snap]
  (into {}
        (keep (fn [[event n]]
                (let [d (- n (get snap event 0))]
                  (when (pos? d) [event d]))))
        @counters))


(defn reset-counters!
  "Drop every count. For the perf harness's own setup only — a test that resets
   a process-global while a sibling namespace counts into it on another thread
   would corrupt that sibling's delta. Prefer `snapshot` + `delta-since`."
  []
  (reset! counters {})
  (reset! gauges {})
  nil)


;; === Gauges =================================================================
;;
;; A gauge is an OBSERVATION, not an event: a measured duration, a calibration
;; reference, a ratio. Kept in its own atom rather than folded into the counts
;; above, because the two carry opposite guarantees and only one of them may be
;; gated. A count is exact and machine-independent — `perf/budgets.edn` asserts
;; on it. A gauge is a reading off this host at this moment, and asserting on one
;; would import every property of the box into the build. Sharing a map would
;; make it a keyword's-worth of care away from doing exactly that.


(defn observe!
  "Record `value` for `event`, last write wins. Doubles welcome — unlike a count,
   a measurement is not an integer."
  [event value]
  (swap! gauges assoc event value)
  nil)


(defn gauges-snapshot
  "Every observation so far. Reported by `kaocha.plugin/perf`; never gated."
  []
  @gauges)
