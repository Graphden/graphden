(ns ^:serial graphden.system.init.cleanup-test
  "Tests for the hourly cleanup scheduler — the CADENCE decisions the
   init-key owns, not the sweeps themselves (each of those lives with the
   data it reclaims and is covered where it lives).

   Pinned here: the tombstone GC is opt-in and rate-limited to its own
   daily-ish gap, and one failing sweep never skips the others.

   `^:serial`: every test `with-redefs`es a sweep var (a process-wide
   root rebind) and one starts a real scheduled executor."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.retention :as retention]
    [graphden.crud.fn-execution.stats :as stats]
    [graphden.system.init.cleanup :as cleanup]
    [graphden.versioning.storage.core :as vcore]
    [integrant.core :as ig]))


(def ^:private day-ms (* 24 60 60 1000))


(defn- recording-gc
  "Run `f` with the tombstone sweep stubbed; returns the recorded
   `[[base-storage retention-ms] …]` calls. `retention-days` nil leaves
   the GC unconfigured (the shipped default)."
  [retention-days f]
  (let [calls (atom [])]
    (with-redefs [cleanup/tombstone-gc-retention-ms
                  (fn [] (some-> retention-days (* day-ms)))
                  vcore/tombstone-gc-sweep!
                  (fn [base retention-ms]
                    (swap! calls conj [base retention-ms])
                    {:fn 0})]
      (f))
    @calls))


(deftest tombstone-gc-is-opt-in
  ;; The safe default: a self-hosted operator who relies on tombstones for
  ;; audit must never have deleted rows silently reclaimed. If this arm
  ;; regressed, an unconfigured deployment would start hard-purging.
  (let [last-run (atom 0)
        calls (recording-gc nil #(cleanup/maybe-sweep-tombstones!
                                   ::storage last-run day-ms 5000000))]
    (is (= [] calls) "no retention configured → the version-table scan never runs")
    (is (zero? @last-run) "and the cadence clock is not armed either")))


(deftest tombstone-gc-honours-its-own-gap
  ;; The GC rides the HOURLY scheduler thread but is far heavier than the
  ;; execution sweep — running it every hour is the regression this gap
  ;; prevents.
  (let [now 100000000]
    (testing "inside the gap → skipped, last-run untouched"
      (let [last-run (atom (- now day-ms -1))       ; 1 ms short of the gap
            calls (recording-gc 7 #(cleanup/maybe-sweep-tombstones!
                                     ::storage last-run day-ms now))]
        (is (= [] calls))
        (is (= (- now day-ms -1) @last-run) "a skipped tick must not slide the clock")))
    (testing "gap exactly reached → sweeps and re-arms the clock"
      (let [last-run (atom (- now day-ms))
            calls (recording-gc 7 #(cleanup/maybe-sweep-tombstones!
                                     ::storage last-run day-ms now))]
        (is (= 1 (count calls)))
        (is (= (* 7 day-ms) (second (first calls)))
            "the configured retention reaches the sweep in MILLISECONDS")
        (is (= now @last-run) "clock re-armed to this tick, so the next one waits a full gap")))
    (testing "first tick after boot (last-run 0) always sweeps"
      (let [last-run (atom 0)
            calls (recording-gc 1 #(cleanup/maybe-sweep-tombstones!
                                     ::storage last-run day-ms now))]
        (is (= 1 (count calls)))
        (is (= day-ms (second (first calls))))))))


(deftest tombstone-gc-sweeps-the-unwrapped-storage
  ;; The GC purges version rows directly; handed a VersionedStorage it would
  ;; be re-filtered by the very branch view it is reclaiming behind.
  (let [last-run (atom 0)
        calls (recording-gc 1 #(cleanup/maybe-sweep-tombstones!
                                 ::not-versioned last-run 0 1))]
    (is (= ::not-versioned (ffirst calls))
        "unwrap passes a non-versioned handle through untouched")))


(defn- await-count
  [a n deadline-ms]
  (let [end (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (cond
        (>= (count @a) n) true
        (> (System/currentTimeMillis) end) false
        :else (do (Thread/sleep 10) (recur))))))


(deftest cleanup-tick-isolates-a-failing-sweep
  ;; Each reclamation is wrapped on its own. A throwing execution sweep
  ;; (e.g. a transient pool error) must not cost the usage-rollup retention
  ;; and the tombstone GC their turn — nor kill the scheduled task, which
  ;; `scheduleAtFixedRate` would do on an escaping throw.
  (let [seen (atom [])]
    (with-redefs [retention/sweep-executions! (fn [_pool]
                                                (swap! seen conj :executions)
                                                (throw (ex-info "pool down" {})))
                  stats/sweep-stats! (fn [_pool days] (swap! seen conj [:stats days]))
                  cleanup/maybe-sweep-tombstones! (fn [& _] (swap! seen conj :tombstones))]
      (let [scheduler (ig/init-key :exec/cleanup-scheduler
                                   {:context {} :period-ms 30})]
        (try
          (is (await-count seen 3 5000) "all three sweeps ran despite the first throwing")
          (is (= [:executions [:stats 90] :tombstones] (take 3 @seen))
              "order is execution log → usage rollups → tombstones, and rollups keep 90 days")
          (finally
            (ig/halt-key! :exec/cleanup-scheduler scheduler)))
        (let [after-halt (count @seen)]
          (Thread/sleep 150)
          (is (= after-halt (count @seen)) "halt-key! stops the cadence"))))))
