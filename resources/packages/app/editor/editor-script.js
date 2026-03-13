// Graph editor JavaScript - 2-entity schema visualization
// Interactive expand/collapse via ancestor list in nodes
let cy = null;
let selectedFnId = null;
let graphData = null;
let lookups = null;

// Map: originalFnId -> number of ancestors to show (0 = just self, 1 = self + parent, etc.)
let expansionLevel = new Map();

// For hover preview: originalFnId -> preview level (null if no preview)
let previewLevel = new Map();

const MAX_VISIBLE_ANCESTORS = 4; // Show at most N ancestors before scrolling

// Track if we need to update overlays
let overlayUpdatePending = false;

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

// Get inheritance chain: [fnId, parentId, grandparentId, ...]
function getInheritanceChain(fnId) {
  const chain = [];
  let current = fnId;
  const visited = new Set();
  while (current && !visited.has(current)) {
    visited.add(current);
    chain.push(current);
    const fn = lookups.fnMap.get(current);
    current = fn ? fn['parent-id'] : null;
  }
  return chain;
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
  expansionLevel.clear();
  previewLevel.clear();

  document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.classList.add('selected');

  const fn = lookups.fnMap.get(fnId);
  if (fn && updateHistory) {
    window.history.pushState(null, '', '#' + fn.name);
  }

  renderGraph(true);
}

// Set expansion level for a node
function setExpansionLevel(originalFnId, level) {
  if (level === 0) {
    expansionLevel.delete(originalFnId);
  } else {
    expansionLevel.set(originalFnId, level);
  }
  previewLevel.delete(originalFnId);
  renderGraph(false);
}

// Set preview level (hover) - rebuild graph to show/hide args
function setPreviewLevel(originalFnId, level) {
  const oldLevel = previewLevel.get(originalFnId);
  if (level === null) {
    previewLevel.delete(originalFnId);
  } else {
    previewLevel.set(originalFnId, level);
  }
  // Only rebuild if level actually changed
  if (oldLevel !== level) {
    renderGraph(false);
  }
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
      // fn node - hide label, overlay will show it
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
          var maxLen = Math.max(...lines.map(l => l.replace(/[^\x20-\x7E]/g, '').length));
          return Math.max(80, maxLen * 7 + 24);
        },
        'height': function(node) {
          var label = node.data('label') || '';
          var lines = label.split('\n').length;
          return Math.max(30, lines * 16 + 16);
        }
      }},
      // Root node
      { selector: 'node[?isRoot]', style: {
        'border-width': 4
      }},
      // Non-placeholder fn nodes - hide label, overlay will show it
      { selector: 'node[type="fn"][!isPlaceholder]', style: {
        'label': ''
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
    layout: { name: 'preset' },
    userZoomingEnabled: true,
    userPanningEnabled: true,
    minZoom: 0.2,
    maxZoom: 3
  });

  // Restore positions or run layout
  const existingNodes = [];
  const newNodes = [];
  const isFirstRender = savedPositions.size === 0;

  cy.nodes().forEach(node => {
    const saved = savedPositions.get(node.id());
    if (saved && !isFirstRender) {
      node.position(saved);
      node.lock();
      existingNodes.push(node);
    } else {
      newNodes.push(node);
    }
  });

  cy.layout({
    name: 'dagre',
    rankDir: 'LR',
    nodeSep: 60,
    edgeSep: 20,
    rankSep: 120,
    fit: false,
    animate: false
  }).run();

  existingNodes.forEach(node => node.unlock());

  // Click handler - delegate to overlay click handler via data
  cy.on('tap', 'node[type="fn"]', function(evt) {
    // Node taps are handled by the HTML overlay
  });

  cy.on('tap', function(evt) {
    if (evt.target === cy) hideNodeDetails();
  });

  // Update overlays on pan/zoom
  cy.on('pan zoom resize', function() {
    if (!overlayUpdatePending) {
      overlayUpdatePending = true;
      requestAnimationFrame(() => {
        updateOverlayPositions();
        overlayUpdatePending = false;
      });
    }
  });

  if (!shouldFit && savedZoom && savedPan) {
    cy.viewport({ zoom: savedZoom, pan: savedPan });
  } else {
    cy.fit(50);
  }

  // Create HTML overlays for interactive ancestor lists
  createNodeOverlays();
}

