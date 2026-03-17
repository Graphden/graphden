// Graph editor JavaScript - 2-entity schema visualization
// Grid-based layout with interactive expand/collapse

// Suppress Cytoscape edge overlap warnings only during user drag
let suppressEdgeWarnings = false;
(function() {
  const originalWarn = console.warn;
  console.warn = function(...args) {
    if (suppressEdgeWarnings && args[0] && typeof args[0] === 'string' && args[0].includes('invalid endpoints')) {
      return; // Suppress this specific warning during drag
    }
    originalWarn.apply(console, args);
  };
})();

let cy = null;
let selectedFnId = null;
let graphData = null;
let lookups = null;

// Map: originalFnId -> number of ancestors to show (0 = just self, 1 = self + parent, etc.)
let expansionLevel = new Map();

// For hover preview: originalFnId -> preview level (null if no preview)
let previewLevel = new Map();

// Flag to prevent mouseleave from triggering during overlay rebuild
let rebuildingOverlays = false;

// Flag to disable hover on all nodes when any node is being grabbed
let isGrabbing = false;

const MAX_VISIBLE_ANCESTORS = 4;

// User-moved nodes (won't be auto-positioned)
let userMovedNodes = new Set();

// Animation duration
const ANIM_DURATION = 200;

// Grid constants
const GRID_GAP_X = 80;  // Horizontal gap between columns (space for edge routing)
const GRID_GAP_Y = 30;  // Vertical gap between rows

// Truncate label to maxLen with ellipsis
function truncateLabel(label, maxLen) {
  if (label.length > maxLen) {
    return label.substring(0, maxLen - 1) + '…';
  }
  return label;
}

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

// Check if a function sets any arguments
function fnSetsArgs(fnId) {
  const args = lookups.argsByFn.get(fnId) || [];
  return args.some(arg => {
    const hasValue = arg.value !== null && arg.value !== undefined;
    const hasRef = !!arg['ref-id'];
    return hasValue || hasRef;
  });
}

// Build grouped ancestor list for display
function buildAncestorItems(chain) {
  const items = [];
  let currentGroupLevel = 0;

  for (let i = 0; i < chain.length; i++) {
    const fnId = chain[i];
    const fn = lookups.fnMap.get(fnId);
    if (!fn) continue;

    const setsArgs = fnSetsArgs(fnId);

    if (setsArgs) {
      currentGroupLevel = i;
      items.push({
        idx: i,
        name: fn.name,
        fnId: fnId,
        groupLevel: i,
        isAlias: false
      });
    } else {
      items.push({
        idx: i,
        name: '(' + fn.name + ')',
        fnId: fnId,
        groupLevel: currentGroupLevel,
        isAlias: true
      });
    }
  }

  return items;
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
  userMovedNodes.clear();

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

// Debounce timer for preview level changes
let previewDebounceTimer = null;
const PREVIEW_DEBOUNCE_MS = 100;

// Set preview level (hover)
function setPreviewLevel(originalFnId, level) {
  const oldLevel = previewLevel.get(originalFnId);

  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }

  if (level === null) {
    previewLevel.delete(originalFnId);
    if (oldLevel !== level) {
      renderGraph(false);
    }
  } else {
    previewDebounceTimer = setTimeout(() => {
      previewLevel.set(originalFnId, level);
      if (oldLevel !== level) {
        renderGraph(false);
      }
    }, PREVIEW_DEBOUNCE_MS);
  }
}

