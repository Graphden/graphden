(ns build
  "Build script for creating uberjar.

   Usage:
     clojure -T:build clean
     clojure -T:build uber"
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b])
  (:import
    (java.io
      File)
    (java.nio.file
      Files
      Path)
    (java.security
      MessageDigest)))


(def lib 'graphden/executor-server)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/executor-server.jar")
(def build-hashes-file "graphden-build-hashes.json")


(def basis (b/create-basis {:project "deps.edn"}))


(defn clean
  [_]
  (b/delete {:path "target"}))


;; === Build-content fingerprint ===
;;
;; SHA-256 over each "section" of the deploy, mixed with relative
;; paths so renames change the digest. We split into three:
;;
;;   :frontend  — `.js` / `.css` / `.html` / `.svg` under
;;                `resources/packages/` (the editor browser bundle)
;;   :packages  — `.edn` / `.clj` under `resources/packages/`
;;                (graph definitions + their base-fn impls)
;;   :backend   — everything else under `src/` and non-package
;;                resources (`system-*.edn`, `logback.xml`, …)
;;
;; Splitting tells you WHAT changed in a deploy — a frontend-only
;; tweak shouldn't move the backend hash, etc.

(defn- relative-path
  "Forward-slash relative path of `f` under `root`. Stable across OSes."
  [^Path root ^File f]
  (-> (.relativize root (.toPath f))
      str
      (str/replace File/separator "/")))


(defn- section-of
  "Returns the section keyword (`:frontend`/`:packages`/`:backend`)
   for a class-dir-relative path, or nil if the file shouldn't be
   hashed at all (compiled `.class` artefacts, AOT cache, etc.)."
  [^String rel-path]
  (let [in-packages? (str/starts-with? rel-path "packages/")]
    (cond
      (and in-packages?
           (or (str/ends-with? rel-path ".js")
               (str/ends-with? rel-path ".css")
               (str/ends-with? rel-path ".html")
               (str/ends-with? rel-path ".svg")))
      :frontend

      (and in-packages?
           (or (str/ends-with? rel-path ".edn")
               (str/ends-with? rel-path ".clj")
               (str/ends-with? rel-path ".cljc")))
      :packages

      (or (str/ends-with? rel-path ".clj")
          (str/ends-with? rel-path ".cljc")
          (str/ends-with? rel-path ".cljs")
          (str/ends-with? rel-path ".edn")
          (str/ends-with? rel-path ".sql")
          (str/ends-with? rel-path ".xml")
          (str/ends-with? rel-path ".sh"))
      :backend)))


(defn- digest-hex
  [^MessageDigest md]
  (apply str (map #(format "%02x" (bit-and ^byte % 0xff)) (.digest md))))


(defn- compute-section-hashes
  "Walk `class-dir`, partition source files by section, and SHA-256
   each section over `(rel-path | content | sep)*` with files sorted
   by relative path. Returns `{:frontend hex :packages hex :backend hex}`."
  [class-dir]
  (let [root      (.toPath (File. ^String class-dir))
        sep       (byte-array 1 (byte 0))
        sections  (atom {:frontend (MessageDigest/getInstance "SHA-256")
                         :packages (MessageDigest/getInstance "SHA-256")
                         :backend  (MessageDigest/getInstance "SHA-256")})
        files     (->> (file-seq (File. ^String class-dir))
                       (filter (fn [^File f] (.isFile f)))
                       (map (fn [^File f]
                              [(relative-path root f) f]))
                       (sort-by first))]
    (doseq [[rel-path ^File f] files
            :let [sec (section-of rel-path)]
            :when sec]
      (let [^MessageDigest md (get @sections sec)]
        (.update md (.getBytes ^String rel-path "UTF-8"))
        (.update md sep)
        (.update md (Files/readAllBytes (.toPath f)))
        (.update md sep)))
    (into {} (map (fn [[k md]] [k (digest-hex md)]) @sections))))


(defn uber
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (let [hashes (compute-section-hashes class-dir)]
    (println "Build hashes:")
    (doseq [[k h] hashes]
      (println (format "  %-9s %s" (str (name k) ":") h)))
    ;; Plain JSON (just three string keys → string values) so the
    ;; build alias doesn't need cheshire on its classpath.
    (spit (str class-dir "/" build-hashes-file)
          (str "{"
               (str/join ","
                         (for [[k v] hashes]
                           (str \" (name k) "\":\"" v \")))
               "}")))
  (b/compile-clj {:basis basis
                  :ns-compile '[graphden.executor-runtime.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'graphden.executor-runtime.core}))
