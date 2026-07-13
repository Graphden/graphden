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
;; Per-check timeouts. Each value is a CEILING — a healthy run finishes well
;; below it. The numbers are sized off observed local + CI durations × 2 to
;; absorb cold-cache + slow-disk variance without hiding a real hang.
;; ===========================================================================

(def ^:private check-timeout-ms
  {"clj-kondo" 120000
   "splint"    120000
   "cljstyle"  120000
   "biome"      60000
   "stylelint" 120000
   ;; The blocking test gate is `bb test-unit` — UNIT tests WITHOUT cloverage
   ;; (skip `^:integration`). Measured ~3 min (parallel ×8 + shared container).
   ;; Coverage is decoupled (`bb test-unit-coverage`, ~24 min, run separately)
   ;; because 21 of those 24 min are instrumentation overhead, not test signal.
   ;; Ceiling 10 min: generous headroom over ~3 min (CI parallel load + the
   ;; full-graph recompile tests) while a true hang (deadlock, unbounded) still
   ;; surfaces. Integration + e2e live at `bb test-integration` / `bb test-e2e`
   ;; and run outside CI on demand — budgets (~10-12 / ~15-25 min) in `bb.edn`.
   "tests-unit" 600000
   "outdated"  120000
   "security"  180000
   ;; Docker-based linters get extra headroom for first-run image
   ;; pulls (~30-60 s for a 1-3 MB hadolint / shellcheck image,
   ;; up to 90 s for gitleaks). Subsequent runs are sub-second
   ;; from the local image cache.
   "shellcheck" 120000
   "hadolint"   120000
   "gitleaks"   300000
   "typos"       60000
   "markdownlint" 60000
   ;; Newer cross-cutting linters; same docker-pull headroom as
   ;; above for the first run.
   "actionlint"   60000
   "lychee"       60000
   "trivy"       300000
   "license-check" 120000
   "commitlint"    60000})


(def ^:private default-check-timeout-ms 60000)


(defn native-cmd?
  [cmd]
  (= 0 (:exit (p/shell {:out :string :err :string :continue true} (str "which " cmd)))))


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
        timeout-ms (get check-timeout-ms check-name default-check-timeout-ms)
        proc (p/process {:cmd cmd :out :string :err :string})
        _ (swap! live-procs conj proc)
        result (deref proc timeout-ms ::timeout)]
    (swap! live-procs disj proc)
    (cond
      (= result ::timeout)
      (do (try (.destroyForcibly (:proc proc)) (catch Exception _ nil))
          (swap! results assoc check-name
                 {:exit -1
                  :output (str "TIMEOUT after " (/ timeout-ms 1000) " s\n"
                               "Command: " (pr-str cmd))
                  :warnings false})
          (swap! status assoc check-name :timeout)
          (reset! failed true))

      :else
      (let [output (str (:out result) "\n" (:err result))
            exit (:exit result)
            warnings? (has-warnings? check-name output)]
        (swap! results assoc check-name
               {:exit exit :output output :warnings warnings?})
        (cond
          (not= 0 exit)
          (do (swap! status assoc check-name :failed)
              (reset! failed true))

          warnings?
          (do (swap! status assoc check-name :warning)
              (reset! failed true))

          :else
          (swap! status assoc check-name :passed))))))


