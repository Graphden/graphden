(ns ci-fast
  "Small changed-file checks before expensive work; full lint remains mandatory."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-select :as select]
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
  [paths native-style? native-kondo? native-shellcheck?]
  (let [paths (sort paths)
        clj (filter #(re-find #"\.(clj|cljc|cljs|edn|bb)$" %) paths)
        docs (filter #(str/ends-with? % ".md") paths)
        shell (filter #(or (str/ends-with? % ".sh") (= % "dev/wtq/wt")) paths)
        web (filter #(re-find #"^resources/packages/app/(editor|ui-preview)/.*\.(js|mjs|jsonc?)$" %) paths)
        css (filter #(or (re-find #"^resources/packages/app/(editor|ui-preview)/.*\.css$" %)
                         (= % "scripts/devtour/tour.css")) paths)]
    (vec (concat
           (when (seq clj)
             [(into (if native-style? ["cljstyle" "check"] ["clojure" "-M:cljstyle" "check"]) clj)
              (into (if native-kondo? ["clj-kondo" "--lint"] ["clojure" "-M:kondo" "--lint"]) clj)])
           (syntax-commands paths)
           (for [path shell] ["bash" "-n" path])
           (when (and native-shellcheck? (seq shell)) [(into ["shellcheck"] shell)])
           (when (seq web) [(into ["npx" "--no-install" "biome" "check"] web)])
           (when (seq css) [(into ["npx" "--no-install" "stylelint"] css)])
           (when (seq docs) [(into ["npx" "--no-install" "markdownlint-cli2" "--no-globs"] docs)])))))


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


(defn run-checks!
  [base]
  (let [changed (select/changed-files (or base (default-base)))
        _ (when-not changed (throw (ex-info "Cannot determine changed-file preflight scope" {:base base})))
        paths (filter fs/regular-file? changed)
        pinned (get-in (edn/read-string (slurp "deps.edn"))
                       [:aliases :kondo :extra-deps 'clj-kondo/clj-kondo :mvn/version])
        native-kondo? (and (fs/which "clj-kondo")
                           (= pinned (second (re-find #"v([0-9.]+)"
                                                      (:out (p/shell {:out :string} "clj-kondo" "--version"))))))]
    (doseq [command (commands paths (boolean (fs/which "cljstyle")) native-kondo? (boolean (fs/which "shellcheck")))]
      (println "changed-file preflight:" (str/join " " command))
      (apply p/shell command))))
