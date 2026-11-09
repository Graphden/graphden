(ns graphden.input.inference-test
  "Pure ordering fixtures. The callback is a stub; real engine proof lives in
   checker-adapter-test and must run on the JVM."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(defn- inference-stub
  [{:keys [root-entry bindings-info]}]
  {:return (if (symbol? (:return root-entry)) (:type (val (first bindings-info))) (:return root-entry))
   :effects #{} :execution :unknown :substitution {} :argument-proofs {}})


(defn callable-fixture
  []
  (let [[owner producer helper owner-input producer-input helper-input result value item a b callable]
        (repeatedly 12 random-uuid)
        type [:fn {:item :text} :bool #{} :unknown]
        text (:text (ids/primitive-fn-ids))
        implementation (fn [id args return paths]
                         {:base-fn-id id :root-entry {:args args :return return :effects #{} :execution :unknown}
                          :argument-paths paths})]
    {:owner owner :producer producer :helper helper :result result :value value :a a :b b
     :options {:infer-signature inference-stub
               :implementations {owner (implementation owner {:result type} type {:result [result]})
                                 producer (implementation producer {:value 'a} 'a {:value [value]})
                                 helper (implementation helper {:item :text} :bool {:item [item]})}}
     :context (context/build-context
                {:fns {owner {:input-type-fn-id owner-input} producer {:input-type-fn-id producer-input}
                       helper {:input-type-fn-id helper-input}}
                 :type-bodies {callable type}
                 :fields {result {:id result :name "result" :type-fn-id callable :required true}
                          value {:id value :name "value" :type-fn-id callable :required true}
                          item {:id item :name "item" :type-fn-id text :required true}}
                 :members {owner-input [{:slot-id result :position 0}]
                           producer-input [{:slot-id value :position 0}]
                           helper-input [{:slot-id item :position 0}]}
                 :bindings {a {:id a :fn-id owner :scope-path [] :target-path [result]
                               :expression-kind "ref" :ref-fn-id producer}
                            b {:id b :fn-id producer :scope-path [] :target-path [value]
                               :expression-kind "ref" :ref-fn-id helper}}})}))


(deftest producer-return-is-inferred-before-the-outer-callable-dispatch-test
  (let [{:keys [owner result value a b context options]} (callable-fixture)
        graph (analysis/analyze-effective-input context owner options)]
    (is (= :callable-producer (get-in graph [:scopes [] :bindings [[owner []] [result]] :reference :mode])))
    (is (= :graph-callable (get-in graph [:scopes [a] :bindings [[owner [a]] [value]] :reference :mode])))
    (is (= #{[[owner [a]] [value]]} (set (keys (:closures graph)))))
    (is (= [:item] (get-in graph [:closures [[owner [a]] [value]] :parameters])))
    (is (= [] (:required-addresses graph)))
    (is (true? (get-in graph [:coverage :signatures])))
    (is (false? (get-in graph [:coverage :type-checker])))
    (is (= #{[] [a] [a b]} (set (keys (:scopes graph)))))))


(deftest missing-implementation-is-not-a-successful-signature-proof-test
  (let [{:keys [owner context options]} (callable-fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
          (analysis/analyze-effective-input context owner (update options :implementations dissoc owner))))))


(deftest explicit-projection-orders-the-source-return-before-its-consumer-test
  (let [[owner target root input left right value a b literal projection variable] (repeatedly 12 random-uuid)
        any (:any (ids/primitive-fn-ids))
        ctx (context/build-context
              {:fns {owner {:input-type-fn-id root} target {:input-type-fn-id input}}
               :type-bodies {variable 'a}
               :members {root [{:slot-id left :position 0} {:slot-id right :position 1}]
                         input [{:slot-id value :position 0}]}
               :fields {left {:id left :name "left" :type-fn-id any}
                        right {:id right :name "right" :type-fn-id any}
                        value {:id value :name "value" :type-fn-id variable}}
               :bindings {a {:id a :fn-id owner :scope-path [] :target-path [left] :expression-kind "ref" :ref-fn-id target}
                          b {:id b :fn-id owner :scope-path [] :target-path [right] :expression-kind "ref" :ref-fn-id target}
                          literal {:id literal :fn-id owner :scope-path [a] :target-path [value]
                                   :expression-kind "value" :value-present true :value {:name "Ada"}}
                          projection {:id projection :fn-id owner :scope-path [b] :target-path [value]
                                      :expression-kind "projection" :projection-scope-path [] :projection-path [left]}}})
        implementations {owner {:base-fn-id owner :root-entry {:args {:left :any :right :any} :return :bool}
                                :argument-paths {:left [left] :right [right]}}
                         target {:base-fn-id target :root-entry {:args {:value 'a} :return 'a}
                                 :argument-paths {:value [value]}}}
        graph (analysis/analyze-effective-input ctx owner {:implementations implementations :infer-signature inference-stub})]
    (is (= {:name :text} (get-in graph [:scopes [a] :signature :return])))
    (is (= {:name :text} (get-in graph [:scopes [b] :signature :return])))
    (is (= {:name :text} (get-in graph [:scopes [] :bindings [[owner []] [right]] :reference :actual-type])))))
