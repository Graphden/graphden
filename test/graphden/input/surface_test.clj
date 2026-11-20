(ns graphden.input.surface-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.surface :as surface]
    [graphden.packages.records.ids :as ids]))


(defn- ref-fixture
  []
  (let [[owner target root result-record target-input user result-name input-name edge] (repeatedly 9 random-uuid)
        text (:text (ids/primitive-fn-ids))]
    {:owner owner :user user :result-name result-name :input-name input-name :edge edge
     :context (context/build-context
                {:fns {owner {:input-type-fn-id root} target {:input-type-fn-id target-input}}
                 :signatures {target {:return {:name :text}}}
                 :members {root [{:slot-id user :position 0}]
                           result-record [{:slot-id result-name :position 0}]
                           target-input [{:slot-id input-name :position 0}]}
                 :fields {user {:id user :name "user" :required true :type-fn-id result-record}
                          result-name {:id result-name :name "name" :required true :type-fn-id text}
                          input-name {:id input-name :name "name" :required true :type-fn-id text}}
                 :bindings {edge {:id edge :fn-id owner :scope-path [] :target-path [user]
                                  :expression-kind "ref" :ref-fn-id target}}})}))


(def options {:name->id {} :binding-id (fn [_ _] (random-uuid))})


(deftest dependency-input-name-never-reopens-the-same-named-result-field-test
  (let [{:keys [owner user result-name input-name edge context]} (ref-fixture)
        graph (analysis/analyze-effective-input context owner)
        public (:public-input graph)
        state (surface/bind-input context public {:user {:name "Ada"}} options)]
    (is (= {:user {:name :text}} (get-in public [:root :type])))
    (is (= :reference-inputs (get-in public [:routes [:user] :kind])))
    (is (= [[owner [edge]] [input-name]] (get-in public [:routes [:user :name] :address])))
    (is (= "Ada" (get-in state [[[owner [edge]] [input-name]] :expression :value])))
    (is (= edge (get-in state [[[owner []] [user]] :binding-id])))
    (is (not (contains? state [[owner []] [user result-name]])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid structured input binding"
          (input/bind-input
            (get-in graph [:scopes [] :declared-contract]) (:bindings public) [owner []]
            {:kind :partial :fields {user {:kind :partial
                                           :fields {result-name {:kind :value :value "Ada" :binding-id (random-uuid)}}}}})))))


(deftest reference-groups-and-stale-surfaces-cannot-be-bound-as-result-values-test
  (let [{:keys [owner context]} (ref-fixture)
        public (:public-input (analysis/analyze-effective-input context owner))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (surface/bind-input context public {:user [:value {:name "Ada"}]} options)))
    (let [error (try (surface/bind-input (assoc context :snapshot-id (random-uuid)) public
                                         {:user {:name "Ada"}} options)
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= :stale-input-surface (:code (ex-data error)))))))


(deftest partial-record-surface-binds-only-unoccupied-children-test
  (let [[owner root person-type person name age binding] (repeatedly 7 random-uuid)
        primitives (ids/primitive-fn-ids)
        ctx (context/build-context
              {:fns {owner {:input-type-fn-id root}}
               :members {root [{:slot-id person :position 0}]
                         person-type [{:slot-id name :position 0} {:slot-id age :position 1}]}
               :fields {person {:id person :name "person" :type-fn-id person-type :required true}
                        name {:id name :name "name" :type-fn-id (:text primitives) :required true}
                        age {:id age :name "age" :type-fn-id (:int primitives) :required true}}
               :bindings {binding {:id binding :fn-id owner :scope-path [] :target-path [person name]
                                   :expression-kind "value" :value-present true :value "Ada"}}})
        graph (analysis/analyze-effective-input ctx owner)
        public (:public-input graph)
        state (surface/bind-input ctx public {:person {:age 37}} options)]
    (is (= {:person {:age :int}} (get-in public [:root :type])))
    (is (= {:person {:name :text :age :int}} (get-in graph [:scopes [] :declared-contract :type])))
    (is (= 37 (get-in state [[[owner []] [person age]] :expression :value])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (surface/bind-input ctx public {:person [:value {:age 37}]} options)))))
