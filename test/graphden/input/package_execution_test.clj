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


(deftest described-rename-keeps-unrepresented-runtime-intersections-blocked
  (let [source (into (declarations "packages/core/collections/fns.edn" #{:empty?})
                     (declarations "packages/web/crud-delete/fns.edn" #{:_owner-fn-ids-empty?}))]
    (is (= :conflicting-runtime-obligations
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
          (if (nil? value)
            (is (= :runtime-obligations-required
                   (rejection-code #(execution/compile-package package owner roots {base-id (:impl native)}))))
            (let [compiled (execution/compile-package package owner roots {base-id (:impl native)})]
              (is (= expected (executor/execute {:compiled-registry (atom {owner (:invoke compiled)})} owner {})))))))
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


(deftest exact-named-callable-forwarding-preserves-graph-call-execution
  (let [request {:value :text}
        callable [:fn {:arg :request-shape} :response-shape]
        definitions [{:namespace "callable-proof" :name :request-shape :type request}
                     {:namespace "callable-proof" :name :response-shape :type request}
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
        bases (assoc @loaded
                     :reader {:args {:func [:fn {:arg request} request] :arg request} :return request :effects #{}
                              :impl (get-in @loaded [:invoke :impl])}
                     :echo {:args {:arg request} :return request :effects #{}
                            :impl (fn [args _] (swap! calls inc) (runtime/resolve-arg args :arg))})
        {:keys [canonical legacy analysis]}
        (runtimes source (ids/fn-id "callable-proof" :run)
                  (fn [package id roots impls]
                    (execution/compile-package (snapshot/with-certified-owner package id roots) id roots impls))
                  bases)]
    (is (= :checked (:status analysis)))
    (doseq [run [legacy canonical]]
      (reset! calls 0)
      (is (= "{\"value\":\"checked\"}" (run {})))
      (is (= 1 @calls)))))
