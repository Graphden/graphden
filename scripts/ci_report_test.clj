(ns ci-report-test
  (:require
    [ci-report]
    [clojure.test :refer [deftest is run-tests]]))


(deftest changed-or-dirty-candidate-cannot-own-a-green-receipt
  (let [initial {:sha (apply str (repeat 40 "a")) :worktree-status ""}
        dirty (assoc initial :worktree-status " M src/a.clj")
        moved (assoc initial :sha (apply str (repeat 40 "b")))]
    (is (ci-report/stable-candidate? initial initial))
    (is (not (ci-report/stable-candidate? initial moved)))
    (is (not (ci-report/stable-candidate? initial dirty)))
    (is (not (ci-report/stable-candidate? dirty initial)))))


(defn- expectation
  [report]
  {:schema 1 :sha (:sha report) :command (:command report)
   :scope (:scope report)
   :groups (into {} (map (juxt :name :group)) (:checks report))})


(deftest receipt-distinguishes-executed-and-unexecuted-checks
  (let [report (ci-report/receipt
                 {:sha "candidate-sha" :command ["bb" "ci" "--since" "base"]
                  :since "base" :checks [{:name "unit" :bb "test" :group :test}]
                  :scoped [{:name "browser" :bb "test-js"}]
                  :manual [{:name "coverage" :bb "coverage"}]
                  :status {"unit" :passed "browser" :scoped "coverage" :manual-skip}
                  :results {"unit" {:exit 0 :duration-ms 23}}
                  :elapsed-ms 30 :exit 0 :wait-ms 7 :path "/tmp/receipt.edn"})]
    (is (= {:reason :ci-lock :duration-ms 7} (:wait report)))
    (is (= [:selected :out-of-scope :manual-skip]
           (mapv :scope (:checks report))))
    (is (ci-report/complete-pass? report ["unit"]))
    (is (ci-report/gate-pass? report (expectation report)))
    (is (not (ci-report/gate-pass? report (assoc (expectation report) :sha "other-sha"))))
    (is (not (ci-report/complete-pass? report ["unit" "browser"])))
    (is (not (ci-report/complete-pass? report ["unit" "coverage"])))
    (is (not (ci-report/complete-pass? (assoc report :complete false) ["unit"])))
    (is (not (ci-report/complete-pass? (assoc report :exit nil) ["unit"])))
    (is (not (ci-report/complete-pass? report ["missing"])))
    (is (not (ci-report/complete-pass? (assoc-in report [:checks 0 :status] :skipped) ["unit"])))))


(deftest advisory-warning-is-recorded-without-redding-the-gate
  (let [report (ci-report/receipt
                 {:sha "candidate-sha" :command ["bb" "ci"]
                  :checks [{:name "unit" :bb "test" :group :test}
                           {:name "outdated" :bb "outdated" :group :info}]
                  :status {"unit" :passed "outdated" :warning}
                  :results {"unit" {:exit 0 :duration-ms 1}
                            "outdated" {:exit -1 :duration-ms 2}}
                  :elapsed-ms 3 :exit 0 :wait-ms 0 :path "/tmp/ci.edn"})]
    (is (ci-report/gate-pass? report (expectation report)))
    (is (not (ci-report/gate-pass? (assoc-in report [:checks 0 :status] :skipped)
                                   (expectation report))))
    (is (not (ci-report/gate-pass? (assoc-in report [:checks 1 :status] :running)
                                   (expectation report))))
    (is (not (ci-report/gate-pass? (update report :checks pop)
                                   (expectation report))))
    (is (not (ci-report/gate-pass? (assoc report :scope {:selected []})
                                   (expectation report))))))


(deftest atomic-report-replaces-stale-success
  (let [path (java.io.File/.getPath (java.io.File/createTempFile "ci-report-test-" ".edn"))]
    (try
      (ci-report/write-atomic! path {:complete true :exit 0})
      (ci-report/write-atomic! path {:complete false :exit nil})
      (is (= {:complete false :exit nil} (ci-report/read-report path)))
      (finally (java.io.File/.delete (java.io.File. path))))))


(let [{:keys [fail error]} (run-tests 'ci-report-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
