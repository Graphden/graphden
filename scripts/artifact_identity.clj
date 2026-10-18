(ns artifact-identity
  "Source identity embedded in the core jar. Section hashes are useful for
   browser cache keys, but do not include shipped Markdown or deps.edn."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str])
  (:import
    (java.io
      ByteArrayOutputStream)
    (java.nio.file
      Files
      Paths)
    (java.security
      MessageDigest)
    (java.util.zip
      ZipFile)))


(def manifest-path "META-INF/graphden/artifact-inputs.edn")


(defn- command-bytes
  [dir & args]
  (let [process (ProcessBuilder/.start (doto (ProcessBuilder. ^java.util.List (vec args))
                                         (ProcessBuilder/.directory (java.io.File. ^String dir))))
        out (ByteArrayOutputStream.)]
    (with-open [stream (Process/.getInputStream process)]
      (java.io.InputStream/.transferTo stream out))
    (let [err (slurp (Process/.getErrorStream process))
          exit (Process/.waitFor process)]
      (when-not (zero? exit)
        (throw (ex-info "Artifact identity command failed"
                        {:command args :exit exit :stderr err})))
      (ByteArrayOutputStream/.toByteArray out))))


(defn- sha256
  [^bytes content]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (MessageDigest/.update md content)
    (clojure.string/join (map #(format "%02x" (bit-and % 255)) (MessageDigest/.digest md)))))


(defn- committed-file-bytes?
  [dir sha]
  (let [entries (-> (String. ^bytes (command-bytes dir "git" "ls-tree" "-r" "-z" sha "--"
                                                   "src" "resources" "deps.edn" "build.clj"
                                                   "scripts/artifact_identity.clj") "UTF-8")
                    (str/split #"\u0000"))]
    (every?
      (fn [entry]
        (if-let [[_ mode id path] (re-matches #"([0-7]+) blob ([0-9a-f]+)\t(.+)" entry)]
          (let [file (Paths/get dir (into-array String [path]))
                content (Files/readAllBytes file)
                algorithm (case (count id) 40 "SHA-1" 64 "SHA-256" nil)]
            (and (#{"100644" "100755"} mode)
                 algorithm
                 (= id
                    (let [md (MessageDigest/getInstance algorithm)]
                      (MessageDigest/.update md (String/.getBytes (str "blob " (alength content) "\u0000") "UTF-8"))
                      (MessageDigest/.update md content)
                      (clojure.string/join
                        (map #(format "%02x" (bit-and % 255)) (MessageDigest/.digest md)))))))
          (str/blank? entry)))
      entries)))


(defn- clean-inputs?
  [dir sha]
  (and (str/blank?
         (String. ^bytes (command-bytes dir "git" "status" "--porcelain"
                                        "--untracked-files=all" "--"
                                        "src" "resources" "deps.edn" "build.clj"
                                        "scripts/artifact_identity.clj") "UTF-8"))
       (str/blank?
         (String. ^bytes (command-bytes dir "git" "ls-files" "--others" "--ignored"
                                        "--exclude-standard" "--" "src" "resources") "UTF-8"))
       (committed-file-bytes? dir sha)))


(defn- working-source-digest
  [dir]
  (let [root (java.io.File/.toPath (java.io.File. dir))
        md (MessageDigest/getInstance "SHA-256")]
    (doseq [file (->> ["src" "resources"]
                      (map #(java.io.File. dir %))
                      (filter #(java.io.File/.exists ^java.io.File %))
                      (mapcat file-seq)
                      (filter #(java.io.File/.isFile ^java.io.File %))
                      (sort-by #(str (java.nio.file.Path/.relativize root (java.io.File/.toPath ^java.io.File %)))))]
      (MessageDigest/.update md (String/.getBytes (str (java.nio.file.Path/.relativize root (java.io.File/.toPath file))) "UTF-8"))
      (MessageDigest/.update md (byte-array [0]))
      (MessageDigest/.update md (Files/readAllBytes (java.io.File/.toPath ^java.io.File file)))
      (MessageDigest/.update md (byte-array [0])))
    (clojure.string/join (map #(format "%02x" (bit-and % 255)) (MessageDigest/.digest md)))))


(defn- working-inputs
  [dir revision]
  {:schema 1
   :core-sha revision
   :source-digest (working-source-digest dir)
   :deps-digest (sha256 (Files/readAllBytes (Paths/get dir (into-array String ["deps.edn"]))))})


(defn core-inputs
  "Exact committed identity when Git inputs are clean. Local dirty or source
   archive builds remain usable, but their manifest is not a release SHA."
  ([] (core-inputs "."))
  ([dir]
   (try
     (let [sha (str/trim (String. ^bytes (command-bytes dir "git" "rev-parse" "HEAD") "UTF-8"))]
       (if (clean-inputs? dir sha)
         {:schema 1
          :core-sha sha
          :source-digest (sha256 (command-bytes dir "git" "ls-tree" "-r" sha "--" "src" "resources"))
          :deps-digest (sha256 (command-bytes dir "git" "show" (str sha ":deps.edn")))}
         (working-inputs dir "dirty")))
     (catch Exception _
       (working-inputs dir "unversioned")))))


(defn write-manifest!
  [class-dir inputs]
  (let [path (Paths/get class-dir (into-array String [manifest-path]))]
    (Files/createDirectories (java.nio.file.Path/.getParent path) (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (java.nio.file.Path/.toFile path) (str (pr-str inputs) "\n"))))


(defn read-jar-manifest
  [jar]
  (with-open [zip (ZipFile. jar)]
    (let [matches (->> (enumeration-seq (ZipFile/.entries zip))
                       (filter #(= manifest-path (java.util.zip.ZipEntry/.getName %))))]
      (when-not (= 1 (count matches))
        (throw (ex-info "Jar must contain exactly one artifact manifest"
                        {:count (count matches)})))
      (with-open [input (ZipFile/.getInputStream zip (first matches))]
        (edn/read-string (slurp input))))))


(defn verify-jar!
  [jar expected]
  (let [actual (read-jar-manifest jar)]
    (when-not (= expected actual)
      (throw (ex-info "Jar artifact inputs differ from source revision"
                      {:expected expected :actual actual})))
    ;; Java multi-release lookup can select a versioned resource even when the
    ;; base Markdown/JSON and our identity manifest look current. These paths
    ;; are owned by this build; no dependency may overlay them at Java 9+.
    (with-open [zip (ZipFile. jar)]
      (doseq [entry (enumeration-seq (ZipFile/.entries zip))
              :let [path (java.util.zip.ZipEntry/.getName entry)]
              :when (re-matches #"META-INF/versions/[0-9]+/packages/.*\.(?:md|json)" path)]
        (throw (ex-info "Versioned shipped resource overrides source identity"
                        {:entry path}))))
    {:inputs actual
     :jar-sha256 (sha256 (Files/readAllBytes (Paths/get jar (make-array String 0))))}))
