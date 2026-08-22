(ns ci
  "CI runner with live progress display.

   Concurrency contract: AT MOST ONE bb ci runs at a time PER HOST.
   We take an exclusive lock on `/tmp/graphden-ci.lock` at start; a second
   `bb ci` — from any checkout or worktree — waits its turn rather than
   running alongside. This avoids the testcontainer dogpile (two CIs each
   starting a Postgres and wedging on Docker daemon throughput) and, just
   as importantly, the CPU dogpile: the timeouts below are ceilings
   measured on an idle box, so concurrent runs time each other out instead
   of finishing sooner.

   Hang containment: every check has a hard timeout (see `check-timeout`
   below). A wedged check (Docker stall, kaocha deadlock, network hiccup
   on antq) is killed at the deadline, reported as failed-by-timeout,
   and the suite continues so the user always gets a result line."
  (:require
    [babashka.process :as p]
    [clojure.string :as str])
  (:import
    (java.nio.channels
      FileChannel)
    (java.nio.file
      Paths
      StandardOpenOption)))


;; Colors
(def green "[32m")
(def red "[31m")
(def yellow "[33m")
(def bold "[1m")
(def reset "[0m")
(def clear-line "[2K\r")


(defn- terminal-cols
  "Best-effort terminal width detection. Falls back to 120 cols
   when not running under a real TTY (piped output, CI logs, …) —
   wider than the typical 80-col default but narrow enough for
   the `print-status` clamping to kick in for the common laptop
   case before the elapsed counter pushes the line into wrap."
  []
  (or (try (some-> (System/getenv "COLUMNS") Integer/parseInt) (catch Exception _ nil))
      (try (let [r (p/shell {:out :string :continue true} "tput" "cols")]
             (when (zero? (:exit r))
               (Integer/parseInt (str/trim (:out r)))))
           (catch Exception _ nil))
      120))


