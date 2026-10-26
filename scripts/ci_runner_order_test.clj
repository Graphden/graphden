(ns ci-runner-order-test
  (:require
    [babashka.fs :as fs]
    [ci-resume :as resume]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


;; Load the real dispatcher without invoking its CLI; child processes are the
;; only stub. This exercises the actual stop/skip path without a JVM or Docker.
(load-string (str/replace (slurp "scripts/ci.clj") #"\(main\)\s*$" ""))


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
    (is (= #{"clj-kondo" "splint"} (set (subvec calls 0 2))))
    (is (= ["tests-js" "tests-unit" "perf"] (subvec calls 2)))
    (is (= 5 (count calls) (count (set calls))))))


(deftest resumed-failure-leaves-unrun-tail-explicitly-skipped
  (let [{:keys [calls status failed]} (dispatch "tests-js")]
    (is failed)
    (is (= #{"clj-kondo" "splint"} (set (take 2 calls))))
    (is (= ["tests-js"] (vec (drop 2 calls))))
    (is (= :failed (get status "tests-js")))
    (is (= #{:skipped} (set (vals (apply dissoc status ["tests-js" "clj-kondo" "splint"])))))))


(deftest affected-wave-fails-before-broad-checks-start
  (let [registry @(ns-resolve 'ci 'registry)
        wanted #{"syntax" "biome-affected" "tests-js-affected" "clj-kondo" "tests-unit"}
        checks (filterv #(wanted (:name %)) registry)
        calls (atom []) status (atom {}) failed (atom false)
        progress @(ns-resolve 'ci 'progress)]
    (reset! progress {:prior nil})
    (try
      (with-redefs-fn {(ns-resolve 'ci 'run-check)
                       (fn [check state _ red]
                         (swap! calls conj (:name check))
                         (let [bad? (= "tests-js-affected" (:name check))]
                           (swap! state assoc (:name check) (if bad? :failed :passed))
                           (when bad? (reset! red true))))}
        #((ns-resolve 'ci 'run-waves!) checks status (atom {}) failed false))
      (is @failed)
      (is (= #{"syntax" "biome-affected" "tests-js-affected"} (set @calls)))
      (is (= :skipped (get @status "clj-kondo")))
      (is (= :skipped (get @status "tests-unit")))
      (finally (reset! progress nil)))))


(deftest blocking-warning-is-a-failed-resume-hint
  (let [dir (fs/create-temp-dir)
        progress @(ns-resolve 'ci 'progress)]
    (try
      (doseq [[group verdict] [[:clj :failed] [:info :passed]]]
        (let [run (resume/begin! (str (fs/path dir "progress.edn")) "checks"
                                 (str/join (repeat 40 "a")) ["check"] nil)
              status (atom {})]
          (reset! progress run)
          (with-redefs-fn {(ns-resolve 'ci 'run-check*)
                           (fn [_ state _ _] (swap! state assoc "check" :warning))}
            #((ns-resolve 'ci 'run-check) {:name "check" :group group} status (atom {}) (atom false)))
          (is (= verdict (get-in @(:state run) [:status "check"])))))
      (finally (reset! progress nil) (fs/delete-tree dir)))))


(deftest completed-ci-check-uses-durable-proof-without-dispatch
  (let [dir (fs/create-temp-dir)
        progress @(ns-resolve 'ci 'progress)
        sha (str/join (repeat 40 "a"))
        prior {:schema 1 :kind "checks" :sha sha :roster ["check"]
               :status {"check" :passed}
               :proof {"check" {:exit 0 :duration-ms 42}}}
        run (resume/begin! (str (fs/path dir "progress.edn")) "checks"
                           sha ["check"] prior true)
        status (atom {}) results (atom {}) failed (atom false)]
    (try
      (reset! progress run)
      (with-redefs-fn {(ns-resolve 'ci 'run-check*)
                       (fn [& _] (throw (ex-info "credited check executed" {})))}
        #((ns-resolve 'ci 'run-check) {:name "check" :group :test}
                                      status results failed))
      (is (= :passed (get @status "check")))
      (is (= {:exit 0 :duration-ms 42} (get @results "check")))
      (is (false? @failed))
      (finally (reset! progress nil) (fs/delete-tree dir)))))


(deftest ci-and-gate-preflight-share-admission-and-cleanup
  (doseq [entry ['main]]
    (let [events (atom [])]
      (with-redefs-fn {(ns-resolve 'ci 'select-checks) (fn [_] {:run [] :scoped [] :manual [] :since "base"})
                       (ns-resolve 'ci-resume 'git-output) (constantly "preflight-fixture-sha")
                       (ns-resolve 'ci-report 'write-atomic!) (fn [& _])
                       (ns-resolve 'ci 'acquire-lock!) (fn [] (swap! events conj :lock) ::lock)
                       (ns-resolve 'ci 'release-lock!) (fn [_] (swap! events conj :release))
                       (ns-resolve 'ci 'kill-live-procs!) (fn [] (swap! events conj :cleanup))
                       (ns-resolve 'ci-proc 'run-bounded!)
                       (fn [command timeout-ms _]
                         (swap! events conj :preflight)
                         (is (= ["bb" "scripts/ci_fast_cli.clj" "base"] command))
                         (is (= 240000 timeout-ms))
                         (throw (ex-info "fixture preflight red" {})))}
        #(binding [*command-line-args* ["--preflight-only" "base"]]
           (is (thrown? Exception ((ns-resolve 'ci entry))))))
      (is (= [:lock :preflight :cleanup :release] @events)))))


(deftest immutable-candidate-fence-refuses-moving-or-dirty-source
  (doseq [diagnostic? [false true]
          change [identity #(assoc % :sha (str/join (repeat 40 "b")))
                  #(assoc % :worktree-status " M src/changed.clj")
                  #(assoc % :worktree-status "?? test/uncommitted_test.clj")]]
    (let [initial {:sha (str/join (repeat 40 "a")) :worktree-status ""}
          current (atom initial)
          reports (atom 0)
          admissions (atom 0)]
      (with-redefs-fn {(ns-resolve 'ci 'select-checks) (constantly {:run [] :scoped [] :manual []})
                       (ns-resolve 'ci 'ci-report-path) (constantly nil)
                       (ns-resolve 'ci-priority-core 'changed-paths) (constantly [])
                       (ns-resolve 'ci-resume 'git-output)
                       (fn [& args] (if (= "rev-parse" (first args)) (:sha @current) (:worktree-status @current)))
                       (ns-resolve 'ci-resume 'invocation!) (constantly nil)
                       (ns-resolve 'ci 'acquire-lock!) (fn [] (swap! admissions inc) nil)
                       (ns-resolve 'ci 'release-lock!) (fn [_])
                       (ns-resolve 'ci 'kill-live-procs!) (fn [])
                       (ns-resolve 'ci 'run-waves!) (fn [& _] (swap! current change))
                       (ns-resolve 'ci 'print-report!) (fn [& _] (swap! reports inc))
                       (ns-resolve 'ci 'print-verdict!) (fn [& _])}
        #(binding [*command-line-args* (if diagnostic? ["--groups" "clj"] [])]
           (with-out-str
             (if (or diagnostic? (= initial (change initial)))
               (do ((ns-resolve 'ci 'run-ci)) (is (= 1 @reports)))
               (do (is (thrown-with-msg? Exception #"unchanged committed candidate" ((ns-resolve 'ci 'run-ci))))
                   (is (zero? @reports)))))))
      (is (= 1 @admissions)))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
