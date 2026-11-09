(ns structured-input-dry-run
  "Read-only semantic source planning. Run with bb --classpath src:scripts
   scripts/structured_input_dry_run.clj --repo LABEL PATH [...]. Optional
   --manifest PATH pins a previously saved inventory. No implementations load.
   Exit zero means analysis completed, not that the corpus is migratable."
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [graphden.input.source :as source]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [structured-input-inventory :as inventory])
  (:import
    (java.io
      StringWriter)
    (java.nio.file
      Files)
    (java.security
      MessageDigest)))


(defn- sha256
  [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes)]
    (apply str (map #(format "%02x" (bit-and % 255)) digest))))


(defn checked-source
  "Read source bytes once and verify their inventory checksum BEFORE parsing.
   Returns private source data; callers must not log this value or reader errors."
  [root path checksum]
  (let [bytes (Files/readAllBytes (fs/path root path))]
    (when-not (= checksum (sha256 bytes))
      (throw (ex-info "Source differs from inventory" {:code :source-checksum-mismatch :file path})))
    (inventory/read-edn path (String. ^bytes bytes "UTF-8"))))


(defn- problem
  [package code]
  {:code code :repository (:repository package) :package (:name package) :file (:path package)})


(defn- read-package
  [repository root package]
  (let [checksums (get-in repository [:source-identity :file-checksums])
        metadata (checked-source root (:path package) (get checksums (:path package)))
        directory (fs/parent (:path package))
        listed (mapv #(str (fs/path directory % "fns.edn")) (:modules metadata))
        paths (vec (distinct (concat (filter #(contains? checksums %) listed) (:unlisted package))))
        modules (mapv (fn [path]
                        (let [module (checked-source root path (get checksums path))]
                          {:path path
                           :definitions (mapv #(assoc % :namespace (:namespace module) :source-file path)
                                              (:fns module))})) paths)
        package (assoc package :repository (:label repository)
                       :source-modules modules :dependencies (or (:dependencies metadata) []))
        blockers (cond-> []
                   (or (seq (:missing package)) (seq (:unlisted package)) (seq (:duplicate-modules package)))
                   (conj (problem package :package-module-coverage))
                   (not (and (vector? (:dependencies package)) (every? string? (:dependencies package))))
                   (conj (problem package :unsupported-package-dependencies)))]
    (assoc package :source-definitions (vec (mapcat :definitions modules)) :blockers blockers)))


(defn- read-packages
  [repositories manifest]
  (let [roots (into {} (map (juxt :label :root)) repositories)]
    (vec (mapcat (fn [repository]
                   (map #(read-package repository (get roots (:label repository)) %) (:packages repository)))
                 (:repositories manifest)))))


(defn- qualify-definitions
  "Freeze reference meaning under THIS successful package context before any
   importing package introduces additional bare-name collisions."
  [context]
  (let [names (:name->id context)
        qualified (into {} (keep (fn [[ref id]] (when (qualified-keyword? ref) [id ref]))) names)]
    (mapv (fn [definition]
            (rebase/rewrite-references
              definition (fn [ref]
                           (let [resolved (parse/normalize-ref ref names (:namespace definition))]
                             (get qualified (get names resolved) resolved)))))
          (:fn-defs context))))


(defn- visible-definitions
  [package states]
  (->> (:dependencies package)
       (mapcat #(get-in states [% :exports]))
       distinct
       vec))


(defn- duplicate-definition?
  [definitions]
  (some #(> % 1)
        (vals (frequencies (map (juxt #(or (:namespace %) "") #(some-> % :name name)) definitions)))))


(defn- preparation-errors
  "A failed whole-package prepare must not hide independent source failures.
   Reference normalization is pure; this diagnostic does not invent a context."
  [package names error]
  (let [diagnostics
        (into []
              (keep (fn [definition]
                      (try
                        (rebase/rewrite-references
                          definition #(parse/normalize-ref % names (:namespace definition)))
                        (when (:marker definition)
                          (shapes/register-marker! (:name definition) (:marker definition)))
                        nil
                        (catch Exception failure
                          (assoc (problem package :definition-preparation-failed)
                                 :file (:source-file definition)
                                 :definition [(:namespace definition) (:name definition)]
                                 :cause-type (:type (ex-data failure)))))))
              (:source-definitions package))]
    (if (seq diagnostics) diagnostics
        [(assoc (problem package :package-preparation-failed) :cause-type (:type (ex-data error)))])))


(defn- summarize-definition
  [package definition]
  (let [original (:source definition)]
    (-> (select-keys definition [:identity :kind :status :origins])
        (assoc :definition [(:namespace original) (:name original)]
               :file (:source-file original)
               :blockers (mapv #(assoc % :repository (:repository package) :package (:name package))
                               (:blockers definition))))))


(defn- blocked-state
  [package blockers]
  {:status :blocked :blockers blockers
   :counts {:supported 0 :blocked (count (:source-definitions package))}
   :definitions (mapv (fn [definition]
                        {:definition [(:namespace definition) (:name definition)]
                         :file (:source-file definition) :status :blocked})
                      (:source-definitions package))})


(defn- prepare-package
  [package states]
  (let [dependencies (visible-definitions package states)
        original (:source-definitions package)
        names (parse/name->id-index {} dependencies)
        definitions (slots/build-defs-by-name dependencies)
        all-names (parse/name->id-index names original)]
    (binding [types/*type-aliases-override* (atom {})
              shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
              ;; Legacy parser warnings may include authored type payloads.
              ;; Only the sanitized structured report leaves this CLI.
              *out* (StringWriter.)
              *err* (StringWriter.)]
      (try
        (doseq [definition dependencies :when (:marker definition)]
          (shapes/register-marker! (:name definition) (:marker definition)))
        (if (duplicate-definition? (concat dependencies original))
          (blocked-state package [(problem package :duplicate-visible-definition)])
          (let [context (parse/prepare-module original names definitions)
                planned (source/plan-module original context)
                summaries (mapv #(summarize-definition package %) (:definitions planned))]
            {:status :prepared
             :exports (vec (distinct (concat dependencies (qualify-definitions context))))
             :definitions summaries :counts (:counts planned)
             :blockers (vec (mapcat :blockers summaries))}))
        (catch Exception error
          (blocked-state package (preparation-errors package all-names error)))))))


(defn- initial-blockers
  [package by-name]
  (into (:blockers package)
        (concat
          (when (> (count (get by-name (:name package))) 1)
            [(problem package :duplicate-package-name)])
          (for [dependency (if (vector? (:dependencies package)) (:dependencies package) [])
                :when (and (string? dependency) (not (contains? by-name dependency)))]
            (assoc (problem package :missing-package-dependency) :dependency dependency)))))


(defn- package-state
  [package states]
  (let [failed (filter #(not= :prepared (get-in states [% :status])) (:dependencies package))]
    (if (seq failed)
      (blocked-state package [(assoc (problem package :blocked-package-dependency) :dependencies (vec failed))])
      (prepare-package package states))))


(defn- plan-packages
  [packages]
  (let [by-name (group-by :name packages)
        indexed (into (sorted-map) (map (juxt :name identity)) packages)
        initial (into {} (keep (fn [[name package]]
                                 (let [blockers (initial-blockers package by-name)]
                                   (when (seq blockers) [name (blocked-state package blockers)])))) indexed)]
    (loop [remaining (apply dissoc indexed (keys initial)) states initial]
      (if (empty? remaining)
        states
        (let [ready (filter (fn [[_ package]] (every? #(contains? states %) (:dependencies package))) remaining)]
          (if (seq ready)
            (recur (apply dissoc remaining (map first ready))
                   (reduce (fn [acc [name package]] (assoc acc name (package-state package acc))) states ready))
            (merge states
                   (into {} (map (fn [[name package]]
                                   [name (blocked-state package
                                                        [(assoc (problem package :cyclic-package-dependency)
                                                                :dependencies (:dependencies package))])]))
                         remaining))))))))


(defn- summary
  [manifest packages states]
  (let [by-name (group-by :name packages)
        summaries (mapv (fn [package]
                          (let [state (if (> (count (get by-name (:name package))) 1)
                                        (blocked-state package [(problem package :duplicate-package-name)])
                                        (get states (:name package)))]
                            (merge (select-keys package [:name :repository :path])
                                   {:dependencies (if (and (vector? (:dependencies package))
                                                           (every? string? (:dependencies package)))
                                                    (:dependencies package) :unsupported)}
                                   (dissoc state :exports)))) packages)
        orphan-blockers (vec (for [repository (:repositories manifest)
                                   file (:modules-without-package repository)]
                               {:code :module-without-package :repository (:label repository) :file file}))
        orphan-count (reduce + 0 (for [repository (:repositories manifest)
                                       module (:modules repository)
                                       :when (contains? (set (:modules-without-package repository)) (:path module))]
                                   (:definitions module)))
        counts (reduce #(merge-with + %1 (:counts %2)) {:supported 0 :blocked orphan-count} summaries)
        complete? (= (get-in manifest [:totals :definitions]) (+ (:supported counts) (:blocked counts)))
        report {:schema-version 1
                :mode :read-only-source-plan
                :verification {:new-loader-equivalence false :runtime-equivalence false :history false}
                :source-identities (mapv #(select-keys % [:label :source-identity]) (:repositories manifest))
                :inventory-totals (:totals manifest)
                :counts counts :coverage-complete? complete?
                :packages summaries
                :blockers (cond-> (into orphan-blockers (mapcat :blockers summaries))
                            (not complete?) (conj {:code :definition-coverage-mismatch}))}]
    (assoc report :report-sha256 (sha256 (.getBytes (inventory/report-text report) "UTF-8")))))


(defn dry-run
  "Inventory explicit roots, verify retained source bytes, plan only within
   declared package dependency closures, then re-inventory before returning.
   Optional expected-manifest must match the initial inventory exactly.
   The returned report contains no source forms, literals or proposed bodies."
  ([repositories] (dry-run repositories nil))
  ([repositories expected-manifest]
   (let [manifest (inventory/inventory repositories)]
     (when (and expected-manifest (not= expected-manifest manifest))
       (throw (ex-info "Inventory differs from pinned manifest" {:code :manifest-mismatch})))
     (let [packages (read-packages repositories manifest)
           states (plan-packages packages)
           after (inventory/inventory repositories)]
       (when-not (= manifest after)
         (throw (ex-info "Inventory changed during planning" {:code :source-drift})))
       (summary manifest packages states)))))


(defn cli-options
  "Only explicit repository roots and an optional frozen inventory path."
  [arguments]
  (loop [args arguments repositories [] manifest nil]
    (if (empty? args)
      {:repositories (inventory/cli-repositories repositories) :manifest manifest}
      (case (first args)
        "--repo" (let [[_ label root & more] args]
                   (when-not (and label root)
                     (throw (ex-info "Expected --repo LABEL PATH" {:code :invalid-arguments})))
                   (recur more (into repositories ["--repo" label root]) manifest))
        "--manifest" (let [[_ path & more] args]
                       (when (or manifest (nil? path) (str/starts-with? path "--"))
                         (throw (ex-info "Expected one --manifest PATH" {:code :invalid-arguments})))
                       (recur more repositories path))
        (throw (ex-info "Unknown argument" {:code :invalid-arguments}))))))


(when (= *file* (System/getProperty "babashka.file"))
  (try
    (let [{:keys [repositories manifest]} (cli-options *command-line-args*)
          pinned (when manifest (inventory/read-edn manifest (slurp manifest)))]
      (print (inventory/report-text (dry-run repositories pinned))))
    (catch Exception error
      (binding [*out* *err*]
        (prn {:code (or (:code (ex-data error)) :dry-run-failed)
              :file (or (:file (ex-data error)) (:source-file (ex-data error)))}))
      (System/exit 2))))
