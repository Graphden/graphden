(ns ci-staged
  "Exact file partition for checks whose single-file execution is equivalent to
   their directory/suite execution. Each phase records its own candidate-bound
   receipt; the remainder refuses a missing or changed affected-phase proof."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-report :as report]
    [ci-resume :as resume]
    [ci-select :as select]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]))


(def runtime-impact
  {"resources/packages/app/editor/editor-root.js"
   ["editor-root.test.js" "tour-picker.test.js"]
   "resources/packages/app/editor/editor-edit-modes.js"
   ["inline-edit.test.js" "path-value-lifecycle.test.js"]
   "resources/packages/web/runtime/graphden-popover.js"
   ["popover.test.js" "inline-edit.test.js" "path-value-lifecycle.test.js"
    "row-actions-placement.test.js" "tour-native-motion.test.js"]
   "resources/packages/app/editor/editor-tour-catalog-graph.js"
   ["tour-picker.test.js" "tour-end.test.js"]})


(def shared-inputs
  #{"bb.edn" "package.json" "package-lock.json" "biome.jsonc"
    "scripts/ci_staged.clj" "scripts/ci_select.clj" "scripts/ci_resume.clj"
    "scripts/checks.edn"})


(defn roster
  [kind]
  (case kind
    :biome (->> ["resources/packages/app/editor" "resources/packages/app/ui-preview"]
                (mapcat #(fs/glob % "**"))
                (filter #(and (fs/regular-file? %) (str/ends-with? (str %) ".js")))
                (map str) (concat ["scripts/devtour/tour.js"]) sort vec)
    :runtime (->> (fs/glob "tools/runtime-test" "*.test.js") (map str) sort vec)))


(defn affected
  "Unknown dynamic editor dependencies widen the affected stage to every
   runtime test. A direct test edit selects that file without guessing by name."
  [kind changed all]
  (let [all-set (set all)]
    (cond
      (nil? changed) {:files all :reason :unknown-diff}
      (= kind :biome)
      (if (some shared-inputs changed)
        {:files all :reason :shared-config}
        {:files (filterv (set changed) all) :reason :changed-files})

      (= kind :runtime)
      (let [runtime-source? #(or (str/starts-with? % "resources/packages/app/editor/")
                                 (str/starts-with? % "resources/packages/app/ui-preview/")
                                 (str/starts-with? % "resources/packages/web/runtime/")
                                 (str/starts-with? % "tools/runtime-test/"))
            dynamic? (some #(or (contains? shared-inputs %)
                                (and (runtime-source? %)
                                     (not (contains? all-set %))
                                     (not (contains? runtime-impact %)))) changed)]
        (if dynamic?
          {:files all :reason :unknown-dynamic-dependency}
          {:files (->> changed
                       (mapcat #(concat (when (contains? all-set %) [%])
                                        (map (fn [name] (str "tools/runtime-test/" name))
                                             (get runtime-impact %))))
                       (filter all-set) set sort vec)
           :reason :declared-dependency})))))


(defn make-plan
  [kind since]
  (let [all (roster kind)
        changed (when (seq since) (select/changed-files since))
        {:keys [files reason]} (affected kind changed all)
        selected (set files)
        rest-files (filterv #(not (selected %)) all)]
    (when-not (and (= (count all) (+ (count files) (count rest-files)))
                   (= (count all) (count (set all)))
                   (empty? (set/intersection selected (set rest-files))))
      (throw (ex-info "Staged check partition does not cover roster exactly" {:kind kind})))
    {:schema 1 :kind kind :sha (resume/git-output "rev-parse" "HEAD")
     :tree (resume/git-output "rev-parse" "HEAD^{tree}")
     :base (when (seq since)
             (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                               "git" "rev-parse" since)]
               (when (zero? exit) (str/trim out))))
     :since since :changed (vec (sort changed)) :reason reason
     :roster all :affected files :remainder rest-files}))


(defn- command
  [kind paths]
  (case kind
    :biome (into ["npx" "--no-install" "biome" "check"] paths)
    :runtime ["node" (str (fs/file-name (first paths)))]))


(defn- command-cwd
  [kind paths]
  (when (= kind :runtime)
    (str (fs/parent (first paths)))))


(defn- expected-batches
  [plan phase]
  (let [paths (get plan phase)]
    (if (= :biome (:kind plan))
      (if (seq paths) [paths] [])
      (mapv vector paths))))


