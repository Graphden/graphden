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

     import DIR --url U --token T --target BRANCH [--create] [--prune] [--dry-run]
       Read the snapshot under DIR and apply it to the NAMED branch
       (`--create` forks it; `--prune` = snapshot semantics). Prints the
       server's report (fn-ids / skipped-owned / pruned). `--dry-run`
       prints the client-side diff against the target instead of writing.

     diff DIR --url U --token T [--branch B]
       Preview what the snapshot under DIR would change against branch B
       (default main) — added / removed / changed fns, no write. The same
       diff `--dry-run` shows before push/import.

     push --local-url L --local-token LT --hub-url H --hub-token HT
          [--branch B] [--target T] [--no-prune]
       The offline workflow's publish half: snapshot the LOCAL branch B
       (default main) and apply it to the hub as branch T (default
       push/<B>), created on demand (owner-stamped, owner write-policy)
       and pruned to the snapshot. Review + merge then happen on the hub
       (diff → conflicts → merge — VERSIONING.md § HTTP API).

     pull --local-url L --local-token LT --hub-url H --hub-token HT
          [--branch B] [--target T] [--no-prune]
       The other direction: snapshot the HUB branch B (default main) and
       land it locally as branch T (default hub/<B>). Merge it into your
       local main with the normal merge flow (the editor's branch
       popover, or POST /api/branches/main/merge {source: hub/<B>}).

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
  (let [flags #{"--create" "--prune" "--include-secret-paths" "--no-prune" "--dry-run"}]
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
;; diff — what a bundle WOULD change against a target branch (client-side)
;; =============================================================================

(defn bundle-diff
  "Compare two fn-def vectors by `[namespace name]` identity: what `incoming`
   adds / removes / changes relative to `current`. `:changed` compares the
   def maps minus the identity keys, so a re-export with no real change is
   silent. Pure — the preview `push`/`import --dry-run` and `diff` all use it."
  [current incoming]
  (let [key-of (juxt :namespace :name)
        ;; nil-safe ordering key — `:namespace` is nil for root-ns defs, and
        ;; a vector containing nil isn't Comparable, so stringify both parts.
        sort-key (fn [m] [(str (:namespace m)) (str (:name m))])
        cur (into {} (map (juxt key-of identity)) current)
        inc (into {} (map (juxt key-of identity)) incoming)
        body #(dissoc % :namespace :name)]
    ;; sort the fn-def MAPS by their [namespace name] key — a bare `sort`
    ;; tries to compare the maps themselves (not Comparable) and throws a
    ;; ClassCastException the moment there are ≥2 added or ≥2 removed defs.
    {:added (vec (sort-by sort-key (map second (remove #(contains? cur (key %)) inc))))
     :removed (vec (sort-by sort-key (map second (remove #(contains? inc (key %)) cur))))
     :changed (vec (sort (for [[k v] inc
                               :let [c (get cur k)]
                               :when (and c (not= (body c) (body v)))]
                           (:name v))))}))


(defn- print-diff
  [{:keys [added removed changed]} label]
  (println (str "diff vs " label ":"))
  (println (str "  + added:   " (count added)))
  (println (str "  - removed: " (count removed)))
  (println (str "  ~ changed: " (count changed)))
  (doseq [[sym rows] [["+" added] ["-" removed]]
          nm (take 40 (map #(if (map? %) (:name %) %) rows))]
    (println (str "  " sym " " nm)))
  (doseq [nm (take 40 changed)] (println (str "  ~ " nm))))


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


(defn- post-import!
  "POST a fn-def bundle to /api/import/graph on `url`, or — when
   `:dry-run` — fetch the target branch and print the client-side diff
   instead of writing. Returns an exit code."
  [{:keys [url token target create prune no-prune dry-run]} fn-defs]
  (if dry-run
    (let [current (:fns (fetch-export! {:url url :token token :branch target}))]
      (print-diff (bundle-diff current fn-defs) (str "branch '" target "'"))
      0)
    (let [query (str "target=" target
                     (when create "&create=true")
                     (when (and prune (not no-prune)) "&prune=true"))
          resp @(http/post (str url "/api/import/graph?" query)
                           {:headers {"Authorization" (str "Bearer " token)
                                      "Content-Type" "application/edn"}
                            :body (pr-str {:fns (wire/encode-unreadable-kws fn-defs)})
                            :timeout 300000})]
      (println (str (:status resp) " " (:body resp)))
      (if (and (nil? (:error resp)) (= 200 (:status resp))) 0 1))))


(defn import!
  "Returns a process exit code."
  [opts]
  (require-opts! opts [:url :token :target])
  (let [dir (io/file (or (first (:args opts)) "."))
        fn-defs (gf/files->bundle (read-snapshot-dir dir))]
    (post-import! opts fn-defs)))


(defn diff!
  "Show what the snapshot under DIR would change against branch `--branch`
   (default main) on the instance — a client-side preview, no write."
  [opts]
  (require-opts! opts [:url :token])
  (let [dir (io/file (or (first (:args opts)) "."))
        incoming (gf/files->bundle (read-snapshot-dir dir))
        branch (or (:branch opts) "main")
        current (:fns (fetch-export! {:url (:url opts) :token (:token opts) :branch branch}))]
    (print-diff (bundle-diff current incoming) (str "branch '" branch "'"))
    0))


;; =============================================================================
;; push / pull — instance → instance transfer (the offline workflow)
;; =============================================================================

(defn- transfer!
  "Snapshot `src`'s branch and apply it to `dst` as `target` (create + prune
   by default — the push branch IS the snapshot). With `:dry-run`, print the
   client-side diff against the destination target instead of writing.
   Returns a process exit code."
  [{:keys [src-url src-token src-branch dst-url dst-token target no-prune dry-run]}]
  (let [bundle (fetch-export! {:url src-url :token src-token :branch src-branch})]
    (post-import! {:url dst-url :token dst-token :target target
                   :create true :prune (not no-prune) :dry-run dry-run}
                  (vec (:fns bundle)))))


(defn push!
  "LOCAL branch → hub branch `push/<branch>` (see the ns doc)."
  [opts]
  (require-opts! opts [:local-url :local-token :hub-url :hub-token])
  (let [branch (or (:branch opts) "main")
        target (or (:target opts) (str "push/" branch))
        code (transfer! {:src-url (:local-url opts) :src-token (:local-token opts)
                         :src-branch branch
                         :dst-url (:hub-url opts) :dst-token (:hub-token opts)
                         :target target :no-prune (:no-prune opts) :dry-run (:dry-run opts)})]
    (when (and (zero? code) (not (:dry-run opts)))
      (println (str "pushed local '" branch "' -> hub '" target
                    "'. Review + merge on the hub: diff it against main, then merge.")))
    code))


(defn pull!
  "Hub branch → LOCAL branch `hub/<branch>` (see the ns doc)."
  [opts]
  (require-opts! opts [:local-url :local-token :hub-url :hub-token])
  (let [branch (or (:branch opts) "main")
        target (or (:target opts) (str "hub/" branch))
        code (transfer! {:src-url (:hub-url opts) :src-token (:hub-token opts)
                         :src-branch branch
                         :dst-url (:local-url opts) :dst-token (:local-token opts)
                         :target target :no-prune (:no-prune opts) :dry-run (:dry-run opts)})]
    (when (and (zero? code) (not (:dry-run opts)))
      (println (str "pulled hub '" branch "' -> local '" target
                    "'. Merge it: POST /api/branches/main/merge {\"source\": \"" target
                    "\"} or use the editor's branch popover.")))
    code))


;; =============================================================================
;; entry
;; =============================================================================

(def ^:private usage
  "usage:
  clojure -M -m graphden.cli export --url URL --token TOKEN --out DIR [--branch B] [--include-secret-paths]
  clojure -M -m graphden.cli import DIR --url URL --token TOKEN --target BRANCH [--create] [--prune] [--dry-run]
  clojure -M -m graphden.cli diff DIR --url URL --token TOKEN [--branch B]
  clojure -M -m graphden.cli push --local-url L --local-token LT --hub-url H --hub-token HT [--branch B] [--target T] [--no-prune]
  clojure -M -m graphden.cli pull --local-url L --local-token LT --hub-url H --hub-token HT [--branch B] [--target T] [--no-prune]")


(defn -main
  [& argv]
  (let [[cmd & rest-argv] argv
        opts (parse-opts (vec rest-argv))
        code (try
               (case cmd
                 "export" (export! opts)
                 "import" (import! opts)
                 "diff" (diff! opts)
                 "push" (push! opts)
                 "pull" (pull! opts)
                 (fail! 2 usage))
               (catch clojure.lang.ExceptionInfo e
                 (fail! (if (= :cli/usage (:type (ex-data e))) 2 1)
                        (ex-message e))))]
    (System/exit code)))
