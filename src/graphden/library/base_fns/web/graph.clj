(ns graphden.library.base-fns.web.graph
  "Graph visualization base functions for Cytoscape.js integration.

   Provides base functions for converting Graphden entities to Cytoscape format:
   - entities-to-cytoscape: Converts fn/fn-schema entities to Cytoscape elements
   - cytoscape-init-script: Generates initialization JavaScript

   ## Cytoscape Element Format

   Cytoscape expects elements in this format:
   {:nodes [{:data {:id \"uuid\" :label \"name\" :type \"fn\"}}]
    :edges [{:data {:id \"edge-id\" :source \"uuid1\" :target \"uuid2\"}}]}

   ## Node Types

   - fn-schema: Function signature (blue)
   - fn: Function instance (green)
   - arg-schema: Argument definition (orange)
   - arg-value: Bound argument value (gray)
   - call-site: Call site reference (purple)"
  (:require
    [cheshire.core :as json]
    [graphden.executor.registry.macros :refer [defbase]]))


;; =============================================================================
;; Entity to Cytoscape Conversion
;; =============================================================================

(defn- fn-schema-to-node
  "Converts fn-schema entity to Cytoscape node."
  [{:keys [id returned-type base-fn-name] entity-name :name}]
  {:data {:id (str id)
          :label (name entity-name)
          :type "fn-schema"
          :returned-type (when returned-type (name returned-type))
          :base-fn-name (when base-fn-name (name base-fn-name))
          :is-base-fn (boolean base-fn-name)}})


(defn- fn->node
  "Converts fn entity to Cytoscape node."
  [{:keys [id fn-schema-id] entity-name :name}]
  {:data {:id (str id)
          :label (name entity-name)
          :type "fn"
          :fn-schema-id (str fn-schema-id)}})


(defn- arg-schema-to-node
  "Converts arg-schema entity to Cytoscape node."
  [{:keys [id required fn-schema-id] entity-name :name arg-type :type}]
  {:data {:id (str id)
          :label (name entity-name)
          :type "arg-schema"
          :arg-type (when arg-type (name arg-type))
          :required (boolean required)
          :fn-schema-id (str fn-schema-id)}})


(defn- arg-value-to-node
  "Converts arg-value entity to Cytoscape node."
  [{:keys [id value owner-fn-id arg-schema-id]}]
  (let [;; Determine if value is a reference
        is-ref (and (map? value)
                    (or (:fn-id value)
                        (:call-site-id value)))
        ref-type (cond
                   (and (map? value) (:fn-id value)) "fn-ref"
                   (and (map? value) (:call-site-id value)) "call-site-ref"
                   :else "literal")
        display-value (cond
                        (and (map? value) (:fn-id value))
                        (str "ref<fn:" (:fn-id value) ">")

                        (and (map? value) (:call-site-id value))
                        (str "ref<cs:" (:call-site-id value) ">")

                        (string? value)
                        (if (> (count value) 20)
                          (str (subs value 0 20) "...")
                          value)

                        :else
                        (let [s (pr-str value)]
                          (if (> (count s) 20)
                            (str (subs s 0 20) "...")
                            s)))]
    {:data {:id (str id)
            :label display-value
            :type "arg-value"
            :ref-type ref-type
            :is-ref is-ref
            :owner-fn-id (str owner-fn-id)
            :arg-schema-id (str arg-schema-id)}}))


(defn- call-site-to-node
  "Converts call-site entity to Cytoscape node."
  [{:keys [id fn-id] entity-name :name}]
  {:data {:id (str id)
          :label (if entity-name (name entity-name) "unnamed")
          :type "call-site"
          :fn-id (str fn-id)}})


(defn- create-edges
  "Creates edges between entities based on relationships."
  [{:keys [fns arg-schemas arg-values call-sites]}]
  (let [;; fn → fn-schema edges
        fn-to-schema-edges
        (for [f fns
              :when (:fn-schema-id f)]
          {:data {:id (str "e-fn-schema-" (:id f))
                  :source (str (:id f))
                  :target (str (:fn-schema-id f))
                  :type "has-schema"}})

        ;; arg-schema → fn-schema edges
        arg-schema-edges
        (for [as arg-schemas
              :when (:fn-schema-id as)]
          {:data {:id (str "e-arg-schema-" (:id as))
                  :source (str (:fn-schema-id as))
                  :target (str (:id as))
                  :type "has-arg"}})

        ;; arg-value → owner-fn edges
        arg-value-owner-edges
        (for [av arg-values
              :when (:owner-fn-id av)]
          {:data {:id (str "e-av-owner-" (:id av))
                  :source (str (:owner-fn-id av))
                  :target (str (:id av))
                  :type "has-value"}})

        ;; arg-value → arg-schema edges
        arg-value-schema-edges
        (for [av arg-values
              :when (:arg-schema-id av)]
          {:data {:id (str "e-av-schema-" (:id av))
                  :source (str (:id av))
                  :target (str (:arg-schema-id av))
                  :type "value-for"}})

        ;; arg-value ref edges (fn-ref or call-site-ref)
        arg-value-ref-edges
        (for [av arg-values
              :let [v (:value av)]
              :when (map? v)
              :let [target-id (or (:fn-id v) (:call-site-id v))]
              :when target-id]
          {:data {:id (str "e-av-ref-" (:id av))
                  :source (str (:id av))
                  :target (str target-id)
                  :type "references"}})

        ;; call-site → fn edges
        call-site-edges
        (for [cs call-sites
              :when (:fn-id cs)]
          {:data {:id (str "e-cs-" (:id cs))
                  :source (str (:id cs))
                  :target (str (:fn-id cs))
                  :type "calls"}})]

    (vec (concat fn-to-schema-edges
                 arg-schema-edges
                 arg-value-owner-edges
                 arg-value-schema-edges
                 arg-value-ref-edges
                 call-site-edges))))


(defbase entities-to-cytoscape
  "Converts Graphden entities to Cytoscape.js element format.

   Arguments:
   - entities: Map with keys :fns, :fn-schemas, :arg-schemas, :arg-values, :call-sites
               Each value is a sequence of entity maps.

   Returns:
   {:nodes [...] :edges [...]} suitable for Cytoscape initialization."
  {:args {:entities :jsonb}
   :return-type :jsonb}
  (let [{:keys [fns fn-schemas arg-schemas arg-values call-sites]
         :or {fns [] fn-schemas [] arg-schemas [] arg-values [] call-sites []}}
        entities

        fn-schema-nodes (mapv fn-schema-to-node fn-schemas)
        fn-nodes (mapv fn->node fns)
        arg-schema-nodes (mapv arg-schema-to-node arg-schemas)
        arg-value-nodes (mapv arg-value-to-node arg-values)
        call-site-nodes (mapv call-site-to-node call-sites)

        all-nodes (vec (concat fn-schema-nodes
                               fn-nodes
                               arg-schema-nodes
                               arg-value-nodes
                               call-site-nodes))

        edges (create-edges entities)]

    {:nodes all-nodes
     :edges edges}))


;; =============================================================================
;; Cytoscape Initialization
;; =============================================================================

(def ^:private default-style
  "Default Cytoscape stylesheet."
  [{:selector "node"
    :style {:label "data(label)"
            :text-valign "center"
            :text-halign "center"
            :font-size "12px"
            :width 60
            :height 60}}

   {:selector "node[type='fn-schema']"
    :style {:background-color "#4A90D9"
            :shape "rectangle"
            :width 80
            :height 40}}

   {:selector "node[type='fn']"
    :style {:background-color "#5CB85C"
            :shape "ellipse"}}

   {:selector "node[type='arg-schema']"
    :style {:background-color "#F0AD4E"
            :shape "diamond"
            :width 50
            :height 50}}

   {:selector "node[type='arg-value']"
    :style {:background-color "#999999"
            :shape "round-rectangle"
            :width 70
            :height 35}}

   {:selector "node[type='arg-value'][is-ref]"
    :style {:background-color "#D9534F"}}

   {:selector "node[type='call-site']"
    :style {:background-color "#9B59B6"
            :shape "hexagon"}}

   {:selector "edge"
    :style {:width 2
            :line-color "#CCCCCC"
            :target-arrow-color "#CCCCCC"
            :target-arrow-shape "triangle"
            :curve-style "bezier"}}

   {:selector "edge[type='has-schema']"
    :style {:line-color "#4A90D9"
            :target-arrow-color "#4A90D9"
            :line-style "dashed"}}

   {:selector "edge[type='references']"
    :style {:line-color "#D9534F"
            :target-arrow-color "#D9534F"}}

   {:selector "edge[type='calls']"
    :style {:line-color "#9B59B6"
            :target-arrow-color "#9B59B6"}}

   {:selector ":selected"
    :style {:border-width 3
            :border-color "#000000"}}])


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
