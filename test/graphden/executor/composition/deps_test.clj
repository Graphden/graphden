(ns graphden.executor.composition.deps-test
  "Tests for `graphden.executor.composition.deps` — dependency
   extraction and Kahn topological sort over fn-defs. Pure namespace,
   no fixture."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.composition.deps :as deps]))


(defn- order
  "Names of the topologically-sorted fn-defs."
  [fn-defs]
  (mapv :name (deps/topological-sort fn-defs)))


(defn- before?
  "True iff `a` precedes `b` in `names`."
  [names a b]
  (let [idx (zipmap names (range))]
    (< (idx a) (idx b))))


;; ============================================================================
;; topological-sort
;; ============================================================================

(deftest topological-sort-parent-test
  (testing "a parent is sorted before its child, regardless of input order"
    (let [base  {:name :base :return-type :int}
          child {:name :child :parent :base}]
      (is (= [:base :child] (order [child base])))
      (is (= [:base :child] (order [base child]))))))


(deftest topological-sort-arg-ref-test
  (testing "an arg-referenced fn is sorted before the referrer"
    (let [defs [{:name :user :parent :base :args {:x :helper}}
                {:name :helper :parent :base}
                {:name :base :return-type :int}]
          o    (order defs)]
      (is (before? o :base :helper))
      (is (before? o :helper :user))
      (is (before? o :base :user)))))


(deftest topological-sort-type-ref-test
  (testing "a :return-type referencing an in-module type-row is a dependency"
    (let [defs [{:name :uses-type :parent :base :return-type :my-rec}
                {:name :my-rec :type {:a :int}}
                {:name :base :return-type :int}]
          o    (order defs)]
      (is (before? o :my-rec :uses-type)))))


(deftest topological-sort-external-dep-test
  (testing "deps on fns outside the module don't block the sort"
    ;; :external-base is not in the module — treated as pre-satisfied.
    (is (= [:foo] (order [{:name :foo :parent :external-base :return-type :int}])))))


(deftest topological-sort-edge-cases-test
  (testing "empty input → empty"
    (is (= [] (order []))))

  (testing "a dependency cycle throws :fn-composition/circular-dependency"
    (let [ex (try (deps/topological-sort [{:name :a :parent :b}
                                          {:name :b :parent :a}])
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :fn-composition/circular-dependency (:type (ex-data ex))))
      (is (= #{:a :b} (:remaining (ex-data ex)))))))
