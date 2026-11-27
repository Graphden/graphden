(ns graphden.input.nullable-operands-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.nullable-operands :as operands]
    [graphden.packages.records.ids :as ids]))


(deftest retain-a-proved-wider-nullable-operand-for-the-exact-shared-slots
  (let [owner (ids/fn-id "core.logic" :coalesce)
        root [(random-uuid) []]
        scalar [:list :int]
        broad [:list [:union :int :null]]
        entry (fn [slot mode actual]
                {:address [root [(ids/slot-id owner slot)]] :expected-type '[:union :null a]
                 :reference {:mode mode :actual-type actual}})
        literal (entry :default :literal scalar)
        value (entry :value :value [:union :null broad])
        initial {'a scalar}]
    (is (= {'a broad} (operands/reconcile initial [literal value])))
    (doseq [other [(assoc-in value [:address 1] [(random-uuid)])
                   (assoc-in value [:address 0] [(random-uuid) []])
                   (assoc-in value [:reference :actual-type] '[:union :null [:list b]])
                   (assoc-in value [:reference :actual-type] [:union :null :keyword])
                   (assoc-in value [:reference :actual-type] [:union :null [:list :any]])
                   (assoc value :expected-type '[:union :null b])]]
      (is (= initial (operands/reconcile initial [literal other]))))))


(deftest any-is-an-exact-instance-only-of-the-naked-nullable-variable
  (is (operands/admits-any? '[:union :null a]))
  (doseq [domain ['[:union :null [:list a]] '[:union :null a b] [:union :null :text] '[:list a]]]
    (is (not (operands/admits-any? domain)))))


(deftest the-empty-list-fallback-requires-an-independent-untyped-native-lookup
  (let [owner (random-uuid)
        binding (random-uuid)
        target (random-uuid)
        coalesce (ids/fn-id "core.logic" :coalesce)
        lookup (ids/fn-id "core.collections" :get)
        paths (into {} (map #(vector % [(ids/slot-id coalesce %)]) [:value :default]))
        domain '[:union :null a]
        scope [owner []]
        caller {:signature {:return :any}
                :signature-source {:base-fn-id coalesce :argument-paths paths
                                   :root-entry {:fn-id coalesce :namespace "core.logic" :name :coalesce
                                                :args {:value domain :default domain} :return 'a :effects #{}}}}
        origin {:fn-id target :signature {:return :any}
                :signature-source {:base-fn-id lookup
                                   :root-entry {:fn-id lookup :namespace "core.collections" :name :get
                                                :args '{:coll [:union :null [:list :any] [:map a :any]] :key :any :default :any}
                                                :return :any}}}
        graph {:scopes {[] caller [binding] origin}}
        initial {'a [:list :never]}
        value {:address [scope (:value paths)] :binding-id binding :expected-type domain
               :expression {:kind :ref :fn-id target} :reference {:mode :value :actual-type :any}}
        fallback {:address [scope (:default paths)] :expected-type domain
                  :expression {:kind :value :value []} :reference {:mode :literal :actual-type [:list :never]}}
        result (fn [g v d] (operands/reconcile initial [v d] g))]
    (is (= {'a :any} (result graph value fallback)))
    (doseq [other [(assoc-in fallback [:expression :value] [nil])
                   (assoc-in fallback [:reference :actual-type] [:list :null])
                   (assoc-in fallback [:reference :actual-type] [:union :null [:list :text]])
                   (assoc-in fallback [:expression :value] nil)]]
      (is (= initial (result graph value other))))
    (is (= initial (result graph (assoc-in value [:reference :actual-type] :null) fallback)))
    (is (= initial (result (assoc-in graph [:scopes [binding] :signature-source :base-fn-id] (random-uuid)) value fallback)))
    (is (= initial (result (assoc-in graph [:scopes [] :configured-contract :fields] [{:id (random-uuid) :type 'a}]) value fallback)))))
