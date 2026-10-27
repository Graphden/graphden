(ns ^:serial graphden.executor.builtin-plans-test
  "Release artifacts must be checked, reproducible package graphs."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.account-menu-cases :as account-menu]
    [graphden.test-infra.browser-canvas-card-cases :as canvas-card]
    [graphden.test-infra.browser-canvas-structure-cases :as canvas-structure]
    [graphden.test-infra.browser-picker-cases :as picker]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.ui.component-contracts :as shipped]))


(use-fixtures :once (setup/create-container-fixture) exec/with-isolated-rich-types)


;; Build tooling deliberately stays off the server's production classpath.
(load-file "tools/ui_preview/builtin_plans.clj")


(defn- tool
  [name & args]
  (apply (ns-resolve 'tools.ui-preview.builtin-plans name) args))


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(defn- script-roundtrip
  [artifact script]
  (let [payload (json/generate-string artifact {:escape-non-ascii true})]
    (sh/sh "node" "-e"
           (str "const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),z=require('node:zlib');"
                "const r=JSON.parse(fs.readFileSync(0,'utf8')),c=vm.createContext({window:{}});vm.runInContext(r.script,c);"
                "const actual=c.window.GraphdenBuiltinPlans,expected=JSON.parse(r.json);"
                "assert.deepStrictEqual(JSON.parse(JSON.stringify(actual)),expected);"
                "const op=vm.runInContext('Object.prototype',c),ap=vm.runInContext('Array.prototype',c);"
                "function check(a,b){if(b===null||typeof b!=='object')return;"
                "assert.equal(Object.getPrototypeOf(a),Array.isArray(b)?ap:op);"
                "for(const k of Object.keys(b)){assert(Object.hasOwn(a,k));check(a[k],b[k]);}}check(actual,expected);"
                "if(actual.dupes)assert.notEqual(actual.dupes[0],actual.dupes[1]);"
                "console.log(JSON.stringify({bytes:Buffer.byteLength(r.script),gzip:z.gzipSync(r.script).length,"
                "jsonBytes:Buffer.byteLength(r.json),jsonGzip:z.gzipSync(r.json).length}));")
           :in (json/generate-string {:script script :json payload} {:escape-non-ascii true}))))


(deftest builtin-script-interns-only-string-values-with-json-semantics
  (let [text (str (str/join (repeat 40 "long")) "\"\\\n\u2028\u2029😀é" (char 0xd800))
        artifact {"__proto__" {"constructor" "own"} "prototype" "data"
                  "values" [text text "" false nil 9007199254740991]
                  "dupes" [{"__proto__" [1 2]} {"__proto__" [1 2]}]}
        script (tool 'serialize-script artifact)
        result (script-roundtrip artifact script)]
    (is (= script (tool 'serialize-script artifact)))
    (is (zero? (:exit result)) (:err result))))


(deftest source-fingerprint-preserves-edn-value-identity
  (let [fingerprint #(tool 'source-fingerprint [{:args {:value {:value %}}}] {})]
    (is (not= (fingerprint {:phase 1 ":phase" 2})
              (fingerprint {:phase 3 ":phase" 2})))
    (is (not= (fingerprint #{1 2}) (fingerprint [1 2])))
    (is (= (fingerprint {:a 1 :b 2})
           (binding [*print-length* 1 *print-level* 1]
             (fingerprint {:a 1 :b 2}))))
    (is (= (fingerprint (array-map :a 1 :b 2))
           (fingerprint (array-map :b 2 :a 1))))))


(deftest authored-builtin-source-keeps-nested-local-type-twins
  (let [primitives (filter #(= ["core.collections" :get] [(:namespace %) (:name %)])
                           (tool 'source-definitions))
        module (fn [path field type]
                 [{:namespace path :name :_leaf :type {field type}}
                  {:namespace path :name :_inputs :type {:leaf :_leaf}}
                  {:namespace path :name :_leaf-value :parent :get
                   :args {:coll {:as :inputs :type :_inputs}
                          :key {:value :leaf} :default {:value nil}}}
                  {:namespace path :name :read-value :parent :get
                   :args {:coll :_leaf-value :key {:value field} :default {:value nil}}}])
        left (module "test.ui-left" :text :text)
        right (module "test.ui-right" :number :int)
        roots {:left {:initial (ids/fn-id "test.ui-left" :read-value)}
               :right {:initial (ids/fn-id "test.ui-right" :read-value)}}
        entries-var (ns-resolve 'tools.ui-preview.builtin-plans 'entries)
        source-var (ns-resolve 'tools.ui-preview.builtin-plans 'source-definitions)]
    (doseq [modules [[left right] [right left]]]
      (let [definitions (vec (concat primitives (mapcat identity modules)))
            artifact (with-redefs-fn {entries-var roots source-var (constantly definitions)}
                       #(tool 'generate))]
        (doseq [[kind input expected] [[:left {:leaf {:text "left"}} "left"]
                                       [:right {:leaf {:number 73}} 73]]]
          (let [request {:plan (get-in artifact [:plans kind])
                         :cases [{:entry "initial" :inputs {"inputs" (plan/encode-value input)}}]}
                {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                              :in (json/generate-string request))]
            (is (zero? exit) err)
            (when (zero? exit)
              (let [result (first (json/parse-string-strict out true))]
                (is (nil? (:error result)) (:error result))
                (is (= expected (some-> (:value result) plan/decode-value)))))))))))


(def ^:private plan-components
  {:accountMenu :account-menu :orgSwitcher :org-switcher :fnPicker :fn-picker :recents :recents
   :inspector :inspector :explorer :explorer :tutorialCatalog :tutorial-catalog
   :executionStatus :execution-status :executionPath :execution-path :feedback :feedback
   :keymap :keymap :appearance :appearance
   :canvas :canvas :canvasValues :canvas-values
   :canvasInheritance :canvas-inheritance :canvasMetadata :canvas-metadata
   :canvasTypeStructure :canvas-type-structure :canvasBindings :canvas-bindings})


(deftest fixed-module-source-retains-composed-and-server-dependencies
  (let [definitions (tool 'source-definitions)
        graph (get-in (tool 'source-graph definitions) [:snapshot :graph])
        rows (into {} (map (juxt :id identity)) (:fns graph))]
    (doseq [[path name] [["core.collections" :assoc-fn]
                         ["core.system" :server-call]
                         ["storage.branches" :current-branch-name]
                         ["app.ui-account-menu" :account-menu-query]]]
      (is (contains? rows (ids/fn-id path name)) (str path "/" name)))
    (is (= [(ids/fn-id "core.collections" :assoc)]
           (:parent-ids (get rows (ids/fn-id "core.collections" :assoc-fn)))))))


(deftest shipped-builtin-plans-are-reproducible-and-isolated
  (let [rich (atom {:by-id {} :by-name {}})
        aliases (atom {:outside :int})
        markers (atom {:secret {:monotone? true :hide-result? true}})
        id (ids/fn-id "app.ui-account-menu" :account-menu-view)
        package? (owned/owned-fn-id? id)
        package-body (types/package-alias-body :text-map)]
    (binding [registry/*rich-types-override* rich
              types/*type-aliases-override* aliases
              shapes/*marker-registry-override* markers]
      (let [artifact (tool 'generate)
            payload (tool 'serialize artifact)]
        (is (not-any? #(= (str (ids/fn-id "app.ui-account-menu" :_account-menu-branch-query)) (:id %))
                      (mapcat :functions (vals (:plans artifact))))
            "Fixed legacy entries never export an unreachable server query body")
        (let [baked (slurp "resources/packages/app/ui-preview/builtin-plans.json")
              script (slurp "resources/packages/app/ui-preview/builtin-plans.js")
              expected-script (tool 'serialize-script artifact)]
          (let [result (script-roundtrip artifact expected-script)]
            (is (zero? (:exit result)) (:err result))
            (when (zero? (:exit result))
              (let [metrics (json/parse-string (:out result) true)]
                (is (< (:bytes metrics) 1310720) "Generated script stays below the bounded editor asset budget")
                (println "Builtin script literal metrics" metrics))))
          ;; Keep exact byte equality; report hashes instead of dumping the
          ;; complete generated graph and JavaScript into a failed CI log.
          (is (true? (= baked payload))
              (str "Regenerate with clojure -M:dev tools/ui_preview/generate_builtin_plans.clj; "
                   "baked SHA-256=" (ids/digest-hex "SHA-256" baked)
                   "; generated SHA-256=" (ids/digest-hex "SHA-256" payload)
                   "; sourceFingerprint=" (:sourceFingerprint artifact)))
          (is (true? (= script expected-script))
              (str "Builtin JavaScript bytes differ; baked SHA-256=" (ids/digest-hex "SHA-256" script)
                   "; generated SHA-256=" (ids/digest-hex "SHA-256" expected-script))))
        (is (re-matches #"[0-9a-f]{64}" (:sourceFingerprint artifact)))
        (is (= "theme" (get-in artifact [:themeTemplate :rootName])))
        (is (= (mapv #(assoc % :namespace "__GRAPHDEN_THEME_NAMESPACE__")
                     (:fns (edn/read-string (slurp "resources/packages/app/ui-theme-template/fns.edn"))))
               (:fns (edn/read-string (get-in artifact [:themeTemplate :edn])))))
        (is (= (set (vals plan-components)) (set (keys shipped/component-contracts))))
        (is (= (set (keys plan-components)) (set (keys (:plans artifact)))))
        (doseq [[kind compiled] (:plans artifact)]
          (is (= 1 (:format compiled) (:primitiveAbi compiled)))
          (is (= (case kind
                   (:tutorialCatalog :executionStatus :executionPath :feedback :keymap :appearance :orgSwitcher
                                     :canvas :canvasValues :canvasMetadata :canvasBindings) #{"view"}
                   (:canvasInheritance :canvasTypeStructure) #{"initial" "view"}
                   #{"initial" "update" "view"})
                 (set (keys (:entries compiled)))))))
      (is (= {:by-id {} :by-name {}} @rich))
      (is (= {:outside :int} @aliases))
      (is (= {:secret {:monotone? true :hide-result? true}} @markers)))
    (is (= package? (owned/owned-fn-id? id)))
    (is (= package-body (types/package-alias-body :text-map)))))


(deftest authored-private-names-in-distinct-namespaces-keep-their-identities
  (let [source-var (ns-resolve 'tools.ui-preview.builtin-plans 'source-definitions)
        entries-var (ns-resolve 'tools.ui-preview.builtin-plans 'entries)
        definitions (source-var)
        probes [{:namespace "builtin.identity.a" :name :_same :parent :core.logic/const
                 :return-type :text :args {:value "first namespace"}}
                {:namespace "builtin.identity.b" :name :_same :parent :core.logic/const
                 :return-type :text :args {:value "second namespace"}}]
        roots {:identityA {:view (ids/fn-id "builtin.identity.a" :_same)}
               :identityB {:view (ids/fn-id "builtin.identity.b" :_same)}}
        artifact (with-redefs-fn {source-var #(into definitions probes)
                                  entries-var (merge @entries-var roots)}
                   #(tool 'generate))]
    (doseq [[kind expected] [[:identityA "first namespace"] [:identityB "second namespace"]]]
      (let [compiled (get-in artifact [:plans kind])
            request {:plan compiled :cases [{:entry "view" :inputs {}}]}
            {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                          :in (json/generate-string request))]
        (is (= (str (get-in roots [kind :view])) (get-in compiled [:entries "view"])))
        (is (zero? exit) err)
        (when (zero? exit)
          (let [result (first (json/parse-string-strict out true))]
            (is (nil? (:error result)) (:error result))
            (is (= expected (some-> (:value result) plan/decode-value)))))))))


(deftest source-errors-and-secret-annotations-veto-generation
  (let [source-var (ns-resolve 'tools.ui-preview.builtin-plans 'source-definitions)
        definitions (source-var)]
    (doseq [[label return-type] [["incompatible declared type" :int]
                                 ["secret annotation erased by storage" [:secret :_account-menu-state]]]]
      (testing label
        (let [changed (mapv #(cond-> % (= :account-menu-initial (:name %))
                                     (assoc :return-type return-type)) definitions)]
          (is (some? (with-redefs-fn {source-var (constantly changed)}
                       #(failure (fn [] (tool 'generate)))))))))
    (testing "concealed source remains forbidden"
      (let [graph-var (ns-resolve 'tools.ui-preview.builtin-plans 'source-graph)
            original @graph-var
            concealed (fn [source]
                        (update-in (original source) [:snapshot :graph :fns]
                                   #(mapv (fn [row]
                                            (cond-> row (= "account-menu-initial" (:name row))
                                                    (assoc :concealed? true))) %)))]
        (is (= :concealed-function
               (:reason (with-redefs-fn {graph-var concealed}
                          #(failure (fn [] (tool 'generate)))))))))
    (testing "CLI cannot turn into an arbitrary source or root exporter"
      (is (some? (failure #(tool '-main "--view" (str (random-uuid)))))))))


(deftest generated-artifacts-run-ordinary-menu-and-picker-cases
  (let [artifact (json/parse-string-strict
                   (slurp "resources/packages/app/ui-preview/builtin-plans.json") true)
        menu-cases (mapv #(update % :entry (fn [entry]
                                             (case entry :account-menu-initial :initial
                                                   :account-menu-update :update
                                                   :account-menu-view :view)))
                         (account-menu/cases))
        picker-cases (mapv #(assoc % :entry :view :operation-limit 100000)
                           (filter #(= :picker-view (:entry %)) picker/cases))]
    (doseq [[kind cases] [[:accountMenu menu-cases] [:fnPicker picker-cases]]]
      (let [request {:plan (get-in artifact [:plans kind])
                     :cases (mapv (fn [{:keys [entry inputs operation-limit]}]
                                    {:entry (name entry) :operationLimit (or operation-limit 10000)
                                     :inputs (into {} (map (fn [[k v]] [(name k) (plan/encode-value v)])) inputs)}) cases)}
            {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                          :in (json/generate-string request))]
        (is (zero? exit) err)
        (when (zero? exit)
          (doseq [[expected actual] (map vector cases (json/parse-string-strict out true))]
            (is (nil? (:error actual)) (:error actual))
            (is (= (:expected expected) (some-> (:value actual) plan/decode-value)))))))))


(deftest stored-picker-disabled-state-exports-with-checked-source
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            registry/*per-org-rich-override* (atom {})
            types/*type-aliases-override* (atom {})
            types/*alias-view* nil
            runtime/*per-org-aliases-override* (atom {})]
    (let [{:keys [storage ctx]} (setup/bootstrap-crud-graph-from-golden!)]
      (try
        (let [captured (snapshot/read-snapshot storage)
              policy (tool 'check-source! (tool 'source-definitions) captured storage)
              row-id (ids/fn-id "app.ui-fn-picker" :picker-row)
              value-id (ids/fn-id "app.ui-fn-picker" :_picker-row-disabled-value)
              exported (snapshot/export-snapshot captured {} {:view row-id} policy)
              cases (filterv #(= :picker-row (:entry %)) picker/cases)
              request {:plan exported
                       :cases (mapv (fn [{:keys [inputs]}]
                                      {:entry "view"
                                       :inputs {"row" (plan/encode-value (:row inputs))}}) cases)}
              {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                            :in (json/generate-string request))]
          (is (= :plain (get-in policy [:classes value-id])))
          (is (some #(= (str value-id) (:id %)) (:functions exported)))
          (is (zero? exit) err)
          (when (zero? exit)
            (doseq [[{:keys [inputs expected]} actual] (map vector cases (json/parse-string-strict out true))]
              (is (nil? (:error actual)) (:error actual))
              (is (= expected (exec/execute ctx row-id inputs)
                     (some-> (:value actual) plan/decode-value)) (pr-str inputs)))))
        (finally (sp/close storage))))))


(defn- assert-stored-browser-views
  ([ctx root compiled cases] (assert-stored-browser-views ctx root compiled cases "view"))
  ([ctx root compiled cases entry]
   (let [request {:plan compiled
                  :cases (mapv #(hash-map :entry entry :inputs {"inputs" (plan/encode-value %)}) cases)}
         {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                       :in (json/generate-string request))]
     (is (zero? exit) err)
     (when (zero? exit)
       (doseq [[input actual] (map vector cases (json/parse-string-strict out true))]
         (is (nil? (:error actual)) (:error actual))
         (is (= (exec/execute ctx root {:inputs input})
                (some-> (:value actual) plan/decode-value)) (:surface input)))))))


(deftest stored-canvas-display-facts-have-the-same-jvm-and-browser-view
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            registry/*per-org-rich-override* (atom {})
            types/*type-aliases-override* (atom {})
            types/*alias-view* nil
            runtime/*per-org-aliases-override* (atom {})]
    (let [{:keys [storage ctx]} (setup/bootstrap-crud-graph-from-golden!)]
      (try
        (let [root (ids/fn-id "app.ui-canvas" :canvas-view)
              defaults {:name nil :secret false :root false :type-errors 0 :failed-runs 0 :lint 0
                        :branch-state "off" :branch-seed nil :deep-free []}
              cases (mapv #(merge defaults %)
                          [{:surface "fn-name" :name "private-label" :secret true}
                           {:surface "root-diagnostics" :root true :type-errors 2 :failed-runs 3 :lint 1}
                           {:surface "root-diagnostics" :type-errors 2}
                           {:surface "branch-local" :branch-state "inherited" :branch-seed "source-label"}
                           {:surface "deep-free" :deep-free ["request" "response"]}])
              artifact (json/parse-string-strict
                         (slurp "resources/packages/app/ui-preview/builtin-plans.json") true)]
          (assert-stored-browser-views ctx root (get-in artifact [:plans :canvas]) cases)
          (is (nil? (:tree (exec/execute ctx root {:inputs (nth cases 2)}))))
          (is (= "This fn inherits branch-local from `:source-label`. Version rows do not propagate across branches on merge."
                 (:title (exec/execute ctx root {:inputs (nth cases 3)}))))
          ;; Reuse this same stored fixture; the new passive component does not
          ;; need another database/bootstrap just to compare its three surfaces.
          (let [defaults {:label "" :secondary "" :secondary-kind "none" :secondary-title "" :history []}
                cases (mapv #(merge defaults %)
                            [{:surface "value-label"}
                             {:surface "value-label" :label "\"␣\""}
                             {:surface "type-chip" :label "text"}
                             {:surface "type-chip" :label "int" :secondary "> 0"
                              :secondary-kind "refinement" :secondary-title "positive integer"}
                             {:surface "type-chip" :label "text" :secondary "< :any"
                              :secondary-kind "narrowing" :secondary-title "subtype of :any"}
                             {:surface "type-history" :history [{:label "any" :source-label "base, ancestor" :kind "slot"}
                                                                {:label "text" :source-label "child" :kind "binding-override"}]}])]
            (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-values" :canvas-values-view)
                                         (get-in artifact [:plans :canvasValues]) cases))
          (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-inheritance" :inheritance-initial)
                                       (get-in artifact [:plans :canvasInheritance]) canvas-card/policy-cases "initial")
          (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-inheritance" :inheritance-view)
                                       (get-in artifact [:plans :canvasInheritance]) [{:label "same, authorized name"}])
          (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-metadata" :metadata-view)
                                       (get-in artifact [:plans :canvasMetadata]) canvas-card/metadata-cases)
          (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-type-structure" :type-structure-initial)
                                       (get-in artifact [:plans :canvasTypeStructure]) canvas-structure/initial-cases "initial")
          (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-type-structure" :type-structure-view)
                                       (get-in artifact [:plans :canvasTypeStructure]) canvas-structure/type-view-cases)
          (assert-stored-browser-views ctx (ids/fn-id "app.ui-canvas-bindings" :bindings-view)
                                       (get-in artifact [:plans :canvasBindings]) canvas-structure/binding-cases)
          (is (= {:tone "body" :bold false}
                 (exec/execute ctx (ids/fn-id "app.ui-canvas-inheritance" :inheritance-initial)
                               {:inputs (last canvas-card/policy-cases)}))))
        (finally (sp/close storage))))))
