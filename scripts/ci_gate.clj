(ns ci-gate
  "Independent check selection for a merge train's CI receipt. The expected
   roster is saved before bb ci runs, so a partial receipt cannot define it."
  (:require
    [ci-report]
    [ci-select]))


(defn expectation
  [sha base skip]
  (let [args (cond-> ["--since" base] (seq skip) (into ["--skip" skip]))
        registry (ci-select/validate-registry!
                   (ci-select/read-registry "scripts/checks.edn"))
        {:keys [run scoped manual]} (ci-select/select-checks registry args)
        all-checks (concat run scoped manual)]
    {:schema 1
     :sha sha
     :command (into ["bb" "ci"] args)
     :scope {:since base
             :selected (mapv :name run)
             :out-of-scope (mapv :name scoped)
             :manual-skip (mapv :name manual)}
     :groups (into {} (map (juxt :name :group)) all-checks)}))


(defn -main
  [& args]
  (let [[mode expected-path & more] args]
    (case mode
      "expect" (let [[sha base skip] more]
                 (ci-report/write-atomic! expected-path
                                          (expectation sha base skip)))
      "verify" (let [[actual-path] more
                     expected (ci-report/read-report expected-path)
                     actual (ci-report/read-report actual-path)]
                 (when-not (ci-report/gate-pass? actual expected)
                   (throw (ex-info "CI receipt is incomplete, stale, or has a wrong check roster"
                                   {:expected expected-path :actual actual-path}))))
      (throw (ex-info "Unknown ci-gate mode" {:mode mode})))))


(apply -main *command-line-args*)
