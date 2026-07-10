// Edges are SVG paths in `#edge-layer`, inside the transformed graph layer.
//
// What this pins:
//   1. The layer renders at all. A zero width/height on a root `<svg>` disables
//      rendering of the element per spec — the paths keep their geometry, and
//      `getBoundingClientRect` still reports the right box, but nothing is ever
//      painted. That failure is invisible to every geometry assertion, so check
//      the element is actually painted.
//   2. Path == hit-zone. The visible path and the fat transparent one carry the
//      same `d`, and it starts/ends on the node borders with the bend at
//      `taxiBendX`.
//   3. Stroke widths track zoom on the GROUPS, not per path: the line never
//      renders thinner than 0.75 screen px, and the grab target is a constant
//      12 screen px.
//   4. The model holds one edge per drawn path.
//
// Read-only — no DB writes, no seeding, no cleanup.
//
// Run:  node edit-edges-svg.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-edges-svg — SVG edge layer, paths and hit-zones');

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady() && !graph.animating
            && document.querySelector('#edge-lines path'),
      null, {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A — structure, and the paths are genuinely painted.
    // ===================================================================
    const structure = await page.evaluate(() => {
      const svg = document.getElementById('edge-layer');
      const line = svg.querySelector('#edge-lines path');
      const cs = getComputedStyle(svg);
      return {
        parentIsGraphLayer: svg.parentElement?.id === 'graph-layer',
        // Edges must paint UNDER the cards.
        paintsFirst: svg.parentElement.firstElementChild === svg,
        lines: svg.querySelectorAll('#edge-lines path').length,
        hits: svg.querySelectorAll('#edge-hits path').length,
        // The zero-size trap: a root <svg> with width or height 0 renders nothing.
        widthPx: parseFloat(cs.width),
        heightPx: parseFloat(cs.height),
        overflow: cs.overflow,
        modelEdgeCount: graphView.edgeList().length,
        markerStart: line.getAttribute('marker-start'),
        markerEnd: line.getAttribute('marker-end'),
      };
    });
    assert(structure.parentIsGraphLayer, '#edge-layer sits inside #graph-layer');
    assert(structure.paintsFirst, 'edge layer paints before (under) the overlays');
    assert(structure.lines > 0 && structure.lines === structure.hits,
           'one hit path per visible path: ' + structure.lines + '/' + structure.hits);
    assert(structure.widthPx > 0 && structure.heightPx > 0,
           'root <svg> is non-zero sized, or nothing would be painted: '
           + structure.widthPx + 'x' + structure.heightPx);
    assert(structure.overflow === 'visible', 'paths may spill outside the 1px box');
    assert(structure.markerStart && structure.markerEnd,
           'edge carries both direction markers');
    assert(structure.modelEdgeCount === structure.lines,
           'the model holds one edge per drawn path: ' + structure.modelEdgeCount);

    // Actually painted. Every geometry assertion above still passes when the
    // root <svg> is zero-sized and paints nothing, so the only honest check is
    // pixels: shoot the page, hide the edge layer, shoot again. Identical bytes
    // mean no edge was ever on screen.
    //
    // The whole viewport, not a strip over the path: the long horizontal run of
    // an edge is largely covered by its own (opaque) label overlay, so a small
    // clip can legitimately show no edge at all.
    const pathWidth = await page.evaluate(
      () => document.querySelector('#edge-lines path').getBoundingClientRect().width);
    assert(pathWidth > 1, 'edge path spans real screen width: ' + Math.round(pathWidth));

    const withEdges = await page.screenshot();
    await page.evaluate(() => {
      document.getElementById('edge-layer').style.visibility = 'hidden';
    });
    const withoutEdges = await page.screenshot();
    await page.evaluate(() => {
      document.getElementById('edge-layer').style.visibility = '';
    });
    assert(!withEdges.equals(withoutEdges),
           'the edge layer actually paints — hiding it changes the page');

    // ...and the guard is not vacuous. Re-introduce the trap — a zero height on
    // the root <svg> — and the page must render exactly as if the layer were
    // hidden, even though every path keeps its geometry.
    const zeroSized = await page.evaluate(async () => {
      const svg = document.getElementById('edge-layer');
      svg.style.height = '0';
      const d = document.querySelector('#edge-lines path').getBoundingClientRect();
      return {width: d.width};  // geometry survives; only painting stops
    });
    const withZeroSize = await page.screenshot();
    await page.evaluate(() => {
      document.getElementById('edge-layer').style.height = '';
    });
    assert(zeroSized.width > 1,
           'a zero-sized layer keeps its path geometry: ' + Math.round(zeroSized.width));
    assert(withZeroSize.equals(withoutEdges),
           'a zero-sized root <svg> paints nothing — this is the trap the size guards');

    const hitReachable = await page.evaluate(() => {
      const r = document.querySelector('#edge-lines path').getBoundingClientRect();
      return document.elementsFromPoint(r.x + r.width / 2, r.y + r.height / 2)
                     .some((el) => el.classList?.contains('edge-hit'));
    });
    assert(hitReachable, 'the hit path is reachable at the midpoint of the drawn edge');

    // ===================================================================
    // Phase B — the drawn path IS the hit path, and it bends at taxiBendX.
    // ===================================================================
    const geometry = await page.evaluate(() => {
      const line = document.querySelector('#edge-lines path');
      const id = line.dataset.edgeId;
      const hit = document.querySelector('#edge-hits path[data-edge-id="' + id + '"]');
      const edge = gv.edge(id);
      const source = edge.source();
      const target = edge.target();
      const d = line.getAttribute('d');
      // "M<sx>,<sy>H<bend>V<ty>H<tx>"
      const nums = d.match(/-?\d+(\.\d+)?/g).map(Number);
      return {
        sameD: d === hit.getAttribute('d'),
        startX: nums[0],
        bendX: nums[2],
        endX: nums[4],
        expectedStartX: source.position().x + source.width() / 2,
        expectedBendX: taxiBendX(source),
        expectedEndX: target.position().x - target.width() / 2,
      };
    });
    assert(geometry.sameD, 'the hit path carries exactly the drawn geometry');
    assert(Math.abs(geometry.startX - geometry.expectedStartX) < 0.01,
           'path leaves the source node border');
    assert(Math.abs(geometry.bendX - geometry.expectedBendX) < 0.01,
           'path bends at the shared taxiBendX');
    assert(Math.abs(geometry.endX - geometry.expectedEndX) < 0.01,
           'path arrives at the target node border');

    // ===================================================================
    // Phase C — stroke widths ride the groups and track zoom.
    // ===================================================================
    const widths = await page.evaluate(() => {
      const read = () => ({
        zoom: gv.zoom(),
        line: parseFloat(document.getElementById('edge-lines').getAttribute('stroke-width')),
        hit: parseFloat(document.getElementById('edge-hits').getAttribute('stroke-width')),
        perPath: document.querySelector('#edge-lines path').getAttribute('stroke-width'),
      });
      navZoomTo(0.2);
      const low = read();
      navZoomTo(2.0);
      const high = read();
      return {low, high};
    });
    assert(widths.low.perPath === null,
           'stroke width lives on the group, not on each path');
    // On-screen width = graphUnits * zoom.
    assert(Math.abs(widths.low.line * widths.low.zoom - 0.75) < 0.01,
           'zoomed far out, the line holds its 0.75 px floor');
    assert(Math.abs(widths.high.line - 2) < 0.01,
           'zoomed in, the line is its natural 2 graph units');
    assert(Math.abs(widths.low.hit * widths.low.zoom - 12) < 0.01
           && Math.abs(widths.high.hit * widths.high.zoom - 12) < 0.01,
           'the grab target is a constant 12 screen px at any zoom');

    // ===================================================================
    // Phase D — hover lights the bundle, and clears.
    // ===================================================================
    const hover = await page.evaluate(() => {
      // `gv.nodes()` takes no selector — use the dedicated fn-node filter so
      // this doesn't silently grab whatever node is first in insertion order.
      const rootId = gv.fnNodes()[0].id();
      gv.highlightEdgesFrom(rootId);
      const lit = document.querySelectorAll('#edge-lines path.edge-hovered').length;
      gv.clearEdgeHighlight();
      const cleared = document.querySelectorAll('#edge-lines path.edge-hovered').length;
      return {lit, cleared, total: document.querySelectorAll('#edge-lines path').length};
    });
    assert(hover.lit === hover.total,
           'hovering the root fn lights its whole outgoing bundle: '
           + hover.lit + '/' + hover.total);
    assert(hover.cleared === 0, 'and the highlight clears');

    assert(pageErrors.length === 0, 'no page errors: ' + JSON.stringify(pageErrors));
    console.log('  PASS');
  } finally {
    await browser.close();
  }
})();
