;; Reachability audit — find unreachable composed fn-defs across all
;; packages, per graphden-fn-refactor skill §9.
;;
;; Run via:  bb reachability
;;
;; Source of truth = resources/packages/*/fns.edn (loaded via the same
;; loader the system uses). We do NOT need `bb deploy` because we
;; never touch the live DB; we walk the loader's parsed output.
;;
;; Output:
;; - Total fn-defs by kind
;; - Reachable count from roots (:web-server + examples/*)
;; - Unreachable composed (named) fn-defs — these are the dead-code
;;   candidates. Unreachable base-fns + type-rows are vocabulary,
;;   left alone per skill §9.

(ns tools.reachability-audit
  "Reachability over the package parser's ordinary namespace identities."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.lint.core :as lint]
    [graphden.packages.loader :as pkg]))


(defn- root-keys
  [index names]
  (into #{}
        (mapcat (fn [ref]
                  (if (namespace ref)
                    (let [[kind target] (lint/resolve-ref index ref)]
                      (when (= :fn-def kind) [(lint/fn-key target)]))
                    ;; Docs/tests mention bare names without an owning source
                    ;; namespace. Conservatively keep every matching identity.
                    (map lint/fn-key (get-in index [:by-name ref])))))
        names))


(defn- bfs-reachable
  [index roots]
  (loop [visited #{} frontier (vec roots)]
    (if (empty? frontier)
      visited
      (let [key (peek frontier) remaining (pop frontier)]
        (if (contains? visited key)
          (recur visited remaining)
          (let [definition (get-in index [:by-key key])
                refs (when definition (lint/references index definition))]
            (recur (conj visited key) (into remaining (remove visited refs)))))))))


(defn- composed?
  "A fn-def is COMPOSED (= deletable user-level construct) when it has
   a :parent / :parents. Bare base-fn-defs have :args + :return-type
   only (no parent). Type-rows have :type / :refine / :list / etc."
  [fd]
  (or (:parent fd) (:parents fd)))


(defn- type-row?
  [fd]
  (and (not (:parent fd))
       (not (:parents fd))
       (or (:type fd) (:refine fd) (:list fd) (:union fd) (:variant fd)
           (:map fd) (:tuple fd) (:fn-type fd))))


(defn- find-clj-files
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))


(def ^:private registry-file
  "Facts the structural walk can't derive — see the file's own header."
  "tools/graph-reachability.edn")


(defn- collect-entry-point-roots
  "Read `tools/graph-reachability.edn` — the registry of fn-defs that
   `src/` runs BY NAME. A grep can't find these reliably: the names live
   in a lookup map several lines from the `execute-by-name` call
   (`accounts/init.clj`), or reach it through a wrapper
   (`value_form/render-template`). Left to the structural walk they read
   as dead code — the whole `app.auth-pages` + `_form-*` surface did.

   `graph_entry_points_test` fails the build when a listed name stops
   resolving, or when a new by-name call site appears in a file the
   registry doesn't account for, so this list can't quietly rot."
  [fn-defs-by-name]
  (->> (:roots (edn/read-string (slurp registry-file)))
       vals
       (apply concat)
       (filter #(contains? fn-defs-by-name %))
       set))


(defn- collect-external-roots
  "fn-defs the sibling repos reach by QUALIFIED name from their own
   fns.edn: the registry's `:external` list, plus — when a sibling
   checkout is on disk (../graphden-cloud, ../graphden-tenancy, or two
   levels up from an agent worktree) — every `:ns.path/name` token in its
   `resources/packages/**/fns.edn`. The monorepo cannot see those
   consumers otherwise; deleting one of their targets as dead breaks the
   cloud's test job, not this repo's (2026-09-10)."
  [fn-defs-by-name]
  (let [listed (->> (:external (edn/read-string (slurp registry-file)))
                    vals (apply concat))
        siblings (for [up ["../" "../../"]
                       repo ["graphden-cloud" "graphden-tenancy"]
                       :let [d (io/file (str up repo "/resources/packages"))]
                       :when (.isDirectory d)]
                   d)
        scanned (for [d siblings
                      f (file-seq d)
                      :when (and (.isFile f) (= "fns.edn" (.getName f)))
                      [_ n] (re-seq #":((?:app|web|core|storage|registry|mcp)\.[a-z.-]+/[a-z0-9?!_-]+)" (slurp f))]
                  (keyword n))]
    (into #{} (filter #(contains? fn-defs-by-name %)) (concat listed scanned))))


(defn- collect-test-roots
  "Walk `test/` for ANY keyword token that names a fn-def — those are
   reachable through the test contract even though the static graph
   walk doesn't see them. Two real shapes the old `:name :x`-only
   pattern missed:

   - `(get all-name->id :materialize-package-version)` — the
     programmatic-API fn-defs `registry_test` drives through the
     executor by name (no HTTP route exists on purpose);
   - `(= :postgres-storage-impl (:name %))` — the pattern-exemplar
     asserts with the keyword BEFORE `(:name %)`, so the anchored
     form never matched its own motivating example.

   Matching every keyword ∩ fn-def-names is deliberately broad: a
   fn-def name mentioned in a test is a test contract either way,
   and the intersection filter keeps incidental keywords (`:ok`,
   `:status`, …) out unless they genuinely name a fn-def."
  [fn-defs-by-name]
  (let [names (set (keys fn-defs-by-name))
        pattern #":([a-zA-Z_][a-zA-Z0-9_-]*[a-zA-Z0-9_?!])"]
    (into #{}
          (mapcat (fn [f]
                    (let [src (slurp f)]
                      (keep (fn [[_ name-str]]
                              (let [kw (keyword name-str)]
                                (when (contains? names kw) kw)))
                            (re-seq pattern src)))))
          (find-clj-files "test/graphden"))))


(defn- definition-ref
  [fd]
  (keyword (or (:namespace fd) "") (name (:name fd))))


(defn- collect-example-roots
  [fn-defs]
  (into #{}
        (keep (fn [fd]
                (when (and (:namespace fd) (:name fd)
                           (str/starts-with? (:namespace fd) "examples"))
                  (definition-ref fd))))
        fn-defs))


(defn- collect-tests-namespace-roots
  "Every public fn-def living in a `tests` NAMESPACE is a root — the
   platform's own in-graph tests (`core.tests`, `web.tests`, …,
   docs/TESTS.md § Platform tests). Nothing references them by name:
   the runner DISCOVERS them by namespace segment and executes each
   one, exactly as it does a user's tests, and
   `packages.platform-tests-test` runs the whole set in CI. So the
   contract that makes them live is the same namespace predicate the
   server uses (`crud.test-runs/test-ns-path?` — segment match, never
   substring), mirrored here.

   `_`-private fns in those namespaces are NOT roots: they are
   scaffolding, reachable only through a test that uses them, which is
   precisely what the walk should prove (an orphaned helper stays a
   finding)."
  [fn-defs]
  (into #{}
        (keep (fn [fd]
                (let [ns-path (:namespace fd)
                      fn-name (:name fd)]
                  (when (and ns-path fn-name
                             (some #{"tests"} (str/split (str ns-path) #"\."))
                             (not (str/starts-with? (name fn-name) "_")))
                    (definition-ref fd)))))
        fn-defs))


(defn- collect-docs-roots
  "Scan `docs/*.md` for fn-name back-tick mentions — pattern docs
   (`PHILOSOPHY.md` etc.) reference `:wrap-element` / `wrap-style` /
   `wrap-script` style exemplars to document the composition style.
   Without this, those exemplars would be marked unreachable even
   though the documentation contract requires them to exist."
  [fn-defs-by-name]
  (let [names (set (keys fn-defs-by-name))
        ;; Match both bare and colon-prefixed back-ticked names.
        pattern #"`:?([a-zA-Z_][a-zA-Z0-9_-]*[a-zA-Z0-9_?!])`"
        md-files (->> (file-seq (io/file "docs"))
                      (filter #(and (.isFile %)
                                    (str/ends-with? (.getName %) ".md"))))]
    (into #{}
          (mapcat (fn [f]
                    (let [src (slurp f)]
                      (keep (fn [[_ name-str]]
                              (let [kw (keyword name-str)]
                                (when (contains? names kw) kw)))
                            (re-seq pattern src)))))
          md-files)))


(defn- collect-roots
  "Every root the BFS starts from, and a line per source so a surprising
   count is visible before the verdict. `:web-server` is the one hard-coded
   root (the process's own entry point); everything else comes from a
   contract some other layer enforces — the examples package, the
   by-name registry, Clojure tests, sibling repos, the docs, and the
   graph's own `tests` namespaces."
  [fn-defs all-by-name fn-def-by-name]
  (let [example-roots (collect-example-roots fn-defs)
        dynamic-roots (collect-entry-point-roots all-by-name)
        test-roots (collect-test-roots all-by-name)
        external-roots (collect-external-roots fn-def-by-name)
        docs-roots (collect-docs-roots all-by-name)
        graph-test-roots (collect-tests-namespace-roots fn-defs)]
    (println "External roots (sibling repos' qualified refs + registry :external):"
             external-roots)
    (println "Dynamic roots (from tools/graph-reachability.edn):"
             (count dynamic-roots))
    (println "Test roots (from `:name :the-fn` in test/):"
             (count test-roots))
    (println "Docs roots (from back-ticked names in docs/*.md):"
             (count docs-roots))
    (println "Graph-test roots (public fn-defs in a `tests` namespace):"
             (count graph-test-roots))
    (-> #{:web-server}
        (into example-roots)
        (into dynamic-roots)
        (into test-roots)
        (into external-roots)
        (into docs-roots)
        (into graph-test-roots))))


(defn analyze
  "Pure audit result. Definitions and traversal retain [namespace name]
   identities; only root-discovery mentions may be intentionally broad."
  [base-defs fn-defs root-names vocabulary]
  (let [index (lint/build-index (concat base-defs fn-defs) #{})
        roots (root-keys index root-names)
        reachable (bfs-reachable index roots)
        by-key (into {} (map (juxt lint/fn-key identity)) fn-defs)
        composed (filter #(composed? (val %)) by-key)
        type-rows (filter #(type-row? (val %)) by-key)
        vocabulary-by-key (into {}
                                (keep (fn [[ref reason]]
                                        (let [[kind target] (lint/resolve-ref index ref)]
                                          (when (= :ambiguous kind)
                                            (throw (ex-info "Ambiguous reachability vocabulary"
                                                            {:type :reachability/ambiguous-vocabulary :name ref})))
                                          (when (= :fn-def kind) [(lint/fn-key target) reason]))))
                                vocabulary)
        unreachable (remove #(reachable (key %)) composed)]
    {:roots roots :reachable reachable :definitions by-key
     :composed-count (count composed) :type-count (count type-rows)
     :vocabulary vocabulary-by-key
     :unreachable-composed (vec (sort-by key (remove #(contains? vocabulary-by-key (key %)) unreachable)))
     :held-vocabulary (filter #(contains? vocabulary-by-key (key %)) unreachable)
     :unreachable-type-rows (remove #(reachable (key %)) type-rows)}))


(defn- run-audit
  []
  (let [packages (pkg/load-packages ["core" "storage" "web" "app-base" "app" "registry" "mcp" "examples"])
        base-defs (keep (fn [[n fd]] (when n (assoc fd :name n))) (:base-fn-defs packages))
        fn-defs (:fn-defs packages)
        ;; These indexes discover root mentions only; no selected definition
        ;; or graph edge is ever looked up through a bare-name map.
        mentions (into {} (mapcat (fn [fd]
                                    [[(:name fd) true]
                                     [(keyword (or (:namespace fd) "") (name (:name fd))) true]]))
                       (concat base-defs fn-defs))
        root-names (collect-roots fn-defs mentions mentions)
        {:keys [roots reachable definitions composed-count type-count vocabulary
                unreachable-composed held-vocabulary unreachable-type-rows]}
        (analyze base-defs fn-defs root-names (:vocabulary (edn/read-string (slurp registry-file))))]
    (println "=== Reachability Audit ===")
    (println "Total base-fns:" (count base-defs))
    (println "Total fn-defs (incl. type-rows):" (count definitions))
    (println "  - composed:" composed-count)
    (println "  - type-rows:" type-count)
    (println "Roots BFS'd from:" (count roots))
    (println "Reachable (any kind):" (count reachable))
    (println)
    (println "=== Unreachable COMPOSED fn-defs"
             "(skill §9 dead-code candidates):"
             (count unreachable-composed)
             "===")
    (doseq [[name fd] (sort-by key unreachable-composed)]
      (println " "
               (format "%-50s  ns=%-30s  parent=%s"
                       (str name)
                       (str (:namespace fd))
                       (str (or (:parent fd)
                                (some-> fd :parents pr-str))))))
    (println)
    (println "=== Unreferenced ON PURPOSE (starter vocabulary,"
             "see tools/graph-reachability.edn):"
             (count held-vocabulary)
             "===")
    (doseq [[name _] (sort-by key held-vocabulary)]
      (println " " (format "%-50s  %s" (str name) (get vocabulary name))))
    (println)
    (println "=== Unreachable TYPE-ROWS (vocabulary, NOT dead):"
             (count unreachable-type-rows)
             "===")
    (doseq [[name fd] (sort-by key unreachable-type-rows)]
      (println " "
               (format "%-50s  ns=%s"
                       (str name)
                       (str (:namespace fd)))))
    {:unreachable-composed (vec (sort-by key unreachable-composed))}))


(defn -main
  [& _args]
  (let [{:keys [unreachable-composed]} (run-audit)]
    (when (seq unreachable-composed)
      (throw (ex-info "Unreachable composed function definitions"
                      {:type :reachability/unreachable :count (count unreachable-composed)})))))
