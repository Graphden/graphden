(ns ci-resume-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-report :as report]
    [ci-resume :as resume]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


(def sha (str/join (repeat 40 "a")))


(defn hint
  [roster states]
  {:schema 1 :kind "checks" :sha sha :roster roster :status (zipmap roster states)})


(deftest external-integration-proof-has-exact-identity-and-full-scope
  (let [proof {:schema 1 :sha sha :ref (str "refs/heads/release-proof/" sha)
               :source :hosted-integration :status :green :run-id 42
               :request-id "11111111-1111-1111-1111-111111111111"
               :exit 0 :duration-ms 12 :coverage {:suite :integration :scope :full}}]
    (is (resume/valid-external-proof? proof sha))
    (doseq [bad [nil (assoc proof :sha "other") (assoc proof :ref "other")
                 (assoc proof :source :local) (assoc proof :status :running)
                 (assoc proof :run-id nil) (assoc proof :request-id "other")
                 (assoc proof :exit 1) (assoc proof :duration-ms -1)
                 (assoc proof :coverage {:suite :integration :scope :related})]]
      (is (not (resume/valid-external-proof? bad sha))))
    (let [dir (fs/create-temp-dir)
          path (str (fs/path dir "stages.edn"))
          roster ["ci" "build" "priority" "integration"]
          prior {:schema 1 :kind "stages" :sha sha :roster roster
                 :status (zipmap roster (repeat :passed))
                 :sources (assoc (zipmap roster (repeat :local)) "priority" :hosted-integration)
                 :proof {"priority" proof}}]
      (try
        (is (= proof (get-in @(-> (resume/begin! path "stages" sha roster prior true) :state)
                             [:proof "priority"])))
        (let [run (resume/begin! path "stages" sha roster prior true)]
          (resume/mark! run "priority" :running)
          (resume/mark! run "priority" :passed)
          (is (nil? (get-in @(:state run) [:proof "priority"]))
              "a local rerun cannot inherit the old hosted proof"))
        (is (empty? (:proof @(-> (resume/begin! path "stages" sha roster prior false) :state))))
        (let [run (resume/begin! path "stages" sha roster nil)
              initial (report/read-report path)
              writes (atom [])
              write! report/write-atomic!]
          (with-redefs [report/write-atomic! (fn [target receipt]
                                               (swap! writes conj receipt)
                                               (write! target receipt))]
            (doseq [invalid [nil (assoc proof :sha "other") (assoc proof :coverage {})]]
              (is (thrown? clojure.lang.ExceptionInfo
                    (resume/mark-hosted-passed! run "priority" invalid)))
              (is (= initial @(:state run) (report/read-report path))))
            (is (empty? @writes) "invalid proof never publishes a pass")
            (resume/mark-hosted-passed! run "priority" proof)
            (is (= 1 (count @writes)) "status and proof use one atomic publication")
            (is (= :passed (get-in (first @writes) [:status "priority"])))
            (is (= proof (get-in (first @writes) [:proof "priority"])))
            (is (= :hosted-integration (resume/stage-proof-source (report/read-report path) "priority"))))
          (let [passed (report/read-report path)]
            (doseq [incomplete [(update passed :proof dissoc "priority")
                                (update passed :sources dissoc "priority")
                                (-> passed (dissoc :proof) (dissoc :sources))]]
              (is (= :unknown (resume/stage-proof-source incomplete "priority")))
              (let [resumed (resume/begin! path "stages" sha roster incomplete true)]
                (is (not (contains? (:credited resumed) "priority")))
                (is (= :pending (get-in (report/read-report path) [:status "priority"]))))))
          (resume/mark! run "priority" :pending)
          (let [pending (report/read-report path)]
            (with-redefs [report/write-atomic! (fn [& _] (throw (ex-info "interrupted write" {})))]
              (is (thrown? clojure.lang.ExceptionInfo
                    (resume/mark-hosted-passed! run "priority" proof))))
            (is (= pending @(:state run) (report/read-report path))
                "failed publication leaves the stage pending with no orphan pass")))
        (finally (fs/delete-tree dir))))))


