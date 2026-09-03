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


(defn corpus-findings
  "Lint the loaded corpus."
  []
  (let [{:keys [base-fn-defs fn-defs]} (loader/load-packages packages)]
    (lint/lint fn-defs {:base-fn-names (keys base-fn-defs)
                        :roots (registry-roots)})))


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


(defn -main
  [& _]
  (let [{:keys [text ok?]} (report (corpus-findings))]
    (println text)
    (System/exit (if ok? 0 1))))
