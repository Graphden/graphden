(ns graphden.input.module-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.core :as input]
    [graphden.input.module :as module]
    [graphden.packages.records.ids :as ids]))


(defn- field-id
  [name]
  (ids/fn-id "fixture.fields" name))


(def ^:private contract
  {:type :fixture/input
   :fields [{:id (field-id :options) :name :options :type {:record {:flag :bool :data :jsonb}}
             :required false
             :fields [{:id (field-id :flag) :name :flag :type :bool}
                      {:id (field-id :data) :name :data :type :jsonb}]}
            {:id (field-id :value) :name :value :type :int}
            {:id (field-id :hof) :name :hof :type [:fn {:x :int} :int]}
            {:id (field-id :fnref) :name :fnref :type :fn-ref}]})


(defn- context-for
  [{:keys [namespace name type]}]
  (let [owner (ids/fn-id namespace name)
        input-id (ids/fn-id "fixture" :input)
        common {:fn-id owner :origin-ledger {:source-owner owner}}]
    (if type
      (assoc common :declaration-records [{:kind :fn :id owner :type type}])
      (merge common
             {:scope [owner []] :input-type-id input-id :contract contract
              :name->id {:leaf (ids/fn-id namespace :leaf)
                         :other/leaf (ids/fn-id "other" :leaf)}
              :binding-id (fn [scope path] (ids/uuid-v5 owner (pr-str [scope path])))
              :declaration-records [{:kind :fn :id owner :input-type-fn-id input-id}]}))))


(defn- source-module
  [definition]
  {:input-format 2 :namespace "fixture" :description "Module documentation"
   :fns [(merge {:name :main :parent :fixture/base} definition)]})


(defn- parse
  [source]
  (module/parse-module-v2 source {:context-for context-for}))


(defn- error-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))


