(ns graphden.input.source-read-checks-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-read-checks :as reads]))


(defn- check-at
  [[scope path :as address] type]
  {:id [address :value-type :field-read] :scope scope :path path :address address
   :kind :value-type :trigger :field-read :expected-type type})


(deftest a-frozen-literal-must-pass-the-check-at-its-own-binding
  (let [address [[(random-uuid) []] [(random-uuid)]]
        type '[:union :null [:list :text] [:map a :any]]
        row {:id (random-uuid) :expression-kind "value" :value-present true :value ["one"]}
        certificate {:address address :row row}
        bound {:binding-id (:id row) :expression {:kind :value :value ["one"]} :expected-type type :reference {:mode :literal}}
        graph {:scopes {[] {:bindings {address bound}}}}
        prove #(reads/literal-read? {} %1 %2 (check-at address type))]
    (is (prove graph certificate))
    (is (not (prove graph (assoc-in certificate [:row :value] ["two"]))))
    (is (not (prove graph (assoc-in certificate [:row :id] (random-uuid)))))
    (is (not (prove (assoc-in graph [:scopes [] :bindings address :expected-type] :any) certificate)))
    (is (not (prove (assoc-in graph [:scopes [] :bindings address :expression :value] [1])
                    (assoc-in certificate [:row :value] [1]))))))


(deftest a-literal-can-discharge-only-a-statically-checked-ancestor-read
  (let [[owner callee edge-id literal-id field literal-field] (repeatedly 6 random-uuid)
        address [[owner []] [field]]
        child [owner [edge-id]]
        literal [child [literal-field]]
        certificate {:address literal :row {:id literal-id :expression-kind "value" :value-present true :value "ok"}}
        expected [:map :keyword :any]
        expression {:kind :ref :fn-id callee}
        ref-binding {:binding-id edge-id :expression expression :expected-type expected
                     :reference {:mode :value :actual-type {:name :any}}}
        edge {:from address :to child :target-fn-id callee :binding-id edge-id :mode :value}
        before {:dependencies [edge] :scopes {[] {:bindings {address ref-binding}} [edge-id] {:fn-id callee}}
                :proofs {address {:status :deferred :reason :runtime-type-obligation}}}
        after (-> before (assoc-in [:proofs address] {:status :checked})
                  (assoc-in [:scopes [] :bindings address :reference :actual-type] {:name :text})
                  (assoc-in [:scopes [edge-id] :signature :return] {:name :text})
                  (assoc-in [:scopes [edge-id] :bindings literal]
                            {:binding-id literal-id :expression {:kind :value :value "ok"} :reference {:mode :literal}}))
        verify #(reads/discharged? {} before % [certificate] (check-at address expected))]
    (is (verify after))
    (doseq [changed [(assoc-in after [:scopes [edge-id] :fn-id] (random-uuid))
                     (assoc-in after [:scopes [edge-id] :signature :return] :any)
                     (assoc-in after [:scopes [] :bindings address :reference :actual-type] :any)
                     (assoc-in after [:proofs address :status] :deferred)
                     (assoc-in after [:scopes [edge-id] :bindings literal :binding-id] (random-uuid))
                     (assoc-in after [:dependencies 0 :target-fn-id] (random-uuid))]]
      (is (not (verify changed))))))
