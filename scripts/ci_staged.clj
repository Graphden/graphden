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
     :base (when (seq since) (resume/git-output "rev-parse" since))
     :since since :changed (vec (sort changed)) :reason reason
     :roster all :affected files :remainder rest-files}))


(defn valid-affected-receipt?
  [receipt plan toolchain]
  (and (= 1 (:schema receipt))
       (= :affected (:phase receipt))
       (true? (:complete receipt))
       (zero? (:exit receipt))
       (= plan (:plan receipt))
       (= toolchain (:toolchain receipt))
       (= (if (= :biome (:kind plan))
            (if (seq (:affected plan)) [(:affected plan)] [])
            (mapv vector (:affected plan)))
          (mapv :paths (:commands receipt)))
       (every? #(zero? (:exit %)) (:commands receipt))))


(defn- receipt-path
  [plan phase]
  (str (fs/path (resume/git-output "rev-parse" "--git-path" "graphden-staged")
                (:sha plan) (str (name (:kind plan)) "-" (name phase) ".edn"))))


(defn- command
  [kind paths]
  (case kind
    :biome (into ["npx" "--no-install" "biome" "check"] paths)
    :runtime ["node" (str (fs/file-name (first paths)))]))


(defn- invoke!
  [kind paths]
  (let [argv (command kind paths)
        start (System/currentTimeMillis)
        result (apply p/shell (merge {:continue true}
                                     (when (= kind :runtime) {:dir (str (fs/parent (first paths)))})) argv)]
    {:paths paths :argv argv :started-at-ms start
     :duration-ms (- (System/currentTimeMillis) start) :exit (:exit result)}))


(defn run-phase!
  [kind phase since]
  (let [plan (make-plan kind since)
        paths (get plan phase)
        path (receipt-path plan phase)
        source (receipt-path plan :affected)
        toolchain {:node (str/trim (:out (p/shell {:out :string} "node" "--version")))
                   :biome (when (= kind :biome)
                            (str/trim (:out (p/shell {:out :string}
                                                     "npx" "--no-install" "biome" "--version"))))}
        prior (when (and (= phase :remainder) (fs/exists? source))
                (edn/read-string (slurp source)))]
    (when (and (= phase :remainder)
               (not (valid-affected-receipt? prior plan toolchain)))
      (throw (ex-info "Affected stage missing, failed or for another candidate/roster"
                      {:kind kind :receipt source})))
    (fs/create-dirs (fs/parent path))
    (let [state (atom {:schema 1 :complete false :exit nil :plan plan
                       :phase phase :started-at-ms (System/currentTimeMillis)
                       :toolchain toolchain
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
        (when-not (= (set (:roster plan))
                     (set (concat (mapcat :paths (:commands prior))
                                  (mapcat :paths (:commands @state)))))
          (throw (ex-info "Staged receipts do not cover the complete roster" {:kind kind}))))
      (report/write-atomic! path @state))))


(defn status!
  []
  (let [sha (resume/git-output "rev-parse" "HEAD")
        dir (fs/path (resume/git-output "rev-parse" "--git-path" "graphden-staged") sha)
        paths (if (fs/exists? dir) (sort (fs/glob dir "*.edn")) [])]
    (when (empty? paths) (println "No staged journal for" sha))
    (doseq [path paths]
      (let [{:keys [complete exit phase plan commands]} (edn/read-string (slurp (str path)))]
        (println (name (:kind plan)) (name phase) "sha" (subs sha 0 8)
                 "files" (reduce + (map (comp count :paths) commands)) "/" (count (get plan phase))
                 "remaining" (- (count (get plan phase)) (reduce + (map (comp count :paths) commands)))
                 "status" (cond (not complete) :running (zero? exit) :passed :else :failed)
                 "reason" (name (:reason plan)) "journal" path)))))
