(ns ci-gitleaks-history
  "Reuse a successful Gitleaks history scan only across a proven descendant
   commit chain. The caller always scans the current working tree separately."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.string :as str])
  (:import
    (java.nio.file
      Files
      Path
      StandardCopyOption)
    (java.security
      MessageDigest)))


(def image "zricethezav/gitleaks:v8.30.1")
(def cache-file "ci-gitleaks-history.edn")
(def schema 2)


(defn sha256
  [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))


(defn file-sha
  [path]
  (when (fs/exists? path)
    (sha256 (Files/readAllBytes (fs/path path)))))


(defn command
  [& args]
  (let [result (apply p/shell {:out :string :err :string :continue true} args)]
    (when (zero? (:exit result))
      (str/trim (:out result)))))


(defn valid-sha?
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{40}" value))))


(defn read-cache
  [path]
  (try
    (when (and (fs/regular-file? path) (< (fs/size path) 131072))
      (edn/read-string (slurp path)))
    (catch Exception _ nil)))


(defn plan
  [receipt inputs]
  (let [{:keys [head tips config ignore image-id shallow grafts replace-refs]} inputs]
    (if (and (map? receipt)
             (= schema (:schema receipt))
             (= :passed (:result receipt))
             (valid-sha? (:head receipt))
             (vector? (:tips receipt))
             (every? valid-sha? (:tips receipt))
             (seq (:tips receipt))
             (= config (:config receipt))
             (= ignore (:ignore receipt))
             (= image-id (:image-id receipt))
             (= shallow (:shallow receipt))
             (= grafts (:grafts receipt))
             (= replace-refs (:replace-refs receipt))
             (valid-sha? head)
             (vector? tips)
             (every? valid-sha? tips))
      (cond
        (= tips (:tips receipt)) {:mode :reuse}
        :else {:mode :incremental :base-tips (:tips receipt)})
      {:mode :full})))


(defn run-gitleaks!
  [repo common log-opts]
  (let [argv (cond-> ["docker" "run" "--rm"
                      "-v" (str repo ":" repo)
                      "-v" (str common ":" common)
                      "-w" repo image "git" "--no-banner" "--redact=100"
                      "--config" (str repo "/.gitleaks.toml")]
               log-opts (conj "--log-opts" log-opts)
               true (conj "."))
        result (apply p/shell {:continue true} argv)]
    (when-not (zero? (:exit result))
      (throw (ex-info "gitleaks history scan failed" {:exit (:exit result)})))))


(defn write-cache!
  [path value]
  (let [target (fs/path path)
        temporary (Files/createTempFile (.getParent ^Path target) "gitleaks-" ".tmp"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (str temporary) (pr-str value))
      (Files/move temporary target
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (fs/delete-if-exists temporary)))))


(defn inputs
  [repo common]
  (let [head (command "git" "-C" repo "rev-parse" "HEAD")
        refs (command "git" "-C" repo "for-each-ref" "--format=%(objectname)")
        replace-refs (command "git" "-C" repo "for-each-ref"
                              "--format=%(refname) %(objectname)" "refs/replace")]
    (when (and (valid-sha? head) (some? refs) (some? replace-refs))
      {:head head
       :tips (->> (conj (str/split-lines refs) head) distinct sort vec)
       :replace-refs replace-refs
       :shallow (file-sha (str common "/shallow"))
       :grafts (file-sha (str common "/info/grafts"))
       :config (file-sha (str repo "/.gitleaks.toml"))
       :ignore (file-sha (str repo "/.gitleaksignore"))
       :image-id (command "docker" "image" "inspect" image "--format" "{{.Id}}")})))


(defn scan-repo!
  [repo common]
  (let [start (inputs repo common)
        path (str common "/" cache-file)
        decision (plan (read-cache path) start)
        mode (:mode decision)]
    (when-not (and start (:config start) (:image-id start)
                   (seq (:tips start)) (every? valid-sha? (:tips start)))
      (throw (ex-info "Gitleaks inputs unavailable" {:head (:head start)})))
    (println "▶ gitleaks history:" (name mode)
             "verified previous refs:" (count (:base-tips decision)))
    (when-not (= mode :reuse)
      (run-gitleaks! repo common
                     (when (= mode :incremental)
                       (str "--all HEAD --not " (str/join " " (:base-tips decision))))))
    (when-not (= start (inputs repo common))
      (throw (ex-info "Gitleaks inputs changed during scan" {})))
    (when-not (= mode :reuse)
      (write-cache! path (assoc start :schema schema :result :passed)))
    (println "✓ gitleaks history" (name mode) "verified at" (:head start))))


(defn scan!
  []
  (let [repo (command "git" "rev-parse" "--show-toplevel")
        common (command "git" "rev-parse" "--path-format=absolute" "--git-common-dir")]
    (when-not (and repo common)
      (throw (ex-info "cannot resolve Git repository" {})))
    (scan-repo! repo common)))


(when (= ["scan"] (vec *command-line-args*))
  (scan!))
