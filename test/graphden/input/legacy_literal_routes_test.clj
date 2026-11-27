(ns graphden.input.legacy-literal-routes-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.input.legacy-literal-routes :as literal]
    [graphden.input.query-lookup-source :as query]
    [graphden.input.source-origins :as origins]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- definitions
  [path names]
  (let [{:keys [fns] module-ns :namespace} (edn/read-string (slurp (io/resource (str "packages/" path "/fns.edn"))))]
    (into [] (comp (filter #(contains? names (:name %))) (map #(assoc % :namespace module-ns))) fns)))


(deftest lexical-writer-shadows-only-the-string-query-reader
  (let [query-body {:parent :query-param :args {:request {:as :request} :param-name {:value "id"}}}
        route (assoc query-body :name :route :namespace "fixture")
        wrapper {:name :wrapper :namespace "fixture" :parent :const :args {:value query-body}}
        source (concat (definitions "core/collections" #{:get :get-in})
                       (definitions "core/logic" #{:coalesce :const})
                       (definitions "core/strings" #{:str-to-keyword})
                       (definitions "core/refinements" #{:path-segment :nullable-text :nullable-keyword :text-map})
                       (definitions "web/ring-adapter" #{:ring-request-shape})
                       (definitions "web/crud-delete" #{:_query-param-raw :_query-param-string-key :_query-param-keyword-key
                                                        :_query-param-fallback :query-param})
                       [route wrapper])
        prepared (parse/prepare-module (vec source) {} {})
        rows (group-by :kind (parse/parse-prepared-module prepared))
        graph (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                                      :bindings (:binding rows) :list-items (:binding-list-item rows)})
        owner (ids/fn-id "fixture" :route)
        child (:fn-id (origins/child-at (origins/index prepared [wrapper])
                                       (ids/fn-id "fixture" :wrapper) [:args :value]))
        proof (literal/descriptor graph owner :param-name)
        inline-proof (literal/descriptor graph child :param-name)
        written (ids/slot-id (ids/fn-id "web.crud" :_query-param-string-key) :param-name)
        selector-id (ids/slot-id (ids/fn-id "web.crud" :query-param) :param-name)]
    (let [witness (query/descriptor graph owner route)
          inline-witness (query/descriptor graph child query-body)
          parent (ids/fn-id "web.crud" :query-param)]
      (is (= (:binding-id proof) (:binding-id witness)))
      (is (= (:binding-id inline-proof) (:binding-id inline-witness)))
      (is (= (ids/fn-id "core.refinements" :nullable-text) (:return-type-id witness)))
      (is (= (:reads witness) (:reads inline-witness)))
      (is (= 3 (count (:reads witness))))
      (is (nil? (query/descriptor graph owner (assoc-in route [:args :param-name :value] "other"))))
      (is (nil? (query/descriptor (assoc-in graph [:fn-map parent :expects-effects] #{:storage/write}) owner route))))
    (is (= (ids/binding-id owner selector-id) (:binding-id proof)))
    (is (= "id" (:value proof)))
    (is (= #{:request} (:accepted proof)))
    (is (= [written] (:written proof)))
    (is (= 2 (count (:prefer-incoming proof))))
    (is (not-any? (:write-mask proof) (:prefer-incoming proof)))
    (is (= proof (assoc inline-proof :owner-id owner :binding-id (ids/binding-id owner selector-id))))
    (is (= (ids/binding-id child selector-id) (:binding-id inline-proof)))
    (is (nil? (literal/descriptor graph owner :request)))
    (doseq [[key value] [[:fn-id (random-uuid)] [:id (random-uuid)] [:ref-fn-id owner]
                         [:resolver-fn-id owner] [:list-closed true] [:value-present false]
                         [:type-override-fn-id (ids/primitive-fn-id :text)] [:required false]]]
      (is (nil? (literal/descriptor (assoc-in graph [:binding-by-fn-slot [owner selector-id] key] value)
                                    owner :param-name))))))
