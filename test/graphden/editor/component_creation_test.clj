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


(deftest creation-failure-facts-never-export-exception-data
  (doseq [[data expected-kind expected-status]
          [[{:http-status 400} nil 400]
           [{:http-status 409 :type :authz/forbidden} "forbidden" 409]
           [{:type :authz/forbidden} "forbidden" nil]
           [{:type :quota/entity-limit :org "private-org"} "quota" nil]
           [{:type :browser-plan/unsupported} "unsupported" nil]
           [{:type :storage-error/candidate-limit} "bounded-storage" nil]
           [{:type :storage-error/bounded-query-unsupported} "bounded-storage" nil]
           [{} nil nil]]]
    (let [result (creation/failure-facts
                   (ex-info "Private error" (assoc data :private-detail "not public")))]
      (is (= {:ok false :failure-type expected-kind :failure-reason nil
              :failure-status expected-status :server-stale? false} result))))
  (is (= {:ok false :failure-type nil :failure-reason nil
          :failure-status nil :server-stale? false}
         (creation/failure-facts (Exception. "Unexpected failure")))))
