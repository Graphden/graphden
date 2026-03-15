(ns graphden.packages.app.editor.impls
  "Graph editor implementations.
   CSS and JS loaded from external resource files.
   Body defined as Hiccup inline."
  (:require
    [clojure.java.io :as io]))


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

(defn editor-styles
  "Load CSS from external file."
  [_args]
  (load-resource "packages/app/editor/editor-styles.css"))


(defn editor-script
  "Load JavaScript from external file."
  [_args]
  (load-resource "packages/app/editor/editor-script.js"))


(defn editor-body
  "Return the editor HTML body as Hiccup."
  [_args]
  [:div {:id "app"}
   [:div {:id "main-container"}
    ;; Side menu
    [:div {:id "side-menu"}
     [:div {:class "menu-header"}
      [:h2 "Graphden"]]
     [:div {:id "entity-list"}]]
    ;; Graph container
    [:div {:id "graph-container"}
     [:div {:id "cy"}]]]])


(defn editor-style-element
  "Wrap CSS in a style element."
  [_args]
  [:style (load-resource "packages/app/editor/editor-styles.css")])


(defn editor-script-element
  "Wrap JavaScript in a script element."
  [_args]
  [:script (load-resource "packages/app/editor/editor-script.js")])


;; === Registry ===

(def impls
  {:editor-styles editor-styles
   :editor-script editor-script
   :editor-body editor-body
   :editor-style-element editor-style-element
   :editor-script-element editor-script-element})
