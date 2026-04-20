(ns graphden.packages.app.editor.impls
  "Graph editor implementations.
   CSS and JS loaded from external resource files."
  (:require
    [clojure.java.io :as io]
    [graphden.executor.defbase :refer [defbase]]))


;; === Resource Loading ===

(defn- load-resource
  "Load a resource file from classpath."
  [path]
  (if-let [resource (io/resource path)]
    (slurp resource)
    (throw (ex-info (str "Resource not found: " path)
                    {:type :execution-error/resource-not-found
                     :path path}))))


;; === Implementations ===

(defbase editor-styles
  "Load CSS from external file."
  []
  (load-resource "packages/app/editor/editor-styles.css"))


;; === Registry ===
;; editor-script is now a fn-def composing :concat-resources + :const paths

(def impls
  {:editor-styles editor-styles})
