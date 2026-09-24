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


(let [{:keys [fail error]} (run-tests 'ci-proc-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
