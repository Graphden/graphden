(ns graphden.input.source-named-types-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.source :as source]
    [graphden.input.source-named-types :as named-types]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]))


(defn- declarations
  [file names]
  (let [{module-ns :namespace :keys [fns]} (edn/read-string (slurp (io/resource file)))]
    (into [] (comp (filter #(names (:name %))) (map #(assoc % :namespace module-ns))) fns)))


(defn- ring-types
  []
  (let [definitions (into (declarations "packages/core/refinements/fns.edn" #{:http-status :text-map})
                          (declarations "packages/core/system/fns.edn" #{:ring-response-shape}))
        prepared (parse/prepare-module definitions {} {})
        plan (source/verify-module (source/plan-module definitions prepared) prepared)]
    [{:prepared prepared :declarations (into {} (map (juxt :fn-id identity)) (:fn-defs prepared))}
     (into {} (map (juxt :identity identity)) (:definitions plan))]))


(deftest shipped-ring-record-freezes-its-nested-named-types
  (let [[package entries] (ring-types)
        roots {:root {:source {:return-type :ring-response-shape}}}
        {:keys [aliases alias-ids] :as frozen} (named-types/evidence roots (named-types/lookup package entries))
        types (context/call-with-types (context/build-context frozen) #(types/resolve-alias :ring-response-shape))
        status-id (ids/fn-id "core.refinements" :http-status)
        shape-id (ids/fn-id "core.system" :ring-response-shape)]
    (is (= #{:ring-response-shape :http-status :text-map} (set (keys aliases))))
    (is (= status-id (:http-status alias-ids)))
    (is (= shape-id (:ring-response-shape alias-ids)))
    (is (= {:status [:refine :int [:and [:>= 100] [:<= 599]]] :headers [:map :text :text] :body :any} types))
    (let [changed (assoc-in entries [status-id :v2 :declaration-records 0 :constraint] [:>= 0])]
      (is (thrown? clojure.lang.ExceptionInfo (named-types/evidence roots (named-types/lookup package changed)))))))
