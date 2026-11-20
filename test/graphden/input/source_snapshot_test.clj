(ns graphden.input.source-snapshot-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core.shapes :as shapes]))


(defn- read-package-edn
  [path]
  (edn/read-string (slurp (io/resource (str "packages/core/" path)))))


(defn- sources
  [module]
  (let [{module-ns :namespace :keys [fns]} (read-package-edn (str module "/fns.edn"))]
    (mapv #(assoc % :namespace module-ns) fns)))


(def ^:private strings
  (filterv #(contains? #{:str-upper :_csv-escape-cell} (:name %)) (sources "strings")))


(def ^:private roots
  (delay
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (let [loaded (:base-fn-defs (loader/load-packages ["core"]))]
        (doseq [{ns-id :namespace :keys [name]} (concat strings (filter #(= :str-trim (:name %)) (sources "strings"))
                                                        (filter #(contains? #{:deref :cell :sleep} (:name %)) (sources "concurrency")))]
          (registry/record-rich-types! (ids/fn-id ns-id name) name (get loaded name)))
        (:by-id @registry/*rich-types-override*)))))


(def ^:private host-id (ids/fn-id "fixture" :format-cell))


(defn- fixture
  [value]
  (let [dep (parse/prepare-module strings {} {})
        host {:namespace "fixture" :name :format-cell :parent :core.strings/_csv-escape-cell
              :args {:value :core.strings/str-upper :string value}}
        prepared (parse/prepare-module [host] (:name->id dep) (:defs-by-name dep))]
    (snapshot/package-index prepared (conj strings host))))


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest unique-direct-reference-preserves-package-field-and-binding-test
  (let [package (fixture "checked")
        certificate (snapshot/verify-binding package host-id @roots)
        field (ids/slot-id (ids/fn-id "core.strings" :str-upper) :string)
        edge (ids/binding-id host-id (ids/slot-id (ids/fn-id "core.strings" :_csv-escape-cell) :value))]
    (is (= [:value :string] (:public-path certificate)))
    (is (= [[host-id [edge]] [field]] (:address certificate)))
    (is (= (ids/binding-id host-id field) (get-in certificate [:row :id])))
    (is (= "checked" (get-in certificate [:row :value])))))


(deftest absent-or-incompatible-package-evidence-is-rejected-test
  (let [package (fixture "checked")
        reader (ids/fn-id "core.strings" :str-upper)]
    (is (= :missing-original-declaration
           (:code (failure #(snapshot/verify-binding (update package :originals dissoc reader) host-id @roots)))))
    (is (= :implementation-signature-required
           (:code (failure #(snapshot/verify-binding package host-id (dissoc @roots reader))))))
    (is (= :ambiguous-original-declaration
           (:code (failure #(snapshot/verify-binding (update-in package [:originals reader] into strings) host-id @roots)))))
    (is (= :rule-signature-view-required
           (:code (failure #(snapshot/verify-binding package host-id
                                                     (assoc-in @roots [reader :return-type-rule] identity))))))
    (is (= :types/check-failed
           (:type (failure #(snapshot/verify-binding (fixture 5) host-id @roots)))))))


(deftest core-prometheus-formatting-source-test
  (testing "An existing core source blocker receives an explicit closure diagnostic"
    (let [originals (into [] (mapcat sources) (:modules (read-package-edn "package.edn")))
          prepared (binding [shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
                     (parse/prepare-module originals {} {}))
          id (ids/fn-id "core.system" :_prom-seg)
          rejected (failure #(snapshot/verify-binding (snapshot/package-index prepared originals) id @roots))]
      (is (= :unverified-source-declaration (:code rejected)))
      (is (= [:rename-projection-required :non-value-binding-required] (:blockers rejected)))
      (is (= #uuid "ccaba282-736e-5623-8b50-1186bac7948c" (:fn-id rejected))))))


(deftest existing-web-reference-keeps-its-core-field-identity-test
  (let [core (filterv #(contains? #{:cell :deref} (:name %)) (sources "concurrency"))
        module (edn/read-string (slurp (io/resource "packages/web/http/fns.edn")))
        web (into [] (comp (filter #(contains? #{:_response-cache-current :response-cache-cell} (:name %)))
                           (map #(assoc % :namespace (:namespace module)))) (:fns module))
        dep (parse/prepare-module core {} {})
        prepared (parse/prepare-module web (:name->id dep) (:defs-by-name dep))
        owner (ids/fn-id "web.http" :_response-cache-current)
        target (ids/fn-id "web.http" :response-cache-cell)
        field (ids/slot-id (ids/fn-id "core.concurrency" :deref) :a)
        binding-id (ids/binding-id owner field)
        {:keys [context options]} (snapshot/freeze-closure (snapshot/package-index prepared (into core web)) [owner] @roots)
        graph (analysis/analyze-effective-input context owner options)
        [edge] (:dependencies graph)]
    (is (= target (:target-fn-id edge)))
    (is (= [[owner []] [field]] (:from edge)))
    (is (= [owner [binding-id]] (:to edge)))
    (is (= #uuid "bd46df2a-1484-5826-887c-c5657179f5df" binding-id (:binding-id edge)))
    (is (true? (get-in graph [:coverage :signatures])))))


(deftest package-renames-keep-projection-identity-and-required-input-test
  (let [core (concat (sources "concurrency") (sources "strings"))
        web (edn/read-string (slurp (io/resource "packages/web/crud-parse/fns.edn")))
        declarations (into (vec core) (map #(assoc % :namespace (:namespace web))) (:fns web))
        selected (filterv #(contains? #{:sleep :str-trim :_retry-pause :_interval-sleep :_parse-fn-form-pid-trim-item}
                                      (:name %)) declarations)
        package (snapshot/package-index (parse/prepare-module selected {} {}) selected)]
    (doseq [[owner-name owner-ns base-name base-ns field-name alias-name]
            [[:_retry-pause "core.concurrency" :sleep "core.concurrency" :ms :delay-ms]
             [:_interval-sleep "core.concurrency" :sleep "core.concurrency" :ms :every-ms]
             [:_parse-fn-form-pid-trim-item "web.crud" :str-trim "core.strings" :string :item]]]
      (let [owner (ids/fn-id owner-ns owner-name)
            field (ids/slot-id (ids/fn-id base-ns base-name) field-name)
            alias-id (ids/slot-id owner alias-name)
            {:keys [context options]} (snapshot/freeze-closure package [owner] @roots)
            graph (analysis/analyze-effective-input context owner options)
            [route] (:projections graph)
            address [[owner []] [alias-id]]]
        (is (= :checked (:status graph)))
        (is (empty? (:obligations graph)))
        (is (= [address] (:required-addresses graph)))
        (is (= [[owner []] [field]] (:from route)))
        (is (= address (:source route)))
        (is (= (ids/binding-id owner field) (get-in graph [:public-input :bindings (:from route) :binding-id])))))))
