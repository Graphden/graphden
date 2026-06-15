(ns ci
  "CI runner with live progress display and coverage report.

   Concurrency contract: AT MOST ONE bb ci runs at a time per checkout.
   We acquire an exclusive flock on `/tmp/graphden-ci-<checkout-hash>.lock`
   at start; a second `bb ci` exits immediately with a pointer to the
   PID that holds the lock. This avoids the testcontainer dogpile where
   two parallel CIs each tried to `GenericContainer.start` a Postgres
   and ended up wedged on Docker daemon throughput.

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
   ;; tests (no cloverage) under :kaocha.plugin/parallel typically
   ;; finishes in ~3-5 min standalone but creeps to 7-8 min under
   ;; bb ci parallel load (cloverage in :coverage runs concurrently
   ;; and instruments the same NSes; CPU contention). Ceiling sized
   ;; so a real hang (test deadlock, testcontainer stuck on Docker
   ;; daemon) still surfaces — 15 min is well outside any realistic
   ;; legitimate runtime, well inside any genuine hang.
   "tests"     900000
   ;; Unit-only coverage measured at ~1:13. Ceiling sized 4× to
   ;; absorb cold-cache variance + cloverage's first-time JIT
   ;; warmup pass through instrumented namespaces.
   "coverage"  300000
   "outdated"  120000
   "security"  180000})


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
  "Path to the exclusion lockfile, keyed off the checkout dir hash so
   distinct working copies on the same host don't shadow each other."
  []
  (let [cwd (System/getProperty "user.dir")
        hex (-> cwd .hashCode (Math/abs) (Integer/toHexString))]
    (Paths/get "/tmp" (into-array String [(str "graphden-ci-" hex ".lock")]))))


(defn- read-lockfile-pid
  "Returns the PID stored in the lockfile (the bb ci that holds the
   lock), or \"unknown\" when the file doesn't exist yet."
  [path]
  (try
    (-> path .toFile slurp str/trim)
    (catch Exception _ "unknown")))


(defn- acquire-lock!
  "Returns `[channel lock]` on success. On contention prints a clear
   message naming the conflicting PID and `System/exit`s 2 — distinct
   from CI failure (1) so wrappers can distinguish a busy-lock from a
   real test break."
  []
  (let [path (lock-path)
        channel (FileChannel/open path
                                  (into-array StandardOpenOption
                                              [StandardOpenOption/CREATE
                                               StandardOpenOption/WRITE
                                               StandardOpenOption/READ]))
        lock (.tryLock channel)]
    (if (nil? lock)
      (do (println (str red "✗ bb ci is already running" reset
                        " (PID " (read-lockfile-pid path) ", lock " path ")"))
          (println (str yellow "  Wait for it to finish, or"
                        " stop it with: kill <PID>" reset))
          (.close channel)
          (System/exit 2))
      (do
        (.truncate channel 0)
        (let [pid (.pid (java.lang.ProcessHandle/current))
              buf (java.nio.ByteBuffer/wrap (.getBytes (str pid "\n") "UTF-8"))]
          (.write channel buf))
        [channel lock]))))


(defn- release-lock!
  [[channel lock]]
  (try (.release lock) (catch Exception _ nil))
  (try (.close channel) (catch Exception _ nil))
  (try (-> (lock-path) .toFile .delete) (catch Exception _ nil)))


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
            ;; bb ci runs the in-JVM `bb test` (kaocha + the parallel
            ;; plugin: :unit at parallelism 8, :integration at 4).
            ;; The earlier `bb test-parallel 4` workaround — 4 separate
            ;; JVMs — was put in because in-JVM consistently timed out
            ;; at the 600 s ceiling: ~38 s × N parallel bootstraps in
            ;; one heap, plus per-ns type-check sweeps, hit swap. The
            ;; executor-eager-compile refactor (lazy semantics via
            ;; Clojure-native delays, ~2000× faster worst-case),
            ;; shared golden DB via `CREATE DATABASE … TEMPLATE` (one
            ;; bootstrap per JVM × pkg-set, NS clones in ~100 ms),
            ;; per-execute DRY memo (handlers don't fire siblings
            ;; twice), and the rich-types race fix together brought
            ;; in-JVM under ceiling — measured 6:59 wall for all 1393
            ;; tests (0 failures). Single JVM avoids the
            ;; 4× container + 4× JVM startup cost the multi-worker
            ;; path was paying. The cloverage-instrumented variant
            ;; lives in `bb coverage` separately.
            test-cmd ["bb" "test"]
            ;; Unit-only coverage in CI. Full coverage (`bb
            ;; coverage-full`) is >20 min — cloverage's per-form
            ;; instrumentation × integration parallelism contention
            ;; on the counter atom — incompatible with the CI
            ;; budget. Unit-only finishes in ~1:13 and covers core
            ;; logic; integration paths stay uncovered here and need
            ;; the manual full pass when a coverage audit is wanted.
            coverage-cmd ["bb" "coverage"]
            outdated-cmd ["clojure" "-M:outdated"]
            biome-cmd ["npx" "biome" "check" "resources/packages/app/editor"]
            stylelint-cmd ["npx" "stylelint" "resources/packages/app/editor/**/*.css"]

            ;; Define checks. security check disabled — requires NVD API
            ;; key, run manually with `bb security`.
            checks [{:name "clj-kondo" :cmd kondo-cmd}
                    {:name "splint" :cmd splint-cmd}
                    {:name "cljstyle" :cmd cljstyle-cmd}
                    {:name "biome" :cmd biome-cmd}
                    {:name "stylelint" :cmd stylelint-cmd}
                    {:name "tests" :cmd test-cmd}
                    {:name "coverage" :cmd coverage-cmd}
                    {:name "outdated" :cmd outdated-cmd}]

            ;; Status tracking
            status (atom (into {} (map (fn [c] [(:name c) :running]) checks)))
            results (atom {})
            failed (atom false)
            start-time (System/currentTimeMillis)

            ;; Progress display
            print-status (fn []
                           (let [elapsed (/ (- (System/currentTimeMillis) start-time) 1000.0)
                                 line (str clear-line
                                           (str/join " │ "
                                                     (map (fn [c]
                                                            (str (status-char (get @status (:name c))) " " (:name c)))
                                                          checks))
                                           (format " │ %.1fs" elapsed))]
                             (print line)
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
