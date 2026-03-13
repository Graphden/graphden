// Graph editor JavaScript - 2-entity schema visualization
// Interactive expand/collapse via ancestor list in nodes

// Suppress cytoscape warnings about overlapping nodes during animation
(function() {
  var originalWarn = console.warn;
  console.warn = function() {
    if (arguments[0] && typeof arguments[0] === 'string' &&
        arguments[0].indexOf('has invalid endpoints') !== -1) {
      return; // Suppress this specific warning
    }
    originalWarn.apply(console, arguments);
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

// Track which node is being hovered (to lock its position)
let hoveredNodeId = null;

// Flag to prevent mouseleave from triggering during overlay rebuild
let rebuildingOverlays = false;

// Flag to disable ancestor selection during drag
let isDragging = false;

// Flag to disable hover on all nodes when any node is being grabbed
let isGrabbing = false;

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

// Check if a function sets any arguments (has value or ref-id)
function fnSetsArgs(fnId) {
  const args = lookups.argsByFn.get(fnId) || [];
  return args.some(arg => {
    const hasValue = arg.value !== null && arg.value !== undefined;
    const hasRef = !!arg['ref-id'];
    return hasValue || hasRef;
  });
}

// Build grouped ancestor list for display
// Returns array of items, where each item is:
// { idx: number, name: string, fnId: string, groupLevel: number, isAlias: boolean }
// groupLevel is the level to use for hover/click (the first fn in the group that sets args)
function buildAncestorItems(chain) {
  const items = [];
  let currentGroupLevel = 0;

  for (let i = 0; i < chain.length; i++) {
    const fnId = chain[i];
    const fn = lookups.fnMap.get(fnId);
    if (!fn) continue;

    const setsArgs = fnSetsArgs(fnId);

    if (setsArgs) {
      // This fn sets args - it starts a new group
      currentGroupLevel = i;
      items.push({
        idx: i,
        name: fn.name,
        fnId: fnId,
        groupLevel: i,
        isAlias: false
      });
    } else {
      // This fn doesn't set args - it's an alias, belongs to previous group
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
    hoveredNodeId = null;
  } else {
    previewLevel.set(originalFnId, level);
    hoveredNodeId = 'fn-' + originalFnId;  // Lock this node's position
  }
  // Only rebuild if level actually changed
  if (oldLevel !== level) {
    renderGraph(false);
  }
}

// Clear all preview state (called when user starts dragging/panning)
function clearPreviewState() {
  if (previewLevel.size > 0) {
    previewLevel.clear();
    hoveredNodeId = null;
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

// Animation duration constant
const ANIM_DURATION = 200;

// Align nodes by left edge within each column (rank)
// Groups nodes by their approximate X position, then aligns left edges
function alignNodesByLeftEdge() {
  if (!cy) return;

  const nodes = cy.nodes();
  if (nodes.length === 0) return;

  // Group nodes by column (approximate X center position)
  const columns = new Map();  // rounded X -> [nodes]
  const tolerance = 50;  // Nodes within this X range are in same column

  nodes.forEach(node => {
    const x = node.position('x');
    const width = node.outerWidth();
    const leftEdge = x - width / 2;

    // Find or create column
    let foundColumn = null;
    for (const [colX, colNodes] of columns) {
      if (Math.abs(x - colX) < tolerance) {
        foundColumn = colX;
        break;
      }
    }

    if (foundColumn !== null) {
      columns.get(foundColumn).push({ node, leftEdge, width });
    } else {
      columns.set(x, [{ node, leftEdge, width }]);
    }
  });

  // For each column, find the minimum left edge and shift nodes
  columns.forEach((colNodes) => {
    if (colNodes.length <= 1) return;  // No alignment needed for single node

    // Find the leftmost left edge in this column
    const minLeftEdge = Math.min(...colNodes.map(n => n.leftEdge));

    // Shift each node so its left edge aligns with minLeftEdge
    colNodes.forEach(({ node, leftEdge, width }) => {
      const currentX = node.position('x');
      const targetLeftEdge = minLeftEdge;
      const targetX = targetLeftEdge + width / 2;
      const shift = targetX - currentX;

      if (Math.abs(shift) > 1) {
        node.position('x', targetX);
      }
    });
  });
}

function renderGraph(shouldFit = true) {
  const elements = buildGraphElements();

  // First render - just create cytoscape
  if (!cy) {
    createCytoscape(elements, shouldFit);
    return;
  }

  // Build maps for quick lookup
  const newNodeMap = new Map(elements.nodes.map(n => [n.data.id, n]));
  const newEdgeMap = new Map(elements.edges.map(e => [e.data.id, e]));
  const newNodeIds = new Set(newNodeMap.keys());
  const newEdgeIds = new Set(newEdgeMap.keys());

  // Get current positions
  const currentPositions = new Map();
  cy.nodes().forEach(node => {
    currentPositions.set(node.id(), { x: node.position('x'), y: node.position('y') });
  });

  // Find nodes/edges to remove and add
  const nodesToRemove = cy.nodes().filter(node => !newNodeIds.has(node.id()));
  const edgesToRemove = cy.edges().filter(edge => !newEdgeIds.has(edge.id()));
  const nodesToAdd = elements.nodes.filter(n => !cy.getElementById(n.data.id).length);
  const edgesToAdd = elements.edges.filter(e => !cy.getElementById(e.data.id).length);

  // Update existing node data (like label)
  cy.nodes().forEach(node => {
    const newData = newNodeMap.get(node.id());
    if (newData) {
      node.data(newData.data);
    }
  });

  // Function to complete the update
  function completeUpdate() {
    // Remove old elements
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();
    });
    cy.remove(nodesToRemove);
    cy.remove(edgesToRemove);

    // Add new elements
    if (nodesToAdd.length > 0 || edgesToAdd.length > 0) {
      cy.add(nodesToAdd);
      cy.add(edgesToAdd);

      // Position new nodes at their parent's position
      nodesToAdd.forEach(nodeData => {
        const node = cy.getElementById(nodeData.data.id);
        const edges = cy.edges().filter(e => e.data('target') === nodeData.data.id);
        if (edges.length > 0) {
          const parentId = edges[0].data('source');
          const parentPos = currentPositions.get(parentId);
          if (parentPos) {
            node.position(parentPos);
          }
        }
      });
    }

    // Lock hovered node position so it doesn't move during layout
    const lockedNodeId = hoveredNodeId;  // Capture current value
    if (lockedNodeId) {
      const hoveredNode = cy.getElementById(lockedNodeId);
      if (hoveredNode.length > 0) {
        hoveredNode.lock();
      }
    }

    // Run layout without animation
    const layout = cy.layout({
      name: 'dagre',
      rankDir: 'LR',
      nodeSep: 60,
      edgeSep: 20,
      rankSep: 120,
      align: 'UL',
      fit: false,
      animate: false
    });

    layout.run();

    // Align nodes by left edge within each column
    alignNodesByLeftEdge();

    // Save final positions after alignment
    const finalPositions = new Map();
    cy.nodes().forEach(node => {
      finalPositions.set(node.id(), { ...node.position() });
    });

    // Reset existing nodes to old positions for animation
    cy.nodes().forEach(node => {
      const oldPos = currentPositions.get(node.id());
      if (oldPos) {
        node.position(oldPos);
      }
    });

    // Animate all nodes to final positions
    const animationPromises = [];
    cy.nodes().forEach(node => {
      const targetPos = finalPositions.get(node.id());
      if (targetPos) {
        const anim = node.animation({
          position: targetPos,
          duration: ANIM_DURATION,
          easing: 'ease-out'
        });
        animationPromises.push(anim.play().promise());
      }
    });

    // Update overlays continuously during animation
    let animating = true;
    function updateLoop() {
      if (animating) {
        updateOverlayPositions();
        requestAnimationFrame(updateLoop);
      }
    }

    // Recreate overlays for new state
    rebuildingOverlays = true;
    createNodeOverlays();
    rebuildingOverlays = false;
    requestAnimationFrame(updateLoop);

    // When all animations complete
    Promise.all(animationPromises).then(() => {
      animating = false;
      if (lockedNodeId) {
        const lockedNode = cy.getElementById(lockedNodeId);
        if (lockedNode.length > 0) {
          lockedNode.unlock();
        }
      }
      updateOverlayPositions();
    });
  }

  // If there are nodes to remove, animate them first
  if (nodesToRemove.length > 0) {
    const animations = [];
    nodesToRemove.forEach(node => {
      // Remove overlay immediately
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();

      // Find parent to animate towards
      const edges = cy.edges().filter(e => e.data('target') === node.id());
      if (edges.length > 0) {
        const parentId = edges[0].data('source');
        const parentNode = cy.getElementById(parentId);
        if (parentNode.length > 0) {
          animations.push(
            node.animation({
              position: parentNode.position(),
              style: { opacity: 0 },
              duration: ANIM_DURATION,
              easing: 'ease-in'
            }).play().promise()
          );
        }
      }
    });

    if (animations.length > 0) {
      Promise.all(animations).then(completeUpdate);
    } else {
      completeUpdate();
    }
  } else {
    completeUpdate();
  }
}

function createCytoscape(elements, shouldFit) {
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
          var maxLineLen = 30;  // Max chars per line for sizing
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
        'label': function(node) {
          var label = node.data('label') || '';
          var maxLen = 30;
          if (label.length > maxLen) {
            return label.substring(0, maxLen - 1) + '…';
          }
          return label;
        },
        'text-valign': 'center',
        'text-halign': 'center',
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'shape': 'rectangle',
        'background-color': '#ffffff',
        'border-width': 1,
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
      // Edge - taxi style with rightward direction
      { selector: 'edge', style: {
        'width': 2,
        'line-color': '#000000',
        'line-style': 'solid',
        'curve-style': 'taxi',
        'taxi-direction': 'rightward',
        'taxi-turn': '50%',  // Turn at midpoint between nodes
        'taxi-turn-min-distance': '20px',
        'edge-distances': 'intersection'
      }},
      // Edge with label - position near target on final horizontal segment
      { selector: 'edge[argName]', style: {
        'target-label': function(edge) {
          var label = edge.data('argName') || '';
          var maxLen = 28;
          if (label.length > maxLen) {
            return label.substring(0, maxLen - 1) + '…';
          }
          return label;
        },
        'target-text-offset': function(edge) {
          // Offset based on label length so it doesn't overlap with target node
          var label = edge.data('argName') || '';
          var effectiveLen = Math.min(label.length, 28);
          var charWidth = 6;
          // Half the label width + some padding
          return (effectiveLen * charWidth / 2 + 15) + 'px';
        },
        'target-text-margin-y': -10,
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
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

  // Click handler
  cy.on('tap', function(evt) {
    if (evt.target === cy) hideNodeDetails();
  });

  // Track actual dragging (not just grab/free)
  // isGrabbing: blocks hover on ALL nodes immediately when any node is grabbed
  // isDragging: blocks clicks, set only after actual movement (3+ pixels)
  let dragStartPos = null;

  cy.on('grab', function(evt) {
    var node = evt.target;
    dragStartPos = node.position();
    isGrabbing = true;  // Block hover on all nodes immediately
    clearPreviewState();
  });

  cy.on('drag', function(evt) {
    // Only set isDragging if node actually moved (for click blocking)
    if (dragStartPos && !isDragging) {
      var node = evt.target;
      var pos = node.position();
      var dx = Math.abs(pos.x - dragStartPos.x);
      var dy = Math.abs(pos.y - dragStartPos.y);
      if (dx > 3 || dy > 3) {
        isDragging = true;
      }
    }
  });

  // When drag ends, re-enable ancestor selection
  cy.on('free', function(evt) {
    dragStartPos = null;
    isGrabbing = false;  // Re-enable hover
    // Small delay to prevent immediate click after actual drag
    if (isDragging) {
      setTimeout(() => {
        isDragging = false;
      }, 50);
    }
  });

  // When pan starts, also clear preview
  cy.on('viewport', function(evt) {
    clearPreviewState();
  });

  // Update overlays on pan/zoom/drag
  cy.on('pan zoom resize drag', function() {
    if (!overlayUpdatePending) {
      overlayUpdatePending = true;
      requestAnimationFrame(() => {
        updateOverlayPositions();
        overlayUpdatePending = false;
      });
    }
  });

  // Initial layout and fit
  if (shouldFit) {
    cy.layout({
      name: 'dagre',
      rankDir: 'LR',
      nodeSep: 60,
      edgeSep: 20,
      rankSep: 120,
      align: 'UL',  // Align nodes to upper-left within their rank
      fit: false,
      animate: false
    }).run();
    alignNodesByLeftEdge();
    cy.fit(50);
    createNodeOverlays();
  }
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
      line.style.paddingLeft = (4 * zoom) + 'px';
      line.style.paddingRight = (4 * zoom) + 'px';
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
    overlay.style.pointerEvents = 'none';  // Overlay itself doesn't capture
    overlay.style.display = 'flex';
    overlay.style.flexDirection = 'column';
    overlay.style.justifyContent = 'center';
    overlay.style.alignItems = 'center';
    overlay.style.overflow = 'hidden';
    overlay.dataset.originalFnId = data.originalFnId;
    // Scale text with zoom
    overlay.style.fontSize = (11 * zoom) + 'px';

    // Text container - captures mouse events to prevent cytoscape grab
    const textContainer = document.createElement('div');
    textContainer.style.pointerEvents = 'auto';  // Block events from reaching cytoscape
    textContainer.style.display = 'flex';
    textContainer.style.flexDirection = 'column';
    textContainer.style.justifyContent = 'center';
    textContainer.style.alignItems = 'center';
    textContainer.style.padding = (4 * zoom) + 'px';
    // Prevent mousedown from propagating to cytoscape
    textContainer.addEventListener('mousedown', (e) => {
      e.stopPropagation();
    });

    const currentLevel = previewLevel.get(data.originalFnId) ?? expansionLevel.get(data.originalFnId) ?? 0;
    const ancestors = data.ancestorList || [];

    // Build items with grouping info
    const items = buildAncestorItems(ancestors);

    // Limit visible items
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);
    const hasMore = items.length > MAX_VISIBLE_ANCESTORS + 1;

    // Check if there are multiple distinct groups (for interactivity)
    const distinctGroups = new Set(items.map(item => item.groupLevel));
    const hasMultipleGroups = distinctGroups.size > 1;

    // Track if mouse is on any line of this overlay
    let linesHovered = 0;

    visibleItems.forEach((item) => {
      const line = document.createElement('div');
      line.className = 'ancestor-line';
      line.textContent = item.name;
      line.dataset.level = item.idx;
      line.dataset.groupLevel = item.groupLevel;
      line.style.fontFamily = 'SF Mono, Monaco, monospace';
      // Use lineHeight instead of padding to avoid gaps between lines
      line.style.lineHeight = '1.6';
      line.style.paddingLeft = (4 * zoom) + 'px';
      line.style.paddingRight = (4 * zoom) + 'px';
      line.style.whiteSpace = 'nowrap';
      line.style.width = '100%';
      line.style.textAlign = 'center';
      line.style.boxSizing = 'border-box';

      // If there are multiple groups, make it interactive
      if (hasMultipleGroups) {
        line.style.cursor = 'pointer';

        // Color: black if this item's groupLevel <= currentLevel, gray otherwise
        if (item.groupLevel <= currentLevel) {
          line.style.color = '#000000';
          line.style.fontWeight = '500';
        } else {
          line.style.color = '#999999';
          line.style.fontWeight = 'normal';
        }

        // Hover: preview expansion to this item's groupLevel
        // Only preview if hovering on a level ABOVE current expansion
        line.addEventListener('mouseenter', () => {
          if (isGrabbing) return;  // Ignore when any node is being dragged
          linesHovered++;
          const currentExpansion = expansionLevel.get(data.originalFnId) || 0;
          // Only set preview for levels above current expansion
          if (item.groupLevel > currentExpansion) {
            setPreviewLevel(data.originalFnId, item.groupLevel);
          }
        });

        line.addEventListener('mouseleave', () => {
          if (isGrabbing) return;  // Ignore when any node is being dragged
          linesHovered--;
          // Reset preview only if no lines are hovered and not rebuilding
          setTimeout(() => {
            if (linesHovered <= 0 && !rebuildingOverlays && !isGrabbing) {
              setPreviewLevel(data.originalFnId, null);
            }
          }, 10);
        });

        // Click: set expansion level to this item's groupLevel
        line.addEventListener('click', (e) => {
          if (isDragging) return;  // Ignore during drag
          e.stopPropagation();
          e.preventDefault();
          setExpansionLevel(data.originalFnId, item.groupLevel);
        });
      } else {
        // Single group - just show name
        line.style.color = '#000000';
        line.style.fontWeight = '500';
      }

      textContainer.appendChild(line);
    });

    // Show ellipsis if there are more items
    if (hasMore) {
      const ellipsis = document.createElement('div');
      ellipsis.className = 'ancestor-line';
      ellipsis.textContent = '...';
      ellipsis.style.fontFamily = 'SF Mono, Monaco, monospace';
      ellipsis.style.lineHeight = '1.6';
      ellipsis.style.paddingLeft = (4 * zoom) + 'px';
      ellipsis.style.paddingRight = (4 * zoom) + 'px';
      ellipsis.style.color = '#999999';
      ellipsis.style.width = '100%';
      ellipsis.style.textAlign = 'center';
      textContainer.appendChild(ellipsis);
    }

    overlay.appendChild(textContainer);
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

  // Get root source id for an arg (follow source-id chain to the top)
  function getRootSourceId(arg) {
    let sourceId = arg.id;
    let cur = arg;
    while (cur['source-id']) {
      sourceId = cur['source-id'];
      cur = lookups.argMap.get(cur['source-id']);
      if (!cur) break;
    }
    return sourceId;
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
          const sourceId = getRootSourceId(arg);
          if (!setArgs.has(sourceId)) {
            setArgs.set(sourceId, {
              argName: resolveArgName(arg),
              value: arg.value,
              refId: arg['ref-id'],
              argId: arg.id,
              sourceId: sourceId  // Store root source id for stable node ids
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
      const sourceId = getRootSourceId(arg);
      return !setArgs.has(sourceId);
    }).map(arg => ({
      ...arg,
      sourceId: getRootSourceId(arg)  // Add sourceId for stable node ids
    }));
  }

  // Add fn node
  function addFnNode(originalFnId, isRoot) {
    const nodeId = 'fn-' + originalFnId;
    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);

    // Build items for display
    const items = buildAncestorItems(chain);
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);

    // Build label from items (for sizing)
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
        originalFnId: originalFnId,
        ancestorList: chain, // Full chain for overlay
        currentLevel: level
      }
    });

    return nodeId;
  }

  // Add arg value node - use sourceId for stable identity across expansion levels
  function addArgValueNode(argInfo, sourceNodeId) {
    const { argName, value, sourceId } = argInfo;
    const nodeId = 'arg-' + sourceId;  // Use sourceId for stable id

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
        id: 'e-val-' + sourceId,  // Use sourceId for stable edge id
        source: sourceNodeId,
        target: nodeId,
        argName: argName
      }
    });

    return nodeId;
  }

  // Add unset arg placeholder - use sourceId for stable identity
  function addUnsetArg(arg, sourceNodeId) {
    const argName = resolveArgName(arg);
    const sourceId = arg.sourceId || arg.id;  // Use sourceId if available
    const nodeId = 'unset-' + sourceId;

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
        id: 'e-unset-' + sourceId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName,
        isUnset: true
      }
    });
  }

  // Process ref arg
  function processRefArg(argInfo, sourceNodeId) {
    const { argName, refId, sourceId } = argInfo;

    const targetNodeId = addFnNode(refId, false);

    // Use combination of source node and target fn for edge id
    // This ensures stable edges when expansion level changes
    const edgeId = 'e-ref-' + sourceNodeId + '-' + refId;

    edges.push({
      data: {
        id: edgeId,
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
