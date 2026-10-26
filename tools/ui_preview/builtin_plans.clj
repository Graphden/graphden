(ns tools.ui-preview.builtin-plans
  "Build fixed release assets from shipped graph sources, never from a database."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.owned :as owned]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]
    [graphden.storage.remote.core :as remote]
    [graphden.types.check :as check]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.util.ns-path :as ns-path]))


(def artifact-path "resources/packages/app/ui-preview/builtin-plans.json")


(def script-path "resources/packages/app/ui-preview/builtin-plans.js")


(def ^:private modules ["ui-account-menu" "ui-fn-picker" "ui-recents" "ui-inspector" "ui-explorer" "ui-tutorial-catalog" "ui-execution" "ui-feedback" "ui-keymap" "ui-canvas" "ui-canvas-values" "ui-canvas-inheritance" "ui-canvas-metadata" "ui-canvas-type-structure" "ui-canvas-bindings"])


(def ^:private entries
  {:explorer {:initial (ids/fn-id "app.ui-explorer" :explorer-toolbar-initial)
              :update (ids/fn-id "app.ui-explorer" :explorer-toolbar-update)
              :view (ids/fn-id "app.ui-explorer" :explorer-toolbar-view)}
   :inspector {:initial (ids/fn-id "app.ui-inspector" :inspector-initial)
               :update (ids/fn-id "app.ui-inspector" :inspector-update)
               :view (ids/fn-id "app.ui-inspector" :inspector-view)}
   :accountMenu {:initial (ids/fn-id "app.ui-account-menu" :account-menu-initial)
                 :update (ids/fn-id "app.ui-account-menu" :account-menu-update)
                 :view (ids/fn-id "app.ui-account-menu" :account-menu-view)}
   :recents {:initial (ids/fn-id "app.ui-recents" :recents-initial)
             :update (ids/fn-id "app.ui-recents" :recents-update)
             :view (ids/fn-id "app.ui-recents" :recents-view)}
   :canvas {:view (ids/fn-id "app.ui-canvas" :canvas-view)}
   :canvasValues {:view (ids/fn-id "app.ui-canvas-values" :canvas-values-view)}
   :canvasInheritance {:initial (ids/fn-id "app.ui-canvas-inheritance" :inheritance-initial)
                       :view (ids/fn-id "app.ui-canvas-inheritance" :inheritance-view)}
   :canvasMetadata {:view (ids/fn-id "app.ui-canvas-metadata" :metadata-view)}
   :canvasTypeStructure {:initial (ids/fn-id "app.ui-canvas-type-structure" :type-structure-initial)
                         :view (ids/fn-id "app.ui-canvas-type-structure" :type-structure-view)}
   :canvasBindings {:view (ids/fn-id "app.ui-canvas-bindings" :bindings-view)}
   :tutorialCatalog {:view (ids/fn-id "app.ui-tutorial-catalog" :catalog-view)}
   :executionStatus {:view (ids/fn-id "app.ui-execution" :status-view)}
   :executionPath {:view (ids/fn-id "app.ui-execution" :path-view)}
   :feedback {:view (ids/fn-id "app.ui-feedback" :feedback-view)}
   :keymap {:view (ids/fn-id "app.ui-keymap" :keymap-view)}
   :fnPicker {:initial (ids/fn-id "app.ui-account-menu" :account-menu-initial)
              :update (ids/fn-id "app.ui-account-menu" :account-menu-update)
              :view (ids/fn-id "app.ui-fn-picker" :picker-view)}})


