(ns structured-input-inventory
  "Read-only package source inventory for the structured-input migration.
   Run: bb scripts/structured_input_inventory.clj --repo LABEL PATH [...].
   Counts syntax, not effective contracts; never loads package implementations."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.pprint :as pp]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.walk :as walk])
  (:import
    (java.io
      PushbackReader
      StringReader)
    (java.nio.file
      Files)
    (java.security
      MessageDigest)))


(defn- git-output
  [root & args]
  (str/trim (:out (process/check @(process/process (into ["git"] args)
                                                   {:dir (str root) :out :string :err :string})))))


(defn- digest
  [content]
  (let [sha (MessageDigest/getInstance "SHA-256")]
    (MessageDigest/.update sha ^bytes content)
    (str/join (map #(format "%02x" (bit-and % 255)) (MessageDigest/.digest sha)))))


(defn- input-paths
  [root]
  (->> ["resources/packages/**/package.edn" "resources/packages/**/fns.edn"]
       (mapcat #(fs/glob root %))
       (filter fs/regular-file?)
       (map #(str (fs/relativize root %)))
       sort
       vec))


(defn- source-snapshot
  [root]
  (let [inputs (into (sorted-map)
                     (map (fn [path]
                            (let [content (Files/readAllBytes (fs/path root path))]
                              [path {:content (String. ^bytes content "UTF-8")
                                     :sha256 (digest content)}])))
                     (input-paths root))
        files (into (sorted-map) (map (fn [[path input]] [path (:content input)])) inputs)
        checksums (into (sorted-map) (map (fn [[path input]] [path (:sha256 input)])) inputs)]
    {:files files
     :identity {:git-head (git-output root "rev-parse" "HEAD")
                :git-head-tree (git-output root "rev-parse" "HEAD^{tree}")
                :dirty? (not (str/blank? (git-output root "status" "--porcelain=v1" "--untracked-files=normal")))
                :input-sha256 (digest (String/.getBytes (pr-str checksums) "UTF-8"))
                :file-checksums checksums}}))


(defn read-edn
  "Read exactly one EDN value. Unknown tags and read-eval fail without execution."
  [path content]
  (try
    (with-open [reader (PushbackReader. (StringReader. content))]
      (let [eof (Object.)
            options {:eof eof :readers {}}
            value (edn/read options reader)]
        (when (or (identical? eof value)
                  (not (identical? eof (edn/read options reader))))
          (throw (ex-info "Expected exactly one EDN value" {})))
        value))
    (catch Exception error
      (throw (ex-info "Cannot read inventory EDN" {:source-file path} error)))))


(def ^:private type-kinds
  [:type :refine :list :map :tuple :union :variant :fn-type :marker])


(defn- definition-kind
  [definition]
  (cond
    (contains? definition :parents) :multiple-parent
    (contains? definition :parent) :single-parent
    :else (or (some #(when (contains? definition %) %) type-kinds) :base-declaration)))


(defn- sum-counts
  [& counts]
  (or (apply merge-with + counts) {}))


(defn- present-keys
  [form fields]
  (into {} (keep #(when (contains? form %) [% 1])) fields))


(declare definition-forms)


(defn- binding-forms
  "Follow only binding syntax, never a whole-value payload or arbitrary map.
   Bare keywords/vectors remain candidates until a semantic parser resolves them."
  [value]
  (cond
    (keyword? value) {:bare-keyword-candidates 1}
    (vector? value) (apply sum-counts {:bare-vector-candidates 1} (map binding-forms value))
    (map? value)
    (if (or (contains? value :parent) (contains? value :parents))
      (sum-counts {:inline-anonymous-definitions 1} (definition-forms value))
      (let [markers (present-keys value [:value :ref :as :type :required :terminal :closed
                                         :closed? :append :resolver :secret-path])]
        (sum-counts markers
                    (when (and (contains? value :required) (false? (:required value)))
                      {:optional-specs 1})
                    (when (empty? markers) {:literal-map-candidates 1})
                    ;; Explicit :value shields its payload, including maps with
                    ;; graph-looking keys. Append has its own expression list.
                    (when (vector? (:append value))
                      (apply sum-counts (map binding-forms (:append value)))))))
    :else {:scalar-literals 1}))


(defn definition-forms
  "Syntactic dimensions; base-spec defaults are separate from composed binds."
  [definition]
  (let [kind (definition-kind definition)
        args (:args definition)
        base? (= :base-declaration kind)
        arg-counts (for [value (vals args)]
                     (if base?
                       (sum-counts {:base-arg-declarations 1}
                                   (when (keyword? value) {:type-shorthands 1})
                                   (when (map? value)
                                     (sum-counts (present-keys value [:type :required])
                                                 (when (false? (:required value)) {:optional-specs 1})
                                                 (when (contains? value :value) {:spec-defaults 1}))))
                       (binding-forms value)))
        lambda-kind (cond
                      (not (contains? definition :lambda-params)) :lambda-params-absent
                      (empty? (:lambda-params definition)) :lambda-params-empty
                      :else :lambda-params-nonempty)]
    (apply sum-counts
           {lambda-kind 1}
           (present-keys definition [:args :lazy-args :effects :expects-effects :branch-local?])
           arg-counts)))


(defn- module-inventory
  [path content]
  (let [{ns-path :namespace :keys [fns]} (read-edn path content)]
    (when-not (and (or (nil? ns-path) (string? ns-path)) (vector? fns)
                   (every? #(and (map? %) (keyword? (:name %))
                                 (or (nil? (:args %)) (map? (:args %)))) fns))
      (throw (ex-info "Expected named definitions in a :fns vector and optional namespace" {:source-file path})))
    {:path path
     :namespace ns-path
     :definitions (count fns)
     :definition-kinds (frequencies (map definition-kind fns))
     :forms (apply sum-counts (map definition-forms fns))}))


(defn- package-inventory
  [path content module-paths]
  (let [{:keys [name version modules dependencies]} (read-edn path content)
        directory (str (fs/parent path))]
    (when-not (and (string? name) (vector? modules) (every? string? modules)
                   (every? #(and (not (fs/absolute? %))
                                 (not-any? #{".." "." ""} (str/split % #"/"))) modules))
      (throw (ex-info "Expected package name and relative module paths" {:source-file path})))
    (let [expected (mapv #(str (fs/path directory % "fns.edn")) modules)
          actual (set (filter #(str/starts-with? % (str directory "/")) module-paths))]
      {:path path :name name :version version :dependencies dependencies
       :listed (vec (sort expected))
       :missing (vec (sort (set/difference (set expected) actual)))
       :unlisted (vec (sort (set/difference actual (set expected))))
       :duplicate-modules (vec (sort (keep (fn [[m n]] (when (> n 1) m)) (frequencies modules))))})))


(defn- repository-inventory
  [label snapshot]
  (let [files (:files snapshot)
        modules (mapv (fn [[path content]] (module-inventory path content))
                      (filter #(str/ends-with? (key %) "/fns.edn") files))
        module-paths (mapv :path modules)
        packages (mapv (fn [[path content]] (package-inventory path content module-paths))
                       (filter #(str/ends-with? (key %) "/package.edn") files))
        covered (into #{} (mapcat #(concat (:listed %) (:unlisted %))) packages)]
    {:label label :source-identity (:identity snapshot)
     :counts {:packages (count packages) :files (count modules)
              :definitions (reduce + 0 (map :definitions modules))}
     :definition-kinds (apply sum-counts (map :definition-kinds modules))
     :forms (apply sum-counts (map :forms modules))
     :packages packages :modules modules
     :modules-without-package (vec (sort (set/difference (set module-paths) covered)))}))


(defn- checked-repositories
  [repositories]
  (when (or (empty? repositories)
            (not= (count repositories) (count (set (map :label repositories)))))
    (throw (ex-info "Provide repositories with distinct labels" {})))
  (let [checked (mapv (fn [{:keys [label root]}]
                        (when-not (and (string? label) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" label)
                                       root (fs/directory? root))
                          (throw (ex-info "Expected repository label and existing directory" {:label label})))
                        (let [root (fs/canonicalize root)]
                          (when-not (= root (fs/canonicalize (git-output root "rev-parse" "--show-toplevel")))
                            (throw (ex-info "Root must be a Git repository checkout" {:label label})))
                          {:label label :root root}))
                      (sort-by :label repositories))]
    (when-not (= (count checked) (count (set (map :root checked))))
      (throw (ex-info "Repository roots must be distinct" {})))
    checked))


(defn inventory
  "Inventory explicit Git roots; detect input/HEAD/status changes during the scan.
   Output paths are relative and contain neither roots nor source payloads."
  [repositories]
  (let [repositories (checked-repositories repositories)
        snapshots (mapv #(source-snapshot (:root %)) repositories)
        reports (mapv #(repository-inventory (:label %1) %2) repositories snapshots)
        after (mapv #(source-snapshot (:root %)) repositories)]
    (when-not (= snapshots after)
      (throw (ex-info "Inventory inputs changed during scan; rerun on stable checkouts" {})))
    {:schema-version 1
     :coverage {:source-glob "resources/packages/**/{package,fns}.edn"
                :classification :syntax-only
                :excludes [:build-output :external-package-mirrors :database-history
                           :embedded-fixtures :tutorial-snippets :effective-contracts]}
     :repositories reports
     :totals (apply sum-counts (map :counts reports))}))


(defn cli-repositories
  "Accept repeated --repo LABEL PATH arguments; never discover sibling roots."
  [args]
  (loop [remaining args repositories []]
    (if (empty? remaining)
      (if (seq repositories) repositories
          (throw (ex-info "Usage: --repo LABEL PATH [--repo LABEL PATH ...]" {})))
      (let [[flag label root & more] remaining]
        (when-not (and (= "--repo" flag) label root (not (str/starts-with? root "--")))
          (throw (ex-info "Expected --repo LABEL PATH" {:flag flag})))
        (recur more (conj repositories {:label label :root root}))))))


(defn report-text
  "Byte-stable EDN: sort maps recursively, retaining authored vector ordering."
  [report]
  (with-out-str
    (pp/pprint (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b)))) %) %)
                              report))))


(when (= *file* (System/getProperty "babashka.file"))
  (try
    (print (report-text (inventory (cli-repositories *command-line-args*))))
    (catch Exception error
      (binding [*out* *err*]
        (println "structured-input-inventory:" (ex-message error))
        (when (ex-data error) (prn (ex-data error))))
      (System/exit 2))))
