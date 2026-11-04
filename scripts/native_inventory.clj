(ns native-inventory
  "Advisory source inventory. Never loads package code or infers purity/deadness."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [edamame.core :as reader])
  (:import
    (java.nio.file
      Files)
    (java.security
      MessageDigest)))


(defn- git-output
  [root & args]
  (str/trim (:out (process/check @(process/process (into ["git"] args)
                                                   {:dir (str root) :out :string :err :string})))))


(defn source-identity
  "Bind a report to HEAD and actual input bytes, including uncommitted inputs.
   Kondo caches are excluded; analyzer configuration and hooks are included."
  [root]
  (let [paths (->> (concat (mapcat #(fs/glob root %)
                                   ["src/**" "resources/packages/**" ".clj-kondo/**"])
                           (map #(fs/path root %) ["deps.edn" "tools/graph-reachability.edn"
                                                   "scripts/native_inventory.clj"]))
                   (filter fs/regular-file?)
                   (remove #(str/includes? (str (fs/relativize root %)) ".clj-kondo/.cache/"))
                   (sort-by #(str (fs/relativize root %))))
        digest (MessageDigest/getInstance "SHA-256")]
    (doseq [path paths]
      (let [content (Files/readAllBytes path)
            relative (str (fs/relativize root path))]
        (MessageDigest/.update digest (String/.getBytes (str relative "\u0000" (alength content) "\u0000") "UTF-8"))
        (MessageDigest/.update digest content)))
    {:git-head (git-output root "rev-parse" "HEAD")
     :git-head-tree (git-output root "rev-parse" "HEAD^{tree}")
     :dirty? (not (str/blank? (git-output root "status" "--porcelain=v1" "--untracked-files=normal")))
     :input-files (count paths)
     :input-sha256 (str/join (map #(format "%02x" (bit-and % 255)) (MessageDigest/.digest digest)))}))


(defn read-source
  "Read forms without evaluation, preserving source positions."
  [source]
  (reader/parse-string-all source
                           {:read-cond :allow :features #{:clj}
                            :fn true :regex true :var true :deref true
                            :syntax-quote true
                            :auto-resolve #(or % "native-inventory")}))


(defn- qualified
  [ns-name var-name]
  (when (and ns-name var-name)
    (symbol (str ns-name) (str var-name))))


(defn- location
  [item]
  (select-keys item [:filename :row :col :end-row :end-col]))


(defn registrations
  "Join literal impls entries to declarations. Dynamic registration stays visible."
  [filename forms declaration]
  (let [ns-name (second (first (filter #(and (seq? %) (= 'ns (first %))) forms)))
        impls-form (first (filter #(and (seq? %) (= 'def (first %)) (= 'impls (second %))) forms))
        value (last impls-form)
        declarations (into {} (map (juxt :name identity)) (:fns declaration))]
    (if (map? value)
      (mapv (fn [[graph-name registration]]
              (let [implementation (if (map? registration) (:impl registration) registration)
                    definition (get declarations graph-name)
                    entry-position (select-keys (meta registration) [:row :col :end-row :end-col])]
                {:graph-id [(:namespace declaration) graph-name]
                 :registration-map-location (assoc (select-keys (meta value) [:row :col]) :filename filename)
                 :entry-location (if (:row entry-position)
                                   (assoc entry-position :filename filename :status :resolved :anchor :registration-value)
                                   {:status :unresolved :reason :no-source-metadata})
                 :native-namespace ns-name
                 :implementation (when (symbol? implementation)
                                   (if (namespace implementation) implementation
                                       (qualified ns-name implementation)))
                 :registration-status (if (symbol? implementation) :literal :unsupported)
                 :metadata (when (map? registration) (dissoc registration :impl))
                 :declaration (select-keys definition [:args :return-type :effects :expects-effects])
                 :declaration-present? (some? definition)}))
            (sort-by (comp str key) value))
      [{:registration-map-location {:filename filename}
        :entry-location {:status :unresolved :reason :nonliteral-or-missing-impls}
        :registration-status :unsupported
        :reason :nonliteral-or-missing-impls}])))


(defn- usage-edge
  [definitions usage]
  (let [unresolved? (or (nil? (:to usage))
                        (#{:clj-kondo/unknown-namespace 'clj-kondo/unknown-namespace} (:to usage)))
        target (if unresolved? (:name usage) (qualified (:to usage) (:name usage)))]
    (merge (location usage)
           {:target target
            :kind (cond (:macro usage) :macro
                        (contains? usage :arity) :call
                        :else :var-value)
            :resolution (cond
                          unresolved? :unresolved
                          (contains? definitions target) :source
                          :else :external)})))


(defn source-graph
  "Resolved usages from kondo retain lexical scoping and HOF var references.
   Hook-generated locations are excluded; duplicate defbase arities collapse."
  [analysis]
  (let [definitions (into {} (map (fn [d] [(qualified (:ns d) (:name d)) (location d)]))
                          (:var-definitions analysis))
        usages (->> (:var-usages analysis)
                    (filter :from-var)
                    (remove :derived-location)
                    (group-by #(qualified (:from %) (:from-var %))))]
    (into (sorted-map)
          (map (fn [[id loc]]
                 [id (assoc loc :edges (->> (get usages id)
                                            (map #(usage-edge definitions %))
                                            distinct
                                            (sort-by (juxt :row :col (comp str :target)))
                                            vec))]))
          definitions)))


(defn helper-paths
  "One shortest evidence path per reachable source var, bounded and cycle-safe.
   Var-value edges are dependencies, not a claim that a callback executes."
  [graph start max-depth max-nodes]
  (loop [pending [[start]] seen #{} paths [] depth-limit? false]
    (if (or (empty? pending) (>= (count seen) max-nodes))
      (let [node-limit? (boolean (some #(and (contains? graph (peek %))
                                             (not (contains? seen (peek %)))) pending))
            reasons (cond-> [] depth-limit? (conj :depth) node-limit? (conj :nodes))]
        {:paths paths :truncated? (boolean (seq reasons)) :limit-reasons reasons})
      (let [path (first pending)
            node (peek path)
            tail (subvec pending 1)]
        (if (or (contains? seen node) (not (contains? graph node)))
          (recur tail seen paths depth-limit?)
          (let [targets (->> (get-in graph [node :edges])
                             (filter #(= :source (:resolution %)))
                             (map :target)
                             (remove (conj seen node))
                             distinct)
                limit? (>= (dec (count path)) max-depth)]
            (recur (if limit? tail (into tail (map #(conj path %) targets)))
                   (conj seen node) (conj paths path)
                   (or depth-limit? (and limit? (boolean (seq targets)))))))))))


(defn- entry-name
  [entry]
  (let [[ns-path graph-name] (:graph-id entry)]
    (when graph-name (str ns-path "/" (name graph-name)))))


(defn- traversal-coverage
  [entries]
  {:registrations (count entries)
   :truncated-entries (count (filter #(get-in % [:helper-paths :truncated?]) entries))
   :limit-reasons (frequencies (mapcat #(get-in % [:helper-paths :limit-reasons]) entries))})


(defn inventory
  "Produce review data, retaining the existing reachability registry as context."
  ([entries analysis reachability] (inventory entries analysis reachability {}))
  ([entries analysis reachability {:keys [entry max-depth max-nodes]
                                   :or {max-depth 6 max-nodes 128}}]
   (let [graph (source-graph analysis)
         _ (when (and entry (not-any? #(= entry (entry-name %)) entries))
             (throw (ex-info "No matching registered graph function" {:entry entry})))
         aliases (into {} (keep (fn [{:keys [from to] ns-alias :alias}]
                                  (when ns-alias [[from (str ns-alias)] to])))
                       (:namespace-usages analysis))
         corpus-entries (mapv (fn [entry]
                                (let [implementation (:implementation entry)
                                      alias-target (when implementation
                                                     (get aliases [(:native-namespace entry) (namespace implementation)]))
                                      resolved (if alias-target (qualified alias-target (name implementation)) implementation)]
                                  (assoc entry :implementation resolved
                                         :review-status :unreviewed
                                         :implementation-status (if (contains? graph resolved) :source :unresolved)
                                         :helper-paths (helper-paths graph resolved max-depth max-nodes))))
                              entries)
         entries (if entry (filterv #(= entry (entry-name %)) corpus-entries) corpus-entries)
         reported-graph (if entry
                          (select-keys graph (mapcat #(map peek (get-in % [:helper-paths :paths])) entries))
                          graph)]
     {:schema-version 1
      :scope {:sources ["src/**/*.clj" "resources/packages/**/impls.clj"]
              :entry entry :max-depth max-depth :max-nodes-per-entry max-nodes
              :limitations [:static-var-dependencies :dynamic-dispatch-not-resolved
                            :macro-expansion-limited-to-kondo-hooks :no-purity-or-dead-code-verdict]}
      :reachability {:source "tools/graph-reachability.edn" :registry reachability}
      :coverage (assoc (traversal-coverage entries)
                       :source-vars (count graph)
                       :reported-source-vars (count reported-graph)
                       :corpus (traversal-coverage corpus-entries)
                       :unsupported-registrations (count (remove #(= :literal (:registration-status %)) entries)))
      :entries entries
      :source-graph reported-graph})))


(defn analyze
  "Ask the pinned analyzer to read paths; analyzed code is never evaluated."
  [root paths]
  (let [config {:output {:format :edn :analysis true :progress false :canonical-paths false}}
        result @(process/process (into ["clojure" "-J-Xmx512m" "-M:kondo" "--lint"]
                                       (concat paths ["--config" (pr-str config)]))
                                 {:dir (str root) :out :string :err :string})
        parsed (try (edn/read-string (:out result))
                    (catch Exception error
                      (throw (ex-info "Cannot read kondo analysis" {:exit (:exit result)} error))))]
    ;; Kondo uses nonzero exit for findings; analysis remains useful. A failed
    ;; invocation or missing analysis must never yield a reassuring empty report.
    (when-not (and (map? (:analysis parsed)) (seq (get-in parsed [:analysis :var-definitions])))
      (throw (ex-info "Kondo returned no source analysis" {:exit (:exit result)})))
    parsed))


(defn scan
  "Analyze the current checkout with its pinned kondo and defbase hook."
  [root options]
  (let [identity-before (source-identity root)
        paths (sort-by str (fs/glob root "resources/packages/**/impls.clj"))
        entries (vec (mapcat (fn [path]
                               (registrations (str (fs/relativize root path))
                                              (read-source (slurp (str path)))
                                              (edn/read-string (slurp (str (fs/path (fs/parent path) "fns.edn"))))))
                             paths))
        analyzed (analyze root ["src" "resources/packages"])
        report (assoc (inventory entries (:analysis analyzed)
                                 (edn/read-string (slurp (str (fs/path root "tools/graph-reachability.edn"))))
                                 options)
                      :analyzer {:name :clj-kondo
                                 :version (get-in (edn/read-string (slurp (str (fs/path root "deps.edn"))))
                                                  [:aliases :kondo :extra-deps 'clj-kondo/clj-kondo :mvn/version])
                                 :findings (:findings analyzed)})
        ;; All report inputs, including registry and dependency metadata, have
        ;; been consumed before closing the identity bracket.
        identity-after (source-identity root)]
    (when-not (= identity-before identity-after)
      (throw (ex-info "Inventory inputs changed during analysis; rerun on a stable checkout"
                      {:before identity-before :after identity-after})))
    (assoc report :source-identity identity-after)))


(defn cli-options
  "Parse explicit review limits; unknown or incomplete flags fail closed."
  [args]
  (loop [args args options {}]
    (if (empty? args)
      options
      (let [[flag value & remaining] args
            option ({"--entry" :entry "--max-depth" :max-depth "--max-nodes" :max-nodes} flag)]
        (when-not (and option value)
          (throw (ex-info "Expected --entry ns/name, --max-depth N or --max-nodes N" {:flag flag})))
        (let [parsed (if (= :entry option) value (parse-long value))]
          (when-not (or (= :entry option) (and parsed (<= 1 parsed 10000)))
            (throw (ex-info "Traversal limit must be between 1 and 10000" {:flag flag})))
          (recur remaining (assoc options option parsed)))))))


(when (= *file* (System/getProperty "babashka.file"))
  (let [report (scan (fs/canonicalize ".") (cli-options *command-line-args*))]
    (binding [*out* *err*]
      (println "Native inventory (advisory, all entries unreviewed):" (pr-str (:coverage report))))
    (pp/pprint report)))
