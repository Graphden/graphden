(ns graphden.input.source-forward-map-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.source :as source]
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
          result (try (snapshot/with-migrated-forwarding package owner [:payload] roots)
                      (catch clojure.lang.ExceptionInfo error
                        {:failure (ex-data error)
                         :observed {:substitution (:substitution checked)
                                    :configured (get-in checked [:scopes [] :configured-contract])
                                    :reference (select-keys (get-in checked [:scopes [] :bindings address]) [:expected-type :reference])
                                    :proof (get-in checked [:scopes [] :proofs address])
                                    :obligations (:obligations checked)}}))]
      (is (= :checked (:status checked)))
      (is (= :authored-source-equivalent (get-in result [:closure-baselines owner :verification]))
          (select-keys result [:failure :observed]))
      (is (not (contains? package :closure-baselines))))))
