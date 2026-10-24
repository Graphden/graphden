(ns ci-priority-core
  "Conservative early-test hints. These only change order; the registered CI and
   browser suites still run in full. Unknown paths deliberately yield no hint."
  (:require
    [babashka.fs :as fs]
    [ci-select]
    [clojure.java.io :as io]
    [clojure.string :as str]))


(def ^:private browser-by-editor
  {"editor-undo.js" ["edit-undo.test.js"]
   "editor-shortcuts.js" ["edit-shortcuts.test.js"]
   "editor-tour-cleanup.js" ["edit-tutorial-cleanup-retry.test.js"]
   "editor-sidebar.js" ["edit-sidebar-lazy.test.js" "edit-sidebar-filter.test.js"]
   "editor-type-format.js" ["edit-canvas-types-bindings-passive.test.js"
                            "edit-canvas-values-passive.test.js"]
   "editor-fn-picker-rank.js" ["edit-fn-picker-tiers.test.js"]
   "editor-problems.js" ["edit-problem-lenses.test.js"]})


(def ^:private browser-impact
  ;; A module can affect consumers that do not mention its name. Keep this
  ;; dependency map explicit; filename matching below is only a fallback.
  {"resources/packages/app/editor/editor-root.js"
   ["edit-ui-components-selective.test.js" "edit-account-menu-graph.test.js"
    "edit-recents-graph.test.js" "edit-fn-picker.test.js" :tutorial]
   "resources/packages/app/editor/editor-prefs.js"
   ["edit-prefs.test.js" "edit-ui-components-selective.test.js" :tutorial]
   "resources/packages/app/editor/editor-ui-components.js"
   ["edit-ui-components-selective.test.js" "edit-ui-components.test.js"
    "edit-account-menu-graph.test.js" "edit-recents-graph.test.js" :tutorial]
   "resources/packages/app/editor/editor-edit-modes.js"
   ["edit-ui-components.test.js" "edit-arg-value.test.js" "edit-secret-inline.test.js"]
   "resources/packages/web/runtime/graphden-popover.js"
   ["edit-ui-components.test.js" "edit-arg-value.test.js" "edit-value-card-display.test.js"]
   "resources/packages/app/editor/editor-tour-picker.js"
   ["edit-tutorial-tour-picker.test.js" :tutorial]
   "resources/packages/app/editor/editor-tour-catalog-graph.js"
   ["edit-tutorial-tour-picker.test.js" "edit-ui-components-selective.test.js" :tutorial]
   "resources/packages/app/editor/editor-row-actions.js"
   [:row-actions]
   "resources/packages/app/ui-tutorial-catalog/fns.edn"
   ["edit-tutorial-tour-picker.test.js" "edit-ui-components-selective.test.js" :tutorial]
   "tools/browser-test/tutorial-tour-helpers.js" [:tutorial]})


(declare dependency-distances)


