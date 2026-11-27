(ns graphden.input.source-package-fixtures
  "Authored package declarations and shared loaded bases for offline input tests."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [graphden.packages.loader :as loader]))


(def web-base-definitions (delay (:base-fn-defs (loader/load-packages ["core" "web"]))))


(defn declarations
  [resource names]
  (let [{module-ns :namespace :keys [fns]} (edn/read-string (slurp (io/resource resource)))]
    (for [d fns :when (contains? names (:name d))]
      (assoc d :namespace module-ns :source-file resource))))
