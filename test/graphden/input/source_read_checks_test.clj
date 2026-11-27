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


(deftest an-unchanged-projection-must-read-the-supplied-reference-itself
  (let [[owner field row-id projected-field projection-id target] (repeatedly 6 random-uuid)
        source [[owner []] [field]]
        dest [[owner []] [projected-field]]
        used [owner [row-id]]
        row {:id row-id :ref-fn-id target}
        certificate {:address source :row row}
        expected [:map :keyword :text]
        old (check-at dest :any)
        current (check-at dest expected)
        projection {:binding-id projection-id :expression {:kind :projection :scope (first source) :path [field]}
                    :expected-type :any :reference {:mode :projection :source source}}
        before {:proofs {dest {:status :deferred :reason :runtime-type-obligation}}
                :scopes {[] {:bindings {dest projection}}}}
        after (-> before
                  (assoc :dependencies [{:from source :to used :target-fn-id target :binding-id row-id :mode :value}])
                  (assoc-in [:scopes [row-id]] {:fn-id target :signature {:return expected}})
                  (assoc-in [:scopes [] :bindings source] {:binding-id row-id :expression {:kind :ref :fn-id target}
                                                           :reference {:mode :value :actual-type expected}}))
        prove #(reads/projected-reference before % {source certificate} old current)]
    (is (= [target used] (prove after)))
    (let [variable 'a
          source-proof (assoc certificate :checked-open {:original variable :type :any})
          binding-type (fn [g t]
                         (-> g (assoc-in [:scopes [] :bindings dest :expected-type] t)
                             (assoc-in [:scopes [] :bindings dest :reference :actual-type] t)))
          b (binding-type before variable)
          g (-> (binding-type after :any) (assoc-in [:scopes [] :bindings source :reference :actual-type] :any)
                (assoc-in [:scopes [row-id] :signature :return] :any))
          match (fn [b g c]
                  (reads/projected-reference b g {source c} (assoc old :expected-type variable) (assoc current :expected-type :any)))]
      (is (= [target used] (match b g source-proof)))
      (is (nil? (match b g certificate)))
      (is (nil? (match b g (assoc-in source-proof [:checked-open :original] 'other))))
      (is (nil? (match (assoc-in b [:scopes [] :bindings dest :expression :path] [projected-field]) g source-proof))))
    (let [read-path [:scopes [] :bindings dest]
          pending (-> before
                      (assoc-in (conj read-path :expected-type) 'a)
                      (assoc-in (into read-path [:reference :actual-type]) 'a))
          solved (-> after
                     (assoc :substitution {'a expected})
                     (assoc-in [:proofs dest] {:status :checked})
                     (assoc-in (conj read-path :expected-type) expected)
                     (assoc-in (into read-path [:reference :actual-type]) expected))
          prove-resolution #(reads/resolved-projection-reference pending %1 {source certificate} %2)
          check (check-at dest 'a)]
      (is (= [target expected] (prove-resolution solved check)))
      (is (nil? (prove-resolution (assoc solved :substitution {}) check)))
      (is (nil? (prove-resolution solved (assoc check :additional-types [:jsonb])))))
    (doseq [g [(assoc-in after [:scopes [row-id] :signature :return] :any)
               (assoc-in after [:scopes [] :bindings dest :expression :path] [projected-field])
               (assoc-in after [:scopes [] :bindings dest :reference :source] dest)
               (assoc-in after [:proofs dest :status] :checked)
               (assoc-in after [:dependencies 0 :target-fn-id] (random-uuid))
               (assoc-in after [:scopes [] :bindings source :binding-id] (random-uuid))]]
      (is (nil? (prove g))))))
