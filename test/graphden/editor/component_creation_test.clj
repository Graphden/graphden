(ns ^:serial graphden.editor.component-creation-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-creation :as creation]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(deftest malformed-selection-commands-are-client-errors-before-storage-reads
  (doseq [input [nil 9 "scalar" []]]
    (is (= {:reason :invalid-request :http-status 400}
           (try (#'creation/parse-command {} input true)
                (catch clojure.lang.ExceptionInfo error (ex-data error))))))
  (with-redefs [versioned/current-branch-id (constantly (random-uuid))]
    (doseq [component [nil 9 {} [] :recents "unknown"]]
      (is (= {:reason :invalid-request :http-status 400}
             (try (#'creation/parse-command {} {:owner (tenancy/current-user-id) :namespace-id nil :component component} true)
                  (catch clojure.lang.ExceptionInfo error (ex-data error))))))))


(deftest creation-failures-preserve-status-without-exposing-internal-data
  (doseq [[data expected-status] [[{:http-status 400} 400]
                                  [{:http-status 409 :type :authz/forbidden} 409]
                                  [{:type :authz/forbidden} 403]
                                  [{:type :quota/entity-limit :org "private-org" :entity :fn} 429]
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
