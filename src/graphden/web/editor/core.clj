(ns graphden.web.editor.core
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
  "CSS styles for the graph editor."
  "
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, -apple-system, sans-serif; background: #f5f5f5; }

  .layout { display: flex; height: 100vh; }
  .sidebar { width: 300px; background: white; border-right: 1px solid #ddd; overflow-y: auto; }
  .main { flex: 1; display: flex; flex-direction: column; }

  .header { padding: 16px; background: white; border-bottom: 1px solid #ddd; }
  .header h1 { font-size: 20px; color: #333; }

  .toolbar { padding: 8px 16px; background: #fafafa; border-bottom: 1px solid #ddd; display: flex; gap: 8px; }
  .toolbar button { padding: 6px 12px; border: 1px solid #ddd; background: white; border-radius: 4px; cursor: pointer; }
  .toolbar button:hover { background: #f0f0f0; }
  .toolbar button.primary { background: #4A90D9; color: white; border-color: #4A90D9; }
  .toolbar button.primary:hover { background: #3A80C9; }

  .graph-container { flex: 1; position: relative; }
  #cy { width: 100%; height: 100%; }

  .panel { padding: 16px; }
  .panel h2 { font-size: 14px; color: #666; margin-bottom: 12px; text-transform: uppercase; }

  .entity-list { list-style: none; }
  .entity-item { padding: 8px 12px; border-bottom: 1px solid #eee; cursor: pointer; }
  .entity-item:hover { background: #f5f5f5; }
  .entity-item .name { font-weight: 500; }
  .entity-item .type { font-size: 12px; color: #888; }

  .form-group { margin-bottom: 12px; }
  .form-group label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
  .form-group input, .form-group select, .form-group textarea {
    width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px;
  }
  .form-group input:focus, .form-group select:focus, .form-group textarea:focus {
    outline: none; border-color: #4A90D9;
  }

  .btn { padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }
  .btn-primary { background: #4A90D9; color: white; }
  .btn-primary:hover { background: #3A80C9; }
  .btn-danger { background: #D9534F; color: white; }
  .btn-danger:hover { background: #C9433F; }
  .btn-secondary { background: #eee; color: #333; }
  .btn-secondary:hover { background: #ddd; }

  .details-panel { position: absolute; top: 16px; right: 16px; width: 320px; background: white;
    border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.15); max-height: calc(100% - 32px); overflow-y: auto; }
  .details-panel.hidden { display: none; }
  .details-panel .panel-header { padding: 12px 16px; border-bottom: 1px solid #eee; display: flex;
    justify-content: space-between; align-items: center; }
  .details-panel .panel-header h3 { font-size: 16px; }
  .details-panel .close-btn { background: none; border: none; font-size: 20px; cursor: pointer; color: #888; }
  .details-panel .panel-body { padding: 16px; }

  .field-row { display: flex; margin-bottom: 8px; }
  .field-label { width: 100px; font-size: 12px; color: #888; }
  .field-value { flex: 1; font-size: 14px; word-break: break-all; }

  .badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 500; }
  .badge-fn-schema { background: #E3F2FD; color: #1976D2; }
  .badge-fn { background: #E8F5E9; color: #388E3C; }
  .badge-arg-schema { background: #FFF3E0; color: #F57C00; }
  .badge-arg-value { background: #EEEEEE; color: #616161; }
  .badge-call-site { background: #F3E5F5; color: #7B1FA2; }

  .loading { text-align: center; padding: 40px; color: #888; }
  .error { background: #FFEBEE; color: #C62828; padding: 12px; border-radius: 4px; margin: 16px; }

  .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5);
    display: flex; align-items: center; justify-content: center; z-index: 1000; }
  .modal-overlay.hidden { display: none; }
  .modal { background: white; border-radius: 8px; width: 480px; max-width: 90%; max-height: 90%;
    overflow-y: auto; }
  .modal-header { padding: 16px; border-bottom: 1px solid #eee; display: flex;
    justify-content: space-between; align-items: center; }
  .modal-body { padding: 16px; }
  .modal-footer { padding: 16px; border-top: 1px solid #eee; display: flex; justify-content: flex-end; gap: 8px; }
  ")


;; =============================================================================
;; JavaScript for Cytoscape and HTMX Integration
;; =============================================================================

(def editor-script
  "JavaScript for graph editor functionality."
  "
  let cy = null;
  let selectedNode = null;

  // Update sidebar entity list
  function updateEntityList(data) {
    const list = document.getElementById('entity-list');
    list.innerHTML = '';

    const entityTypes = [
      { key: 'fn_schemas', type: 'fn-schema', badge: 'badge-fn-schema' },
      { key: 'fns', type: 'fn', badge: 'badge-fn' },
      { key: 'arg_schemas', type: 'arg-schema', badge: 'badge-arg-schema' },
      { key: 'arg_values', type: 'arg-value', badge: 'badge-arg-value' },
      { key: 'call_sites', type: 'call-site', badge: 'badge-call-site' }
    ];

    entityTypes.forEach(({ key, type, badge }) => {
      (data[key] || []).forEach(entity => {
        const li = document.createElement('li');
        li.className = 'entity-item';
        li.innerHTML = '<div class=\"name\">' + (entity.name || entity.id.substring(0,8)) + '</div>' +
                       '<div class=\"type\"><span class=\"badge ' + badge + '\">' + type + '</span></div>';
        li.onclick = () => {
          const node = cy.getElementById(entity.id);
          if (node.length) {
            cy.center(node);
            node.select();
            showNodeDetails({ ...entity, entityType: type });
          }
        };
        list.appendChild(li);
      });
    });

    if (list.children.length === 0) {
      list.innerHTML = '<li class=\"loading\">No entities found</li>';
    }
  }

  // Initialize Cytoscape
  async function initGraph() {
    const response = await fetch('/api/graph/entities');
    const data = await response.json();

    updateEntityList(data);
    const elements = convertToElements(data);

    cy = cytoscape({
      container: document.getElementById('cy'),
      elements: elements,
      style: [
        { selector: 'node', style: {
          'label': 'data(label)', 'text-valign': 'center', 'text-halign': 'center',
          'font-size': '11px', 'width': 60, 'height': 60
        }},
        { selector: 'node[type=\"fn-schema\"]', style: {
          'background-color': '#4A90D9', 'shape': 'rectangle', 'width': 80, 'height': 40
        }},
        { selector: 'node[type=\"fn\"]', style: { 'background-color': '#5CB85C', 'shape': 'ellipse' }},
        { selector: 'node[type=\"arg-schema\"]', style: {
          'background-color': '#F0AD4E', 'shape': 'diamond', 'width': 50, 'height': 50
        }},
        { selector: 'node[type=\"arg-value\"]', style: {
          'background-color': '#999999', 'shape': 'round-rectangle', 'width': 70, 'height': 35
        }},
        { selector: 'node[type=\"arg-value\"][isRef]', style: { 'background-color': '#D9534F' }},
        { selector: 'node[type=\"call-site\"]', style: {
          'background-color': '#9B59B6', 'shape': 'hexagon'
        }},
        { selector: 'edge', style: {
          'width': 2, 'line-color': '#CCCCCC', 'target-arrow-color': '#CCCCCC',
          'target-arrow-shape': 'triangle', 'curve-style': 'bezier'
        }},
        { selector: 'edge[type=\"has-schema\"]', style: {
          'line-color': '#4A90D9', 'target-arrow-color': '#4A90D9', 'line-style': 'dashed'
        }},
        { selector: 'edge[type=\"references\"]', style: {
          'line-color': '#D9534F', 'target-arrow-color': '#D9534F'
        }},
        { selector: 'edge[type=\"calls\"]', style: {
          'line-color': '#9B59B6', 'target-arrow-color': '#9B59B6'
        }},
        { selector: ':selected', style: { 'border-width': 3, 'border-color': '#000' }}
      ],
      layout: { name: 'dagre', rankDir: 'TB', nodeSep: 50, edgeSep: 10, rankSep: 100 }
    });

    cy.on('tap', 'node', function(evt) {
      const data = evt.target.data();
      showNodeDetails(data);
    });

    cy.on('tap', function(evt) {
      if (evt.target === cy) {
        hideNodeDetails();
      }
    });

    cy.fit();
  }

  function convertToElements(data) {
    const nodes = [];
    const edges = [];

    // Add fn-schemas
    (data.fn_schemas || []).forEach(fs => {
      nodes.push({
        data: { id: fs.id, label: fs.name, type: 'fn-schema', entityType: 'fn-schema', ...fs }
      });
    });

    // Add fns
    (data.fns || []).forEach(f => {
      nodes.push({
        data: { id: f.id, label: f.name, type: 'fn', entityType: 'fn', ...f }
      });
      if (f.fn_schema_id) {
        edges.push({
          data: { id: 'e-fs-' + f.id, source: f.id, target: f.fn_schema_id, type: 'has-schema' }
        });
      }
    });

    // Add arg-schemas
    (data.arg_schemas || []).forEach(as => {
      nodes.push({
        data: { id: as.id, label: as.name, type: 'arg-schema', entityType: 'arg-schema', ...as }
      });
      if (as.fn_schema_id) {
        edges.push({
          data: { id: 'e-as-' + as.id, source: as.fn_schema_id, target: as.id, type: 'has-arg' }
        });
      }
    });

    // Add arg-values
    (data.arg_values || []).forEach(av => {
      const isRef = av.value && typeof av.value === 'object' && (av.value.fn_id || av.value.call_site_id);
      let label = isRef ? 'ref' : String(av.value).substring(0, 15);
      nodes.push({
        data: { id: av.id, label: label, type: 'arg-value', entityType: 'arg-value', isRef: isRef, ...av }
      });
      if (av.owner_fn_id) {
        edges.push({
          data: { id: 'e-av-o-' + av.id, source: av.owner_fn_id, target: av.id, type: 'has-value' }
        });
      }
      if (isRef) {
        const targetId = av.value.fn_id || av.value.call_site_id;
        edges.push({
          data: { id: 'e-av-r-' + av.id, source: av.id, target: targetId, type: 'references' }
        });
      }
    });

    // Add call-sites
    (data.call_sites || []).forEach(cs => {
      nodes.push({
        data: { id: cs.id, label: cs.name || 'unnamed', type: 'call-site', entityType: 'call-site', ...cs }
      });
      if (cs.fn_id) {
        edges.push({
          data: { id: 'e-cs-' + cs.id, source: cs.id, target: cs.fn_id, type: 'calls' }
        });
      }
    });

    return { nodes, edges };
  }

  function showNodeDetails(data) {
    selectedNode = data;
    const panel = document.getElementById('details-panel');
    panel.classList.remove('hidden');

    // Load details via HTMX
    htmx.ajax('GET', '/partials/entity-details/' + data.entityType + '/' + data.id, '#details-content');
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
    const data = await response.json();

    updateEntityList(data);
    const elements = convertToElements(data);

    cy.elements().remove();
    cy.add(elements.nodes);
    cy.add(elements.edges);
    cy.layout({ name: 'dagre', rankDir: 'TB', nodeSep: 50, edgeSep: 10, rankSep: 100 }).run();
    cy.fit();
  }

  function fitGraph() {
    if (cy) cy.fit();
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
     [:h2 "Entities"]
     [:ul {:class "entity-list" :id "entity-list"}
      [:li {:class "loading"} "Loading..."]]]]
   ;; Main area
   [:div {:class "main"}
    [:div {:class "header"}
     [:h1 "Graph Editor"]]
    [:div {:class "toolbar"}
     [:button {:onclick "showCreateModal('fn-schema')"} "New fn-schema"]
     [:button {:onclick "showCreateModal('fn')"} "New fn"]
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
   ;; html-handler returns :fn type, so it's a Ring handler
   {:name :editor-handler
    :parent :html-handler
    :args {:body :editor-page>}}

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

   ;; Health check - calls health-status base function and wraps in JSON
   {:name :health-data
    :parent :health-status
    :args {}}

   {:name :health-handler-fn
    :parent :json-handler
    :args {:data :health-data>}}

   ;; Favicon - SVG graph icon
   {:name :favicon-handler-fn
    :parent :static-handler
    :args {:content "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><rect width=\"32\" height=\"32\" fill=\"#4A90D9\" rx=\"6\"/><circle cx=\"10\" cy=\"10\" r=\"4\" fill=\"white\"/><circle cx=\"22\" cy=\"10\" r=\"4\" fill=\"white\"/><circle cx=\"16\" cy=\"22\" r=\"4\" fill=\"white\"/><line x1=\"10\" y1=\"14\" x2=\"16\" y2=\"18\" stroke=\"white\" stroke-width=\"2\"/><line x1=\"22\" y1=\"14\" x2=\"16\" y2=\"18\" stroke=\"white\" stroke-width=\"2\"/></svg>"
           :content-type "image/svg+xml"}}

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
