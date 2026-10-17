(ns graphden.lint.reachability-test
  (:require
    [clojure.test :refer [deftest is]]))


(load-file "tools/reachability_audit.clj")


(defn- analyze
  [definitions roots vocabulary]
  ((ns-resolve 'tools.reachability-audit 'analyze)
   [{:name :const :namespace "core.logic" :args {:value :any}}]
   definitions roots vocabulary))


(def twins
  [{:namespace "a" :name :entry :parent :_label}
   {:namespace "b" :name :entry :parent :_label}
   {:namespace "a" :name :_label :parent :const :args {:value "left"}}
   {:namespace "b" :name :_label :parent :const :args {:value "right"}}])


(deftest namespace-twins-retain-each-owner-and-real-unreachable-findings
  (doseq [definitions [twins (reverse twins)]]
    (let [dead {:namespace "a" :name :_dead :parent :const}
          result (analyze (conj (vec definitions) dead) [:a/entry :b/entry] {})]
      (is (= #{["a" :entry] ["b" :entry] ["a" :_label] ["b" :_label] ["core.logic" :const]}
             (:reachable result)))
      (is (= [["a" :_dead]] (mapv key (:unreachable-composed result)))))
    (is (empty? (:unreachable-composed (analyze definitions [:entry] {})))
        "An ownerless docs/test mention conservatively seeds both identities")))


(deftest qualified-external-and-root-references-use-the-canonical-resolver
  (let [caller {:namespace "b" :name :external :parent :a/_label}
        result (analyze (conj twins caller) [:b/external] {})]
    (is (contains? (:reachable result) ["a" :_label]))
    (is (not (contains? (:reachable result) ["b" :_label]))))
  (doseq [other-ns ["a" "package@v1"]]
    (let [definitions [{:namespace nil :name :entry :parent :_label}
                       {:namespace nil :name :_label :parent :const}
                       {:namespace other-ns :name :_label :parent :const}]
          result (analyze definitions [(keyword "" "entry")] {})]
      (is (contains? (:reachable result) [nil :_label]))
      (is (not (contains? (:reachable result) [other-ns :_label]))))))


(deftest ambiguity-does-not-invent-edges-or-suppress-vocabulary
  (let [definitions (conj twins {:namespace "c" :name :entry :parent :_label})
        result (analyze definitions [:c/entry] {})]
    (is (not (contains? (:reachable result) ["a" :_label])))
    (is (not (contains? (:reachable result) ["b" :_label])))
    (is (= :reachability/ambiguous-vocabulary
           (try (analyze definitions [] {:_label "Not an identity"}) nil
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
    (is (= [["b" :_label] ["b" :entry] ["c" :entry]]
           (mapv key (:unreachable-composed
                       (analyze definitions [:a/entry] {:a/_label "Explicit vocabulary owner"})))))))


(deftest discovered-definition-roots-never-seed-another-owner
  (doseq [[ns-path collector] [["core.tests" 'collect-tests-namespace-roots]
                               ["examples.demo" 'collect-example-roots]]]
    (let [definitions [{:namespace ns-path :name :entry :parent :const}
                       {:namespace "unrelated" :name :entry :parent :const}]
          roots ((ns-resolve 'tools.reachability-audit collector) definitions)
          result (analyze definitions roots {})]
      (is (= #{(keyword ns-path "entry")} roots))
      (is (= [["unrelated" :entry]] (mapv key (:unreachable-composed result)))))))
