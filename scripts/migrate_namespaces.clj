#!/usr/bin/env bb
;; Namespace migration script
;; Moves files and updates all namespace references

(ns migrate-namespaces
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]))

;; Mapping: old-ns-prefix -> new-ns-prefix
(def ns-mappings
  {"graphden.storage-protocol" "graphden.storage.protocol"
   "graphden.postgres-storage" "graphden.storage.postgres"
   "graphden.graph-storage-age" "graphden.storage.age"
   "graphden.data-schema-protocol" "graphden.schema.protocol"
   "graphden.field-types" "graphden.schema.fields"
   "graphden.malli-data-schema" "graphden.schema.malli"
   "graphden.graph-data-schema" "graphden.schema.graph"
   "graphden.versioned-data-schema" "graphden.schema.versioned"
   "graphden.value-traits-schema" "graphden.schema.traits"
   "graphden.executor" "graphden.executor"
   "graphden.fn-registry" "graphden.executor.registry"
   "graphden.fn-composition" "graphden.executor.composition"
   "graphden.base-functions" "graphden.executor.base-fns"
   "graphden.versioned-storage" "graphden.versioning.storage"
   "graphden.merge-protection" "graphden.versioning.merge"
   "graphden.http-kit-fns" "graphden.web.http-kit"
   "graphden.reitit-fns" "graphden.web.reitit"
   "graphden.web-server-fns" "graphden.web.server"})

;; Component dir -> old ns prefix
(def component-mappings
  {"storage-protocol" "graphden.storage-protocol"
   "postgres-storage" "graphden.postgres-storage"
   "graph-storage-age" "graphden.graph-storage-age"
   "data-schema-protocol" "graphden.data-schema-protocol"
   "field-types" "graphden.field-types"
   "malli-data-schema" "graphden.malli-data-schema"
   "graph-data-schema" "graphden.graph-data-schema"
   "versioned-data-schema" "graphden.versioned-data-schema"
   "value-traits-schema" "graphden.value-traits-schema"
   "executor" "graphden.executor"
   "fn-registry" "graphden.fn-registry"
   "fn-composition" "graphden.fn-composition"
   "base-functions" "graphden.base-functions"
   "versioned-storage" "graphden.versioned-storage"
   "merge-protection" "graphden.merge-protection"
   "http-kit-fns" "graphden.http-kit-fns"
   "reitit-fns" "graphden.reitit-fns"
   "web-server-fns" "graphden.web-server-fns"})

(defn ns->path
  "Convert namespace to file path (without extension)"
  [ns-str]
  (-> ns-str
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn transform-ns
  "Transform old namespace to new namespace"
  [old-ns]
  (reduce (fn [ns [old-prefix new-prefix]]
            (if (str/starts-with? ns old-prefix)
              (str/replace ns old-prefix new-prefix)
              ns))
          old-ns
          ;; Sort by length descending to match longest prefix first
          (sort-by (comp - count first) ns-mappings)))

(defn update-ns-in-content
  "Update all namespace references in file content"
  [content]
  (reduce (fn [c [old-prefix new-prefix]]
            ;; Replace namespace references
            ;; Pattern: word boundary + old-prefix (followed by . or end/space)
            (-> c
                ;; In ns declaration
                (str/replace (re-pattern (str "\\b" (java.util.regex.Pattern/quote old-prefix) "\\b"))
                             new-prefix)
                ;; In requires/imports
                (str/replace (re-pattern (str "\\[" (java.util.regex.Pattern/quote old-prefix)))
                             (str "[" new-prefix))))
          content
          ;; Sort by length descending to match longest prefix first
          (sort-by (comp - count first) ns-mappings)))

(defn process-file!
  "Process a single file: update content and move to new location"
  [src-file src-type base-dir]
  (let [content (slurp (str src-file))
        ;; Extract namespace from file
        ns-match (re-find #"\(ns\s+([a-zA-Z0-9._-]+)" content)
        old-ns (second ns-match)]
    (when old-ns
      (let [new-ns (transform-ns old-ns)
            new-content (update-ns-in-content content)
            new-rel-path (str (ns->path new-ns) ".clj")
            new-file (fs/path base-dir src-type new-rel-path)]
        ;; Create parent dirs
        (fs/create-dirs (fs/parent new-file))
        ;; Write new file
        (spit (str new-file) new-content)
        (println (str "  " old-ns " -> " new-ns))))))

(defn migrate-component!
  "Migrate a single component"
  [component-name base-dir]
  (let [component-dir (fs/path base-dir "components" component-name)]
    (println (str "\nMigrating " component-name "..."))

    ;; Process src files
    (when (fs/exists? (fs/path component-dir "src"))
      (doseq [f (fs/glob (fs/path component-dir "src") "**/*.clj")]
        (process-file! f "src" base-dir)))

    ;; Process test files
    (when (fs/exists? (fs/path component-dir "test"))
      (doseq [f (fs/glob (fs/path component-dir "test") "**/*.clj")]
        (process-file! f "test" base-dir)))

    ;; Copy resources if any
    (let [resources-dir (fs/path component-dir "resources")]
      (when (fs/exists? resources-dir)
        (doseq [f (fs/glob resources-dir "**/*")
                :when (fs/regular-file? f)]
          (let [rel (fs/relativize resources-dir f)
                dest (fs/path base-dir "resources" (str rel))]
            (fs/create-dirs (fs/parent dest))
            (fs/copy f dest {:replace-existing true})
            (println (str "  [resource] " (str rel)))))))))

(defn -main [& _args]
  (let [base-dir "/root/projects/graphden"]
    (println "Starting namespace migration...")
    (println "================================")

    ;; Create resources dir
    (fs/create-dirs (fs/path base-dir "resources"))

    ;; Migrate all components
    (doseq [component (keys component-mappings)]
      (migrate-component! component base-dir))

    (println "\n================================")
    (println "Migration complete!")
    (println "Next steps:")
    (println "1. Update deps.edn paths")
    (println "2. Update tests.edn paths")
    (println "3. Run tests")
    (println "4. Delete components/ directory")))

(-main)
