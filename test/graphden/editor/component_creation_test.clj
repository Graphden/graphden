(ns graphden.editor.component-creation-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-creation :as creation]))


(deftest creation-failures-preserve-status-without-exposing-internal-data
  (doseq [[data expected-status] [[{:http-status 400} 400]
                                  [{:http-status 409 :type :authz/forbidden} 409]
                                  [{:type :authz/forbidden} 403]
                                  [{:type :browser-plan/unsupported} 422]
                                  [{:type :storage-error/candidate-limit} 422]
                                  [{:type :storage-error/bounded-query-unsupported} 422]
                                  [{} 500]]]
    (let [result (creation/failure-response
                   (ex-info "Internal cause should remain private" (assoc data :private-detail "not public")))]
      (is (= expected-status (:http-status result)))
      (is (= #{:ok :committed :http-status :reason} (set (keys result))))
      (is (false? (:ok result)))
      (is (false? (:committed result)))
      (is (not (re-find #"Internal cause|private-detail|not public" (:reason result))))))
  (is (= 500 (:http-status (creation/failure-response (Exception. "Unexpected failure"))))))
