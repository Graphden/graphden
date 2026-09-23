(ns graphden.packages.web.crud-types-impls-test
  "Unit tests for the `web/crud-types` base-fn impls."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "crud-types"))


(deftest fn-ns-index-records-its-db-read
  ;; `:fn-ns-index` declares `:effects #{:db}` and reads every visible fn,
  ;; but never recorded the effect: a gate that excludes `:db` let it run,
  ;; and an effect trace did not show the read.
  (let [data (try (binding [cr/*allowed-effects* #{}]
                    ((impls/impl-of :fn-ns-index) {} nil))
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :execution/forbidden-effect (:type data)) (pr-str data))
    (is (= :db (:effect data)))))
