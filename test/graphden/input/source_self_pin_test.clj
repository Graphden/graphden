(ns graphden.input.source-self-pin-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.compile :as compile]
    [graphden.input.rows :as rows]
    [graphden.input.source :as source]
    [graphden.input.source-package-fixtures :as fixtures]
    [graphden.input.source-rich-types :as rich]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.check :as check]
    [graphden.types.validator :as validator]))


(defn- self-pin-fixture
  []
  (let [originals (fixtures/declarations "packages/core/collections/fns.edn" #{:assoc :assoc-fn})
        prepared (parse/prepare-module originals {} {})
        source-plan (source/verify-module (source/plan-module originals prepared) prepared)
        entry (some #(when (= :assoc-fn (get-in % [:source :name])) %) (:definitions source-plan))
        owner (:identity entry)
        base (ids/fn-id "core.collections" :assoc)
        roots (binding [registry/*rich-types-override* (atom {:by-name {} :by-id {}})]
                (registry/record-rich-types! base :assoc (get @fixtures/web-base-definitions :assoc))
                (:by-id @registry/*rich-types-override*))
        package (snapshot/package-index prepared originals)
        {:keys [context]} (snapshot/freeze-declarations package [owner])]
    {:owner owner :base base :entry entry :roots roots :package package :prepared prepared :context context}))


(deftest generic-callable-self-pin-describes-a-free-inherited-field
  (let [{:keys [owner base entry roots package context prepared]} (self-pin-fixture)
        slot (ids/slot-id base :value)
        id (ids/binding-id owner slot)
        scheme (get-in entry [:source :args :value :type])
        [legacy] (filter #(= :binding (:kind %)) (get-in entry [:legacy :records]))
        [row] (get-in entry [:v2 :bindings])
        checked (analysis/analyze-input context owner {:check-expression check/check-input-binding})
        address [[owner []] [slot]]
        scoped-type (variables/type-in-scope [owner []] scheme)
        freeze-error (try (snapshot/freeze-closure package [owner] roots)
                          nil
                          (catch clojure.lang.ExceptionInfo error (ex-data error)))
        replay-code (fn [modify]
                      (-> (source/verify-module {:definitions [(update entry :proposed modify)]} prepared)
                          :definitions first :blockers first :code))]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {[:value] {:type scheme}} (get-in entry [:proposed :configure])))
    (is (not-any? #(contains? (:proposed entry) %) [:bind :input]))
    (is (= #uuid "22bac94f-e90d-5bdd-aa6b-609ff82ba1cf" id (:id legacy) (:id row)))
    (is (= [slot] [(:slot-id legacy)] (:target-path row)))
    (is (= #uuid "4aa1cd55-64e7-52dc-a6f8-38c7302660e8" (:type-override-fn-id legacy) (:type-override-fn-id row)))
    (is (= scheme (get-in context [:fns (:type-override-fn-id row) :constraint])))
    (is (empty? (rows/state [row])))
    (is (empty? (:bindings checked)))
    (is (= :deferred (get-in checked [:metadata-proofs id :status])))
    (is (= :runtime-type-obligation (get-in checked [:metadata-proofs id :reason])))
    (is (= {} (get-in checked [:metadata-proofs id :substitution])))
    (is (= :incomplete (:status checked)))
    (is (= [{:code :runtime-type-obligation :address address}] (:diagnostics checked)))
    (is (= scoped-type (:type (some #(when (= slot (:id %)) %) (get-in checked [:configured-contract :fields])))))
    (is (= {:type :input/source-snapshot :code :callable-self-pin-obligation-required :fn-id owner
            :binding-id id :target-path [slot] :type-override-fn-id (:type-override-fn-id row)}
           freeze-error))
    (is (= :types/check-failed
           (try (check/check-input-binding {:address address :expected scoped-type :actual :text
                                            :expression {:kind :value :value "wrong"} :substitution {}})
                nil
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
    (is (= :unverified-callable-self-pin (replay-code #(assoc-in % [:configure [:value] :type] '[:fn {:arg c} b]))))
    (is (= :self-pin-requires-free-field (replay-code #(assoc % :bind {:value [:value nil]}))))
    (let [config (get-in entry [:v2 :configurations [:value]])
          [field] (filter #(= slot (:id %)) (get-in entry [:v2 :contract :fields]))
          records (group-by :id (get-in entry [:v2 :declaration-records]))
          original (get-in context [:fns owner])]
      (is (rich/exact-self-pin? original field config records))
      (is (not (rich/exact-self-pin? original (assoc field :id (ids/slot-id owner :value)) config records)))
      (is (not (rich/exact-self-pin? original field (assoc-in config [:row :expression-kind] "value") records)))
      (is (not (rich/exact-self-pin? original field config (dissoc records (:type-override-fn-id row))))))))


(deftest effective-self-pin-preserves-the-field-read-and-rejects-an-external-callable
  (let [{:keys [owner base entry roots package]} (self-pin-fixture)
        address [[owner []] [(ids/slot-id base :value)]]
        scheme (variables/type-in-scope [owner []] (get-in entry [:source :args :value :type]))
        {:keys [context options]} (#'snapshot/freeze-source-analysis package [owner] roots)
        graph (analysis/analyze-effective-input context owner options)
        reads (filterv #(= address (:address %)) (:obligations graph))
        implementations (update-vals (:implementations options) #(assoc % :impl (:impl (get @fixtures/web-base-definitions :assoc))))
        compile-error (try (compile/compile-checked graph {:context context :implementations implementations})
                           nil
                           (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (is (= :checked (:status graph)))
    (is (= [[scheme :value-type :field-read]] (mapv (juxt :expected-type :kind :trigger) reads)))
    (is (every? (set (get-in graph [:scopes [] :obligations])) reads))
    (is (= {} (get-in graph [:scopes [] :metadata-proofs (get-in entry [:v2 :bindings 0 :id]) :substitution])))
    (is (= {:type :input/compile-rejected :code :supplied-callable-validation-required
            :address address :kind :value-type :trigger :field-read} compile-error))
    (is (= :types/unsupported-validator
           (try (validator/build scheme)
                nil
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))
