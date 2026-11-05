(ns graphden.packages.web.crud-types-impls-test
  "Unit tests for the `web/crud-types` base-fn impls."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "crud-types"))


(deftest candidate-ns-facts-records-its-db-read
  ;; The `:_candidate-ns-facts` acquisition boundary declares `:db` and
  ;; records the effect before reading the scoped snapshot. The graph's
  ;; `:fn-ns-index` now projects over those facts without a direct DB read.
  (let [data (try (binding [cr/*allowed-effects* #{}]
                    ((impls/impl-of :_candidate-ns-facts) {} nil))
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :execution/forbidden-effect (:type data)) (pr-str data))
    (is (= :db (:effect data)))))
