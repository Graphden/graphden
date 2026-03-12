(ns graphden.packages.app.editor.impls
  "Graph editor implementations - CSS, JavaScript, and Hiccup body.")


;; =============================================================================
;; CSS Styles (embedded for simplicity)
;; =============================================================================

(def ^:private editor-styles-str
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

(def ^:private editor-script-str
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

  // Resolve arg name by following source-id chain
  // Inherited args may not have name, but their source arg does
  function resolveArgName(arg) {
    let current = arg;
    const maxDepth = 100;
    for (let i = 0; i < maxDepth; i++) {
      if (current.name) return current.name;
      if (!current['source-id']) return null;
      current = lookups.argMap.get(current['source-id']);
      if (!current) return null;
    }
    return null;
  }

  // Check if arg is HOF by following source-id chain
  // is-fn flag is set on base fn arg, inherited args don't have it directly
  function isArgHof(arg) {
    let current = arg;
    const maxDepth = 100;
    for (let i = 0; i < maxDepth; i++) {
      if (current['is-fn']) return true;
      if (!current['source-id']) return false;
      current = lookups.argMap.get(current['source-id']);
      if (!current) return false;
    }
    return false;
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

      li.innerHTML = '<div class=\"name\">' + fn.name + '</div>';
      li.onclick = () => selectFn(fn.id);
      list.appendChild(li);
    });

    if (list.children.length === 0) {
      list.innerHTML = '<li class=\"loading\">No functions found</li>';
    }
  }

  // Select a function and show its dependency tree
  // updateHistory: true = add to browser history (user click), false = don't add (back/forward)
  function selectFn(fnId, updateHistory = true) {
    selectedFnId = fnId;
    document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
    const item = document.querySelector('[data-fn-id=\"' + fnId + '\"]');
    if (item) item.classList.add('selected');

    // Update URL hash for direct linking
    const fn = lookups.fnMap.get(fnId);
    if (fn && updateHistory) {
      window.history.pushState(null, '', '#' + fn.name);
    }

    renderGraph();
  }

  // Select fn by name (for URL hash navigation)
  function selectFnByName(name, updateHistory = true) {
    const fn = (graphData.fns || []).find(f => f.name === name);
    if (fn) {
      selectFn(fn.id, updateHistory);
    }
  }

  // Initialize Cytoscape with fn-centric graph
  async function initGraph() {
    const response = await fetch('/api/graph/entities');
    graphData = await response.json();
    lookups = buildLookups(graphData);

    updateEntityList(graphData);

    // Check URL hash for pre-selected fn (don't add to history on initial load)
    const hash = window.location.hash.slice(1);
    if (hash) {
      selectFnByName(decodeURIComponent(hash), false);
    } else {
      renderGraph();
    }
  }

  // Handle hash changes (back/forward navigation - don't add to history)
  window.addEventListener('popstate', () => {
    const hash = window.location.hash.slice(1);
    if (hash && graphData) {
      selectFnByName(decodeURIComponent(hash), false);
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
          'height': 'label',
          'padding': '8px',
          'shape': 'rectangle',
          'background-color': '#f5f5f5',
          'border-width': 1,
          'border-color': '#000',
          'color': '#000'
        }},
        // Edges - simple black lines
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
        }},
        // HOF nodes - different shades based on nesting depth (cycles every 4)
        // hofDepthMod = ((hofDepth - 1) % 4) + 1, so 1->1, 2->2, 3->3, 4->4, 5->1, etc.
        { selector: 'node[hofDepthMod = 1]', style: {
          'border-color': '#555'
        }},
        { selector: 'node[hofDepthMod = 2]', style: {
          'border-color': '#888'
        }},
        { selector: 'node[hofDepthMod = 3]', style: {
          'border-color': '#aaa'
        }},
        { selector: 'node[hofDepthMod = 4]', style: {
          'border-color': '#ccc'
        }},
        // All edges inside HOF subgraph - color based on hofDepth (cycles every 4)
        { selector: 'edge[hofDepthMod = 1]', style: {
          'line-color': '#555'
        }},
        { selector: 'edge[hofDepthMod = 2]', style: {
          'line-color': '#888'
        }},
        { selector: 'edge[hofDepthMod = 3]', style: {
          'line-color': '#aaa'
        }},
        { selector: 'edge[hofDepthMod = 4]', style: {
          'line-color': '#ccc'
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
    const hofDepth = new Map(); // Track HOF nesting depth for each fn

    if (!rootFnId || !lookups.fnMap.has(rootFnId)) {
      return { nodes: [], edges: [] };
    }

    // Calculate HOF depth for each fn
    // hofDepth = 0 means not in HOF, 1 = first HOF level, 2 = nested HOF, etc.
    // All nodes inside a HOF subgraph inherit the HOF depth
    function calculateHofDepth(fnId, currentHofDepth, visited) {
      // Allow revisiting if we have a higher depth to propagate
      const existingDepth = hofDepth.get(fnId) || 0;
      if (visited.has(fnId) && currentHofDepth <= existingDepth) return;
      visited.add(fnId);

      // Store max HOF depth for this fn
      if (currentHofDepth > existingDepth) {
        hofDepth.set(fnId, currentHofDepth);
      }

      const args = lookups.argsByFn.get(fnId) || [];
      args.forEach(arg => {
        if (arg['ref-id']) {
          // If this arg is HOF, increase depth; otherwise keep current depth
          // This ensures all children of HOF node also get the HOF depth
          const nextDepth = isArgHof(arg) ? currentHofDepth + 1 : currentHofDepth;
          calculateHofDepth(arg['ref-id'], nextDepth, visited);
        }
      });
    }
    calculateHofDepth(rootFnId, 0, new Set());

    // Helper to compute cyclic depth mod (1-4 repeating)
    function hofDepthMod(d) {
      return d > 0 ? ((d - 1) % 4) + 1 : 0;
    }

    // Recursively collect fn and its dependencies
    function collectFn(fnId, currentDepth, isRoot) {
      if (addedFns.has(fnId) || currentDepth > depth) return;
      addedFns.add(fnId);

      const fn = lookups.fnMap.get(fnId);
      if (!fn) return;

      const isBase = !fn['parent-id'];
      const isComposed = !!fn['parent-id'];
      const fnHofDepth = hofDepth.get(fnId) || 0;

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
          hofDepth: fnHofDepth,
          hofDepthMod: hofDepthMod(fnHofDepth),
          parentId: fn['parent-id']
        }
      });

      // Get args for this fn
      const args = lookups.argsByFn.get(fnId) || [];

      args.forEach(arg => {
        const argName = resolveArgName(arg);
        const hasValue = arg.value !== null && arg.value !== undefined;
        const hasRef = !!arg['ref-id'];
        const isHofArg = isArgHof(arg);
        // HOF edge depth = depth of the target fn
        const targetHofDepth = hasRef ? (hofDepth.get(arg['ref-id']) || 0) : 0;

        if (hasRef) {
          // Reference to another fn - edge to that fn
          const refFnId = arg['ref-id'];
          edges.push({
            data: {
              id: 'e-' + arg.id,
              source: fnId,
              target: refFnId,
              type: isHofArg ? 'arg-hof' : 'arg-ref',
              argName: argName,
              hofDepth: targetHofDepth,
              hofDepthMod: hofDepthMod(targetHofDepth)
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
    const panel = document.getElementById('details-panel');
    if (panel) panel.classList.add('hidden');
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
;; Hiccup Templates
;; =============================================================================

(def ^:private editor-body-hiccup
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
;; Implementation Functions
;; =============================================================================

(def impls
  "Implementation map for editor base functions."
  {:editor-styles
   (fn [_args] editor-styles-str)

   :editor-script
   (fn [_args] editor-script-str)

   :editor-body
   (fn [_args] editor-body-hiccup)

   ;; Pre-wrapped elements for direct use in fn-defs
   :editor-style-element
   (fn [_args] [:style editor-styles-str])

   :editor-script-element
   (fn [_args] [:script editor-script-str])})
