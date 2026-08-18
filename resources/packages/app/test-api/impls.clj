(ns graphden.packages.app.test-api.impls
  "Impls for the tests API (`tests` namespace convention) — two thin
   boundary defbases over `graphden.crud.test-runs`; request parsing
   and response shaping are graph fn-defs in fns.edn."
  (:require
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _tests-run-apply
  ;; POST /api/tests/run — run every test on the current branch (or
  ;; the body's fn-ids subset) through the standard execute pipeline
  ;; and summarise. `_request` is unused data-wise but load-bearing:
  ;; it pins the fn to the request scope so the result is never
  ;; call-cached across requests (the `:_reconcile-services-apply`
  ;; pattern).
  [_request fn-ids timeout-ms]
  (cr/record-effect! :db)
  (test-runs/run-tests! ctx {:fn-ids fn-ids :timeout-ms timeout-ms}))


(defbase _tests-status-apply
  ;; GET /api/tests/status — every test fn on the current branch with
  ;; the newest execution status of its CURRENT version (nil status =
  ;; never ran since last edit). Same `_request` pinning as above.
  [_request]
  (cr/record-effect! :db)
  (test-runs/tests-with-statuses ctx))


(def impls
  {:_tests-run-apply _tests-run-apply
   :_tests-status-apply _tests-status-apply})
