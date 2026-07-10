// Overlays live in one transformed layer, in graph coordinates.
//
// Before: every overlay carried its own `left`/`top`/`width`/`transform`, and
// `updateOverlayPositions()` rewrote all ~200 of them on every wheel tick and
// every pan delta.
//
// After: `#graph-layer` carries `translate(pan) scale(zoom)`, overlays are laid
// out once in graph units, and pan/zoom is a single style write.
//
// The test pins both halves of that claim:
//   1. Correctness — an overlay's on-screen box still coincides with the box
//      Cytoscape reports for its node (`renderedPosition`/`renderedWidth`),
//      at the initial zoom and after zooming.
//   2. The O(1) property — zooming must NOT touch any overlay's own styles.
//
// Read-only — no DB writes, no seeding, no cleanup.
//
// Run:  node edit-overlay-layer.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';
// Cytoscape rounds rendered geometry; allow a sub-pixel slack.
const TOL = 1.5;

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-overlay-layer — one transformed layer, graph-coord overlays');

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0 && !cy.animated()
            && !!document.getElementById('graph-layer'),
      null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — structure: the layer exists and owns the overlays.
    // ===================================================================
    const structure = await page.evaluate(() => {
      const layer = document.getElementById('graph-layer');
      const nodeOverlays = document.querySelectorAll('.node-overlay');
      const inLayer = Array.from(nodeOverlays).filter((el) => layer.contains(el));
      return {
        parentIsCy: layer.parentElement?.id === 'cy',
        total: nodeOverlays.length,
        inLayer: inLayer.length,
        // Zero-sized + pointer-transparent, so it never eats clicks itself.
        pointerEvents: getComputedStyle(layer).pointerEvents,
        transformOrigin: getComputedStyle(layer).transformOrigin,
      };
    });
    assert(structure.parentIsCy, '#graph-layer is a child of #cy');
    assert(structure.total > 0, 'node overlays rendered: ' + structure.total);
    assert(structure.inLayer === structure.total,
           'every node overlay lives inside the layer: '
           + structure.inLayer + '/' + structure.total);
    assert(structure.pointerEvents === 'none', 'layer is pointer-transparent');
    assert(structure.transformOrigin.startsWith('0px 0px'),
           'layer transform-origin is the top-left: ' + structure.transformOrigin);

    // ===================================================================
    // Phase B — the layer transform reproduces Cytoscape's projection.
    // An overlay's screen box must coincide with the box cytoscape renders
    // for its node. Checked at the initial zoom, then again after zooming.
    // ===================================================================
    const probe = () => page.evaluate(() => {
      const node = cy.nodes('[type="fn"][!isPlaceholder]').first();
      const overlay = getNodeOverlay(node.id());
      const contRect = document.getElementById('cy').getBoundingClientRect();
      const ovRect = overlay.getBoundingClientRect();
      const rp = node.renderedPosition();
      return {
        zoom: cy.zoom(),
        // Cytoscape's own idea of where the node is drawn, container-relative.
        expected: {left: rp.x - node.renderedWidth() / 2,
                   top: rp.y - node.renderedHeight() / 2},
        // Where the overlay actually landed, container-relative.
        actual: {left: ovRect.left - contRect.left,
                 top: ovRect.top - contRect.top},
        // Graph-coordinate layout, which must survive zooming untouched.
        styleLeft: overlay.style.left,
        styleTop: overlay.style.top,
        styleWidth: overlay.style.width,
        styleTransform: overlay.style.transform,
        layerTransform: document.getElementById('graph-layer').style.transform,
      };
    });

    const before = await probe();
    assert(Math.abs(before.actual.left - before.expected.left) < TOL
           && Math.abs(before.actual.top - before.expected.top) < TOL,
           'overlay box matches cytoscape\'s rendered node box at zoom '
           + before.zoom.toFixed(3));
    assert(before.styleTransform === '',
           'overlay carries no per-node transform of its own');
    assert(/^translate\(.+\) scale\(.+\)$/.test(before.layerTransform),
           'layer carries translate+scale: ' + before.layerTransform);

    // Zoom about the container centre. This fires cy's `zoom` event, which is
    // the path that used to rewrite every overlay.
    await page.evaluate(() => {
      const c = document.getElementById('cy');
      cy.zoom({level: cy.zoom() * 1.7,
               renderedPosition: {x: c.clientWidth / 2, y: c.clientHeight / 2}});
    });
    const after = await probe();

    assert(after.zoom > before.zoom * 1.5, 'zoom actually changed: '
           + before.zoom.toFixed(3) + ' → ' + after.zoom.toFixed(3));
    assert(Math.abs(after.actual.left - after.expected.left) < TOL
           && Math.abs(after.actual.top - after.expected.top) < TOL,
           'overlay box still matches cytoscape\'s rendered node box after zoom');

    // ===================================================================
    // Phase C — the O(1) property. Zooming rewrote the layer's transform and
    // NOTHING on the overlay itself.
    // ===================================================================
    assert(after.styleLeft === before.styleLeft
           && after.styleTop === before.styleTop
           && after.styleWidth === before.styleWidth,
           'zoom left the overlay\'s graph-coordinate styles untouched ('
           + before.styleLeft + ' / ' + before.styleTop + ')');
    assert(after.layerTransform !== before.layerTransform,
           'zoom rewrote the layer transform instead');

    // ===================================================================
    // Phase D — edge labels ride the same layer, and still sit after the bend.
    // ===================================================================
    const edgeLabels = await page.evaluate(() => {
      const layer = document.getElementById('graph-layer');
      const overlays = Array.from(document.querySelectorAll('.edge-label-overlay'));
      if (overlays.length === 0) return null;
      const strays = overlays.filter((el) => !layer.contains(el));
      let afterBend = 0;
      for (const [edgeId, overlay] of _edgeOverlaysByEdgeId) {
        const edge = cy.getElementById(edgeId);
        if (!edge.length || !edge.source().length) continue;
        if (parseFloat(overlay.style.left) >= taxiBendX(edge.source())) afterBend++;
      }
      return {count: overlays.length, strays: strays.length, afterBend,
              pointerEvents: getComputedStyle(overlays[0]).pointerEvents};
    });
    assert(edgeLabels, 'edge-label overlays present: ' + edgeLabels?.count);
    assert(edgeLabels.strays === 0, 'no edge label escaped the layer');
    assert(edgeLabels.pointerEvents === 'auto',
           'edge labels opt back into pointer events under the transparent layer');
    assert(edgeLabels.afterBend === edgeLabels.count,
           'every edge label anchors at or after its bend: '
           + edgeLabels.afterBend + '/' + edgeLabels.count);

    console.log('  PASS');
  } finally {
    await browser.close();
  }
})();
