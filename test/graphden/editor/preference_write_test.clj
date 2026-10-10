(ns graphden.editor.preference-write-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.preference-write :as preferences]))


(deftest conditional-writes-refuse-unsupported-storage-and-malformed-comparisons
  (is (= :unsupported (preferences/write! {} "components" {} true {:present false :value nil})))
  (doseq [expected [nil [] {:present false} {:present "false" :value nil}
                    {:present false :value nil :owner "another-owner"}]]
    (is (= :invalid (preferences/write! {} "components" {} true expected)))))
