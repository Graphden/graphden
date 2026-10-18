(ns ci-report-test
  (:require
    [ci-report]
    [clojure.test :refer [deftest is run-tests]]))


(deftest receipt-distinguishes-executed-and-unexecuted-checks
  (let [report (ci-report/receipt
                 {:sha "candidate-sha" :command ["bb" "ci" "--since" "base"]
                  :since "base" :checks [{:name "unit" :bb "test"}]
                  :scoped [{:name "browser" :bb "test-js"}]
                  :manual [{:name "coverage" :bb "coverage"}]
                  :status {"unit" :passed "browser" :scoped "coverage" :manual-skip}
                  :results {"unit" {:exit 0 :duration-ms 23}}
                  :elapsed-ms 30 :exit 0 :wait-ms 7 :path "/tmp/receipt.edn"})]
    (is (= {:reason :ci-lock :duration-ms 7} (:wait report)))
    (is (= [:selected :out-of-scope :manual-skip]
           (mapv :scope (:checks report))))
    (is (ci-report/complete-pass? report ["unit"]))
    (is (not (ci-report/complete-pass? report ["unit" "browser"])))
    (is (not (ci-report/complete-pass? report ["unit" "coverage"])))
    (is (not (ci-report/complete-pass? (assoc report :complete false) ["unit"])))
    (is (not (ci-report/complete-pass? (assoc report :exit nil) ["unit"])))
    (is (not (ci-report/complete-pass? report ["missing"])))
    (is (not (ci-report/complete-pass? (assoc-in report [:checks 0 :status] :skipped) ["unit"])))))


(deftest atomic-report-replaces-stale-success
  (let [path (java.io.File/.getPath (java.io.File/createTempFile "ci-report-test-" ".edn"))]
    (try
      (ci-report/write-atomic! path {:complete true :exit 0})
      (ci-report/write-atomic! path {:complete false :exit nil})
      (is (= {:complete false :exit nil} (ci-report/read-report path)))
      (finally (java.io.File/.delete (java.io.File. path))))))


(let [{:keys [fail error]} (run-tests 'ci-report-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
