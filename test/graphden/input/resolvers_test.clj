(ns graphden.input.resolvers-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.signatures :as signatures]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner resolver root resolver-input result path edge secret] (repeatedly 8 random-uuid)
        text (:text (ids/primitive-fn-ids))]
    {:owner owner :resolver resolver :root root :resolver-input resolver-input
     :result result :path path :edge edge :secret secret :text text
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root} resolver {:input-type-fn-id resolver-input}}
                 :type-bodies {secret [:secret :text]}
                 :signatures {resolver {:args {:path :text} :return [:secret :text] :effects #{:network}}}
                 :members {root [{:slot-id result :position 0} {:slot-id path :position 1}]
                           resolver-input [{:slot-id path :position 0}]}
                 :fields {result {:id result :name "result" :type-fn-id secret :required true}
                          path {:id path :name "path" :type-fn-id text :required false}}
                 :bindings {edge {:id edge :fn-id owner :scope-path [] :target-path [result]
                                  :expression-kind "value" :value-present true :value "secret/path"
                                  :resolver-fn-id resolver}}})}))


(deftest stored-resolver-input-is-a-derived-literal-not-a-caller-capture-test
  (let [{:keys [owner path edge context]} (fixture)
        graph (analysis/analyze-effective-input context owner)
        scope [owner [edge]]
        argument (get-in graph [:resolver-arguments [scope [path]]])
        facts (signatures/binding-facts graph scope {:path [path]})]
    (is (= :resolved-value (:mode (first (:dependencies graph)))))
    (is (= "secret/path" (:value argument)))
    (is (= "secret/path" (get-in facts [:bindings-info :path :value])))
    (is (= [] (:input-routes graph)))
    (is (= [] (:required-addresses graph)))
    (is (= {:path :text} (get-in graph [:public-input :root :type])))))


(deftest resolver-internal-captures-stop-at-the-resolver-invocation-boundary-test
  (let [{:keys [owner resolver-input path edge context text]} (fixture)
        [helper helper-input inner inner-edge] (repeatedly 4 random-uuid)
        ctx (-> context
                (assoc-in [:fns helper] {:input-type-fn-id helper-input})
                (assoc-in [:signatures helper] {:args {:path :text} :return :text :effects #{}})
                (assoc-in [:fields inner] {:id inner :name "inner" :type-fn-id text :required true})
                (update-in [:members resolver-input] conj {:slot-id inner :position 1})
                (assoc-in [:members helper-input] [{:slot-id path :position 0}])
                (assoc-in [:bindings inner-edge]
                          {:id inner-edge :fn-id (get-in context [:bindings edge :resolver-fn-id])
                           :scope-path [] :target-path [inner] :expression-kind "ref" :ref-fn-id helper}))
        graph (analysis/analyze-effective-input ctx owner)
        route (first (:input-routes graph))]
    (is (= [[owner [edge]] [path]] (:source route)))
    (is (= [[owner [edge inner-edge]] [path]] (:from route)))
    (is (= "secret/path" (get-in graph [:resolver-arguments [[owner [edge]] [path]] :value])))
    (let [deep (analysis/analyze-effective-input
                 (assoc-in ctx [:members resolver-input] [{:slot-id inner :position 0}]) owner)]
      (is (= [] (:input-routes deep)))
      (is (= "secret/path" (get-in deep [:resolver-arguments [[owner [edge inner-edge]] [path]] :value]))))))


(deftest resolver-zero-parameters-ignore-the-value-and-multiple-parameters-reject-test
  (let [{:keys [owner resolver-input context text]} (fixture)
        empty-graph (analysis/analyze-effective-input (assoc-in context [:members resolver-input] []) owner)
        other (random-uuid)
        multiple (-> context
                     (assoc-in [:fields other] {:id other :name "other" :type-fn-id text})
                     (update-in [:members resolver-input] conj {:slot-id other :position 1}))]
    (is (= {} (:resolver-arguments empty-graph)))
    (is (= 1 (count (:dependencies empty-graph))))
    (is (thrown? clojure.lang.ExceptionInfo (analysis/analyze-effective-input multiple owner)))))


(deftest resolver-output-never-becomes-an-identity-edge-or-an-implicit-closure-test
  (let [{:keys [owner resolver secret context]} (fixture)]
    (doseq [output [:fn-ref [:fn {:item :text} :bool #{}]]]
      (let [graph (analysis/analyze-effective-input
                    (-> context (assoc-in [:type-bodies secret] output)
                        (assoc-in [:signatures resolver :return] output)) owner)]
        (is (= [:resolved-value] (mapv :mode (:dependencies graph))))
        (is (= {} (:closures graph)))))))