// Clear all preview state
function clearPreviewState() {
  if (previewLevel.size > 0) {
    previewLevel.clear();
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

// ============================================================================
// GRID-BASED LAYOUT
// ============================================================================

// Calculate node dimensions based on label
function calculateNodeSize(nodeData) {
  const label = nodeData.data.label || '';
  const type = nodeData.data.type;

  if (type === 'arg') {
    const maxLen = 30;
    const effectiveLen = Math.min(label.length, maxLen);
    return {
      width: Math.max(40, effectiveLen * 6 + 16),
      height: 28
    };
  } else {
    // fn node
    const lines = label.split('\n');
    const maxLineLen = 30;
    const maxLen = Math.max(...lines.map(l => {
      const cleanLen = l.replace(/[^\x20-\x7E]/g, '').length;
      return Math.min(cleanLen, maxLineLen);
    }));
    return {
      width: Math.max(80, maxLen * 7 + 24),
      height: Math.max(30, lines.length * 16 + 16)
    };
  }
}

// Build grid layout from graph elements
// Returns: Map<nodeId, {row, col, width, height, x, y}>
// Uses layoutGraph from layout-core.js for matrix building
function buildGridLayout(elements) {
  const { nodes, edges } = elements;
  if (nodes.length === 0) return new Map();

  // Use layout-core.js for matrix building
  const result = layoutGraph(elements);
  const { matrix, gridPos, collisions, validation } = result;

  // Log any issues for debugging
  if (collisions.length > 0) {
    console.error('Layout collisions:', collisions);
  }
  if (!validation.valid) {
    console.error('Layout validation issues:', validation.issues);
  }

  // Calculate sizes for all nodes
  const sizes = new Map();
  nodes.forEach(n => {
    sizes.set(n.data.id, calculateNodeSize(n));
  });

  // Calculate column widths (max width in each column)
  const colWidths = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    if (size) {
      const currentMax = colWidths.get(pos.col) || 0;
      colWidths.set(pos.col, Math.max(currentMax, size.width));
    }
  });

  // Calculate row heights (max height in each row)
  const rowHeights = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    if (size) {
      const currentMax = rowHeights.get(pos.row) || 0;
      rowHeights.set(pos.row, Math.max(currentMax, size.height));
    }
  });

  // Calculate extra gap needed before each column based on edge label lengths
  const { hEdge } = matrix;
  const colExtraGap = new Map();
  const maxColKey = Math.max(...Array.from(colWidths.keys()), 0);
  for (let c = 1; c <= maxColKey; c++) {
    let maxLabelLen = 0;
    for (let r = 0; r < hEdge.length; r++) {
      const argName = hEdge[r] && hEdge[r][c - 1];
      if (argName && typeof argName === 'string') {
        maxLabelLen = Math.max(maxLabelLen, argName.length);
      }
    }
    colExtraGap.set(c, maxLabelLen > 0 ? maxLabelLen * 7 : 0);
  }

  // Calculate X positions for left edge of each column
  const colLeftX = new Map();
  let currentX = 0;
  for (let c = 0; c <= maxColKey; c++) {
    const extraGap = colExtraGap.get(c) || 0;
    currentX += extraGap;
    colLeftX.set(c, currentX);
    const width = colWidths.get(c) || 80;
    currentX += width + GRID_GAP_X;
  }

  // Calculate Y positions for center of each row
  const rowCenterY = new Map();
  let currentY = 0;
  const maxRowKey = Math.max(...Array.from(rowHeights.keys()), 0);
  for (let r = 0; r <= maxRowKey; r++) {
    const height = rowHeights.get(r) || 30;
    rowCenterY.set(r, currentY + height / 2);
    currentY += height + GRID_GAP_Y;
  }

  // Build final layout: nodeId -> {x, y, width, height, row, col}
  const layout = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    if (size) {
      const leftX = colLeftX.get(pos.col);
      layout.set(nodeId, {
        x: leftX + size.width / 2,
        y: rowCenterY.get(pos.row),
        width: size.width,
        height: size.height,
        row: pos.row,
        col: pos.col
      });
    }
  });

  return layout;
}

// ============================================================================
// RENDER GRAPH
// ============================================================================

