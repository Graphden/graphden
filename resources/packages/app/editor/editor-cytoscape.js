// Editor Cytoscape - Cytoscape.js initialization and rendering
// Depends on: editor-state.js, editor-layout.js, editor-overlays.js

// ============================================================================
// CYTOSCAPE STYLES
// ============================================================================

const CYTOSCAPE_STYLES = [
  // fn node (base dimensions, used for calculating size).
  // Width/height come from precomputed layoutWidth/layoutHeight on node.data
  // (computed in editor-layout.js so CY node size MATCHES the actual rendered
  // overlay height — important for MI rows where cells wrap to multiple lines).
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
    'padding': '0px',
    'width': function(node) {
      return node.data('layoutWidth') || 80;
    },
    'height': function(node) {
      return node.data('layoutHeight') || 30;
    }
  }},
  // Non-placeholder fn nodes - hide completely, overlay shows content
  { selector: 'node[type="fn"][!isPlaceholder]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'overlay-opacity': 0    // suppress cytoscape's default click/select highlight
  }},
  // Placeholder (unset arg) - hide, overlay shows content with drag handle
  { selector: 'node[?isPlaceholder]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'padding': '0px',
    'overlay-opacity': 0
  }},
  // Arg value node - hide, overlay shows content with drag handle
  { selector: 'node[type="arg"]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'padding': '0px',
    'overlay-opacity': 0,
    'width': function(node) {
      return node.data('layoutWidth') || 40;
    },
    'height': function(node) {
      return node.data('layoutHeight') || 36;
    }
  }},
  // Edge - taxi style
  { selector: 'edge', style: {
    'width': 2,
    'line-color': '#000000',
    'line-style': 'solid',
    'curve-style': 'taxi',
    'taxi-direction': 'rightward',
    // Direction markers — one at each end, different shapes so the edge
    // visually reads "arg-slot of source fn ◯──▸ value fn":
    //   source: filled circle = socket/pin on the owning fn's arg
    //   target: triangle arrow = points at the referenced (value-producing) fn
    // Subtle at default scale; scales with line width via `arrow-scale`.
    'source-arrow-shape': 'circle',
    'source-arrow-color': '#000',
    'source-arrow-fill': 'filled',
    'target-arrow-shape': 'triangle',
    'target-arrow-color': '#000',
    'target-arrow-fill': 'filled',
    'arrow-scale': 0.9,
    // Bend AFTER the source node's column ends, so the vertical segment lands
    // in the inter-column gap. Without this, edges from a narrow node bend
    // INSIDE the column at source.right + 40px, which can collide with a
    // wider sibling node sharing the same column (different row).
    'taxi-turn': function(edge) {
      var src = edge.source();
      var colRight = src.data('colRightX');
      if (colRight === undefined) return 40;
      var srcRight = src.position().x + src.width() / 2;
      // 20px past the column boundary — clears any node in the source's
      // column and lands the vertical segment safely in the gap.
      return Math.max(20, colRight - srcRight + 20);
    },
    'taxi-turn-min-distance': '10px',
    'source-endpoint': 'outside-to-node',
    'target-endpoint': 'outside-to-node'
  }},
  // Edge labels are rendered as HTML overlays (see createEdgeLabelOverlay)
  // — Cytoscape's target-label doesn't render multi-line text reliably with
  // taxi edges, so we render them as positioned divs instead.
  // Unset edge - dashed, same black color
  { selector: 'edge[?isUnset]', style: {
    'line-style': 'dashed'
  }},
  // Hover highlight: edges fan out from a single fn's right edge and converge
  // onto single target nodes, so any hover lights up the whole visual bundle
  // (all edges that share the hovered edge's source OR target). Accent blue
  // reads as "interactive" without fighting the monochrome node aesthetic.
  { selector: 'edge.edge-hovered', style: {
    'line-color': '#0066cc',
    'source-arrow-color': '#0066cc',
    'target-arrow-color': '#0066cc',
    'z-index': 999
  }}
];

// ============================================================================
// CYTOSCAPE INITIALIZATION
// ============================================================================

// Floor for the rendered edge thickness in CSS pixels — just high enough that
// sub-pixel anti-aliasing still produces a visible line. Kept deliberately low
// so edges don't look disproportionately thick when the rest of the graph is
// small: they scale naturally down to this threshold and then hold.
const MIN_EDGE_PIXELS = 0.75;
// Edge thickness in graph units at zoom=1 (matches the static stylesheet).
const BASE_EDGE_WIDTH = 2;

