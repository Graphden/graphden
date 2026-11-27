(ns graphden.input.signatures-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.signatures :as signatures]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner record child text] (repeatedly 4 random-uuid)
        scope [owner []]
        contract {:type {:user {:name :text} :other :text}
                  :fields [{:id record :name :user :type {:name :text}
                            :fields [{:id child :name :name :type :text}]}
                           {:id text :name :other :type :text}]}]
    {:scope scope :record record :child child :text text
     :graph {:scopes {[] {:configured-contract contract :bindings {}}}}}))


(deftest audited-local-rule-is-bound-to-the-captured-callback
  (let [callback (fn [_ default] default)
        captured {:return-type-rule callback
                  :local-type-rule-evidence {:return-type-rule callback}}]
    (is (signatures/local-type-rules? captured))
    (is (not (signatures/local-type-rules? (assoc captured :return-type-rule (fn [_ default] default)))))
    (is (signatures/local-type-rules? {:return-type-rule nil}))))


(deftest literal-validator-rules-require-their-frozen-type-definition
  (let [rule (with-meta (fn [_ fallback] fallback) {:graphden.input/validated-type-argument :type})
        signature {:fn-id (random-uuid) :return-type-rule rule}
        prove (fn [aliases facts]
                (context/call-with-types {:aliases aliases}
                                         (fn []
                                           (try (#'signatures/check-rule-type! signature facts) :checked
                                                (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))
        given (fn [value] {:type {:value value :value-present true}})]
    (is (= :checked (prove {} (given [:map :text :text]))))
    (is (= :checked (prove {:tag :text} (given [:list :tag]))))
    (is (= :rule-type-source-required (prove {} (given [:list :tag]))))
    (is (= :checked (prove {} {:type {:type :any}})))
    (is (= :checked (prove {} (given :int))))))


(deftest abi-paths-read-partial-records-without-parsing-literal-data-test
  (let [{:keys [scope record child graph]} (fixture)
        value {:ref :ordinary-data}
        address [scope [record child]]
        graph (assoc-in graph [:scopes [] :bindings address]
                        {:address address :expression {:kind :value :value value}
                         :reference {:mode :literal :actual-type {:ref :keyword}}})
        facts (signatures/binding-facts graph scope {:payload [record] :renamed [record child]})]
    (is (= {:name {:ref :ordinary-data}} (get-in facts [:bindings-info :payload :value])))
    (is (= {:ref :ordinary-data} (get-in facts [:bindings-info :renamed :value])))
    (is (= {:name {:ref :keyword}} (get-in facts [:bindings-info :payload :type])))
    (is (= #{} (:free-arguments facts)))
    (is (not (contains? (get-in facts [:bindings-info :renamed]) :ref)))))


(deftest a-checked-literal-retains-its-field-refinement-in-rule-facts
  (let [{:keys [scope text graph]} (fixture)
        address [scope [text]]
        status [:refine :int [:and [:>= 100] [:<= 599]]]
        fact-type (fn [expected actual proof]
                    (let [entry {:address address :expression {:kind :value :value 401}
                                 :expected-type expected :reference {:mode :literal :actual-type actual}}
                          model (-> graph (assoc-in [:scopes [] :bindings address] entry)
                                    (assoc-in [:scopes [] :proofs address] proof))]
                      (get-in (signatures/binding-facts model scope {:status [text]}) [:bindings-info :status :type])))]
    (is (= status (fact-type status :int {:status :checked})))
    (is (= :int (fact-type status :int {:status :deferred})))
    (is (= :int (fact-type status :int nil)))
    (is (= :int (fact-type [:refine :numeric [:> 0]] :int {:status :checked})))
    (is (= [:secret :int] (fact-type status [:secret :int] {:status :checked})))))


(deftest optional-abi-argument-distinguishes-a-free-field-from-a-bound-nil
  (let [{:keys [scope text graph]} (fixture)
        address [scope [text]]
        graph (-> graph
                  (assoc-in [:scopes [] :configured-contract :type :other] :any)
                  (update-in [:scopes [] :configured-contract :fields 1] assoc :type :any :required false))
        paths {:default [text]}
        absent (signatures/binding-facts graph scope paths)
        explicit (signatures/binding-facts (assoc-in graph [:scopes [] :bindings address]
                                                     {:address address :expression {:kind :value :value nil}
                                                      :reference {:mode :literal :actual-type :null}})
                                           scope paths)]
    (is (= #{:default} (:free-arguments absent)))
    (is (= {:type :any :address address} (get-in absent [:bindings-info :default])))
    (is (empty? (:free-arguments explicit)))
    (is (= {:type :null :value nil :value-present true :address address} (get-in explicit [:bindings-info :default])))))


(deftest ancestor-whole-binding-and-lexical-routes-retain-free-provenance-test
  (let [{:keys [scope record child text graph]} (fixture)
        source [scope [record]]
        target [scope [text]]
        graph (assoc graph :input-routes [{:from target :source [scope [record child]]}])
        free (signatures/binding-facts graph scope {:argument [text]})
        bound (assoc-in graph [:scopes [] :bindings source]
                        {:address source :expression {:kind :value :value {:name "Ada"}}
                         :reference {:mode :literal :actual-type [:secret {:name :text}]}})
        literal (signatures/binding-facts bound scope {:argument [text]})]
    (is (= #{:argument} (:free-arguments free)))
    (is (= "Ada" (get-in literal [:bindings-info :argument :value])))
    (is (= [:secret :text] (get-in literal [:bindings-info :argument :type])))
    (is (= #{} (:free-arguments literal)))))


(deftest evaluated-references-retain-scoped-identity-and-effects-test
  (let [{:keys [scope text graph]} (fixture)
        edge (random-uuid)
        target [(first scope) [edge]]
        address [scope [text]]
        graph (-> graph
                  (assoc :dependencies [{:from address :to target}])
                  (assoc-in [:scopes [edge] :signature] {:return :text :effects #{:network}})
                  (assoc-in [:scopes [] :bindings address]
                            {:address address :expression {:kind :ref :fn-id (random-uuid)}
                             :reference {:mode :value :actual-type :text}}))
        facts (signatures/binding-facts graph scope {:argument [text]})]
    (is (= (signatures/use-id target) (get-in facts [:bindings-info :argument :ref])))
    (is (= #{:network} (get-in facts [:argument-effects :argument])))
    (is (not= (signatures/use-id target) (signatures/use-id [(first scope) [(random-uuid)]])))
    (is (thrown? clojure.lang.ExceptionInfo
          (signatures/binding-facts (update graph :scopes dissoc [edge]) scope {:argument [text]})))))


(deftest closed-generated-list-exposes-ordered-operand-facts
  (let [{:keys [scope text graph]} (fixture)
        edge (random-uuid)
        address [scope [text]]
        builder-path [:scopes [edge]]
        node {:signature-source {:generated-list {:operands [:item-0 :item-1]}}
              :signature {:return [:list :any] :effects #{}
                          :resolved-bindings {:item-0 {:type :keyword :value-present true :value :id}
                                              :item-1 {:type 'a :ref (random-uuid)}
                                              :tail {:type [:list :text]
                                                     :list-elements [{:type :text :value-present true :value "tail"}]}}}}
        graph (-> graph
                  (assoc :dependencies [{:from address :to [(first scope) [edge]]}])
                  (assoc-in builder-path node)
                  (assoc-in [:scopes [] :bindings address]
                            {:address address :expected-type [:list :any]
                             :expression {:kind :ref :fn-id (random-uuid)}
                             :reference {:mode :value}}))
        read-argument #(get-in (signatures/binding-facts % scope {:items [text]}) [:bindings-info :items])
        fact (read-argument graph)
        signature (variables/resolve-signature {'a :bool} {:resolved-bindings {:items fact}})]
    (is (= [{:type :keyword :value-present true :value :id}
            {:type 'a}
            {:type :text :value-present true :value "tail"}]
           (:list-elements fact)))
    (is (nil? (:value-present fact)))
    (is (= [:keyword :bool :text] (mapv :type (get-in signature [:resolved-bindings :items :list-elements]))))
    (is (nil? (:list-elements (read-argument (update-in graph (into builder-path [:signature :resolved-bindings :tail])
                                                        dissoc :list-elements)))))))


(deftest abi-never-falls-back-to-a-matching-display-name-test
  (let [{:keys [scope graph]} (fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
          (signatures/binding-facts graph scope {:other [:other]})))
    (is (thrown? clojure.lang.ExceptionInfo
          (signatures/binding-facts graph scope {:other [(random-uuid)]})))))


(deftest signature-adapter-receives-frozen-scoped-facts-and-never-the-implementation-test
  (let [{:keys [scope text graph]} (fixture)
        base (random-uuid)
        any-id (ids/primitive-fn-id :any)
        target-scope [(first scope) [(random-uuid)]]
        graph (-> graph
                  (assoc :root-scope scope)
                  (assoc-in [:scopes [] :fn-id] (first scope))
                  (assoc-in [:scopes (second target-scope) :signature] {:return :int :effects #{}}))
        seen (atom nil)
        result (signatures/infer-use
                 {:fns {(first scope) {:id (first scope) :return-type-fn-id any-id}}
                  :source-return-types {[(first scope) any-id] 'a} :signatures {}} graph scope
                 {:base-fn-id base :root-entry {:name :identity :args {:value 'a} :return 'a}
                  :argument-paths {:value [text]}
                  :impl (fn [& _] (throw (ex-info "must not execute" {})))}
                 (fn [facts]
                   (reset! seen facts)
                   {:return :text :effects #{} :substitution {} :argument-proofs {}}))]
    (is (= :text (get-in result [:signature :return])))
    (is (= [scope [text]] (get-in @seen [:bindings-info :value :address])))
    (is (= :int (get-in @seen [:signature-view :by-id (signatures/use-id target-scope) :return])))
    (is (= base (get-in @seen [:signature-view :by-name :identity])))
    (is (= (get-in @seen [:root-entry :args :value]) (get-in @seen [:root-entry :return])))
    (is (= (get-in @seen [:root-entry :return]) (get-in @seen [:declaration :return-type])))
    (is (not= 'a (get-in @seen [:root-entry :return])))
    (is (not (contains? @seen :impl)))
    (is (= 'a (get-in result [:signature-source :root-entry :return])))
    (is (not (contains? (:signature-source result) :impl)))))


(deftest frozen-field-identity-connects-declaration-and-abi-binders
  (let [[owner base field other use-id] (repeatedly 5 random-uuid)
        rich '[:union :null [:map a :any]]
        declaration {:id field :name :coll :type rich}
        implementation {:base-fn-id base :root-entry {:args {:coll rich} :return 'a}
                        :argument-paths {:coll [field]} :argument-declarations {:coll declaration}}
        infer (fn [scope adjust implementation]
                (let [contract (variables/descriptor-in-scope scope
                                                              {:type {:coll rich} :fields [declaration]})
                      graph {:root-scope scope
                             :scopes {(second scope) (adjust {:fn-id owner :declared-contract {:type {:coll rich} :fields [declaration]}
                                                              :configured-contract contract :bindings {}})}}
                      seen (atom nil)]
                  (signatures/infer-use
                    {:fns {owner {:id owner :return-type-fn-id (ids/primitive-fn-id :any)}}
                     :source-return-types {[owner (ids/primitive-fn-id :any)] 'a}}
                    graph scope implementation
                    (fn [facts]
                      (reset! seen facts)
                      {:return (get-in facts [:root-entry :return]) :effects #{}
                       :substitution {} :argument-proofs {}}))
                  @seen))
        scope [owner []]
        linked (infer scope identity implementation)
        separate (infer [owner [use-id]] identity implementation)
        source-variable (variables/type-in-scope scope 'a)]
    (is (= source-variable (get-in linked [:root-entry :return])
           (get-in linked [:declaration :return-type])))
    (is (= (get-in linked [:bindings-info :coll :type]) (get-in linked [:root-entry :args :coll])))
    (is (not= (get-in linked [:root-entry :return]) (get-in separate [:root-entry :return])))
    (doseq [unproved [(dissoc implementation :argument-declarations)
                      (assoc-in implementation [:argument-declarations :coll :id] other)
                      (assoc-in implementation [:argument-declarations :coll :type] '[:list a])
                      (assoc-in implementation [:argument-declarations :coll :type] '[:union :null [:map b :any]])]]
      (is (not= source-variable (get-in (infer scope identity unproved) [:root-entry :return]))))
    (let [narrowed (infer scope #(-> % (assoc-in [:configured-contract :type :coll] :any)
                                     (assoc-in [:configured-contract :fields 0 :type] :any)) implementation)]
      (is (not= source-variable (get-in narrowed [:root-entry :return]))))))


(deftest projected-lookups-instantiate-only-their-private-map-key
  (let [base (ids/fn-id "core.collections" :get)
        owner (random-uuid)
        scope [owner []]
        abi [base [owner]]
        field (ids/slot-id base :coll)
        address [scope [field]]
        variable (variables/type-in-scope abi 'a)
        domain [:union :null [:list :any] [:map variable :any]]
        root {:fn-id base :name :get :namespace "core.collections" :return :any :args {:key :any :default :any :coll domain}}
        record {:id :uuid}
        facts (fn [rich] {:bindings-info {:coll {:type rich :address address}}})
        node (fn [rich] {:bindings {address {:expected-type rich :reference {:mode :projection :actual-type rich}}}})
        specialize (fn [entry n renaming f] (#'signatures/projected-lookup-signature entry n scope abi renaming f))]
    (doseq [[rich key-type] [[record :any] [[:map :text :int] :text]]]
      (is (= #{:null [:list :any] [:map key-type :any]}
             (set (rest (get-in (specialize root (node rich) {} (facts rich)) [:args :coll]))))))
    (doseq [[entry n renaming f] [[(assoc root :fn-id (random-uuid)) (node record) {} (facts record)]
                                  [(assoc-in root [:args :key] variable) (node record) {} (facts record)]
                                  [root (node record) {variable 'caller-key} (facts record)]
                                  [root (assoc-in (node record) [:bindings address :reference :mode] :value) {} (facts record)]
                                  [root (node record) {} (facts {:different :text})]
                                  [root (node '{:id k}) {} (facts '{:id k})]]]
      (is (= entry (specialize entry n renaming f))))))