function renderGraph(shouldFit = true) {
  const elements = buildGraphElements();

  // First render - create cytoscape
  if (!cy) {
    createCytoscape(elements, shouldFit);
    return;
  }

  // Stop any running animations
  cy.nodes().forEach(node => node.stop(true, true));

  // Build layout
  const layout = buildGridLayout(elements);

  // Build maps for quick lookup
  const newNodeIds = new Set(elements.nodes.map(n => n.data.id));
  const newEdgeIds = new Set(elements.edges.map(e => e.data.id));

  // Find nodes/edges to remove and add
  const nodesToRemove = cy.nodes().filter(node => !newNodeIds.has(node.id()));
  const edgesToRemove = cy.edges().filter(edge => !newEdgeIds.has(edge.id()));
  const nodesToAdd = elements.nodes.filter(n => !cy.getElementById(n.data.id).length);
  const edgesToAdd = elements.edges.filter(e => !cy.getElementById(e.data.id).length);

  // Update existing node data
  cy.nodes().forEach(node => {
    const newData = elements.nodes.find(n => n.data.id === node.id());
    if (newData) {
      node.data(newData.data);
    }
  });

  function completeUpdate() {
    // Remove old elements
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();
      userMovedNodes.delete(node.id());
    });
    cy.remove(nodesToRemove);
    cy.remove(edgesToRemove);

    // Add new nodes with initial position (to avoid zero-length edges)
    if (nodesToAdd.length > 0) {
      nodesToAdd.forEach(n => {
        const pos = layout.get(n.data.id);
        if (pos) {
          n.position = { x: pos.x, y: pos.y };
        }
      });
      cy.add(nodesToAdd);
    }

    // Add edges after nodes are positioned
    if (edgesToAdd.length > 0) {
      cy.add(edgesToAdd);
    }

    // Apply layout positions with animation
    const animPromises = [];

    cy.nodes().forEach(node => {
      const nodeId = node.id();

      // Skip user-moved nodes
      if (userMovedNodes.has(nodeId)) return;

      const pos = layout.get(nodeId);
      if (!pos) return;

      const targetPos = { x: pos.x, y: pos.y };
      const currentPos = node.position();

      // New nodes are already at target position (set during add)
      // Only animate existing nodes that need to move
      const isNewNode = nodesToAdd.some(n => n.data.id === nodeId);
      if (!isNewNode && (Math.abs(currentPos.x - targetPos.x) > 1 || Math.abs(currentPos.y - targetPos.y) > 1)) {
        const anim = node.animation({
          position: targetPos,
          duration: ANIM_DURATION,
          easing: 'ease-out'
        });
        animPromises.push(anim.play().promise());
      } else if (!isNewNode) {
        node.position(targetPos);
      }
    });

    // Update overlays during animation
    let animating = true;
    function updateLoop() {
      if (animating) {
        updateOverlayPositions();
        requestAnimationFrame(updateLoop);
      }
    }

    rebuildingOverlays = true;
    createNodeOverlays();
    rebuildingOverlays = false;
    requestAnimationFrame(updateLoop);

    Promise.all(animPromises).then(() => {
      animating = false;
      updateOverlayPositions();
      if (shouldFit && cy.nodes().length > 0) {
        cy.fit(50);
      }
    });
  }

  // If nodes to remove, animate them first
  if (nodesToRemove.length > 0) {
    const removeAnims = [];
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();

      const parentEdge = cy.edges().filter(e => e.data('target') === node.id());
      if (parentEdge.length > 0) {
        const parentId = parentEdge[0].data('source');
        const parentNode = cy.getElementById(parentId);
        if (parentNode.length > 0) {
          const parentPos = parentNode.position();
          removeAnims.push(
            node.animation({
              position: { x: parentPos.x, y: parentPos.y },
              style: { opacity: 0 },
              duration: ANIM_DURATION,
              easing: 'ease-in'
            }).play().promise()
          );
        }
      }
    });

    if (removeAnims.length > 0) {
      // Suppress edge warnings during collapse animation
      suppressEdgeWarnings = true;
      Promise.all(removeAnims).then(() => {
        suppressEdgeWarnings = false;
        completeUpdate();
      });
    } else {
      completeUpdate();
    }
  } else {
    completeUpdate();
  }
}

// ============================================================================
// CYTOSCAPE SETUP
// ============================================================================