/**
 * Let edge thickness scale with zoom, but don't let the rendered pixel width
 * fall below MIN_EDGE_PIXELS — below that sub-pixel rendering makes the line
 * disappear entirely.
 */
function updateEdgeWidthForZoom() {
  if (!cy) return;
  const z = cy.zoom();
  const w = Math.max(BASE_EDGE_WIDTH, MIN_EDGE_PIXELS / z);
  cy.edges().style('width', w);
}

/**
 * Create Cytoscape instance with initial elements and layout
 */
async function createCytoscape(nodes, edges, layout, shouldFit) {
  // Ensure cytoscape library is loaded
  if (typeof cytoscape === 'undefined') {
    console.error('Cytoscape library not loaded yet, retrying in 100ms...');
    await new Promise(resolve => setTimeout(resolve, 100));
    if (typeof cytoscape === 'undefined') {
      console.error('Cytoscape library still not loaded!');
      return;
    }
  }

  // Apply positions to nodes
  const nodesWithPos = nodes.map(n => {
    const pos = layout.get(n.data.id);
    if (pos) {
      return { ...n, position: { x: pos.x, y: pos.y } };
    }
    return n;
  });

  cy = cytoscape({
    container: document.getElementById('cy'),
    elements: { nodes: nodesWithPos, edges: edges },
    style: CYTOSCAPE_STYLES,
    layout: { name: 'preset' },
    minZoom: 0.1,
    maxZoom: 3,
    autoungrabify: true,    // Disable direct node dragging - only drag via overlay handle
    autounselectify: true,  // Disable selection — clicks are handled via overlays only
    boxSelectionEnabled: false
  });
  // Expose for browser-test debugging; harmless in production (single-graph page).
  window.cy = cy;

  if (shouldFit && cy.nodes().length > 0) {
    cy.fit(50);
  }

  // Event handlers for pan/zoom
  cy.on('pan zoom', function() {
    updateOverlayPositions();
    updateZoomSlider();
    updateEdgeWidthForZoom();
  });
  // Seed the zoom-aware edge width once on init so edges don't vanish when
  // the initial `cy.fit` picks a small zoom (big graphs) before any user pan/zoom.
  updateEdgeWidthForZoom();

  // Edge-hover highlight. Two regimes based on pointer position on the edge:
  //   - Near the SOURCE endpoint (the circle at the fn side) → light up the
  //     whole outgoing bundle, same as hovering the fn itself. The circle
  //     visually represents the "pin on the fn" so hovering it should act
  //     at the fn level.
  //   - Anywhere else along the edge → light up every sibling edge whose
  //     taxi path actually passes under the cursor. Sibling verticals
  //     overlap when several children stack on the same side of the source;
  //     Cytoscape's hit-test only picks one of them, so without this we'd
  //     light up just one of the two-or-more lines drawn at that pixel —
  //     looking random to the user. Edges whose vertical run lies on the
  //     opposite side of the cursor (i.e. children above when cursor is
  //     below source) are correctly excluded by the segment-containment
  //     check.
  // SOURCE_CIRCLE_RADIUS is in graph units (matches arrow-scale 0.9 × default
  // triangle half-length plus a small slack) so the hit-zone feels consistent
  // across zoom levels — Cytoscape reports `evt.position` in graph coords.
  const SOURCE_CIRCLE_RADIUS = 14;
  // Cursor-to-segment tolerance (graph units). Mouse: tight, since the
  // cursor lands on a single pixel. Touch: looser to compensate for
  // finger imprecision (a tap on the overlap zone may register a few
  // graph units off the line) but still small enough to not catch
  // unrelated rows above or below.
  const SEGMENT_TOL_MOUSE = 4;
  const SEGMENT_TOL_TOUCH = 14;

  function edgeSourceHit(evt) {
    const p = evt.position;
    const src = evt.target.sourceEndpoint();
    if (!p || !src) return false;
    const dx = p.x - src.x;
    const dy = p.y - src.y;
    return (dx * dx + dy * dy) <= SOURCE_CIRCLE_RADIUS * SOURCE_CIRCLE_RADIUS;
  }

  // The taxi-turn formula here mirrors the stylesheet's. Same source = same
  // bend X, so we can compute it once per source-node.
  function bendXFor(sourceNode) {
    const srcRight = sourceNode.position().x + sourceNode.width() / 2;
    const colRight = sourceNode.data('colRightX');
    const turn = colRight === undefined ? 40 : Math.max(20, colRight - srcRight + 20);
    return srcRight + turn;
  }

  // Returns the collection of sibling edges whose taxi path (any of the three
  // segments) passes within `tol` of the cursor.
  function siblingEdgesUnderCursor(sourceNode, cursor, tol) {
    const srcRight = sourceNode.position().x + sourceNode.width() / 2;
    const srcY = sourceNode.position().y;
    const bendX = bendXFor(sourceNode);
    return sourceNode.outgoers('edge').filter(e => {
      const tgt = e.target();
      const tgtLeft = tgt.position().x - tgt.width() / 2;
      const tgtY = tgt.position().y;
      const yLo = Math.min(srcY, tgtY);
      const yHi = Math.max(srcY, tgtY);
      // Segment 1: first horizontal (src.right → bendX) — shared by all
      // sibling edges from this source, so any hit here lights the bundle.
      if (cursor.x >= srcRight - tol && cursor.x <= bendX + tol
          && Math.abs(cursor.y - srcY) <= tol) return true;
      // Segment 2: vertical at bendX from src.y to tgt.y — overlaps with
      // siblings on the same side of source.y. The bounds-check naturally
      // excludes edges that turn the OTHER way before reaching cursor.y.
      if (Math.abs(cursor.x - bendX) <= tol
          && cursor.y >= yLo - tol && cursor.y <= yHi + tol) return true;
      // Segment 3: second horizontal (bendX → tgt.left) — unique per edge.
      if (cursor.x >= bendX - tol && cursor.x <= tgtLeft + tol
          && Math.abs(cursor.y - tgtY) <= tol) return true;
      return false;
    });
  }

  function setHovered(desired) {
    const desiredIds = new Set(desired.map(e => e.id()));
    cy.edges('.edge-hovered').forEach(e => {
      if (!desiredIds.has(e.id())) e.removeClass('edge-hovered');
    });
    desired.forEach(e => {
      if (!e.hasClass('edge-hovered')) e.addClass('edge-hovered');
    });
  }

  function clearHover() {
    cy.edges('.edge-hovered').removeClass('edge-hovered');
  }

  function hoveredFor(edge, evt, tol) {
    if (edgeSourceHit(evt)) return edge.source().outgoers('edge');
    const matched = siblingEdgesUnderCursor(edge.source(), evt.position, tol);
    // Fallback to just the hovered edge if our segment model doesn't catch it
    // (shouldn't happen for taxi edges but defensive).
    return matched.length > 0 ? matched : edge;
  }

  cy.on('mouseover', 'edge', function (evt) {
    setHovered(hoveredFor(evt.target, evt, SEGMENT_TOL_MOUSE));
  });
  cy.on('mousemove', 'edge', function (evt) {
    setHovered(hoveredFor(evt.target, evt, SEGMENT_TOL_MOUSE));
  });
  cy.on('mouseout', 'edge', function () {
    clearHover();
  });
  // Touch (iPad/iPhone): a finger doesn't "hover", so we wire tapstart +
  // tapdrag to mirror mouseover with a wider tolerance — finger taps
  // register a few graph units off the pixel-precise line, and SEGMENT_TOL
  // for mouse would miss the overlap. Lifting the finger clears.
  function isTouchEvent(evt) {
    const oe = evt && evt.originalEvent;
    return !!(oe && (oe.touches !== undefined || oe.pointerType === 'touch'));
  }
  cy.on('tapstart', 'edge', function (evt) {
    if (!isTouchEvent(evt)) return;
    setHovered(hoveredFor(evt.target, evt, SEGMENT_TOL_TOUCH));
  });
  cy.on('tapdrag', 'edge', function (evt) {
    if (!isTouchEvent(evt)) return;
    setHovered(hoveredFor(evt.target, evt, SEGMENT_TOL_TOUCH));
  });
  cy.on('tapend', function (evt) {
    if (!isTouchEvent(evt)) return;
    clearHover();
  });

  // Create overlays
  createNodeOverlays();

  // Sync slider with actual zoom after initial fit
  updateZoomSlider();
}

