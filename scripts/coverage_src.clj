#!/usr/bin/env bb
;; Generate the coverage-mode symlink tree for the package layer.
;;
;; The package loader `eval`s every `resources/packages/<pkg>/<mod>/impls.clj`
;; from its RESOURCE path, so cloverage — which locates a namespace's source
;; at the classpath position derived from its NAME
;; (`graphden.packages.app-base.prefs.impls` →
;; `graphden/packages/app_base/prefs/impls.clj`) — never finds, never
;; instruments, and never reports the impls namespaces. This script mirrors
;; each impls.clj as a symlink at exactly that derived position under
;; `<out-dir>` (default `target/coverage-src`); the `:coverage` deps alias
;; puts the directory on the classpath and `tests-coverage.edn` lists it in
;; `:src-ns-path`. Regenerated on every `bb coverage` — never committed.
;;
;;   bb scripts/coverage_src.clj [<resources-root> ...] [--out <out-dir>]
;;
;; Defaults: resources-root = resources/packages, out = target/coverage-src.
;; graphden-tenancy runs the same script over its own resources.
(ns coverage-src
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]))


(defn- ns-sym-of
  "The `ns` form's name from an impls.clj, or nil. A regex over the
   head of the file, not a read: impls.clj is Clojure (`@`, `#(`, …),
   which the EDN reader refuses."
  [file]
  (some->> (re-find #"(?s)\(ns\s+([\w.\-*+!?<>=/]+)" (slurp (str file)))
           second
           symbol))


(defn- ns->path
  [ns-sym]
  (str (-> (name ns-sym)
           (str/replace "-" "_")
           (str/replace "." "/"))
       ".clj"))


(defn -main
  [& args]
  (let [[roots [_ out]] (split-with #(not= "--out" %) args)
        roots (if (seq roots) roots ["resources/packages"])
        out (or out "target/coverage-src")]
    (fs/delete-tree out)
    (let [links (for [root roots
                      file (fs/glob root "**/impls.clj")
                      :let [ns-sym (ns-sym-of file)]
                      :when ns-sym]
                  [(fs/path out (ns->path ns-sym)) (fs/absolutize file)])]
      (doseq [[link target] links]
        (fs/create-dirs (fs/parent link))
        (fs/create-sym-link link target))
      (println (str "coverage-src: " (count links) " impls namespaces mirrored under " out)))))


(apply -main *command-line-args*)
