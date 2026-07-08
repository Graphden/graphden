(ns graphden.packages.manifest
  "The `executor-packages.edn` manifest — the operator's SINGLE data file
   listing external impl-packages (Type-2, PACKAGE_DISTRIBUTION § 5). Each
   entry names a package (whose `packages/<name>/` resources ride the
   classpath via its dependency coordinate) plus that coordinate:

     {:packages [{:name \"telegram\"
                  :lib  com.acme/graphden-telegram
                  :coord {:git/url \"https://github.com/acme/graphden-telegram\"
                          :git/sha \"abc123\"}}
                 {:name \"s3\" :lib com.acme/graphden-s3
                  :coord {:mvn/version \"1.2.0\"}}]}

   Two consumers read it so the operator edits ONE file, not deps + config:

   - `build.clj` merges `extra-deps` into the uberjar basis, so the external
     packages' resources are bundled.
   - `:app/packages` appends `package-names` to the loaded package list, so
     the loader picks them up at runtime.

   (Dev classpath is Clojure-native: add the coordinate to `deps.edn` or a
   gitignored `deps.local.edn` `:local/root` override while developing the
   package — see § 15.)"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]))


(def default-resource "executor-packages.edn")


(defn read-manifest
  "Parse the manifest classpath resource into `{:packages [...]}`, or nil when
   absent (the common case — no external impl-packages)."
  ([] (read-manifest default-resource))
  ([resource]
   (when-let [r (io/resource resource)]
     (with-open [rdr (java.io.PushbackReader. (io/reader r))]
       (edn/read rdr)))))


(defn package-names
  "External package names to load, from a parsed manifest (or nil → [])."
  [manifest]
  (into [] (keep :name) (:packages manifest)))


(defn extra-deps
  "`{lib coord}` map for the build basis, from a parsed manifest (or nil → {}).
   Entries missing `:lib` or `:coord` are skipped (a name-only entry is a
   package already on the classpath by other means)."
  [manifest]
  (into {}
        (keep (fn [e] (when (and (:lib e) (:coord e)) [(:lib e) (:coord e)])))
        (:packages manifest)))
