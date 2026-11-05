(ns ci-proc-test
  "Self-test for killing a timed-out CI check (`scripts/ci_proc.clj`). Runs
   inside the `ci-selftest` registry check (`bb ci-test`).

   Standalone bb script: exit 0 = PASS, 1 = FAIL."
  (:require
    [babashka.process :as p]
    [ci-proc]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]])
  (:import
    (java.lang
      ProcessHandle)))


(defn- descendants-of
  [proc]
  (-> (Process/.toHandle (:proc proc)) ProcessHandle/.descendants .iterator iterator-seq doall))


(deftest a-timed-out-check-with-a-grandchild-is-killed-and-salvaged
  ;; The shape of `bb kondo`: the direct child starts another process that
  ;; shares its stdout, and is still printing when the timeout fires.
  (let [proc (p/process {:cmd ["bash" "-c" "bash -c 'echo first; for i in $(seq 100); do echo $i; sleep 0.1; done'; echo never"]
                         :out :string :err :string})
        _ (Thread/sleep 500)
        grandchildren (descendants-of proc)
        _ (is (= ::timeout (deref proc 300 ::timeout)))
        salvaged (ci-proc/kill-and-salvage! proc)]
    (is (seq grandchildren) "the fixture really has a grandchild")
    (is (some? salvaged) "what the check printed is readable, not `Stream closed`")
    (is (str/includes? (str (:out salvaged)) "first"))
    (Thread/sleep 200)
    (is (not-any? ProcessHandle/.isAlive grandchildren)
        "no orphaned grandchild keeps running after the timeout")))


(deftest active-check-can-finish-past-its-normal-ceiling
  (let [proc (p/process {:cmd ["bash" "-c"
                               "yes > /dev/null & worker=$!; sleep 1.2; kill $worker; wait $worker 2>/dev/null; exit 0"]
                         :out :string :err :string})]
    (try
      (is (zero? (:exit (ci-proc/await-active-result
                          proc 500 {:poll-ms 50 :stale-ms 800 :extension-ms 2000}))))
      (finally (ci-proc/kill-and-salvage! proc)))))


(deftest active-check-still-has-a-hard-cap
  (let [proc (p/process {:cmd ["bash" "-c" "yes > /dev/null & wait"]
                         :out :string :err :string})]
    (try
      (is (= :ci-proc/timeout
             (ci-proc/await-active-result
               proc 300 {:poll-ms 50 :stale-ms 800 :extension-ms 400})))
      (finally (ci-proc/kill-and-salvage! proc)))))


(deftest quiet-check-times-out-at-normal-ceiling
  (let [proc (p/process {:cmd ["bash" "-c" "sleep 5"]
                         :out :string :err :string})
        started (System/nanoTime)]
    (try
      (is (= :ci-proc/timeout
             (ci-proc/await-active-result
               proc 500 {:poll-ms 50 :stale-ms 100 :extension-ms 2000})))
      (is (< (/ (- (System/nanoTime) started) 1000000) 1500)
          "a quiet hang does not consume the active-work allowance")
      (finally (ci-proc/kill-and-salvage! proc)))))


(defn- fake-run-check
  "ci.clj's run-check contract over a script: `outcomes` maps a check name to
   the statuses its successive runs end in."
  [outcomes]
  (let [runs (atom {})]
    (fn [c status results failed]
      (let [n (:name c)
            i (get (swap! runs update n (fnil inc -1)) n)
            s (nth (get outcomes n) i)]
        (swap! results assoc n {:exit (if (= :passed s) 0 1) :output (str n " run " i)})
        (swap! status assoc n s)
        (when (ci-proc/blocking-red? c s) (reset! failed true))))))


(deftest a-check-that-passes-alone-is-not-a-red
  (let [cs [{:name "kondo"} {:name "splint"} {:name "biome"} {:name "outdated" :group :info}]
        status (atom {"kondo" :failed "splint" :timeout "biome" :passed "outdated" :warning})
        results (atom {"kondo" {:exit -1 :output "Stream closed"} "splint" {:exit -1 :output "TIMEOUT"}})
        failed (atom true)
        run (fake-run-check {"kondo" [:passed] "splint" [:failed]})]
    (ci-proc/retry-solo! cs status results failed run)
    (is (= :retried (@status "kondo")) "passed alone -> environment, not code")
    (is (= "Stream closed" (get-in @results ["kondo" :first-try :output])) "the first attempt is kept")
    (is (= :failed (@status "splint")) "red again alone -> a real red")
    (is (= :passed (@status "biome")) "green checks are not re-run")
    (is (= :warning (@status "outdated")) "an :info warning is not a red, not re-run")
    (is (true? @failed))))


(deftest every-red-passing-alone-clears-the-run
  (let [cs [{:name "kondo"} {:name "gitleaks"}]
        status (atom {"kondo" :failed "gitleaks" :failed})
        failed (atom true)]
    (ci-proc/retry-solo! cs status (atom {}) failed
                         (fake-run-check {"kondo" [:passed] "gitleaks" [:passed]}))
    (is (= {"kondo" :retried "gitleaks" :retried} @status))
    (is (false? @failed))))


