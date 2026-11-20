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
        (doseq [{ns-id :namespace :keys [name]} (concat strings (filter #(= :equal? (:name %)) (sources "logic"))
                                                        (filter #(= :get (:name %)) (sources "collections"))
                                                        (filter #(#{:str-trim :re-find?} (:name %)) (sources "strings"))
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
      (is (= :configuration-evidence-required (:code rejected)))
      (is (= (ids/fn-id "core.system" :_prom-k-lower) (:fn-id rejected))))))


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
        ;; Pinning the SAME primitive is authored policy and must survive
        ;; as a configuration on the projection destination.
        pinned {:namespace "fixture" :name :pinned-sleep :parent :sleep
                :args {:ms {:as :wait :type :int}}}
        selected (filterv #(contains? #{:sleep :str-trim :_retry-pause :_interval-sleep :_parse-fn-form-pid-trim-item}
                                      (:name %)) declarations)
        selected (conj selected pinned)
        package (snapshot/package-index (parse/prepare-module selected {} {}) selected)]
    (doseq [[owner-name owner-ns base-name base-ns field-name alias-name]
            [[:_retry-pause "core.concurrency" :sleep "core.concurrency" :ms :delay-ms]
             [:_interval-sleep "core.concurrency" :sleep "core.concurrency" :ms :every-ms]
             [:_parse-fn-form-pid-trim-item "web.crud" :str-trim "core.strings" :string :item]
             [:pinned-sleep "fixture" :sleep "core.concurrency" :ms :wait]]]
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


(deftest checked-primitive-rename-reports-signature-coverage
  (let [defs (filterv #(#{:sleep :_retry-pause} (:name %)) (sources "concurrency"))
        id (ids/fn-id "core.concurrency" :_retry-pause)
        result (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module defs {} {}) defs) id @roots)]
    (is (= :checked (:status result)))
    (is (true? (get-in result [:coverage :signatures])))
    (is (= :checked (get-in result [:projections 0 :proof :status])))
    (is (empty? (:obligations result)))))


(deftest actual-generic-get-rename-has-a-typed-route-and-explicit-rule-coverage
  (let [selected (into (filterv #(= :get (:name %)) (sources "collections"))
                       (filter #(= :_prom-stack (:name %))) (sources "system"))
        id (ids/fn-id "core.system" :_prom-stack)
        field (ids/slot-id (ids/fn-id "core.collections" :get) :coll)
        alias-id (ids/slot-id id :input)
        package (snapshot/package-index (parse/prepare-module selected {} {}) selected)
        result (snapshot/projection-readiness package id @roots)
        [route] (:projections result)]
    (is (= :incomplete (:status result)))
    (is (= :rule-signature-view-required (-> result :diagnostics last :code)))
    (is (false? (get-in result [:coverage :signatures])))
    (is (= (ids/binding-id id field) (:binding-id route)))
    (is (= [[id []] [field]] (:from route)))
    (is (= [[id []] [alias-id]] (:source route)))
    (is (= :union (first (:actual-type route))))
    (is (= :deferred (get-in route [:proof :status])))
    (is (seq (:obligations result)))
    (is (some #{[[id []] [alias-id]]} (:required-addresses result)))))


(deftest named-dictionary-rename-reaches-the-scoped-checker
  (let [web (edn/read-string (slurp (io/resource "packages/web/crud/fns.edn")))
        child (assoc (some #(when (= :_apply-error-code (:name %)) %) (:fns web)) :namespace (:namespace web))
        ds (conj (filterv #(= :get (:name %)) (sources "collections"))
                 (some #(when (= :keyword-map (:name %)) %) (sources "refinements")) child)
        owner (ids/fn-id (:namespace child) (:name child))
        dictionary (ids/fn-id (:namespace (second ds)) :keyword-map)
        package (snapshot/package-index (parse/prepare-module ds {} {}) ds)
        report (snapshot/projection-readiness package owner @roots)
        [route] (:projections report)
        field (ids/slot-id (ids/fn-id "core.collections" :get) :coll)]
    (is (= :incomplete (:status report)))
    (is (some #(= :rule-signature-view-required (:code %)) (:diagnostics report)))
    (is (= (ids/binding-id owner field) (:binding-id route)))
    (is (= [[owner []] [(ids/slot-id owner :outcome)]] (:source route)))
    (is (= [:map :keyword :any] (:actual-type route)))
    (is (= :missing-original-declaration
           (-> (snapshot/projection-readiness (update package :originals dissoc dictionary) owner @roots)
               :diagnostics first :code)))))


(deftest nested-uses-retain-two-scopes-and-one-original-rename
  (let [core (into (filterv #(#{:str-trim :re-find?} (:name %)) (sources "strings"))
                   (filter #(= :equal? (:name %))) (sources "logic"))
        web (edn/read-string (slurp (io/resource "packages/web/crud-parse/fns.edn")))
        renamed (-> (some #(when (= :_parse-fn-form-pid-trim-item (:name %)) %) (:fns web))
                    (assoc :namespace (:namespace web)))
        target (keyword (:namespace web) (name (:name renamed)))
        host {:namespace "fixture" :name :matches :parent :equal? :args {:a target :b target}}
        ds (conj core renamed host)
        owner (ids/fn-id "fixture" :matches)
        origin (ids/fn-id (:namespace web) (:name renamed))
        field (ids/slot-id (ids/fn-id "core.strings" :str-trim) :string)
        alias-id (ids/slot-id origin :item)
        uses (mapv #(ids/binding-id owner (ids/slot-id (ids/fn-id "core.logic" :equal?) %)) [:a :b])
        result (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module ds {} {}) ds) owner @roots)
        routes (:projections result)
        colliding (assoc host :parent :re-find? :args {:string target :pattern target})
        bad (conj core renamed colliding)
        refused (snapshot/projection-readiness (snapshot/package-index (parse/prepare-module bad {} {}) bad) owner @roots)]
    (is (= :blocked (:status refused)))
    (is (= [:ref-scope-required] (get-in refused [:diagnostics 0 :blockers])))
    (is (= :checked (:status result)) (pr-str (:diagnostics result)))
    (is (= 2 (count routes)))
    (is (= #{origin} (set (map :origin-fn-id routes))))
    (is (= #{(ids/binding-id origin field)} (set (map :binding-id routes))))
    (is (= (set (for [use-id uses] [[owner [use-id]] [field]])) (set (map :from routes))))
    (is (= (set (for [use-id uses] [[owner [use-id]] [alias-id]])) (set (map :source routes))))
    (is (every? #(= :checked (get-in % [:proof :status])) routes))))
