(ns graphden.input.source-forward-map-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.signatures :as signatures]
    [graphden.input.source :as source]
    [graphden.input.source-forward-generic :as generic]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]))


(defn- shipped
  [module names]
  (let [{ns-id :namespace :keys [fns]} (edn/read-string (slurp (io/resource (str "packages/core/" module "/fns.edn"))))]
    (mapv #(assoc % :namespace ns-id) (filter #(names (:name %)) fns))))


(deftest actual-update-in-map-slot-requires-a-frozen-closed-reference-proof
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [originals (into (shipped "collections" #{:update-in})
                          (concat (shipped "refinements" #{:path-segment})
                                  [{:namespace "map-forward" :name :shape :type {:value :text}}
                                   {:namespace "map-forward" :name :producer :args {:payload :text} :return-type :shape}
                                   {:namespace "map-forward" :name :host :parent :update-in
                                    :args {:m :producer :payload {:type :text}}}]))
          prepared (parse/prepare-module originals {} {})
          package (snapshot/package-index prepared originals)
          owner (ids/fn-id "map-forward" :host)
          producer (ids/fn-id "map-forward" :producer)
          base (ids/fn-id "core.collections" :update-in)
          _ (sync/register-type-aliases! originals)
          _ (registry/record-rich-types! base :update-in (get-in (loader/load-packages ["core"]) [:base-fn-defs :update-in]))
          _ (registry/record-rich-types! producer :producer {:namespace "map-forward" :args {:payload {:type :text}}
                                                             :return-type {:value :text} :effects #{}})
          roots (:by-id @registry/*rich-types-override*)
          entry (first (:definitions (source/plan-module [(peek originals)] prepared)))
          baseline (source/forwarded-fields-baseline prepared entry [:payload])
          provisional (assoc-in package [:closure-baselines owner] (:entry baseline))
          {:keys [context options]} (snapshot/freeze-closure provisional [owner] roots)
          checked (analysis/analyze-effective-input context owner options)
          address [[owner []] [(ids/slot-id base :m)]]
          witness (:generic-reference (first (:proofs baseline)))
          result (try (snapshot/with-migrated-forwarding package owner [:payload] roots)
                      (catch clojure.lang.ExceptionInfo error
                        {:failure (ex-data error)
                         :observed {:substitution (:substitution checked)
                                    :configured (get-in checked [:scopes [] :configured-contract])
                                    :reference (select-keys (get-in checked [:scopes [] :bindings address]) [:expected-type :reference])
                                    :proof (get-in checked [:scopes [] :proofs address])
                                    :obligations (:obligations checked)}}))]
      (is (= :checked (:status checked)))
      (is (= :map (:kind witness)))
      (is (= witness (generic/verify! context checked owner witness)))
      (is (= :authored-source-equivalent (get-in result [:closure-baselines owner :verification]))
          (pr-str (select-keys result [:failure :observed])))
      (doseq [patch [{:base-fn-id (random-uuid)} {:field-id (random-uuid)}
                     {:argument :other} {:expected [:map 'other :any]}
                     {:binding-id (random-uuid)} {:target-fn-id (random-uuid)}
                     {:return-owner-id (random-uuid)} {:return-type-ref :other/shape}]]
        (is (= (if (contains? patch :field-id) :unknown-field :unproved-generic-map-reference)
               (try (generic/verify! context checked owner (merge witness patch))
                    nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
      (doseq [edit [#(assoc % :substitution {})
                    #(update % :dependencies into (:dependencies %))
                    #(assoc-in % [:scopes [] :signature-source :argument-paths :m] [(random-uuid)])
                    #(assoc-in % [:scopes [] :signature-source :root-entry :effects] #{:db})
                    #(assoc-in % [:scopes [] :proofs address] {:status :unchecked})
                    #(assoc-in % [:scopes [] :bindings address :reference :mode] :graph-callable)]]
        (is (= :unproved-generic-map-reference
               (try (generic/verify! context (edit checked) owner witness)
                    nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
      (doseq [edit [#(assoc % :source-field-types {})
                    #(assoc-in % [:fns (:return-owner-id witness) :return-type-fn-id] (random-uuid))]]
        (is (= :unproved-generic-map-reference
               (try (generic/verify! (edit context) checked owner witness)
                    nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
      (let [expected (get-in checked [:scopes [] :bindings address :expected-type])
            obligation {:id [address :value-type :field-read] :address address
                        :scope (first address) :path (second address)
                        :kind :value-type :trigger :field-read :expected-type expected}
            deferred (-> checked
                         (assoc-in [:scopes [] :proofs address] {:status :deferred :reason :runtime-type-obligation})
                         (assoc :obligations [obligation]))]
        (is (= witness (generic/verify! context deferred owner witness)))
        (doseq [edit [#(assoc % :obligations [])
                      #(assoc-in % [:obligations 0 :expected-type] :any)
                      #(assoc-in % [:obligations 0 :trigger] :container-materialization)]]
          (is (= :unproved-generic-map-reference
                 (try (generic/verify! context (edit deferred) owner witness)
                      nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))
      (is (not (contains? package :closure-baselines))))))


(deftest update-in-frozen-callbacks-preserve-path-null-and-default-behavior
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [original (get-in (loader/load-packages ["core"]) [:base-fn-defs :update-in])
          base (ids/fn-id "core.collections" :update-in)
          _ (sync/register-type-aliases! (shipped "refinements" #{:path-segment}))
          _ (registry/record-rich-types! base :update-in original)
          frozen (get-in @registry/*rich-types-override* [:by-id base])
          shape {:headers {:token :text} :body :text}
          facts [{:m {:type shape} :path {:value [{:value :headers} {:value :token}]}}
                 {:m {:type shape} :path {:value [{:ref :dynamic}]}}
                 {:m {:type :null} :path {:value nil}}
                 {:m {:type [:map :keyword :any]} :path {:value false}}
                 {:path {:value []}}]
          expected [[shape {:path [:list :keyword]} {:path shape}]
                    [shape {:path [:list :keyword]} {:path shape}]
                    [:null {} {}] [[:map :keyword :any] {} {}] [:text {} {}]]
          evaluate (fn [entry input]
                     [((:return-type-rule entry) input :text)
                      ((:slot-types-rule entry) input)
                      ((:nav-types-rule entry) input)])
          before (mapv #(evaluate original %) facts)
          after (context/call-with-types (context/build-context {}) #(mapv (partial evaluate frozen) facts))]
      (is (= expected before after))
      (is (= base (:fn-id frozen)))
      (is (signatures/local-type-rules? frozen))
      (doseq [rule [:return-type-rule :slot-types-rule :nav-types-rule]]
        (is (identical? (get original rule) (get-in frozen [:local-type-rule-evidence rule]))))
      (doseq [entry [original frozen]]
        (is (= :missing-field
               (try ((:return-type-rule entry) {:m {:type shape} :path {:value [{:value :missing}]}} :any)
                    nil (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))))


(deftest frozen-value-rules-use-only-the-checked-binding-facts
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [native (:base-fn-defs (loader/load-packages ["core"]))
          facts [["core.collections" :conj {:coll {:type [:list :text]} :item {:type :text}} [:list :text]]
                 ["core.collections" :dissoc {:map {:type {:x :int :y :text}} :key {:value :x}} {:y :text}]
                 ["core.collections" :keys {:map {:type [:map :text :int]}} [:list :text]]
                 ["core.collections" :update-vals
                  {:m {:type [:map :keyword :int]} :f {:type [:fn {:value :int} :text]}} [:map :keyword :text]]
                 ["core.strings" :name {:value {:type [:union :null :keyword]}} [:union :null :text]]]
          context (context/build-context {})]
      (doseq [[namespace name binding-facts expected] facts]
        (let [base (ids/fn-id namespace name)
              original (get native name)
              callback (:return-type-rule original)
              _ (registry/record-rich-types! base name original)
              frozen (get-in @registry/*rich-types-override* [:by-id base])]
          (is (signatures/local-type-rules? frozen) (str namespace "/" name))
          (is (identical? callback (get-in frozen [:local-type-rule-evidence :return-type-rule])))
          (is (= expected (callback binding-facts :any)
                 (context/call-with-types context #((:return-type-rule frozen) binding-facts :any))))
          (is (= (callback {} :any)
                 (context/call-with-types context #((:return-type-rule frozen) {} :any)))))))))
