(ns graphden.storage.bounded-query-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.storage.bounded-query :as bounded]))


(deftest function-candidates-require-an-exact-namespace-and-name
  (let [namespace-id (random-uuid)]
    (is (= {:namespace-id namespace-id :name "editor"}
           (bounded/candidate-where :fn {:namespace-id namespace-id :name "editor" :org-id "acme"} 2048)))
    (doseq [where [{} {:name "editor"} {:namespace-id namespace-id}
                   {:namespace-id [namespace-id] :name "editor"}
                   {:namespace-id namespace-id :name ["editor"]}
                   {:namespace-id namespace-id :name " "}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported bounded owner query"
            (bounded/candidate-where :fn where 2048))))))
