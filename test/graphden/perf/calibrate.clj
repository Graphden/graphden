(ns graphden.perf.calibrate
  "Turns milliseconds into a unit that survives leaving this machine.

   The problem
   ===========
   `docs/PERF_NOTES.md` lists `/api/graph/entities?scope=tree` at ~15 ms. That
   number is unusable as a baseline: it is 15 ms on the box it was measured on,
   on the day it was measured, and `scripts/ci.clj` records this host reaching
   load average 75 with two runs on it. Comparing it to a reading from
   ubuntu-latest compares hardware, not code.

   The fix
   =======
   Measure a REFERENCE workload in the same process, in the same run, and report
   the ratio. \"This endpoint costs 12 empty round trips\" is a statement about
   the code. It stays roughly 12 on a fast box and a slow one, because a slow box
   slows the reference too.

   Two references, because one would be a lie
   =========================================
   Normalising a database-bound operation by a CPU loop measures the ratio of the
   host's disk to its ALU — a number about the machine, not about us. So there
   are two: a CPU-bound reference and a round-trip reference. The API scenarios
   here are round-trip-bound, so `:db-units` is the honest one; `:cpu-units` is
   there for anything that isn't.

   The honest limit
   ================
   This narrows the noise. It does not remove it. Normalisation cannot see a
   neighbour that steals the CPU only during the scenario and not during the
   calibration, and PERF_NOTES already records a real optimisation measuring
   \"~5% — inside the run's std-dev\". So these numbers are REPORTED and never
   gated — that job belongs to the counts in `perf/budgets.edn`, which have no
   std-dev at all. Expect this to catch \"twice as slow\", not \"10% slower\"."
  (:require
    [clojure.math :as math]
    [graphden.util.counters :as counters]
    [next.jdbc :as jdbc]))


(defn- median
  [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (when (pos? n)
      (if (odd? n)
        (nth v (quot n 2))
        (quot (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2)))))


(defn- time-ns
  [f]
  (let [t0 (System/nanoTime)]
    (f)
    (- (System/nanoTime) t0)))


(defn- sample
  "Median of `n` timings — median, not mean, so one scheduler hiccup or GC pause
   moves the reference by nothing instead of inflating it and making every
   scenario look artificially cheap."
  [n f]
  (dotimes [_ 3] (f))                                       ; warm the JIT
  (median (repeatedly n #(time-ns f))))


(defn cpu-reference-ns
  "Nanos for a fixed, allocation-free integer workload. Deliberately dull: the
   point is that it does the same work on every machine, so its runtime is a
   measure of the machine."
  []
  (sample 15 (fn []
               (loop [i 0 acc 0]
                 (if (< i 200000)
                   (recur (inc i) (unchecked-add acc (unchecked-multiply i 31)))
                   acc)))))


(defn db-reference-ns
  "Nanos for one trivial round trip. This is the unit the API scenarios are
   measured in, because that is what they are made of: `SELECT 1` costs a
   connection checkout, a wire round trip and a parse, and nothing else. An
   endpoint that costs 12 of these is doing about 12 round trips' worth of work,
   on any hardware."
  [ds]
  (sample 15 (fn [] (jdbc/execute-one! ds ["SELECT 1"]))))


(defn record!
  "Measure both references and record them as gauges, so every scenario in this
   run can be read as a ratio against them. Call once per suite."
  [ds]
  (let [cpu (cpu-reference-ns)
        db (db-reference-ns ds)]
    (counters/observe! :calibration/cpu-ref-ns cpu)
    (counters/observe! :calibration/db-ref-ns db)
    {:cpu-ns cpu :db-ns db}))


(defn units
  "`elapsed-ns` expressed in round trips. nil when the reference is missing —
   better an absent number than a made-up one."
  [elapsed-ns ref-ns]
  (when (and ref-ns (pos? ref-ns))
    (/ (math/round (/ (* 100.0 elapsed-ns) ref-ns)) 100.0)))
