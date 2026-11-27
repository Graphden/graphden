(ns graphden.input.inheritance-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[base left right child input empty-input field binding] (repeatedly 8 random-uuid)]
    {:base base :left left :right right :child child :input input :field field :binding binding
     :context (context/build-context
                {:fns {base {:input-type-fn-id input}
                       left {:input-type-fn-id empty-input :parent-ids [base]}
                       right {:input-type-fn-id empty-input :parent-ids [base]}
                       child {:input-type-fn-id empty-input :parent-ids [left right]}}
                 :members {input [{:id (random-uuid) :slot-id field :position 0}] empty-input []}
                 :fields {field {:id field :name "name" :required true
                                 :type-fn-id (get (ids/primitive-fn-ids) :text)}}
                 :bindings {binding {:id binding :fn-id base :scope-path [] :target-path [field]
                                     :expression-kind "value" :value-present true :value "base"}}})}))


(deftest inherited-diamond-keeps-one-shared-field-and-binding-origin-test
  (let [{:keys [base child field binding context]} (fixture)
        view (inheritance/declared-view context child)]
    (is (= [field] (mapv :id (get-in view [:contract :fields]))))
    (is (= [binding] (mapv :id (:bindings view))))
    (is (= base (:origin-fn-id (first (:bindings view)))))
    (is (= child (:fn-id (first (:bindings view)))))
    (is (= 1 (count (rows/state (:bindings view)))))))


(deftest independent-equal-inherited-binds-still-conflict-test
  (let [{:keys [context left child binding]} (fixture)
        another (random-uuid)
        ctx (assoc-in context [:bindings another]
                      (assoc (get-in context [:bindings binding]) :id another :fn-id left))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid structured input binding"
          (rows/state (:bindings (inheritance/declared-view ctx child)))))))


(deftest same-public-name-does-not-merge-distinct-field-identities-test
  (let [{:keys [context child field]} (fixture)
        other-field (random-uuid)
        other-type (random-uuid)
        ctx (-> context
                (assoc-in [:fns child :input-type-fn-id] other-type)
                (assoc-in [:members other-type] [{:id (random-uuid) :slot-id other-field :position 0}])
                (assoc-in [:fields other-field] (assoc (get-in context [:fields field]) :id other-field)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (inheritance/declared-view ctx child)))))


(deftest inherited-whole-record-marker-and-refinement-survive-composition-test
  (let [{:keys [context child input]} (fixture)
        ctx (assoc-in context [:type-bodies input] [:secret [:refine {:name :text} [:present :name]]])]
    (is (= [:secret [:refine {:name :text} [:present :name]]]
           (get-in (inheritance/declared-view ctx child) [:contract :type])))))


(deftest inheritance-cycles-are-not-recursive-type-fallbacks-test
  (let [{:keys [context child base]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (inheritance/declared-view (assoc-in context [:fns base :parent-ids] [child]) child)))))


(deftest record-type-parents-use-the-existing-record-declaration-test
  (let [{:keys [context child input field]} (fixture)
        ctx (-> context (assoc-in [:fns input] {:id input})
                (assoc-in [:fns child :parent-ids] [input]))
        view (inheritance/declared-view ctx child)]
    (is (= [field] (mapv :id (get-in view [:contract :fields]))))
    (is (= [] (:bindings view)))))
