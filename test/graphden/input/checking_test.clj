(ns graphden.input.checking-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.checking :as checking]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(deftest conflicting-scoped-solutions-use-the-type-engine-failure-sentinel-test
  (let [a (types/unify 'shared :int {})
        b (types/unify 'shared :text {})
        graph {:scopes {[] {:substitution a} [(random-uuid)] {:substitution b}}}
        error (try (checking/check-graph graph nil) (catch clojure.lang.ExceptionInfo e e))]
    (is (types/fail? (types/unify :int :text {})))
    (is (= :incompatible-input-substitutions (:code (ex-data error))))))


(deftest equality-keeps-the-referenced-union-without-inventing-an-arm
  (let [owner (ids/fn-id "core.logic" :equal?)
        scope [(random-uuid) []]
        variable 'operand
        operand (fn [slot mode type]
                  {:address [scope [(ids/slot-id owner slot)]]
                   :expected-type variable :reference {:mode mode :actual-type type}})
        literal (operand :a :literal :keyword)
        reference #(operand :b :value %)
        union [:union :keyword :null 'other]
        solve (fn [entries]
                (let [fields (mapv (fn [{[_ [id]] :address}] {:id id :type variable}) entries)
                      node {:substitution {variable :keyword}
                            :configured-contract {:fields fields} :bindings (into {} (map (juxt :address identity)) entries)}
                      graph (checking/check-graph {:scopes {[] node}}
                                                  (fn [request] {:status :checked :substitution (:substitution request)}))]
                  (get (:substitution graph) variable)))]
    (is (= union (solve [literal (reference union)])))
    (is (= union (solve [(reference union) literal])))
    (is (= :keyword (solve [literal (reference [:union :null :text])])))
    (is (= :keyword (solve [literal (reference [:union :keyword variable])])))
    (is (= :keyword (solve [literal (reference :text)])))
    (is (= :keyword (solve [literal (assoc-in (reference union) [:address 1] [(random-uuid)])])))))