(defn- complete-dependencies
  "Retain the complete fixed module sources, adding their actual installed
   dependencies. Literal data never creates a dependency edge."
  [definitions installed]
  (let [reference #(keyword (:namespace %) (name (:name %)))
        indexed (into {} (map (juxt reference identity)) (concat installed definitions))
        by-name (group-by (comp keyword name :name) (vals indexed))]
    (loop [pending (vec definitions) seen #{} result []]
      (if-let [definition (peek pending)]
        (let [id (reference definition)]
          (if (contains? seen id)
            (recur (pop pending) seen result)
            (let [dependencies (atom [])]
              (rebase/rewrite-references
                definition
                (fn [ref]
                  (let [local (if (namespace ref) ref (keyword (:namespace definition) (name ref)))
                        target (or (get indexed local)
                                   (when-not (namespace ref)
                                     (let [matches (get by-name ref)]
                                       (when (= 1 (count matches)) (first matches)))))]
                    (when target (swap! dependencies conj target)))
                  ref))
              (recur (into (pop pending) @dependencies) (conj seen id) (conj result definition)))))
        (vec (sort-by (juxt :namespace :name) result))))))


(defn- source-definitions
  []
  (let [loaded (loader/load-packages ["web"])
        primitives (->> (:base-fn-defs loaded)
                        (map (fn [[n d]] (assoc d :name n)))
                        (filter #((plan/supported-primitive-ids) (ids/fn-id (:namespace %) (:name %)))))
        dependencies (filter #(or (not (or (:parent %) (:parents %)))
                                  (= ["core.refinements" :color-const] [(:namespace %) (:name %)]))
                             (:fn-defs loaded))
        ui (mapcat (fn [module]
                     (let [{module-namespace :namespace :keys [fns]}
                           (edn/read-string (slurp (io/resource (str "packages/app/" module "/fns.edn"))))]
                       (map #(assoc % :namespace module-namespace) fns))) modules)]
    (complete-dependencies (concat primitives dependencies ui)
                           (concat (map (fn [[n d]] (assoc d :name n)) (:base-fn-defs loaded))
                                   (:fn-defs loaded)))))


(defn- source-graph
  [definitions]
  (let [namespaces (->> definitions (map :namespace) distinct sort
                        (mapv (fn [n] {:id (ids/fn-id n :_builtin-namespace) :name n})))
        namespace-ids (into {} (map (juxt :name :id)) namespaces)
        rows (concat (records/boot-primitive-records) (records/parse-module definitions))
        bundle (reduce (fn [acc {:keys [kind] :as row}]
                         (update acc kind (fnil conj [])
                                 (cond-> (dissoc row :kind)
                                   (:namespace-id row) (update :namespace-id namespace-ids)))) {} rows)]
    {:snapshot {:graph {:fns (:fn bundle) :slots (:slot bundle) :fn-slots (:fn-slot bundle)
                        :bindings (:binding bundle) :list-items (:binding-list-item bundle)}
                :namespaces namespaces}
     :storage (remote/from-bundle (assoc bundle :ns namespaces))}))


(defn- canonical
  [value]
  (walk/postwalk (fn [v]
                   (cond
                     (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
                     (set? v) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) v)
                     :else v)) value))


(defn- source-fingerprint
  [definitions theme-template]
  ;; Function objects are loader metadata, not source. Include the complete
  ;; declarative definitions plus the shipped backend ABI implementation.
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false *print-namespace-maps* false]
    (ids/digest-hex "SHA-256"
                    (pr-str (canonical
                              {:definitions (mapv #(dissoc % :impl :return-type-rule :slot-types-rule :nav-types-rule)
                                                  definitions)
                               :theme-template theme-template
                               :runtime (slurp (io/resource "packages/app/ui-preview/browser-runtime.js"))})))))


(defn- check-source!
  [definitions {:keys [graph namespaces]} storage]
  (runtime/register-type-aliases-from-db! graph ::builtin (ns-path/path-map namespaces))
  (let [prepared (:fn-defs (records-parse/prepare-module definitions {} {}))
        by-id (into {} (map (juxt :fn-id identity)) prepared)
        {:keys [forward-deps reverse-deps]} (deps/build-deps-state graph)
        reachable (deps/forward-closure forward-deps (mapcat vals (vals entries)))
        {:keys [ordered cyclic]} (deps/dependency-order reverse-deps reachable)]
    (when (seq cyclic) (throw (ex-info "Builtin UI source has cyclic dependencies" {})))
    (doseq [[id d] by-id :when ((plan/supported-primitive-ids) id)]
      (registry/record-rich-types! id (:name d) d))
    ;; First check the authored forms: their marker/type annotations must veto
    ;; export even when a storage representation deliberately erases them.
    (doseq [id ordered :let [d (get by-id id)] :when (or (:parent d) (:parents d))]
      (check/check-fn-def! (assoc d :fn-id id)))
    (let [authored (snapshot/capture-policy @registry/*rich-types-override*)]
      ;; Certify the exact reconstructed rows used by the existing snapshot
      ;; exporter, without manufacturing or weakening its provenance metadata.
      (doseq [id ordered]
        (when-let [d (type-check/reconstruct-fn-def storage id)]
          (check/check-fn-def! d)))
      (let [checked (snapshot/capture-policy @registry/*rich-types-override*)]
        (update checked :classes
                #(into {} (map (fn [[id visibility]]
                                 [id (if (= :plain (get-in authored [:classes id])) visibility :unknown)])) %))))))


(defn generate
  "Offline utility: generate fixed package plans, without external roots or
   sources. Package metadata overrides are process-wide; callers must serialize."
  []
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            registry/*per-org-rich-override* (atom {})
            types/*type-aliases-override* (atom {})
            types/*alias-view* nil
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
            runtime/*per-org-aliases-override* (atom {})]
    (let [definitions (source-definitions)
          prepared (:fn-defs (records-parse/prepare-module definitions {} {}))
          theme-template (edn/read-string
                           (slurp (io/resource "packages/app/ui-theme-template/fns.edn")))
          package-ids (into #{} (map :fn-id) prepared)
          aliases (into {} (mapcat (fn [d]
                                     (when-let [body (sync/type-row-alias-body d)]
                                       [[(ids/fn-id (:namespace d) (:name d)) body]
                                        [(:name d) body]
                                        [(keyword (:namespace d) (name (:name d))) body]]))) prepared)]
      ;; Reproduce package bootstrap's source metadata without mutating its
      ;; process-global registries. This utility runs only during asset builds.
      (with-redefs [owned/owned-fn-id? (fn [id] (contains? package-ids id))
                    types/package-alias-body (fn [n] (get aliases n))]
        (let [{:keys [snapshot storage]} (source-graph definitions)
              policy (check-source! definitions snapshot storage)]
          {:format 1 :primitiveAbi 1 :sourceFingerprint (source-fingerprint definitions theme-template)
           ;; JSON cannot preserve keyword references versus string values.
           ;; The UI replaces only the explicit per-definition namespace token.
           :themeTemplate {:rootName "theme"
                           :edn (pr-str {:fns (mapv #(assoc % :namespace "__GRAPHDEN_THEME_NAMESPACE__")
                                                    (:fns theme-template))})}
           :plans (into (sorted-map)
                        (map (fn [[name roots]] [name (snapshot/export-snapshot snapshot {} roots policy)]))
                        entries)})))))


(defn serialize
  [artifact]
  (str (json/generate-string (canonical artifact)) "\n"))


(defn serialize-script
  "Emit the same JSON data as trusted literals. Only repeated long string
   VALUES share a constant; every object/array remains independently allocated."
  [artifact]
  (let [data (json/parse-string (serialize artifact))
        string-values (fn string-values
                        [value]
                        (cond
                          (string? value) [value]
                          (map? value) (mapcat string-values (vals value))
                          (sequential? value) (mapcat string-values value)
                          :else []))
        pool (->> (frequencies (string-values data))
                  (keep (fn [[value n]] (when (and (> n 1) (>= (count value) 32)) value)))
                  sort vec)
        indexes (zipmap pool (range))
        quote-json #(json/generate-string % {:escape-non-ascii true})
        literal (fn literal
                  [value]
                  (cond
                    (string? value) (if-some [i (get indexes value)]
                                      (str "strings[" i "]")
                                      (quote-json value))
                    ;; __proto__ needs a computed key to remain an own data
                    ;; property. Other JSON-quoted keys use normal literals.
                    (map? value) (str "{" (str/join "," (map (fn [[k v]]
                                                               (str (if (= k "__proto__")
                                                                      (str "[" (quote-json k) "]")
                                                                      (quote-json k))
                                                                    ":" (literal v)))
                                                             (sort-by key value))) "}")
                    (sequential? value) (str "[" (str/join "," (map literal value)) "]")
                    :else (quote-json value)))]
    (str "// Generated by tools/ui_preview/generate_builtin_plans.clj.\n"
         "(()=>{const strings=" (quote-json pool) ";window.GraphdenBuiltinPlans="
         (literal data) ";})();\n")))


(defn -main
  [& args]
  (when-not (or (empty? args) (= ["--check"] (vec args)))
    (throw (ex-info "Use no arguments to generate, or --check to verify builtin plans" {})))
  (let [artifact (generate)
        expected {artifact-path (serialize artifact)
                  script-path (serialize-script artifact)}]
    (if (seq args)
      (let [stale (into [] (keep (fn [[path content]]
                                   (when (not= content (when (java.io.File/.exists (io/file path)) (slurp path))) path))) expected)]
        (when (seq stale)
          (throw (ex-info "Builtin plans are stale; regenerate before WTQ"
                          {:paths stale :command "clojure -M:dev tools/ui_preview/generate_builtin_plans.clj"})))
        (println "Builtin plans match graph sources"))
      (do
        (doseq [[path content] expected] (spit path content))
        (println "Generated" artifact-path "and" script-path)))))
