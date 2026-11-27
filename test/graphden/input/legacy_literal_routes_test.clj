(ns graphden.input.legacy-literal-routes-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.input.legacy-literal-routes :as literal]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- definitions
  [path names]
  (let [{:keys [namespace fns]} (edn/read-string (slurp (io/resource (str "packages/" path "/fns.edn"))))]
    (into [] (comp (filter #(contains? names (:name %))) (map #(assoc % :namespace namespace))) fns)))


(deftest lexical-writer-shadows-only-the-string-query-reader
  (let [source (concat (definitions "core/collections" #{:get :get-in :path-segment})
                       (definitions "core/logic" #{:coalesce})
                       (definitions "core/strings" #{:str-to-keyword})
                       (definitions "core/refinements" #{:nullable-text :nullable-keyword :text-map})
                       (definitions "web/ring-adapter" #{:ring-request-shape})
                       (definitions "web/crud-delete" #{:_query-param-raw :_query-param-string-key :_query-param-keyword-key
                                                        :_query-param-fallback :query-param})
                       [{:name :route :namespace "fixture" :parent :query-param
                         :args {:request {:as :request} :param-name {:value "id"}}}])
        rows (group-by :kind (parse/parse-prepared-module (parse/prepare-module (vec source) {} {})))
        graph (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                                      :bindings (:binding rows) :list-items (:binding-list-item rows)})
        owner (ids/fn-id "fixture" :route)
        proof (literal/descriptor graph owner :param-name)
        written (ids/slot-id (ids/fn-id "web.crud" :_query-param-string-key) :param-name)
        selector (ids/slot-id (ids/fn-id "web.crud" :query-param) :param-name)]
    (is (= (ids/binding-id owner selector) (:binding-id proof)))
    (is (= "id" (:value proof)))
    (is (= #{:request} (:accepted proof)))
    (is (= [written] (:written proof)))
    (is (= 2 (count (:prefer-incoming proof))))
    (is (not-any? (:write-mask proof) (:prefer-incoming proof)))
    (is (nil? (literal/descriptor graph owner :request)))
    (doseq [[key value] [[:fn-id (random-uuid)] [:id (random-uuid)] [:ref-fn-id owner]
                         [:resolver-fn-id owner] [:list-closed true] [:value-present false]]]
      (is (nil? (literal/descriptor (assoc-in graph [:binding-by-fn-slot [owner selector] key] value)
                                    owner :param-name))))))
