(ns graphden.input.source-owner-proof-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.source :as source]
    [graphden.input.source-certification :as certification]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(use-fixtures :each
  (fn [run]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (run))))


(defn- declarations
  [module names]
  (let [{ns-id :namespace :keys [fns]} (edn/read-string (slurp (io/resource (str "packages/core/" module "/fns.edn"))))]
    (mapv #(assoc % :namespace ns-id) (filter #(contains? names (:name %)) fns))))


(def ^:private implementations (delay (:base-fn-defs (loader/load-packages ["core"]))))


(defn- fixture
  []
  (let [base (into (declarations "strings" #{:_csv-escape-cell})
                   (concat (declarations "strings" #{:str-trim})
                           (declarations "system" #{:to-json-string})))
        a {:namespace "owner-proof" :name :a :parent :_csv-escape-cell
           :args {:value :to-json-string :data {:type :jsonb}}}
        b {:namespace "owner-proof" :name :b :parent :a
           :args {:data {:value nil} :extra {:type :text}}}
        c {:namespace "owner-proof" :name :c :parent :b
           :args {:extra :str-trim :string " null "}}
        originals (into base [a b c])
        prepared (parse/prepare-module originals {} {})]
    (doseq [{ns-id :namespace :keys [name]} base]
      (registry/record-rich-types! (ids/fn-id ns-id name) name (get @implementations name)))
    {:package (snapshot/package-index prepared originals) :originals originals
     :roots (:by-id @registry/*rich-types-override*) :owner (ids/fn-id "owner-proof" :c)}))


(defn- failure-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (or (:code (ex-data error)) (:type (ex-data error))))))


(deftest three-owner-proof-transaction-replans-inherited-and-scoped-inputs
  (let [{:keys [package originals roots owner]} (fixture)
        initial (source/plan-module originals (:prepared package))
        result (snapshot/with-certified-owner package owner roots)
        c (get-in result [:closure-baselines owner])
        all (certification/verify-plan (:prepared package) originals {} roots)]
    (is (= [[:field-projection-required] [:inherited-projection-required]
            [:ref-scope-required :inherited-projection-required]]
           (mapv #(mapv :code (:blockers %)) (take-last 3 (:definitions initial)))))
    (is (= :authored-source-equivalent (:verification c)) (:blockers c))
    (is (= [:value " null "] (get-in c [:proposed :bind [:extra :string]])))
    (is (= [:value nil] (get-in result [:closure-baselines (ids/fn-id "owner-proof" :b) :proposed :bind :data])))
    (is (every? #(= :authored-source-equivalent (:verification %)) (get-in all [:plan :definitions])))
    (is (not (contains? package :closure-baselines)))
    (let [reordered (parse/prepare-module (vec (reverse originals)) {} {})
          other (snapshot/with-certified-owner (snapshot/package-index reordered (reverse originals)) owner roots)]
      (is (= (select-keys (:v2 c) [:fn-id :input-type-id :declaration-records :bindings])
             (select-keys (get-in other [:closure-baselines owner :v2])
                          [:fn-id :input-type-id :declaration-records :bindings]))))))


(deftest inherited-occupancy-is-not-cleared-by-a-successful-parent-proof
  (let [{:keys [originals roots owner]} (fixture)
        changed (update originals (dec (count originals)) assoc-in [:args :data] {:value false})
        package (snapshot/package-index (parse/prepare-module changed {} {}) changed)]
    (is (= :unsupported-owner-certificate
           (failure-code #(snapshot/with-certified-owner package owner roots))))
    (is (not (contains? package :closure-baselines)))))


(deftest failed-leaf-check-does-not-publish-successful-ancestor-certificates
  (let [{:keys [originals roots owner]} (fixture)
        changed (update originals (dec (count originals)) assoc-in [:args :string] 42)
        prepared (parse/prepare-module changed {} {})
        package (snapshot/package-index prepared changed)
        before (source/plan-module changed (:prepared package))]
    (is (= :types/check-failed
           (failure-code #(snapshot/with-certified-owner package owner roots))))
    (is (not (contains? package :closure-baselines)))
    (is (= before (source/plan-module changed (:prepared package))))))


(deftest joint-proof-completion-is-independent-of-owner-uuid-queue-order
  (let [{:keys [originals roots]} (fixture)
        changed (update originals (dec (count originals)) assoc :name :early)
        prepared (parse/prepare-module changed {} {})
        owner (ids/fn-id "owner-proof" :early)
        parent (ids/fn-id "owner-proof" :a)
        result (certification/verify-plan prepared changed {} roots)]
    (is (neg? (compare owner parent)))
    (is (empty? (:blocked result)))
    (is (= #{owner parent (ids/fn-id "owner-proof" :b)} (:certified result)))
    (is (every? #(= :authored-source-equivalent (:verification %)) (get-in result [:plan :definitions])))))
