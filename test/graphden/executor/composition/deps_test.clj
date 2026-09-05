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

  (testing "a single element, and independent elements, pass straight through"
    (is (= [:single] (mapv :name (deps/topological-sort [{:name :single :parent :base}]))))
    (is (= #{:a :b :c}
           (set (mapv :name (deps/topological-sort [{:name :a :parent :base}
                                                    {:name :b :parent :base}
                                                    {:name :c :parent :base}]))))
        "order is unconstrained between independents; membership is not"))

  (testing "a self-reference is a cycle"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
          (deps/topological-sort [{:name :self-ref :parent :base
                                   :args {:x :self-ref}}]))))

  (testing "a dependency cycle throws :fn-composition/circular-dependency"
    (let [ex (try (deps/topological-sort [{:name :a :parent :b}
                                          {:name :b :parent :a}])
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :fn-composition/circular-dependency (:type (ex-data ex))))
      (is (= #{:a :b} (:remaining (ex-data ex)))))))


;; ============================================================================
;; identity edges (`:fn-ref` slots)
;; ============================================================================

(deftest topological-sort-identity-edges-are-not-dependencies-test
  (let [defs [{:name :consumer :args {:service {:type :fn-ref}} :return-type :any}
              {:name :svc-a :parent :consumer :args {:service :svc-b}}
              {:name :svc-b :parent :consumer :args {:service :svc-a}}]
        identity-arg? (fn [_fd arg] (= :service arg))]
    (testing "without the predicate the mutual refs read as a cycle"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (deps/topological-sort defs))))
    (testing "with it, both sort after their parent and each other's order is free"
      (let [o (mapv :name (deps/topological-sort defs identity-arg?))]
        (is (= 3 (count o)))
        (is (before? o :consumer :svc-a))
        (is (before? o :consumer :svc-b))))
    (testing "a non-identity arg is still a dependency"
      (let [defs' (conj defs {:name :user :parent :consumer :args {:other :svc-a}})
            o (mapv :name (deps/topological-sort defs' identity-arg?))]
        (is (before? o :svc-a :user))))))
