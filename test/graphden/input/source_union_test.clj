(ns graphden.input.source-union-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.source-package-fixtures :as fixtures]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(def ^:private originals
  (vec (concat (fixtures/declarations "packages/core/refinements/fns.edn" #{:nullable-text :keyword-or-text})
               (fixtures/declarations "packages/core/strings/fns.edn" #{:str-contains?})
               (fixtures/declarations "packages/web/html/fns.edn" #{:hiccup-node :hiccup :render-hiccup}))))


(def ^:private roots
  (delay
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              types/*type-aliases-override* (atom {})
              shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
      (let [loaded @fixtures/web-base-definitions]
        (sync/register-type-aliases! originals)
        (doseq [{:keys [name] module-ns :namespace} originals :when (get loaded name)]
          (registry/record-rich-types! (ids/fn-id module-ns name) name (get loaded name)))
        (:by-id @registry/*rich-types-override*)))))


(defn- freeze
  [defs owner]
  (let [package (snapshot/package-index (parse/prepare-module defs {} {}) defs)]
    (snapshot/freeze-closure package [owner] @roots)))


(deftest frozen-unions-keep-declaration-and-member-order
  (doseq [[ns-name name input owner members]
          [["core.refinements" :nullable-text :string (ids/fn-id "core.strings" :str-contains?) [:null :text]]
           ["web.html" :hiccup-node :hiccup (ids/fn-id "web.html" :render-hiccup) [:null :text :int :float :bool [:list :any]]]]]
    (let [id (ids/fn-id ns-name name)
          {:keys [context options]} (freeze originals owner)
          desc (context/call-with-types context #(descriptor/resolve-descriptor context id))
          graph (analysis/analyze-effective-input context owner options)
          field (some #(when (= input (:name %)) %) (get-in graph [:scopes [] :configured-contract :fields]))]
      (is (= id (get-in context [:alias-ids name]) (:type-id field)))
      (is (= (into [:union] members) (get-in context [:fns id :constraint]) (get-in context [:aliases name])))
      (is (= members (get-in context [:type-origins id :union])))
      (is (= (types/make-union members) (:type desc) (:type field)))
      (is (= (set members) (set (:alternatives desc))))
      (let [check (:check-expression options)
            actual (check {:expected (:type field) :actual :any :address [[owner []] [(:id field)]]
                           :expression {:kind :ref :fn-id owner} :substitution {}})]
        (is (= :deferred (:status actual)))
        (is (= :runtime-type-obligation (:reason actual))))
      (is (= ns-name (get-in context [:type-origins id :namespace])))
      (is (string? (get-in context [:type-origins id :source-file]))))))


(deftest union-list-operands-are-checked-as-the-named-declaration
  (let [host {:namespace "union-test" :name :node :parent :web.html/hiccup
              :args {:tag "div" :children [{:value "text"} {:value [:em "child"]}]}}
        id (ids/fn-id "union-test" :node)
        analyze (fn [d]
                  (let [{:keys [context options]} (freeze (conj originals d) id)]
                    (analysis/analyze-effective-input context id options)))
        valid (analyze host)
        target (ids/slot-id (ids/fn-id "web.html" :hiccup) :children)
        {:keys [binding-id builder-id]} (list-builder/origin id target)
        item (some #(when (= :item-0 (:name %)) %) (get-in valid [:scopes [binding-id] :configured-contract :fields]))
        union (types/make-union [:null :text :int :float :bool [:list :any]])]
    (is (= :checked (:status valid)) (pr-str (:diagnostics valid)))
    (is (= (ids/slot-id builder-id :item-0) (:id item)))
    (is (= (ids/fn-id "web.html" :hiccup-node) (:type-id item)))
    (is (= union (:type item) (get-in valid [:scopes [] :signature :return])))
    (is (= [:list union] (get-in valid [:scopes [binding-id] :signature :return])))
    (doseq [other [{:unknown true} :unknown-node]]
      (let [failure (try (analyze (assoc-in host [:args :children 0 :value] other)) nil
                         (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (= :types/check-failed (:type failure)))
        (is (= :named-union-mismatch (:reason failure)))))))


(deftest original-union-order-and-uuid-must-match-the-prepared-row
  (let [package (snapshot/package-index (parse/prepare-module originals {} {}) originals)
        owner (ids/fn-id "web.html" :render-hiccup)
        type-id (ids/fn-id "web.html" :hiccup-node)
        swapped (update-in package [:originals type-id 0 :union] #(vec (reverse %)))
        other (ids/fn-id "other" :hiccup-node)
        forged (-> package (assoc-in [:prepared :name->id :hiccup-node] other)
                   (assoc-in [:declarations other] (get-in package [:declarations type-id]))
                   (assoc-in [:originals other] (get-in package [:originals type-id])))]
    (is (thrown? clojure.lang.ExceptionInfo (snapshot/freeze-closure swapped [owner] @roots)))
    (is (thrown? clojure.lang.ExceptionInfo (snapshot/freeze-closure forged [owner] @roots)))))
