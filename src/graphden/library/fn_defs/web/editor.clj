(ns graphden.library.fn-defs.web.editor
  "Graph Editor fn-defs.

   ## 2-Entity Schema

   The graph uses a minimal 2-entity schema:
   - fn: function entity (parent-id=nil for base-fn, parent-id set for composed)
   - arg: argument entity (source-id for inheritance, value/ref-id for data)

   ## Pages

   - GET / - Main graph editor page with Cytoscape visualization
   - GET /api/graph/entities - JSON endpoint for all entities (for Cytoscape)
   - GET /api/entities/:type - List entities of a type (:fn or :arg)
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

  /* Arg display in sidebar */
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
  "JavaScript for graph editor functionality - 2-entity schema visualization."
  "
  let cy = null;
  let selectedFnId = null;
  let graphData = null;
  let maxDepth = 100;
  let lookups = null;

  // Build lookup maps for fast access
  // 2-entity schema: fn (with parent-id) and arg (with source-id, value, ref-id)
  function buildLookups(data) {
    const fnMap = new Map();
    const argMap = new Map();
    const argsByFn = new Map();  // fn-id -> [args]

    (data.fns || []).forEach(f => fnMap.set(f.id, f));
    (data.args || []).forEach(a => {
      argMap.set(a.id, a);
      const fnId = a['fn-id'];
      if (fnId) {
        if (!argsByFn.has(fnId)) argsByFn.set(fnId, []);
        argsByFn.get(fnId).push(a);
      }
    });

    return { fnMap, argMap, argsByFn };
  }

  // Update sidebar - show fn entities
  function updateEntityList(data) {
    const list = document.getElementById('entity-list');
    list.innerHTML = '';

    (data.fns || []).forEach(fn => {
      const li = document.createElement('li');
      li.className = 'entity-item';
      if (fn.id === selectedFnId) li.className += ' selected';
      li.dataset.fnId = fn.id;

      const isBase = !fn['parent-id'];
      const badge = isBase ? '[base]' : '[composed]';

      li.innerHTML = '<div class=\"name\">' + fn.name + ' <span style=\"color:#888;font-size:10px\">' + badge + '</span></div>';
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
        // fn nodes - rounded rectangles
        { selector: 'node[type=\"fn\"]', style: {
          'label': 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'text-wrap': 'wrap',
          'font-size': '11px',
          'font-family': 'SF Mono, Monaco, monospace',
          'width': 'label',
          'height': 'label',
          'padding': '10px',
          'shape': 'round-rectangle',
          'background-color': '#fff',
          'border-width': 2,
          'border-color': '#000',
          'color': '#000'
        }},
        // Selected root fn - thicker border
        { selector: 'node[isRoot]', style: {
          'border-width': 4
        }},
        // Unset arg placeholder - dashed border
        { selector: 'node[isPlaceholder]', style: {
          'border-style': 'dashed',
          'border-color': '#999'
        }},
        // Literal arg values - rectangles
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
          'background-color': '#f5f5f5',
          'border-width': 1,
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
        // Inheritance edge (to parent)
        { selector: 'edge[type=\"inherits\"]', style: {
          'line-style': 'dashed',
          'line-color': '#666'
        }},
        // Unset arg edges - dashed
        { selector: 'edge[type=\"arg-unset\"]', style: {
          'line-style': 'dashed',
          'line-color': '#999'
        }}
      ],
      layout: {
        name: 'dagre',
        rankDir: 'LR',
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

  // Convert data to fn-centric elements using 2-entity schema
  function convertToFnCentricElements(data, depth, rootFnId) {
    const nodes = [];
    const edges = [];
    const addedFns = new Set();

    if (!rootFnId || !lookups.fnMap.has(rootFnId)) {
      return { nodes: [], edges: [] };
    }

    // Recursively collect fn and its dependencies
    function collectFn(fnId, currentDepth, isRoot) {
      if (addedFns.has(fnId) || currentDepth > depth) return;
      addedFns.add(fnId);

      const fn = lookups.fnMap.get(fnId);
      if (!fn) return;

      const isBase = !fn['parent-id'];
      const isComposed = !!fn['parent-id'];

      // Build label
      let label = fn.name;

      // If composed, show parent name
      if (isComposed) {
        const parent = lookups.fnMap.get(fn['parent-id']);
        if (parent && parent.name !== fn.name) {
          const maxLen = Math.max(fn.name.length, parent.name.length);
          const separator = String.fromCharCode(9472).repeat(maxLen); // box drawing char
          label = fn.name + '\\n' + separator + '\\n' + parent.name;
        }
      }

      nodes.push({
        data: {
          id: fn.id,
          label: label,
          type: 'fn',
          isBase: isBase,
          isComposed: isComposed,
          isRoot: isRoot,
          parentId: fn['parent-id']
        }
      });

      // Get args for this fn
      const args = lookups.argsByFn.get(fnId) || [];

      args.forEach(arg => {
        const argName = arg.name;
        const hasValue = arg.value !== null && arg.value !== undefined;
        const hasRef = !!arg['ref-id'];

        if (hasRef) {
          // Reference to another fn - edge to that fn
          const refFnId = arg['ref-id'];
          edges.push({
            data: {
              id: 'e-' + arg.id,
              source: fnId,
              target: refFnId,
              type: 'arg-ref',
              argName: argName
            }
          });
          // Recurse into dependency
          if (currentDepth < depth) {
            collectFn(refFnId, currentDepth + 1, false);
          }
        } else if (hasValue) {
          // Literal value - show as arg node
          const argNodeId = 'arg-' + fnId + '-' + arg.id;
          let displayValue = JSON.stringify(arg.value);
          if (displayValue.length > 20) {
            displayValue = displayValue.substring(0, 17) + '...';
          }
          nodes.push({
            data: {
              id: argNodeId,
              label: displayValue,
              type: 'arg',
              argId: arg.id,
              rawValue: arg.value
            }
          });
          edges.push({
            data: {
              id: 'e-arg-' + fnId + '-' + arg.id,
              source: fnId,
              target: argNodeId,
              type: 'arg-literal',
              argName: argName
            }
          });
        } else {
          // Unset arg - show as placeholder
          const placeholderId = 'unset-' + fnId + '-' + arg.id;
          const argType = arg.type || 'any';
          nodes.push({
            data: {
              id: placeholderId,
              label: argType,
              type: 'fn',
              isPlaceholder: true
            }
          });
          edges.push({
            data: {
              id: 'e-unset-' + fnId + '-' + arg.id,
              source: fnId,
              target: placeholderId,
              type: 'arg-unset',
              argName: argName
            }
          });
        }
      });

      // Show inheritance edge to parent
      if (isComposed && fn['parent-id']) {
        // We don't add parent as a separate node since it may already be added
        // Just note the inheritance in the label
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
     [:button {:onclick "showCreateModal('fn')"} "+ fn"]
     [:button {:onclick "showCreateModal('arg')"} "+ arg"]]
    [:div {:class "graph-container"}
     [:div {:id "cy"}]]]
   ;; Modal
   [:div {:class "modal-overlay hidden" :id "modal-overlay"}
    [:div {:class "modal"}
     [:div {:class "modal-header"}
      [:h3 "Create Entity"]
      [:button {:class "close-btn" :onclick "hideModal()"} "x"]]
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
   - http-server: Serves HTTP on port"
  [;; Head elements with scripts
   {:name :editor-head
    :parent :with-htmx
    :args {:head :editor-cytoscape-head}}

   {:name :editor-cytoscape-head
    :parent :with-cytoscape
    :args {:head [[:style editor-styles]]}}

   ;; Complete HTML page
   {:name :editor-page
    :parent :html-page
    :args {:title "Graphden - Graph Editor"
           :head :editor-head
           :body editor-body-hiccup
           :scripts [[:script {:src "https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"}]
                     [:script {:src "https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"}]
                     [:script editor-script]]}}

   ;; Handler returns Ring response with HTML
   {:name :editor-response
    :parent :html-response
    :args {:body :editor-page}}

   {:name :editor-handler
    :parent :make-handler
    :args {:response :editor-response}}

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

   ;; Health check
   {:name :health-data
    :parent :health-status
    :args {}}

   {:name :health-json-body
    :parent :to-json-string
    :args {:data :health-data}}

   {:name :health-response
    :parent :ring-response
    :args {:status 200
           :headers {"Content-Type" "application/json"}
           :body :health-json-body}}

   {:name :health-handler
    :parent :make-handler
    :args {:response :health-response}}

   ;; Favicon - SVG graph icon
   {:name :favicon-response
    :parent :ring-response
    :args {:status 200
           :headers {"Content-Type" "image/svg+xml"
                     "Cache-Control" "public, max-age=86400"}
           :body "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><rect width=\"32\" height=\"32\" fill=\"#fff\" rx=\"4\"/><rect x=\"1\" y=\"1\" width=\"30\" height=\"30\" fill=\"none\" stroke=\"#000\" stroke-width=\"2\" rx=\"4\"/><rect x=\"3\" y=\"12\" width=\"10\" height=\"8\" rx=\"2\" fill=\"#fff\" stroke=\"#000\" stroke-width=\"1.5\"/><rect x=\"19\" y=\"4\" width=\"10\" height=\"8\" rx=\"2\" fill=\"#fff\" stroke=\"#000\" stroke-width=\"1.5\"/><rect x=\"19\" y=\"20\" width=\"10\" height=\"8\" rx=\"2\" fill=\"#fff\" stroke=\"#000\" stroke-width=\"1.5\"/><line x1=\"13\" y1=\"14\" x2=\"19\" y2=\"8\" stroke=\"#000\" stroke-width=\"1.5\"/><line x1=\"13\" y1=\"18\" x2=\"19\" y2=\"24\" stroke=\"#000\" stroke-width=\"1.5\"/></svg>"}}

   {:name :favicon-handler
    :parent :make-handler
    :args {:response :favicon-response}}

   ;; === Route Building ===

   ;; Health route
   {:name :health-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :health-handler}}
   {:name :health-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :health-route-opts}}
   {:name :health-route
    :parent :pair
    :args {:a "/health", :b :health-route-methods}}

   ;; Favicon route
   {:name :favicon-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :favicon-handler}}
   {:name :favicon-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :favicon-route-opts}}
   {:name :favicon-route
    :parent :pair
    :args {:a "/favicon.ico", :b :favicon-route-methods}}

   ;; Editor (home) route
   {:name :editor-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :editor-handler}}
   {:name :editor-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :editor-route-opts}}
   {:name :editor-route
    :parent :pair
    :args {:a "/", :b :editor-route-methods}}

   ;; API entities route
   {:name :api-entities-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :api-entities-handler}}
   {:name :api-entities-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :api-entities-route-opts}}
   {:name :api-entities-route
    :parent :pair
    :args {:a "/api/graph/entities", :b :api-entities-route-methods}}

   ;; Entity details partial route
   {:name :entity-details-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :entity-details-handler}}
   {:name :entity-details-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :entity-details-route-opts}}
   {:name :entity-details-route
    :parent :pair
    :args {:a "/partials/entity-details/:type/:id", :b :entity-details-route-methods}}

   ;; Entity form partial route (create)
   {:name :entity-form-create-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :entity-form-handler}}
   {:name :entity-form-create-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :entity-form-create-route-opts}}
   {:name :entity-form-create-route
    :parent :pair
    :args {:a "/partials/entity-form/:type", :b :entity-form-create-route-methods}}

   ;; Entity form partial route (edit)
   {:name :entity-form-edit-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :entity-form-handler}}
   {:name :entity-form-edit-route-methods
    :parent :assoc-any
    :args {:m {}, :k "get", :v :entity-form-edit-route-opts}}
   {:name :entity-form-edit-route
    :parent :pair
    :args {:a "/partials/entity-form/:type/:id", :b :entity-form-edit-route-methods}}

   ;; Create entity API route
   {:name :create-entity-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :create-entity-handler}}
   {:name :create-entity-route-methods
    :parent :assoc-any
    :args {:m {}, :k "post", :v :create-entity-route-opts}}
   {:name :create-entity-route
    :parent :pair
    :args {:a "/api/entities/:type", :b :create-entity-route-methods}}

   ;; Delete entity API route
   {:name :delete-entity-route-opts
    :parent :assoc-any
    :args {:m {}, :k "handler", :v :delete-entity-handler}}
   {:name :delete-entity-route-methods
    :parent :assoc-any
    :args {:m {}, :k "delete", :v :delete-entity-route-opts}}
   {:name :delete-entity-route
    :parent :pair
    :args {:a "/api/entities/:type/:id", :b :delete-entity-route-methods}}

   ;; Build routes vector
   {:name :routes-0
    :parent :conj-any
    :args {:coll [], :x :health-route}}
   {:name :routes-1
    :parent :conj-any
    :args {:coll :routes-0, :x :favicon-route}}
   {:name :routes-2
    :parent :conj-any
    :args {:coll :routes-1, :x :editor-route}}
   {:name :routes-3
    :parent :conj-any
    :args {:coll :routes-2, :x :api-entities-route}}
   {:name :routes-4
    :parent :conj-any
    :args {:coll :routes-3, :x :entity-details-route}}
   {:name :routes-5
    :parent :conj-any
    :args {:coll :routes-4, :x :entity-form-create-route}}
   {:name :routes-6
    :parent :conj-any
    :args {:coll :routes-5, :x :entity-form-edit-route}}
   {:name :routes-7
    :parent :conj-any
    :args {:coll :routes-6, :x :create-entity-route}}
   {:name :all-routes
    :parent :conj-any
    :args {:coll :routes-7, :x :delete-entity-route}}

   ;; Router with all routes
   {:name :editor-router
    :parent :router
    :args {:routes :all-routes}}

   ;; HTTP server with router
   {:name :web-server
    :parent :http-server
    :args {:handler :editor-router
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server)