// ============================================================================
// RENDER GRAPH
// ============================================================================

/**
 * Render or update the graph
 * Fetches nodes, edges, and layout from backend in single request
 */
async function renderGraph(shouldFit = true) {
  // Capture anchor BEFORE await — anchorNodeId may be cleared by caller after async yield.
  // The anchor holds the FULL cytoscape node id (including `fn-{root}_{fn-id}`
  // scoping prefix where applicable) so scoped leaves stay stationary too.
  let capturedAnchorNodeId = anchorNodeId;
  let anchorOldPos = null;

  if (cy) {
    // Fallback: if no explicit anchor was set (e.g. the user triggered a
    // render path that didn't go through applyClickSpec/applyHoverSpec),
    // anchor to a preview key so hover re-renders don't drift the graph.
    if (!capturedAnchorNodeId && previewState.size > 0) {
      capturedAnchorNodeId = previewState.keys().next().value;
    }

    // Save anchor position BEFORE fetch (before any async yield)
    if (capturedAnchorNodeId) {
      const anchorNode = cy.getElementById(capturedAnchorNodeId);
      if (anchorNode.length) {
        anchorOldPos = { ...anchorNode.position() };
      }
    }
  }

  // Fetch everything from backend
  const result = await fetchBackendLayout();
  if (!result) {
    console.error('Failed to fetch layout from backend');
    return;
  }

  const { nodes, edges, layout } = result;

  // First render - create cytoscape
  if (!cy) {
    if (nodes.length > 0) {
      await createCytoscape(nodes, edges, layout, shouldFit);
    }
    return;
  }

  // Stop any running animations
  cy.nodes().forEach(node => node.stop(true, true));

  // Save positions of user-moved nodes BEFORE render. During preview,
  // nodes may be removed and re-added; we restore their positions after.
  cy.nodes().forEach(node => {
    if (userMovedNodes.has(node.id())) {
      savedUserPositions.set(node.id(), { ...node.position() });
    }
  });

  // Calculate offset to keep anchor node stationary.
  // SKIP if the anchor is a user-moved node — otherwise the offset shifts
  // all nodes to match the user's drag, visually "undoing" the drag.
  let offsetX = 0;
  let offsetY = 0;
  if (capturedAnchorNodeId && anchorOldPos && !userMovedNodes.has(capturedAnchorNodeId)) {
    const anchorNewPos = layout.get(capturedAnchorNodeId);
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
  const newNodeIds = new Set(nodes.map(n => n.data.id));
  const newEdgeIds = new Set(edges.map(e => e.data.id));

  // Find nodes/edges to remove and add
  const nodesToRemove = cy.nodes().filter(node => !newNodeIds.has(node.id()));
  const edgesToRemove = cy.edges().filter(edge => !newEdgeIds.has(edge.id()));
  const nodesToAdd = nodes.filter(n => !cy.getElementById(n.data.id).length);
  const edgesToAdd = edges.filter(e => !cy.getElementById(e.data.id).length);


  // Update existing node data
  cy.nodes().forEach(node => {
    const newData = nodes.find(n => n.data.id === node.id());
    if (newData) {
      node.data(newData.data);
    }
  });

  // Update existing edge data (e.g. argName changes when expansion reveals
  // rename chains — same edge id, different label)
  cy.edges().forEach(edge => {
    const newData = edges.find(e => e.data.id === edge.id());
    if (newData) {
      edge.data(newData.data);
    }
  });

  function completeUpdate() {
    suppressEdgeWarnings = true;

    // Remove old elements.
    // During preview, keep userMovedNodes entries (positions are saved).
    // On commit, entries are cleared via savedUserPositions.clear().
    const isPreview = previewState.size > 0;
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();
      if (!isPreview) {
        userMovedNodes.delete(node.id());
        savedUserPositions.delete(node.id());
      }
    });
    cy.remove(nodesToRemove);
    cy.remove(edgesToRemove);

    // Add new nodes with initial position.
    // If a node was user-moved (has a saved position), use that instead of layout.
    if (nodesToAdd.length > 0) {
      nodesToAdd.forEach(n => {
        const saved = savedUserPositions.get(n.data.id);
        if (saved && userMovedNodes.has(n.data.id)) {
          n.position = { x: saved.x, y: saved.y };
        } else {
          const pos = layout.get(n.data.id);
          if (pos) {
            n.position = { x: pos.x, y: pos.y };
          }
        }
      });
      cy.add(nodesToAdd);
    }

    // Add edges after nodes are positioned
    if (edgesToAdd.length > 0) {
      cy.add(edgesToAdd);
      // Re-apply the zoom-clamped width — the style() in updateEdgeWidthForZoom
      // targets the edge set at call time, so newly-added edges need a refresh.
      updateEdgeWidthForZoom();
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
        updateZoomSlider();
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
