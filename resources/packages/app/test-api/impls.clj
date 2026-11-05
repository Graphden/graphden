(ns graphden.packages.app.test-api.impls
  "Fresh scoped test facts and the shared sequential execution lifecycle.
   Selection, summaries and status display joins live in fns.edn."
  (:require
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _tests-discover
  [_request]
  (cr/record-effect! :db)
  (test-runs/test-facts ctx))


(defbase _tests-execute-selected
  ;; Request pins the lifecycle to this invocation. The native runner re-reads
  ;; authoritative rows, retains one batch memo and emits completion nudges.
  [_request selected-ids timeout-ms]
  (cr/record-effect! :db)
  (test-runs/run-selected-tests! ctx selected-ids timeout-ms))


(defbase _tests-status-facts
  [_request]
  (cr/record-effect! :db)
  (test-runs/status-facts ctx))


(def impls
  {:_tests-discover _tests-discover
   :_tests-execute-selected {:impl _tests-execute-selected :taint-propagate? true}
   :_tests-status-facts _tests-status-facts})
