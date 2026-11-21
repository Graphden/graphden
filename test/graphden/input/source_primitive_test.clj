(ns graphden.input.source-primitive-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source :as source]
    [graphden.input.source-primitive :as primitive]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private module
  {:input-format 2 :namespace "fixture"
   :fns [{:name :rounding :input (array-map :value {:type :numeric :description "Number"}
                                            :label {:type :text :required false})
          :return-type :int}]})


(def ^:private root (ids/fn-id "fixture" :rounding))


(deftest primitive-preparation-retains-every-legacy-declaration-identity
  (let [authored (first (:fns module))
        original (-> authored
                     (assoc :namespace (:namespace module) :args (:input authored))
                     (dissoc :input))
        prepared (parse/prepare-module [original] {} {})
        proof (first (:definitions (source/verify-module (source/plan-module [original] prepared) prepared)))
        result (primitive/prepare module root)
        identity-keys [:fn-id :input-type-id :contract :declaration-records :origin-ledger]]
    (is (= :authored-source-equivalent (:verification proof)))
    (is (= (select-keys (:v2 proof) identity-keys) (select-keys result identity-keys)))
    (is (= (ids/slot-id root :label) (get-in result [:contract :fields 1 :id])))
    (is (false? (get-in result [:contract :fields 1 :required])))))


(defn- code
  [call]
  (try (call) nil (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest primitive-preparation-rejects-unproved-source-shapes
  (doseq [[change expected] [[{:parent :floor} :staged-primitive-inheritance-unsupported]
                             [{:bind {:value [:ref :other]}} :staged-primitive-bindings-unsupported]
                             [{:input {:value [:list :numeric]}} :staged-primitive-type-unsupported]
                             [{:return-type :other} :staged-primitive-type-unsupported]
                             [{:fn-id (random-uuid)} :staged-primitive-declaration-unsupported]]]
    (is (= expected (code #(primitive/prepare (update-in module [:fns 0] merge change) root)))))
  (is (= :staged-root-required (code #(primitive/prepare module (random-uuid)))))
  (is (= :staged-primitive-module-ledger
         (code #(primitive/prepare (update module :fns conj (first (:fns module))) root)))))
