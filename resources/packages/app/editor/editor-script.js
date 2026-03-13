// Graph editor JavaScript - 2-entity schema visualization
// Interactive expand/collapse for inheritance drill-down
let cy = null;
let selectedFnId = null;
let graphData = null;
let lookups = null;
let expandedNodes = new Set(); // fnId -> expanded (showing parent instead)

// Build lookup maps
function buildLookups(data) {
  const fnMap = new Map();
  const argMap = new Map();
  const argsByFn = new Map();

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

// Resolve arg name
function resolveArgName(arg) {
  let current = arg;
  for (let i = 0; i < 100; i++) {
    if (current.name) return current.name;
    if (!current['source-id']) return null;
    current = lookups.argMap.get(current['source-id']);
    if (!current) return null;
  }
  return null;
}

// Update sidebar
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  (data.fns || []).forEach(fn => {
    const li = document.createElement('li');
    li.className = 'entity-item';
    if (fn.id === selectedFnId) li.className += ' selected';
    li.dataset.fnId = fn.id;
    li.innerHTML = '<div class="name">' + fn.name + '</div>';
    li.onclick = () => selectFn(fn.id);
    list.appendChild(li);
  });

  if (list.children.length === 0) {
    list.innerHTML = '<li class="loading">No functions found</li>';
  }
}

// Select a function
function selectFn(fnId, updateHistory = true) {
  selectedFnId = fnId;
  expandedNodes.clear();
  document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.classList.add('selected');

  const fn = lookups.fnMap.get(fnId);
  if (fn && updateHistory) {
    window.history.pushState(null, '', '#' + fn.name);
  }

  renderGraph(true);
}

// Expand node - add to expanded set
function expandNode(fnId) {
  const fn = lookups.fnMap.get(fnId);
  if (!fn || !fn['parent-id']) return;
  expandedNodes.add(fnId);
  renderGraph(false);
}

// Collapse node - remove from expanded set
function collapseNode(fnId) {
  expandedNodes.delete(fnId);
  renderGraph(false);
}

function selectFnByName(name, updateHistory = true) {
  const fn = (graphData.fns || []).find(f => f.name === name);
  if (fn) selectFn(fn.id, updateHistory);
}

async function initGraph() {
  const response = await fetch('/api/graph/entities');
  graphData = await response.json();
  lookups = buildLookups(graphData);
  updateEntityList(graphData);

  const hash = window.location.hash.slice(1);
  if (hash) {
    selectFnByName(decodeURIComponent(hash), false);
  } else {
    renderGraph(true);
  }
}

window.addEventListener('popstate', () => {
  const hash = window.location.hash.slice(1);
  if (hash && graphData) selectFnByName(decodeURIComponent(hash), false);
});

function renderGraph(shouldFit = true) {
  const elements = buildGraphElements();

  // Save positions of existing nodes
  const savedPositions = new Map();
  let savedZoom, savedPan;
  if (cy) {
    cy.nodes().forEach(node => {
      savedPositions.set(node.id(), { x: node.position('x'), y: node.position('y') });
    });
    savedZoom = cy.zoom();
    savedPan = cy.pan();
    cy.destroy();
  }

  cy = cytoscape({
    container: document.getElementById('cy'),
    elements: elements,
    style: [
      // fn node
      { selector: 'node[type="fn"]', style: {
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'font-size': '11px',
        'font-family': 'SF Mono, Monaco, monospace',
        'shape': 'round-rectangle',
        'background-color': '#ffffff',
        'border-width': 2,
        'border-color': '#000000',
        'color': '#000000',
        'padding': '10px',
        'width': function(node) {
          var label = node.data('label') || '';
          var lines = label.split('\n');
          var maxLen = Math.max(...lines.map(l => l.length));
          return Math.max(60, maxLen * 7 + 20);
        },
        'height': function(node) {
          var label = node.data('label') || '';
          var lines = label.split('\n').length;
          return Math.max(30, lines * 14 + 20);
        }
      }},
      // Root node
      { selector: 'node[?isRoot]', style: {
        'border-width': 4
      }},
      // Placeholder (unset arg)
      { selector: 'node[?isPlaceholder]', style: {
        'border-style': 'dashed',
        'border-color': '#999999',
        'background-color': '#ffffff'
      }},
      // Arg value node
      { selector: 'node[type="arg"]', style: {
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'shape': 'rectangle',
        'background-color': '#f5f5f5',
        'border-width': 1,
        'border-color': '#000000',
        'color': '#000000',
        'padding': '8px',
        'width': function(node) {
          var label = node.data('label') || '';
          return Math.max(40, label.length * 6 + 16);
        },
        'height': 28
      }},
      // Edge
      { selector: 'edge', style: {
        'width': 2,
        'line-color': '#000000',
        'line-style': 'solid',
        'curve-style': 'bezier'
      }},
      // Edge with label
      { selector: 'edge[argName]', style: {
        'label': 'data(argName)',
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'text-rotation': 'autorotate',
        'text-margin-y': -10,
        'text-background-color': '#ffffff',
        'text-background-opacity': 1,
        'text-background-padding': '2px'
      }},
      // Unset arg edge
      { selector: 'edge[?isUnset]', style: {
        'line-style': 'dashed',
        'line-color': '#999999'
      }}
    ],
    layout: { name: 'preset' }, // We'll position manually first
    userZoomingEnabled: true,
    userPanningEnabled: true,
    minZoom: 0.2,
    maxZoom: 3
  });

  // Restore positions for existing nodes
  const hasPositions = savedPositions.size > 0;
  const newNodes = [];

  cy.nodes().forEach(node => {
    const saved = savedPositions.get(node.id());
    if (saved && hasPositions) {
      node.position(saved);
    } else {
      newNodes.push(node);
    }
  });

  // Run dagre layout
  cy.layout({
    name: 'dagre',
    rankDir: 'LR',
    nodeSep: 50,
    edgeSep: 15,
    rankSep: 100,
    fit: false,
    animate: false
  }).run();

  // Click handler
  cy.on('tap', 'node[type="fn"]', function(evt) {
    const data = evt.target.data();
    if (data.isPlaceholder) return;

    // Check if this node can be collapsed (is expanded showing parent)
    if (data.canCollapse && data.originalFnId) {
      collapseNode(data.originalFnId);
      return;
    }

    // Check if can expand (has parent)
    if (data.canExpand && data.originalFnId) {
      expandNode(data.originalFnId);
    }
  });

  cy.on('tap', function(evt) {
    if (evt.target === cy) hideNodeDetails();
  });

  // Restore or fit viewport
  if (!shouldFit && savedZoom && savedPan) {
    cy.viewport({ zoom: savedZoom, pan: savedPan });
  } else {
    cy.fit(50);
  }
}