(defn run-ci
  []
  (let [lock-handle (acquire-lock!)]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread.
        ^Runnable
        (fn []
          (kill-live-procs!)
          (release-lock! lock-handle))))
    (try
      (let [;; Build commands
            ;; Lint paths match `bb check`'s all-paths: src + test + the
            ;; package impls.clj files (previously un-linted, see
            ;; .clj-kondo/config.edn for the namespace-name-mismatch
            ;; carve-out).
            lint-paths ["src" "test" "resources/packages"]
            kondo-cmd (concat (if (native-cmd? "clj-kondo")
                                ["clj-kondo" "--lint"]
                                ["clojure" "-M:kondo" "--lint"])
                              lint-paths)
            cljstyle-cmd (concat (if (native-cmd? "cljstyle")
                                   ["cljstyle" "check"]
                                   ["clojure" "-M:cljstyle" "check"])
                                 lint-paths)
            splint-cmd (concat ["clojure" "-M:splint"] lint-paths)
            ;; bb ci runs UNIT tests WITHOUT cloverage instrumentation — the
            ;; blocking gate is pass/fail, and that's ~3 min. Measured 2026-07:
            ;; the same suite UNDER cloverage is ~24 min (~3 min tests + ~21 min
            ;; instrumentation — cloverage instruments every `graphden.*` form
            ;; and the handful of full-graph recompile tests fire all of them,
            ;; ~50×). Coupling a 3-min pass/fail signal to 21 min of coverage
            ;; measurement was the wrong trade, so coverage is DECOUPLED: run
            ;; `bb test-unit-coverage` separately (on demand / nightly), not in
            ;; the per-push ci gate.
            ;;
            ;; Integration + e2e live at `bb test-integration` and
            ;; `bb test-e2e` — manual runs before merging changes that
            ;; touch storage / system / routes (integration) or the
            ;; editor UI (e2e). Rationale: integration is ~10-12 min,
            ;; e2e is ~15-25 min; a unit-level regression (lint /
            ;; type-check / pure-fn test) shouldn't gate on them.
            ;; Combined `bb test` (unit + integration) and `bb test-all`
            ;; (everything) stay available for the manual full passes.
            test-cmd ["bb" "test-unit"]
            outdated-cmd ["clojure" "-M:outdated"]
            biome-cmd ["npx" "biome" "check" "resources/packages/app/editor"]
            stylelint-cmd ["npx" "stylelint" "resources/packages/app/editor/**/*.css"]
            ;; Cross-cutting linters introduced as a single batch
            ;; alongside the existing kondo/splint/cljstyle. Each is
            ;; either docker-based (no host install) or pulls the
            ;; binary into `.tools/` on first run — see bb.edn task
            ;; docs for the install story per tool.
            pwd (System/getenv "PWD")
            shellcheck-cmd ["docker" "run" "--rm"
                            "-v" (str pwd ":/src") "-w" "/src"
                            "koalaman/shellcheck:stable"
                            "tools/browser-test/run-edit-tests.sh"
                            ".claude/hooks/check-ui-on-stop.sh"
                            ".claude/hooks/remind-packages-quality-on-impls-edit.sh"]
            ;; hadolint reads Dockerfile from stdin — wrap it through
            ;; `bb hadolint` which iterates Dockerfile + Dockerfile.build.
            hadolint-cmd ["bb" "hadolint"]
            gitleaks-cmd ["docker" "run" "--rm"
                          "-v" (str pwd ":/repo") "-w" "/repo"
                          "zricethezav/gitleaks:latest"
                          "detect" "--no-banner" "--no-git"]
            typos-cmd ["bb" "typos"]
            markdownlint-cmd ["npx" "markdownlint-cli2"]
            actionlint-cmd ["docker" "run" "--rm"
                            "-v" (str pwd ":/repo") "-w" "/repo"
                            "rhysd/actionlint:1.7.7" "-color"]
            lychee-cmd ["docker" "run" "--rm"
                        "-v" (str pwd ":/data") "-w" "/data"
                        "lycheeverse/lychee:sha-467197f-alpine"
                        "--offline" "--no-progress"
                        "--exclude-path" "node_modules"
                        "--exclude-path" "target"
                        "--exclude-path" ".tools"
                        "--exclude-path" ".playwright-mcp"
                        "**/*.md"]
            trivy-cmd ["bb" "trivy"]
            license-check-cmd ["bb" "license-check"]
            commitlint-cmd ["bb" "commitlint"]

            ;; Define checks. security check disabled — requires NVD API
            ;; key, run manually with `bb security`. The `tests-unit` check
            ;; is the blocking test gate: kaocha :unit, NO cloverage (~3 min).
            ;; Coverage is decoupled — `bb test-unit-coverage` / `bb coverage`,
            ;; run separately.
            checks [{:name "clj-kondo" :cmd kondo-cmd}
                    {:name "splint" :cmd splint-cmd}
                    {:name "cljstyle" :cmd cljstyle-cmd}
                    {:name "biome" :cmd biome-cmd}
                    {:name "stylelint" :cmd stylelint-cmd}
                    {:name "shellcheck" :cmd shellcheck-cmd}
                    {:name "hadolint" :cmd hadolint-cmd}
                    {:name "gitleaks" :cmd gitleaks-cmd}
                    {:name "typos" :cmd typos-cmd}
                    {:name "markdownlint" :cmd markdownlint-cmd}
                    {:name "actionlint" :cmd actionlint-cmd}
                    {:name "lychee" :cmd lychee-cmd}
                    {:name "trivy" :cmd trivy-cmd}
                    {:name "license-check" :cmd license-check-cmd}
                    {:name "commitlint" :cmd commitlint-cmd}
                    {:name "tests-unit" :cmd test-cmd}
                    {:name "outdated" :cmd outdated-cmd}]

            ;; Status tracking
            status (atom (into {} (map (fn [c] [(:name c) :running]) checks)))
            results (atom {})
            failed (atom false)
            start-time (System/currentTimeMillis)

            ;; Progress display. The status line is rendered every
            ;; 200 ms and is clamped to a single terminal row — no
            ;; row-tracking / cursor-up, so we can never overshoot
            ;; above the start of the status output and erase the
            ;; user's terminal scrollback.
            ;;
            ;; Format:
            ;;   +3 ⚠1 ✗1 ◐3 │ ✗ cljstyle │ ◐ tests biome … │ 12.3s
            ;;   └counter────┘ └failed────┘ └running greedy┘ └time┘
            ;;
            ;; Counter shows each non-zero category once. Failed names
            ;; always fit (the user cares about them most); running
            ;; names are added greedily within the remaining budget,
            ;; truncated with `…` when they don't all fit.
            cols (terminal-cols)
            sep " │ "
            print-status
            (fn []
              (let [elapsed-s (/ (- (System/currentTimeMillis) start-time) 1000.0)
                    elapsed-part (str sep (format "%.1fs" elapsed-s))
                    statuses @status
                    n-passed (count (filter #(= :passed (val %)) statuses))
                    n-warn (count (filter #(= :warning (val %)) statuses))
                    n-failed (count (filter #(#{:failed :timeout} (val %)) statuses))
                    n-running (count (filter #(= :running (val %)) statuses))
                    failed-names (->> checks
                                      (filter #(#{:failed :timeout} (get statuses (:name %))))
                                      (mapv :name))
                    running-names (->> checks
                                       (filter #(= :running (get statuses (:name %))))
                                       (mapv :name))
                    counter (str/join " "
                                      (cond-> []
                                        (pos? n-passed) (conj (str green "+" n-passed reset))
                                        (pos? n-warn) (conj (str yellow "⚠" n-warn reset))
                                        (pos? n-failed) (conj (str red "✗" n-failed reset))
                                        (pos? n-running) (conj (str yellow "◐" n-running reset))))
                    failed-part (when (seq failed-names)
                                  (str sep red "✗" reset " " (str/join " " failed-names)))
                    running-marker (str sep yellow "◐" reset " ")
                    cols-budget (dec cols)
                    fixed-len (+ (visible-len counter)
                                 (visible-len (or failed-part ""))
                                 (visible-len elapsed-part))
                    running-budget (- cols-budget fixed-len (visible-len running-marker))
                    ;; Greedy fit running names; reserve 2 cols for " …"
                    ;; until we know whether overflow occurs.
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
                                   (str running-marker fitted (when overflow? " …")))
                    line (str counter
                              (or failed-part "")
                              (or running-part "")
                              elapsed-part)]
                (print (str "\r\033[2K" line))
                (flush)))

            ;; Progress display thread
            progress-running (atom true)
            progress-thread (future
                              (while @progress-running
                                (print-status)
                                (Thread/sleep 200)))]

        ;; Run all checks in parallel, each with its own timeout.
        (let [futures (mapv (fn [c] (future (run-check c status results failed))) checks)]

          ;; Wait for all to complete
          (doseq [f futures] @f)

          ;; Stop progress display
          (reset! progress-running false)
          @progress-thread
          (println))                ; Final newline

        ;; Show results
        (println)
        (println (str bold "═══════════════════════════════════════════════════════════════" reset))
        (println (str bold "                         CI RESULTS" reset))
        (println (str bold "═══════════════════════════════════════════════════════════════" reset))
        (println)

        ;; Show each check result
        (doseq [c checks]
          (let [check-name (:name c)
                r (get @results check-name)
                s (get @status check-name)]
            (println (str (status-char s) " " bold check-name reset
                          (case s
                            :passed (str " " green "PASSED" reset)
                            :failed (str " " red "FAILED" reset)
                            :timeout (str " " red "TIMED OUT" reset)
                            :warning (str " " yellow "WARNINGS" reset)
                            "")))

            ;; Show output for failures/warnings/timeouts
            (when (#{:failed :warning :timeout} s)
              (println)
              (println (:output r))
              (println))))

        ;; Coverage report moved to `bb coverage` — bb ci runs the
        ;; plain tests suite for speed.

        ;; Final summary
        (let [elapsed (/ (- (System/currentTimeMillis) start-time) 1000.0)
              passed-count (count (filter (fn [c] (= :passed (get @status (:name c)))) checks))
              total-count (count checks)]
          (println)
          (if @failed
            (do
              (println (str red bold "✗ CI FAILED" reset
                            " (" passed-count "/" total-count
                            " checks passed in " (format "%.1fs" elapsed) ")"))
              (System/exit 1))
            (println (str green bold "✓ CI PASSED" reset
                          " (" total-count "/" total-count
                          " checks passed in " (format "%.1fs" elapsed) ")")))))
      (finally
        (release-lock! lock-handle)))))


(run-ci)
