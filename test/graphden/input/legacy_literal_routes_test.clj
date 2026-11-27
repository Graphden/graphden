(ns graphden.input.legacy-literal-routes-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
    [graphden.input.legacy-literal-routes :as literal]
    [graphden.input.package-execution :as execution]
    [graphden.input.query-lookup-inline :as inline]
    [graphden.input.query-lookup-source :as query]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]))


(defn- definitions
  [path names]
  (let [{:keys [fns] module-ns :namespace} (edn/read-string (slurp (io/resource (str "packages/" path "/fns.edn"))))]
    (into [] (comp (filter #(contains? names (:name %))) (map #(assoc % :namespace module-ns))) fns)))


(defn- query-definitions
  [extra]
  (into []
        cat
        [(definitions "core/collections" #{:get :get-in})
         (definitions "core/logic" #{:coalesce :const :equal?})
         (definitions "core/strings" #{:str-to-keyword})
         (definitions "core/refinements" #{:path-segment :nullable-text :nullable-keyword :text-map})
         (definitions "web/ring-adapter" #{:ring-request-shape})
         (definitions "web/crud-delete" #{:_query-param-raw :_query-param-string-key :_query-param-keyword-key
                                          :_query-param-fallback :query-param})
         extra]))


(defn- legacy-rows
  [prepared]
  (let [rows (group-by :kind (parse/parse-prepared-module prepared))]
    {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
     :bindings (:binding rows) :list-items (:binding-list-item rows)}))


(deftest lexical-writer-shadows-only-the-string-query-reader
  (let [query-body {:parent :query-param :args {:request {:as :request} :param-name {:value "id"}}}
        route (assoc query-body :name :route :namespace "fixture")
        wrapper {:name :wrapper :namespace "fixture" :parent :const :args {:value query-body}}
        source (query-definitions [route wrapper])
        prepared (parse/prepare-module source {} {})
        graph (lookups/build-lookups (legacy-rows prepared))
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


(deftest expanded-inline-query-retains-the-original-bound-reference
  (let [body {:parent :query-param :args {:request {:as :request} :param-name {:value "id"}}}
        wrapper {:name :has-id? :namespace "fixture" :parent :equal? :args {:a body :b {:value "match"}}}
        source (query-definitions [wrapper])
        owner (ids/fn-id "fixture" :has-id?)
        prepared (parse/prepare-module source {} {})
        rows (legacy-rows prepared)
        graph (lookups/build-lookups rows)
        package (assoc (snapshot/package-index prepared source) :legacy-graph graph)
        child (origins/child-at @(:inline-origins package) owner [:args :a])]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              types/*type-aliases-override* (atom {})]
      (let [{:keys [fn-defs base-fn-defs]} (loader/load-packages ["web"])
            _ (sync/register-type-aliases! fn-defs)
            _ (doseq [[name {module-ns :namespace :as base}] base-fn-defs]
                (registry/record-rich-types! (ids/fn-id module-ns name) name base))
            signatures (:by-id @registry/*rich-types-override*)
            old (eager/compile-all (assoc graph :base-fns (registry/compute-base-fns-map base-fn-defs)))
            old-context {:compiled-registry (atom old) :graph-cache (atom rows) :base-fns {}}
            {:keys [roots entry] :as migrated} (inline/prepare package owner signatures)
            implementations (into {} (map (fn [[name {module-ns :namespace impl :impl}]]
                                            [(ids/fn-id module-ns name) impl])) base-fn-defs)
            runtime (execution/compile-package (:package migrated) owner roots implementations)
            request {:request-method :get :uri "/fixture" :query-string "" :headers {} :body nil :async-channel nil}]
        (is (= [(:fn-id child)] (vec (keep :ref-fn-id (get-in entry [:v2 :bindings])))))
        (is (= :unknown-input-field
               (try ((:invoke runtime) {:request {}} {})
                    (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
        (doseq [[fields expected] [[{} false]
                                   [{:query-params {"id" 17}} false]
                                   [{:query-params {"id" "match"}} true]
                                   [{:query-string "id=match"} true]]]
          (let [request (merge request fields)]
            (is (= expected (executor/execute old-context owner {:request request})
                   ((:invoke runtime) {:a {:request request}} {})))))))))