function createCytoscape(elements, shouldFit) {
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
          var maxLineLen = 30;
          var maxLen = Math.max(...lines.map(function(l) {
            var cleanLen = l.replace(/[^\x20-\x7E]/g, '').length;
            return Math.min(cleanLen, maxLineLen);
          }));
          return Math.max(80, maxLen * 7 + 24);
        },
        'height': function(node) {
          var label = node.data('label') || '';
          var lines = label.split('\n').length;
          return Math.max(30, lines * 16 + 16);
        }
      }},
      // Non-placeholder fn nodes - hide completely, overlay shows content
      { selector: 'node[type="fn"][!isPlaceholder]', style: {
        'label': '',
        'background-opacity': 0,
        'border-width': 0
      }},
      // Placeholder (unset arg) - dashed border, same black color
      { selector: 'node[?isPlaceholder]', style: {
        'border-style': 'dashed'
      }},
      // Arg value node
      { selector: 'node[type="arg"]', style: {
        'label': function(node) {
          return truncateLabel(node.data('label') || '', 30);
        },
        'text-valign': 'center',
        'text-halign': 'center',
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'shape': 'rectangle',
        'background-color': '#ffffff',
        'border-width': 2,
        'border-color': '#000000',
        'color': '#000000',
        'padding': '8px',
        'width': function(node) {
          var label = node.data('label') || '';
          var maxLen = 30;
          var effectiveLen = Math.min(label.length, maxLen);
          return Math.max(40, effectiveLen * 6 + 16);
        },
        'height': 28
      }},
      // Edge - taxi style
      { selector: 'edge', style: {
        'width': 2,
        'line-color': '#000000',
        'line-style': 'solid',
        'curve-style': 'taxi',
        'taxi-direction': 'rightward',
        'taxi-turn': '40px',
        'taxi-turn-min-distance': '10px',
        'source-endpoint': 'outside-to-node',
        'target-endpoint': 'outside-to-node'
      }},
      // Edge labels
      { selector: 'edge[argName]', style: {
        'target-label': function(edge) {
          return truncateLabel(edge.data('argName') || '', 28);
        },
        'target-text-offset': function(edge) {
          var label = edge.data('argName') || '';
          return Math.max(30, label.length * 3 + 15);
        },
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'color': '#666666',
        'text-background-color': '#ffffff',
        'text-background-opacity': 1,
        'text-background-padding': '3px'
      }},
      // Unset edge - dashed, same black color
      { selector: 'edge[?isUnset]', style: {
        'line-style': 'dashed'
      }}
    ],
    layout: { name: 'preset' },
    minZoom: 0.1,
    maxZoom: 3
  });

  // Apply initial layout
  const layout = buildGridLayout({ nodes: elements.nodes, edges: elements.edges });
  cy.nodes().forEach(node => {
    const pos = layout.get(node.id());
    if (pos) {
      node.position({ x: pos.x, y: pos.y });
    }
  });

  if (shouldFit && cy.nodes().length > 0) {
    cy.fit(50);
  }

  // Track user-moved nodes
  cy.on('dragfree', 'node', function(evt) {
    userMovedNodes.add(evt.target.id());
  });

  // Event handlers
  cy.on('grab', 'node', function() {
    isGrabbing = true;
    suppressEdgeWarnings = true;
    clearPreviewState();
  });

  cy.on('free', 'node', function() {
    isGrabbing = false;
    suppressEdgeWarnings = false;
  });

  cy.on('pan zoom', function() {
    updateOverlayPositions();
  });

  cy.on('drag', 'node', function() {
    updateOverlayPositions();
  });

  // Create overlays
  createNodeOverlays();
}

// ============================================================================
// NODE OVERLAYS (for ancestor list interaction)
// ============================================================================