(defn- valid-phase-receipt?
  [receipt plan phase toolchain]
  (and (= 1 (:schema receipt))
       (= phase (:phase receipt))
       (true? (:complete receipt))
       (number? (:exit receipt))
       (zero? (:exit receipt))
       (= plan (:plan receipt))
       (= toolchain (:toolchain receipt))
       (= (mapv (fn [paths]
                  {:paths paths
                   :argv (command (:kind plan) paths)
                   :cwd (command-cwd (:kind plan) paths)})
                (expected-batches plan phase))
          (mapv #(select-keys % [:paths :argv :cwd]) (:commands receipt)))
       (every? #(and (number? (:exit %)) (zero? (:exit %)))
               (:commands receipt))))


(defn valid-affected-receipt?
  [receipt plan toolchain]
  (valid-phase-receipt? receipt plan :affected toolchain))


(defn- receipt-path
  [plan phase]
  (str (fs/path (resume/git-output "rev-parse" "--git-path" "graphden-staged")
                (:sha plan) (str (name (:kind plan)) "-" (name phase) ".edn"))))


(defn- invoke!
  [kind paths]
  (let [argv (command kind paths)
        cwd (command-cwd kind paths)
        start (System/currentTimeMillis)
        result (apply p/shell (merge {:continue true}
                                     (when cwd {:dir cwd})) argv)]
    {:paths paths :argv argv :cwd cwd :started-at-ms start
     :duration-ms (- (System/currentTimeMillis) start) :exit (:exit result)}))


(defn- toolchain
  [kind]
  {:node (str/trim (:out (p/shell {:out :string} "node" "--version")))
   :env {:PATH (System/getenv "PATH")
         :RUN_EDIT_TESTS (System/getenv "RUN_EDIT_TESTS")}
   :biome (when (= kind :biome)
            (str/trim (:out (p/shell {:out :string}
                                     "npx" "--no-install" "biome" "--version"))))})


(defn run-phase!
  [kind phase since]
  (let [plan (make-plan kind since)
        paths (get plan phase)
        path (receipt-path plan phase)
        source (receipt-path plan :affected)
        current-toolchain (toolchain kind)
        prior (when (and (= phase :remainder) (fs/exists? source))
                (edn/read-string (slurp source)))]
    (when (and (= phase :remainder)
               (not (valid-affected-receipt? prior plan current-toolchain)))
      (throw (ex-info "Affected stage missing, failed or for another candidate/roster/command/environment"
                      {:kind kind :receipt source})))
    (fs/create-dirs (fs/parent path))
    (let [state (atom {:schema 1 :complete false :exit nil :plan plan
                       :phase phase :started-at-ms (System/currentTimeMillis)
                       :pid (java.lang.ProcessHandle/.pid (java.lang.ProcessHandle/current))
                       :toolchain current-toolchain
                       :commands []})]
      (report/write-atomic! path @state)
      (println "staged" (name kind) (name phase) (count paths) "of" (count (:roster plan))
               "reason" (name (:reason plan)) "journal" path)
      (doseq [batch (if (= kind :biome) (when (seq paths) [paths]) (mapv vector paths))]
        (let [result (invoke! kind batch)]
          (swap! state update :commands conj result)
          (report/write-atomic! path @state)
          (when-not (zero? (:exit result))
            (swap! state assoc :complete true :exit (:exit result)
                   :finished-at-ms (System/currentTimeMillis))
            (report/write-atomic! path @state)
            (throw (ex-info "Staged check failed" result)))))
      (swap! state assoc :complete true :exit 0 :finished-at-ms (System/currentTimeMillis))
      (when (= phase :remainder)
        (when-not (valid-phase-receipt? @state plan :remainder current-toolchain)
          (throw (ex-info "Remainder receipt does not prove expected commands"
                          {:kind kind :receipt path})))
        (when-not (= (set (:roster plan))
                     (set (concat (mapcat :paths (:commands prior))
                                  (mapcat :paths (:commands @state)))))
          (throw (ex-info "Staged receipts do not cover the complete roster" {:kind kind}))))
      (report/write-atomic! path @state))))


(defn phase-status
  [plan phase receipt current-toolchain alive?]
  (cond
    (nil? receipt) :pending
    (or (not= plan (:plan receipt))
        (not= phase (:phase receipt))
        (not= current-toolchain (:toolchain receipt))) :stale
    (not (:complete receipt)) (if (alive? (:pid receipt)) :running :interrupted)
    (valid-phase-receipt? receipt plan phase current-toolchain) :passed
    :else :failed))


(defn- live-pid?
  [pid]
  (and (integer? pid)
       (some-> (java.lang.ProcessHandle/of pid)
               (java.util.Optional/.orElse nil)
               (java.lang.ProcessHandle/.isAlive))))


(defn- read-receipt
  [path]
  (when (fs/exists? path)
    (try (edn/read-string (slurp path))
         (catch Exception _ {:invalid true}))))


(defn status!
  []
  (let [sha (resume/git-output "rev-parse" "HEAD")
        dir (fs/path (resume/git-output "rev-parse" "--git-path" "graphden-staged") sha)
        since-env (System/getenv "GRAPHDEN_CI_SINCE")]
    (doseq [kind [:biome :runtime]
            :let [a-path (str (fs/path dir (str (name kind) "-affected.edn")))
                  b-path (str (fs/path dir (str (name kind) "-remainder.edn")))
                  a (read-receipt a-path)
                  b (read-receipt b-path)
                  since (or since-env (get-in a [:plan :since]) (get-in b [:plan :since]))]
            :when (or a b (seq since))]
      (let [plan (make-plan kind since)
            current-toolchain (try (toolchain kind) (catch Exception _ nil))
            a-status (phase-status plan :affected a current-toolchain live-pid?)
            b-status (if (and (nil? b) (not= :passed a-status))
                       :blocked
                       (phase-status plan :remainder b current-toolchain live-pid?))]
        (doseq [[phase receipt state path] [[:affected a a-status a-path]
                                            [:remainder b b-status b-path]]]
          (let [passed (if (= state :passed) (count (get plan phase))
                           (reduce + (map (comp count :paths)
                                          (filter #(and (number? (:exit %))
                                                        (zero? (:exit %)))
                                                  (:commands receipt)))))]
            (println (name kind) (name phase) "sha" (subs sha 0 8)
                     "status" (name state) "passed" passed "/" (count (get plan phase))
                     "pending" (- (count (get plan phase)) passed)
                     "reason" (name (:reason plan)) "journal" path)))))
    (when (and (not (fs/exists? dir)) (not (seq since-env)))
      (println "No staged journal for" sha "; use bb wt status for a running train"))))
