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
    [graphden.input.source :as source]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse])
  (:import
    (java.nio.charset
      StandardCharsets)))


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
  ([source root] (runtimes source root execution/compile-package))
  ([source root compile-package]
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
         canonical (compile-package (snapshot/package-index prepared source)
                                    root roots implementations)
         legacy (eager/compile-all (assoc (lookups/build-lookups graph)
                                          :base-fns (registry/compute-base-fns-map @loaded)))
         invoke (fn [compiled args]
                  (executor/execute {:compiled-registry (atom compiled)
                                     :graph-cache (atom graph) :base-fns {}}
                                    root args))]
     {:analysis (:analysis canonical) :staged-source (:staged-source canonical)
      :canonical #(invoke {root (:invoke canonical)} %)
      :legacy #(invoke legacy %)})))


(defn- rejection-code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(defn- staged-options
  [legacy proposed visible]
  (let [text (pr-str proposed)]
    {:legacy-module legacy :bytes (String/.getBytes text StandardCharsets/UTF_8)
     :sha256 (ids/digest-hex "SHA-256" text) :visible-fn-ids visible}))


(defn- staged-floor
  [payload checksum]
  (let [module-ns "core.arithmetic"
        root (ids/fn-id module-ns :floor)
        base (get @loaded :floor)
        _ (registry/record-rich-types! root :floor base)
        frozen (execution/compile-staged-primitive root (:by-id @registry/*rich-types-override*)
                                                   {root (:impl base)}
                                                   {:bytes payload :sha256 checksum :visible-fn-ids #{root}})]
    (assoc frozen :execute #(executor/execute {:compiled-registry (atom {root (:invoke frozen)})} root %))))


(deftest staged-primitive-executes-without-legacy-preparation
  (let [module {:input-format 2 :namespace "core.arithmetic"
                :fns [{:name :floor :input {:number :numeric} :return-type :int}]}
        text (pr-str module)
        {:keys [analysis execute]} (staged-floor (String/.getBytes text StandardCharsets/UTF_8)
                                                 (ids/digest-hex "SHA-256" text))]
    (is (= :checked (:status analysis)))
    (is (= -3 (execute {:number -2.5})))
    (is (= :unknown-input-field (rejection-code #(execute {:value 1}))))))


(deftest staged-module-replays-byte-derived-bindings-before-execution
  (let [module-ns "offline.fixture"
        name :fixed-floor
        root (ids/fn-id module-ns name)
        floor (ids/fn-id "core.arithmetic" :floor)
        legacy {:namespace module-ns :fns [{:name name :parent :floor :args {:number 8.75}}]}
        source (into (mapv #(assoc % :namespace module-ns) (:fns legacy))
                     (declarations "packages/core/arithmetic/fns.edn" #{:floor}))
        prepared (parse/prepare-module source {} {})
        proposal (get-in (source/plan-module [(first source)] prepared) [:definitions 0 :proposed])
        module (assoc legacy :input-format 2 :fns [(dissoc proposal :namespace)])
        run (fn [options] (runtimes source root #(execution/compile-staged-module %1 %2 %3 %4 options)))
        options (staged-options legacy module #{root floor})
        {:keys [canonical staged-source] legacy-invoke :legacy} (run options)]
    (is (= (:sha256 options) (:sha256 staged-source)))
    (is (= 8 (legacy-invoke {}) (canonical {})))
    (is (= :staged-function-not-visible
           (rejection-code #(run (assoc options :visible-fn-ids #{root})))))
    (is (= :staged-function-not-visible
           (rejection-code #(run (assoc options :visible-fn-ids #{floor})))))
    (let [changed (assoc-in module [:fns 0 :bind :number] [:value 9])
          changed-options (staged-options (:legacy-module options) changed #{root floor})]
      (is (= :unverified-staged-declaration (rejection-code #(run changed-options)))))
    (let [changed (assoc module :fns [])]
      (is (= :staged-module-ledger-mismatch
             (rejection-code #(run (staged-options legacy changed #{root floor}))))))))


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


(defn- map-source
  []
  (into [{:name :_checked-map-upper-test :namespace "fixture" :parent :map :args {:func :str-upper}}]
        (concat (declarations "packages/core/hof/fns.edn" #{:map})
                (declarations "packages/core/strings/fns.edn" #{:str-upper}))))


(deftest prepared-package-map-matches-the-legacy-package-execution
  (let [root (ids/fn-id "fixture" :_checked-map-upper-test)
        {:keys [canonical legacy analysis]} (runtimes (map-source) root)]
    (is (= :checked (:status analysis)))
    (is (= [:list :text] (get-in analysis [:scopes [] :signature :return])))
    (is (= [:list :text] (get-in analysis [:public-input :routes [:coll] :type])))
    (is (= [[:string]] (mapv :parameters (vals (:closures analysis)))))
    (doseq [[input expected] [[[] []] [["a" "" "a" "graph"] ["A" "" "A" "GRAPH"]]]]
      (is (= expected (legacy {:coll input}) (canonical {:coll input}))))
    (is (= :execution-error/type-mismatch
           (try (canonical {:coll [1]})
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))


(deftest prepared-web-state-requires-a-compile-time-value-lowering
  (let [source (into (declarations "packages/core/concurrency/fns.edn" #{:cell :deref})
                     (declarations "packages/web/http/fns.edn" #{:response-cache-cell :_response-cache-current}))]
    (is (= :compile-time-value-compilation-required
           (rejection-code #(runtimes source (ids/fn-id "web.http" :_response-cache-current)))))))
