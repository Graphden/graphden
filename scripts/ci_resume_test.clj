(ns ci-resume-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-report :as report]
    [ci-resume :as resume]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


(def sha (apply str (repeat 40 "a")))


(defn hint
  [roster states]
  {:schema 1 :kind "checks" :sha sha :roster roster :status (zipmap roster states)})


(deftest failed-unrun-then-passed-with-full-current-roster
  (let [old (hint ["a" "b" "c" "d" "e"] [:passed :passed :failed :pending :pending])]
    (is (= ["c" "d" "e" "a" "b"] (resume/order (:roster old) old)))
    (is (= ["c" "e" "new" "a"] (resume/order ["new" "a" "c" "e"] old)))
    (is (= ["d" "e" "a" "b" "c"]
           (resume/order (:roster old) (hint ["c" "d" "e" "a" "b"]
                                             [:passed :failed :pending :pending :pending])))))
  (doseq [a resume/statuses b resume/statuses c resume/statuses]
    (let [roster ["a" "b" "c" "new"]
          prior (hint ["a" "b" "c" "deleted"] [a b c :failed])]
      (is (= (frequencies roster) (frequencies (resume/order roster prior)))))))


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
    (is (= [[b] [c] [t] [p] [a]] scheduled))
    (is (= (frequencies [a b c t p]) (frequencies (mapcat identity scheduled))))))


(deftest image-dependency-is-recorded-in-the-actual-execution-order
  (let [ordered ["visual" "integration" "fleet" "ci" "build" "e2e"]]
    (is (= ["build" "visual" "integration" "fleet" "ci" "e2e"]
           (resume/stage-dependencies ordered)))
    (is (= (frequencies ordered) (frequencies (resume/stage-dependencies ordered))))))


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


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
