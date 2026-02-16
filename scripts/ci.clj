(ns ci
  "CI runner with live progress display and coverage report."
  (:require
    [babashka.process :as p]
    [clojure.string :as str]))


;; Colors
(def green "\u001b[32m")
(def red "\u001b[31m")
(def yellow "\u001b[33m")
(def bold "\u001b[1m")
(def reset "\u001b[0m")
(def clear-line "\u001b[2K\r")


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
    ;; outdated: check if antq found any outdated deps (shows :upgrade in table)
    "outdated" (str/includes? output ":upgrade")
    false))


(defn soft-check?
  "Returns true for checks that are informational and shouldn't fail CI.
   These checks provide useful info but failures don't block the build."
  [check-name]
  (contains? #{"outdated" "security"} check-name))


(defn status-char
  [s]
  (case s
    :running (str yellow "◐" reset)
    :passed (str green "✓" reset)
    :failed (str red "✗" reset)
    :warning (str yellow "⚠" reset)
    "?"))


(defn run-ci
  []
  (let [;; Build commands
        kondo-cmd (if (native-cmd? "clj-kondo")
                    ["clj-kondo" "--lint" "src" "test"]
                    ["clojure" "-M:kondo" "--lint" "src" "test"])
        cljstyle-cmd (if (native-cmd? "cljstyle")
                       ["cljstyle" "check" "src" "test"]
                       ["clojure" "-M:cljstyle" "check" "src" "test"])
        splint-cmd ["clojure" "-M:splint" "src" "test"]
        test-cmd ["clojure" "-M:dev:test:cloverage"
                  "--src-ns-path" "src"
                  "--test-ns-path" "test"
                  "--ns-regex" "graphden\\..*"
                  "--ns-exclude-regex" ".*-test"
                  "--ns-exclude-regex" ".*contract-tests"
                  "--ns-exclude-regex" ".*test-helpers"
                  "--ns-exclude-regex" ".*test-mocks"]
        outdated-cmd ["clojure" "-M:outdated"]
        security-cmd ["clojure" "-M:watson" "-p" "deps.edn"]

        ;; Define checks
        ;; NOTE: security check disabled - requires NVD API key, run manually with bb security
        checks [{:name "clj-kondo" :cmd kondo-cmd}
                {:name "splint" :cmd splint-cmd}
                {:name "cljstyle" :cmd cljstyle-cmd}
                {:name "tests+coverage" :cmd test-cmd}
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

        ;; Run single check
        run-check (fn [c]
                    (let [check-name (:name c)
                          cmd (:cmd c)
                          proc (p/process {:cmd cmd :out :string :err :string})
                          result @proc
                          output (str (:out result) "\n" (:err result))
                          exit (:exit result)
                          warnings? (has-warnings? check-name output)
                          soft? (soft-check? check-name)]
                      (swap! results assoc check-name {:exit exit :output output :warnings warnings?})
                      (cond
                        (not= 0 exit)
                        (do (swap! status assoc check-name :failed)
                            (when-not soft? (reset! failed true)))

                        warnings?
                        (do (swap! status assoc check-name :warning)
                            (when-not soft? (reset! failed true)))

                        :else
                        (swap! status assoc check-name :passed))))

        ;; Progress display thread
        progress-running (atom true)
        progress-thread (future
                          (while @progress-running
                            (print-status)
                            (Thread/sleep 200)))]

    ;; Run all checks in parallel
    (let [futures (mapv (fn [c] (future (run-check c))) checks)]

      ;; Wait for all to complete
      (doseq [f futures] @f)

      ;; Stop progress display
      (reset! progress-running false)
      @progress-thread
      (println))  ; Final newline

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
                        :warning (str " " yellow "WARNINGS" reset)
                        "")))

        ;; Show output for failures/warnings
        (when (or (= s :failed) (= s :warning))
          (println)
          (println (:output r))
          (println))))

    ;; Coverage summary from output
    (when-let [coverage-output (:output (get @results "tests+coverage"))]
      (println)
      (println (str bold "═══════════════════════════════════════════════════════════════" reset))
      (println (str bold "                      COVERAGE REPORT" reset))
      (println (str bold "═══════════════════════════════════════════════════════════════" reset))
      (println)
      ;; Extract and print coverage table
      (let [lines (str/split-lines coverage-output)
            table-start (first (keep-indexed (fn [i line] (when (str/includes? line "Namespace") i)) lines))
            table-end (first (keep-indexed (fn [i line]
                                             (when (and (> i (or table-start 0))
                                                        (str/includes? line "ALL FILES")) i))
                                           lines))]
        (when (and table-start table-end)
          (doseq [line (subvec (vec lines) table-start (inc table-end))]
            (println line))))
      (println))

    ;; Final summary
    (let [elapsed (/ (- (System/currentTimeMillis) start-time) 1000.0)
          passed-count (count (filter (fn [c] (= :passed (get @status (:name c)))) checks))
          total-count (count checks)]
      (println)
      (if @failed
        (do
          (println (str red bold "✗ CI FAILED" reset " (" passed-count "/" total-count " checks passed in " (format "%.1fs" elapsed) ")"))
          (System/exit 1))
        (println (str green bold "✓ CI PASSED" reset " (" total-count "/" total-count " checks passed in " (format "%.1fs" elapsed) ")"))))))


(run-ci)