// Update positions of existing overlays without recreating them
function updateOverlayPositions() {
  const overlays = document.querySelectorAll('.node-overlay');
  const zoom = cy.zoom();

  overlays.forEach(overlay => {
    const originalFnId = overlay.dataset.originalFnId;
    const nodeId = 'fn-' + originalFnId;
    const node = cy.getElementById(nodeId);
    if (node.length === 0) return;

    const bb = node.renderedBoundingBox();
    overlay.style.left = (bb.x1) + 'px';
    overlay.style.top = (bb.y1) + 'px';
    overlay.style.width = (bb.x2 - bb.x1) + 'px';
    overlay.style.height = (bb.y2 - bb.y1) + 'px';
    // Scale text with zoom
    overlay.style.fontSize = (11 * zoom) + 'px';

    // Update padding on ancestor lines
    overlay.querySelectorAll('.ancestor-line').forEach(line => {
      line.style.padding = (2 * zoom) + 'px ' + (4 * zoom) + 'px';
    });
  });
}

// Create HTML overlays on top of cytoscape nodes for interactive ancestor selection
function createNodeOverlays() {
  // Remove existing overlays
  document.querySelectorAll('.node-overlay').forEach(el => el.remove());

  const container = document.getElementById('cy');
  const zoom = cy.zoom();

  cy.nodes('[type="fn"]').forEach(node => {
    const data = node.data();
    if (data.isPlaceholder) return;

    const bb = node.renderedBoundingBox();

    const overlay = document.createElement('div');
    overlay.className = 'node-overlay';
    overlay.style.position = 'absolute';
    overlay.style.left = (bb.x1) + 'px';
    overlay.style.top = (bb.y1) + 'px';
    overlay.style.width = (bb.x2 - bb.x1) + 'px';
    overlay.style.height = (bb.y2 - bb.y1) + 'px';
    overlay.style.pointerEvents = 'auto';
    overlay.style.display = 'flex';
    overlay.style.flexDirection = 'column';
    overlay.style.justifyContent = 'center';
    overlay.style.alignItems = 'center';
    overlay.style.overflow = 'hidden';
    overlay.dataset.originalFnId = data.originalFnId;
    // Scale text with zoom
    overlay.style.fontSize = (11 * zoom) + 'px';

    const currentLevel = previewLevel.get(data.originalFnId) ?? expansionLevel.get(data.originalFnId) ?? 0;
    const ancestors = data.ancestorList || [];

    // Show at most MAX_VISIBLE_ANCESTORS + 1 ancestors (including self)
    const visibleAncestors = ancestors.slice(0, MAX_VISIBLE_ANCESTORS + 1);
    const hasMore = ancestors.length > MAX_VISIBLE_ANCESTORS + 1;

    visibleAncestors.forEach((ancestorId, idx) => {
      const fn = lookups.fnMap.get(ancestorId);
      if (!fn) return;

      const line = document.createElement('div');
      line.className = 'ancestor-line';
      line.textContent = fn.name;
      line.dataset.level = idx;
      line.style.fontFamily = 'SF Mono, Monaco, monospace';
      line.style.padding = (2 * zoom) + 'px ' + (4 * zoom) + 'px';
      line.style.whiteSpace = 'nowrap';

      // If this node has ancestors, make it interactive
      if (ancestors.length > 1) {
        line.style.cursor = 'pointer';

        // Color: black if <= currentLevel, gray otherwise
        if (idx <= currentLevel) {
          line.style.color = '#000000';
          line.style.fontWeight = '500';
        } else {
          line.style.color = '#999999';
          line.style.fontWeight = 'normal';
        }

        // Hover: preview expansion
        line.addEventListener('mouseenter', () => {
          setPreviewLevel(data.originalFnId, idx);
        });

        line.addEventListener('mouseleave', () => {
          setPreviewLevel(data.originalFnId, null);
        });

        // Click: set expansion level
        line.addEventListener('click', (e) => {
          e.stopPropagation();
          setExpansionLevel(data.originalFnId, idx);
        });
      } else {
        // Single node without ancestors - just show name
        line.style.color = '#000000';
        line.style.fontWeight = '500';
      }

      overlay.appendChild(line);
    });

    // Show ellipsis if there are more ancestors
    if (hasMore) {
      const ellipsis = document.createElement('div');
      ellipsis.className = 'ancestor-line';
      ellipsis.textContent = '...';
      ellipsis.style.fontSize = '11px';
      ellipsis.style.fontFamily = 'SF Mono, Monaco, monospace';
      ellipsis.style.padding = '2px 4px';
      ellipsis.style.color = '#999999';
      overlay.appendChild(ellipsis);
    }

    container.appendChild(overlay);
  });
}

