(ns graphden.input.checker-adapter-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check :as check]))


(deftest structured-literal-uses-the-existing-directional-checker-test
  (let [input {:address [[(random-uuid) []] [(random-uuid)]]
               :expected :int :actual :int :expression {:kind :value :value 12}}]
    (is (= :checked (:status (check/check-input-binding input))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not fit"
          (check/check-input-binding (assoc input :actual :text :expression {:kind :value :value "hidden"}))))
    (is (= :deferred (:status (check/check-input-binding (assoc input :actual :any)))))))


(deftest structured-unification-keeps-concrete-sibling-direction-test
  (let [input {:address [[(random-uuid) []] []]
               :expected {:n :int :value 'a} :actual {:n :numeric :value :text}
               :expression {:kind :ref :fn-id (random-uuid)}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not fit"
          (check/check-input-binding input)))))


(deftest existing-refinement-rejection-is-sanitized-and-deferred-proof-is-explicit-test
  (let [input {:address [[(random-uuid) []] []]
               :expected [:refine :int [:> 0]] :actual :int
               :expression {:kind :value :value -1}}
        error (try (check/check-input-binding input) (catch clojure.lang.ExceptionInfo e e))]
    (is (= :refinement-violation (:reason (ex-data error))))
    (is (not (contains? (ex-data error) :binding)))
    (is (= :deferred (:status (check/check-input-binding (assoc input :expression {:kind :value :value 1})))))))


(deftest shared-input-variable-narrows-the-remaining-field-through-real-checker-test
  (let [[owner root variable x y binding] (repeatedly 6 random-uuid)
        ctx (context/build-context
              {:fns {owner {:input-type-fn-id root}}
               :type-bodies {variable 'a}
               :fields {x {:id x :name "x" :type-fn-id variable :required true}
                        y {:id y :name "y" :type-fn-id variable :required true}}
               :members {root [{:id (random-uuid) :slot-id x :position 0}
                               {:id (random-uuid) :slot-id y :position 1}]}
               :bindings {binding {:id binding :fn-id owner :scope-path [] :target-path [x]
                                   :expression-kind "value" :value-present true :value 3}}})
        result (analysis/analyze-input ctx owner {:check-expression check/check-input-binding})]
    (is (= :checked (:status result)))
    (is (= [[[owner []] [y]]] (:required-addresses result)))
    (is (= :int (get-in result [:remaining-contract :fields 1 :type])))))


(defn- polymorphic-use-fixture
  []
  (let [[owner target root target-input variable left right configuration a b av bv] (repeatedly 12 random-uuid)
        primitives (ids/primitive-fn-ids)
        ref-row (fn [id field]
                  {:id id :fn-id owner :scope-path [] :target-path [field]
                   :expression-kind "ref" :ref-fn-id target})
        config (fn [id edge value]
                 {:id id :fn-id owner :scope-path [edge] :target-path [configuration]
                  :expression-kind "value" :value-present true :value value})]
    {:owner owner :left left :right right :bv bv
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root} target {:input-type-fn-id target-input}}
                 :type-bodies {variable 'a}
                 :signatures {target {:args {:configuration 'a} :return 'a}}
                 :members {root [{:slot-id left :position 0} {:slot-id right :position 1}]
                           target-input [{:slot-id configuration :position 0}]}
                 :fields {left {:id left :name "left" :type-fn-id (:int primitives) :required true}
                          right {:id right :name "right" :type-fn-id (:text primitives) :required true}
                          configuration {:id configuration :name "configuration" :type-fn-id variable :required true}}
                 :bindings {a (ref-row a left) b (ref-row b right)
                            av (config av a 3) bv (config bv b "text")}})}))


(deftest independent-polymorphic-uses-check-with-one-solution-and-distinct-types-test
  (let [{:keys [owner left right context]} (polymorphic-use-fixture)
        result (analysis/analyze-effective-input context owner {:check-expression check/check-input-binding})]
    (is (= :checked (:status result)))
    (is (= [] (:required-addresses result)))
    (is (= :int (get-in result [:scopes [] :bindings [[owner []] [left]] :reference :actual-type])))
    (is (= :text (get-in result [:scopes [] :bindings [[owner []] [right]] :reference :actual-type])))))


(deftest constructor-input-cannot-disagree-with-its-coupled-return-type-test
  (let [{:keys [owner bv context]} (polymorphic-use-fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
          (analysis/analyze-effective-input (assoc-in context [:bindings bv :value] 9) owner
                                            {:check-expression check/check-input-binding})))))


(deftest field-projection-cannot-drop-a-container-secret-marker-test
  (let [[owner root source target binding] (repeatedly 5 random-uuid)
        text (:text (ids/primitive-fn-ids))
        ctx (context/build-context
              {:fns {owner {:input-type-fn-id root}}
               :type-bodies {root [:secret {:source :text :target :text}]}
               :members {root [{:slot-id source :position 0} {:slot-id target :position 1}]}
               :fields {source {:id source :name "source" :required false :type-fn-id text}
                        target {:id target :name "target" :required true :type-fn-id text}}
               :bindings {binding {:id binding :fn-id owner :scope-path [] :target-path [target]
                                   :expression-kind "projection" :projection-scope-path [] :projection-path [source]}}})]
    (is (thrown? clojure.lang.ExceptionInfo
          (analysis/analyze-effective-input ctx owner {:check-expression check/check-input-binding})))))


(defn- refinement-context
  [container?]
  (let [[owner root scalar field binding] (repeatedly 5 random-uuid)
        integer (:int (ids/primitive-fn-ids))
        ctx (context/build-context
              {:fns {owner {:input-type-fn-id root}}
               :type-bodies (if container?
                              {root [:refine {:value :int} [:present :value]]}
                              {scalar [:refine :int [:> 0]]})
               :members {root [{:slot-id field :position 0}]}
               :fields {field {:id field :name "value" :required true
                               :type-fn-id (if container? integer scalar)}}
               :bindings {binding {:id binding :fn-id owner :scope-path [] :target-path [field]
                                   :expression-kind "value" :value-present true :value 1}}})]
    {:context ctx :owner owner :field field}))


(deftest checked-analysis-keeps-scalar-and-container-runtime-boundaries-explicit-test
  (doseq [[container? kind trigger] [[false :value-type :field-read]
                                     [true :container-type :container-materialization]]]
    (let [{:keys [owner field context]} (refinement-context container?)
          result (analysis/analyze-effective-input context owner {:check-expression check/check-input-binding})
          obligation (first (:obligations result))]
      (is (= :checked (:status result)))
      (is (= 1 (count (:obligations result))))
      (is (= [[owner []] (if container? [] [field])] (:address obligation)))
      (is (= kind (:kind obligation)))
      (is (= trigger (:trigger obligation)))
      (is (true? (get-in result [:coverage :runtime-validation-required]))))))


(deftest supported-runtime-obligations-do-not-hide-a-missing-checker-test
  (let [{:keys [owner context]} (refinement-context true)
        result (analysis/analyze-effective-input context owner)]
    (is (= :incomplete (:status result)))
    (is (some #(= :type-checker-required (:code %)) (:diagnostics result)))
    (is (= :container-materialization (:trigger (first (:obligations result)))))))
