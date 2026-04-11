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
  "Load JavaScript from external files (modular structure)."
  [_args]
  (str
    ;; Load modules in dependency order
    (load-resource "packages/app/editor/editor-state.js")   "\n\n"
    (load-resource "packages/app/editor/editor-data.js")    "\n\n"
    (load-resource "packages/app/editor/editor-layout.js")  "\n\n"
    (load-resource "packages/app/editor/editor-overlays.js") "\n\n"
    (load-resource "packages/app/editor/editor-ui.js")      "\n\n"
    (load-resource "packages/app/editor/editor-cytoscape.js") "\n\n"
    (load-resource "packages/app/editor/editor-main.js")))


(defn editor-body
  "Return the editor HTML body as Hiccup."
  [_args]
  [:div {:id "app"}
   [:div {:id "main-container"}
    ;; Side menu
    [:div {:id "side-menu"}
     [:div {:class "menu-header"}
      [:h2 "Graphden"]]
     [:div {:id "search-bar"}
      [:input {:id "search-input" :type "text" :placeholder "Filter..."
               :oninput "onSearchInput(this.value)"}]
      [:button {:id "search-clear" :onclick "clearSearch()"} "\u00D7"]]
     [:div {:id "entity-list"}]]
    ;; Graph container
    [:div {:id "graph-container"}
     [:div {:id "cy"}]
     ;; Navigation controls
     [:div {:id "nav-controls"}
      ;; Zoom slider (vertical): + on top, slider, − on bottom
      [:div {:class "nav-zoom-col"}
       [:button {:class "nav-btn" :onclick "navZoom(1)" :title "Zoom in"} "+"]
       [:input {:id "zoom-slider" :type "range" :min "10" :max "300" :value "100"
                :orient "vertical"
                :oninput "navZoomTo(this.value/100)" :title "Zoom"}]
       [:button {:class "nav-btn" :onclick "navZoom(-1)" :title "Zoom out"} "−"]]
      ;; Bottom row: [go to root] [reset positions] [reset zoom]
      [:div {:class "nav-bottom-row"}
       [:button {:class "nav-btn" :onclick "navGoToRoot()" :title "Go to root node"} "⌂"]
       [:button {:class "nav-btn" :onclick "navResetPositions()" :title "Reset all node positions"} "⟲"]
       [:button {:class "nav-btn" :onclick "navResetZoom()" :title "Reset zoom"} "⊙"]]]]]])


(defn editor-style-element
  "Wrap CSS in a style element."
  [_args]
  [:style (load-resource "packages/app/editor/editor-styles.css")])


(defn editor-script-element
  "Wrap JavaScript in a script element."
  [_args]
  [:script (str
             ;; Load modules in dependency order
             (load-resource "packages/app/editor/editor-state.js")   "\n\n"
             (load-resource "packages/app/editor/editor-data.js")    "\n\n"
             (load-resource "packages/app/editor/editor-layout.js")  "\n\n"
             (load-resource "packages/app/editor/editor-overlays.js") "\n\n"
             (load-resource "packages/app/editor/editor-ui.js")      "\n\n"
             (load-resource "packages/app/editor/editor-cytoscape.js") "\n\n"
             (load-resource "packages/app/editor/editor-main.js"))])


;; === Registry ===

(def impls
  {:editor-styles editor-styles
   :editor-script editor-script
   :editor-body editor-body
   :editor-style-element editor-style-element
   :editor-script-element editor-script-element})
