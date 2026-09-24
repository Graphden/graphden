(ns ci-proc
  "Check processes of the CI runner (`scripts/ci.clj`): killing a timed-out
   check, and re-running a red one alone (`retry-solo!`, `--retry-solo`).

   A check is `bb <task>`, and most tasks start a process of their own —
   `bb kondo` a JVM, `bb wtq-test` a bash tree. The runner used to kill a
   timed-out check with `Process.destroyForcibly`, which kills only the `bb`
   child AND closes the pipes this JVM reads it through. The grandchild kept
   the pipe open, so the read still in flight threw `IOException: Stream
   closed`: the runner reported `runner error … 0.0s` instead of TIMEOUT (the
   cause read as a mystery), lost what the check had printed, and left the
   grandchild running on the very host whose load had timed it out.

   Here the whole tree is killed through `ProcessHandle`s, which close
   nothing: once every holder of the pipe is gone the read ends on EOF with
   the output intact. Tested by `scripts/ci_proc_test.clj`."
  (:import
    (java.lang
      ProcessHandle)))


(defn destroy-tree!
  "Forcibly kill the babashka.process `proc` and every descendant of it."
  [proc]
  (let [handle (Process/.toHandle (:proc proc))
        tree (-> handle ProcessHandle/.descendants .iterator iterator-seq doall)]
    (doseq [h (cons handle tree)]
      (try (ProcessHandle/.destroyForcibly h) (catch Exception _ nil)))))


(defn kill-and-salvage!
  "After a timeout: kill `proc`'s whole tree, then return what it printed
   before the axe (`{:out … :err …}`), or nil when that cannot be read."
  [proc]
  (destroy-tree! proc)
  (try (deref proc 5000 nil) (catch Exception _ nil)))


(defn blocking-red?
  "Did check `c`, now at status `s`, fail the run? An `:info` check's
   warnings never do (see ci.clj)."
  [c s]
  (boolean (or (#{:failed :timeout} s)
               (and (= :warning s) (not= :info (:group c))))))


(defn retry-solo!
  "Re-run every check of `cs` that failed the run — one at a time, alone —
   through `run-check` (ci.clj's, `[c status results failed]`). A check that
   passes alone was killed by its surroundings, not by the code: several
   agents' pre-queue lints overlapping at load 40-80 time each other out or
   lose a child's stream. It ends `:retried` with its first attempt kept
   under `:first-try` (the report prints it — logged, not hidden). One that
   fails again is a real red and fails the run as before. `failed` is
   recomputed from the re-runs, so it must hold only blocking reds from `cs`."
  [cs status results failed run-check]
  (let [reds (filterv #(blocking-red? % (get @status (:name %))) cs)]
    (reset! failed false)
    (doseq [c reds
            :let [n (:name c)
                  first-try (get @results n)]]
      (swap! status assoc n :running)
      (run-check c status results failed)
      (when (= :passed (get @status n))
        (swap! status assoc n :retried)
        (swap! results assoc-in [n :first-try] first-try)))))
