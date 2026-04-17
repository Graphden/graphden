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


(defn- truncate [s max-len]
  (if (> (count s) max-len) (str (subs s 0 max-len) "...") s))

(defn format-display-value
  [{:keys [value ref-id max-length]}]
  (let [n (or max-length 20)]
    (cond
      ref-id              (str "ref<fn:" ref-id ">")
      (string? value)     (truncate value n)
      (some? value)       (truncate (pr-str value) n)
      :else               "unset")))


(defn- arg->node
  [{:keys [id fn-id source-id value ref-id is-fn required] entity-name :name arg-type :type}]
  (let [has-value? (or (some? value) (some? ref-id))
        ref-type (cond (some? ref-id) "fn-ref" (some? value) "literal" :else "unset")
        display-value (format-display-value {:value value :ref-id ref-id})]
    {:data {:id (str id)
            :label (str (if entity-name (name entity-name) "?") ": " display-value)
            :type "arg"
            :fn-id (str fn-id)
            :source-id (when source-id (str source-id))
            :arg-type (when arg-type (name arg-type))
            :has-value has-value?
            :is-fn (boolean is-fn)
            :is-ref (some? ref-id)
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

(defn cytoscape-init-script
  [{:keys [container-id elements style layout on-click]}]
  (let [style-json (json/generate-string style)
        layout-json (json/generate-string layout)
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
  (let [elements-json (json/generate-string elements)]
    (str "
(function() {
  var cy = window.graphdenCy;
  if (!cy) { console.error('Cytoscape not initialized'); return; }

  cy.elements().remove();
  cy.add(" elements-json ");
  " (when layout "cy.layout({ name: 'dagre', rankDir: 'TB' }).run();") "
  cy.fit();
})();
")))


;; === Registry ===

(def impls
  {:entities-to-cytoscape entities-to-cytoscape
   :cytoscape-init-script cytoscape-init-script
   :cytoscape-update-script cytoscape-update-script
   :format-display-value format-display-value})
