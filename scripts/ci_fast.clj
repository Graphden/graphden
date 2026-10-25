(ns ci-fast
  "Small changed-file checks before expensive work; full lint remains mandatory."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [babashka.tasks :as tasks]
    [ci-report :as report]
    [ci-select :as select]
    [ci-staged :as staged]
    [clojure.edn :as edn]
    [clojure.string :as str]))


(def clj-scan-paths
  ["src" "test" "development" "resources/packages" "scripts" "dev/wtq" "build.clj" "bb.edn"])


(defn syntax-commands
  "Syntax guards outside the narrower biome and runtime test scan sets."
  [paths]
  (let [paths (sort paths)]
    (vec (concat
           (for [path paths :when (re-find #"\.(js|mjs|cjs)$" path)] ["node" "--check" path])
           (for [path paths :when (str/ends-with? path ".json")]
             ["node" "-e" "JSON.parse(require('node:fs').readFileSync(process.argv[1], 'utf8'))" path])))))


(defn commands
  ([paths native-style? native-kondo? native-shellcheck?]
   (commands paths paths native-style? native-kondo? native-shellcheck?))
  ([paths changed native-style? native-kondo? native-shellcheck?]
   (let [paths (sort paths)
         changed (sort changed)
         clj (filter #(re-find #"\.(clj|cljc|cljs|edn|bb)$" %) paths)
         docs (filter #(str/ends-with? % ".md") paths)
         shell (filter #(or (str/ends-with? % ".sh") (= % "dev/wtq/wt")) paths)
         web (filter #(re-find #"^resources/packages/app/(editor|ui-preview)/.*\.(js|mjs|jsonc?)$" %) paths)
         css (filter #(or (re-find #"^resources/packages/app/(editor|ui-preview)/.*\.css$" %)
                          (= % "scripts/devtour/tour.css")) paths)
         graph? (some #(re-find #"^resources/packages/.+/fns\.edn$" %) changed)
         plan-input? (some #(or (re-find #"^resources/packages/.+/(fns\.edn|impls\.clj)$" %)
                                (re-find #"^resources/packages/app/ui-preview/builtin-plans\.(json|js)$" %)
                                (re-find #"^tools/ui_preview/(builtin_plans|generate_builtin_plans)\.clj$" %)) changed)
         devtour-input? (some #(or (re-find #"^(src|resources/packages|development/src)/.*\.(clj|cljc|cljs|edn|js)$" %)
                                   (= % "docs/devtour/tour.edn")) changed)]
     (vec (concat
            (when (seq clj)
              [(into ["clojure" "-M:splint"] clj)
               (into (if native-style? ["cljstyle" "check"] ["clojure" "-M:cljstyle" "check"]) clj)
               (into (if native-kondo? ["clj-kondo" "--lint"] ["clojure" "-M:kondo" "--lint"]) clj)])
            (syntax-commands paths)
            (for [path shell] ["bash" "-n" path])
            (when (and native-shellcheck? (seq shell)) [(into ["shellcheck"] shell)])
            (when (seq web) [(into ["npx" "--no-install" "biome" "check"] web)])
            (when (seq css) [(into ["npx" "--no-install" "stylelint"] css)])
            (when (seq docs) [(into ["npx" "--no-install" "markdownlint-cli2" "--no-globs"] docs)])
            ;; A graph edit can introduce a corpus-wide type alias collision
            ;; even when its own plan compiles. Check before building a live
            ;; instance; the normal CI roster still runs the same sweep once.
            (when graph? [["bb" "graph-lint"] ["bb" "reachability"]
                          ["bb" "type-sweep"]])
            (when devtour-input? [["bb" "devtour-check"]])
            (when plan-input? [["clojure" "-M:dev" "tools/ui_preview/generate_builtin_plans.clj" "--check"]]))))))


(defn default-base
  []
  (let [result (p/shell {:out :string :err :string :continue true}
                        "git" "merge-base" "HEAD" "develop")]
    (if (zero? (:exit result)) (str/trim (:out result)) "HEAD")))


(defn lint-paths
  "Canonical scanner roots plus changed Clojure/EDN files outside those roots.
   Paths remain separate arguments, including spaces and shell metacharacters."
  [roots base]
  (let [base (if (str/blank? base) (default-base) base)
        changed (select/changed-files base)]
    (when-not changed (throw (ex-info "Cannot determine extra lint scope" {:base base})))
    (into (vec roots)
          (comp (filter #(re-find #"\.(clj|cljc|cljs|edn|bb)$" %))
                (filter fs/regular-file?)
                (remove (fn [path] (some #(or (= path %) (str/starts-with? path (str % "/"))) roots)))
                (distinct))
          (sort changed))))


(defn run-syntax!
  [base]
  (let [base (if (str/blank? base) (default-base) base)
        changed (select/changed-files base)]
    (when-not changed (throw (ex-info "Cannot determine syntax scope" {:base base})))
    (doseq [command (syntax-commands (filter fs/regular-file? changed))]
      (println "syntax:" (str/join " " command))
      (apply p/shell command))))


(defn run-command!
  "Use Babashka's embedded Clojure CLI when only bb and Java are installed."
  [command]
  (if (= "clojure" (first command))
    (apply tasks/clojure (rest command))
    (apply p/shell command)))


(defn run-checks!
  [base]
  (let [base (or base (default-base))
        changed (select/changed-files base)
        _ (when-not changed (throw (ex-info "Cannot determine changed-file preflight scope" {:base base})))
        paths (filter fs/regular-file? changed)
        pinned (get-in (edn/read-string (slurp "deps.edn"))
                       [:aliases :kondo :extra-deps 'clj-kondo/clj-kondo :mvn/version])
        native-kondo? (and (fs/which "clj-kondo")
                           (= pinned (second (re-find #"v([0-9.]+)"
                                                      (:out (p/shell {:out :string} "clj-kondo" "--version"))))))
        planned-commands (commands paths changed (boolean (fs/which "cljstyle")) native-kondo? (boolean (fs/which "shellcheck")))
        {:keys [files reason]} (staged/affected :runtime (set changed) (staged/roster :runtime))
        sha (str/trim (:out (p/shell {:out :string} "git" "rev-parse" "HEAD")))
        started (System/currentTimeMillis)
        directory "target/preflight"
        path (str directory "/" sha "-" started ".edn")
        state (atom {:schema 1 :complete false :sha sha :base base
                     :pid (java.lang.ProcessHandle/.pid (java.lang.ProcessHandle/current))
                     :changed (vec (sort changed)) :runtime-reason reason
                     :started-at-ms started :commands []})
        save! #(do (report/write-atomic! path @state)
                   (report/write-atomic! (str directory "/latest.edn") @state))
        run-item! (fn [command cwd]
                    (let [start (System/currentTimeMillis)
                          item {:argv command :cwd cwd :started-at-ms start}]
                      (println "candidate preflight:" (str/join " " command)
                               (if cwd (str "in " cwd) ""))
                      (swap! state update :commands conj item)
                      (save!)
                      (try
                        (if cwd
                          (apply p/shell {:dir cwd} command)
                          (run-command! command))
                        (swap! state update :commands update (dec (count (:commands @state)))
                               assoc :exit 0 :duration-ms (- (System/currentTimeMillis) start))
                        (catch Exception error
                          (swap! state update :commands update (dec (count (:commands @state)))
                                 assoc :exit (or (:exit (ex-data error)) 1)
                                 :duration-ms (- (System/currentTimeMillis) start))
                          (save!)
                          (throw error)))
                      (save!)))]
    (fs/create-dirs directory)
    (save!)
    (try
      (doseq [command planned-commands] (run-item! command nil))
      (println "runtime preflight:" (count files) "file(s), reason" (name reason))
      (doseq [runtime-path files]
        (run-item! ["node" (str (fs/file-name runtime-path))] "tools/runtime-test"))
      (swap! state assoc :complete true :exit 0 :finished-at-ms (System/currentTimeMillis))
      (save!)
      (println "candidate preflight: PASS, receipt" path)
      (catch Exception error
        (swap! state assoc :complete true :exit 1 :finished-at-ms (System/currentTimeMillis))
        (save!)
        (println "candidate preflight: FAIL, receipt" path)
        (throw error)))))


(defn show-status!
  []
  (let [path "target/preflight/latest.edn"]
    (if-not (fs/exists? path)
      (println "candidate preflight: no receipt")
      (let [{:keys [sha complete exit started-at-ms finished-at-ms pid] :as receipt} (edn/read-string (slurp path))
            items (:commands receipt)
            passed (count (filter #(and (some? (:exit %)) (zero? (:exit %))) items))
            current (last (filter #(nil? (:exit %)) items))
            alive? (and pid (some-> (java.lang.ProcessHandle/of pid) (.isPresent)))]
        (println "candidate preflight:" (cond
                                          (and (not complete) alive?) "RUNNING"
                                          (not complete) "INTERRUPTED"
                                          (zero? exit) "PASS"
                                          :else "FAIL")
                 "sha" sha "checks" passed "/" (count items)
                 "started" started-at-ms "finished" finished-at-ms)
        (when current (println "current:" (:argv current)))
        (println "receipt:" path)))))
