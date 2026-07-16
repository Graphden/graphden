(ns kaocha.plugin.perf
  "Records what a suite run cost, as a machine-readable file.

   Why
   ===
   `tests.edn` already says where the money goes: \"~51 s of fixture against
   ~0.14 s of assertions\". That ratio was obtained by hand, once, from two
   numbers someone happened to compare. Every run since has printed a top-25 and
   thrown it away — so \"did that change make the suite slower?\" has never had an
   answer, only a re-derivation.

   This plugin writes the numbers down. It measures NOTHING itself: the
   already-enabled `:kaocha.plugin/profiling` stamps
   `:kaocha.plugin.profiling/duration` on every testable, and the fixture
   counters live in `graphden.util.counters`. All this does is walk the result
   tree, do the arithmetic, and persist it.

   The fixture-vs-assertion split
   ==============================
   A `:kaocha.type/ns` testable's duration contains its `:once` fixtures; its
   child `:kaocha.type/var` testables' durations do not. So

     ns-duration − Σ(child durations) ≈ the `:once` fixture cost

   which is the 51-vs-0.14 number, per namespace, for free. Under
   `:kaocha/parallelism` the NSes overlap each other — so the per-NS split stays
   honest (parent and children share a thread) while the SUM across NSes exceeds
   wall-clock. Hence `:wall-ms` is recorded from the run root, never summed.

   What is safe to compare across machines
   =======================================
   `:counters` — exactly. A count is machine-independent by construction, which
   is why `perf/budgets.edn` gates on those and only those.

   `:namespaces` durations — NOT comparable across machines, and not comparable
   across runs on THIS host either: `scripts/ci.clj` records that two concurrent
   runs on 8 cores drove load average to 75 and timed out 9 of 17 checks. They
   are here to answer \"where did the time go in this run\", the question the
   top-25 print was already trying to answer, and to feed the normalised
   wall-clock report — never to gate."
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [graphden.util.counters :as counters]
    [kaocha.plugin :refer [defplugin]]
    [kaocha.testable :as testable]))


(def ^:private duration-key
  "Set by `:kaocha.plugin/profiling`, which `tests.edn` already enables. Keyed
   by its fully-qualified name rather than a `:require` so this plugin never
   forces profiling's load order — if profiling is switched off, every duration
   reads nil and we degrade to counters-only rather than throwing."
  :kaocha.plugin.profiling/duration)


(defn- ns-report
  "One `:kaocha.type/ns` testable → `{:id :wall-ms :tests-ms :fixture-ms :tests}`.
   `:fixture-ms` is the residue: what the namespace spent NOT inside a test var."
  [t]
  (let [children (remove ::testable/skip (:kaocha.result/tests t))
        total-ns (get t duration-key)
        tests-ns (reduce + 0 (keep duration-key children))]
    (when total-ns
      {:id (:kaocha.testable/id t)
       :wall-ms (quot total-ns 1000000)
       :tests-ms (quot tests-ns 1000000)
       ;; Clamped at zero: with profiling off, or a child that outlived its
       ;; parent's stamp, the subtraction could go negative and a negative
       ;; "fixture cost" would be read as a real finding by whoever sees it.
       :fixture-ms (max 0 (quot (- total-ns tests-ns) 1000000))
       :tests (count children)})))


(defn- collect
  "The run's result tree → the report map that gets written to disk."
  [result]
  (let [nses (->> (testable/test-seq result)
                  (filter #(= :kaocha.type/ns (:kaocha.testable/type %)))
                  (remove ::testable/load-error)
                  (remove ::testable/skip)
                  (keep ns-report)
                  (sort-by :fixture-ms >)
                  vec)]
    {:wall-ms (some-> (get result duration-key) (quot 1000000))
     ;; Absolute, not a delta: the JVM is fresh per suite run, so every count
     ;; since zero IS this run's count. That also keeps the plugin free of any
     ;; ordering relationship with `:kaocha.plugin/shared-container`, whose
     ;; `pre-run` eagerly bootstraps the golden and would land on the wrong side
     ;; of a `pre-run` snapshot taken here.
     :counters (counters/snapshot)
     :namespaces nses}))


(defn- write-report!
  [report path]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint report)))
  path)


(defn- print-summary!
  [{:keys [counters namespaces]}]
  (println "\nFixture cost — the time no assertion paid for")
  (doseq [{:keys [id tests-ms fixture-ms]} (take 10 namespaces)]
    (println (format "  %6.1fs fixture  %5.1fs tests  %s"
                     (/ fixture-ms 1000.0) (/ tests-ms 1000.0) (str id))))
  (let [fixture-total (reduce + 0 (map :fixture-ms namespaces))
        tests-total (reduce + 0 (map :tests-ms namespaces))]
    (when (pos? tests-total)
      (println (format "  ── %.0fs fixture vs %.1fs assertions across %d namespaces (%.0f:1)"
                       (/ fixture-total 1000.0) (/ tests-total 1000.0)
                       (count namespaces) (/ (double fixture-total) tests-total)))))
  (when (seq counters)
    (println "\nStructural counts — these are what perf/budgets.edn gates on")
    (doseq [[event n] (sort-by key counters)]
      (println (format "  %-32s %d" (str event) n)))))


;; `defplugin` builds its hook names into `defmethod`s, so clj-kondo sees the
;; hook name and its parameter vector as free symbols. Scoped to this one form —
;; `shared_container.clj` disables the linter for its whole namespace, which
;; would also hide a genuine typo anywhere else in this file.
#_{:clj-kondo/ignore [:unresolved-symbol]}


(defplugin kaocha.plugin/perf
  "Persists the run's cost to perf/runs/*.edn."

  (post-summary
    [result]
    ;; post-summary, not post-run: profiling stamps the root's duration in its
    ;; own post-run, and plugin hooks of the same name run in `tests.edn` order.
    ;; Reading the root duration from a later phase is immune to that ordering.
    ;; The default is deliberately NOT a name any budget claims. A report is only
    ;; meaningful for the suite that produced it, and `bb test` runs unit AND
    ;; integration in ONE kaocha invocation — so a default of "unit.edn" would
    ;; file both suites' counters under the unit budget and quietly change what
    ;; that budget means depending on which task ran. Budgeted reports get their
    ;; name from the task that scopes them (`bb test-unit`, `bb test-perf`);
    ;; everything else lands here and is trend data.
    (let [report (collect result)
          path (or (System/getenv "GRAPHDEN_PERF_REPORT") "perf/runs/last.edn")]
      (try
        (write-report! report path)
        (print-summary! report)
        (println (str "\nperf: report written to " path))
        (catch Exception e
          ;; A reporting plugin must never be the reason a green suite reports
          ;; red. Say what broke and hand the result back untouched.
          (println (str "perf: could not write " path ": " (ex-message e))))))
    result))
