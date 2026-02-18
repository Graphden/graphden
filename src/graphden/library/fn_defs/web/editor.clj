(ns graphden.library.fn-defs.web.editor
  "Graph Editor fn-defs.

   This component defines fn entities (NOT base-fns) that compose
   base functions to create the graph editor UI.

   ## Architecture

   The graph editor is built using:
   - HTMX for dynamic updates without full page reloads
   - Cytoscape.js for graph visualization
   - Hiccup for HTML templating

   ## Pages

   - GET / - Main graph editor page with Cytoscape visualization
   - GET /api/entities - JSON endpoint for all entities (for Cytoscape)
   - GET /api/entities/:type - List entities of a type
   - GET /api/entities/:type/:id - Get single entity
   - POST /api/entities/:type - Create entity
   - PUT /api/entities/:type/:id - Update entity
   - DELETE /api/entities/:type/:id - Delete entity

   ## HTMX Partials

   - GET /partials/entity-form/:type - Form for creating entity
   - GET /partials/entity-form/:type/:id - Form for editing entity
   - GET /partials/entity-details/:type/:id - Entity details panel")


;; =============================================================================
;; CSS Styles (embedded for simplicity)
;; =============================================================================

(def editor-styles
  "CSS styles for the graph editor - monochrome design."
  "
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: 'SF Mono', 'Monaco', 'Inconsolata', monospace; background: #fff; color: #000; }

  .layout { display: flex; height: 100vh; }
  .sidebar { width: 280px; background: #fafafa; border-right: 2px solid #000; overflow-y: auto; }
  .main { flex: 1; display: flex; flex-direction: column; background: #fff; }

  .header { padding: 12px 16px; background: #000; color: #fff; }
  .header h1 { font-size: 16px; font-weight: 500; letter-spacing: 1px; }

  .toolbar { padding: 8px 16px; border-bottom: 1px solid #ccc; display: flex; gap: 8px; align-items: center; }
  .toolbar button { padding: 6px 14px; border: 1px solid #000; background: #fff; cursor: pointer;
    font-family: inherit; font-size: 12px; transition: all 0.1s; }
  .toolbar button:hover { background: #000; color: #fff; }
  .toolbar .separator { width: 1px; height: 20px; background: #ccc; margin: 0 8px; }
  .toolbar label { font-size: 12px; color: #666; }
  .toolbar input[type=range] { width: 80px; }
  .toolbar .depth-value { font-size: 12px; min-width: 20px; }

  .graph-container { flex: 1; position: relative; background: #fff; }
  #cy { width: 100%; height: 100%; }

  .panel { padding: 16px; }
  .panel h2 { font-size: 11px; color: #666; margin-bottom: 12px; text-transform: uppercase;
    letter-spacing: 2px; border-bottom: 1px solid #ddd; padding-bottom: 8px; }

  .entity-list { list-style: none; }
  .entity-item { padding: 10px 12px; border-bottom: 1px solid #eee; cursor: pointer;
    transition: background 0.1s; }
  .entity-item:hover { background: #f0f0f0; }
  .entity-item.selected { background: #000; color: #fff; }
  .entity-item .name { font-weight: 500; font-size: 13px; }
  .entity-item .meta { font-size: 11px; color: #888; margin-top: 2px; }
  .entity-item.selected .meta { color: #aaa; }

  .form-group { margin-bottom: 12px; }
  .form-group label { display: block; font-size: 11px; color: #666; margin-bottom: 4px;
    text-transform: uppercase; letter-spacing: 1px; }
  .form-group input, .form-group select, .form-group textarea {
    width: 100%; padding: 8px; border: 1px solid #000; font-family: inherit; font-size: 13px;
  }
  .form-group input:focus, .form-group select:focus, .form-group textarea:focus {
    outline: none; box-shadow: 0 0 0 2px rgba(0,0,0,0.1);
  }

  .btn { padding: 8px 16px; border: 1px solid #000; cursor: pointer; font-family: inherit;
    font-size: 12px; transition: all 0.1s; }
  .btn-primary { background: #000; color: #fff; }
  .btn-primary:hover { background: #333; }
  .btn-danger { background: #fff; color: #000; border-style: dashed; }
  .btn-danger:hover { background: #f5f5f5; }
  .btn-secondary { background: #fff; color: #000; }
  .btn-secondary:hover { background: #f0f0f0; }

  .details-panel { position: absolute; top: 16px; right: 16px; width: 320px; background: #fff;
    border: 2px solid #000; max-height: calc(100% - 32px); overflow-y: auto; }
  .details-panel.hidden { display: none; }
  .details-panel .panel-header { padding: 12px 16px; border-bottom: 2px solid #000; display: flex;
    justify-content: space-between; align-items: center; background: #000; color: #fff; }
  .details-panel .panel-header h3 { font-size: 13px; font-weight: 500; }
  .details-panel .close-btn { background: none; border: none; font-size: 18px; cursor: pointer;
    color: #fff; line-height: 1; }
  .details-panel .panel-body { padding: 16px; }

  .field-row { display: flex; margin-bottom: 8px; padding-bottom: 8px; border-bottom: 1px dotted #ddd; }
  .field-label { width: 90px; font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 1px; }
  .field-value { flex: 1; font-size: 13px; word-break: break-all; }

  .badge { display: inline-block; padding: 2px 8px; font-size: 10px; font-weight: 500;
    border: 1px solid #000; text-transform: uppercase; letter-spacing: 1px; }
  .badge-fn { background: #000; color: #fff; }
  .badge-base { background: #fff; color: #000; border-style: dashed; }

  .loading { text-align: center; padding: 40px; color: #888; font-size: 12px; }
  .error { background: #fff; color: #000; padding: 12px; border: 2px dashed #000; margin: 16px;
    font-size: 12px; }

  .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.7);
    display: flex; align-items: center; justify-content: center; z-index: 1000; }
  .modal-overlay.hidden { display: none; }
  .modal { background: #fff; border: 2px solid #000; width: 400px; max-width: 90%; max-height: 90%;
    overflow-y: auto; }
  .modal-header { padding: 12px 16px; border-bottom: 2px solid #000; display: flex;
    justify-content: space-between; align-items: center; background: #000; color: #fff; }
  .modal-header h3 { font-size: 13px; }
  .modal-header .close-btn { background: none; border: none; font-size: 18px; cursor: pointer;
    color: #fff; }
  .modal-body { padding: 16px; }
  .modal-footer { padding: 16px; border-top: 1px solid #ddd; display: flex; justify-content: flex-end; gap: 8px; }

  /* Schema info panel - shown on hover/click */
  .schema-info { font-size: 11px; color: #666; margin-top: 4px; padding-left: 12px;
    border-left: 2px solid #ddd; }
  .schema-info .arg { margin: 4px 0; }
  .schema-info .arg-name { font-weight: 500; }
  .schema-info .arg-type { color: #888; }
  .schema-info .arg-unset { opacity: 0.5; font-style: italic; }
  .schema-info .arg-set { font-weight: 500; }

  /* Arg value indicator */
  .arg-indicator { display: inline-block; width: 8px; height: 8px; border: 1px solid #000;
    margin-right: 4px; }
  .arg-indicator.set { background: #000; }
  .arg-indicator.unset { background: #fff; }
  ")


;; =============================================================================
;; JavaScript for Cytoscape and HTMX Integration
;; =============================================================================

(def editor-script
  "JavaScript for graph editor functionality - fn-centric visualization."
  "
  let cy = null;
  let selectedNode = null;
  let graphData = null;
  let maxDepth = 3;

  // Build lookup maps for fast access
  function buildLookups(data) {
    const fnSchemaMap = new Map();
    const argSchemaMap = new Map();
    const fnMap = new Map();
    const fnArgMap = new Map();  // fn_id -> [{arg_schema_id, arg_value}]
    const callSiteMap = new Map();

    (data.fn_schemas || []).forEach(fs => fnSchemaMap.set(fs.id, fs));
    (data.arg_schemas || []).forEach(as => argSchemaMap.set(as.id, as));
    (data.fns || []).forEach(f => fnMap.set(f.id, f));
    (data.call_sites || []).forEach(cs => callSiteMap.set(cs.id, cs));

    // Build fn_arg map from arg_values: owner_fn_id -> list of arg_values
    (data.arg_values || []).forEach(av => {
      const fnId = av.owner_fn_id;
      if (!fnId) return;
      if (!fnArgMap.has(fnId)) fnArgMap.set(fnId, []);
      fnArgMap.get(fnId).push({
        arg_schema_id: av.arg_schema_id,
        arg_value: av
      });
    });

    return { fnSchemaMap, argSchemaMap, fnMap, fnArgMap, callSiteMap };
  }

  // Update sidebar - show only fn entities
  function updateEntityList(data) {
    const list = document.getElementById('entity-list');
    list.innerHTML = '';
    const lookups = buildLookups(data);

    (data.fns || []).forEach(fn => {
      const schema = lookups.fnSchemaMap.get(fn.fn_schema_id);
      const isBase = schema && schema.base_fn_name;
      const returnType = schema ? schema.returned_type : '?';

      const li = document.createElement('li');
      li.className = 'entity-item';
      li.dataset.fnId = fn.id;
      li.innerHTML =
        '<div class=\"name\">' + fn.name + '</div>' +
        '<div class=\"meta\">' +
          (isBase ? '<span class=\"badge badge-base\">base</span> ' : '') +
          '<span style=\"opacity:0.6\">-> ' + returnType + '</span>' +
        '</div>';

      li.onclick = () => selectFn(fn.id);
      list.appendChild(li);
    });

    if (list.children.length === 0) {
      list.innerHTML = '<li class=\"loading\">No functions found</li>';
    }
  }

  // Select a function and center graph on it
  function selectFn(fnId) {
    document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
    const item = document.querySelector('[data-fn-id=\"' + fnId + '\"]');
    if (item) item.classList.add('selected');

    const node = cy.getElementById(fnId);
    if (node.length) {
      cy.center(node);
      node.select();
      showFnDetails(fnId);
    }
  }

  // Initialize Cytoscape with fn-centric graph
  async function initGraph() {
    const response = await fetch('/api/graph/entities');
    graphData = await response.json();

    updateEntityList(graphData);
    renderGraph();
  }

  function renderGraph() {
    const elements = convertToFnCentricElements(graphData, maxDepth);

    if (cy) {
      cy.destroy();
    }

    cy = cytoscape({
      container: document.getElementById('cy'),
      elements: elements,
      style: [
        // fn nodes - circles, monochrome
        { selector: 'node[type=\"fn\"]', style: {
          'label': 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'font-size': '11px',
          'font-family': 'SF Mono, Monaco, monospace',
          'width': 70,
          'height': 70,
          'shape': 'ellipse',
          'background-color': '#fff',
          'border-width': 2,
          'border-color': '#000',
          'color': '#000'
        }},
        // Base fn - dashed border
        { selector: 'node[type=\"fn\"][isBase]', style: {
          'border-style': 'dashed',
          'border-width': 2
        }},
        // call-site indicator - small square attached to fn
        { selector: 'node[type=\"call-site\"]', style: {
          'label': '',
          'width': 12,
          'height': 12,
          'shape': 'rectangle',
          'background-color': '#000',
          'border-width': 0
        }},
        // Edges - argument connections
        { selector: 'edge', style: {
          'width': 1,
          'line-color': '#666',
          'target-arrow-color': '#666',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'font-size': '10px',
          'font-family': 'SF Mono, Monaco, monospace',
          'text-rotation': 'autorotate',
          'text-margin-y': -8
        }},
        // Argument edges - show arg name as label
        { selector: 'edge[type=\"arg\"]', style: {
          'label': 'data(argName)',
          'line-color': '#999',
          'target-arrow-color': '#999',
          'line-style': 'solid'
        }},
        // Unset argument edges - dashed
        { selector: 'edge[type=\"arg-unset\"]', style: {
          'label': 'data(argName)',
          'line-color': '#ccc',
          'target-arrow-color': '#ccc',
          'line-style': 'dashed'
        }},
        // call-site edges
        { selector: 'edge[type=\"call-site\"]', style: {
          'line-color': '#000',
          'target-arrow-color': '#000',
          'width': 2
        }},
        // Selected
        { selector: ':selected', style: {
          'border-width': 4,
          'border-color': '#000'
        }}
      ],
      layout: {
        name: 'dagre',
        rankDir: 'LR',  // Left to right: output <- inputs
        nodeSep: 60,
        edgeSep: 20,
        rankSep: 120
      },
      userZoomingEnabled: true,
      userPanningEnabled: true,
      minZoom: 0.2,
      maxZoom: 3
    });

    cy.on('tap', 'node[type=\"fn\"]', function(evt) {
      const data = evt.target.data();
      showFnDetails(data.id);
    });

    cy.on('tap', function(evt) {
      if (evt.target === cy) {
        hideNodeDetails();
      }
    });

    cy.fit(50);
  }

  // Convert data to fn-centric elements
  // Only shows fn nodes connected by argument edges
  function convertToFnCentricElements(data, depth) {
    const nodes = [];
    const edges = [];
    const addedFns = new Set();
    const lookups = buildLookups(data);

    // Add all fns as nodes
    (data.fns || []).forEach(fn => {
      const schema = lookups.fnSchemaMap.get(fn.fn_schema_id);
      const isBase = schema && schema.base_fn_name;
      const returnType = schema ? schema.returned_type : '?';

      nodes.push({
        data: {
          id: fn.id,
          label: fn.name,
          type: 'fn',
          isBase: isBase,
          returnType: returnType,
          schemaId: fn.fn_schema_id,
          entityType: 'fn'
        }
      });
      addedFns.add(fn.id);
    });

    // Add edges based on arg_values -> references
    (data.arg_values || []).forEach(av => {
      if (!av.owner_fn_id) return;
      const argSchema = lookups.argSchemaMap.get(av.arg_schema_id);
      const argName = argSchema ? argSchema.name : '?';

      if (av.value && typeof av.value === 'object') {
        // Reference to another fn or call-site
        let targetFnId = null;
        let isCallSite = false;

        if (av.value.fn_id) {
          targetFnId = av.value.fn_id;
        } else if (av.value.call_site_id) {
          const cs = lookups.callSiteMap.get(av.value.call_site_id);
          if (cs) {
            targetFnId = cs.fn_id;
            isCallSite = true;
          }
        }

        if (targetFnId && addedFns.has(targetFnId)) {
          edges.push({
            data: {
              id: 'e-' + av.id,
              source: av.owner_fn_id,
              target: targetFnId,
              type: isCallSite ? 'call-site' : 'arg',
              argName: argName
            }
          });
        }
      }
    });

    // Show unset args as edges to placeholder
    (data.fns || []).forEach(fn => {
      const schema = lookups.fnSchemaMap.get(fn.fn_schema_id);
      if (!schema) return;

      const schemaArgs = (data.arg_schemas || []).filter(as => as.fn_schema_id === fn.fn_schema_id);
      const boundArgs = lookups.fnArgMap.get(fn.id) || [];
      const boundArgSchemaIds = new Set(boundArgs.map(ba => ba.arg_schema_id));

      schemaArgs.forEach(as => {
        if (!boundArgSchemaIds.has(as.id) && as.required) {
          // Create a placeholder node for unset required arg
          const placeholderId = 'unset-' + fn.id + '-' + as.id;
          nodes.push({
            data: {
              id: placeholderId,
              label: '?',
              type: 'fn',
              isBase: false,
              isPlaceholder: true
            }
          });
          edges.push({
            data: {
              id: 'e-unset-' + fn.id + '-' + as.id,
              source: fn.id,
              target: placeholderId,
              type: 'arg-unset',
              argName: as.name
            }
          });
        }
      });
    });

    return { nodes, edges };
  }

  // Show function details in panel
  function showFnDetails(fnId) {
    selectedNode = fnId;
    const panel = document.getElementById('details-panel');
    panel.classList.remove('hidden');
    htmx.ajax('GET', '/partials/entity-details/fn/' + fnId, '#details-content');
  }

  function hideNodeDetails() {
    selectedNode = null;
    document.getElementById('details-panel').classList.add('hidden');
  }

  function showCreateModal(entityType) {
    document.getElementById('modal-overlay').classList.remove('hidden');
    htmx.ajax('GET', '/partials/entity-form/' + entityType, '#modal-content');
  }

  function hideModal() {
    document.getElementById('modal-overlay').classList.add('hidden');
  }

  async function refreshGraph() {
    const response = await fetch('/api/graph/entities');
    graphData = await response.json();
    updateEntityList(graphData);
    renderGraph();
  }

  function fitGraph() {
    if (cy) cy.fit(50);
  }

  function setDepth(value) {
    maxDepth = parseInt(value);
    document.getElementById('depth-value').textContent = maxDepth;
    renderGraph();
  }

  // Initialize on load
  document.addEventListener('DOMContentLoaded', initGraph);

  // Listen for HTMX events to refresh graph
  document.body.addEventListener('entityCreated', refreshGraph);
  document.body.addEventListener('entityUpdated', refreshGraph);
  document.body.addEventListener('entityDeleted', refreshGraph);
  ")


;; =============================================================================
;; Hiccup Templates (used in fn-defs)
;; =============================================================================

(def editor-body-hiccup
  "Hiccup for the graph editor body."
  [:div {:class "layout"}
   ;; Sidebar
   [:div {:class "sidebar"}
    [:div {:class "panel"}
     [:h2 "Functions"]
     [:ul {:class "entity-list" :id "entity-list"}
      [:li {:class "loading"} "Loading..."]]]]
   ;; Main area
   [:div {:class "main"}
    [:div {:class "header"}
     [:h1 "GRAPHDEN"]]
    [:div {:class "toolbar"}
     [:button {:onclick "showCreateModal('fn')"} "+ fn"]
     [:div {:class "separator"}]
     [:label "Depth:"]
     [:input {:type "range" :min "1" :max "10" :value "3" :id "depth-slider"
              :onchange "setDepth(this.value)"}]
     [:span {:class "depth-value" :id "depth-value"} "3"]
     [:div {:class "separator"}]
     [:button {:onclick "refreshGraph()"} "Refresh"]
     [:button {:onclick "fitGraph()"} "Fit"]]
    [:div {:class "graph-container"}
     [:div {:id "cy"}]
     ;; Details panel
     [:div {:class "details-panel hidden" :id "details-panel"}
      [:div {:class "panel-header"}
       [:h3 "Details"]
       [:button {:class "close-btn" :onclick "hideNodeDetails()"} "×"]]
      [:div {:class "panel-body" :id "details-content"}]]]]
   ;; Modal
   [:div {:class "modal-overlay hidden" :id "modal-overlay"}
    [:div {:class "modal"}
     [:div {:class "modal-header"}
      [:h3 "Create Entity"]
      [:button {:class "close-btn" :onclick "hideModal()"} "×"]]
     [:div {:class "modal-body" :id "modal-content"}]]]])


;; =============================================================================
;; Fn Definitions
;; =============================================================================

(def fn-defs
  "Fn definitions for the graph editor.

   Composes base-fns to create the editor:
   - html-page: Creates HTML structure with HTMX + Cytoscape
   - html-handler: Wraps HTML as Ring handler
   - router: Routes requests to handlers
   - http-server: Serves HTTP on port

   With recursive reference resolution (:fn-name> syntax), handlers
   can be embedded directly in route definitions."
  [;; Head elements with scripts
   {:name :editor-head
    :parent :with-htmx
    :args {:head :editor-cytoscape-head>}}

   {:name :editor-cytoscape-head
    :parent :with-cytoscape
    :args {:head [[:style editor-styles]]}}

   ;; Complete HTML page
   {:name :editor-page
    :parent :html-page
    :args {:title "Graphden - Graph Editor"
           :head :editor-head>
           :body editor-body-hiccup
           :scripts [[:script {:src "https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"}]
                     [:script {:src "https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"}]
                     [:script editor-script]]}}

   ;; Handler returns Ring response with HTML
   ;; Two-step composition: html-response creates response, make-handler wraps as handler
   {:name :editor-response
    :parent :html-response
    :args {:body :editor-page>}}

   {:name :editor-handler
    :parent :make-handler
    :args {:response :editor-response>}}

   ;; API handler for graph entities (JSON)
   {:name :api-entities-handler
    :parent :all-entities-json-handler
    :args {}}

   ;; HTMX partial handlers
   {:name :entity-details-handler-fn
    :parent :entity-details-handler
    :args {}}

   {:name :entity-form-handler-fn
    :parent :entity-form-handler
    :args {}}

   {:name :create-entity-handler-fn
    :parent :create-entity-api-handler
    :args {}}

   {:name :delete-entity-handler-fn
    :parent :delete-entity-api-handler
    :args {}}

   ;; Health check - composed from primitives
   ;; Chain: health-status -> to-json-string -> ring-response -> make-handler
   {:name :health-data
    :parent :health-status
    :args {}}

   {:name :health-json-body
    :parent :to-json-string
    :args {:data :health-data>}}

   {:name :health-response
    :parent :ring-response
    :args {:status 200
           :headers {"Content-Type" "application/json"}
           :body :health-json-body>}}

   {:name :health-handler-fn
    :parent :make-handler
    :args {:response :health-response>}}

   ;; Favicon - SVG graph icon, composed from primitives
   {:name :favicon-response
    :parent :ring-response
    :args {:status 200
           :headers {"Content-Type" "image/svg+xml"
                     "Cache-Control" "public, max-age=86400"}
           :body "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><rect width=\"32\" height=\"32\" fill=\"#4A90D9\" rx=\"6\"/><circle cx=\"10\" cy=\"10\" r=\"4\" fill=\"white\"/><circle cx=\"22\" cy=\"10\" r=\"4\" fill=\"white\"/><circle cx=\"16\" cy=\"22\" r=\"4\" fill=\"white\"/><line x1=\"10\" y1=\"14\" x2=\"16\" y2=\"18\" stroke=\"white\" stroke-width=\"2\"/><line x1=\"22\" y1=\"14\" x2=\"16\" y2=\"18\" stroke=\"white\" stroke-width=\"2\"/></svg>"}}

   {:name :favicon-handler-fn
    :parent :make-handler
    :args {:response :favicon-response>}}

   ;; Router with all routes
   {:name :editor-router
    :parent :router
    :args {:routes [["/health" {"get" {"handler" :health-handler-fn>}}]
                    ["/favicon.ico" {"get" {"handler" :favicon-handler-fn>}}]
                    ["/" {"get" {"handler" :editor-handler>}}]
                    ["/api/graph/entities" {"get" {"handler" :api-entities-handler>}}]
                    ["/partials/entity-details/:type/:id" {"get" {"handler" :entity-details-handler-fn>}}]
                    ["/partials/entity-form/:type" {"get" {"handler" :entity-form-handler-fn>}}]
                    ["/partials/entity-form/:type/:id" {"get" {"handler" :entity-form-handler-fn>}}]
                    ["/api/entities/:type" {"post" {"handler" :create-entity-handler-fn>}}]
                    ["/api/entities/:type/:id" {"delete" {"handler" :delete-entity-handler-fn>}}]]}}

   ;; HTTP server with router
   {:name :web-server-fn
    :parent :http-server
    :args {:handler :editor-router>
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server-fn)
