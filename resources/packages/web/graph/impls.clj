(ns graphden.packages.web.graph.impls
  "Implementations for web/graph base functions for Cytoscape.js."
  (:require
    [cheshire.core :as json]))


;; === Entity to Cytoscape Conversion ===

(defn- fn->node
  [{:keys [id parent-id return-type] entity-name :name}]
  (let [is-base? (nil? parent-id)]
    {:data {:id (str id)
            :label (if entity-name (name entity-name) "unnamed")
            :type "fn"
            :is-base is-base?
            :parent-id (when parent-id (str parent-id))
            :return-type (when return-type (name return-type))}}))


(defn- arg->node
  [{:keys [id fn-id source-id value ref-id is-fn required] entity-name :name arg-type :type}]
  (let [has-value? (or (some? value) (some? ref-id))
        ref-type (cond
                   (some? ref-id) "fn-ref"
                   (some? value) "literal"
                   :else "unset")
        is-ref? (some? ref-id)
        display-value (cond
                        ref-id
                        (str "ref<fn:" ref-id ">")

                        (and (some? value) (string? value))
                        (if (> (count value) 20)
                          (str (subs value 0 20) "...")
                          value)

                        (some? value)
                        (let [s (pr-str value)]
                          (if (> (count s) 20)
                            (str (subs s 0 20) "...")
                            s))

                        :else
                        "unset")]
    {:data {:id (str id)
            :label (str (if entity-name (name entity-name) "?") ": " display-value)
            :type "arg"
            :fn-id (str fn-id)
            :source-id (when source-id (str source-id))
            :arg-type (when arg-type (name arg-type))
            :has-value has-value?
            :is-fn (boolean is-fn)
            :is-ref is-ref?
            :ref-type ref-type
            :required (boolean required)
            :ref-id (when ref-id (str ref-id))}}))


(defn- create-edges
  [{:keys [fns args]}]
  (let [fn-parent-edges
        (for [f fns
              :when (:parent-id f)]
          {:data {:id (str "e-parent-" (:id f))
                  :source (str (:id f))
                  :target (str (:parent-id f))
                  :type "inherits"}})

        fn-arg-edges
        (for [arg args]
          {:data {:id (str "e-fn-arg-" (:id arg))
                  :source (str (:fn-id arg))
                  :target (str (:id arg))
                  :type "has-arg"}})

        arg-source-edges
        (for [arg args
              :when (:source-id arg)]
          {:data {:id (str "e-source-" (:id arg))
                  :source (str (:id arg))
                  :target (str (:source-id arg))
                  :type "inherits-from"}})

        arg-ref-edges
        (for [arg args
              :when (:ref-id arg)]
          {:data {:id (str "e-ref-" (:id arg))
                  :source (str (:id arg))
                  :target (str (:ref-id arg))
                  :type "references"}})]

    (vec (concat fn-parent-edges
                 fn-arg-edges
                 arg-source-edges
                 arg-ref-edges))))


(defn entities-to-cytoscape
  [{:keys [entities]}]
  (let [{:keys [fns args]
         :or {fns [] args []}}
        entities

        fn-nodes (mapv fn->node fns)
        arg-nodes (mapv arg->node args)
        all-nodes (vec (concat fn-nodes arg-nodes))
        edges (create-edges entities)]

    {:nodes all-nodes
     :edges edges}))


;; === Cytoscape Initialization ===

(def ^:private default-style
  [{:selector "node"
    :style {:label "data(label)"
            :text-valign "center"
            :text-halign "center"
            :font-size "12px"
            :width 80
            :height 40}}

   {:selector "node[type='fn'][is-base]"
    :style {:background-color "#fff"
            :border-width 2
            :border-color "#000"
            :shape "round-rectangle"}}

   {:selector "node[type='fn'][!is-base]"
    :style {:background-color "#fff"
            :border-width 2
            :border-color "#000"
            :shape "round-rectangle"}}

   {:selector "node[type='arg'][has-value]"
    :style {:background-color "#f5f5f5"
            :border-width 1
            :border-color "#000"
            :shape "rectangle"
            :width 100
            :height 30}}

   {:selector "node[type='arg'][!has-value]"
    :style {:background-color "#fff"
            :border-width 1
            :border-color "#999"
            :border-style "dashed"
            :shape "rectangle"
            :width 100
            :height 30}}

   {:selector "edge"
    :style {:width 2
            :line-color "#ccc"
            :target-arrow-color "#ccc"
            :target-arrow-shape "triangle"
            :curve-style "bezier"}}

   {:selector "edge[type='inherits']"
    :style {:line-color "#000"
            :target-arrow-color "#000"
            :line-style "dashed"}}

   {:selector "edge[type='references']"
    :style {:line-color "#666"
            :target-arrow-color "#666"}}

   {:selector ":selected"
    :style {:border-width 3
            :border-color "#000"}}])


(def ^:private default-layout
  {:name "dagre"
   :rankDir "TB"
   :nodeSep 50
   :edgeSep 10
   :rankSep 100})


(defn cytoscape-init-script
  [{:keys [container-id elements style layout on-click]}]
  (let [style-json (json/generate-string (or style default-style))
        layout-json (json/generate-string (or layout default-layout))
        elements-json (json/generate-string elements)
        click-handler (if on-click
                        (str "cy.on('tap', 'node', function(evt) { " on-click "(evt.target.data()); });")
                        "")]
    (str "
(function() {
  var cy = cytoscape({
    container: document.getElementById('" container-id "'),
    elements: " elements-json ",
    style: " style-json ",
    layout: " layout-json ",
    userZoomingEnabled: true,
    userPanningEnabled: true,
    boxSelectionEnabled: true
  });

  " click-handler "

  // Store reference globally for debugging
  window.graphdenCy = cy;

  // Fit to viewport
  cy.fit();
})();
")))


(defn cytoscape-update-script
  [{:keys [elements layout]}]
  (let [elements-json (json/generate-string elements)
        should-layout (if (nil? layout) true layout)]
    (str "
(function() {
  var cy = window.graphdenCy;
  if (!cy) { console.error('Cytoscape not initialized'); return; }

  cy.elements().remove();
  cy.add(" elements-json ");
  " (when should-layout "cy.layout({ name: 'dagre', rankDir: 'TB' }).run();") "
  cy.fit();
})();
")))


;; === Registry ===

(def impls
  {:entities-to-cytoscape entities-to-cytoscape
   :cytoscape-init-script cytoscape-init-script
   :cytoscape-update-script cytoscape-update-script})
