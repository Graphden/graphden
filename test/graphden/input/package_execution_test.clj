(ns graphden.input.package-execution-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.runtime :as runtime]
    [graphden.input.analysis :as analysis]
    [graphden.input.compile :as input]
    [graphden.input.literal-lookup :as literal-lookup]
    [graphden.input.package-execution :as execution]
    [graphden.input.source :as source]
    [graphden.input.source-certification :as certification]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]
    [graphden.types.validator :as validator])
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
(def ^:private mathx-loaded (delay (:base-fn-defs (loader/load-packages ["core" "mathx"]))))


(defn- runtimes
  ([source root] (runtimes source root execution/compile-package))
  ([source root compile-package] (runtimes source root compile-package @loaded))
  ([source root compile-package base-fn-defs]
   (let [prepared (parse/prepare-module source {} {})
         records (group-by :kind (parse/parse-prepared-module prepared))
         graph {:fns (:fn records) :slots (:slot records) :fn-slots (:fn-slot records)
                :bindings (:binding records) :list-items (:binding-list-item records)}
         base-declarations (filter #(contains? base-fn-defs (:name %)) source)
         _ (doseq [{:keys [name] module-ns :namespace} base-declarations]
             (registry/record-rich-types! (ids/fn-id module-ns name) name (get base-fn-defs name)))
         roots (:by-id @registry/*rich-types-override*)
         implementations (into {} (map (fn [{:keys [name] module-ns :namespace}]
                                         [(ids/fn-id module-ns name) (:impl (get base-fn-defs name))])) base-declarations)
         canonical (compile-package (snapshot/package-index prepared source)
                                    root roots implementations)
         legacy (eager/compile-all (assoc (lookups/build-lookups graph)
                                          :base-fns (registry/compute-base-fns-map base-fn-defs)))
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


(deftest legacy-reference-can-open-the-reader-of-a-forwarded-input
  (let [source (into (declarations "packages/core/logic/fns.edn" #{:if :nil?})
                     (concat (declarations "packages/core/refinements/fns.edn" #{:positive-int :non-negative-int})
                             (declarations "packages/core/strings/fns.edn" #{:str-trim})
                             [{:namespace "fixture" :name :choose :parent :if
                               :args {:test {:parent :nil? :args {:value {:as :rows}}}
                                      :then {:value "empty"} :else {:value "nonempty"}
                                      :rows :str-trim :string {:type :text}}}]))
        owner (ids/fn-id "fixture" :choose)
        compile-owner (fn [package root roots implementations]
                        (let [migrated (snapshot/with-certified-owner package root roots)
                              entry (get-in migrated [:closure-baselines root])]
                          (is (= [:ref :core.strings/str-trim] (get-in entry [:proposed :bind [:test :rows]])))
                          (is (= [:project [:string]] (get-in entry [:proposed :bind [:test :rows :string]])))
                          (execution/compile-package migrated root roots implementations)))
        {:keys [canonical legacy]} (runtimes source owner compile-owner)]
    (doseq [value ["  a  " "  "]]
      (is (= "nonempty" (legacy {:string value}) (canonical {:string value}))))))


(deftest one-legacy-literal-feeds-both-checked-reference-occurrences
  (let [source (into (declarations "packages/core/logic/fns.edn" #{:if})
                     (concat (declarations "packages/core/refinements/fns.edn" #{:positive-int :non-negative-int})
                             (declarations "packages/core/strings/fns.edn" #{:str-upper})
                             [{:namespace "fixture" :name :upper-word :parent :str-upper
                               :args {:string {:as :word}}}
                              {:namespace "fixture" :name :choose :parent :if
                               :args {:then :upper-word :else :upper-word :word "hello"}}]))
        owner (ids/fn-id "fixture" :choose)
        compile-owner (fn [package root roots implementations]
                        (let [migrated (snapshot/with-migrated-bindings package [root] roots)
                              proof (get-in migrated [:closure-baselines root])
                              copied (some #(when (:copy-of (val %)) (key %)) (get-in proof [:v2 :scoped-inputs]))
                              omitted (update-in proof [:proposed :bind] dissoc copied)
                              replay (source/verify-module {:definitions [omitted]} (:prepared migrated))]
                          (is (vector? copied))
                          (is (= :scoped-origin-mismatch (get-in replay [:definitions 0 :blockers 0 :code])))
                          (execution/compile-package migrated root roots implementations)))
        {:keys [canonical legacy]} (runtimes source owner compile-owner)]
    (doseq [test-value [true false]]
      (is (= "HELLO" (legacy {:test test-value}) (canonical {:test test-value}))))))


(deftest scoped-value-reference-retains-the-checks-of-its-new-use
  (let [operation (fn [name field input-type result]
                    {:namespace "scoped" :name name :args {field {:type input-type}} :return-type result})
        base-declarations [(operation :sink :left :text :text) (operation :reader :deep :any :text)
                           (operation :middle :inner :any :any) (operation :leaf :sample :any :any)]
        source (into base-declarations [{:namespace "scoped" :name :producer :parent :middle :args {:inner :leaf}}
                                        {:namespace "scoped" :name :host :parent :sink
                                         :args {:left :reader :deep :producer}}])
        native (into {} (map (fn [{:keys [name args] :as base}]
                               (let [field (first (keys args))]
                                 [name (assoc base :impl
                                              (fn [arguments _]
                                                (let [value (runtime/resolve-arg arguments field)]
                                                  (if (= :reader name) (pr-str value) value))))]))) base-declarations)
        owner (ids/fn-id "scoped" :host)
        receipt (atom nil)
        {:keys [canonical legacy analysis]}
        (runtimes source owner
                  (fn [package id roots implementations]
                    (let [migrated (snapshot/with-certified-owner package id roots)]
                      (reset! receipt (get-in migrated [:closure-baselines id]))
                      (execution/compile-package migrated id roots implementations))) native)
        certificate (some :scope-certificate (get-in @receipt [:legacy :arguments]))
        child-path (conj (get-in certificate [:row :scope-path]) (get-in certificate [:row :id]))
        inner (ids/slot-id (ids/fn-id "scoped" :middle) :inner)
        checks (into {} (map (juxt :address :expected-type)) (:obligations analysis))]
    (is (= :authored-source-equivalent (:verification @receipt)))
    (is (= (:obligations analysis) (:obligations certificate)))
    (is (= :any (get checks (:address certificate))))
    (is (= :any (get checks [[owner child-path] [inner]])))
    (doseq [value [nil false "field"]]
      (is (= (pr-str value) (legacy {:sample value})
             (canonical {:left {:deep {:inner {:sample value}}}}))))))


(deftest secondary-record-fields-remain-visible-during-native-parent-execution
  (let [decl {:namespace "fixture" :name :TextInput :type {:text :text}}
        child {:namespace "fixture" :name :upper-record :parents [:str-upper :TextInput]}
        source (into [decl child] (declarations "packages/core/strings/fns.edn" #{:str-upper}))
        root (ids/fn-id "fixture" :upper-record)
        {:keys [legacy canonical analysis]} (runtimes source root)]
    (is (= [(:fn-id analysis) []] (:root-scope analysis)))
    (is (= :text (get-in analysis [:public-input :routes [:text] :completion-type])))
    (is (= "MIXED" (legacy {:string "MiXeD" :text "separate"})
           (canonical {:string "MiXeD" :text "separate"})))))


(deftest a-projected-literal-map-proves-its-checked-lookup-result
  (let [root (ids/fn-id "fixture" :has-error)
        source (into [{:namespace "fixture" :name :error? :parent :some?
                       :args {:value {:parent :get :args {:coll {:as :listing :type [:map :keyword :any]}
                                                          :key {:value :error} :default nil}}}}
                      {:namespace "fixture" :name :has-error :parent :error?
                       :args {:listing {:value {:kind "fns" :error "bad-kind"}}}}]
                     cat [(declarations "packages/core/logic/fns.edn" #{:some?})
                          (declarations "packages/core/collections/fns.edn" #{:get})])
        evidence (atom nil)
        prove literal-lookup/proves-read?
        compile-source (fn [package owner roots native]
                         (execution/compile-package (snapshot/with-certified-owner package owner roots) owner roots native))
        {:keys [legacy canonical]}
        (with-redefs [literal-lookup/proves-read?
                      (fn [before after certs check]
                        (when (prove before after certs check)
                          (reset! evidence [before after certs check])
                          true))]
          (runtimes source root compile-source @loaded))]
    (is (= true (legacy {}) (canonical {})))
    (is (some? @evidence))
    (when-let [[before after certs check] @evidence]
      (is (= :any (:expected-type check)))
      (is (not (prove before after [] check)))
      (is (not (prove before after (mapv #(assoc-in % [:row :value :error] 7) certs) check)))
      (is (not (prove before after certs (assoc check :id (random-uuid))))))))


(deftest read-of-a-constant-record-retains-a-checked-keyword-result
  (let [root (ids/fn-id "fixture" :tagged?)
        source (into [{:namespace "fixture" :name :tagged :parent :const
                       :args {:value {:value {:tag :ok :value "text"}}}}
                      {:namespace "fixture" :name :tagged? :parent :variant-is?
                       :args {:coll :tagged :tag {:value :ok}}}]
                     cat [(declarations "packages/core/logic/fns.edn" #{:const :equal?})
                          (declarations "packages/core/collections/fns.edn" #{:get})
                          (declarations "packages/core/variants/fns.edn" #{:variant-tag :variant-is?})])
        evidence (atom nil)
        prove literal-lookup/proves-read?
        compile-source (fn [package owner roots native]
                         (let [verified (snapshot/with-certified-owner package owner roots)]
                           (execution/compile-package verified owner roots native)))
        {:keys [legacy canonical]} (with-redefs [literal-lookup/proves-read?
                                                 (fn [before after certs check]
                                                   (when (prove before after certs check)
                                                     (reset! evidence [before after certs check])
                                                     true))]
                                     (runtimes source root compile-source @loaded))]
    (is (= true (legacy {}) (canonical {})))
    (is (some? @evidence))
    (when-let [[before after certs check] @evidence]
      (let [address (:address check)
            map-address (:address (first certs))
            lookup-path (second (first map-address))
            key-address [[root lookup-path] (get-in after [:scopes lookup-path :signature-source :argument-paths :key])]
            constant-path (conj lookup-path (get-in (first certs) [:row :id]))
            record-address [[root constant-path] (get-in after [:scopes constant-path :signature-source :argument-paths :value])]
            at (fn [path addr] [:scopes path :bindings addr :expression :value])
            binding (get-in after [:scopes lookup-path :bindings map-address])
            expected (:expected-type binding)
            actual (get-in binding [:reference :actual-type])]
        (is (= [:map :keyword :any] (literal-lookup/keyword-map-read-type after map-address expected actual)))
        (is (nil? (literal-lookup/keyword-map-read-type
                    (assoc-in after (at constant-path record-address) {:tag :ok "extra" 0})
                    map-address expected actual)))
        (is (nil? (literal-lookup/keyword-map-read-type after map-address [:union :null [:map :text :any]] actual)))
        (is (false? (boolean (prove before after [] check))))
        (doseq [graph [(assoc-in after (at lookup-path key-address) :missing)
                       (assoc-in after (at constant-path record-address) {:tag "wrong"})
                       (assoc-in after [:proofs address :status] :deferred)
                       (assoc-in after [:scopes constant-path :signature-source :base-fn-id] (random-uuid))]]
          (is (false? (boolean (prove before graph certs check)))))))))


(deftest forwarded-input-reaches-two-distinct-uses-of-one-legacy-slot
  (let [base {:namespace "fixture" :name :combine
              :args {:left {:type :text} :right {:type :text}} :return-type :text}
        native (assoc base :impl (fn [args _]
                                   (str (runtime/resolve-arg args :left) "/" (runtime/resolve-arg args :right))))
        owner (ids/fn-id "fixture" :both)
        source (into (declarations "packages/core/strings/fns.edn" #{:str-upper})
                     [base
                      {:namespace "fixture" :name :left-reader :parent :str-upper}
                      {:namespace "fixture" :name :right-reader :parent :str-upper}
                      {:namespace "fixture" :name :both :parent :combine
                       :args {:left :left-reader :right :right-reader :string {:type :text}}}])
        compile-package (fn [package root roots impls]
                          (let [migrated (snapshot/with-migrated-forwarding package root :string roots)
                                receipt (get-in migrated [:closure-baselines root])]
                            (is (= :authored-source-equivalent (:verification receipt)))
                            (is (= {[:left :string] [:project [:string]] [:right :string] [:project [:string]]}
                                   (select-keys (get-in receipt [:proposed :bind]) [[:left :string] [:right :string]])))
                            (execution/compile-package migrated root roots impls)))
        {:keys [legacy canonical]} (runtimes source owner compile-package (assoc @loaded :combine native))]
    (is (= "AB/AB" (legacy {:string "Ab"}) (canonical {:string "Ab"})))
    (is (= "/" (legacy {:string ""}) (canonical {:string ""})))))


(deftest forwarded-input-preserves-lazy-conditional-branches
  (let [owner (ids/fn-id "fixture" :choose)
        source (into [{:namespace "fixture" :name :left-reader :parent :str-upper}
                      {:namespace "fixture" :name :right-reader :parent :str-upper}
                      {:namespace "fixture" :name :choose :parent :if
                       :args {:then :left-reader :else :right-reader :string {:type :text}}}]
                     cat [(declarations "packages/core/strings/fns.edn" #{:str-upper})
                          (declarations "packages/core/logic/fns.edn" #{:if})
                          (declarations "packages/core/refinements/fns.edn" #{:positive-int :non-negative-int})])
        calls (atom 0)
        implementations (update-in @loaded [:str-upper :impl]
                                   (fn [impl]
                                     (fn [args context]
                                       (swap! calls inc)
                                       (impl args context))))
        compile-package (fn [package root roots impls]
                          (let [migrated (snapshot/with-migrated-forwarding package root :string roots)]
                            (execution/compile-package migrated root roots impls)))
        {:keys [legacy canonical]} (runtimes source owner compile-package implementations)]
    (doseq [test [true false]
            run [legacy canonical]]
      (reset! calls 0)
      (is (= "DATA" (run {:test test :string "data"})))
      (is (= 1 @calls)))))


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


(deftest runtime-matches-when-a-scoped-argument-introduces-an-inline-reference
  (let [strings (declarations "packages/core/strings/fns.edn" #{:_csv-escape-cell :str-upper :str-trim})
        inline {:parent :str-trim :args {:string " Ada "}}
        host {:namespace "fixture" :name :host :parent :_csv-escape-cell
              :args {:value :str-upper :string inline}}
        source (conj strings host)
        owner (ids/fn-id "fixture" :host)
        slot (ids/slot-id (ids/fn-id "core.strings" :str-upper) :string)
        compile-with-proof (fn [package root roots implementations]
                             (let [verified (snapshot/with-certified-owner package root roots)
                                   entry (get-in verified [:closure-baselines root])
                                   child (get-in entry [:v2 :inline-definitions [slot]])]
                               (is (= :authored-source-equivalent (:verification entry)))
                               (is (= (:fn-id child) (get-in entry [:v2 :scoped-inputs [:value :string] :row :ref-fn-id])))
                               (execution/compile-package verified root roots implementations)))
        {:keys [legacy canonical]} (runtimes source owner compile-with-proof)]
    (is (= "ADA" (legacy {}) (canonical {})))))


(deftest checked-polymorphic-list-execution-matches-the-legacy-graph
  (let [child {:namespace "fixture" :name :directory :parent :zipmap
               :args {:keys {:append [{:value :first} {:value :second}] :closed true}
                      :vals {:append ["one" 2] :closed true}}}
        source (conj (declarations "packages/core/collections/fns.edn" #{:zipmap}) child)
        root (ids/fn-id "fixture" :directory)
        base (ids/fn-id "core.collections" :zipmap)
        prepared (parse/prepare-module source {} {})
        entry (first (:definitions (source/plan-module [child] prepared)))
        module {:namespace "fixture" :fns [(dissoc child :namespace)]}
        staged (assoc module :input-format 2 :fns [(dissoc (:proposed entry) :namespace)])
        run (fn [visible]
              (let [options (staged-options module staged visible)]
                (runtimes source root #(execution/compile-staged-module %1 %2 %3 %4 options))))
        {:keys [canonical legacy analysis]} (run #{root base})]
    (is (= :checked (:status analysis)))
    (is (= {:first "one" :second 2} (legacy {}) (canonical {})))
    (is (= :staged-function-not-visible (rejection-code #(run #{root}))))))


(deftest inherited-list-contributors-execute-in-legacy-order
  (let [prefix {:namespace "fixture" :name :subtotal :parent :sub :args {:nums [100 10]}}
        suffix {:namespace "fixture" :name :total :parent :subtotal
                :args {:nums {:append [{:parent :floor :args {:number 2.7}}] :closed true}}}
        source (into (declarations "packages/core/arithmetic/fns.edn" #{:sub :floor}) [prefix suffix])
        {:keys [canonical legacy analysis]} (runtimes source (ids/fn-id "fixture" :total))]
    (is (= :checked (:status analysis)))
    (is (= 88 (legacy {}) (canonical {})))))


(deftest shipped-described-rename-retains-binding-metadata-and-runtime-values
  (let [source (into (declarations "packages/core/strings/fns.edn" #{:to-str})
                     (declarations "packages/app/branches/fns.edn" #{:_approval-status-source-ref}))
        owner (ids/fn-id "app.branches" :_approval-status-source-ref)
        target (ids/slot-id (ids/fn-id "core.strings" :to-str) :value)
        alias-id (ids/slot-id owner :source-branch-id)
        {:keys [canonical legacy analysis]} (runtimes source owner)
        configured (some #(when (= target (:id %)) %) (get-in analysis [:scopes [] :configured-contract :fields]))]
    (is (= :checked (:status analysis)))
    (is (= "The proposal (source) branch to report on." (:description configured)))
    (is (= alias-id (get-in analysis [:public-input :routes [:source-branch-id] :origin-field-id])))
    (is (= :text (get-in analysis [:scopes [] :signature :return])))
    (is (= "00112233-4455-6677-8899-aabbccddeeff"
           (legacy {:source-branch-id #uuid "00112233-4455-6677-8899-aabbccddeeff"})
           (canonical {:source-branch-id #uuid "00112233-4455-6677-8899-aabbccddeeff"})))))


(deftest described-rename-requires-a-typed-list-read-validator
  (let [source (into (declarations "packages/core/collections/fns.edn" #{:empty?})
                     (declarations "packages/web/crud-delete/fns.edn" #{:_owner-fn-ids-empty?}))]
    (is (= :runtime-obligations-required
           (rejection-code #(runtimes source (ids/fn-id "web.crud" :_owner-fn-ids-empty?)))))))


(deftest shipped-documenting-value-does-not-install-a-default-binding
  (let [source (declarations "packages/core/system/fns.edn" #{:parse-json})
        owner (ids/fn-id "core.system" :parse-json)
        field (ids/slot-id owner :keywordize)
        {:keys [canonical legacy analysis]} (runtimes source owner)
        node (some #(when (= field (:id %)) %) (get-in analysis [:scopes [] :configured-contract :fields]))
        package (snapshot/package-index (parse/prepare-module source {} {}) source)
        frozen (snapshot/freeze-declarations package [owner] (:by-id @registry/*rich-types-override*))
        declared (some #(when (= field (:id %)) %) (get-in frozen [:entries owner :contract :fields]))]
    (is (= :checked (:status analysis)))
    (is (= {:type :bool :required false :value true
            :description "When true, convert string keys to keywords."} (:declaration declared)))
    (is (false? (:required node)))
    (is (empty? (get-in frozen [:entries owner :bindings])))
    (is (= [[[owner []] [(ids/slot-id owner :string)]]] (:required-addresses analysis)))
    (doseq [[arguments expected] [[{:string "{\"flag\":false,\"missing\":null}"} {"flag" false "missing" nil}]
                                  [{:string "{\"flag\":false}" :keywordize false} {"flag" false}]
                                  [{:string "{\"flag\":false}" :keywordize true} {:flag false}]]]
      (is (= expected (legacy arguments) (canonical arguments))))))


(deftest documenting-value-does-not-hide-an-explicit-inherited-false
  (let [child {:namespace "fixture" :name :string-keys :parent :parse-json
               :args {:keywordize {:value false}}}
        source (conj (declarations "packages/core/system/fns.edn" #{:parse-json}) child)
        owner (ids/fn-id "fixture" :string-keys)
        {:keys [canonical legacy analysis]} (runtimes source owner)
        argument {:string "{\"flag\":false,\"missing\":null}"}]
    (is (= :checked (:status analysis)))
    (is (= {"flag" false "missing" nil} (legacy argument) (canonical argument)))))


(deftest frozen-get-in-callback-retains-unconsumed-runtime-obligations
  (binding [types/*type-aliases-override* (atom {})]
    (let [base (into (declarations "packages/core/collections/fns.edn" #{:get-in})
                     (declarations "packages/core/refinements/fns.edn" #{:path-segment}))
          base-id (ids/fn-id "core.collections" :get-in)
          native (get @loaded :get-in)]
      (sync/register-type-aliases! base)
      (registry/record-rich-types! base-id :get-in native)
      (doseq [[value path default expected] [[{:leaf false} [:leaf] "fallback" false]
                                             [{:leaf nil} [:leaf] false nil]
                                             [{} [:leaf] false false]
                                             [nil [:leaf] false false]
                                             [{:leaf 7} ["leaf"] "fallback" "fallback"]]]
        (let [child {:namespace "fixture" :name :lookup :parent :get-in
                     :args {:map {:value value} :path {:value path} :default {:value default}}}
              source (conj base child)
              owner (ids/fn-id "fixture" :lookup)
              package (snapshot/package-index (parse/prepare-module source {} {}) source)
              roots (:by-id @registry/*rich-types-override*)
              {:keys [context options]} (snapshot/freeze-closure package [owner] roots)
              checked (analysis/analyze-effective-input context owner options)
              actual ((:impl native) {:map value :path path :default default} {})]
          (is (= :checked (:status checked)))
          (is (= expected actual))
          (is (= actual (validator/validate! (get-in checked [:scopes [] :signature :return]) actual)))
          (let [compiled (execution/compile-package package owner roots {base-id (:impl native)})]
            (is (= expected (executor/execute {:compiled-registry (atom {owner (:invoke compiled)})} owner {}))))))
      (let [without-proof (update @loaded :get-in dissoc :local-type-rules)]
        (is (= :rule-signature-view-required
               (rejection-code #(runtimes base base-id execution/compile-package without-proof))))))))


(deftest frozen-lookup-abi-and-source-field-share-the-declared-binder
  (binding [types/*type-aliases-override* (atom {})]
    (let [child {:namespace "fixture" :name :json-lookup :parent :get-in
                 :args {:map :parse-json :path {:value [:leaf]} :default {:value false}}}
          source (into (declarations "packages/core/collections/fns.edn" #{:get-in})
                       (concat (declarations "packages/core/refinements/fns.edn" #{:path-segment})
                               (declarations "packages/core/system/fns.edn" #{:parse-json}) [child]))
          owner (ids/fn-id "fixture" :json-lookup)
          base (ids/fn-id "core.collections" :get-in)
          field (ids/slot-id base :map)
          _ (sync/register-type-aliases! source)
          _ (doseq [name [:get-in :parse-json]
                    :let [native (get @loaded name)]]
              (registry/record-rich-types! (ids/fn-id (:namespace native) name) name native))
          roots (:by-id @registry/*rich-types-override*)
          package (snapshot/package-index (parse/prepare-module source {} {}) source)
          {:keys [context options]} (snapshot/freeze-closure package [owner] roots)
          checked (analysis/analyze-effective-input context owner options)
          obligations (filterv #(= [[owner []] [field]] (:address %)) (:obligations checked))]
      (is (= '[:union :null [:map a :any]]
             (get-in options [:implementations owner :argument-declarations :map :type])))
      (is (= field (get-in options [:implementations owner :argument-declarations :map :id])))
      (is (= :checked (:status checked)))
      (is (= 1 (count obligations)))
      (is (= :field-read (:trigger (first obligations))))
      (is (= (:expected-type (first obligations))
             (:type (some #(when (= field (:id %)) %) (get-in checked [:scopes [] :configured-contract :fields])))))
      ;; Binder equality removes a duplicate check, not its runtime boundary.
      (is (= :runtime-obligations-required
             (rejection-code #(execution/compile-package
                                package owner roots
                                (into {} (for [name [:get-in :parse-json]
                                               :let [native (get @loaded name)]]
                                           [(ids/fn-id (:namespace native) name) (:impl native)])))))))))


(deftest generated-list-projection-reads-the-authored-outer-field
  (let [child {:namespace "fixture" :name :greeting :parent :str
               :args {:parts ["Hello " {:as :person :type :text} "!"]}}
        source (conj (declarations "packages/core/strings/fns.edn" #{:str}) child)
        root (ids/fn-id "fixture" :greeting)
        {:keys [canonical legacy analysis]} (runtimes source root)
        field (ids/slot-id root :person)]
    (is (= :checked (:status analysis)))
    (is (= field (get-in analysis [:public-input :routes [:person] :origin-field-id])))
    (is (= "Hello Ada!" (legacy {:person "Ada"}) (canonical {:person "Ada"})))))


(deftest staged-primitive-executes-without-legacy-preparation
  (let [module {:input-format 2 :namespace "core.arithmetic"
                :fns [{:name :floor :input {:number :numeric} :return-type :int}]}
        text (pr-str module)
        {:keys [analysis execute]} (staged-floor (String/.getBytes text StandardCharsets/UTF_8)
                                                 (ids/digest-hex "SHA-256" text))]
    (is (= :checked (:status analysis)))
    (is (= -3 (execute {:number -2.5})))
    (is (= :unknown-input-field (rejection-code #(execute {:value 1}))))))


(deftest stored-input-compiles-a-shipped-native-implementation-through-the-executor
  (let [originals (conj (declarations "packages/core/arithmetic/fns.edn" #{:floor})
                        {:name :stored-floor :namespace "fixture" :parent :floor})
        owner (ids/fn-id "fixture" :stored-floor)
        compile-snapshot (fn [package root roots implementations]
                           (let [{:keys [context]} (snapshot/freeze-closure package [root] roots)
                                 trusted (into {} (map (fn [[id impl]] [id {:signature (get roots id) :impl impl}])) implementations)]
                             (input/compile-stored context root trusted)))
        {:keys [canonical legacy analysis]} (runtimes originals owner compile-snapshot)]
    (is (= :checked (:status analysis)))
    (is (= #{[] [:number]} (set (keys (get-in analysis [:public-input :routes])))))
    (doseq [[argument expected] [[3.2 3] [-1.2 -2] [0 0]]]
      (is (= expected (legacy {:number argument}) (canonical {:number argument}))))
    (is (= :unknown-input-field (rejection-code #(canonical {:value 1}))))))


(defn- staged-gcd
  [payload checksum]
  (let [module-ns "mathx.ops"
        root (ids/fn-id module-ns :gcd-with-12)
        parent (ids/fn-id module-ns :gcd)
        base (get @mathx-loaded :gcd)
        _ (registry/record-rich-types! parent :gcd base)
        frozen (execution/compile-staged-literal-root root (:by-id @registry/*rich-types-override*)
                                                      {parent (:impl base)}
                                                      {:bytes payload :sha256 checksum :visible-fn-ids #{root parent}})]
    (assoc frozen :execute #(executor/execute {:compiled-registry (atom {root (:invoke frozen)})} root %))))


(deftest staged-composed-root-keeps-only-its-residual-field
  (let [module {:input-format 2 :namespace "mathx.ops"
                :fns [{:name :gcd :input {:a :int :b :int} :return-type :int :effects #{}}
                      {:name :gcd-with-12 :parent :gcd :bind {:a [:value 12]}}]}
        text (pr-str module)
        {:keys [analysis execute]} (staged-gcd (String/.getBytes text StandardCharsets/UTF_8)
                                               (ids/digest-hex "SHA-256" text))]
    (is (= :checked (:status analysis)))
    (is (= #{[] [:b]} (set (keys (get-in analysis [:public-input :routes])))))
    (is (= {:b :int} (get-in analysis [:public-input :root :type])))
    (is (= 6 (execute {:b 18})))
    (is (= :unknown-input-field (rejection-code #(execute {:a 9 :b 18}))))))


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


(deftest staged-legacy-rename-keeps-omission-and-nil-at-the-original-reader
  (let [path "packages/examples/regression/migrate-on-fn-ref/fns.edn"
        module (edn/read-string (slurp (io/resource path)))
        module-ns (:namespace module)
        core (declarations "packages/core/collections/fns.edn" #{:list :conj})
        source (into core (map #(assoc % :namespace module-ns)) (:fns module))
        owners (mapv #(ids/fn-id module-ns (:name %)) (:fns module))
        visible (into #{} (map #(ids/fn-id (:namespace %) (:name %))) source)
        compile-staged (fn [package root roots implementations]
                         (let [certified (reduce #(snapshot/with-certified-owner %1 %2 roots) package owners)
                               staged (assoc module :input-format 2
                                             :fns (mapv #(-> (get-in certified [:closure-baselines % :proposed])
                                                             (dissoc :namespace :source-file)) owners))]
                           (execution/compile-staged-module certified root roots implementations
                                                            (staged-options module staged visible))))
        reads (atom [])
        conj-impl (get-in @loaded [:conj :impl])
        native (assoc-in @loaded [:conj :impl] (fn [args ctx]
                                                 (swap! reads conj (contains? args :item))
                                                 (conj-impl args ctx)))
        pair (ids/fn-id module-ns :_ex-pair-with-first)
        {:keys [canonical legacy analysis]} (runtimes source pair compile-staged native)]
    (is (false? (get-in analysis [:public-input :routes [:item2] :required])))
    (doseq [input [{} {:item2 nil} {:item2 false} {:item2 "second"}]
            run [legacy canonical]]
      (reset! reads [])
      (is (= ["first" (:item2 input)] (run input)))
      (is (= [true true] @reads)))
    (let [outer (ids/fn-id module-ns :ex-outer)
          {:keys [canonical legacy]} (runtimes source outer compile-staged)]
      (is (= [["first" nil]] (legacy {}) (canonical {})))
      (is (= [["first" 42]] (legacy {:item2 42}) (canonical {:items {:item-0 {:item2 42}}}))))))


(deftest shipped-json-handler-executes-from-verified-scoped-source
  (let [core (vec (concat
                    (declarations "packages/core/collections/fns.edn" #{:assoc})
                    (declarations "packages/core/refinements/fns.edn" #{:http-status :text-map})
                    (declarations "packages/core/system/fns.edn"
                                  #{:ring-response-shape :ring-response :_ring-response--w-status
                                    :_ring-response--w-headers :to-json-string})))
        web (vec (concat
                   (declarations "packages/web/response/fns.edn" #{:json-content-type :ok-response :json-ok-response})
                   (declarations "packages/web/crud/fns.edn" #{:value-kinds})
                   (declarations "packages/web/crud-routes/fns.edn"
                                 #{:json-handler :_json-handler-body :value-kinds-handler})))
        prepared-core (parse/prepare-module core {} {})
        prepared-web (parse/prepare-module web (:name->id prepared-core) (:defs-by-name prepared-core))
        root (ids/fn-id "web.crud" :json-handler)
        descendant (ids/fn-id "web.crud" :value-kinds-handler)
        parents (mapv (partial ids/fn-id "web.response") [:ok-response :json-content-type])
        prepare-staged
        (fn [_ owner roots implementations]
          (let [{:keys [source-proof]} (certification/verify-plan prepared-core core {} roots)
                {certified :package ids :certified {:keys [definitions]} :plan}
                (certification/verify-plan prepared-web web source-proof roots)
                {:keys [source proposed]} (get-in certified [:closure-baselines owner])
                legacy {:namespace "web.crud" :fns [(dissoc source :namespace :source-file)]}
                staged (assoc legacy :input-format 2 :fns [(dissoc proposed :namespace :source-file)])
                options (staged-options legacy staged (set (keys (:declarations certified))))]
            (is (every? ids (conj parents owner)))
            (is (= :authored-source-equivalent
                   (:verification (first (filter #(= descendant (:identity %)) definitions)))))
            (is (= [:project [:data]] (get-in proposed [:bind [:body :data]])))
            (execution/compile-staged-module certified owner roots implementations options)))
        {:keys [canonical legacy analysis]} (runtimes (into core web) root prepare-staged)]
    (is (= :checked (:status analysis)))
    (doseq [value [{:ok true} [1 false "data"] "value"]]
      (let [args {:data value}
            result (canonical args)]
        (is (= 200 (:status result)))
        (is (= "application/json" (get-in result [:headers "Content-Type"])))
        (is (= (legacy args) result))))
    (is (= :unknown-input-field (rejection-code #(canonical {:body {:data "occupied"}}))))))


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


(deftest scoped-list-operand-executes-at-its-preserved-legacy-position
  (let [directory {:namespace "fixture" :name :operand-directory :parent :zipmap
                   :args {:keys {:append [{:value :prefix} {:value :upper}] :closed true}
                          :vals {:append [{:value false} :str-upper] :closed true}}}
        child {:namespace "fixture" :name :operand-call :parent :operand-directory :args {:string "checked"}}
        source (into (declarations "packages/core/strings/fns.edn" #{:str-upper})
                     (concat (declarations "packages/core/collections/fns.edn" #{:zipmap}) [directory child]))
        owner (ids/fn-id "fixture" :operand-call)
        compile-certified (fn [package root roots implementations]
                            (execution/compile-package (snapshot/with-migrated-bindings package [root] roots)
                                                       root roots implementations))
        {:keys [canonical legacy analysis]} (runtimes source owner compile-certified)]
    (is (= :checked (:status analysis)))
    (is (empty? (:obligations analysis)))
    (is (= {:prefix false :upper "CHECKED"} (legacy {}) (canonical {})))))


(deftest untyped-positional-input-executes-with-its-original-any-slot
  (let [directory {:namespace "fixture" :name :positional-directory :parent :str
                   :args {:parts {:append [{:value false} {:as :upper}] :closed true}}}
        wrapper {:namespace "fixture" :name :positional-wrapper :parent :const :args {:value :positional-directory}}
        dependencies (into (declarations "packages/core/logic/fns.edn" #{:const})
                           (declarations "packages/core/strings/fns.edn" #{:str}))
        owner (ids/fn-id "fixture" :positional-call)
        retained-checks (atom nil)
        compile-certified (fn [package root roots implementations]
                            (let [migrated (snapshot/migrate-binding package root roots)]
                              (reset! retained-checks (get-in migrated [:legacy :arguments 0 :scope-certificate :obligations]))
                              (execution/compile-package (snapshot/with-migrated-bindings package [root] roots)
                                                         root roots implementations)))]
    (doseq [value [nil false {:input [:as :data] :bind false :configure nil}]]
      (let [child {:namespace "fixture" :name :positional-call :parent :positional-wrapper :args {:upper {:value value}}}
            source (into dependencies [directory wrapper child])
            {:keys [canonical legacy analysis]} (runtimes source owner compile-certified)]
        (is (= :checked (:status analysis)))
        (is (= 2 (count (:obligations analysis))))
        (is (= #{[:value-type :field-read [:list :any]] [:value-type :field-read :any]}
               (into #{} (map (juxt :kind :trigger :expected-type)) (:obligations analysis))))
        (is (= (set @retained-checks) (set (:obligations analysis))))
        (is (= (str false value) (legacy {}) (canonical {})))))))


(deftest inherited-forwarding-and-scoped-owner-chain-executes-like-legacy
  (let [a {:namespace "owner-proof" :name :a :parent :_csv-escape-cell
           :args {:value :to-json-string :data {:type :jsonb}}}
        b {:namespace "owner-proof" :name :b :parent :a
           :args {:data {:value nil} :extra {:type :text}}}
        c {:namespace "owner-proof" :name :c :parent :b
           :args {:extra :str-trim :string "unread"}}
        source (into (declarations "packages/core/strings/fns.edn" #{:_csv-escape-cell :str-trim})
                     (concat (declarations "packages/core/system/fns.edn" #{:to-json-string}) [a b c]))
        root (ids/fn-id "owner-proof" :c)
        calls (atom [])
        original (get-in @loaded [:to-json-string :impl])
        implementations (-> @loaded
                            (assoc-in [:to-json-string :impl]
                                      (fn [& args] (swap! calls conj :selected) (apply original args)))
                            (assoc-in [:str-trim :impl]
                                      (fn [& _] (swap! calls conj :unread) (throw (ex-info "Unread reference forced" {})))))
        {:keys [canonical legacy analysis]}
        (runtimes source root
                  (fn [package owner roots impls]
                    (execution/compile-package (snapshot/with-certified-owner package owner roots) owner roots impls))
                  implementations)]
    (is (= :checked (:status analysis)))
    (is (empty? (:required-addresses analysis)))
    (is (= "null" (legacy {})))
    (is (= [:selected] @calls))
    (reset! calls [])
    (is (= "null" (canonical {})))
    (is (= [:selected] @calls))))


(deftest inline-scoped-owner-executes-like-its-legacy-declaration
  (let [forwarded {:namespace "inline-scope" :name :forwarded :parent :_csv-escape-cell
                   :args {:value :to-json-string :data {:type :jsonb}}}
        configured {:namespace "inline-scope" :name :configured :parent :forwarded
                    :args {:data {:value false} :extra {:type :text}}}
        child {:parent :configured :args {:extra :str-trim :string " unused "}}
        dependencies (into (declarations "packages/core/strings/fns.edn" #{:_csv-escape-cell :str-trim})
                           (declarations "packages/core/system/fns.edn" #{:to-json-string}))
        owner (ids/fn-id "inline-scope" :host)
        compile-certified (fn [package root roots implementations]
                            (execution/compile-package (snapshot/with-certified-owner package root roots)
                                                       root roots implementations))]
    (doseq [inline [child {:parent :_csv-escape-cell :args {:value child}}]]
      (let [host {:namespace "inline-scope" :name :host :parent :_csv-escape-cell :args {:value inline}}
            {:keys [canonical legacy analysis]} (runtimes (into dependencies [forwarded configured host])
                                                          owner compile-certified)]
        (is (= :checked (:status analysis)))
        (is (empty? (:required-addresses analysis)))
        (is (= "false" (legacy {}) (canonical {})))))))


(deftest scoped-inline-annotation-preserves-input-and-execution
  (let [child {:parent :_csv-escape-cell
               :args {:value :str-trim :string {:as :string :type :text :description "Source"}}}
        host {:namespace "inline-annotation" :name :host :parent :_csv-escape-cell :args {:value child}}
        dependencies (declarations "packages/core/strings/fns.edn" #{:_csv-escape-cell :str-trim})
        owner (ids/fn-id "inline-annotation" :host)
        compile-certified (fn [package root roots implementations]
                            (execution/compile-package (snapshot/with-certified-owner package root roots)
                                                       root roots implementations))
        {:keys [canonical legacy analysis]} (runtimes (conj dependencies host) owner compile-certified)]
    (is (= :checked (:status analysis)))
    (is (= "input" (legacy {:string " input "}) (canonical {:value {:value {:string " input "}}})))))


(deftest same-owner-joint-proof-preserves-nil-false-and-unread-reference
  (let [parent {:namespace "joint-runtime" :name :with-extra :parent :_csv-escape-cell
                :args {:extra {:type :text :required false}}}
        owner {:namespace "joint-runtime" :name :joint :parent :with-extra
               :args {:value :to-json-string :extra :str-trim
                      :data {:type :jsonb} :string "unread"}}
        source (into (declarations "packages/core/strings/fns.edn" #{:_csv-escape-cell :str-trim})
                     (concat (declarations "packages/core/system/fns.edn" #{:to-json-string}) [parent owner]))
        root (ids/fn-id "joint-runtime" :joint)
        calls (atom [])
        original (get-in @loaded [:to-json-string :impl])
        implementations (-> @loaded
                            (assoc-in [:to-json-string :impl]
                                      (fn [& args] (swap! calls conj :selected) (apply original args)))
                            (assoc-in [:str-trim :impl]
                                      (fn [& _] (swap! calls conj :unread) (throw (ex-info "Unread joint reference forced" {})))))
        {:keys [canonical legacy analysis]}
        (runtimes source root
                  (fn [package id roots impls]
                    (execution/compile-package (snapshot/with-certified-owner package id roots) id roots impls))
                  implementations)]
    (is (= :checked (:status analysis)))
    (doseq [[value expected] [[nil "null"] [false "false"]]
            run [legacy canonical]]
      (reset! calls [])
      (is (= expected (run {:data value})))
      (is (= [:selected] @calls)))))


(defn- generic-forward-runtimes
  [value-type encode retained]
  (binding [types/*type-aliases-override* (atom {})]
    (let [producer {:namespace "fixture" :name :generic-map-producer
                    :args {:payload :any} :return-type :map-result}
          host {:namespace "fixture" :name :generic-map-host :parent :const
                :args {:value :generic-map-producer :payload {:type :any}}}
          source (into (declarations "packages/core/logic/fns.edn" #{:const})
                       [{:namespace "fixture" :name :map-result :map {:key :keyword :value value-type}} producer host])
          _ (sync/register-type-aliases! source)
          base (assoc producer :impl (fn [arguments _context] {:payload (encode (runtime/resolve-arg arguments :payload))}))
          base-definitions (assoc @loaded :generic-map-producer base)
          compile-certified (fn [package root roots implementations]
                              (let [migrated (snapshot/migrate-forwarded-field package root :payload roots)]
                                (reset! retained {:verification (:verification migrated)
                                                  :certificate (some :scope-certificate (get-in migrated [:legacy :arguments]))})
                                (execution/compile-package (snapshot/with-migrated-forwarding package root :payload roots)
                                                           root roots implementations)))]
      (runtimes source (ids/fn-id "fixture" :generic-map-host) compile-certified base-definitions))))


(deftest generic-const-forwarding-executes-with-the-exact-named-map-return
  (let [retained (atom nil)
        {:keys [canonical legacy analysis]} (generic-forward-runtimes :text pr-str retained)]
    (is (= :checked (:status analysis)))
    (is (= [:map :keyword :text] (get-in analysis [:scopes [] :signature :return])))
    (is (= (ids/slot-id (ids/fn-id "core.logic" :const) :value)
           (get-in @retained [:certificate :generic-reference :field-id])))
    (is (= (set (:obligations analysis)) (set (get-in @retained [:certificate :obligations]))))
    (doseq [value [nil false {:input [:as :data] :bind false :configure nil}]]
      (is (= {:payload (pr-str value)} (legacy {:payload value}) (canonical {:payload value}))))))


(deftest generic-const-forwarding-executes-with-a-checked-dictionary-read
  (let [retained (atom nil)
        {:keys [canonical legacy]} (generic-forward-runtimes :any identity retained)]
    (doseq [value [nil false {:key "value"}]]
      (is (= {:payload value} (legacy {:payload value}) (canonical {:payload value}))))
    (is (= :authored-source-equivalent (:verification @retained)))
    (is (= [[:map :keyword :any]]
           (mapv :expected-type
                 (filter #(= (get-in @retained [:certificate :generic-reference :field-id]) (first (:path %)))
                         (get-in @retained [:certificate :obligations])))))))


(defn- named-callable-runtime
  [record-shape? compile-package]
  (let [request (if record-shape? {:value :text} [:map :keyword :text])
        type-source (if record-shape? {:type request} {:map {:key :keyword :value :text}})
        callable [:fn {:arg :request-shape} :response-shape]
        definitions [(merge {:namespace "callable-proof" :name :request-shape} type-source)
                     (merge {:namespace "callable-proof" :name :response-shape} type-source)
                     {:namespace "callable-proof" :name :reader
                      :args {:func {:type callable} :arg {:type :request-shape}}
                      :return-type :response-shape}
                     {:namespace "callable-proof" :name :echo
                      :args {:arg {:type :request-shape}} :return-type :response-shape}
                     {:namespace "callable-proof" :name :wrapper :parent :to-json-string
                      :args {:data :reader :func {:type callable}}}
                     {:namespace "callable-proof" :name :run :parent :wrapper
                      :args {:func :echo :arg {:value {:value "checked"}}}}]
        source (into (declarations "packages/core/system/fns.edn" #{:to-json-string}) definitions)
        calls (atom 0)
        base-definitions (assoc @loaded
                                :reader {:namespace "callable-proof"
                                         :args {:func {:type [:fn {:arg request} request]} :arg {:type request}}
                                         :return-type request :effects #{}
                                         :impl (get-in @loaded [:invoke :impl])}
                                :echo {:namespace "callable-proof" :args {:arg {:type request}}
                                       :return-type request :effects #{}
                                       :impl (fn [args _] (swap! calls inc) (runtime/resolve-arg args :arg))})]
    (assoc (runtimes source (ids/fn-id "callable-proof" :run)
                     (fn [package id roots impls]
                       (compile-package (snapshot/with-certified-owner package id roots) id roots impls))
                     base-definitions)
           :calls calls)))


(deftest exact-named-dictionary-callable-forwarding-preserves-graph-execution
  (let [{:keys [canonical legacy analysis calls]} (named-callable-runtime false execution/compile-package)]
    (is (= :checked (:status analysis)))
    (doseq [run [legacy canonical]]
      (reset! calls 0)
      (is (= "{\"value\":\"checked\"}" (run {})))
      (is (= 1 @calls)))))


(deftest exact-named-record-callable-executes-through-the-checked-parameter-path
  (let [retained (atom nil)
        capture (fn [package id roots impls]
                  (let [{:keys [context options]} (snapshot/freeze-closure package [id] roots)
                        checked (analysis/analyze-effective-input context id options)]
                    (reset! retained {:verification (get-in package [:closure-baselines id :verification])
                                      :status (:status checked)
                                      :call-sites (mapcat :call-sites (vals (:closures checked)))})
                    (execution/compile-package package id roots impls)))
        {:keys [canonical legacy calls]} (named-callable-runtime true capture)]
    (is (= :authored-source-equivalent (:verification @retained)))
    (is (= :checked (:status @retained)))
    (is (= [[:value]] (mapv :value-path (:call-sites @retained))))
    (doseq [run [legacy canonical]]
      (reset! calls 0)
      (is (= "{\"value\":\"checked\"}" (run {})))
      (is (= 1 @calls)))))


(deftest shipped-method-map-keeps-a-bound-handler-callable-after-return
  (let [source (into (declarations "packages/core/collections/fns.edn" #{:assoc :assoc-fn :assoc-empty})
                     (concat (declarations "packages/app-base/common/fns.edn" #{:method-map :assoc-handler})
                             [{:namespace "callable-proof" :name :echo
                               :args {:arg {:type :text}} :return-type :text}
                              {:namespace "callable-proof" :name :route :parent :app.common/method-map
                               :args {:method "GET" :handler :echo}}]))
        base-defs (assoc @loaded :echo {:name :echo :namespace "callable-proof"
                                        :args {:arg :text} :return-type :text :effects #{}
                                        :impl (fn [args _] (runtime/resolve-arg args :arg))})
        root (ids/fn-id "callable-proof" :route)
        callback-id (ids/fn-id "callable-proof" :echo)
        compile-package (fn [package id roots impls]
                          (let [certified (snapshot/with-certified-owner package id roots)]
                            (is (= :authored-source-equivalent (get-in certified [:closure-baselines id :verification])))
                            (execution/compile-package certified id roots impls)))
        {:keys [canonical legacy analysis]} (runtimes source root compile-package base-defs)]
    (is (= :checked (:status analysis)))
    (doseq [run [legacy canonical]]
      (let [handler (get-in (run {}) ["GET" "handler"])]
        (is (= callback-id (:graphden.executor/fn-id (meta handler))))
        (is (= "request" (handler "request")))
        (is (= "other thread" @(future (handler "other thread"))))))))


(deftest a-forwarded-value-reaches-every-legacy-list-reader
  (let [source (into (declarations "packages/core/strings/fns.edn" #{:str})
                     (map #(assoc % :namespace "forwarded-list")
                          [{:name :_item :parent :str :args {:parts [{:as :label}]}}
                           {:name :left :parent :str :args {:parts [{:value "left="} :_item]}}
                           {:name :right :parent :str :args {:parts [{:value ";right="} :_item]}}
                           {:name :both :parent :str
                            :args {:parts [:left :right] :label {:type :text}}}]))
        root (ids/fn-id "forwarded-list" :both)
        compile-certified (fn [package id roots implementations]
                            (execution/compile-package (snapshot/with-certified-owner package id roots)
                                                       id roots implementations))
        {:keys [canonical legacy]} (runtimes source root compile-certified)]
    (doseq [label ["shared" ""]]
      (let [args {:label label}]
        (is (= (str "left=" label ";right=" label) (legacy args) (canonical args)))))))


(deftest migrated-zipmap-closes-only-the-final-unbound-legacy-continuation
  (let [prefix {:namespace "fixture" :name :base-row :parent :zipmap
                :args {:keys [{:value :first}] :vals [{:value "a"}]}}
        suffix {:namespace "fixture" :name :extended-row :parent :base-row
                :args {:keys {:append [{:value :second}]} :vals {:append [{:value "b"}]}}}
        originals (into (declarations "packages/core/collections/fns.edn" #{:zipmap}) [prefix suffix])
        root (ids/fn-id "fixture" :extended-row)
        compile-root (fn [package id roots impls]
                       (let [{:keys [context options]} (snapshot/freeze-source-analysis package [id] roots)
                             open (analysis/analyze-effective-input context id options)]
                         (is (= 2 (count (filter #(= [:input-field :tail true] ((juxt :kind :name :whole-bindable?) %))
                                                 (vals (get-in open [:public-input :routes]))))))
                         (execution/compile-package package id roots impls)))
        {:keys [legacy canonical analysis]} (runtimes originals root compile-root)
        public (set (map :name (get-in analysis [:public-input :root :fields])))]
    (is (= :checked (:status analysis)))
    (is (= {:first "a" :second "b"} (legacy {}) (canonical {})))
    (is (empty? public))
    (is (= :unknown-input-field (rejection-code #(canonical {:keys {:tail [:injected]}}))))))


(deftest migrated-zipmap-retains-list-demand-and-reference-sharing
  (let [tick (fn [name value]
               {:namespace "fixture" :name name :parent :str-upper :args {:string {:value value}}})
        owner {:namespace "fixture" :name :unequal-row :parent :zipmap
               :args {:keys [:first-key :second-key :extra-key :unused-key]
                      :vals [:first-value :first-value]}}
        originals (into (declarations "packages/core/collections/fns.edn" #{:zipmap})
                        (concat (declarations "packages/core/strings/fns.edn" #{:str-upper})
                                [(tick :first-key "a") (tick :second-key "b") (tick :extra-key "c")
                                 (tick :unused-key "d") (tick :first-value "value") owner]))
        calls (atom [])
        implementations (update-in @loaded [:str-upper :impl]
                                   (fn [impl]
                                     (fn [args ctx]
                                       (let [v (impl args ctx)]
                                         (swap! calls conj v)
                                         v))))
        {:keys [legacy canonical]} (runtimes originals (ids/fn-id "fixture" :unequal-row)
                                             execution/compile-package implementations)
        _ (is (empty? @calls))
        expected (legacy {})
        reads @calls]
    (reset! calls [])
    (is (= {"A" "VALUE" "B" "VALUE"} expected (canonical {})))
    (is (= reads @calls))
    (is (= 1 (count (filter #{"VALUE"} reads))))
    (is (not-any? #{"D"} reads))))


(deftest ordinary-numeric-list-remains-open-at-invocation
  (let [prefix {:namespace "fixture" :name :difference-prefix :parent :sub :args {:nums [20 3]}}
        suffix {:namespace "fixture" :name :difference-complete :parent :difference-prefix
                :args {:nums {:append [2 4] :closed true}}}
        originals (into (declarations "packages/core/arithmetic/fns.edn" #{:sub}) [prefix suffix])
        part (runtimes originals (ids/fn-id "fixture" :difference-prefix))
        full (runtimes originals (ids/fn-id "fixture" :difference-complete))]
    (is (= 15 ((:canonical part) {:nums {:tail [2]}})))
    (is (= 11 ((:canonical full) {}) ((:legacy full) {})))))


(deftest an-inherited-list-builder-feeds-its-certified-forwarded-field
  (let [source (into (declarations "packages/core/strings/fns.edn" #{:str :str-trim})
                     [{:namespace "forwarded-list" :name :wrapper :parent :str-trim
                       :args {:string :str :parts {:type [:list :any]}}}
                      {:namespace "forwarded-list" :name :parts :parent :wrapper
                       :args {:parts ["  first" " second "]}}])
        root (ids/fn-id "forwarded-list" :parts)
        compile-certified (fn [package id roots implementations]
                            (execution/compile-package (snapshot/with-certified-owner package id roots)
                                                       id roots implementations))
        {:keys [legacy canonical]} (runtimes source root compile-certified)]
    (is (= "first second" (legacy {}) (canonical {})))))


(deftest a-scoped-literal-preserves-its-sibling-list-builder
  (let [source (into (declarations "packages/core/strings/fns.edn" #{:str :str-trim})
                     [{:namespace "forwarded-list" :name :scope-and-list :parent :str
                       :args {:parts [:str-trim {:value "!"}] :string "   attached   "}}])
        root (ids/fn-id "forwarded-list" :scope-and-list)
        compile-certified (fn [package id roots implementations]
                            (execution/compile-package (snapshot/with-certified-owner package id roots)
                                                       id roots implementations))
        {:keys [legacy canonical]} (runtimes source root compile-certified)]
    (is (= "attached!" (legacy {}) (canonical {})))))


(deftest terminal-named-input-feeds-distinct-legacy-slot-identities
  (let [source (into (declarations "packages/core/collections/fns.edn" #{:zipmap})
                     (concat (declarations "packages/core/strings/fns.edn" #{:str :to-str})
                             [{:namespace "fixture" :name :first-text :parent :str
                               :args {:parts [{:value "["} {:as :word} {:value "]"}]}}
                              {:namespace "fixture" :name :second-text :parent :str
                               :args {:parts [{:as :word} {:value "!"}]}}
                              {:namespace "fixture" :name :count-text :parent :to-str
                               :args {:value {:as :n}}}
                              {:namespace "fixture" :name :template :parent :zipmap
                               :args {:keys [{:value :first} {:value :second} {:value :n}]
                                      :vals [:first-text :second-text :count-text]
                                      :word {:type :text} :n {:type :int}}}]))
        root (ids/fn-id "fixture" :template)
        compile-template (fn [package owner roots implementations]
                           (let [rows (group-by :kind (parse/parse-prepared-module (:prepared package)))
                                 graph (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                                                               :bindings (:binding rows) :list-items (:binding-list-item rows)})
                                 hydrated (assoc package :legacy-graph graph)
                                 migrated (snapshot/with-migrated-forwarding hydrated owner [:word :n] roots)]
                             (execution/compile-package migrated owner roots implementations)))
        {:keys [canonical legacy]} (runtimes source root compile-template)
        args {:word "sample" :n 7}]
    (is (= {:first "[sample]" :second "sample!" :n "7"} (legacy args) (canonical args)))
    (doseq [consumer [{:name :descendant :parent :template}
                      {:name :list-client :parent :str :args {:parts [:template]}}
                      {:name :client :parent :to-str :args {:value :template}}]]
      (is (= :nonunique-reference-use
             (rejection-code #(runtimes (conj source (assoc consumer :namespace "fixture")) root compile-template)))))))


(deftest original-query-lookup-preserves-ring-middleware-fields
  (let [source (into (declarations "packages/core/refinements/fns.edn" #{:path-segment :text-map})
                     (concat (declarations "packages/core/collections/fns.edn" #{:get-in})
                             (declarations "packages/web/ring-adapter/fns.edn" #{:ring-request-shape})
                             (declarations "packages/web/crud-delete/fns.edn" #{:_query-param-string-key})))
        root (ids/fn-id "web.crud" :_query-param-string-key)
        compile-query (fn [package root roots implementations]
                        (execution/compile-package (snapshot/with-certified-owner package root roots)
                                                   root roots implementations))]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! source)
      (let [{:keys [legacy canonical]} (runtimes source root compile-query)
            request {:request-method :get :uri "/" :headers {} :query-string "" :body nil :async-channel nil}]
        (doseq [value [nil false "found" 0]
                :let [args {:request (assoc request :query-params {"q" value}) :param-name "q"}]]
          (is (= value (legacy args) (canonical args))))))))
