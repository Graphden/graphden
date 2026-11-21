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


(def ^:private composed-module
  {:input-format 2 :namespace "fixture"
   :fns [{:name :subtract :input (array-map :left :int :right :int) :return-type :int}
         {:name :from-12 :parent :subtract :bind {:left [:value 12]}}]})


(def ^:private composed-root (ids/fn-id "fixture" :from-12))
(def ^:private composed-parent (ids/fn-id "fixture" :subtract))
(def ^:private visible #{composed-root composed-parent})


(deftest literal-root-has-the-legacy-binding-and-inherited-field-identities
  (let [original [{:namespace "fixture" :name :subtract
                   :args (array-map :left :int :right :int) :return-type :int}
                  {:namespace "fixture" :name :from-12 :parent :subtract :args {:left 12}}]
        prepared (parse/prepare-module original {} {})
        proof (second (:definitions (source/verify-module (source/plan-module original prepared) prepared)))
        actual (second (primitive/prepare-literal-root composed-module composed-root visible))
        identity-keys [:fn-id :input-type-id :contract :declaration-records :origin-ledger :bindings]
        slot (ids/slot-id composed-parent :left)]
    (is (= :authored-source-equivalent (:verification proof)))
    (is (= (select-keys (:v2 proof) identity-keys) (select-keys actual identity-keys)))
    (is (= (ids/binding-id composed-root slot) (get-in actual [:bindings 0 :id])))
    (is (= [slot] (get-in actual [:bindings 0 :target-path])))))


(deftest literal-root-rejects-other-parent-and-binding-shapes
  (is (= :staged-function-not-visible
         (code #(primitive/prepare-literal-root composed-module composed-root #{composed-root}))))
  (doseq [[patch diagnostic] [[{:parent :unknown} :staged-literal-parent-required]
                              [{:parents [:subtract]} :staged-literal-parent-unsupported]
                              [{:bind {:unowned [:value 12]}} :staged-literal-field-unknown]
                              [{:bind {:left [:ref :subtract]}} :staged-literal-binding-unsupported]
                              [{:bind {:left [:value [12]]}} :staged-literal-binding-unsupported]
                              [{:bind {:left [:value 12] :right [:value 1]}} :staged-literal-binding-unsupported]
                              [{:configure {[:left] {:type :int}}} :staged-literal-declaration-unsupported]]]
    (is (= diagnostic
           (code #(primitive/prepare-literal-root (update-in composed-module [:fns 1] merge patch)
                                                  composed-root visible))))))
