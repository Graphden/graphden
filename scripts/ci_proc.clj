(ns ci-proc
  "Killing a timed-out check of the CI runner (`scripts/ci.clj`).

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
