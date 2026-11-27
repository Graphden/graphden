(ns structured-input-stage
  "Materialize verified module replacements in a new directory. Source
   checkouts, even when dirty, are read-only inputs to this command."
  (:require
    [babashka.fs :as fs]
    [graphden.packages.records.ids :as ids]
    [structured-input-cst :as cst]
    [structured-input-inventory :as inventory])
  (:import
    (java.nio.file
      CopyOption
      Files
      Path)))


(defn- refuse
  [code]
  (throw (ex-info "Cannot stage structured input migration" {:code code})))


(defn- source-file
  [checkout relative]
  (when-not (and (string? relative) (seq relative))
    (refuse :invalid-module-path))
  (let [path (fs/path relative)]
    (when-not (and (fs/relative? path) (= path (Path/.normalize path))
                   (not-any? #{"." ".."} (map str path))
                   (= "fns.edn" (str (fs/file-name path))))
      (refuse :invalid-module-path))
    (let [file (fs/real-path (fs/path checkout path))]
      (when-not (and (Path/.startsWith file checkout) (fs/regular-file? file))
        (refuse :invalid-module-path))
      file)))


(defn- verify-patch
  [checkout {:keys [path patch]}]
  (let [original (slurp (str (source-file checkout path)) :encoding "UTF-8")
        {:keys [status text]} (cst/apply-text patch original)
        source (inventory/read-edn path original)
        target (inventory/read-edn path text)]
    (when-not (and (= :applied status) (= 2 (:input-format target))
                   (= (:namespace patch) (:namespace source) (:namespace target))
                   (= (mapv :name (:fns source)) (mapv :name (:fns target))))
      (refuse :module-has-no-upgrade-proof))
    {:path path :text text :sha256 (:target-sha256 patch)}))


(defn- verify-generated
  [packages staged-paths {package-name :package :keys [owners module-paths sha256 text]}]
  (let [known-owners (get packages package-name)
        path (str "generated-structured-input/" package-name ".edn")]
    (when-not (and (string? package-name) (re-matches #"[a-z0-9][a-z0-9.-]*" package-name)
                   (string? text) (= sha256 (ids/digest-hex "SHA-256" text))
                   (vector? owners) (seq owners) (every? uuid? owners)
                   (= (count owners) (count (set owners)))
                   (vector? module-paths) (seq module-paths)
                   (every? staged-paths module-paths)
                   (map? known-owners) (every? known-owners owners)
                   (= (set module-paths) (set (map known-owners owners))))
      (refuse :invalid-generated-source-ledger))
    (let [source (inventory/read-edn path text)]
      (when-not (and (= 1 (:artifact-format source))
                     (= owners (mapv :owner-id (:owners source)))
                     (vector? (:modules source)) (seq (:modules source))
                     (every? #(= 2 (:input-format %)) (:modules source)))
        (refuse :invalid-generated-source-ledger)))
    {:path path :sha256 sha256 :text text :owners owners}))


(defn- stage-files!
  [destination replacements receipt]
  (let [temp (fs/create-temp-dir {:dir (fs/parent destination) :prefix ".input-staging-"})]
    (try
      (doseq [{:keys [path text]} replacements]
        (let [target (fs/path temp path)]
          (fs/create-dirs (fs/parent target))
          (spit (str target) text :encoding "UTF-8")))
      (spit (str (fs/path temp "structured-input-receipt.edn"))
            (inventory/report-text receipt) :encoding "UTF-8")
      (Files/move temp destination (make-array CopyOption 0))
      (finally
        (when (fs/exists? temp) (fs/delete-tree temp))))))


(defn stage!
  "Verify every proposed source file first, then publish a complete staging
   directory. The receipt reports blocked modules; no source path is written."
  [{:keys [artifact-format modules generated-source report]} repository checkout output]
  (when-not (and (= 1 artifact-format) (vector? modules)
                 (or (nil? generated-source) (vector? generated-source))
                 (some #(= repository (:label %)) (:source-identities report)))
    (refuse :invalid-artifact))
  (let [checkout (fs/real-path checkout)
        destination (fs/absolutize output)
        group (filterv #(= repository (:repository %)) modules)
        paths (mapv :path group)
        products (filterv #(= repository (:repository %)) generated-source)
        package-owners (into {} (for [{:keys [name definitions] repo :repository} (:packages report)
                                      :when (= repo repository)]
                                  [name (into {} (map (juxt :identity :file)) definitions)]))]
    (when (or (fs/exists? destination) (not (fs/directory? (fs/parent destination))))
      (refuse :new-staging-directory-required))
    (when-not (and (= (count paths) (count (set paths)))
                   (every? #(contains? #{:verified :blocked} (:status %)) group)
                   (= (count products) (count (set (map :package products)))))
      (refuse :invalid-module-ledger))
    (let [replacements (mapv (partial verify-patch checkout) (filter #(= :verified (:status %)) group))
          products (mapv (partial verify-generated package-owners (set (map :path replacements))) products)
          receipt {:artifact-format artifact-format
                   :repository repository
                   :source-report-sha256 (:report-sha256 report)
                   :staged (mapv #(select-keys % [:path :sha256]) replacements)
                   :generated-source (mapv #(select-keys % [:path :sha256 :owners]) products)
                   :blocked (mapv #(select-keys % [:path :reason]) (filter #(= :blocked (:status %)) group))}]
      (stage-files! destination (into replacements products) receipt)
      receipt)))


(when (= *file* (System/getProperty "babashka.file"))
  (try
    (let [[artifact repository checkout output :as args] *command-line-args*]
      (when-not (= 4 (count args)) (refuse :expected-artifact-repository-checkout-output))
      (let [report (inventory/read-edn "migration artifact" (slurp artifact :encoding "UTF-8"))]
        (print (inventory/report-text (stage! report repository checkout output)))))
    (catch Exception failure
      (binding [*out* *err*]
        (prn {:code (or (:code (ex-data failure)) (:reason (ex-data failure)) :stage-artifact-failed)}))
      (System/exit 2))))
