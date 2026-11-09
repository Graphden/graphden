(ns graphden.input.scopes-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner target root target-input left right source a b] (repeatedly 9 random-uuid)
        text (get (ids/primitive-fn-ids) :text)
        binding (fn [id field]
                  {:id id :fn-id owner :scope-path [] :target-path [field]
                   :expression-kind "ref" :ref-fn-id target})]
    {:owner owner :target target :source source :a a :b b :left left :right right
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root} target {:input-type-fn-id target-input}}
                 :signatures {owner {:return :text} target {:return :text}}
                 :members {root [{:slot-id left :position 0} {:slot-id right :position 1}]
                           target-input [{:slot-id source :position 0}]}
                 :fields {left {:id left :name "left" :type-fn-id text :required true}
                          right {:id right :name "right" :type-fn-id text :required true}
                          source {:id source :name "source" :type-fn-id text :required true}}
                 :bindings {a (binding a left) b (binding b right)}})}))


(deftest shared-target-retains-distinct-binding-use-scopes-test
  (let [{:keys [owner target source a b context]} (fixture)
        result (analysis/analyze-effective-input context owner)]
    (is (= #{[] [a] [b]} (set (keys (:scopes result)))))
    (is (= #{[[owner [a]] [source]] [[owner [b]] [source]]}
           (set (:required-addresses result))))
    (is (= [target target] (mapv :target-fn-id (:dependencies result))))
    (is (true? (get-in result [:coverage :recursive-availability])))))


(deftest scoped-binding-satisfies-only-its-reference-use-test
  (let [{:keys [owner source a b context]} (fixture)
        id (random-uuid)
        overlay {:id id :fn-id owner :scope-path [a] :target-path [source]
                 :expression-kind "value" :value-present true :value "one use"}
        result (analysis/analyze-effective-input (assoc-in context [:bindings id] overlay) owner)]
    (is (= [[[owner [b]] [source]]] (:required-addresses result)))
    (is (= id (get-in result [:scopes [a] :bindings [[owner [a]] [source]] :binding-id])))))


(deftest scoped-binding-cannot-rebind-target-authored-occupancy-test
  (let [{:keys [owner target source a context]} (fixture)
        [base overlay] (repeatedly 2 random-uuid)
        row {:id base :fn-id target :scope-path [] :target-path [source]
             :expression-kind "value" :value-present true :value "same"}
        ctx (-> context (assoc-in [:bindings base] row)
                (assoc-in [:bindings overlay] (assoc row :id overlay :fn-id owner :scope-path [a])))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid structured input binding"
          (analysis/analyze-effective-input ctx owner)))))


(deftest identity-references-never-import-target-inputs-test
  (let [{:keys [owner left right source context]} (fixture)
        identity-type (get (ids/primitive-fn-ids) :fn-ref)
        ctx (-> context (assoc-in [:fields left :type-fn-id] identity-type)
                (assoc-in [:fields right :type-fn-id] identity-type))
        result (analysis/analyze-effective-input ctx owner)]
    (is (= #{[]} (set (keys (:scopes result)))))
    (is (= [] (:dependencies result)))
    (is (not-any? #(= [source] (second %)) (:required-addresses result)))))


(deftest reference-cycles-are-explicit-incomplete-coverage-test
  (let [{:keys [owner target source context]} (fixture)
        id (random-uuid)
        ctx (assoc-in context [:bindings id]
                      {:id id :fn-id target :scope-path [] :target-path [source]
                       :expression-kind "ref" :ref-fn-id owner})
        result (analysis/analyze-effective-input ctx owner)]
    (is (false? (get-in result [:coverage :recursive-availability])))
    (is (some #(= :reference-cycle (:code %)) (:diagnostics result)))))


(deftest callable-producer-lifts-constructor-input-not-invocation-parameters-test
  (let [{:keys [owner target source left right a b context]} (fixture)
        callable-id (random-uuid)
        callable [:fn {:item :int} :text #{}]
        ctx (-> context (assoc-in [:type-bodies callable-id] callable)
                (assoc-in [:fields left :type-fn-id] callable-id)
                (assoc-in [:fields right :type-fn-id] callable-id)
                (assoc-in [:signatures target] {:return callable :args {:source :text}}))
        result (analysis/analyze-effective-input ctx owner)]
    (is (= #{[[owner [a]] [source]] [[owner [b]] [source]]}
           (set (:required-addresses result))))
    (is (= #{:callable-producer} (set (map :mode (:dependencies result)))))))


(deftest use-overlays-require-an-evaluated-reference-occurrence-test
  (let [{:keys [owner source a left right context]} (fixture)
        id (random-uuid)
        overlay {:id id :fn-id owner :scope-path [(random-uuid)] :target-path [source]
                 :expression-kind "value" :value-present true :value "unreachable"}
        identity-type (get (ids/primitive-fn-ids) :fn-ref)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-effective-input (assoc-in context [:bindings id] overlay) owner)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-effective-input
            (-> context (assoc-in [:fields left :type-fn-id] identity-type)
                (assoc-in [:fields right :type-fn-id] identity-type)
                (assoc-in [:bindings id] (assoc overlay :scope-path [a]))) owner)))))
