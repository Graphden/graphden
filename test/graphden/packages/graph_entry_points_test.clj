(ns graphden.packages.graph-entry-points-test
  "Guard for the Clojure → graph boundary registered in
   `tools/graph-reachability.edn`.

   `src/` mostly reaches the graph by fn-id. A handful of places reach it
   by NAME through `executor/execute-by-name` — the accounts pages and
   emails, the value-form presentational shells, the boot-time router.
   Those names are string literals in Clojure and keywords in `fns.edn`,
   with nothing but this test between them: rename a fn-def and the call
   site keeps compiling, keeps linting, and fails when a user opens the
   page.

   Two assertions, both mechanical:

   1. every registered name still resolves in the loaded package set —
      catches the rename;
   2. every `src/` file that calls `execute-by-name` is accounted for in
      the registry (as a literal-name entry, a data-driven one, or the
      API surface itself) — catches a NEW by-name call site slipping in
      unregistered, which is how the registry would rot.

   The same registry seeds `tools/reachability_audit.clj`, so a missing
   entry also shows up there as a false dead-code verdict."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]))


(def ^:private registry-path "tools/graph-reachability.edn")


(defn- registry
  []
  (edn/read-string (slurp registry-path)))


(defn- loaded-fn-names
  "Every fn-def / base-fn name in the full first-party package set —
   the same set the reachability audit walks."
  []
  (let [{:keys [base-fn-defs fn-defs]}
        (loader/load-packages ["core" "storage" "web" "app-base" "app"
                               "registry" "mcp"])]
    (into (set (keys base-fn-defs))
          (keep :name)
          fn-defs)))


(defn- src-files-calling-execute-by-name
  []
  (->> (file-seq (io/file "src/graphden"))
       (filter #(and (java.io.File/.isFile %)
                     (str/ends-with? (java.io.File/.getName %) ".clj")))
       (filter #(str/includes? (slurp %) "execute-by-name"))
       (map #(str/replace (java.io.File/.getPath %) #"^\./" ""))
       set))


(deftest registered-entry-point-names-still-resolve-test
  ;; Covers both name-bearing sections: the by-name call sites AND the
  ;; deliberately-unreferenced vocabulary the audit holds back on their
  ;; behalf. An allowlist entry for a fn-def that no longer exists is
  ;; how the audit would start hiding a real leftover.
  (let [known (loaded-fn-names)
        {:keys [roots vocabulary]} (registry)
        registered (into (into {}
                               (map (fn [[n why]] [n why]))
                               vocabulary)
                         (mapcat (fn [[file names]]
                                   (map (fn [n] [n file]) names)))
                         roots)
        missing (into (sorted-map)
                      (remove (fn [[n _]] (contains? known n)))
                      registered)]
    (is (seq registered) "the registry is not empty — it seeds the audit too")
    (is (= {} (into {} missing))
        (str "fn-def(s) named from Clojure no longer exist in the graph. "
             "Either the name changed in fns.edn (update the call site AND "
             "the registry) or the fn-def was deleted (drop its registry "
             "entry): " (pr-str missing)))))


(deftest every-by-name-call-site-is-registered-test
  (let [{:keys [roots data-driven api-surface]} (registry)
        accounted (into (set (keys roots))
                        (concat (keys data-driven) api-surface))
        unregistered (sort (remove accounted (src-files-calling-execute-by-name)))]
    (is (empty? unregistered)
        (str "src file(s) call `execute-by-name` but are absent from "
             registry-path ". Add the literal names under :roots, or the "
             "file under :data-driven / :api-surface with a one-line "
             "reason: " (pr-str unregistered)))))


(deftest registry-files-still-exist-test
  (testing "a registry entry pointing at a moved/deleted file is dead weight"
    (let [{:keys [roots data-driven api-surface]} (registry)
          listed (into (set (keys roots))
                       (concat (keys data-driven) api-surface))
          gone (sort (remove #(java.io.File/.exists (io/file %)) listed))]
      (is (empty? gone)
          (str "registry lists file(s) that no longer exist: " (pr-str gone))))))