(defn- browser-local-consumers
  [browser-files owners]
  (let [sources (into {}
                      (map (fn [path]
                             [(str (fs/file-name path)) (slurp (str path))]))
                      (fs/glob "tools/browser-test" "*.js"))
        consumers (reduce-kv
                    (fn [graph file source]
                      (reduce (fn [g [_ dependency]]
                                (let [dependency (if (str/ends-with? dependency ".js")
                                                   dependency (str dependency ".js"))]
                                  (if (contains? sources dependency)
                                    (update g dependency (fnil conj #{}) file)
                                    g)))
                              graph
                              (re-seq #"require\(['\"]\./([^'\"]+)['\"]\)" source)))
                    {} sources)
        distances (dependency-distances consumers owners)]
    (->> browser-files
         (filter #(contains? distances %))
         (sort-by (juxt distances identity)))))


(defn- browser-contract-consumers
  [browser-files marker]
  (let [owners (->> (fs/glob "tools/browser-test" "*.js")
                    (keep (fn [path]
                            (when (str/includes? (slurp (str path)) marker)
                              (str (fs/file-name path)))))
                    set)]
    (browser-local-consumers browser-files owners)))


(defn- impacted-browser-files
  [browser-files path]
  (mapcat (fn [entry]
            (case entry
              :tutorial (filter #(str/starts-with? % "edit-tutorial-") browser-files)
              :row-actions (browser-contract-consumers browser-files
                                                       "row-actions-popover")
              [entry]))
          (get browser-impact path)))


(defn changed-paths
  "Use the same path set as CI scoping. A missing base yields no early hints;
   the full gate widens as usual."
  [base]
  (when (and (string? base) (not (str/blank? base)))
    (ci-select/changed-files base)))


(defn browser-first
  "Existing browser files directly changed, declared as consumers of a module,
   or sharing its specific filename stem. This only moves tests earlier; the
   shell runner validates the complete suite again before use."
  [changed]
  (when changed
    (let [browser-files (->> (fs/glob "tools/browser-test" "edit-*.test.js")
                             (map (comp str fs/file-name))
                             sort
                             vec)]
      (->> changed
           sort
           (mapcat (fn [path]
                     (cond
                       (re-matches #"tools/browser-test/edit-[^/]+\.test\.js" path)
                       [(str (fs/file-name path))]

                       (re-matches #"tools/browser-test/[^/]+\.js" path)
                       (concat (browser-local-consumers browser-files
                                                        #{(str (fs/file-name path))})
                               (impacted-browser-files browser-files path))

                       (str/starts-with? path "resources/packages/app/editor/")
                       (let [basename (str (fs/file-name path))
                             stem (-> basename
                                      (str/replace #"^editor-" "")
                                      (str/replace #"\.(js|css)$" "")
                                      (str/replace #"-graph$" ""))
                             prefix (str "edit-" stem "-")]
                         (concat (impacted-browser-files browser-files path)
                                 (get browser-by-editor basename)
                                 (filter #(or (= % (str "edit-" stem ".test.js"))
                                              (str/starts-with? % prefix))
                                         browser-files)))

                       :else (impacted-browser-files browser-files path))))
           distinct
           (filter #(fs/exists? (str "tools/browser-test/" %)))
           vec))))


(defn browser-order
  "Priority is a permutation of the existing browser suite, never a filter."
  [changed]
  (let [all (->> (fs/glob "tools/browser-test" "edit-*.test.js")
                 (map (comp str fs/file-name))
                 sort
                 vec)
        first-files (browser-first changed)
        first-set (set first-files)]
    (into (vec first-files) (remove first-set) all)))


(defn runtime-first
  "Find runtime tests that explicitly mention a changed editor source file.
   A textual match can add an early test but can never exclude one."
  [changed]
  (when changed
    (let [editor-names (->> changed
                            (filter #(re-matches #"resources/packages/app/editor/[^/]+\.(?:js|css)" %))
                            (map #(str (fs/file-name %)))
                            set)
          test-files (sort (fs/glob "tools/runtime-test" "*.test.js"))]
      (->> test-files
           (filter (fn [path]
                     (or (contains? changed (str path))
                         (some #(str/includes? (slurp (str path)) %) editor-names))))
           (map str)
           vec))))


(defn runtime-order
  "Move directly related JS files first while retaining the full file roster."
  [changed]
  (let [all (mapv str (sort (fs/glob "tools/runtime-test" "*.test.js")))
        first-files (runtime-first changed)
        first-set (set first-files)]
    (into (vec first-files) (remove first-set) all)))


(def ^:private non-unit-tags [:integration :container-e2e :perf])


(defn- unit-eligible?
  [value]
  (not-any? #(contains? (meta value) %) non-unit-tags))


(defn- unit-test-form?
  [form]
  (and (seq? form)
       (= 'deftest (first form))
       (symbol? (second form))
       (unit-eligible? form)
       (unit-eligible? (second form))))


(defn- integration-test-form?
  [form ns-integration?]
  (and (seq? form)
       (= 'deftest (first form))
       (symbol? (second form))
       (or ns-integration?
           (some #(contains? (meta %) :integration) [form (second form)]))))


(defn- focused-namespace
  [path kind]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader path))]
      (binding [*read-eval* false]
        (let [form (read reader false ::eof)
              name (when (and (seq? form) (= 'ns (first form))) (second form))
              ns-integration? (boolean (and name (contains? (meta name) :integration)))
              selected? (case kind
                          :unit (and (unit-eligible? name) unit-test-form?)
                          :integration #(integration-test-form? % ns-integration?))]
          (when (and (symbol? name) selected?)
            (loop [form (read reader false ::eof)]
              (cond
                (= ::eof form) nil
                (selected? form) (str name)
                :else (recur (read reader false ::eof))))))))
    (catch Exception _ nil)))


(defn- libspec-names
  [spec]
  (cond
    (symbol? spec) [spec]
    (and (vector? spec) (symbol? (first spec)))
    (let [[prefix & more] spec]
      (if (vector? (first more))
        (mapcat (fn [child]
                  (when (and (vector? child) (symbol? (first child)))
                    [(symbol (str prefix "." (first child)))]))
                more)
        [prefix]))
    :else []))


(defn- literal-links
  "Conservative source hints for common literal dynamic links. The full
   roster still runs, so an unrecognized expression cannot exclude a test."
  [source]
  {:deps (->> (re-seq #"\(\s*(?:clojure\.core/)?(?:requiring-resolve|resolve|require)\s+'([^\s\)\]\}]+)"
                      source)
              (map second)
              (map (fn [value]
                     (let [sym (symbol value)]
                       (if-let [ns-name (namespace sym)]
                         (symbol ns-name) sym))))
              set)
   :packages (->> (re-seq #"\bimpls-fixture\s+\"([^\"]+)\"\s+\"([^\"]+)\""
                          source)
                  (map (fn [[_ package module]] [package module]))
                  set)})


(defn- namespace-entry
  [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (let [form (read {:read-cond :allow :features #{:clj :bb}} reader)
            name (when (and (seq? form) (= 'ns (first form))) (second form))
            links (literal-links (slurp path))]
        (when-not (symbol? name)
          (throw (ex-info "Expected ns form" {:path path})))
        {:name name
         :deps (into (->> (drop 2 form)
                          (filter #(and (seq? %) (#{:require :use :require-macros} (first %))))
                          (mapcat rest)
                          (mapcat libspec-names)
                          set)
                     (:deps links))
         :packages (:packages links)}))))


(defn- clojure-files
  []
  (concat (fs/glob "src" "**/*.clj")
          (fs/glob "test" "**/*.clj")))


(defn- namespace-graph
  []
  (reduce (fn [graph path]
            (let [path (str path)
                  entry (namespace-entry path)]
              (when (contains? (:by-name graph) (:name entry))
                (throw (ex-info "Duplicate namespace" {:path path :name (:name entry)})))
              (-> graph
                  (assoc-in [:by-path path] entry)
                  (assoc-in [:by-name (:name entry)] path)
                  (update :package-consumers
                          (fn [consumers]
                            (reduce (fn [r [package module]]
                                      (update r [package module] (fnil conj #{})
                                              (:name entry)))
                                    consumers (:packages entry))))
                  (update :reverse
                          (fn [consumers]
                            (reduce (fn [r dep]
                                      (update r dep (fnil conj #{}) (:name entry)))
                                    consumers (:deps entry)))))))
          {:by-path {} :by-name {} :reverse {} :package-consumers {}}
          (clojure-files)))


(defn- dependency-distances
  [reverse-graph sources]
  (loop [distances (zipmap sources (repeat 0))
         frontier (set sources)
         depth 1]
    (if (empty? frontier)
      distances
      (let [next-frontier (->> frontier
                               (mapcat #(get reverse-graph %))
                               (remove #(contains? distances %))
                               set)]
        (recur (into distances (map #(vector % depth)) next-frontier)
               next-frontier
               (inc depth))))))


(defn- test-layers
  "All reachable test namespaces, grouped by shortest require distance.
   The Kaocha runner completes each layer before starting the next. A parse
   failure removes hints altogether; the unchanged full suite still runs."
  [changed kind]
  (when changed
    (try
      (let [{:keys [by-path package-consumers] consumers :reverse}
            (namespace-graph)
            sources (->> changed
                         (filter #(or (str/starts-with? % "src/")
                                      (str/starts-with? % "test/")))
                         (filter #(str/ends-with? % ".clj"))
                         (keep #(get-in by-path [% :name]))
                         set)
            package-sources (->> changed
                                 (keep (fn [path]
                                         (when-let [[_ package module]
                                                    (re-matches
                                                      #"resources/packages/([^/]+)/([^/]+)/impls\.clj"
                                                      path)]
                                           (get package-consumers [package module]))))
                                 (mapcat identity)
                                 set)
            distances (dependency-distances consumers
                                            (into sources package-sources))
            test-entries (->> by-path
                              (filter (fn [[path _]]
                                        (and (str/starts-with? path "test/")
                                             (str/ends-with? path "_test.clj"))))
                              (keep (fn [[path {:keys [name]}]]
                                      (when-let [depth (get distances name)]
                                        (when (focused-namespace path kind)
                                          [depth (str name)]))))
                              (sort-by (juxt first second)))]
        (->> test-entries
             (partition-by first)
             (mapv (fn [layer] (mapv second layer)))))
      (catch Exception e
        (println (str "⚠ Clojure namespace graph unavailable: " (ex-message e)
                      " — running the full suite without early hints"))
        []))))


(defn unit-layers
  [changed]
  (test-layers changed :unit))


(defn integration-layers
  [changed]
  (test-layers changed :integration))


(defn unit-first
  "All reachable unit namespaces in dependency-distance order."
  [changed]
  (when changed (vec (mapcat identity (unit-layers changed)))))


(defn integration-first
  "All reachable integration namespaces in dependency-distance order."
  [changed]
  (when changed (vec (mapcat identity (integration-layers changed)))))


(defn plan
  [changed]
  (let [unit-groups (unit-layers changed)
        integration-groups (integration-layers changed)]
    {:runtime (runtime-first changed)
     :unit (vec (mapcat identity unit-groups))
     :unit-layers unit-groups
     :integration (vec (mapcat identity integration-groups))
     :integration-layers integration-groups
     :browser (browser-first changed)}))
