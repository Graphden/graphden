(ns graphden.types.get-in-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.types.core :as types]
    [graphden.types.get-in :as lookup]))


(deftest inferred-results-contain-real-get-in-results
  (doseq [{:keys [type value path default default-type expected actual-type]}
          [{:type [:map :keyword :int] :value {} :path [:missing]
            :default "fallback" :default-type :text :expected [:union :int :text] :actual-type :text}
           {:type [:map :keyword :int] :value {:hit 7} :path [:hit]
            :default false :default-type :bool :expected [:union :int :bool] :actual-type :int}
           {:type [:union :null {:x :int}] :value nil :path [:x]
            :default false :default-type :bool :expected [:union :bool :int] :actual-type :bool}
           {:type {:x :int} :value {:x 7} :path ["x"]
            :default "fallback" :default-type :text :expected :any :actual-type :text}
           {:type {:x :int} :value {:x 7} :path [:other/x]
            :default false :default-type :bool :expected :any :actual-type :bool}
           {:type {:x :null} :value {:x nil} :path [:x]
            :default false :default-type :bool :expected :null :actual-type :null}
           {:type {:x :bool} :value {:x false} :path [:x]
            :default "fallback" :default-type :text :expected :bool :actual-type :bool}
           {:type [:map :keyword {:x :text}] :value {} :path [:outer :x]
            :default nil :default-type :null :expected [:union :null :text] :actual-type :null}
           {:type :null :value nil :path []
            :default "fallback" :default-type :text :expected :null :actual-type :null}]]
    (let [inferred (lookup/return-type {:map {:type type} :path {:value path}
                                        :default {:type default-type :value default}} :any)
          actual (get-in value path default)]
      (is (= (types/resolve-alias expected) inferred))
      (is (types/subtype? actual-type inferred))
      (is (= (case actual-type :text "fallback" :bool false :int 7 :null nil) actual)))))


(deftest incomplete-facts-do-not-supply-a-narrow-default
  (is (= :any (lookup/return-type {:map {:type [:map :keyword :int]} :path {:value [:missing]}
                                   :default {:type :any :free? true}} :any)))
  (is (= :any (lookup/return-type {:map {:type [:map :keyword :int]} :path {:value [:missing]}
                                   :default {}} :any)))
  (is (= :any (lookup/return-type {:map {:type {:x :int}} :path {}} :any)))
  (is (= :any (lookup/return-type {:path {:value []}} :any)))
  (is (= :any (lookup/return-type {:map {:type [:list :int]} :path {:value [0]}} :any))))
