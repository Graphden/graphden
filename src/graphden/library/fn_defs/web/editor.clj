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

  /* Arg labels in sidebar */
  .fn-args { margin-top: 8px; padding-left: 12px; border-left: 2px solid #ddd; }
  .fn-arg { font-size: 11px; margin: 4px 0; display: flex; gap: 6px; }
  .fn-arg .arg-name { color: #666; }
  .fn-arg .arg-value { font-weight: 500; max-width: 120px; overflow: hidden; text-overflow: ellipsis;
    white-space: nowrap; }
  .fn-arg.unset .arg-name { color: #999; font-style: italic; }
  ")


;; =============================================================================
;; JavaScript for Cytoscape and HTMX Integration
;; =============================================================================

(def editor-script
  "JavaScript for graph editor functionality - fn-centric visualization."
  "
  let cy = null;
  let selectedFnId = null;
  let graphData = null;
  let maxDepth = 100; // Show all levels
  let lookups = null;

  // Build lookup maps for fast access
  // Note: API returns kebab-case keys (fn-schema-id), so we use bracket notation
  function buildLookups(data) {
    const fnSchemaMap = new Map();
    const argSchemaMap = new Map();
    const fnMap = new Map();
    const fnArgMap = new Map();  // fn_id -> [{argSchemaId, argValue}]
    const callSiteMap = new Map();

    (data.fn_schemas || []).forEach(fs => fnSchemaMap.set(fs.id, fs));
    (data.arg_schemas || []).forEach(as => argSchemaMap.set(as.id, as));
    (data.fns || []).forEach(f => fnMap.set(f.id, f));
    (data.call_sites || []).forEach(cs => callSiteMap.set(cs.id, cs));

    // Build arg_value lookup map: arg-value-id -> arg-value
    const argValueMap = new Map();
    (data.arg_values || []).forEach(av => argValueMap.set(av.id, av));

    // Build fn_arg map from fn_args: fn-id -> [{argSchemaId, argValue}]
    (data.fn_args || []).forEach(fa => {
      const fnId = fa['fn-id'];
      const argValueId = fa['arg-value-id'];
      const argSchemaId = fa['arg-schema-id'];
      const argValue = argValueMap.get(argValueId);
      if (!fnId || !argValue) return;
      if (!fnArgMap.has(fnId)) fnArgMap.set(fnId, []);
      fnArgMap.get(fnId).push({
        argSchemaId: argSchemaId,
        argValue: argValue
      });
    });

    return { fnSchemaMap, argSchemaMap, fnMap, fnArgMap, callSiteMap };
  }

  // Update sidebar - show only fn entities
  function updateEntityList(data) {
    const list = document.getElementById('entity-list');
    list.innerHTML = '';

    (data.fns || []).forEach(fn => {
      const li = document.createElement('li');
      li.className = 'entity-item';
      if (fn.id === selectedFnId) li.className += ' selected';
      li.dataset.fnId = fn.id;
      li.innerHTML = '<div class=\"name\">' + fn.name + '</div>';
      li.onclick = () => selectFn(fn.id);
      list.appendChild(li);
    });

    if (list.children.length === 0) {
      list.innerHTML = '<li class=\"loading\">No functions found</li>';
    }
  }

  // Select a function and show its dependency tree
  function selectFn(fnId) {
    selectedFnId = fnId;
    document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
    const item = document.querySelector('[data-fn-id=\"' + fnId + '\"]');
    if (item) item.classList.add('selected');

    // Update URL hash for direct linking
    const fn = lookups.fnMap.get(fnId);
    if (fn) {
      window.history.replaceState(null, '', '#' + fn.name);
    }

    renderGraph();
  }

  // Select fn by name (for URL hash navigation)
  function selectFnByName(name) {
    const fn = (graphData.fns || []).find(f => f.name === name);
    if (fn) {
      selectFn(fn.id);
    }
  }

  // Initialize Cytoscape with fn-centric graph
  async function initGraph() {
    const response = await fetch('/api/graph/entities');
    graphData = await response.json();
    lookups = buildLookups(graphData);

    updateEntityList(graphData);

    // Check URL hash for pre-selected fn
    const hash = window.location.hash.slice(1);
    if (hash) {
      selectFnByName(decodeURIComponent(hash));
    } else {
      renderGraph();
    }
  }

  // Handle hash changes (back/forward navigation)
  window.addEventListener('hashchange', () => {
    const hash = window.location.hash.slice(1);
    if (hash && graphData) {
      selectFnByName(decodeURIComponent(hash));
    }
  });

  function renderGraph() {
    const elements = convertToFnCentricElements(graphData, maxDepth, selectedFnId);

    if (cy) {
      cy.destroy();
    }

    cy = cytoscape({
      container: document.getElementById('cy'),
      elements: elements,
      style: [
        // fn nodes - rounded rectangles (adapt to label width)
        { selector: 'node[type=\"fn\"]', style: {
          'label': 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'font-size': '11px',
          'font-family': 'SF Mono, Monaco, monospace',
          'width': 'label',
          'height': 28,
          'padding': '12px',
          'shape': 'round-rectangle',
          'background-color': '#fff',
          'border-width': 2,
          'border-color': '#000',
          'color': '#000'
        }},
        // Selected root fn - thicker border only
        { selector: 'node[isRoot]', style: {
          'border-width': 4
        }},
        // Placeholder for unset arg - dashed border (black/white only)
        { selector: 'node[isPlaceholder]', style: {
          'border-style': 'dashed'
        }},
        // Literal arg values - rectangles (different shape = different type)
        { selector: 'node[type=\"arg\"]', style: {
          'label': 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'font-size': '10px',
          'font-family': 'SF Mono, Monaco, monospace',
          'width': 'label',
          'height': 28,
          'padding': '8px',
          'shape': 'rectangle',
          'background-color': '#fff',
          'border-width': 2,
          'border-color': '#000',
          'color': '#000'
        }},
        // Edges - simple lines
        { selector: 'edge', style: {
          'width': 2,
          'line-color': '#000',
          'curve-style': 'bezier',
          'label': 'data(argName)',
          'font-size': '10px',
          'font-family': 'SF Mono, Monaco, monospace',
          'text-rotation': 'autorotate',
          'text-margin-y': -10,
          'text-background-color': '#fff',
          'text-background-opacity': 1,
          'text-background-padding': '2px'
        }},
        // Unset arg edges - dashed (black/white only)
        { selector: 'edge[type=\"arg-unset\"]', style: {
          'line-style': 'dashed'
        }}
      ],
      layout: {
        name: 'dagre',
        rankDir: 'LR',  // Left to right: root fn on left, deps on right
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
      if (!data.isPlaceholder) {
        selectFn(data.id);
      }
    });

    cy.on('tap', function(evt) {
      if (evt.target === cy) {
        hideNodeDetails();
      }
    });

    cy.fit(50);
  }

  // Get fn dependencies (fns referenced in arg_values) for a given fn
  function getFnDependencies(fnId) {
    const deps = [];
    const argValues = lookups.fnArgMap.get(fnId) || [];

    argValues.forEach(av => {
      const value = av.arg_value.value;
      if (value && typeof value === 'object') {
        let targetFnId = null;
        let isCallSite = false;

        if (value.fn_id) {
          targetFnId = value.fn_id;
        } else if (value.call_site_id) {
          const cs = lookups.callSiteMap.get(value.call_site_id);
          if (cs) {
            targetFnId = cs.fn_id;
            isCallSite = true;
          }
        }

        if (targetFnId) {
          deps.push({
            fnId: targetFnId,
            argSchemaId: av.arg_schema_id,
            argValueId: av.arg_value.id,
            isCallSite: isCallSite
          });
        }
      }
    });

    return deps;
  }

  // Convert data to fn-centric elements - only selected fn and its dependency tree
  function convertToFnCentricElements(data, depth, rootFnId) {
    const nodes = [];
    const edges = [];
    const addedFns = new Set();

    if (!rootFnId || !lookups.fnMap.has(rootFnId)) {
      // No fn selected - show hint
      return { nodes: [], edges: [] };
    }

    // Recursively collect fn and its dependencies to specified depth
    function collectFn(fnId, currentDepth, isRoot) {
      if (addedFns.has(fnId) || currentDepth > depth) return;
      addedFns.add(fnId);

      const fn = lookups.fnMap.get(fnId);
      if (!fn) return;

      const schema = lookups.fnSchemaMap.get(fn['fn-schema-id']);
      const isBase = schema && schema['base-fn-name'];
      const returnType = schema ? schema['returned-type'] : '?';

      nodes.push({
        data: {
          id: fn.id,
          label: fn.name,
          type: 'fn',
          isBase: isBase,
          isRoot: isRoot,
          returnType: returnType,
          schemaId: fn['fn-schema-id']
        }
      });

      // Get all schema args for this fn and show them
      // For wrapper fns (those with base-fn-name different from name), look up parent's args
      if (schema) {
        let argSchemaSourceId = schema.id;
        const isWrapper = schema['base-fn-name'] && schema.name !== schema['base-fn-name'];
        if (isWrapper) {
          // Find the parent base function's schema by name
          const parentSchema = Array.from(lookups.fnSchemaMap.values()).find(
            s => s.name === schema['base-fn-name']
          );
          if (parentSchema) {
            argSchemaSourceId = parentSchema.id;
          }
        }
        const schemaArgs = (data.arg_schemas || []).filter(as => as['fn-schema-id'] === argSchemaSourceId);
        const boundArgs = lookups.fnArgMap.get(fnId) || [];
        const boundBySchemaId = new Map();
        boundArgs.forEach(ba => boundBySchemaId.set(ba.argSchemaId, ba.argValue));

        schemaArgs.forEach(as => {
          const argValue = boundBySchemaId.get(as.id);
          const argName = as.name;

          if (argValue) {
            // Arg has a value - check if it's a fn reference or literal
            const value = argValue.value;
            let targetFnId = null;
            let isCallSite = false;

            if (value && typeof value === 'object') {
              if (value['fn-id']) {
                targetFnId = value['fn-id'];
              } else if (value['call-site-id']) {
                const cs = lookups.callSiteMap.get(value['call-site-id']);
                if (cs) {
                  targetFnId = cs['fn-id'];
                  isCallSite = true;
                }
              }
            }

            if (targetFnId) {
              // It's a fn reference - edge to the fn
              edges.push({
                data: {
                  id: 'e-' + argValue.id,
                  source: fnId,
                  target: targetFnId,
                  type: isCallSite ? 'call-site' : 'arg',
                  argName: argName
                }
              });
              // Recurse into dependency
              if (currentDepth < depth) {
                collectFn(targetFnId, currentDepth + 1, false);
              }
            } else {
              // It's a literal value - show as arg node
              const argNodeId = 'arg-' + fnId + '-' + as.id;
              let displayValue = JSON.stringify(value);
              if (displayValue.length > 20) {
                displayValue = displayValue.substring(0, 17) + '...';
              }
              nodes.push({
                data: {
                  id: argNodeId,
                  label: argName + ': ' + displayValue,
                  type: 'arg',
                  argSchemaId: as.id,
                  rawValue: value
                }
              });
              edges.push({
                data: {
                  id: 'e-arg-' + fnId + '-' + as.id,
                  source: fnId,
                  target: argNodeId,
                  type: 'arg-literal',
                  argName: ''
                }
              });
            }
          } else {
            // Arg is unset (free argument)
            const placeholderId = 'unset-' + fnId + '-' + as.id;
            nodes.push({
              data: {
                id: placeholderId,
                label: argName + ': ?',
                type: 'fn',
                isBase: false,
                isPlaceholder: true
              }
            });
            edges.push({
              data: {
                id: 'e-unset-' + fnId + '-' + as.id,
                source: fnId,
                target: placeholderId,
                type: 'arg-unset',
                argName: ''
              }
            });
          }
        });
      }
    }

    collectFn(rootFnId, 1, true);
    return { nodes, edges };
  }

  // Show function details in panel
  function showFnDetails(fnId) {
    const panel = document.getElementById('details-panel');
    panel.classList.remove('hidden');
    htmx.ajax('GET', '/partials/entity-details/fn/' + fnId, '#details-content');
  }

  function hideNodeDetails() {
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
    lookups = buildLookups(graphData);
    updateEntityList(graphData);
    renderGraph();
  }

  function fitGraph() {
    if (cy) cy.fit(50);
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
     [:button {:onclick "showCreateModal('fn')"} "+ fn"]]
    [:div {:class "graph-container"}
     [:div {:id "cy"}]]]
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
   {:name :entity-details-handler
    :parent :entity-details-handler
    :args {}}

   {:name :entity-form-handler
    :parent :entity-form-handler
    :args {}}

   {:name :create-entity-handler
    :parent :create-entity-api-handler
    :args {}}

   {:name :delete-entity-handler
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

   {:name :health-handler
    :parent :make-handler
    :args {:response :health-response>}}

   ;; Favicon - SVG graph icon, composed from primitives
   {:name :favicon-response
    :parent :ring-response
    :args {:status 200
           :headers {"Content-Type" "image/svg+xml"
                     "Cache-Control" "public, max-age=86400"}
           :body "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><rect width=\"32\" height=\"32\" fill=\"#4A90D9\" rx=\"6\"/><circle cx=\"10\" cy=\"10\" r=\"4\" fill=\"white\"/><circle cx=\"22\" cy=\"10\" r=\"4\" fill=\"white\"/><circle cx=\"16\" cy=\"22\" r=\"4\" fill=\"white\"/><line x1=\"10\" y1=\"14\" x2=\"16\" y2=\"18\" stroke=\"white\" stroke-width=\"2\"/><line x1=\"22\" y1=\"14\" x2=\"16\" y2=\"18\" stroke=\"white\" stroke-width=\"2\"/></svg>"}}

   {:name :favicon-handler
    :parent :make-handler
    :args {:response :favicon-response>}}

   ;; Router with all routes
   {:name :editor-router
    :parent :router
    :args {:routes [["/health" {"get" {"handler" :health-handler>}}]
                    ["/favicon.ico" {"get" {"handler" :favicon-handler>}}]
                    ["/" {"get" {"handler" :editor-handler>}}]
                    ["/api/graph/entities" {"get" {"handler" :api-entities-handler>}}]
                    ["/partials/entity-details/:type/:id" {"get" {"handler" :entity-details-handler>}}]
                    ["/partials/entity-form/:type" {"get" {"handler" :entity-form-handler>}}]
                    ["/partials/entity-form/:type/:id" {"get" {"handler" :entity-form-handler>}}]
                    ["/api/entities/:type" {"post" {"handler" :create-entity-handler>}}]
                    ["/api/entities/:type/:id" {"delete" {"handler" :delete-entity-handler>}}]]}}

   ;; HTTP server with router
   {:name :web-server
    :parent :http-server
    :args {:handler :editor-router>
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server)