(deftest failed-unrun-then-passed-with-full-current-roster
  (let [old (hint ["a" "b" "c" "d" "e"] [:passed :passed :failed :pending :pending])]
    (is (= ["c" "d" "e" "a" "b"] (resume/order (:roster old) old)))
    (is (= ["c" "new" "e" "a"] (resume/order ["new" "a" "c" "e"] old)))
    (is (= ["d" "a" "b" "e" "c"]
           (resume/order (:roster old) (hint ["c" "d" "e" "a" "b"]
                                             [:passed :failed :pending :pending :pending])))))
  (doseq [a resume/statuses b resume/statuses c resume/statuses]
    (let [roster ["a" "b" "c" "new"]
          prior (hint ["a" "b" "c" "deleted"] [a b c :failed])]
      (is (= (frequencies roster) (frequencies (resume/order roster prior)))))))


(deftest red-browser-hint-preserves-new-priority-among-pending-files
  (let [previous (assoc (hint ["old-pass" "old-pending" "old-fail"]
                              [:passed :pending :failed])
                        :kind "browser")
        priority ["new-priority" "old-pending" "old-fail" "old-pass"]]
    (is (= ["old-fail" "new-priority" "old-pending" "old-pass"]
           (resume/order priority previous)))
    (is (= #{} (resume/credited previous (str/join (repeat 40 "b")) priority)))))


(deftest malformed-progress-cannot-author-a-roster
  (let [valid (hint ["a" "b"] [:passed :failed])
        dir (fs/create-temp-dir)
        path (str (fs/path dir "progress.edn"))]
    (try
      (is (resume/valid-receipt? valid "checks"))
      (doseq [bad [(assoc valid :schema 0) (assoc valid :sha "unknown")
                   (assoc valid :kind "browser") (assoc valid :roster ["a" "a"])
                   (assoc valid :status {"a" :passed}) (assoc-in valid [:status "b"] :skipped)
                   (assoc-in valid [:status "extra"] :passed) nil]]
        (report/write-atomic! path bad)
        (is (nil? (resume/read-hint path "checks"))))
      (spit path "{:truncated")
      (is (nil? (resume/read-hint path "checks")))
      (is (= ["a" "b"] (resume/order ["a" "b"] (resume/read-hint path "checks"))))
      (finally (fs/delete-tree dir)))))


(deftest writes-reset-old-proof-and-serialize-parallel-completions
  (let [dir (fs/create-temp-dir)
        path (str (fs/path dir "progress.edn"))
        ids (mapv str (range 16))
        run (resume/begin! path "checks" sha ids (hint ids (repeat :passed)))]
    (try
      (is (= #{:pending} (set (vals (:status (report/read-report path))))))
      (resume/record-order! run (reverse ids))
      (is (= (vec (reverse ids)) (:roster (report/read-report path))))
      (is (thrown? Exception (resume/record-order! run (rest ids))))
      (doseq [f (mapv #(future (resume/mark! run % :passed)) ids)] @f)
      (is (= (zipmap ids (repeat :passed)) (:status (report/read-report path))))
      (is (thrown? Exception (resume/mark! run "unknown" :passed)))
      (finally (fs/delete-tree dir)))))


(deftest resource-boundaries-and-producer-dependencies-survive-reordering
  (let [check (fn [name group] {:name name :group group})
        a (check "a" :clj) b (check "b" :web) c (check "c" :sec)
        t (check "test" :test) p (check "perf" :post-test)
        batches {:pre [[a b] [c]] :priority [] :test [[t]] :post-test [[p]]}
        old (hint ["a" "b" "c" "test" "perf"] [:passed :failed :pending :passed :pending])
        scheduled (resume/schedule-batches batches old)]
    (is (= [[b] [c] [a] [t] [p]] scheduled))
    (is (= (frequencies [a b c t p]) (frequencies (mapcat identity scheduled))))))


(deftest image-dependency-is-recorded-in-the-actual-execution-order
  (let [ordered ["visual" "integration" "fleet" "ci" "build" "e2e"]]
    (is (= ["ci" "build" "visual" "integration" "fleet" "e2e"]
           (resume/stage-dependencies ordered)))
    (is (= (frequencies ordered) (frequencies (resume/stage-dependencies ordered))))))


(deftest related-tests-precede-full-suites-even-after-a-retry-reorders-stages
  (let [ordered ["integration" "e2e" "visual" "priority" "build" "ci"]]
    (is (= ["ci" "build" "priority" "visual" "integration" "e2e"]
           (resume/stage-dependencies ordered)))
    (is (= (frequencies ordered) (frequencies (resume/stage-dependencies ordered))))))


(deftest priority-order-preserves-receipts-and-all-selected-stages
  (doseq [[roster expected]
          [[["ci" "build" "visual" "priority" "e2e" "integration" "fleet"]
            ["ci" "build" "priority" "visual" "e2e" "integration" "fleet"]]
           [["e2e" "integration" "priority" "visual" "build" "ci"]
            ["ci" "build" "priority" "visual" "e2e" "integration"]]
           [["visual" "priority" "ci" "integration"]
            ["ci" "priority" "visual" "integration"]]
           [["visual" "e2e" "build" "ci"]
            ["ci" "build" "visual" "e2e"]]]]
    (let [dir (fs/create-temp-dir)
          path (str (fs/path dir "stages.edn"))
          prior (assoc (hint roster (repeat :passed)) :kind "stages" :sha sha
                       :sources (zipmap roster (repeat :local)))]
      (try
        (let [run (resume/begin! path "stages" sha roster prior true)
              receipt (report/read-report path)]
          (is (= (frequencies roster) (frequencies (:roster receipt))))
          (is (= expected (:roster receipt)))
          (is (= (zipmap roster (repeat :passed)) (:status receipt)))
          (is (= (set roster) (set (:credited run))))
          (is (= expected (resume/stage-dependencies expected))))
        (finally (fs/delete-tree dir))))))


(deftest standalone-retry-is-automatic-and-rejects-rewritten-history
  (let [dir (fs/create-temp-dir)
        scripts (str (fs/absolutize "scripts"))
        git (fn [& args]
              (str/trim (:out (apply p/shell {:dir (str dir) :out :string :err :string}
                                     "git" args))))
        path (str (fs/path dir ".git/graphden-resume/checks.edn"))
        invoke (fn []
                 (str/split-lines
                   (str/trim (:out (p/shell {:dir (str dir) :out :string
                                             :extra-env {"GRAPHDEN_RESUME_OUT" "" "GRAPHDEN_RESUME_FROM" ""}}
                                            "bb" "-cp" scripts (str scripts "/ci_resume_cli.clj")
                                            "auto" "checks" "a" "b" "c")))))]
    (try
      (git "init" "-q" "-b" "develop")
      (git "config" "user.email" "fixture@example.invalid")
      (git "config" "user.name" "fixture")
      (git "-c" "commit.gpgsign=false" "commit" "-q" "--allow-empty" "-m" "root")
      (let [root (git "rev-parse" "HEAD")]
        (git "-c" "commit.gpgsign=false" "commit" "-q" "--allow-empty" "-m" "change")
        (let [prior (assoc (hint ["a" "b" "c"] [:passed :failed :pending]) :sha (git "rev-parse" "HEAD"))]
          (report/write-atomic! path prior)
          (is (= ["b" "c" "a"] (invoke)))
          (is (= #{:pending} (set (vals (:status (report/read-report path))))))
          (report/write-atomic! path prior)
          (git "checkout" "-q" "--detach" root)
          (is (= ["a" "b" "c"] (invoke)))))
      (finally (fs/delete-tree dir)))))


(deftest current-browser-phase-requires-exact-non-inherited-roster
  (let [dir (fs/create-temp-dir)
        scripts (str (fs/absolutize "scripts"))
        prefix (str (fs/path dir "current"))
        path (str prefix ".browser.edn")
        current-sha (resume/git-output "rev-parse" "HEAD")
        receipt {:schema 1 :kind "browser" :sha current-sha :roster ["a" "b"]
                 :status {"a" :passed "b" :pending}}
        invoke (fn []
                 (p/shell {:out :string :err :string
                           :extra-env {"GRAPHDEN_RESUME_OUT" prefix "GRAPHDEN_RESUME_FROM" ""
                                       "GRAPHDEN_RESUME_CREDIT" "0"}}
                          "bb" "-cp" scripts (str scripts "/ci_resume_cli.clj")
                          "auto" "browser" "a" "b")
                 (report/read-report path))]
    (try
      (report/write-atomic! path receipt)
      (is (= {"a" :passed "b" :pending} (:status (invoke))))
      (doseq [invalid [(assoc receipt :sha sha)
                       (assoc receipt :inherited true)
                       (-> receipt (assoc :roster ["a"]) (assoc :status {"a" :passed}))]]
        (report/write-atomic! path invalid)
        (is (= {"a" :pending "b" :pending} (:status (invoke)))))
      (finally (fs/delete-tree dir)))))


(deftest browser-failure-survives-an-intervening-build-failure
  (let [dir (fs/create-temp-dir)
        prefix #(str (fs/path dir %))
        r1 (prefix "r1") r2 (prefix "r2") r3 (prefix "r3")
        prior (assoc (hint ["a" "b" "c"] [:passed :failed :pending]) :kind "browser")]
    (try
      ;; R1 fails browser b after a passes; R2 fails build before browser starts.
      (report/write-atomic! (str r1 ".browser.edn") prior)
      (resume/carry-hints! r2 r1)
      (let [build (resume/begin! (str r2 ".stages.edn") "stages" sha ["build" "e2e"] nil)]
        (resume/mark! build "build" :failed))
      (is (= sha (:sha (resume/read-hint (str r2 ".browser.edn") "browser"))))
      (is (true? (:inherited (resume/read-hint (str r2 ".browser.edn") "browser"))))
      ;; R3 keeps b,c,a, but resets every status for its own actual invocation.
      (resume/carry-hints! r3 r2)
      (let [run (resume/begin! (str r3 ".browser.edn") "browser" (str/join (repeat 40 "b"))
                               ["a" "b" "c"] (resume/read-hint (str r3 ".browser.edn") "browser"))]
        (is (= ["b" "c" "a"] (:roster run)))
        (is (= #{:pending} (set (vals (:status @(:state run))))))
        (is (not (contains? @(:state run) :inherited))))
      ;; Rejected lineage supplies no source; it must not resurrect old hints.
      (resume/carry-hints! r3 nil)
      (is (nil? (resume/read-hint (str r3 ".browser.edn") "browser")))
      (finally (fs/delete-tree dir)))))


(deftest exact-interrupted-candidate-credits-only-proven-passes
  (let [roster ["a" "b" "tests-perf" "perf"]
        prior (-> (hint roster [:passed :passed :passed :passed])
                  (assoc :proof {"a" {:exit 0 :duration-ms 42}
                                 "tests-perf" {:exit 0 :duration-ms 50}
                                 "perf" {:exit 0 :duration-ms 10}}))
        dir (fs/create-temp-dir)
        path (str (fs/path dir "checks.edn"))]
    (try
      (let [run (resume/begin! path "checks" sha roster prior true)]
        (is (= #{"a"} (:credited run)))
        (is (= :passed (get-in (report/read-report path) [:status "a"])))
        (is (= :pending (get-in (report/read-report path) [:status "b"]))))
      (is (empty? (:credited (resume/begin! path "checks" sha (conj roster "new") prior true))))
      (is (empty? (:credited (resume/begin! path "checks" (str/join (repeat 40 "b")) roster prior true))))
      (is (empty? (:credited (resume/begin! path "checks" sha roster (assoc prior :inherited true) true))))
      (finally (fs/delete-tree dir)))))


(deftest gate-credit-requires-an-exact-unfinished-or-preconditioned-train
  (let [expected {:schema 1 :sha sha :scope {:since "base"}}
        gate {:schema 1 :sha sha :complete false :exit nil}]
    (is (resume/interrupted-gate? gate expected sha "base"))
    (is (resume/interrupted-gate? (assoc gate :complete true :exit 3) expected sha "base"))
    (is (not (resume/interrupted-gate? (assoc gate :complete true :exit 3
                                              :failed-stage :visual) expected sha "base")))
    (is (not (resume/interrupted-gate? (assoc gate :complete true :exit 1) expected sha "base")))
    (is (not (resume/interrupted-gate? gate expected sha "other")))
    (is (not (resume/interrupted-gate? gate expected (str/join (repeat 40 "b")) "base")))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
