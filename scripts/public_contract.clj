(ns public-contract
  "Early package upgrade guard over parser-produced public OWN slot identities.

   This deliberately does not claim full call-surface or subtype compatibility.
   Both revisions use the current parser; implementations are never loaded.
   Only changed named public definitions are parsed into contract snapshots."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.packages.records :as records]
    [graphden.packages.records.parse :as parser]))


(defn package-path?
  "The first-party package definitions covered by this guard."
  [path]
  (boolean (re-matches #"resources/packages/.+/fns\.edn" path)))


(defn- definition-id
  [definition]
  (records/fn-id (:namespace definition) (:name definition)))


(defn- public-definition?
  [definition]
  (and (:name definition)
       (not (str/starts-with? (name (:name definition)) "_"))))


(defn- index-definitions
  [definitions]
  (reduce (fn [index definition]
            (let [id (definition-id definition)]
              (when (contains? index id)
                (throw (ex-info "Duplicate package function identity" {:fn-id id})))
              (assoc index id definition)))
          {} definitions))


(defn changed-public-ids
  "Existing public roots whose own definition changed, including removal.
   File moves and source descriptions alone do not change the declaration.
   Private helper changes do not expand this deliberately narrow check."
  [old-definitions new-definitions]
  (let [old (index-definitions old-definitions)
        new (index-definitions new-definitions)
        declaration #(dissoc % :source-file :source-line :description)]
    (into #{}
          (keep (fn [[id definition]]
                  (when (and (public-definition? definition)
                             (not= (declaration definition) (declaration (get new id))))
                    id)))
          old)))


(defn- root-contract
  [{:keys [name->id defs-by-name]} definition]
  (let [id (definition-id definition)
        rows (group-by :kind (parser/parse-fn-def definition name->id defs-by-name))
        root (some #(when (= id (:id %)) %) (:fn rows))
        slots (into {} (map (juxt :id identity)) (:slot rows))
        own-ids (into #{} (comp (filter #(= id (:fn-id %))) (map :slot-id)) (:fn-slot rows))]
    (when-not root
      (throw (ex-info "Parser did not emit the selected public root" {:fn-id id})))
    {:fn-id id
     :name (keyword (:namespace definition) (name (:name definition)))
     :source-file (:source-file definition)
     :base-fn? (and (empty? (:parent-ids root)) (some? (:return-type-fn-id root)))
     :composed? (boolean (seq (:parent-ids root)))
     :slots (into {} (map (fn [slot-id]
                            (let [slot (get slots slot-id)]
                              (when-not slot
                                (throw (ex-info "Parser did not emit an own slot" {:fn-id id :slot-id slot-id})))
                              [slot-id (select-keys slot [:name :type-fn-id :required :source-slot-id])])))
                  own-ids)
     :lambda-params (:lambda-params root)
     :return-type-fn-id (:return-type-fn-id root)}))


(defn contract-snapshots
  "Resolve cross-module declarations once, then parse only selected roots.
   Nested record fields and private inline helpers are not root-owned slots."
  [definitions selected-ids]
  (if (empty? selected-ids)
    {}
    (let [prepared (parser/prepare-module definitions {} {})]
      (into {}
            (comp (filter #(contains? selected-ids (definition-id %)))
                  (map (fn [definition]
                         [(definition-id definition) (root-contract prepared definition)])))
            (:fn-defs prepared)))))


(defn- field-change
  [kind field old new]
  (when (not= (get old field) (get new field))
    {:kind kind :severity :review :old (get old field) :new (get new field)}))


(defn- slot-changes
  [old-slots new-slots]
  (mapcat
    (fn [id]
      (let [old (get old-slots id)
            new (get new-slots id)
            changes (cond
                      (nil? new) [{:kind :own-slot-removed :severity :breaking :old old}]
                      (nil? old) [{:kind :own-slot-added :severity :review :new new}]
                      :else (remove nil?
                                    [(field-change :slot-type-changed :type-fn-id old new)
                                     (when-let [change (field-change :slot-required-changed :required old new)]
                                       (cond-> change
                                         (and (false? (:required old)) (true? (:required new)))
                                         (assoc :severity :breaking)))]))]
        (map #(assoc % :slot-id id :slot-name (:name (or old new))) changes)))
    (sort-by str (set/union (set (keys old-slots)) (set (keys new-slots))))))


(defn contract-changes
  "Report exact declared-contract deltas. Identity cuts, optional-to-required
   own slots and missing source links after base->graph extraction fail.
   Source-link presence does not prove complete runtime reader reachability.
   Type, lambda and return changes require review, not guessed subtyping;
   a new own slot may merely expose a previously inherited input."
  [old new]
  (mapv #(merge {:fn-id (:fn-id old) :function (:name old) :source-file (:source-file old)} %)
        (if (nil? new)
          [{:kind :public-fn-removed :severity :breaking}]
          (concat (slot-changes (:slots old) (:slots new))
                  (when (and (:base-fn? old) (:composed? new))
                    (for [id (sort-by str (keys (:slots old)))
                          :let [slot (get-in new [:slots id])]
                          :when (and slot (nil? (:source-slot-id slot)))]
                      {:kind :own-slot-source-missing :severity :breaking
                       :slot-id id :slot-name (:name slot)}))
                  (remove nil?
                          [(field-change :lambda-params-changed :lambda-params old new)
                           (field-change :return-type-changed :return-type-fn-id old new)])))))


(defn compare-definitions
  "Compare only changed existing public roots, retaining both exact snapshots."
  [old-definitions new-definitions]
  (let [selected (changed-public-ids old-definitions new-definitions)
        old (contract-snapshots old-definitions selected)
        new (contract-snapshots new-definitions selected)
        changes (into [] (mapcat #(contract-changes (get old %) (get new %))) (sort-by str selected))]
    {:checked (count selected)
     :old old :new new
     :changes changes
     :breaking (filterv #(= :breaking (:severity %)) changes)}))


(defn- git-output
  [root & args]
  (:out (process/check @(process/process (into ["git"] args)
                                         {:dir (str root) :out :string :err :string}))))


(defn- revision
  [root ref]
  (str/trim (git-output root "rev-parse" "--verify" "--end-of-options" (str ref "^{commit}"))))


(defn- revision-paths
  [root sha]
  (filterv package-path?
           (str/split (git-output root "ls-tree" "-r" "--name-only" "-z" sha "--" "resources/packages") #"\u0000")))


(defn- read-module
  [path content]
  (let [module (edn/read-string content)]
    (when-not (and (map? module) (vector? (:fns module)))
      (throw (ex-info "Expected package module with :fns vector" {:source-file path})))
    (mapv #(assoc % :namespace (:namespace module) :source-file path) (:fns module))))


(defn- read-definitions
  [root sha paths]
  (into []
        (mapcat (fn [path]
                  (read-module path (if sha
                                      (git-output root "show" (str sha ":" path))
                                      (slurp (str (fs/path root path)))))))
        paths))


(defn check-revisions
  "Baseline is an explicit commit; candidate is a commit or the working tree.
   Git failures are errors. No package change skips all parser preparation."
  [root base candidate]
  (let [base-sha (revision root base)
        candidate-sha (when candidate (revision root candidate))
        changed (str/split (apply git-output root "diff" "--name-only" "-z" base-sha
                                  (concat (when candidate-sha [candidate-sha]) ["--" "resources/packages"])) #"\u0000")
        untracked (when-not candidate-sha
                    (str/split (git-output root "ls-files" "--others" "--exclude-standard" "-z" "--" "resources/packages") #"\u0000"))
        paths (filterv package-path? (concat changed untracked))
        report (if (empty? paths)
                 {:checked 0 :old {} :new {} :changes [] :breaking []}
                 (compare-definitions
                   (read-definitions root base-sha (revision-paths root base-sha))
                   (read-definitions root candidate-sha
                                     (if candidate-sha
                                       (revision-paths root candidate-sha)
                                       (mapv #(str (fs/relativize root %))
                                             (fs/glob root "resources/packages/**/fns.edn"))))))]
    (assoc report :base base-sha :candidate (or candidate-sha :working-tree))))


(defn cli-options
  "Explicit --base wins over the CI's immutable merge baseline. A standalone
   invocation defaults to HEAD^, so a missing baseline fails rather than passes."
  [args ci-base]
  (loop [remaining args options {:base (if (str/blank? ci-base) "HEAD^" ci-base)}]
    (if (empty? remaining)
      options
      (let [[flag value & more] remaining
            option ({"--base" :base "--candidate" :candidate} flag)]
        (when-not (and option value (not (str/starts-with? value "--")))
          (throw (ex-info "Expected --base REF or --candidate REF" {:flag flag})))
        (recur more (assoc options option value))))))


(defn -main
  [& args]
  (try
    (let [{:keys [base candidate]} (cli-options args (System/getenv "GRAPHDEN_CI_SINCE"))
          report (check-revisions (fs/canonicalize ".") base candidate)]
      (println "public-contract:" (:base report) "->" (:candidate report)
               "—" (:checked report) "changed public roots")
      (doseq [change (:changes report)] (prn change))
      (println "public-contract:" (if (seq (:breaking report)) "FAIL" "PASS")
               "—" (count (:breaking report)) "identity/requiredness/source-link breaks;"
               "own declarations only, not full surface/subtype compatibility")
      (if (seq (:breaking report)) 1 0))
    (catch Exception error
      (binding [*out* *err*]
        (println "public-contract: ERROR —" (ex-message error))
        (when (ex-data error) (prn (ex-data error))))
      2)))


(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
