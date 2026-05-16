// Editor Cytoscape - Cytoscape.js initialization and rendering
// Depends on: editor-state.js, editor-layout.js, editor-overlays.js

// ============================================================================
// CYTOSCAPE STYLES
// ============================================================================

// Cytoscape renders to canvas — it doesn't honour `var(--*)` references the
// way HTML overlays do. We resolve the CSS vars at style-build time and
// re-apply on theme change (see `applyThemeToCytoscape`).
//
// Read from `document.body` because the dark-theme overrides are scoped
// there (`body.theme-dark { --bg: ...; }`); reading from documentElement
// would always return the `:root` defaults regardless of theme.
function cssVar(name) {
  return getComputedStyle(document.body).getPropertyValue(name).trim();
}

function buildCytoscapeStyles() {
  const cardBg = cssVar('--card-bg');
  const cardBorder = cssVar('--card-border');
  const cardFg = cssVar('--card-fg');
  const edgeColor = cssVar('--fg');
  const accent = cssVar('--accent');
  return [
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
    'background-color': cardBg,
    'border-width': 2,
    'border-color': cardBorder,
    'color': cardFg,
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
  // Placeholder (unset arg) - hide; overlay shows a small `+` binder
  // for editable viewers, nothing for read-only viewers (the empty
  // edge endpoint is itself the "slot is unbound" signal).
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
    'line-color': edgeColor,
    'line-style': 'solid',
    'curve-style': 'taxi',
    'taxi-direction': 'rightward',
    // Direction markers — one at each end, different shapes so the edge
    // visually reads "arg-slot of source fn ◯──▸ value fn":
    //   source: filled circle = socket/pin on the owning fn's arg
    //   target: triangle arrow = points at the referenced (value-producing) fn
    // Subtle at default scale; scales with line width via `arrow-scale`.
    'source-arrow-shape': 'circle',
    'source-arrow-color': edgeColor,
    'source-arrow-fill': 'filled',
    'target-arrow-shape': 'triangle',
    'target-arrow-color': edgeColor,
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
  //
  // Unset edges keep the same solid line — the absence of a value at the
  // edge's endpoint (only a small `+` binder, not a fn card) is sufficient
  // signal that the slot is unbound. Type expectations read off the
  // type-chip on the edge label, not from a separate placeholder card.
  // Hover highlight: edges fan out from a single fn's right edge and converge
  // onto single target nodes, so any hover lights up the whole visual bundle
  // (all edges that share the hovered edge's source OR target). Accent blue
  // reads as "interactive" without fighting the monochrome node aesthetic.
  { selector: 'edge.edge-hovered', style: {
    'line-color': accent,
    'source-arrow-color': accent,
    'target-arrow-color': accent,
    'z-index': 999
  }}
  ];
}

// Re-apply cytoscape style with current theme colors. Called by editor-prefs
// when the user toggles the theme — without this, edges and node borders
// keep their old (black/white) colors after the body class flips.
function applyThemeToCytoscape() {
  // window.cy starts undefined and only becomes a cytoscape instance after
  // createCytoscape runs. Theme can flip before that (the early-init pass
  // applies stored theme on every page load). Bail until the instance is
  // ready and offers the `.style()` API.
  const c = window.cy;
  if (!c || typeof c.style !== 'function') return;
  c.style().fromJson(buildCytoscapeStyles()).update();
  if (typeof updateEdgeWidthForZoom === 'function') updateEdgeWidthForZoom();
}
window.applyThemeToCytoscape = applyThemeToCytoscape;

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
 * `cy.fit` thinks of the canvas as the full container, but with the
 * overlay layout the leftmost slice of the canvas sits behind the
 * sidebar and isn't actually visible. We fit-to-visible by computing
 * zoom and pan ourselves: zoom to the bounding box that fits in
 * (canvasWidth − sidebarWidth − 2*padding), then pan so the bbox
 * centre lands at the centre of the visible area (right of the
 * sidebar).
 */
function fitInVisibleArea(padding) {
  if (!cy || cy.nodes().length === 0) return;
  const cyContainer = document.getElementById('cy');
  if (!cyContainer) { cy.fit(padding); return; }
  const canvasW = cyContainer.clientWidth;
  const canvasH = cyContainer.clientHeight;
  const sidebar = document.getElementById('side-menu');
  const collapsed = document.body.classList.contains('sidebar-collapsed');
  const sidebarW = (sidebar && !collapsed) ? sidebar.getBoundingClientRect().width : 0;
  const visibleW = Math.max(canvasW - sidebarW, 100);
  const visibleH = canvasH;

  const bb = cy.elements().boundingBox();
  if (bb.w <= 0 || bb.h <= 0) { cy.fit(padding); return; }

  const targetZoom = Math.min(
    (visibleW - 2 * padding) / bb.w,
    (visibleH - 2 * padding) / bb.h
  );
  const minZ = (typeof cy.minZoom === 'function') ? cy.minZoom() : 0.1;
  const maxZ = (typeof cy.maxZoom === 'function') ? cy.maxZoom() : 3;
  const zoom = Math.min(Math.max(targetZoom, minZ), maxZ);
  const visibleCenterX = sidebarW + visibleW / 2;
  const visibleCenterY = canvasH / 2;
  const bbCx = (bb.x1 + bb.x2) / 2;
  const bbCy = (bb.y1 + bb.y2) / 2;
  cy.zoom(zoom);
  cy.pan({
    x: visibleCenterX - bbCx * zoom,
    y: visibleCenterY - bbCy * zoom
  });
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
    style: buildCytoscapeStyles(),
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
    fitInVisibleArea(50);
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
    const oe = evt?.originalEvent;
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


  // Update existing node + edge data. Cytoscape's `.data(obj)` is a
  // MERGE — fields the backend stops sending stick around on the
  // element. That bit us on edges' `typeChain`: after the user
  // collapses an expansion the backend correctly returns an edge
  // without the chain, but the old chain lingered in cy data and
  // the renderer kept drawing the stale narrowing row. Replace by
  // clearing keys that don't appear in the new payload, then merging.
  const replaceData = (el, newData) => {
    if (!newData) return;
    const newKeys = new Set(Object.keys(newData));
    for (const k of Object.keys(el.data())) {
      if (!newKeys.has(k)) el.removeData(k);
    }
    el.data(newData);
  };
  cy.nodes().forEach(node => {
    const fresh = nodes.find(n => n.data.id === node.id());
    if (fresh) replaceData(node, fresh.data);
  });
  cy.edges().forEach(edge => {
    const fresh = edges.find(e => e.data.id === edge.id());
    if (fresh) replaceData(edge, fresh.data);
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
        fitInVisibleArea(50);
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
