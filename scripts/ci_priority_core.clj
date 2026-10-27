(ns ci-priority-core
  "Conservative early-test hints. These only change order; the registered CI and
   browser suites still run in full. Unknown paths deliberately yield no hint."
  (:require
    [babashka.fs :as fs]
    [ci-select]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]))


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


(defn- script-definitions
  "Top-level browser globals. Only uniquely owned names form graph edges."
  [source]
  (->> (concat (map second (re-seq #"(?m)^(?:async )?(?:function|class|const|let|var)\s+([A-Za-z_$][\w$]*)\b"
                                   source))
               (map second (re-seq #"\b(?:window|globalThis)\.([A-Za-z_$][\w$]*)\s*="
                                   source)))
       (filter #(>= (count %) 7))
       set))


(defn- browser-selectors
  [source]
  (set (re-seq #"\b(?:gd|fn|node|execute|tour)-[a-z][a-z0-9-]{5,}\b" source)))


(defn- browser-source-graph
  ([]
   (let [scripts (mapv str (fs/glob "resources/packages" "**/*.js"))
         browser (mapv str (fs/glob "tools/browser-test" "*.js"))
         sources (into {} (map (fn [path] [path (slurp path)]) (concat scripts browser)))]
     (browser-source-graph sources scripts browser)))
  ([sources scripts browser]
   (let [owners (reduce (fn [acc path]
                          (reduce (fn [a identifier] (update a identifier (fnil conj #{}) path))
                                  acc (script-definitions (get sources path))))
                        {} scripts)
         unique-owners (into {} (keep (fn [[identifier paths]]
                                        (when (= 1 (count paths)) [identifier (first paths)])))
                             owners)
         selector-consumers (reduce (fn [acc path]
                                      (reduce (fn [a selector] (update a selector (fnil conj #{}) path))
                                              acc (browser-selectors (get sources path))))
                                    {} browser)
         reverse-graph (reduce-kv
                         (fn [graph path source]
                           (reduce (fn [g identifier]
                                     (if-let [owner (get unique-owners identifier)]
                                       (if (= owner path) g (update g owner (fnil conj #{}) path))
                                       g))
                                   graph
                                   (distinct (re-seq #"\b[A-Za-z_$][\w$]{6,}\b" source))))
                         {} sources)
         reverse-graph (reduce
                         (fn [graph path]
                           (reduce (fn [g selector]
                                     (let [consumers (get selector-consumers selector)]
                                       (if (<= (count consumers) 15)
                                         (update g path (fnil into #{}) consumers)
                                         g)))
                                   graph (browser-selectors (get sources path))))
                         reverse-graph scripts)]
     {:sources sources :reverse reverse-graph})))


(def ^:private cached-browser-source-graph
  ;; The CLI computes one immutable candidate plan per process. Tests may ask
  ;; for several changed paths in that same process.
  (memoize browser-source-graph))


(defn- component-roles
  "Read the shipped namespace→role contract without loading the JVM app."
  []
  (try
    (with-open [reader (java.io.PushbackReader.
                         (io/reader "src/graphden/ui/component_contracts.clj"))]
      (binding [*read-eval* false]
        (loop []
          (let [form (read {:eof nil} reader)]
            (cond
              (nil? form) {}
              (and (seq? form) (= 'def (first form))
                   (= 'component-contracts (second form)))
              (reduce-kv (fn [roles role contract]
                           (update roles (namespace (:reference contract))
                                   (fnil conj #{}) (name role)))
                         {} (last form))
              :else (recur))))))
    (catch Exception e
      (println (str "⚠ component roles unavailable: " (ex-message e)
                    " — using conservative browser hints"))
      {})))


(def ^:private cached-component-roles (memoize component-roles))


(defn- component-source-seeds
  "A component graph reaches JS and tests that name its shipped role in a
   runtime access. Plain word matches are too broad for names such as keymap."
  [sources roles]
  (let [accesses (for [role roles
                       :let [camel (str/replace role #"-([a-z])"
                                                (fn [[_ letter]] (str/upper-case letter)))]]
                   (re-pattern (str "(?:\\.plans\\." (java.util.regex.Pattern/quote camel)
                                    "\\b|(?:componentNow|gdLoadUIComponentRuntime|gdUIComponentFailed)\\(\\s*['\"]"
                                    (java.util.regex.Pattern/quote role) "['\"])")))]
    (set (keep (fn [[path source]]
                 (when (and (or (str/starts-with? path "resources/packages/")
                                (str/starts-with? path "tools/browser-test/"))
                            (some #(re-find % source) accesses))
                   path)) sources))))


(defn- browser-source-consumers
  [browser-files {:keys [sources] reverse-graph :reverse} path]
  (let [package-module (second (re-matches #"resources/packages/[^/]+/([^/]+)/fns\.edn" path))
        roles (when (and package-module (fs/exists? path))
                (try (get (cached-component-roles)
                          (:namespace (edn/read-string (slurp path))))
                     (catch Exception _ nil)))
        exact-seeds (when (seq roles) (component-source-seeds sources roles))
        module-tokens (when package-module
                        (set [package-module (str/replace package-module #"^ui-" "")]))
        seeds (cond
                (contains? sources path) #{path}
                (seq exact-seeds) exact-seeds
                (and package-module (str/includes? package-module "-"))
                (into #{} (keep (fn [[script source]]
                                  (when (and (str/starts-with? script "resources/packages/")
                                             (some #(str/includes? source %) module-tokens))
                                    script))) sources)
                :else #{})
        distances (dependency-distances reverse-graph seeds)
        reached (->> distances
                     (filter (fn [[candidate depth]]
                               (and (<= depth 3)
                                    (str/starts-with? candidate "tools/browser-test/"))))
                     (sort-by (juxt second first))
                     (map first))
        tests (set browser-files)
        direct (keep (fn [candidate]
                       (let [name (str (fs/file-name candidate))]
                         (when (contains? tests name) name))) reached)
        helpers (into #{} (map (comp str fs/file-name))
                      (remove #(contains? tests (str (fs/file-name %))) reached))]
    (concat direct (browser-local-consumers browser-files helpers))))


(def ^:private cached-package-impl-sources
  (memoize (fn []
             (into {} (map (fn [path] [(str path) (slurp (str path))]))
                   (fs/glob "resources/packages" "**/impls.clj")))))


(defn- browser-api-consumers
  "Follow a changed package module, or a Clojure source required by its impl,
   to the package's declared HTTP roots and browser tests that call them."
  [browser-files path]
  (let [modules (if (re-matches #"resources/packages/[^/]+/[^/]+/(?:fns\.edn|impls\.clj)" path)
                  [path]
                  (when-let [[_ source] (re-matches #"src/(graphden/.+)\.clj" path)]
                    (let [ns-name (-> source (str/replace "/" ".")
                                      (str/replace "_" "-"))]
                      (->> (cached-package-impl-sources)
                           (keep (fn [[impl body]]
                                   (when (str/includes? body (str "[" ns-name " ")) impl)))))))]
    (for [module-path modules
          :let [definitions (str/replace module-path #"(?:fns\.edn|impls\.clj)$" "fns.edn")]
          :when (fs/exists? definitions)
          :let [roots (->> (re-seq #":path\s+\"/([a-z][a-z0-9-]*)" (slurp definitions))
                           (map second) (filter #(>= (count %) 4)) set)
                markers (set (map #(str "/api/" %) roots))]
          file browser-files
          :when (let [source (slurp (str "tools/browser-test/" file))]
                  (some #(str/includes? source %) markers))]
      file)))


(defn changed-paths
  "Use the same path set as CI scoping. A missing base yields no early hints;
   the full gate widens as usual."
  [base]
  (when (and (string? base) (not (str/blank? base)))
    (ci-select/changed-files base)))


(defn browser-first
  "Existing browser files directly changed, reachable through JS globals,
   selectors and local helper imports, or sharing a filename stem. This only
   moves tests earlier; the shell runner validates the complete suite."
  [changed]
  (when changed
    (let [browser-files (->> (fs/glob "tools/browser-test" "edit-*.test.js")
                             (map (comp str fs/file-name))
                             sort
                             vec)
          graph (cached-browser-source-graph)]
      (->> (concat
             (->> changed
                  sort
                  (filter #(re-matches #"tools/browser-test/edit-[^/]+\.test\.js" %))
                  (map (comp str fs/file-name)))
             (mapcat #(browser-api-consumers browser-files %) (sort changed))
             (mapcat (fn [path]
                       (cond
                         (re-matches #"tools/browser-test/edit-[^/]+\.test\.js" path)
                         []

                         (re-matches #"tools/browser-test/[^/]+\.js" path)
                         (browser-local-consumers browser-files
                                                  #{(str (fs/file-name path))})

                         (str/starts-with? path "resources/packages/app/editor/")
                         (let [basename (str (fs/file-name path))
                               stem (-> basename
                                        (str/replace #"^editor-" "")
                                        (str/replace #"\.(js|css)$" "")
                                        (str/replace #"-graph$" ""))
                               prefix (str "edit-" stem "-")]
                           (concat (filter #(or (= % (str "edit-" stem ".test.js"))
                                                (str/starts-with? % prefix))
                                           browser-files)
                                   (browser-source-consumers browser-files graph path)))

                         :else (browser-source-consumers browser-files graph path)))
                     (sort changed)))
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
         :package-impl-consumer? (boolean (:ci/package-impl-consumer (meta name)))
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
                  (cond-> (:package-impl-consumer? entry)
                    (update :package-impl-consumers (fnil conj #{}) (:name entry)))
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
          {:by-path {} :by-name {} :reverse {} :package-consumers {}
           :package-impl-consumers #{}}
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
      (let [{:keys [by-path package-consumers package-impl-consumers]
             consumers :reverse}
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
            package-impl-sources (when (some #(re-matches
                                                #"resources/packages/[^/]+/[^/]+/impls\.clj"
                                                %)
                                             changed)
                                   package-impl-consumers)
            distances (dependency-distances consumers
                                            (into sources
                                                  (concat package-sources package-impl-sources)))
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
