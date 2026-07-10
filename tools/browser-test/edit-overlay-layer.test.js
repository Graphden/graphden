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
    // Phase B — the layer transform really does project graph coordinates.
    //
    // Cross-check two things drawn by different machinery: the HTML overlay of
    // a source node, and the start of the SVG edge that leaves it. The path
    // starts exactly on the node's right border, so the path's start point —
    // mapped to the screen by the browser's own `getScreenCTM`, not by our
    // arithmetic — must land on the overlay's right edge, at its vertical
    // centre. Checked at the initial zoom, then again after zooming.
    // ===================================================================
    const probe = () => page.evaluate(() => {
      const line = document.querySelector('#edge-lines path');
      const source = gv.edge(line.dataset.edgeId).source();
      const overlay = getNodeOverlay(source.id());

      // Path start, in screen coordinates, via the browser's transform stack.
      const p = line.getPointAtLength(0);
      const m = line.getScreenCTM();
      const start = {x: p.x * m.a + p.y * m.c + m.e, y: p.x * m.b + p.y * m.d + m.f};

      // The overlay's DECLARED box — `width` / `min-height` in graph units,
      // which is the footprint the edge is drawn against. Its rendered box can
      // be taller when the card's content overflows (effect chips, the
      // signed-out CTA), so `getBoundingClientRect().height` is the wrong ruler.
      const r = overlay.getBoundingClientRect();
      const declaredH = parseFloat(overlay.style.minHeight) * gv.zoom();
      return {
        zoom: gv.zoom(),
        edgeStart: start,
        overlayRightMid: {x: r.right, y: r.top + declaredH / 2},
        // Graph-coordinate layout, which must survive zooming untouched.
        styleLeft: overlay.style.left,
        styleTop: overlay.style.top,
        styleWidth: overlay.style.width,
        styleTransform: overlay.style.transform,
        layerTransform: document.getElementById('graph-layer').style.transform,
      };
    });

    const agree = (p) => Math.abs(p.edgeStart.x - p.overlayRightMid.x) < TOL
                      && Math.abs(p.edgeStart.y - p.overlayRightMid.y) < TOL;

    const before = await probe();
    assert(agree(before),
           'the SVG edge starts on the HTML overlay\'s right edge at zoom '
           + before.zoom.toFixed(3));
    assert(before.styleTransform === '',
           'overlay carries no per-node transform of its own');
    assert(/^translate\(.+\) scale\(.+\)$/.test(before.layerTransform),
           'layer carries translate+scale: ' + before.layerTransform);

    // Zoom about the container centre. This is the path that used to rewrite
    // every overlay's styles; now it rewrites one transform.
    await page.evaluate(() => {
      const c = document.getElementById('cy');
      gv.setZoom(gv.zoom() * 1.7, {x: c.clientWidth / 2, y: c.clientHeight / 2});
    });
    const after = await probe();

    assert(after.zoom > before.zoom * 1.5, 'zoom actually changed: '
           + before.zoom.toFixed(3) + ' → ' + after.zoom.toFixed(3));
    assert(agree(after),
           'the SVG edge still starts on the overlay\'s right edge after zoom');

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

    // ===================================================================
    // Phase E — a card's declared box is its real box.
    //
    // `calculateNodeSize` predicts card height by mirroring the overlay
    // renderer, and the mirror cannot keep up: the effects strip wraps to as
    // many chip rows as the width allows, and it had already forgotten the
    // `branch-local` strip. `web-server` came out 90 graph units against a real
    // 167 — enough to push the card 77 units into the row below, where the grid
    // leaves 40, and to anchor its edges 38 units above its visual centre.
    // `reflowFromMeasuredHeights` re-lays the rows against measured heights.
    // ===================================================================
    const boxes = await page.evaluate(() => {
      const cards = [];
      for (const [nodeId, overlay] of _overlaysByNodeId) {
        const node = gv.node(nodeId);
        if (!node || node.data('isPlaceholder')) continue;
        cards.push({
          declared: parseFloat(overlay.style.minHeight) || 0,
          rendered: overlay.offsetHeight,
          top: node.position().y - node.height() / 2,
          col: node.data('colRightX'),
        });
      }
      // Two cards in the same column must not overlap vertically.
      let overlaps = 0;
      for (let i = 0; i < cards.length; i++) {
        for (let j = i + 1; j < cards.length; j++) {
          const a = cards[i];
          const b = cards[j];
          if (a.col !== b.col || a.col === undefined) continue;
          if (a.top < b.top + b.rendered && b.top < a.top + a.rendered) overlaps++;
        }
      }
      const worst = Math.max(...cards.map((c) => Math.abs(c.rendered - c.declared)));
      return {count: cards.length, worst, overlaps};
    });
    assert(boxes.count > 0, 'measured ' + boxes.count + ' cards');
    assert(boxes.worst <= 1,
           'every card\'s declared height matches what it renders (worst gap: '
           + boxes.worst + ' graph units)');
    assert(boxes.overlaps === 0, 'no two cards in a column overlap');

    console.log('  PASS');
  } finally {
    await browser.close();
  }
})();