(deftest admitted-preflight-timeout-kills-its-descendants-and-clears-ownership
  (let [live (atom #{})
        run (future (try (ci-proc/run-bounded! ["bash" "-c" "sleep 30 & wait"] 1000 live)
                         (catch Exception e (ex-data e))))
        grandchildren (loop [tries 0]
                        (let [children (mapcat descendants-of @live)]
                          (if (or (seq children) (= tries 50)) (vec children)
                              (do (Thread/sleep 10) (recur (inc tries))))))]
    (is (seq grandchildren) "preflight is owned while its descendant is alive")
    (is (= 1000 (:timeout-ms @run)))
    (is (true? (:timed-out? @run)))
    (is (empty? @live))
    (Thread/sleep 200)
    (is (not-any? ProcessHandle/.isAlive grandchildren))))


(deftest admitted-preflight-nonzero-exit-is-not-success
  (let [live (atom #{})
        failure (try (ci-proc/run-bounded! ["bash" "-c" "exit 7"] 1000 live)
                     (catch Exception e (ex-data e)))]
    (is (= 7 (:exit failure)))
    (is (empty? @live))))


(deftest namespace-diagnostics-arrive-before-process-exit-and-survive-timeout
  (let [proc (p/process {:cmd ["bash" "-c"
                               "printf 'ordinary output\\nunit namespace start: slow.ns\\nunit progress: 0/1 passed, 1 failed; failed-tests=:slow.ns/red\\n'; sleep 30"]})
        seen (atom [])
        ready (promise)
        publish! (fn [line] (swap! seen conj line) (deliver ready true))
        stdout (ci-proc/observe-output! (:out proc) publish!)
        stderr (ci-proc/observe-output! (:err proc) publish!)]
    (try
      (is (true? (deref ready 2000 :missing)))
      (is (= ::pending (deref proc 10 ::pending)))
      (let [saved (ci-proc/collected-output (ci-proc/kill-and-salvage! proc) stdout stderr)]
        (is (str/includes? (:out saved) "ordinary output"))
        (is (str/includes? (:out saved) "failed-tests=:slow.ns/red"))
        (is (= 2 (count @seen)))
        (is (every? #(str/starts-with? % "unit ") @seen)))
      (finally (ci-proc/kill-and-salvage! proc)))))


(deftest open-pipe-does-not-hold-reader-after-producer-exits
  ;; A controllable pipe retains its writer after the subprocess exits, like
  ;; an inherited descriptor. No EOF is available until this test closes it.
  (let [input (java.io.PipedInputStream. 65536)
        output (java.io.PipedOutputStream. input)
        text (str "unit namespace start: held.pipe\n" (str/join (repeat 9000 "я")))
        producer (p/process {:cmd ["bash" "-c" "printf 'unit namespace start: held.pipe\\n'; printf '\\321\\217%.0s' {1..9000}; printf 'stderr %.0s' {1..9000} >&2"]
                             :out output})
        stdout (ci-proc/observe-output! input (fn [_]))
        stderr (ci-proc/observe-output! (:err producer) (fn [_]))]
    (try
      (is (zero? (:exit @producer)))
      (let [result (deref (future (ci-proc/collected-output {:exit 0} stdout stderr)) 2000 ::timeout)]
        (is (not= ::timeout result))
        (is (= text (:out result)) "all bytes, including Unicode and a partial final line, survive")
        (is (= (str/join (repeat 9000 "stderr ")) (:err result)))
        (is (future-done? (:worker stdout)))
        (is (future-done? (:worker stderr)))
        (is (thrown? java.io.IOException (java.io.OutputStream/.write output 1)) "the reader closed its pipe"))
      (finally
        (java.io.OutputStream/.close output)
        (ci-proc/kill-and-salvage! producer)))))


(deftest drain-timeout-retains-transcript-and-closes-pipe-before-cancel
  (let [input (java.io.PipedInputStream.)
        output (java.io.PipedOutputStream. input)
        ready (promise)
        blocker (java.util.concurrent.CountDownLatch. 1)
        text "unit namespace start: blocked.publisher\n"
        stdout (ci-proc/observe-output! input (fn [_] (deliver ready true) (java.util.concurrent.CountDownLatch/.await blocker)))
        stderr (ci-proc/observe-output! (java.io.ByteArrayInputStream. (String/.getBytes "stderr tail" "UTF-8")) (fn [_]))]
    (try
      (java.io.OutputStream/.write output (String/.getBytes text "UTF-8"))
      (is (true? (deref ready 2000 ::timeout)))
      (let [result (ci-proc/collected-output {:exit 0} stdout stderr)]
        (is (= text (:out result)))
        (is (= "stderr tail" (:err result)))
        (is (future-cancelled? (:worker stdout)))
        (is (thrown? java.io.IOException (java.io.OutputStream/.write output 1))))
      (finally (java.util.concurrent.CountDownLatch/.countDown blocker) (java.io.OutputStream/.close output)))))


(deftest actual-unit-task-enables-fail-fast-only-in-ci
  (let [probe "(defn clojure [& args] (prn args)) (eval (get-in (read-string (slurp \"bb.edn\")) [:tasks 'test-unit :task]))"]
    (doseq [[ci? expected?] [[false false] [true true]]]
      (let [env (cond-> (dissoc (into {} (System/getenv)) "GRAPHDEN_CI_PRIORITY_HINTS")
                  ci? (assoc "GRAPHDEN_CI_PRIORITY_HINTS" "{}"))
            result @(p/process {:cmd ["bb" "-e" probe] :env env :out :string :err :string})]
        (is (zero? (:exit result)) (:err result))
        (is (= expected? (str/includes? (:out result) "--fail-fast")))))))


(let [{:keys [fail error]} (run-tests 'ci-proc-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
