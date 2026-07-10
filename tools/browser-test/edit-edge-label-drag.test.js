// Regression: the edge-label must anchor AFTER the taxi bend, even once the
// user has dragged the source node out of its layout column.
//
// The bend X used to be computed in three places with two different formulas.
// The cytoscape `taxi-turn` style and the edge-hover hit-test clamped the bend
// to `srcRight + 20` using the node's LIVE position; the edge-label anchor used
// a bare `colRightX + 20`. `colRightX` is stamped once per layout
// (editor-layout.js) and does not follow a dragged node — so dragging a node
// right, past its own column's right edge, put the label LEFT of the bend the
// edge actually drew, on top of the source node.
//
// All three now call the single `taxiBendX` (editor-layout.js). This test
// pins that contract: the pure clamp, and the rendered invariant after a drag.
//
// Read-only — no DB writes, no seeding, no cleanup.
//
// Run:  node edit-edge-label-drag.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

// Any fn whose graph has labelled edges. `web-server` composes a router and a
// handler, so its card fans out several named-arg edges.
const PROBE_FN = 'web-server';

// How far right to drag the source node. Must exceed the widest plausible
// column so `srcRight` overtakes the stale `colRightX` and the clamp engages.
const DRAG_DX = 600;

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-edge-label-drag — label anchors after the bend post-drag');

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0 && !cy.animated(),
      null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — `taxiBendX` is reachable and clamps as specified.
    // Pure: feeds stub nodes, touches no rendering.
    // ===================================================================
    const pure = await page.evaluate(() => {
      if (typeof taxiBendX !== 'function') return {missing: true};
      const stub = (x, w, colRight) => ({
        position: () => ({x, y: 0}),
        width: () => w,
        data: (k) => (k === 'colRightX' ? colRight : undefined),
      });
      return {
        // No layout yet → fixed fallback turn of 40 past src.right (=50).
        noColumn: taxiBendX(stub(0, 100, undefined)),
        // Node sits inside its column → bend clears the column (200 + 20).
        insideColumn: taxiBendX(stub(0, 100, 200)),
        // Node dragged past its stale column right-edge → the clamp wins,
        // bend stays 20 ahead of src.right (450 + 20), NOT behind it at 220.
        draggedPastColumn: taxiBendX(stub(400, 100, 200)),
      };
    });
    assert(!pure.missing, 'taxiBendX is in scope (editor-layout.js loaded)');
    assert(pure.noColumn === 90, 'no colRightX → src.right + 40 fallback turn');
    assert(pure.insideColumn === 220, 'inside column → colRightX + 20 clearance');
    assert(pure.draggedPastColumn === 470,
           'dragged past column → clamped to src.right + 20 (not stale 220)');

    // ===================================================================
    // Phase B — rendered invariant. Drag a labelled edge's source node far
    // right, then compare the label's left edge against the bend, both in
    // graph coordinates.
    // ===================================================================
    await page.waitForFunction(
      () => typeof _edgeOverlaysByEdgeId !== 'undefined'
            && _edgeOverlaysByEdgeId.size > 0,
      null, {timeout: 20000, polling: 100});

    const rendered = await page.evaluate((dx) => {
      // Pick the first labelled edge whose source still has a live position
      // and a stamped column (an un-laid-out source can't exercise the clamp).
      for (const [edgeId, overlay] of _edgeOverlaysByEdgeId) {
        const edge = cy.getElementById(edgeId);
        if (!edge.length) continue;
        const src = edge.source();
        if (!src.length || src.data('colRightX') === undefined) continue;

        // Reproduce exactly the state a drag leaves behind: the node's
        // position moves, `colRightX` stays stale. (editor-drag.js mutates
        // cyNode.position() and re-runs updateOverlayPositions.)
        const startX = src.position().x;
        const startY = src.position().y;
        src.position({x: startX + dx, y: startY});
        updateOverlayPositions();

        // cy.pan() hands back a LIVE reference — snapshot the primitives.
        const panX = cy.pan().x;
        const zoom = cy.zoom();

        const bendGraph = taxiBendX(src);
        const labelLeftGraph = (parseFloat(overlay.style.left) - panX) / zoom;
        // What the old formula would have produced, to prove this scenario
        // actually engages the clamp rather than passing vacuously.
        const staleBendGraph = src.data('colRightX') + 20;

        return {edgeId, bendGraph, labelLeftGraph, staleBendGraph};
      }
      return null;
    }, DRAG_DX);

    assert(rendered, 'found a labelled edge with a laid-out source node');
    assert(rendered.staleBendGraph < rendered.bendGraph,
           'scenario engages the clamp (stale formula would sit behind the bend)');
    assert(rendered.labelLeftGraph >= rendered.bendGraph,
           'label starts at or after the bend (was: left of it, over the node)');

    // ===================================================================
    // Phase C — the guard is not vacuous. Swap the shared `taxiBendX` for
    // the pre-fix formula (bare `colRightX + 20`, no live-position clamp),
    // re-run the positioner, and confirm Phase B's invariant breaks. This
    // executes the OLD code path in the shipped bundle, so the regression
    // test is proven to detect the regression it names.
    // ===================================================================
    const regressed = await page.evaluate((edgeId) => {
      const fixed = taxiBendX;
      // Function declarations land on the global object, and every caller
      // looks the name up there — so this really does re-route the positioner.
      // eslint-disable-next-line no-global-assign
      taxiBendX = (src) => {
        const colRight = src.data('colRightX');
        const srcRight = src.position().x + src.width() / 2;
        return colRight === undefined ? srcRight + 40 : colRight + 20;
      };
      updateOverlayPositions();

      const overlay = _edgeOverlaysByEdgeId.get(edgeId);
      const panX = cy.pan().x;
      const zoom = cy.zoom();
      const labelLeftGraph = (parseFloat(overlay.style.left) - panX) / zoom;

      // Restore before anything else observes the patched global.
      taxiBendX = fixed;
      updateOverlayPositions();

      // The bend the EDGE draws is unaffected by the label's formula — the
      // cytoscape style calls the (restored) shared function.
      const edge = cy.getElementById(edgeId);
      return {labelLeftGraph, bendGraph: taxiBendX(edge.source())};
    }, rendered.edgeId);

    assert(regressed.labelLeftGraph < regressed.bendGraph,
           'pre-fix formula puts the label BEFORE the bend — guard is non-vacuous');

    // And the restore actually took, so we leave the page consistent.
    const restored = await page.evaluate((edgeId) => {
      const overlay = _edgeOverlaysByEdgeId.get(edgeId);
      const panX = cy.pan().x;
      const zoom = cy.zoom();
      const edge = cy.getElementById(edgeId);
      return {
        labelLeftGraph: (parseFloat(overlay.style.left) - panX) / zoom,
        bendGraph: taxiBendX(edge.source()),
      };
    }, rendered.edgeId);
    assert(restored.labelLeftGraph >= restored.bendGraph,
           'invariant holds again once the shared taxiBendX is restored');

    console.log('  PASS');
  } finally {
    await browser.close();
  }
})();
