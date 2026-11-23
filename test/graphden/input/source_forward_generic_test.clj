(ns graphden.input.source-forward-generic-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.source :as source]
    [graphden.input.source-forward :as forward]
    [graphden.input.source-forward-generic :as generic]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- source-fixture
  ([] (source-fixture identity))
  ([edit]
   (let [definitions (edit [{:namespace "core.logic" :name :const :args {:value {:type 'a}} :return-type 'a}
                            {:namespace "fixture" :name :named-map :map {:key :keyword :value :any}}
                            {:namespace "fixture" :name :producer :args {:data :text} :return-type :named-map}
                            {:namespace "fixture" :name :host :parent :core.logic/const
                             :args {:value :producer :data {:type :text}}}])
         prepared (parse/prepare-module definitions {} {})
         [entry] (:definitions (source/plan-module [(peek definitions)] prepared))]
     {:prepared prepared :entry entry})))


(defn- proof-fixture
  []
  (let [{:keys [prepared entry]} (source-fixture)
        proof (forward/verify-forwarded-field prepared entry :data)
        witness (:generic-reference proof)
        {:keys [base-fn-id field-id binding-id target-fn-id return-type-ref return-type-id variable]} witness
        owner (:owner-id proof)
        scope [owner []]
        address [scope [field-id]]
        scoped (variables/type-in-scope scope variable)
        rich [:map :keyword :any]
        any-id (ids/primitive-fn-id :any)
        ctx (context/build-context
              {:fns {target-fn-id {:id target-fn-id :return-type-fn-id return-type-id}}
               :fields {field-id {:id field-id :name "value" :type-fn-id any-id}}
               :source-field-types {[field-id any-id] variable}
               :alias-ids {return-type-ref return-type-id}
               :type-origins {return-type-id {:namespace "fixture" :name :named-map :map {:key :keyword :value :any}}}
               :type-bodies {return-type-id rich}})
        graph {:substitution {scoped rich}
               :dependencies [{:from address :to [owner [binding-id]] :binding-id binding-id
                               :target-fn-id target-fn-id :mode :value}]
               :scopes {[] {:declared-contract {:fields [{:id field-id :name :value :type variable}]}
                            :configured-contract {:fields [{:id field-id :name :value :type rich}]}
                            :signature-source {:base-fn-id base-fn-id :argument-paths {:value [field-id]}
                                               :root-entry {:args {:value variable} :return variable :effects #{}}}
                            :bindings {address {:binding-id binding-id :expected-type rich
                                                :reference {:mode :value :actual-type rich}}}
                            :proofs {address {:status :checked}} :signature {:effects #{}}}
                        [binding-id] {:fn-id target-fn-id :declaration-order [target-fn-id]
                                      :signature {:return rich :effects #{}}}}}]
    {:context ctx :graph graph :owner owner :witness witness :address address :scoped scoped}))


(defn- rejection
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest original-const-field-identifies-a-provisional-generic-edge
  (let [{:keys [prepared entry]} (source-fixture)
        proof (forward/verify-forwarded-field prepared entry :data)
        witness (:generic-reference proof)]
    (is (= 'a (:variable witness)))
    (is (= (ids/slot-id (ids/fn-id "core.logic" :const) :value) (:field-id witness)))
    (is (= :fixture/named-map (:return-type-ref witness)))
    (is (= (ids/fn-id "fixture" :named-map) (:return-type-id witness))))
  (doseq [edit [#(assoc-in % [0 :effects] #{:db})
                #(assoc-in % [0 :return-type] 'b)
                #(assoc-in % [2 :return-type] [:fn {} :text])
                #(assoc-in % [0 :args :value :type] [:list 'a])]]
    (let [{:keys [prepared entry]} (source-fixture edit)]
      (is (= :unproved-reference-binding (rejection #(forward/verify-forwarded-field prepared entry :data)))))))


(deftest closed-substitution-retains-the-exact-abi-field
  (let [{:keys [context graph owner witness]} (proof-fixture)]
    (is (= witness (generic/verify! context graph owner witness)))
    (doseq [patch [{:version 2} {:field-id (random-uuid)} {:base-fn-id (random-uuid)}
                   {:variable 'b} {:binding-id (random-uuid)} {:return-type-id (random-uuid)}
                   {:return-type-ref :other/named-map} {:target-fn-id (random-uuid)} {:return-owner-id (random-uuid)}]]
      (is (= :unproved-generic-forward-reference
             (rejection #(generic/verify! context graph owner (merge witness patch))))))))


(deftest shapes-and-unchecked-or-mismatched-effects-do-not-establish-binders
  (let [{:keys [context graph owner witness address scoped]} (proof-fixture)
        field-id (:field-id witness)
        binding-id (:binding-id witness)]
    (doseq [transform [#(assoc % :substitution {})
                       #(assoc-in % [:substitution scoped] [:map :text :any])
                       #(assoc-in % [:scopes [] :signature-source :argument-paths :value] [(random-uuid)])
                       #(assoc-in % [:scopes [] :signature-source :root-entry :args :value] 'same-shape)
                       #(assoc-in % [:scopes [] :signature-source :root-entry :effects] #{:db})
                       #(assoc-in % [:scopes [] :proofs address :status] :deferred)
                       #(assoc-in % [:scopes [binding-id] :signature :return] [:fn {} :text])
                       #(assoc-in % [:scopes [binding-id] :declaration-order] [(random-uuid)])
                       #(assoc-in % [:scopes [binding-id] :signature :effects] #{:db})]]
      (is (= :unproved-generic-forward-reference
             (rejection #(generic/verify! context (transform graph) owner witness)))))
    (is (= :unproved-generic-forward-reference
           (rejection #(generic/verify! (assoc context :source-field-types {}) graph owner witness))))
    (is (= :unproved-generic-forward-reference
           (rejection #(generic/verify! (assoc-in context [:fields field-id :id] (random-uuid)) graph owner witness))))
    (doseq [transform [#(assoc % :type-origins {})
                       #(assoc-in % [:type-origins (:return-type-id witness) :namespace] "other")
                       #(-> % (assoc-in [:fields field-id :type-fn-id] (ids/primitive-fn-id :text))
                            (assoc-in [:source-field-types [field-id (ids/primitive-fn-id :text)]] 'a))
                       #(assoc-in % [:type-bodies (:return-type-id witness)] [:map :keyword 'unbound])
                       #(assoc-in % [:type-bodies (:return-type-id witness)] [:map :keyword [:fn {} :text]])]]
      (is (= :unproved-generic-forward-reference
             (rejection #(generic/verify! (transform context) graph owner witness)))))))


(deftest deferred-named-map-checks-require-the-exact-retained-read
  (let [{:keys [context graph owner witness address]} (proof-fixture)
        check {:id [address :value-type :field-read] :address address :scope (first address) :path (second address)
               :kind :value-type :trigger :field-read :expected-type [:map :keyword :any]}
        deferred (-> graph
                     (assoc-in [:scopes [] :proofs address] {:status :deferred :reason :runtime-type-obligation})
                     (assoc :obligations [check]))]
    (is (= witness (generic/verify! context deferred owner witness)))
    (doseq [transform [#(assoc % :obligations [])
                       #(assoc-in % [:obligations 0 :expected-type] [:map :text :any])
                       #(assoc-in % [:obligations 0 :kind] :container-type)
                       #(assoc-in % [:obligations 0 :trigger] :container-materialization)
                       #(assoc-in % [:obligations 0 :path] [(random-uuid)])
                       #(assoc-in % [:scopes [] :proofs address :reason] :missing-actual-type)]]
      (is (= :unproved-generic-forward-reference
             (rejection #(generic/verify! context (transform deferred) owner witness)))))))


(deftest another-named-return-with-the-same-body-is-not-the-source-origin
  (let [{:keys [context graph owner witness]} (proof-fixture)
        other-id (ids/fn-id "other" :named-map)
        changed (-> context
                    (assoc-in [:fns (:return-owner-id witness) :return-type-fn-id] other-id)
                    (assoc-in [:type-bodies other-id] [:map :keyword :any])
                    (assoc-in [:type-origins other-id] {:namespace "other" :name :named-map :map {:key :keyword :value :any}})
                    (assoc-in [:alias-ids :other/named-map] other-id))]
    (is (= :unproved-generic-forward-reference
           (rejection #(generic/verify! changed graph owner witness))))))
