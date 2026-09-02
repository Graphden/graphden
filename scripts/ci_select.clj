(ns ci-select
  "Check-selection core for the CI runner (`scripts/ci.clj`) — split out so it
   is loadable WITHOUT running a CI pass: `scripts/ci_select_test.clj` (the
   `ci-selftest` registry check) exercises the partition logic and validates
   `scripts/checks.edn` itself. A bug here does not fail loudly — it silently
   under-runs the landing gate, so this file guards itself with a test."
  (:require
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.string :as str]))


(def known-groups
  "Every :group a checks.edn entry may carry. A typo'd group would silently
   misclassify a check (e.g. the unit suite running in the lint wave, without
   the machine-wide lock) — validate-registry! rejects it instead."
  #{:clj :web :infra :docs :sec :commit :test :post-test :info})


(defn read-registry
  [path]
  (edn/read-string (slurp path)))


(defn validate-registry!
  "Fails fast on a malformed registry — at authoring time, in EVERY run.
   Without this, a bad :relevant regex compiles lazily and only under
   `--since`, i.e. it detonates in the landing gate and nowhere else; a
   missing :timeout is an NPE mid-wave; a typo'd :group silently breaks
   the fail-fast wave split. Returns the registry unchanged."
  [registry]
  (doseq [c registry]
    (let [ctx (str "checks.edn entry " (pr-str (:name c)))]
      (when-not (string? (:name c))
        (throw (ex-info (str ctx ": :name must be a string") {:check c})))
      (when-not (string? (:bb c))
        (throw (ex-info (str ctx ": :bb must be a string (bb task name)") {:check c})))
      (when-not (pos-int? (:timeout c))
        (throw (ex-info (str ctx ": :timeout must be a positive int (ms)") {:check c})))
      (when-not (contains? known-groups (:group c))
        (throw (ex-info (str ctx ": :group " (pr-str (:group c))
                             " is not one of " (pr-str known-groups))
                        {:check c})))
      (when-some [rel (:relevant c)]
        (when-not (and (vector? rel) (every? string? rel))
          (throw (ex-info (str ctx ": :relevant must be a vector of regex strings") {:check c})))
        (doseq [pat rel]
          (try (re-pattern pat)
               (catch Exception e
                 (throw (ex-info (str ctx ": bad :relevant regex " (pr-str pat)
                                      " — " (ex-message e))
                                 {:check c :pattern pat}))))))))
  (let [names (map :name registry)]
    (when-not (apply distinct? names)
      (throw (ex-info (str "checks.edn: duplicate check names "
                           (pr-str (for [[n f] (frequencies names) :when (> f 1)] n)))
                      {}))))
  registry)


(defn flag-value
  [args flag]
  (second (drop-while #(not= flag %) args)))


(defn changed-files
  "Repo-relative paths that differ from `since` — committed AND uncommitted
   (`git diff`) plus untracked (`git ls-files --others`), so a scoped local run
   never misses a file the author just created. Returns nil (= no scoping,
   run everything) when git can't answer — a bad ref must widen the run, not
   silently narrow it."
  [since]
  (try
    (let [diff (p/shell {:out :string :err :string :continue true}
                        "git" "diff" "--name-only" since)
          untracked (p/shell {:out :string :err :string :continue true}
                             "git" "ls-files" "--others" "--exclude-standard")]
      (if (and (zero? (:exit diff)) (zero? (:exit untracked)))
        (into #{}
              (remove str/blank?)
              (concat (str/split-lines (:out diff))
                      (str/split-lines (:out untracked))))
        (do (println (str "⚠ --since " since ": git diff failed — running every check"))
            nil)))
    (catch Exception e
      (println (str "⚠ --since " since ": " (ex-message e) " — running every check"))
      nil)))


(def ^:private compile-pattern
  "Compile each :relevant string once per run, not once per (pattern ×
   changed-file) pair — a wide diff is thousands of files against ~50
   patterns."
  (memoize re-pattern))


(defn relevant?
  "Does the diff make this check worth running? A check with no `:relevant`
   patterns always is; otherwise ≥1 changed path must match ≥1 pattern.
   With no diff info (nil `changed`) everything is relevant — conservative."
  [c changed]
  (or (nil? changed)
      (nil? (:relevant c))
      (boolean (some (fn [pat] (some #(re-find (compile-pattern pat) %) changed))
                     (:relevant c)))))


(defn partition-checks
  "Pure core of the selection: splits `registry` into `{:run :scoped :manual}`
   given the already-resolved inputs — `wanted` (group set or nil), `changed`
   (path set or nil), `skip-set` (name/group strings or nil).

   `:post-test` reads what the `:test` wave writes (`bb perf` grades
   perf/runs/*.edn) — if no :test check will run, :post-test is scoped out
   too, whatever its own patterns say: grading a stale run passes on a
   regression it never saw."
  [registry {:keys [wanted changed skip-set]}]
  (let [in-groups (filterv (fn [c] (or (nil? wanted) (contains? wanted (:group c))))
                           registry)
        manual? (fn [c]
                  (and skip-set
                       (or (contains? skip-set (:name c))
                           (contains? skip-set (name (:group c))))))
        {manual true rest' false} (group-by (comp boolean manual?) in-groups)
        {run true scoped false} (group-by #(relevant? % changed) rest')
        tests-run? (boolean (some #(= :test (:group %)) run))
        {post-orphaned true run false} (group-by #(and (not tests-run?)
                                                       (= :post-test (:group %)))
                                                 run)]
    {:run (vec run)
     :scoped (into (vec scoped) post-orphaned)
     :manual (vec manual)}))


(defn select-checks
  "CLI wrapper over `partition-checks` for the runner. Parses `--groups`,
   `--since`, `--skip` out of `args`, resolves the diff, and attaches each
   runnable entry's `:cmd` — `bb <task>`, the single command source."
  [registry args]
  (let [groups-arg (flag-value args "--groups")
        wanted (when groups-arg
                 (into #{}
                       (comp (map str/trim) (remove str/blank?) (map keyword))
                       (str/split groups-arg #",")))
        ;; `--groups ""` would select ZERO checks — a green run that ran
        ;; nothing. That can only be a caller bug; refuse it.
        _ (when (and groups-arg (empty? wanted))
            (throw (ex-info "--groups selected no groups (empty argument)"
                            {:groups groups-arg})))
        since (flag-value args "--since")
        changed (when since (changed-files since))
        skip-arg (flag-value args "--skip")
        skip-set (when skip-arg
                   (into #{} (map str/trim) (str/split skip-arg #",")))
        parts (partition-checks registry {:wanted wanted
                                          :changed changed
                                          :skip-set skip-set})]
    (-> parts
        (update :run (fn [run] (mapv #(assoc % :cmd ["bb" (:bb %)]) run)))
        (assoc :since since))))
