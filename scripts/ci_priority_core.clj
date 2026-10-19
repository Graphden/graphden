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


(defn changed-paths
  "Use the same path set as CI scoping. A missing base yields no early hints;
   the full gate widens as usual."
  [base]
  (when (and (string? base) (not (str/blank? base)))
    (ci-select/changed-files base)))


(defn browser-first
  "Existing browser files directly changed, paired with an editor module, or
   sharing its specific filename stem. This only moves tests earlier; the shell
   runner validates the complete suite again before use."
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

                       (str/starts-with? path "resources/packages/app/editor/")
                       (let [basename (str (fs/file-name path))
                             stem (-> basename
                                      (str/replace #"^editor-" "")
                                      (str/replace #"\.(js|css)$" "")
                                      (str/replace #"-graph$" ""))
                             prefix (str "edit-" stem "-")]
                         (concat (get browser-by-editor basename)
                                 (filter #(or (= % (str "edit-" stem ".test.js"))
                                              (str/starts-with? % prefix))
                                         browser-files)))

                       :else nil)))
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


(defn- direct-unit-test
  [source]
  (condp re-matches source
    #"test/graphden/.+_test\.clj" source
    #"src/graphden/.+\.clj"
    (str "test/" (subs source (count "src/") (- (count source) 4)) "_test.clj")
    nil))


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


(defn unit-first
  "At most three exact path-paired unit namespaces. More matches would make
   this preflight a second broad suite; the full unit run remains required."
  [changed]
  (when changed
    (let [namespaces (->> changed
                          sort
                          (keep direct-unit-test)
                          (filter fs/exists?)
                          distinct
                          (map #(focused-namespace % :unit))
                          (remove nil?)
                          vec)]
      (if (<= (count namespaces) 3) namespaces []))))


(defn integration-first
  "One exact path-paired integration namespace for an early PG signal. The
   normal full integration gate remains mandatory after browser tests."
  [changed]
  (when changed
    (->> changed
         sort
         (keep direct-unit-test)
         (filter fs/exists?)
         distinct
         (keep #(focused-namespace % :integration))
         first
         vector
         (remove nil?)
         vec)))


(defn plan
  [changed]
  {:runtime (runtime-first changed)
   :unit (unit-first changed)
   :integration (integration-first changed)
   :browser (browser-first changed)})


(defn empty-focused-run?
  "Kaocha exits 0 when a focus filter selects no tests; reject that warning."
  [output]
  (boolean (re-find #"All [0-9]+ tests were skipped\.|No tests were found\."
                    output)))