function createNodeOverlays() {
  // Remove existing overlays
  document.querySelectorAll('.node-overlay').forEach(el => el.remove());

  if (!cy) return;

  const container = document.getElementById('cy');

  cy.nodes('[type="fn"][!isPlaceholder]').forEach(node => {
    const originalFnId = node.data('originalFnId');
    if (!originalFnId) return;

    const chain = getInheritanceChain(originalFnId);
    const items = buildAncestorItems(chain);

    const overlay = document.createElement('div');
    overlay.className = 'node-overlay';
    overlay.dataset.originalFnId = originalFnId;
    overlay.style.position = 'absolute';
    overlay.style.pointerEvents = 'auto';
    overlay.style.zIndex = '10';
    overlay.style.background = 'white';
    overlay.style.border = '2px solid black';
    overlay.style.borderRadius = '8px';
    overlay.style.overflow = 'hidden';
    overlay.style.fontFamily = 'SF Mono, Monaco, monospace';
    overlay.style.fontSize = '11px';
    overlay.style.cursor = 'pointer';

    // Build content
    const currentLevel = expansionLevel.get(originalFnId) || 0;
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);
    const hasMore = items.length > MAX_VISIBLE_ANCESTORS + 1;

    visibleItems.forEach((item, idx) => {
      const line = document.createElement('div');
      line.className = 'ancestor-line';
      line.dataset.level = item.groupLevel;
      line.style.padding = '4px 8px';
      // Only show separator between different groups
      const nextItem = visibleItems[idx + 1];
      const showSeparator = nextItem && nextItem.groupLevel !== item.groupLevel;
      line.style.borderBottom = showSeparator ? '1px solid #eee' : 'none';
      line.textContent = item.name;

      if (item.groupLevel <= currentLevel) {
        line.style.fontWeight = 'bold';
        line.style.background = '#f0f0f0';
      }

      line.addEventListener('mouseenter', () => {
        if (!isGrabbing) {
          setPreviewLevel(originalFnId, item.groupLevel);
        }
      });

      line.addEventListener('click', (e) => {
        e.stopPropagation();
        setExpansionLevel(originalFnId, item.groupLevel);
      });

      overlay.appendChild(line);
    });

    if (hasMore) {
      const more = document.createElement('div');
      more.style.padding = '2px 8px';
      more.style.color = '#999';
      more.style.fontSize = '10px';
      more.textContent = '...';
      overlay.appendChild(more);
    }

    // Add drag handle at the bottom
    const dragHandle = document.createElement('div');
    dragHandle.className = 'drag-handle';
    dragHandle.style.height = '12px';
    dragHandle.style.background = 'linear-gradient(to bottom, #f0f0f0, #ddd)';
    dragHandle.style.borderTop = '1px solid #ccc';
    dragHandle.style.cursor = 'grab';
    dragHandle.style.display = 'flex';
    dragHandle.style.alignItems = 'center';
    dragHandle.style.justifyContent = 'center';
    dragHandle.innerHTML = '<span style="color:#999;font-size:8px;">⋮⋮⋮</span>';

    // Make drag handle pass events to cytoscape node
    dragHandle.addEventListener('mousedown', (e) => {
      e.stopPropagation();
      e.preventDefault();

      const cyNode = cy.getElementById('fn-' + originalFnId);
      if (!cyNode.length) return;

      isGrabbing = true;
      dragHandle.style.cursor = 'grabbing';

      let lastX = e.clientX;
      let lastY = e.clientY;

      const onMouseMove = (moveE) => {
        const dx = (moveE.clientX - lastX) / cy.zoom();
        const dy = (moveE.clientY - lastY) / cy.zoom();
        lastX = moveE.clientX;
        lastY = moveE.clientY;

        const pos = cyNode.position();
        cyNode.position({ x: pos.x + dx, y: pos.y + dy });
        updateOverlayPositions();
      };

      const onMouseUp = () => {
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', onMouseUp);
        isGrabbing = false;
        dragHandle.style.cursor = 'grab';
      };

      document.addEventListener('mousemove', onMouseMove);
      document.addEventListener('mouseup', onMouseUp);
    });

    overlay.appendChild(dragHandle);

    overlay.addEventListener('mouseleave', () => {
      if (!rebuildingOverlays && !isGrabbing) {
        setPreviewLevel(originalFnId, null);
      }
    });

    container.appendChild(overlay);
  });

  updateOverlayPositions();
}

function updateOverlayPositions() {
  if (!cy) return;

  const pan = cy.pan();
  const zoom = cy.zoom();

  document.querySelectorAll('.node-overlay').forEach(overlay => {
    const originalFnId = overlay.dataset.originalFnId;
    const node = cy.getElementById('fn-' + originalFnId);
    if (!node.length) return;

    const pos = node.position();
    const width = node.outerWidth();
    const height = node.outerHeight();

    const screenX = pos.x * zoom + pan.x;
    const screenY = pos.y * zoom + pan.y;

    // After scale(zoom), element will be width*zoom x height*zoom
    // We want to center it at (screenX, screenY)
    // With transform-origin: center, the scaled element centers at (left + width/2, top + height/2)
    // So: left + width/2 = screenX, meaning left = screenX - width/2
    overlay.style.left = (screenX - width / 2) + 'px';
    overlay.style.top = (screenY - height / 2) + 'px';
    overlay.style.width = width + 'px';
    overlay.style.minHeight = height + 'px';
    overlay.style.transform = 'scale(' + zoom + ')';
    overlay.style.transformOrigin = 'center center';
  });
}

// ============================================================================
// BUILD GRAPH ELEMENTS
// ============================================================================

