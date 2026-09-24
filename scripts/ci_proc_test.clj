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


(let [{:keys [fail error]} (run-tests 'ci-proc-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
