// Editor Cytoscape - Cytoscape.js initialization and rendering
// Depends on: editor-state.js, editor-layout.js, editor-overlays.js, editor-graph.js

// ============================================================================
// CYTOSCAPE STYLES
// ============================================================================

const CYTOSCAPE_STYLES = [
  // fn node (base dimensions, used for calculating size)
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
  // Placeholder (unset arg) - hide, overlay shows content with drag handle
  { selector: 'node[?isPlaceholder]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0
  }},
  // Arg value node - hide, overlay shows content with drag handle
  { selector: 'node[type="arg"]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'width': function(node) {
      var label = node.data('label') || '';
      var maxLen = 30;
      var effectiveLen = Math.min(label.length, maxLen);
      return Math.max(40, effectiveLen * 6 + 16);
    },
    'height': 28 + 14  // content + drag handle
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
];

// ============================================================================
// CYTOSCAPE INITIALIZATION
// ============================================================================

/**
 * Create Cytoscape instance with initial elements
 */
function createCytoscape(elements, shouldFit) {
  cy = cytoscape({
    container: document.getElementById('cy'),
    elements: elements,
    style: CYTOSCAPE_STYLES,
    layout: { name: 'preset' },
    minZoom: 0.1,
    maxZoom: 3,
    autoungrabify: true  // Disable direct node dragging - only drag via overlay handle
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

  // Event handlers for pan/zoom
  cy.on('pan zoom', function() {
    updateOverlayPositions();
  });

  // Create overlays
  createNodeOverlays();
}

// ============================================================================
// RENDER GRAPH
// ============================================================================

/**
 * Render or update the graph
 * Handles anchor node positioning to keep expanded node stationary
 */
function renderGraph(shouldFit = true) {
  const elements = buildGraphElements();

  // First render - create cytoscape
  if (!cy) {
    createCytoscape(elements, shouldFit);
    return;
  }

  // Find the anchor node - the node that should stay stationary during layout
  // Priority: 1. Explicit anchorFnId (from click/expansion)
  //           2. Preview node (from hover)
  let anchorNodeId = null;
  let anchorOldPos = null;

  if (anchorFnId) {
    anchorNodeId = 'fn-' + anchorFnId;
  } else if (previewLevel.size > 0) {
    const previewFnId = previewLevel.keys().next().value;
    anchorNodeId = 'fn-' + previewFnId;
  }

  // Save anchor node's current position before any changes
  if (anchorNodeId) {
    const anchorNode = cy.getElementById(anchorNodeId);
    if (anchorNode.length) {
      anchorOldPos = { ...anchorNode.position() };
    }
  }

  // Stop any running animations
  cy.nodes().forEach(node => node.stop(true, true));

  // Build layout
  const layout = buildGridLayout(elements);

  // Calculate offset to keep anchor node stationary
  let offsetX = 0;
  let offsetY = 0;
  if (anchorNodeId && anchorOldPos) {
    const anchorNewPos = layout.get(anchorNodeId);
    if (anchorNewPos) {
      offsetX = anchorOldPos.x - anchorNewPos.x;
      offsetY = anchorOldPos.y - anchorNewPos.y;
    }
  }

  // Apply offset to all layout positions
  if (offsetX !== 0 || offsetY !== 0) {
    layout.forEach((pos, nodeId) => {
      pos.x += offsetX;
      pos.y += offsetY;
    });
  }

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
    suppressEdgeWarnings = true;

    // Remove old elements
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();
      userMovedNodes.delete(node.id());
    });
    cy.remove(nodesToRemove);
    cy.remove(edgesToRemove);

    // Add new nodes with initial position
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

      if (userMovedNodes.has(nodeId)) return;

      const pos = layout.get(nodeId);
      if (!pos) return;

      const targetPos = { x: pos.x, y: pos.y };
      const currentPos = node.position();

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
      suppressEdgeWarnings = false;
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
