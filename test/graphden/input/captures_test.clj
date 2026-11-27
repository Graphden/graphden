(ns graphden.input.captures-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner target root target-input callable body item config edge] (repeatedly 9 random-uuid)
        primitives (ids/primitive-fn-ids)]
    {:owner owner :target target :root root :target-input target-input :callable callable
     :body body :item item :config config :edge edge
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root}
                       target {:input-type-fn-id target-input :lambda-params [:item]}}
                 :type-bodies {callable [:fn {:item :int} :text #{}]}
                 :signatures {target {:return :text :effects #{} :args {:item :int :config :text}}}
                 :members {root [{:slot-id body :position 0} {:slot-id config :position 1}]
                           target-input [{:slot-id item :position 0} {:slot-id config :position 1}]}
                 :fields {body {:id body :name "body" :required true :type-fn-id callable}
                          item {:id item :name "item" :required true :type-fn-id (:int primitives)}
                          config {:id config :name "config" :required true :type-fn-id (:text primitives)}}
                 :bindings {edge {:id edge :fn-id owner :scope-path [] :target-path [body]
                                  :expression-kind "ref" :ref-fn-id target}}})}))


(deftest callable-parameters-and-stable-identity-captures-have-distinct-routes-test
  (let [{:keys [owner body item config edge context]} (fixture)
        graph (analysis/analyze-effective-input context owner)
        closure (get-in graph [:closures [[owner []] [body]]])]
    (is (= [:item] (:parameters closure)))
    (is (= [{:target [[owner [edge]] [item]] :value-path [] :parameter :item}] (:call-sites closure)))
    (is (= [[owner []] [config]] (:source (first (:captures closure)))))
    (is (= [[[owner []] [config]]] (:required-addresses graph)))
    (is (= {:config :text} (get-in graph [:public-input :root :type])))
    (is (true? (get-in graph [:coverage :recursive-availability])))))


(deftest explicit-empty-parameters-capture-even-a-structural-name-match-test
  (let [{:keys [owner target body item edge context]} (fixture)
        graph (analysis/analyze-effective-input (assoc-in context [:fns target :lambda-params] []) owner)]
    (is (= [] (get-in graph [:closures [[owner []] [body]] :parameters])))
    (is (contains? (set (:required-addresses graph)) [[owner [edge]] [item]]))
    (is (= {:item :int} (get-in graph [:public-input :root :type :body])))))


(deftest positional-inference-excludes-a-capture-by-identity-test
  (let [{:keys [owner target callable body context]} (fixture)
        ctx (-> context (update-in [:fns target] dissoc :lambda-params)
                (assoc-in [:type-bodies callable] [:fn {:argument :int} :text #{}]))
        graph (analysis/analyze-effective-input ctx owner)]
    (is (= [:item] (get-in graph [:closures [[owner []] [body]] :parameters])))))


(deftest same-name-different-origin-capture-needs-an-explicit-migration-projection-test
  (let [{:keys [owner target-input config edge context]} (fixture)
        other (random-uuid)
        ctx (-> context
                (assoc-in [:fields other] (assoc (get-in context [:fields config]) :id other))
                (assoc-in [:members target-input 1 :slot-id] other))
        native (analysis/analyze-effective-input ctx owner)
        projection (random-uuid)
        lowered (assoc-in ctx [:bindings projection]
                          {:id projection :fn-id owner :scope-path [edge] :target-path [other]
                           :expression-kind "projection" :projection-scope-path [] :projection-path [config]})
        migrated (analysis/analyze-effective-input lowered owner)]
    (is (contains? (set (:required-addresses native)) [[owner [edge]] [other]]))
    (is (= {:config :text} (get-in native [:public-input :root :type :body])))
    (is (= [[[owner []] [config]]] (:required-addresses migrated)))
    (is (= {:config :text} (get-in migrated [:public-input :root :type])))))


(deftest ambiguous-positional-candidates-require-authored-parameters-test
  (let [{:keys [owner target root callable context]} (fixture)
        ctx (-> context (update-in [:fns target] dissoc :lambda-params)
                (assoc-in [:type-bodies callable] [:fn {:argument :int} :text #{}])
                (update-in [:members root] #(vec (take 1 %))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Ambiguous"
          (analysis/analyze-effective-input ctx owner)))))


(deftest invocation-parameters-flow-through-shared-identity-value-references-test
  (let [{:keys [owner target target-input item config edge context]} (fixture)
        [helper helper-input helper-field helper-edge] (repeatedly 4 random-uuid)
        ctx (-> context
                (assoc-in [:fns helper] {:input-type-fn-id helper-input})
                (assoc-in [:signatures helper] {:return :text :effects #{}})
                (assoc-in [:members helper-input] [{:slot-id item :position 0} {:slot-id config :position 1}])
                (update-in [:members target-input] conj {:slot-id helper-field :position 2})
                (assoc-in [:fields helper-field] {:id helper-field :name "helper" :required true
                                                  :type-fn-id (:text (ids/primitive-fn-ids))})
                (assoc-in [:bindings helper-edge] {:id helper-edge :fn-id target :scope-path []
                                                   :target-path [helper-field] :expression-kind "ref" :ref-fn-id helper}))
        graph (analysis/analyze-effective-input ctx owner)
        routes (into {} (map (juxt :from :source)) (:input-routes graph))]
    (is (= [[owner [edge]] [item]] (get routes [[owner [edge helper-edge]] [item]])))
    (is (= [[[owner []] [config]]] (:required-addresses graph)))
    (is (= {:config :text} (get-in graph [:public-input :root :type])))))


(deftest fix-style-self-is-an-invocation-parameter-without-a-graph-cycle-test
  (let [{:keys [owner target root callable item config body edge context]} (fixture)
        self-type (random-uuid)
        self [:fn {:argument :any} :any #{}]
        ctx (-> context
                (assoc-in [:type-bodies self-type] self)
                (assoc-in [:type-bodies callable] [:fn {:self self} :text #{}])
                (assoc-in [:fields item :name] "self")
                (assoc-in [:fields item :type-fn-id] self-type)
                (assoc-in [:fns target :lambda-params] [:self])
                (assoc-in [:signatures target :args] {:self self :config :text})
                (update-in [:members root] #(vec (take 1 %))))
        graph (analysis/analyze-effective-input ctx owner)]
    (is (= [:self] (get-in graph [:closures [[owner []] [body]] :parameters])))
    (is (= [[[owner [edge]] [config]]] (:required-addresses graph)))
    (is (= {:body {:config :text}} (get-in graph [:public-input :root :type])))))
