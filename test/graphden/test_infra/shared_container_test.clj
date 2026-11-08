(ns graphden.test-infra.shared-container-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.test-infra.shared-container :as shared]))


(deftest container-stop-is-bounded-after-all-tests-pass
  (let [stop! @#'shared/stop-with-timeout!
        interrupted (promise)]
    (is (nil? (stop! (constantly nil) 1000)))
    (is (thrown-with-msg? IllegalStateException #"stop failed"
          (stop! #(throw (IllegalStateException. "stop failed")) 1000)))
    (is (= :graphden.test-infra.shared-container/timeout
           (stop! #(try
                     (Thread/sleep 60000)
                     (catch InterruptedException _
                       (deliver interrupted true)))
                  100)))
    (is (true? (deref interrupted 1000 false))
        "a timed-out Docker stop is interrupted and cannot hold the JVM open")))
