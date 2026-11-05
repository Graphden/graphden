(ns graphden.test-infra.test-api
  "Test clients of the actual app.test-api policy graphs."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.packages.records.ids :as ids]))


(defn run-tests!
  [ctx opts]
  (exec/execute-with-named-args
    ctx (ids/fn-id "app.test-api" :_tests-run-apply)
    (merge {:request {:request-method :post :uri "/api/tests/run" :headers {}}
            :fn-ids nil :platform? nil :timeout-ms nil}
           opts)))


(defn tests-with-statuses
  [ctx]
  (exec/execute-with-named-args
    ctx (ids/fn-id "app.test-api" :_tests-status-apply)
    {:request {:request-method :get :uri "/api/tests/status" :headers {}}}))
