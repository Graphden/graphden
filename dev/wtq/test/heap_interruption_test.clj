(ns heap-interruption-test
  (:require
    [clojure.test :refer [deftest is run-tests]]
    [heap-interruption :as retry]))


(deftest only-an-exact-aborted-integration-is-eligible
  (let [candidate {:schema 1 :sha "candidate" :mode :validate :complete true :exit 1
                   :ci {:sha "candidate" :exit 0} :scope {:integration :selected}}
        expected {:schema 1 :sha "candidate" :scope {:since "develop"}}
        stages {:schema 1 :sha "candidate" :status {"ci" :passed "build" :passed "priority" :failed}}
        trace {:clojure.main/triage {:clojure.error/class 'java.lang.OutOfMemoryError
                                     :clojure.error/cause "Java heap space" :clojure.error/phase :execution}
               :clojure.main/trace {:cause "Java heap space"}}
        wave (str "integration progress: 9/20 passed, 0 failed\n"
                  "Execution error (OutOfMemoryError) at collector/example.\nJava heap space\n"
                  "FAIL: automatic related integration\n")
        match? #(retry/verified? %1 %2 %3 %4 %5 "candidate" "develop")]
    (is (match? candidate expected stages wave trace))
    (is (not (match? candidate (assoc expected :sha "elsewhere") stages wave trace)))
    (is (not (match? candidate (assoc-in expected [:scope :since] "earlier") stages wave trace)))
    (is (not (match? (assoc candidate :mode :land) expected stages wave trace)))
    (is (not (match? candidate expected (assoc-in stages [:status "build"] :pending) wave trace)))
    (is (not (match? candidate expected stages (str "integration progress: 4/20 passed, 1 failed\n" wave) trace)))
    (is (not (match? candidate expected stages (str "FAIL in (example)\n" wave) trace)))
    (is (not (match? candidate expected stages "JVM exited with status 1" trace)))
    (is (not (match? candidate expected stages wave (assoc-in trace [:clojure.main/trace :cause] "native thread"))))))


(let [{:keys [fail error]} (run-tests)]
  (System/exit (+ fail error)))