// Build graph elements
// Key: when a fn is "expanded", we show its PARENT instead of it
// The node shows "◀ childName" prefix in label to indicate collapse
function buildGraphElements() {
  const nodes = [];
  const edges = [];
  const addedNodeIds = new Set();

  if (!selectedFnId || !lookups.fnMap.has(selectedFnId)) {
    return { nodes: [], edges: [] };
  }

  // Get display info for a fn
  // Returns { displayFnId, chain, isExpanded }
  // chain = [originalFn, parent, grandparent, ...displayFn]
  function getDisplayInfo(fnId) {
    const chain = [];
    let current = fnId;
    while (true) {
      chain.push(current);
      if (!expandedNodes.has(current)) break;
      const fn = lookups.fnMap.get(current);
      if (fn && fn['parent-id']) {
        current = fn['parent-id'];
      } else {
        break;
      }
    }
    return {
      displayFnId: chain[chain.length - 1],
      chain: chain,
      isExpanded: chain.length > 1
    };
  }

  // Collect set args from chain
  function collectSetArgs(chain) {
    const setArgs = new Map();
    for (const fnId of chain) {
      const args = lookups.argsByFn.get(fnId) || [];
      args.forEach(arg => {
        const hasValue = arg.value !== null && arg.value !== undefined;
        const hasRef = !!arg['ref-id'];
        if (hasValue || hasRef) {
          let sourceId = arg.id;
          let cur = arg;
          while (cur['source-id']) {
            sourceId = cur['source-id'];
            cur = lookups.argMap.get(cur['source-id']);
            if (!cur) break;
          }
          if (!setArgs.has(sourceId)) {
            setArgs.set(sourceId, {
              argName: resolveArgName(arg),
              value: arg.value,
              refId: arg['ref-id'],
              argId: arg.id
            });
          }
        }
      });
    }
    return setArgs;
  }

  // Get unset args
  function getUnsetArgs(displayFnId, setArgs) {
    const args = lookups.argsByFn.get(displayFnId) || [];
    return args.filter(arg => {
      const hasValue = arg.value !== null && arg.value !== undefined;
      const hasRef = !!arg['ref-id'];
      if (hasValue || hasRef) return false;
      let sourceId = arg.id;
      let cur = arg;
      while (cur['source-id']) {
        sourceId = cur['source-id'];
        cur = lookups.argMap.get(cur['source-id']);
        if (!cur) break;
      }
      return !setArgs.has(sourceId);
    });
  }

  // Add fn node
  // originalFnId = the fn we're conceptually showing (may be expanded to show parent)
  // displayFnId = the actual fn being displayed
  // isExpanded = true if we're showing parent instead of original
  function addFnNode(originalFnId, isRoot) {
    const { displayFnId, chain, isExpanded } = getDisplayInfo(originalFnId);

    // Use a stable ID based on original fn so positions are preserved
    const nodeId = 'fn-' + originalFnId;
    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const displayFn = lookups.fnMap.get(displayFnId);
    if (!displayFn) return nodeId;

    const originalFn = lookups.fnMap.get(originalFnId);
    const hasParent = !!displayFn['parent-id'];

    // Build label
    let label = displayFn.name;

    if (isExpanded) {
      // Show collapse indicator: "◀ originalName" on first line
      label = '\u25C0 ' + originalFn.name + '\n' + '\u2500'.repeat(Math.max(originalFn.name.length + 2, displayFn.name.length)) + '\n' + displayFn.name;
      if (hasParent) {
        const parent = lookups.fnMap.get(displayFn['parent-id']);
        if (parent) {
          label += '\n' + '\u2500'.repeat(Math.max(displayFn.name.length, parent.name.length)) + '\n' + parent.name;
        }
      }
    } else if (hasParent) {
      // Not expanded, show parent below
      const parent = lookups.fnMap.get(displayFn['parent-id']);
      if (parent && parent.name !== displayFn.name) {
        const maxLen = Math.max(displayFn.name.length, parent.name.length);
        label = displayFn.name + '\n' + '\u2500'.repeat(maxLen) + '\n' + parent.name;
      }
    }

    nodes.push({
      data: {
        id: nodeId,
        label: label,
        type: 'fn',
        isRoot: isRoot,
        canExpand: hasParent && !isExpanded,
        canCollapse: isExpanded,
        originalFnId: originalFnId,
        displayFnId: displayFnId
      }
    });

    return nodeId;
  }

  // Add arg value node
  function addArgValueNode(argInfo, sourceNodeId) {
    const { argName, value, argId } = argInfo;
    const nodeId = 'arg-' + argId;

    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    let displayValue = JSON.stringify(value);
    if (displayValue.length > 20) {
      displayValue = displayValue.substring(0, 17) + '...';
    }

    nodes.push({
      data: {
        id: nodeId,
        label: displayValue,
        type: 'arg'
      }
    });

    edges.push({
      data: {
        id: 'e-val-' + argId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName
      }
    });

    return nodeId;
  }

  // Add unset arg placeholder
  function addUnsetArg(arg, sourceNodeId) {
    const argName = resolveArgName(arg);
    const nodeId = 'unset-' + arg.id;

    if (addedNodeIds.has(nodeId)) return;
    addedNodeIds.add(nodeId);

    nodes.push({
      data: {
        id: nodeId,
        label: arg.type || 'any',
        type: 'fn',
        isPlaceholder: true
      }
    });

    edges.push({
      data: {
        id: 'e-unset-' + arg.id,
        source: sourceNodeId,
        target: nodeId,
        argName: argName,
        isUnset: true
      }
    });
  }

  // Process ref arg
  function processRefArg(argInfo, sourceNodeId) {
    const { argName, refId, argId } = argInfo;

    // Add target fn node
    const targetNodeId = addFnNode(refId, false);

    // Add edge
    edges.push({
      data: {
        id: 'e-ref-' + argId,
        source: sourceNodeId,
        target: targetNodeId,
        argName: argName
      }
    });

    // Process target's args
    const { displayFnId, chain } = getDisplayInfo(refId);
    const targetSetArgs = collectSetArgs(chain);

    targetSetArgs.forEach((info) => {
      if (info.refId) {
        processRefArg(info, targetNodeId);
      } else if (info.value !== null && info.value !== undefined) {
        addArgValueNode(info, targetNodeId);
      }
    });

    const targetUnsetArgs = getUnsetArgs(displayFnId, targetSetArgs);
    targetUnsetArgs.forEach(arg => addUnsetArg(arg, targetNodeId));
  }

  // Main: process selected fn
  const rootNodeId = addFnNode(selectedFnId, true);
  const { displayFnId, chain } = getDisplayInfo(selectedFnId);
  const setArgs = collectSetArgs(chain);

  setArgs.forEach((argInfo) => {
    if (argInfo.refId) {
      processRefArg(argInfo, rootNodeId);
    } else if (argInfo.value !== null && argInfo.value !== undefined) {
      addArgValueNode(argInfo, rootNodeId);
    }
  });

  const unsetArgs = getUnsetArgs(displayFnId, setArgs);
  unsetArgs.forEach(arg => addUnsetArg(arg, rootNodeId));

  return { nodes, edges };
}

function showFnDetails(fnId) {
  const panel = document.getElementById('details-panel');
  panel.classList.remove('hidden');
  htmx.ajax('GET', '/partials/entity-details/fn/' + fnId, '#details-content');
}

function hideNodeDetails() {
  const panel = document.getElementById('details-panel');
  if (panel) panel.classList.add('hidden');
}

function closeDetailsPanel() {
  hideNodeDetails();
}

function collapseAll() {
  expandedNodes.clear();
  renderGraph(false);
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
  renderGraph(true);
}

function fitGraph() {
  if (cy) cy.fit(50);
}

document.addEventListener('DOMContentLoaded', initGraph);
document.body.addEventListener('entityCreated', refreshGraph);
document.body.addEventListener('entityUpdated', refreshGraph);
document.body.addEventListener('entityDeleted', refreshGraph);
