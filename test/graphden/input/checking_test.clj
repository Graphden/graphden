(ns graphden.input.checking-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.checking :as checking]
    [graphden.types.core :as types]))


(deftest conflicting-scoped-solutions-use-the-type-engine-failure-sentinel-test
  (let [a (types/unify 'shared :int {})
        b (types/unify 'shared :text {})
        graph {:scopes {[] {:substitution a} [(random-uuid)] {:substitution b}}}
        error (try (checking/check-graph graph nil) (catch clojure.lang.ExceptionInfo e e))]
    (is (types/fail? (types/unify :int :text {})))
    (is (= :incompatible-input-substitutions (:code (ex-data error))))))
