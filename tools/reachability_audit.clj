;; Reachability audit — find unreachable composed fn-defs across all
;; packages, per graphden-fn-refactor skill §9.
;;
;; Run via:  clojure -M:dev tools/reachability_audit.clj
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

(require '[graphden.packages.loader :as pkg]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])


(defn- collect-refs
  "Recursively collect every fn-name referenced by a fn-def's
   :parent / :parents / :args / nested values. References can be:

   - **keyword** matching a known fn-name (the common case — fn-def
     args use `:my-fn` to mean a ref);
   - **string** matching a known fn-name's `(name kw)` (dynamic
     dispatch via `executor/execute-by-name`; `:_value-form-registry`
     is the live example — its `:value` is a list of
     `[type-string fn-name-string]` pairs, resolved at runtime).

   Over-collection is the safe direction here — a literal that
   happens to match a fn-name is treated as a ref."
  [fn-defs-by-name v]
  (let [string->kw (delay
                     (into {}
                           (keep (fn [k] [(name k) k]))
                           (keys fn-defs-by-name)))]
    (letfn [(walk
              [x]
              (cond
                (keyword? x)
                (if (contains? fn-defs-by-name x) #{x} #{})

                (string? x)
                (if-let [kw (get @string->kw x)] #{kw} #{})

                (map? x)
                (apply clojure.set/union (map walk (vals x)))

                (sequential? x)
                (apply clojure.set/union (map walk x))

                :else #{}))]
      (walk v))))


(defn- refs-of-fn-def
  "Set of fn-def names that `fd` references — parent(s), args, type
   refs in :type maps. Filters to names that exist in `fn-defs-by-name`."
  [fn-defs-by-name fd]
  (let [parents (cond
                  (:parent fd)  [(:parent fd)]
                  (:parents fd) (:parents fd)
                  :else         [])
        arg-refs (collect-refs fn-defs-by-name (:args fd))
        type-refs (collect-refs fn-defs-by-name (:type fd))
        list-refs (collect-refs fn-defs-by-name (:list fd))
        union-refs (collect-refs fn-defs-by-name (:union fd))
        refine-refs (collect-refs fn-defs-by-name (:refine fd))]
    (-> #{}
        (into (filter #(contains? fn-defs-by-name %) parents))
        (into arg-refs)
        (into type-refs)
        (into list-refs)
        (into union-refs)
        (into refine-refs))))


(defn- bfs-reachable
  "Standard BFS — accumulate every fn-def reachable from `roots`
   following the per-def reference set."
  [fn-defs-by-name roots]
  (loop [visited #{}
         frontier (vec roots)]
    (if (empty? frontier)
      visited
      (let [next-name (peek frontier)
            rest-frontier (pop frontier)]
        (if (contains? visited next-name)
          (recur visited rest-frontier)
          (let [fd (get fn-defs-by-name next-name)
                refs (when fd (refs-of-fn-def fn-defs-by-name fd))
                new-frontier (into rest-frontier
                                   (remove visited refs))]
            (recur (conj visited next-name) new-frontier)))))))


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


(defn- run-audit
  []
  ;; The FULL first-party set incl. the optional registry/mcp packages —
  ;; without them their fn-defs (and anything only they reference, e.g.
  ;; :fn-id-by-name) show as false-positive dead (round-3 audit gap).
  ;; fns executed BY NAME from src (the auth-pages / _form-* templates)
  ;; are seeded from `tools/graph-reachability.edn` — a structural walk
  ;; cannot see them, and without that registry ~51 of the 53 fn-defs
  ;; this audit called dead were live.
  (let [packages (pkg/load-packages ["core" "storage" "web" "app-base" "app" "registry" "mcp" "examples"])
        base-fn-defs (:base-fn-defs packages)
        fn-defs (:fn-defs packages)
        base-by-name (into {}
                           (keep (fn [[n fd]]
                                   (when n [n (assoc fd :name n)])))
                           base-fn-defs)
        fn-def-by-name (into {} (keep (fn [fd] [(:name fd) fd])
                                      fn-defs))
        all-by-name (merge base-by-name fn-def-by-name)
        example-roots (into #{}
                            (keep (fn [fd]
                                    (let [ns (:namespace fd)]
                                      (when (and ns
                                                 (str/starts-with? ns "examples")
                                                 (:name fd))
                                        (:name fd))))
                                  fn-defs))
        dynamic-roots (collect-entry-point-roots all-by-name)
        test-roots (collect-test-roots all-by-name)
        docs-roots (collect-docs-roots all-by-name)
        roots (-> #{:web-server}
                  (into example-roots)
                  (into dynamic-roots)
                  (into test-roots)
                  (into docs-roots))
        _ (println "Dynamic roots (from tools/graph-reachability.edn):"
                   dynamic-roots)
        _ (println "Test roots (from `:name :the-fn` in test/):"
                   test-roots)
        _ (println "Docs roots (from back-ticked names in docs/*.md):"
                   docs-roots)
        reachable (bfs-reachable all-by-name roots)
        composed (filter #(composed? (val %)) fn-def-by-name)
        type-rows (filter #(type-row? (val %)) fn-def-by-name)
        ;; Starter vocabulary is unreferenced ON PURPOSE. Reported in
        ;; its own group rather than dropped — a silent filter would
        ;; read as "covered everything" the day one of these really
        ;; does become a leftover.
        vocabulary (:vocabulary (edn/read-string (slurp registry-file)))
        unreachable (remove #(reachable (key %)) composed)
        unreachable-composed (remove #(contains? vocabulary (key %)) unreachable)
        held-vocabulary (filter #(contains? vocabulary (key %)) unreachable)
        unreachable-type-rows (remove #(reachable (key %)) type-rows)]
    (println "=== Reachability Audit ===")
    (println "Total base-fns:" (count base-by-name))
    (println "Total fn-defs (incl. type-rows):" (count fn-def-by-name))
    (println "  - composed:" (count composed))
    (println "  - type-rows:" (count type-rows))
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
                       (str (:namespace fd)))))))


(run-audit)
