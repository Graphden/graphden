(ns ci-runner-order-test
  (:require
    [babashka.fs :as fs]
    [ci-resume :as resume]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


;; Load the real dispatcher without invoking its CLI; child processes are the
;; only stub. This exercises the actual stop/skip path without a JVM or Docker.
(load-string (str/replace (slurp "scripts/ci.clj") #"\(run-ci\)\s*$" ""))


(defn dispatch
  [fail-name]
  (let [registry @(ns-resolve 'ci 'registry)
        names #{"clj-kondo" "splint" "tests-js" "tests-unit" "perf"}
        checks (filterv #(names (:name %)) registry)
        prior {:roster ["clj-kondo" "splint" "tests-js" "tests-unit" "perf"]
               :status {"clj-kondo" :passed "splint" :passed "tests-js" :failed
                        "tests-unit" :pending "perf" :pending}}
        calls (atom []) status (atom {}) failed (atom false)
        progress @(ns-resolve 'ci 'progress)]
    (reset! progress {:prior prior})
    (try
      (with-redefs-fn {(ns-resolve 'ci 'run-check)
                       (fn [check state _ red]
                         (swap! calls conj (:name check))
                         (swap! state assoc (:name check) (if (= fail-name (:name check)) :failed :passed))
                         (when (= fail-name (:name check)) (reset! red true)))}
        #((ns-resolve 'ci 'run-waves!) checks status (atom {}) failed false))
      {:calls @calls :status @status :failed @failed}
      (finally (reset! progress nil)))))


(deftest resumed-dispatch-executes-every-selected-check-once
  (let [{:keys [calls failed]} (dispatch nil)]
    (is (false? failed))
    (is (= ["tests-js" "tests-unit" "perf"] (subvec calls 0 3)))
    (is (= #{"clj-kondo" "splint"} (set (subvec calls 3))))
    (is (= 5 (count calls) (count (set calls))))))


(deftest resumed-failure-leaves-unrun-tail-explicitly-skipped
  (let [{:keys [calls status failed]} (dispatch "tests-js")]
    (is failed)
    (is (= ["tests-js"] calls))
    (is (= :failed (get status "tests-js")))
    (is (= #{:skipped} (set (vals (dissoc status "tests-js")))))))


(deftest blocking-warning-is-a-failed-resume-hint
  (let [dir (fs/create-temp-dir)
        progress @(ns-resolve 'ci 'progress)]
    (try
      (doseq [[group verdict] [[:clj :failed] [:info :passed]]]
        (let [run (resume/begin! (str (fs/path dir "progress.edn")) "checks"
                                 (apply str (repeat 40 "a")) ["check"] nil)
              status (atom {})]
          (reset! progress run)
          (with-redefs-fn {(ns-resolve 'ci 'run-check*)
                           (fn [_ state _ _] (swap! state assoc "check" :warning))}
            #((ns-resolve 'ci 'run-check) {:name "check" :group group} status (atom {}) (atom false)))
          (is (= verdict (get-in @(:state run) [:status "check"])))))
      (finally (reset! progress nil) (fs/delete-tree dir)))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