// Build graph elements
function buildGraphElements() {
  const nodes = [];
  const edges = [];
  const addedNodeIds = new Set();

  if (!selectedFnId || !lookups.fnMap.has(selectedFnId)) {
    return { nodes: [], edges: [] };
  }

  // Get effective expansion level (considering preview)
  function getEffectiveLevel(originalFnId) {
    if (previewLevel.has(originalFnId)) {
      return previewLevel.get(originalFnId);
    }
    return expansionLevel.get(originalFnId) || 0;
  }

  // Collect set args from expansion chain up to level
  function collectSetArgs(originalFnId) {
    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);
    const activeFns = chain.slice(0, level + 1); // fns whose args are shown

    const setArgs = new Map();
    for (const fnId of activeFns) {
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
    return { setArgs, activeFns };
  }

  // Get unset args from the most expanded fn
  function getUnsetArgs(originalFnId, setArgs) {
    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);
    const displayFnId = chain[Math.min(level, chain.length - 1)];

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
  function addFnNode(originalFnId, isRoot) {
    const nodeId = 'fn-' + originalFnId;
    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);

    // Build label: list of ancestor names
    // Active ones (up to level) are shown, we mark them in data
    const visibleChain = chain.slice(0, Math.min(chain.length, MAX_VISIBLE_ANCESTORS + 1));
    const labelLines = visibleChain.map((fnId, idx) => {
      const fn = lookups.fnMap.get(fnId);
      const name = fn ? fn.name : '?';
      // We can't do colors in cytoscape label, so just show names
      // The overlay will handle coloring
      return name;
    });

    if (chain.length > MAX_VISIBLE_ANCESTORS + 1) {
      labelLines.push('...');
    }

    const label = labelLines.join('\n');

    nodes.push({
      data: {
        id: nodeId,
        label: label,
        type: 'fn',
        isRoot: isRoot,
        originalFnId: originalFnId,
        ancestorList: chain, // Full chain for overlay
        currentLevel: level
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

    const targetNodeId = addFnNode(refId, false);

    edges.push({
      data: {
        id: 'e-ref-' + argId,
        source: sourceNodeId,
        target: targetNodeId,
        argName: argName
      }
    });

    // Process target's args
    const { setArgs } = collectSetArgs(refId);

    setArgs.forEach((info) => {
      if (info.refId) {
        processRefArg(info, targetNodeId);
      } else if (info.value !== null && info.value !== undefined) {
        addArgValueNode(info, targetNodeId);
      }
    });

    const unsetArgs = getUnsetArgs(refId, setArgs);
    unsetArgs.forEach(arg => addUnsetArg(arg, targetNodeId));
  }

  // Main: process selected fn
  const rootNodeId = addFnNode(selectedFnId, true);
  const { setArgs } = collectSetArgs(selectedFnId);

  setArgs.forEach((argInfo) => {
    if (argInfo.refId) {
      processRefArg(argInfo, rootNodeId);
    } else if (argInfo.value !== null && argInfo.value !== undefined) {
      addArgValueNode(argInfo, rootNodeId);
    }
  });

  const unsetArgs = getUnsetArgs(selectedFnId, setArgs);
  unsetArgs.forEach(arg => addUnsetArg(arg, rootNodeId));

  // Sort nodes by BFS order
  const sortedNodes = [];
  const nodeMap = new Map(nodes.map(n => [n.data.id, n]));
  const visited = new Set();
  const queue = [rootNodeId];

  while (queue.length > 0) {
    const nodeId = queue.shift();
    if (visited.has(nodeId)) continue;
    visited.add(nodeId);

    const node = nodeMap.get(nodeId);
    if (node) sortedNodes.push(node);

    edges.filter(e => e.data.source === nodeId)
      .forEach(e => {
        if (!visited.has(e.data.target)) {
          queue.push(e.data.target);
        }
      });
  }

  nodes.forEach(n => {
    if (!visited.has(n.data.id)) {
      sortedNodes.push(n);
    }
  });

  return { nodes: sortedNodes, edges };
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
  expansionLevel.clear();
  previewLevel.clear();
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
