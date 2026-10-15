(ns graphden.lint.corpus
  "The graph lint over the first-party package corpus — `bb graph-lint`.

   Loads every shipped package through the same loader the system
   boots from (no DB), runs `graphden.lint.core/lint`, prints the
   findings, and exits non-zero on a warning that is not allowlisted —
   or on an allowlist entry that no longer fires (the same two-way
   contract as the type-check sweep's allowlist: tolerance is
   explicit and expires with the finding).

   Exemptions for `:unreferenced-private` come from
   `tools/graph-reachability.edn` — the registry of fn-defs `src/`
   runs by name, plus the starter vocabulary."
  (:gen-class)
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [graphden.lint.core :as lint]
    [graphden.lint.similar :as similar]
    [graphden.packages.loader :as loader]))


(def packages
  "The full first-party set incl. the optional registry/mcp packages —
   without them anything only they reference reads as unreferenced."
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


(def allowed-warnings
  "Warnings the corpus tolerates, keyed by the finding's `:fns` with
   the reason. Empty is the goal; an entry that stops firing fails
   the gate so the list cannot rot."
  {})


(def ^:private registry-path
  "tools/graph-reachability.edn")


(defn registry-roots
  "Fn NAMES the reachability registry declares as entered from outside
   the graph — `:roots` and `:registry-fns` (per src file) and
   `:vocabulary`."
  []
  (let [{:keys [roots registry-fns vocabulary]} (edn/read-string (slurp registry-path))]
    (-> #{}
        (into (mapcat val) roots)
        (into (mapcat val) registry-fns)
        (into (keys vocabulary)))))


(defn- corpus-findings-from
  [{:keys [base-fn-defs fn-defs]}]
  (let [base-names (keys base-fn-defs)]
    (lint/lint fn-defs {:base-fn-names base-names
                        :roots (into (registry-roots)
                                     (lint/names-referenced-by fn-defs base-names
                                                               (vals base-fn-defs)))
                        :check-public? true})))


(defn corpus-findings
  "Lint the loaded corpus."
  []
  (corpus-findings-from (loader/load-packages packages)))


(defn similar-findings
  "Opt-in, informational structural template candidates from the same corpus."
  [{:keys [base-fn-defs fn-defs]}]
  (similar/analyze fn-defs {:base-fn-names (keys base-fn-defs)}))


(defn- format-finding
  [{:keys [severity rule message]}]
  (format "%-7s %-28s %s" (name severity) (name rule) message))


(defn report
  "Human report + gate verdict for a findings vector. Returns
   `{:text :ok?}`."
  [findings]
  (let [warnings (lint/warnings findings)
        unexpected (remove #(contains? allowed-warnings (:fns %)) warnings)
        firing (into #{} (map :fns) warnings)
        stale (remove firing (keys allowed-warnings))
        counts (frequencies (map :severity findings))
        lines (concat
                (map format-finding findings)
                [""
                 (str "graph-lint: " (count findings) " findings — "
                      (get counts :warning 0) " warnings, "
                      (get counts :info 0) " info")]
                (when (seq stale)
                  [(str "STALE allowlist entries (no longer fire — remove them): "
                        (str/join ", " (map pr-str stale)))])
                (when (seq unexpected)
                  [(str (count unexpected) " warning(s) not allowlisted — fix the graph"
                        " or add them to graphden.lint.corpus/allowed-warnings with a reason")]))]
    {:text (str/join "\n" lines)
     :ok? (and (empty? unexpected) (empty? stale))}))


(defn- similar-report
  [{:keys [groups skipped subjects]} details?]
  (let [display (take 100 groups)
        reasons (->> skipped (map :skipped) frequencies
                     (sort-by (comp name key))
                     (map (fn [[reason occurrences]] (str (name reason) "=" occurrences))))]
    (str/join "\n"
              (concat
                [(str "similar-template: " (count groups) " candidate groups among "
                      (- subjects (count skipped)) "/" subjects " analyzed subjects; "
                      (count skipped) " bounded skips"
                      (when (seq reasons) (str " (" (str/join ", " reasons) ")")))]
                (map (fn [{:keys [fns difference-paths expanded-fns]}]
                       (str "INFO    " (pr-str fns) " — " expanded-fns " graph fns; differing literal paths "
                            (pr-str difference-paths)))
                     display)
                (when (> (count groups) 100)
                  [(str "... " (- (count groups) 100) " further candidates; pure analyzer returns all")])
                (when details?
                  (concat (map (fn [{:keys [skipped] :as entry}]
                                 (str "SKIP    " (pr-str (:fn entry)) " — " (name skipped)))
                               (take 50 skipped))
                          (when (> (count skipped) 50)
                            [(str "... " (- (count skipped) 50) " further bounded skips")])))))))


(defn -main
  [& args]
  (when-not (or (empty? args) (= ["--similar"] (vec args))
                (= ["--similar" "--details"] (vec args)))
    (binding [*out* *err*] (println "Usage: bb graph-lint [--similar [--details]]"))
    (System/exit 2))
  (let [corpus (loader/load-packages packages)
        {:keys [text ok?]} (report (corpus-findings-from corpus))]
    (println text)
    (when (= "--similar" (first args))
      (println)
      (println (similar-report (similar-findings corpus) (= "--details" (second args)))))
    (System/exit (if ok? 0 1))))
