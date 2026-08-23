(ns graphden.cli
  "Graph ⇄ git round-trip CLI (`clojure -M -m graphden.cli …`).

   Works over HTTP ONLY — one code path against a local instance or the
   cloud, no direct Postgres access. The text layout is
   `packages.git-format` (one module file per namespace + a manifest),
   the wire endpoints are the registry package's
   `GET /api/export/graph` / `POST /api/import/graph`.

   Commands:

     export --url U --token T --out DIR [--branch B] [--include-secret-paths]
       Download branch B's graph (default: the instance's main) and lay it
       out under DIR. Existing fns/*.edn under DIR are removed first so
       the directory IS the snapshot — deletions show up as git deletions.

     import DIR --url U --token T --target BRANCH [--create] [--prune]
       Read the snapshot under DIR and apply it to the NAMED branch
       (`--create` forks it; `--prune` = snapshot semantics). Prints the
       server's report (fn-ids / skipped-owned / pruned).

   Exit codes: 0 ok, 1 the server refused (its reason is printed),
   2 bad usage / IO."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.packages.git-format :as gf]
    [graphden.packages.records.wire :as wire]
    [org.httpkit.client :as http])
  (:import
    (java.io
      File)))


(defn- fail!
  [code msg]
  (binding [*out* *err*] (println msg))
  code)


(defn- parse-opts
  "`[\"--url\" \"http://…\" \"--create\" …]` → {:url … :create true …} +
   positional args under :args. Flags without a value: --create --prune
   --include-secret-paths."
  [argv]
  (let [flags #{"--create" "--prune" "--include-secret-paths"}]
    (loop [argv argv opts {:args []}]
      (if-let [a (first argv)]
        (cond
          (contains? flags a)
          (recur (rest argv) (assoc opts (keyword (subs a 2)) true))
          (str/starts-with? a "--")
          (recur (drop 2 argv) (assoc opts (keyword (subs a 2)) (second argv)))
          :else
          (recur (rest argv) (update opts :args conj a)))
        opts))))


(defn- require-opts!
  [opts ks]
  (when-let [missing (seq (remove #(seq (str (get opts %))) ks))]
    (throw (ex-info (str "missing required option(s): "
                         (str/join " " (map #(str "--" (name %)) missing)))
                    {:type :cli/usage}))))


;; =============================================================================
;; export
;; =============================================================================

(defn- fetch-export!
  [{:keys [url token branch include-secret-paths]}]
  (let [resp @(http/get (str url "/api/export/graph"
                             (when include-secret-paths "?include-secret-paths=true"))
                        {:headers (cond-> {"Authorization" (str "Bearer " token)}
                                    branch (assoc "X-Graphden-Branch" branch))
                         :as :text :timeout 120000})]
    (when (or (:error resp) (not= 200 (:status resp)))
      (throw (ex-info (str "export failed: "
                           (or (some-> (:error resp) str) (:status resp))
                           " " (some-> (:body resp) (subs 0 (min 200 (count (str (:body resp)))))))
                      {:type :cli/http :status (:status resp)})))
    (edn/read-string {:readers wire/wire-readers} (:body resp))))


(defn- clean-snapshot-dir!
  "Remove the previous snapshot's fns/*.edn + manifest so the directory IS
   the new snapshot (a def deleted on the server becomes a git deletion)."
  [^File dir]
  (let [fns-dir (io/file dir "fns")]
    (when (File/.exists fns-dir)
      (doseq [^File f (File/.listFiles fns-dir)
              :when (str/ends-with? (File/.getName f) ".edn")]
        (File/.delete f)))
    (File/.delete (io/file dir "graphden.edn"))))


(defn export!
  "Returns a process exit code."
  [opts]
  (require-opts! opts [:url :token :out])
  (let [bundle (fetch-export! opts)
        files (gf/bundle->files (:fns bundle)
                                (cond-> {}
                                  (:branch opts) (assoc :branch (:branch opts))
                                  (seq (:secrets bundle)) (assoc :secrets (vec (:secrets bundle)))))
        dir (io/file (:out opts))]
    (clean-snapshot-dir! dir)
    (doseq [[path content] files
            :let [f (io/file dir path)]]
      (io/make-parents f)
      (spit f content))
    (println (str "exported " (count (:fns bundle)) " fn-defs into "
                  (count files) " files under " (:out opts)))
    0))


;; =============================================================================
;; import
;; =============================================================================

(defn- read-snapshot-dir
  [^File dir]
  (let [fns-dir (io/file dir "fns")
        manifest (io/file dir "graphden.edn")]
    (when-not (File/.isDirectory fns-dir)
      (throw (ex-info (str "no snapshot under " dir " — expected fns/*.edn")
                      {:type :cli/usage})))
    (into (if (File/.exists manifest)
            {"graphden.edn" (slurp manifest)}
            {})
          (map (fn [^File f] [(str "fns/" (File/.getName f)) (slurp f)]))
          (filter #(str/ends-with? (File/.getName ^File %) ".edn")
                  (File/.listFiles fns-dir)))))


(defn import!
  "Returns a process exit code."
  [opts]
  (require-opts! opts [:url :token :target])
  (let [dir (io/file (or (first (:args opts)) "."))
        fn-defs (gf/files->bundle (read-snapshot-dir dir))
        query (str "target=" (:target opts)
                   (when (:create opts) "&create=true")
                   (when (:prune opts) "&prune=true"))
        resp @(http/post (str (:url opts) "/api/import/graph?" query)
                         {:headers {"Authorization" (str "Bearer " (:token opts))
                                    "Content-Type" "application/edn"}
                          :body (pr-str {:fns (wire/encode-unreadable-kws fn-defs)})
                          :timeout 300000})]
    (println (str (:status resp) " " (:body resp)))
    (if (and (nil? (:error resp)) (= 200 (:status resp))) 0 1)))


;; =============================================================================
;; entry
;; =============================================================================

(def ^:private usage
  "usage:
  clojure -M -m graphden.cli export --url URL --token TOKEN --out DIR [--branch B] [--include-secret-paths]
  clojure -M -m graphden.cli import DIR --url URL --token TOKEN --target BRANCH [--create] [--prune]")


(defn -main
  [& argv]
  (let [[cmd & rest-argv] argv
        opts (parse-opts (vec rest-argv))
        code (try
               (case cmd
                 "export" (export! opts)
                 "import" (import! opts)
                 (fail! 2 usage))
               (catch clojure.lang.ExceptionInfo e
                 (fail! (if (= :cli/usage (:type (ex-data e))) 2 1)
                        (ex-message e))))]
    (System/exit code)))
