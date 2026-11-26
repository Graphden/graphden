(ns graphden.input.analysis-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner root field] (repeatedly 3 random-uuid)]
    {:owner owner :field field
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root}}
                 :members {root [{:id (random-uuid) :slot-id field :position 0}]}
                 :fields {field {:id field :name "name" :required true
                                 :type-fn-id (get (ids/primitive-fn-ids) :text)}}})}))


(defn- bind-row
  [owner field expression]
  (merge {:id (random-uuid) :fn-id owner :scope-path [] :target-path [field]} expression))


(deftest local-occupancy-does-not-claim-type-checking-without-adapter-test
  (let [{:keys [owner field context]} (fixture)
        row (bind-row owner field {:expression-kind "value" :value-present true :value "bound"})
        result (analysis/analyze-input (assoc-in context [:bindings (:id row)] row) owner)]
    (is (= [] (:required-addresses result)))
    (is (= :incomplete (:status result)))
    (is (= [{:code :type-checker-required :address [[owner []] [field]]}] (:diagnostics result)))
    (is (false? (get-in result [:coverage :type-checker])))))


(deftest unbound-required-input-is-distinct-from-a-type-error-test
  (let [{:keys [owner field context]} (fixture)
        result (analysis/analyze-input context owner)]
    (is (= [[[owner []] [field]]] (:required-addresses result)))
    (is (= :incomplete (:status result)))
    (is (= {} (:bindings result)))))


(deftest evaluated-ref-availability-is-an-explicit-coverage-gap-test
  (let [{:keys [owner field context]} (fixture)
        target (random-uuid)
        row (bind-row owner field {:expression-kind "ref" :ref-fn-id target})
        result (analysis/analyze-input
                 (-> context (assoc-in [:fns target] {:id target})
                     (assoc-in [:signatures target] {:return :text :args {:source :int} :effects #{:db}})
                     (assoc-in [:bindings (:id row)] row)) owner)]
    (is (nil? (:effective-contract result)))
    (is (false? (get-in result [:coverage :recursive-availability])))
    (is (contains? (set (map :code (:diagnostics result))) :reference-availability-required))
    (is (= target (get-in result [:bindings [[owner []] [field]] :expression :fn-id])))))


(deftest duplicate-supplied-binding-path-is-never-map-overwritten-test
  (let [{:keys [owner field context]} (fixture)
        a (bind-row owner field {:expression-kind "value" :value-present true :value "same"})
        b (assoc a :id (random-uuid))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid structured input binding"
          (analysis/analyze-input (assoc context :bindings {(:id a) a (:id b) b}) owner)))))


(deftest replayed-rows-belong-to-one-final-function-use
  (let [{:keys [owner field context]} (fixture)
        child (random-uuid)
        ctx (assoc-in context [:fns child] {:input-type-fn-id (get-in context [:fns owner :input-type-fn-id])
                                            :parent-ids [owner]})
        row (assoc (bind-row owner field {:expression-kind "value" :value-present true :value "parent"}) :kind :input-binding)
        replay (analysis/with-use-bindings ctx {owner [row]})
        bound (get-in (analysis/analyze-input replay owner) [:bindings [[owner []] [field]]])]
    (is (not= (:snapshot-id ctx) (:snapshot-id replay)))
    (is (= [owner (:id row)] ((juxt :origin-fn-id :binding-id) bound)))
    (is (= [[[child []] [field]]] (:required-addresses (analysis/analyze-input replay child))))
    (is (empty? (:bindings ctx)))
    (doseq [invalid [(assoc row :fn-id child) (assoc row :org-id (random-uuid))
                     (assoc row :required true) (assoc row :scope-path [(random-uuid)])]]
      (is (thrown? clojure.lang.ExceptionInfo (analysis/with-use-bindings ctx {owner [invalid]}))))
    (is (thrown? clojure.lang.ExceptionInfo (analysis/with-use-bindings ctx {owner [row row]})))
    (is (thrown? clojure.lang.ExceptionInfo (analysis/with-use-bindings replay {child [row]})))
    (let [existing (assoc row :id (random-uuid))
          stored (assoc-in ctx [:bindings (:id existing)] existing)]
      (is (thrown? clojure.lang.ExceptionInfo (analysis/with-use-bindings stored {owner [existing]})))
      (is (thrown? clojure.lang.ExceptionInfo
            (analysis/analyze-input (analysis/with-use-bindings stored {owner [row]}) owner))))))
