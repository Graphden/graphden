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
