(ns graphden.packages.rebase-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.packages.rebase :as rebase]))


(deftest moving-a-bundle-rewrites-only-its-own-semantic-reference-positions
  (let [literal {:lib/value [:lib/value {:ref :lib/value :parent :lib/value}]}
        input [{:name :value :namespace "lib" :parent :core.logic/const :args {:value {:value literal}}}
               {:name :shape :namespace "lib"
                :type {:lib/value {:type :lib/value :required false :description :lib/value}}}
               {:name :consumer :namespace "lib.child"
                :parents [:lib/value :external/value :lib/not-in-bundle]
                :args {:lib/value {:ref :lib/value :as :lib/value :description :lib/value
                                   :resolver :lib/value :type [:refine :lib/shape [:= :lib/value]]}
                       :seq {:append [:lib/value {:value :lib/value} {:parent :lib/value
                                                                      :args {:x :lib/value}
                                                                      :return-type :lib/shape}]}
                       :literal {:value literal}}
                :return-type [:fn {:lib/value [:list :lib/shape]} [:map :keyword :lib/shape] #{:lib/value}]}
               {:name :variant :namespace "lib" :variant [:lib/value :lib/shape :other :external/type]}
               {:name :mapping :namespace "lib" :map {:key :keyword :value :lib/shape}}
               {:name :callable :namespace "lib" :fn-type [{:arg :lib/shape} :lib/shape]}]
        moved (rebase/rebase-bundle input #(case % "lib" "lib-v1" "lib.child" "lib-v1.child" %))
        [_ shape consumer variant mapping callable] moved]
    (is (= literal (get-in moved [0 :args :value :value])))
    (is (= :core.logic/const (:parent (first moved))))
    (is (= {:lib/value {:type :lib-v1/value :required false :description :lib/value}} (:type shape)))
    (is (= [:lib-v1/value :external/value :lib/not-in-bundle] (:parents consumer)))
    (is (= {:ref :lib-v1/value :as :lib/value :description :lib/value
            :resolver :lib-v1/value :type [:refine :lib-v1/shape [:= :lib/value]]}
           (get-in consumer [:args :lib/value])))
    (is (= [:lib-v1/value {:value :lib/value} {:parent :lib-v1/value :args {:x :lib-v1/value}
                                               :return-type :lib-v1/shape}]
           (get-in consumer [:args :seq :append])))
    (is (= literal (get-in consumer [:args :literal :value])))
    (is (= [:fn {:lib/value [:list :lib-v1/shape]} [:map :keyword :lib-v1/shape] #{:lib/value}]
           (:return-type consumer)))
    (is (= [:lib/value :lib-v1/shape :other :external/type] (:variant variant)))
    (is (= {:key :keyword :value :lib-v1/shape} (:map mapping)))
    (is (= [{:arg :lib-v1/shape} :lib-v1/shape] (:fn-type callable)))
    (is (= ["lib-v1" "lib-v1" "lib-v1.child" "lib-v1" "lib-v1" "lib-v1"] (mapv :namespace moved)))))


(deftest qualified-members-can-move-from-the-root-without-rewriting-bare-names
  (let [root-ref (keyword "" "value")
        defs [{:name :value :parent :core.logic/const :args {:value {:value root-ref}}}
              {:name :consumer :parent root-ref :args {:literal {:value root-ref} :bare :value}}]
        moved (rebase/rebase-bundle defs (constantly "copied"))]
    (is (= :copied/value (get-in moved [1 :parent])))
    (is (= :value (get-in moved [1 :args :bare])))
    (is (= root-ref (get-in moved [0 :args :value :value])))
    (is (= root-ref (get-in moved [1 :args :literal :value])))))