function buildGraphElements() {
  const nodes = [];
  const edges = [];
  const addedNodeIds = new Set();

  if (!selectedFnId || !lookups.fnMap.has(selectedFnId)) {
    return { nodes: [], edges: [] };
  }

  function getEffectiveLevel(originalFnId) {
    if (previewLevel.has(originalFnId)) {
      return previewLevel.get(originalFnId);
    }
    return expansionLevel.get(originalFnId) || 0;
  }

  // Get the full source chain for an arg: [arg.id, source-id, source-id's source-id, ...]
  function getSourceChain(argId) {
    const chain = [argId];
    let current = lookups.argMap.get(argId);
    while (current && current['source-id']) {
      chain.push(current['source-id']);
      current = lookups.argMap.get(current['source-id']);
    }
    return chain;
  }

  // Build bindings map: source-id -> binding info
  // Bindings come from fns between originalFnId and displayFnId
  function buildBindings(originalFnId, displayLevel) {
    const chain = getInheritanceChain(originalFnId);
    const childFns = chain.slice(0, displayLevel); // fns that provide bindings

    const bindings = new Map(); // any source-id in chain -> {value, refId, argName, argId}

    for (const fnId of childFns) {
      const args = lookups.argsByFn.get(fnId) || [];
      args.forEach(arg => {
        const hasValue = arg.value !== null && arg.value !== undefined;
        const hasRef = !!arg['ref-id'];
        if ((hasValue || hasRef) && arg['source-id']) {
          const sourceChain = getSourceChain(arg.id);
          const bindingInfo = {
            value: arg.value,
            refId: arg['ref-id'],
            argName: resolveArgName(arg),
            argId: arg.id
          };
          // Mark all ancestors in source chain as bound
          sourceChain.forEach(srcId => {
            if (!bindings.has(srcId)) {
              bindings.set(srcId, bindingInfo);
            }
          });
        }
      });
    }
    return bindings;
  }

  // Build bindings map: source-arg-id -> bound value/ref from originalFn
  // This maps each free arg in parent fn's structure to its bound value
  function buildArgBindings(originalFnId) {
    const bindings = new Map(); // source-arg-id -> {argName, value, refId}
    const args = lookups.argsByFn.get(originalFnId) || [];

    args.forEach(arg => {
      const hasValue = arg.value !== null && arg.value !== undefined;
      const hasRef = !!arg['ref-id'];

      if ((hasValue || hasRef) && arg['source-id']) {
        // This arg binds a value to source-id
        // Follow the source chain to find all source args this binds
        let sourceId = arg['source-id'];
        while (sourceId) {
          bindings.set(sourceId, {
            argName: resolveArgName(arg),
            value: arg.value,
            refId: arg['ref-id'],
            argId: arg.id
          });
          const sourceArg = lookups.argMap.get(sourceId);
          sourceId = sourceArg ? sourceArg['source-id'] : null;
        }
      }
    });

    return bindings;
  }

  // Collect args for a fn, applying bindings from child fn
  // Returns args split into: refs (to other fns), values (literals), unset (free)
  function collectFnArgs(fnId, bindings) {
    const args = lookups.argsByFn.get(fnId) || [];
    const refs = [];
    const values = [];
    const unset = [];

    args.forEach(arg => {
      const argName = resolveArgName(arg);
      const hasValue = arg.value !== null && arg.value !== undefined;
      const hasRef = !!arg['ref-id'];

      // Check if this arg is bound by child
      const binding = bindings.get(arg.id);

      if (binding) {
        // Arg is bound by child - use bound value
        if (binding.refId) {
          refs.push({ argName: binding.argName, refId: binding.refId, argId: binding.argId });
        } else if (binding.value !== null && binding.value !== undefined) {
          values.push({ argName: binding.argName, value: binding.value, argId: binding.argId });
        }
      } else if (hasRef) {
        // Arg has ref in this fn
        refs.push({ argName, refId: arg['ref-id'], argId: arg.id });
      } else if (hasValue) {
        // Arg has value in this fn
        values.push({ argName, value: arg.value, argId: arg.id });
      } else {
        // Free arg (unset)
        unset.push({ argName, type: arg.type || 'any', argId: arg.id });
      }
    });

    // Sort: refs first, then values, then unset
    return { refs, values, unset };
  }


  function addFnNode(originalFnId, isRoot) {
    const nodeId = 'fn-' + originalFnId;
    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const chain = getInheritanceChain(originalFnId);
    const items = buildAncestorItems(chain);
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);

    const labelLines = visibleItems.map(item => item.name);
    if (items.length > MAX_VISIBLE_ANCESTORS + 1) {
      labelLines.push('...');
    }

    const label = labelLines.join('\n');

    nodes.push({
      data: {
        id: nodeId,
        label: label,
        type: 'fn',
        isRoot: isRoot,
        originalFnId: originalFnId
      }
    });

    return nodeId;
  }

  function addArgValueNode(argName, value, argId, sourceNodeId) {
    const nodeId = 'arg-' + argId;

    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const displayValue = truncateLabel(JSON.stringify(value), 20);

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

  function addUnsetArgNode(argName, argType, argId, sourceNodeId) {
    const nodeId = 'unset-' + argId;

    if (addedNodeIds.has(nodeId)) return;
    addedNodeIds.add(nodeId);

    nodes.push({
      data: {
        id: nodeId,
        label: argType || 'any',
        type: 'fn',
        isPlaceholder: true
      }
    });

    edges.push({
      data: {
        id: 'e-unset-' + argId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName,
        isUnset: true
      }
    });
  }

  // Process a fn and its args, with bindings from parent
  // originalFnId: the fn that was clicked/expanded (for overlay tracking)
  // displayFnId: the fn whose args we're showing
  // bindings: Map of source-arg-id -> {argName, value, refId} from the calling fn
  function processFn(originalFnId, displayFnId, bindings, sourceNodeId, edgeArgName, isRoot) {
    // Use originalFnId for node id (so overlay can find it)
    const nodeId = addFnNode(originalFnId, isRoot);

    // Add edge from source if not root
    if (sourceNodeId && edgeArgName !== null) {
      const edgeId = 'e-ref-' + sourceNodeId + '-' + originalFnId;
      if (!addedNodeIds.has(edgeId)) {
        addedNodeIds.add(edgeId);
        edges.push({
          data: {
            id: edgeId,
            source: sourceNodeId,
            target: nodeId,
            argName: edgeArgName
          }
        });
      }
    }

    // Get displayFn's args and apply bindings
    const { refs, values, unset } = collectFnArgs(displayFnId, bindings);

    // Process ref args (children fns)
    // For children, originalFnId = refId (they're not expanded)
    refs.forEach(({ argName, refId, argId }) => {
      // Pass bindings down so nested free args can be substituted
      processFn(refId, refId, bindings, nodeId, argName, false);
    });

    // Process value args
    values.forEach(({ argName, value, argId }) => {
      addArgValueNode(argName, value, argId, nodeId);
    });

    // Process unset args (free args shown as placeholders)
    unset.forEach(({ argName, type, argId }) => {
      addUnsetArgNode(argName, type, argId, nodeId);
    });

    return nodeId;
  }

  // Process expanded fn - show internal structure of parent fn with bindings applied
  function processExpandedFn(originalFnId, level, sourceNodeId, edgeArgName, isRoot) {
    const chain = getInheritanceChain(originalFnId);
    const displayFnId = chain[Math.min(level, chain.length - 1)];

    // Build bindings from originalFn's args
    const bindings = buildArgBindings(originalFnId);

    // Show the displayFn (parent) structure but keep originalFnId for tracking
    return processFn(originalFnId, displayFnId, bindings, sourceNodeId, edgeArgName, isRoot);
  }

  // Main entry point for processing a fn
  function processAnyFn(fnId, sourceNodeId, edgeArgName, isRoot) {
    const level = getEffectiveLevel(fnId);

    if (level > 0) {
      return processExpandedFn(fnId, level, sourceNodeId, edgeArgName, isRoot);
    } else {
      // Build bindings from this fn's own args (for substitution)
      const bindings = buildArgBindings(fnId);
      // originalFnId = displayFnId when not expanded
      return processFn(fnId, fnId, bindings, sourceNodeId, edgeArgName, isRoot);
    }
  }

  // Main: process selected fn
  processAnyFn(selectedFnId, null, null, true);

  return { nodes, edges };
}

document.addEventListener('DOMContentLoaded', initGraph);
