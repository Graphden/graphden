(ns graphden.input.projections-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner root source target] (repeatedly 4 random-uuid)
        text (get (ids/primitive-fn-ids) :text)]
    {:owner owner :root root :source source :target target
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root}}
                 :members {root [{:slot-id source :position 0} {:slot-id target :position 1}]}
                 :fields {source {:id source :name "source" :type-fn-id text :required false}
                          target {:id target :name "target" :type-fn-id text :required true}}})}))


(defn- projection
  [owner target source]
  {:id (random-uuid) :fn-id owner :scope-path [] :target-path [target]
   :expression-kind "projection" :projection-scope-path [] :projection-path [source]})


(deftest required-projection-exposes-an-optional-source-as-required-test
  (let [{:keys [owner source target context]} (fixture)
        row (projection owner target source)
        result (analysis/analyze-effective-input (assoc-in context [:bindings (:id row)] row) owner)]
    (is (= [[[owner []] [source]]] (:required-addresses result)))
    (is (true? (get-in result [:coverage :recursive-availability])))
    (is (= :text (get-in result [:scopes [] :bindings [[owner []] [target]] :reference :actual-type])))
    (is (false? (get-in result [:scopes [] :declared-contract :fields 0 :required])))))


(deftest projection-preserves-container-secret-markers-test
  (let [{:keys [owner root source target context]} (fixture)
        secret-text (random-uuid)
        row (projection owner target source)
        ctx (-> context (assoc-in [:type-bodies root] [:secret {:source :text :target [:secret :text]}])
                (assoc-in [:type-bodies secret-text] [:secret :text])
                (assoc-in [:fields target :type-fn-id] secret-text)
                (assoc-in [:bindings (:id row)] row))
        result (analysis/analyze-effective-input ctx owner)]
    (is (= [:secret :text]
           (get-in result [:scopes [] :bindings [[owner []] [target]] :reference :actual-type])))))


(deftest projection-cycles-cannot-hide-behind-occupied-fields-test
  (let [{:keys [owner source target context]} (fixture)
        a (projection owner target source)
        b (projection owner source target)
        ctx (assoc context :bindings {(:id a) a (:id b) b})
        error (try (analysis/analyze-effective-input ctx owner)
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= :projection-cycle (:code (ex-data error))))))


(deftest outer-projection-keeps-its-source-owner-when-used-inside-a-reference-test
  (let [{:keys [owner source target context]} (fixture)
        [callee callee-input parameter edge] (repeatedly 4 random-uuid)
        overlay (assoc (projection owner parameter source) :scope-path [edge])
        ctx (-> context
                (assoc-in [:fns callee] {:input-type-fn-id callee-input})
                (assoc-in [:members callee-input] [{:slot-id parameter :position 0}])
                (assoc-in [:fields parameter] {:id parameter :name "parameter" :required true
                                               :type-fn-id (get (ids/primitive-fn-ids) :text)})
                (assoc-in [:signatures callee] {:return :text})
                (assoc :bindings {edge {:id edge :fn-id owner :scope-path [] :target-path [target]
                                        :expression-kind "ref" :ref-fn-id callee}
                                  (:id overlay) overlay}))
        result (analysis/analyze-effective-input ctx owner)]
    (is (= [[[owner []] [source]]] (:required-addresses result)))
    (is (= [[owner []] [source]]
           (get-in result [:scopes [edge] :bindings [[owner [edge]] [parameter]] :reference :source])))
    (let [cyclic (assoc-in ctx [:bindings (:id overlay) :projection-path] [target])
          error (try (analysis/analyze-effective-input cyclic owner)
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= :projection-cycle (:code (ex-data error)))))))


(deftest view-projection-obeys-the-frozen-marker-policy-test
  (doseq [hidden? [false true]]
    (let [id (random-uuid)
          ctx (context/build-context {:markers {:ui-view {:hide-result? hidden?}}})
          node {:type [:ui-view {:name :text}]
                :fields [{:id id :name :name :type :text :required true}]}]
      (context/call-with-types
        ctx
        (fn []
          (is (= (if hidden? [:ui-view :text] :text) (descriptor/read-type-at node [id])))
          (is (= [:ui-view {:name :text}] (:type node)))
          (is (= [:ui-view {:name :text}] (descriptor/read-type-at node []))))))))
