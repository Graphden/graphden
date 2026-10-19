(ns graphden.crud.callable-reconstruction-test
  "Pure stored-row reconstruction of callable overrides; no DB fixture."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.type-check :as tc]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(deftest anonymous-callable-overrides-survive-each-binding-shape
  (doseq [shape [[:fn {:x :int} :int]
                 [:fn {} :int #{:db}]
                 [:fn {:x 'a} 'a #{:db} #{}]
                 [:fn {} :text #{} #{:browser-main}]
                 [:fn {} :text :any [:execution-var 'E]]]]
    (let [type-id (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str shape)))
          slot-id (random-uuid)
          ref-id (random-uuid)
          rows {type-id {:id type-id :name nil :constraint shape}
                ref-id {:id ref-id :name "callback"}}
          base {:slot-id slot-id :type-override-fn-id type-id}
          decode (fn [binding] (tc/binding-shape-for-edn nil rows {} {} binding))]
      (is (= {:type shape} (decode base)))
      (is (= {:ref :callback :type shape}
             (decode (assoc base :ref-fn-id ref-id))))
      (is (= {:value nil :type shape}
             (decode (assoc base :value nil :value-present true))))
      (is (= {:as :renamed :type shape}
             (tc/binding-shape-for-edn nil rows {}
                                       {slot-id {:name "renamed" :type-fn-id type-id}}
                                       {:slot-id slot-id})))
      (is (= shape (tc/rich-type-from-row (get rows type-id) rows))))))


(deftest anonymous-function-reference-keeps-identity-alongside-override
  (let [target (random-uuid)
        type-id (random-uuid)
        shape [:fn {} [:secret :text] #{:db} [:execution-var 'E]]
        rows {target {:id target :name nil}
              type-id {:id type-id :name nil :constraint shape}}]
    (is (= {:ref target :type shape}
           (tc/binding-shape-for-edn nil rows {} {} {:ref-fn-id target :type-override-fn-id type-id})))
    (is (types/contains-secret? (:type (tc/binding-shape-for-edn nil rows {} {}
                                                                 {:ref-fn-id target :type-override-fn-id type-id}))))))
