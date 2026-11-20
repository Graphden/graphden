(ns graphden.input.signatures-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.signatures :as signatures]))


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


(deftest abi-never-falls-back-to-a-matching-display-name-test
  (let [{:keys [scope graph]} (fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
          (signatures/binding-facts graph scope {:other [:other]})))
    (is (thrown? clojure.lang.ExceptionInfo
          (signatures/binding-facts graph scope {:other [(random-uuid)]})))))


(deftest signature-adapter-receives-frozen-scoped-facts-and-never-the-implementation-test
  (let [{:keys [scope text graph]} (fixture)
        base (random-uuid)
        target-scope [(first scope) [(random-uuid)]]
        graph (-> graph
                  (assoc :root-scope scope)
                  (assoc-in [:scopes [] :fn-id] (first scope))
                  (assoc-in [:scopes (second target-scope) :signature] {:return :int :effects #{}}))
        seen (atom nil)
        result (signatures/infer-use
                 {:fns {} :signatures {}} graph scope
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
    (is (not= 'a (get-in @seen [:root-entry :return])))
    (is (not (contains? @seen :impl)))
    (is (= 'a (get-in result [:signature-source :root-entry :return])))
    (is (not (contains? (:signature-source result) :impl)))))