(defn- visible-len
  "Length of `s` with ANSI SGR sequences stripped — colour codes
   render to zero visible width but the raw `(count …)` would
   include them and over-count, forcing premature truncation."
  [s]
  (count (str/replace s #"\[[0-9;]*[A-Za-z]" "")))


;; ===========================================================================
;; Per-check timeouts live in the registry (`scripts/checks.edn`) alongside each
;; check's bb task + group — one source of truth, read below. Each value is a
;; CEILING: a healthy run finishes well below it; a true hang (docker stall,
;; kaocha deadlock, antq network hiccup) is killed at the deadline and reported.
;; ===========================================================================

;; Selection core (registry read + validation + --groups/--since/--skip
;; partitioning) lives in `scripts/ci_select.clj` so the `ci-selftest` check
;; can load and test it WITHOUT running a CI pass.
(load-file "scripts/ci_select.clj")


(def ^:private registry
  "The check registry — a vector of `{:name :bb :timeout :group :relevant}`,
   from `scripts/checks.edn`. `:bb` is the task that actually runs the check,
   so the command is defined ONCE (in bb.edn) and this runner delegates to it.
   Validated on EVERY run — a malformed entry (bad :relevant regex, missing
   :timeout, typo'd :group) fails here, at authoring time, instead of
   detonating mid-wave or only inside the landing gate's scoped run."
  (ci-select/validate-registry!
    (ci-select/read-registry "scripts/checks.edn")))


(defn- select-checks
  [args]
  (ci-select/select-checks registry args))


(defn has-warnings?
  [check-name output]
  (case check-name
    ;; clj-kondo: check for actual warnings (not just the word in status line)
    "clj-kondo" (and (or (str/includes? output "warning:")
                         (str/includes? output " warnings, "))
                     (not (str/includes? output "0 warnings, 0 errors")))
    ;; splint: check for style warnings (but not "0 style warnings")
    "splint" (and (str/includes? output "style warning")
                  (not (str/includes? output "0 style warning")))
    ;; cljstyle: actual formatting issues
    "cljstyle" (str/includes? output "formatted incorrectly")
    ;; outdated: any antq row tagged for upgrade
    "outdated" (str/includes? output ":upgrade")
    ;; biome: any warnings.
    "biome" (str/includes? output "warnings.")
    false))


(defn status-char
  [s]
  (case s
    :running (str yellow "◐" reset)
    :passed (str green "✓" reset)
    :failed (str red "✗" reset)
    :timeout (str red "⏱" reset)
    :warning (str yellow "⚠" reset)
    :skipped (str yellow "⊘" reset)
    :scoped (str yellow "⊘" reset)
    :manual-skip (str yellow "⊘" reset)
    "?"))


;; ===========================================================================
;; Lock-file mutual exclusion
;; ===========================================================================

(defn- lock-path
  "Path to the exclusion lockfile. MACHINE-WIDE, not per-checkout.

   It used to be keyed off the checkout dir hash, on the theory that
   distinct working copies are independent. They are not: a `bb ci` run
   saturates the host (10 linters in parallel, then the unit suite), and
   the per-check timeouts here are ceilings measured on an idle box. Two
   agents in two worktrees running `bb ci` at once therefore do not merely
   run slower — they time each other out. Observed on an 8-core host: two
   concurrent runs, 9 of 17 checks lost to TIMEOUT at load average 75,
   with nothing wrong in either branch.

   One lock per machine turns that into a queue: editing stays parallel
   across worktrees, `bb ci` takes its turn, and a green run means green."
  []
  (Paths/get "/tmp" (into-array String ["graphden-ci.lock"])))


(defn- read-lockfile-pid
  "Returns the PID stored in the lockfile (the bb ci that holds the
   lock), or \"unknown\" when the file doesn't exist yet."
  [path]
  (try
    (-> path .toFile slurp str/trim)
    (catch Exception _ "unknown")))


(defn- acquire-lock!
  "Returns `[channel lock]`, WAITING for the machine-wide lock if another
   run holds it.

   Contention is now the normal case, not an error: several agents share
   one host and each checkpoints with `bb ci`. Failing fast on a busy lock
   would be worse than useless — the landing gate runs `bb ci` too, so any
   agent's routine run would bounce the gate as a red FAIL. Block instead,
   announce who we are waiting for, and take our turn."
  []
  (let [path (lock-path)
        channel (FileChannel/open path
                                  (into-array StandardOpenOption
                                              [StandardOpenOption/CREATE
                                               StandardOpenOption/WRITE
                                               StandardOpenOption/READ]))
        lock (or (.tryLock channel)
                 (do (println (str yellow "⏳ another bb ci holds the machine-wide lock"
                                   reset " (PID " (read-lockfile-pid path) ") — waiting our turn ..."))
                     (println (str "   (one CI at a time per host: concurrent runs time"
                                   " each other out, they do not go faster)"))
                     (.lock channel)))]
    (.truncate channel 0)
    (let [pid (.pid (java.lang.ProcessHandle/current))
          buf (java.nio.ByteBuffer/wrap (.getBytes (str pid "\n") "UTF-8"))]
      (.write channel buf))
    [channel lock]))


(defn- release-lock!
  "Release and close — but do NOT unlink the lockfile. A waiter already holds
   an open channel on this inode; deleting it lets the next run CREATE a fresh
   file and lock that instead, so two runs would believe they hold the lock.
   The empty file is 0 bytes and costs nothing to keep."
  [[channel lock]]
  (try (.release lock) (catch Exception _ nil))
  (try (.close channel) (catch Exception _ nil)))


;; ===========================================================================
;; Process tracking — every spawned check registers here so we can kill
;; every child on Ctrl-C / abnormal exit without leaving orphan kaocha
;; JVMs holding a testcontainer.
;; ===========================================================================

(def ^:private live-procs
  "Atom of running `babashka.process` records — populated by `run-check`
   for the duration of its child process and drained on completion."
  (atom #{}))


(defn- kill-live-procs!
  []
  (doseq [proc @live-procs]
    (try (.destroyForcibly (:proc proc)) (catch Exception _ nil))))


;; ===========================================================================
;; Check runner with timeout + proc tracking
;; ===========================================================================

(defn- run-check
  [c status results failed]
  (let [check-name (:name c)
        cmd (:cmd c)
        timeout-ms (:timeout c)
        started (System/nanoTime)
        proc (p/process {:cmd cmd :out :string :err :string})
        _ (swap! live-procs conj proc)
        result (deref proc timeout-ms ::timeout)
        ;; Wall time of THIS check, recorded whatever the outcome. The run used
        ;; to report one number — total elapsed — which cannot answer "which
        ;; check got slower", and the per-check `:timeout` ceilings in
        ;; `checks.edn` were set from numbers nobody kept. A timed-out check
        ;; still gets a duration: it is the ceiling, and seeing it sit at the
        ;; ceiling is the point.
        duration-ms (quot (- (System/nanoTime) started) 1000000)]
    (swap! live-procs disj proc)
    (cond
      (= result ::timeout)
      (do (try (.destroyForcibly (:proc proc)) (catch Exception _ nil))
          ;; Salvage what the child printed BEFORE the axe: kaocha's
          ;; dots-so-far name the namespace that was still running,
          ;; which is the whole diagnosis. Discarding it made a
          ;; timeout verdict contentless (audit-6).
          (swap! results assoc check-name
                 {:exit -1
                  :output (let [r (deref proc 5000 nil)]
                            (str "TIMEOUT after " (/ timeout-ms 1000) " s\n"
                                 "Command: " (pr-str cmd)
                                 (when (:out r) (str "\n--- partial stdout ---\n" (:out r)))
                                 (when (seq (:err r)) (str "\n--- partial stderr ---\n" (:err r)))))
                  :warnings false
                  :duration-ms duration-ms})
          ;; An `:info` check that times out is still advisory — a hung
          ;; `outdated`/antq network call is the very "network hiccup"
          ;; these checks are expected to have, and must NOT fail the run
          ;; (mirrors the `:else` branch's `block!`; this arm used to
          ;; `reset! failed` unconditionally).
          (let [info? (= :info (:group c))]
            (swap! status assoc check-name (if info? :warning :timeout))
            (when-not info? (reset! failed true))))

      :else
      (let [output (str (:out result) "\n" (:err result))
            exit (:exit result)
            warnings? (has-warnings? check-name output)]
        (swap! results assoc check-name
               {:exit exit :output output :warnings warnings?
                :duration-ms duration-ms})
        ;; `:info` checks (e.g. `outdated` — a dependency has an upgrade
        ;; available) are advisory: they surface a status but NEVER fail the run
        ;; or gate the unit suite. Everything else is blocking.
        (let [info? (= :info (:group c))
              block! (fn [] (when-not info? (reset! failed true)))]
          (cond
            (not= 0 exit)
            (do (swap! status assoc check-name (if info? :warning :failed))
                (block!))

            warnings?
            (do (swap! status assoc check-name :warning)
                (block!))

            :else
            (swap! status assoc check-name :passed)))))))


(defn- report-skips!
  "Print what this run is NOT doing. Skipped checks are REPORTED, never
   silently dropped — a green scoped run must read as scoped in the log."
  [scoped manual since]
  (when (seq scoped)
    (println (str yellow "⊘ out of scope" reset " (--since " since
                  ", per scripts/checks.edn :relevant): "
                  (str/join " " (map :name scoped)))))
  (when (seq manual)
    (println (str yellow "⊘ MANUALLY SKIPPED" reset " (--skip): "
                  (str/join " " (map :name manual))
                  " — this run is PARTIAL by operator choice"))))


(defn- status-line
  "The one-row progress line, clamped to `cols`:

     +3 ⚠1 ✗1 ◐3 │ ✗ cljstyle │ ◐ tests biome … │ 12.3s
     └counter────┘ └failed────┘ └running greedy┘ └time┘

   The counter shows each non-zero category once. Failed names ALWAYS
   fit — they are what the reader cares about; running names are added
   greedily within whatever budget is left and truncated with `…`.

   Pure: takes the status snapshot and returns the string, so the
   fitting logic can be read (and reasoned about) without a terminal."
  [checks statuses elapsed-s cols]
  (let [sep " │ "
        elapsed-part (str sep (format "%.1fs" elapsed-s))
        n-of (fn [pred] (count (filter #(pred (val %)) statuses)))
        n-passed (n-of #(= :passed %))
        n-warn (n-of #(= :warning %))
        n-failed (n-of #{:failed :timeout})
        n-running (n-of #(= :running %))
        names-with (fn [pred]
                     (mapv :name (filter #(pred (get statuses (:name %))) checks)))
        failed-names (names-with #{:failed :timeout})
        running-names (names-with #(= :running %))
        counter (str/join " "
                          (cond-> []
                            (pos? n-passed) (conj (str green "+" n-passed reset))
                            (pos? n-warn) (conj (str yellow "⚠" n-warn reset))
                            (pos? n-failed) (conj (str red "✗" n-failed reset))
                            (pos? n-running) (conj (str yellow "◐" n-running reset))))
        failed-part (when (seq failed-names)
                      (str sep red "✗" reset " " (str/join " " failed-names)))
        running-marker (str sep yellow "◐" reset " ")
        fixed-len (+ (visible-len counter)
                     (visible-len (or failed-part ""))
                     (visible-len elapsed-part))
        running-budget (- (dec cols) fixed-len (visible-len running-marker))
        ;; Greedy fit; reserve 2 cols for " …" until we know whether
        ;; overflow occurs.
        [fitted overflow?]
        (if (and (seq running-names) (pos? running-budget))
          (loop [acc "" remaining running-names]
            (if (empty? remaining)
              [acc false]
              (let [nm (first remaining)
                    candidate (if (empty? acc) nm (str acc " " nm))]
                (if (<= (+ (count candidate) 2) running-budget)
                  (recur candidate (next remaining))
                  [acc (seq acc)]))))
          ["" false])
        running-part (when (seq fitted)
                       (str running-marker fitted (when overflow? " …")))]
    (str counter (or failed-part "") (or running-part "") elapsed-part)))


(defn- run-waves!
  "Three waves, fail-fast.

   The lint / commit / info checks run in parallel first (~1 min); the
   unit suite (`:test`, ~3 min) runs ONLY if they all pass. A formatting
   slip should not cost the unit suite — the exact waste that prompted
   this. When `--groups` selects no `:test` check (e.g. `bb lint`), the
   later waves are empty and this is just a lint run.

   `:post-test` is a third wave because it READS what the test wave
   wrote: `bb perf` compares `perf/runs/*.edn`, which the suites emit as
   they run. In the test wave it would race them and grade the previous
   run's numbers — passing on a regression it never saw. A red suite
   also makes the perf report meaningless (half the scenarios may not
   have run, so every count reads low and every budget passes), so it is
   skipped rather than reported as a reassuring lie."
  [checks status results failed]
  (let [wave (fn [cs]
               (doseq [f (mapv (fn [c] (future (run-check c status results failed))) cs)]
                 @f))
        by-group (group-by :group checks)
        test-checks (:test by-group)
        post-checks (:post-test by-group)
        pre-checks (remove #(#{:test :post-test} (:group %)) checks)
        skip-all! (fn [cs] (doseq [c cs] (swap! status assoc (:name c) :skipped)))]
    (wave pre-checks)
    (if @failed
      (skip-all! (concat test-checks post-checks))
      (do (wave test-checks)
          (if @failed
            (skip-all! post-checks)
            (wave post-checks))))))


(defn- print-check-result!
  "One check's verdict line, plus its output when it did not pass."
  [c status results since]
  (let [check-name (:name c)
        r (get results check-name)
        s (get status check-name)
        ms (:duration-ms r)
        ;; A check that runs at 80%+ of its ceiling is not passing, it is
        ;; about to start timing out — on a busier host, or after the next
        ;; change. That is the moment to say so, not the run after it flips
        ;; red. `checks.edn` calls its timeouts "measured on an idle box";
        ;; this is what tells you when a box stopped being idle enough.
        near-ceiling? (and ms (:timeout c) (>= ms (* 0.8 (:timeout c))))]
    (println (str (status-char s) " " bold check-name reset
                  (case s
                    :passed (str " " green "PASSED" reset)
                    :failed (str " " red "FAILED" reset)
                    :timeout (str " " red "TIMED OUT" reset)
                    :warning (str " " yellow "WARNINGS" reset)
                    :skipped (str " " yellow "SKIPPED" reset " (lint failed first)")
                    :scoped (str " " yellow "SKIPPED" reset " (out of scope — no relevant files changed since " since ")")
                    :manual-skip (str " " yellow "SKIPPED" reset " (--skip: operator choice)")
                    "")
                  (when ms (format "  %.1fs" (/ ms 1000.0)))
                  (when near-ceiling?
                    (str " " yellow "(" (format "%.0f%%" (* 100.0 (/ ms (double (:timeout c)))))
                         " of its " (format "%.0fs" (/ (:timeout c) 1000.0)) " ceiling)" reset))))
    (when (#{:failed :warning :timeout} s)
      (println)
      (println (:output r))
      (println))))


(defn- print-report!
  "The results block: every check in REGISTRY order, skipped ones
   included, so a scoped run's report still lists what it did NOT do."
  [checks scoped manual status results since]
  (println)
  (println (str bold "═══════════════════════════════════════════════════════════════" reset))
  (println (str bold "                         CI RESULTS" reset))
  (println (str bold "═══════════════════════════════════════════════════════════════" reset))
  (println)
  (let [order (into {} (map-indexed (fn [i c] [(:name c) i]) registry))]
    (doseq [c (sort-by (comp order :name) (concat checks scoped manual))]
      (print-check-result! c status results since))))


(defn- print-verdict!
  "The last line, which is what a reader trusts. A scoped or partial
   pass must SAY so."
  [checks scoped manual status failed? elapsed-s]
  (let [passed-count (count (filter (fn [c] (= :passed (get status (:name c)))) checks))
        ;; Skipped (unit suite gated off by a lint failure) is not "failed"
        ;; per-se, but it wasn't run — exclude it from the denominator so
        ;; the count reflects what actually executed.
        total-count (count (remove (fn [c] (= :skipped (get status (:name c)))) checks))
        scope-note (str (when (seq scoped) (str "; " (count scoped) " out of scope"))
                        (when (seq manual) (str "; " (count manual) " SKIPPED by --skip")))]
    (println)
    (if failed?
      (do (println (str red bold "✗ CI FAILED" reset
                        " (" passed-count "/" total-count
                        " checks passed in " (format "%.1fs" elapsed-s) scope-note ")"))
          (System/exit 1))
      (println (str green bold "✓ CI PASSED" reset
                    " (" total-count "/" total-count
                    " checks passed in " (format "%.1fs" elapsed-s) scope-note ")")))))


(defn run-ci
  "Select the checks, run them in waves behind a live progress line,
   report. Coverage lives in `bb coverage` — `bb ci` runs the plain
   tests suite for speed."
  []
  (let [;; The check set + its metadata (bb task, timeout, group, relevant
        ;; paths) come from the registry in `scripts/checks.edn`; `--groups`
        ;; narrows it, `--since` diff-scopes it, `--skip` force-skips.
        {checks :run scoped :scoped manual :manual since :since}
        (select-checks *command-line-args*)
        ;; The host-wide lock exists to keep two testcontainer stacks off one
        ;; Docker daemon — so it is only needed when the unit suite runs. A
        ;; lint-only run (`bb lint`, `bb lint-clj`) skips it and never waits on a
        ;; gate's `bb ci`.
        needs-lock? (some #(= :test (:group %)) checks)
        lock-handle (do (report-skips! scoped manual since)
                        (when needs-lock? (acquire-lock!)))]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread.
        ^Runnable
        (fn []
          (kill-live-procs!)
          (when lock-handle (release-lock! lock-handle)))))
    (try
      (let [status (atom (merge (into {} (map (fn [c] [(:name c) :running]) checks))
                                (into {} (map (fn [c] [(:name c) :scoped]) scoped))
                                (into {} (map (fn [c] [(:name c) :manual-skip]) manual))))
            results (atom {})
            failed (atom false)
            start-time (System/currentTimeMillis)
            elapsed-s #(/ (- (System/currentTimeMillis) start-time) 1000.0)
            ;; The status line is re-rendered every 200 ms into a SINGLE
            ;; terminal row — no row-tracking / cursor-up, so we can never
            ;; overshoot above the start of the status output and erase the
            ;; user's scrollback.
            cols (terminal-cols)
            progress-running (atom true)
            progress-thread (future
                              (while @progress-running
                                (print (str "\r\033[2K"
                                            (status-line checks @status (elapsed-s) cols)))
                                (flush)
                                (Thread/sleep 200)))]
        (run-waves! checks status results failed)
        (reset! progress-running false)
        @progress-thread
        (println)                     ; close the progress row
        (print-report! checks scoped manual @status @results since)
        (print-verdict! checks scoped manual @status @failed (elapsed-s)))
      (finally
        (when lock-handle (release-lock! lock-handle))))))


(run-ci)