(deftest partial-bindings-retain-rich-declarations-and-origins
  (let [declaration {:options {:type {:record {:flag :bool :data :jsonb}}
                               :required false :description "Options" :value {:flag true}}}
        source (source-module {:input declaration :description "Authored metadata"
                               :bind {:options {:flag false :data [:value nil]}
                                      :value [:ref :leaf] :hof [:ref :leaf] :fnref [:ref :leaf]}})
        parsed (parse source)
        definition (first (:definitions parsed))
        rows (:bindings definition)
        emitted (module/emit-module-v2 parsed {:context-for context-for})]
    (is (= declaration (get-in emitted [:fns 0 :input])))
    (is (= "Authored metadata" (get-in emitted [:fns 0 :description])))
    (is (= "Module documentation" (:description emitted)))
    (is (= 5 (count rows)))
    (is (= #{false nil} (set (map :value (filter :value-present rows)))))
    (is (= 3 (count (filter #(= "ref" (:expression-kind %)) rows)))
        "value/HOF/fn-ref leaves retain ordinary refs; reader never selects dispatch")
    (is (every? #(= (ids/fn-id "fixture" :leaf) (:ref-fn-id %))
                (filter #(= "ref" (:expression-kind %)) rows)))
    (is (= (:records parsed) (:records (parse emitted))))
    (is (= (:origin-ledger definition) (get-in (parse emitted) [:definitions 0 :origin-ledger])))
    (is (= (first (:fns source)) (:source definition)))))


(deftest literal-payloads-are-atomic-and-scalars-are-literal
  (doseq [payload [nil false {:ref :leaf :value 42 :configure {:x true}} [:ref :leaf]]]
    (let [parsed (parse (source-module {:bind [:value payload]}))
          row (first (get-in parsed [:definitions 0 :bindings]))]
      (is (= [] (:target-path row)))
      (is (true? (:value-present row)))
      (is (= payload (:value row)))
      (is (= [:value payload]
             (get-in (module/emit-module-v2 parsed {:context-for context-for}) [:fns 0 :bind])))))
  (let [row (first (get-in (parse (source-module {:bind {:value :leaf}})) [:definitions 0 :bindings]))]
    (is (= "value" (:expression-kind row)))
    (is (= :leaf (:value row)))))


(deftest declared-defaults-and-missing-required-input-do-not-create-bindings
  (let [source (source-module {:input {:value {:type :int :value 42 :required false}}})
        parsed (parse source)
        {:keys [contract binding-state fn-id bindings]} (first (:definitions parsed))]
    (is (empty? bindings))
    (is (seq (:missing (input/remaining-input contract binding-state [fn-id []]))))
    (is (= source (module/emit-module-v2 parsed {:context-for context-for}))))
  (let [parsed (parse (source-module {:bind {}}))]
    (is (empty? (get-in parsed [:definitions 0 :bindings])))))


(deftest type-only-definitions-preserve-source
  (let [source {:input-format 2 :fns [{:name :record :type {:x :int} :description "Rich type"}]}
        parsed (parse source)]
    (is (= source (module/emit-module-v2 parsed {:context-for context-for})))
    (is (= 1 (count (:records parsed))))
    (is (not (contains? (first (:definitions parsed)) :contract)))))


(deftest frozen-reference-context-is-namespace-specific
  (doseq [namespace ["left" "right" nil]]
    (let [source (assoc (source-module {:bind {:value [:ref :leaf] :hof [:ref :other/leaf]}})
                        :namespace namespace)
          rows (get-in (parse source) [:definitions 0 :bindings])]
      (is (= #{(ids/fn-id namespace :leaf) (ids/fn-id "other" :leaf)} (set (map :ref-fn-id rows))))))
  (is (= :invalid-input-context
         (error-code #(module/parse-module-v2 (source-module {})
                                              {:context-for (fn [definition]
                                                              (assoc (context-for definition)
                                                                     :name->id {:leaf :ambiguous}))}))))
  (is (= :unresolved-ref (error-code #(parse (source-module {:bind {:value [:ref :missing]}}))))))


(deftest reject-format-and-source-errors-before-context-callback
  (let [calls (atom 0)
        options {:context-for (fn [_] (swap! calls inc))}]
    (doseq [source [(dissoc (source-module {}) :input-format)
                    (assoc (source-module {}) :input-format 1)
                    (assoc (source-module {}) :input-format 3)
                    (source-module {:args {}})
                    (source-module {:configure {}})
                    (source-module {:namespace "wrong"})
                    {:input-format 2 :fns [{:name :x} {:name :x}]}
                    {:input-format 2 :fns [{:name :x :type {:a :int} :bind {}}]}]]
      (is (some? (error-code #(module/parse-module-v2 source options)))))
    (is (zero? @calls))))


(deftest reject-drifted-and-incomplete-contexts
  (doseq [[change expected]
          [[(constantly nil) :invalid-definition-context]
           [#(assoc % :fn-id (random-uuid)) :invalid-definition-context]
           [#(assoc % :scope [(random-uuid) []]) :invalid-definition-context]
           [#(assoc % :input-type-id (random-uuid)) :invalid-input-context]
           [#(assoc % :declaration-records []) :missing-owner-declaration]
           [#(assoc-in % [:declaration-records 0 :id] "not-uuid") :invalid-declaration-record]
           [#(assoc-in % [:contract :fields 0 :state] :free) :residual-contract-forbidden]
           [#(update-in % [:contract :fields] conj (first (:fields contract))) :duplicate-field]
           [#(assoc % :binding-id (constantly "not-uuid")) :invalid-owner]]]
    (is (= expected
           (error-code #(module/parse-module-v2 (source-module {:bind {:value 1}})
                                                {:context-for (comp change context-for)})))))
  (let [source (update (source-module {}) :fns conj {:name :late})
        original source]
    (is (= :invalid-definition-context
           (error-code #(module/parse-module-v2 source
                                                {:context-for (fn [definition]
                                                                (when-not (= :late (:name definition))
                                                                  (context-for definition)))}))))
    (is (= original source) "No mutation or partial result escapes a failed module")))


(deftest export-rechecks-origin-and-context-drift
  (let [parsed (parse (source-module {:bind {:value 1}}))]
    (is (= :export-origin-drift
           (error-code #(module/emit-module-v2 parsed
                                               {:context-for (fn [definition]
                                                               (assoc (context-for definition)
                                                                      :binding-id (fn [_ _] (random-uuid))))}))))
    (is (= :export-context-drift
           (error-code #(module/emit-module-v2 parsed
                                               {:context-for (fn [definition]
                                                               (assoc (context-for definition)
                                                                      :origin-ledger {:changed true}))}))))))


(deftest forward-refs-use-frozen-context-without-installing-earlier-definitions
  (let [source (update (source-module {:bind {:value [:ref :later]}}) :fns conj {:name :later :bind {}})
        calls (atom [])
        options {:context-for (fn [definition]
                                (swap! calls conj (:name definition))
                                (assoc-in (context-for definition) [:name->id :later]
                                          (ids/fn-id "fixture" :later)))}
        parsed (module/parse-module-v2 source options)]
    (is (= [:main :later] @calls))
    (is (= (ids/fn-id "fixture" :later)
           (get-in parsed [:definitions 0 :bindings 0 :ref-fn-id])))
    (is (empty? (get-in parsed [:definitions 1 :bindings])))))


(deftest reused-origin-identities-cannot-hide-distinct-bindings
  (let [origin (random-uuid)
        options {:context-for (fn [definition]
                                (assoc (context-for definition) :binding-id (fn [_ _] origin)))}]
    (is (= :binding-identity-conflict
           (error-code #(module/parse-module-v2 (source-module {:bind {:value 1 :hof 1}}) options))))
    (is (= :conflicting-record-identity
           (error-code #(module/parse-module-v2
                          (update (source-module {:bind {:value 1}}) :fns conj
                                  {:name :other :bind {:value 1}})
                          options))))))
