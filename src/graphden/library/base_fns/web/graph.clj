(ns graphden.library.base-fns.web.graph
  "Graph visualization base functions for Cytoscape.js integration.

   Provides base functions for converting Graphden entities to Cytoscape format:
   - entities-to-cytoscape: Converts fn/arg entities to Cytoscape elements
   - cytoscape-init-script: Generates initialization JavaScript

   ## 2-Entity Schema

   The graph uses a minimal 2-entity schema:
   - fn: function entity (parent-id=nil for base-fn, parent-id set for composed)
   - arg: argument entity (source-id for inheritance, value/ref-id for data)

   ## Cytoscape Element Format

   Cytoscape expects elements in this format:
   {:nodes [{:data {:id \"uuid\" :label \"name\" :type \"fn\"}}]
    :edges [{:data {:id \"edge-id\" :source \"uuid1\" :target \"uuid2\"}}]}

   ## Node Types

   - fn (base): Function with Clojure implementation (parent-id=nil)
   - fn (composed): Function referencing a parent (parent-id set)
   - arg: Argument with value or reference"
  (:require
    [cheshire.core :as json]
    [graphden.executor.registry.macros :refer [defbase]]))


;; =============================================================================
;; Entity to Cytoscape Conversion
;; =============================================================================

(defn- fn->node
  "Converts fn entity to Cytoscape node."
  [{:keys [id parent-id return-type] entity-name :name}]
  (let [is-base? (nil? parent-id)]
    {:data {:id (str id)
            :label (if entity-name (name entity-name) "unnamed")
            :type "fn"
            :is-base is-base?
            :parent-id (when parent-id (str parent-id))
            :return-type (when return-type (name return-type))}}))


(defn- arg->node
  "Converts arg entity to Cytoscape node."
  [{:keys [id fn-id source-id value ref-id is-fn required] entity-name :name arg-type :type}]
  (let [has-value? (or (some? value) (some? ref-id))
        ;; ref-type: "literal" for value, "fn-ref" for ref-id, "unset" otherwise
        ref-type (cond
                   (some? ref-id) "fn-ref"
                   (some? value) "literal"
                   :else "unset")
        ;; is-ref: true if ref-id is set (fn reference)
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
  "Creates edges between entities based on relationships."
  [{:keys [fns args]}]
  (let [;; fn → parent-fn edges (for composed functions)
        fn-parent-edges
        (for [f fns
              :when (:parent-id f)]
          {:data {:id (str "e-parent-" (:id f))
                  :source (str (:id f))
                  :target (str (:parent-id f))
                  :type "inherits"}})

        ;; fn → arg edges
        fn-arg-edges
        (for [arg args]
          {:data {:id (str "e-fn-arg-" (:id arg))
                  :source (str (:fn-id arg))
                  :target (str (:id arg))
                  :type "has-arg"}})

        ;; arg → source-arg edges (for inherited args)
        arg-source-edges
        (for [arg args
              :when (:source-id arg)]
          {:data {:id (str "e-source-" (:id arg))
                  :source (str (:id arg))
                  :target (str (:source-id arg))
                  :type "inherits-from"}})

        ;; arg → ref-fn edges (for fn references)
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


(defbase entities-to-cytoscape
  "Converts Graphden entities to Cytoscape.js element format.

   Arguments:
   - entities: Map with keys :fns and :args
               Each value is a sequence of entity maps.

   Returns:
   {:nodes [...] :edges [...]} suitable for Cytoscape initialization."
  {:args {:entities :jsonb}
   :return-type :jsonb}
  (let [{:keys [fns args]
         :or {fns [] args []}}
        entities

        fn-nodes (mapv fn->node fns)
        arg-nodes (mapv arg->node args)
        all-nodes (vec (concat fn-nodes arg-nodes))
        edges (create-edges entities)]

    {:nodes all-nodes
     :edges edges}))


;; =============================================================================
;; Cytoscape Initialization
;; =============================================================================

(def ^:private default-style
  "Default Cytoscape stylesheet for 2-entity schema."
  [{:selector "node"
    :style {:label "data(label)"
            :text-valign "center"
            :text-halign "center"
            :font-size "12px"
            :width 80
            :height 40}}

   ;; Base fn - solid border
   {:selector "node[type='fn'][is-base]"
    :style {:background-color "#fff"
            :border-width 2
            :border-color "#000"
            :shape "round-rectangle"}}

   ;; Composed fn - solid border (same as base)
   {:selector "node[type='fn'][!is-base]"
    :style {:background-color "#fff"
            :border-width 2
            :border-color "#000"
            :shape "round-rectangle"}}

   ;; Arg with value
   {:selector "node[type='arg'][has-value]"
    :style {:background-color "#f5f5f5"
            :border-width 1
            :border-color "#000"
            :shape "rectangle"
            :width 100
            :height 30}}

   ;; Arg without value (unset)
   {:selector "node[type='arg'][!has-value]"
    :style {:background-color "#fff"
            :border-width 1
            :border-color "#999"
            :border-style "dashed"
            :shape "rectangle"
            :width 100
            :height 30}}

   ;; Edges
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
  "Default Cytoscape layout configuration."
  {:name "dagre"
   :rankDir "TB"
   :nodeSep 50
   :edgeSep 10
   :rankSep 100})


(defbase cytoscape-init-script
  "Generates JavaScript to initialize Cytoscape graph.

   Arguments:
   - container-id: ID of the container element
   - elements: Cytoscape elements {:nodes [...] :edges [...]}
   - style: Cytoscape style array (optional, uses default)
   - layout: Layout configuration (optional, uses dagre)
   - on-click: JavaScript callback for node clicks (optional)

   Returns:
   JavaScript code as string."
  {:args {:container-id :text
          :elements :jsonb
          :style {:type :jsonb :required false}
          :layout {:type :jsonb :required false}
          :on-click {:type :text :required false}}
   :return-type :text}
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


(defbase cytoscape-update-script
  "Generates JavaScript to update existing Cytoscape graph.

   Arguments:
   - elements: New Cytoscape elements {:nodes [...] :edges [...]}
   - layout: Whether to re-run layout (optional, default true)

   Returns:
   JavaScript code as string."
  {:args {:elements :jsonb
          :layout {:type :bool :required false}}
   :return-type :text}
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


;; =============================================================================
;; Exports
;; =============================================================================

(def all-defs
  "All graph visualization base function definitions."
  {:entities-to-cytoscape entities-to-cytoscape
   :cytoscape-init-script cytoscape-init-script
   :cytoscape-update-script cytoscape-update-script})
