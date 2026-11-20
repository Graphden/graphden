(ns graphden.input.package-execution-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.input.package-execution :as execution]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(use-fixtures :each
  (fn [run]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (run))))


(defn- declarations
  [resource names]
  (let [{:keys [fns] module-ns :namespace} (edn/read-string (slurp (io/resource resource)))]
    (into [] (comp (filter #(contains? names (:name %)))
                   (map #(assoc % :namespace module-ns))) fns)))


(def ^:private loaded (delay (:base-fn-defs (loader/load-packages ["core"]))))


(defn- runtimes
  [source root]
  (let [prepared (parse/prepare-module source {} {})
        records (group-by :kind (parse/parse-prepared-module prepared))
        graph {:fns (:fn records) :slots (:slot records) :fn-slots (:fn-slot records)
               :bindings (:binding records) :list-items (:binding-list-item records)}
        base-declarations (filter #(contains? @loaded (:name %)) source)
        _ (doseq [{:keys [name] module-ns :namespace} base-declarations]
            (registry/record-rich-types! (ids/fn-id module-ns name) name (get @loaded name)))
        roots (:by-id @registry/*rich-types-override*)
        implementations (into {} (map (fn [{:keys [name] module-ns :namespace}]
                                        [(ids/fn-id module-ns name) (:impl (get @loaded name))])) base-declarations)
        canonical (execution/compile-package (snapshot/package-index prepared source)
                                             root roots implementations)
        legacy (eager/compile-all (assoc (lookups/build-lookups graph)
                                         :base-fns (registry/compute-base-fns-map @loaded)))
        invoke (fn [compiled args]
                 (executor/execute {:compiled-registry (atom compiled)
                                    :graph-cache (atom graph) :base-fns {}}
                                   root args))]
    {:analysis (:analysis canonical)
     :canonical #(invoke {root (:invoke canonical)} %)
     :legacy #(invoke legacy %)}))


(defn- rejection-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest prepared-package-rename-executes-through-both-compilers
  (let [source (into (declarations "packages/core/strings/fns.edn" #{:str-trim})
                     (declarations "packages/web/crud-parse/fns.edn" #{:_parse-fn-form-pid-trim-item}))
        {:keys [canonical legacy analysis]}
        (runtimes source (ids/fn-id "web.crud" :_parse-fn-form-pid-trim-item))]
    (is (= :checked (:status analysis)))
    (doseq [[input expected] [["  shipped  " "shipped"] ["\nsecond\t" "second"] ["" ""]]]
      (is (= expected (legacy {:item input}) (canonical {:item input}))))
    (is (= :unknown-input-field (rejection-code #(canonical {:string "hidden"}))))))


(deftest prepared-package-reference-routes-named-inputs-through-both-compilers
  (let [name :_csv-upper-test
        source (conj (declarations "packages/core/strings/fns.edn" #{:_csv-escape-cell :str-upper})
                     {:name name :namespace "fixture" :parent :_csv-escape-cell
                      :args {:value :str-upper}})
        {:keys [canonical legacy analysis]} (runtimes source (ids/fn-id "fixture" name))]
    (is (= :input-field (get-in analysis [:public-input :routes [:value :string] :kind])))
    (doseq [[input expected] [["graph" "GRAPH"] ["graph,input" "\"GRAPH,INPUT\""]]]
      (is (= expected (legacy {:string input}) (canonical {:value {:string input}}))))))


(deftest prepared-web-state-requires-a-compile-time-value-lowering
  (let [source (into (declarations "packages/core/concurrency/fns.edn" #{:cell :deref})
                     (declarations "packages/web/http/fns.edn" #{:response-cache-cell :_response-cache-current}))]
    (is (= :compile-time-value-compilation-required
           (rejection-code #(runtimes source (ids/fn-id "web.http" :_response-cache-current)))))))
