// Regression: a :sequence-typed slot bound to a fn-ref must surface
// as an edge + downstream fn-card in the editor's layout. Pins down
// the bug where `:web-server`'s `:_router/:routes :all` binding
// became invisible from the web-server view.
//
// The test fn-def is `:ex-regression-str-via-ref` —
// `:str/:parts` is :sequence-typed and bound to `:_ex-regression-list`
// (a list of three literals).
//
// Expected layout (after the fix lands):
//   - ex-regression-str-via-ref card     (root)
//   - _ex-regression-list card           (the bound list)
//   - 3 arg-overlay nodes (the items "a"/"b"/"c")
//   - edges:
//       parts:    root → list             (the regressed edge)
//       items[0]: list → "a"
//       items[1]: list → "b"
//       items[2]: list → "c"
//
// Run from this directory:  node regression-sequence-fn-ref.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({
    args: ['--no-sandbox', '--no-zygote', '--in-process-gpu']});
  const page = await browser.newPage();
  console.log('regression-sequence-fn-ref — :sequence slot bound to fn-ref must produce an edge');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#ex-regression-str-via-ref');
    await page.waitForTimeout(2500);

    const snapshot = await page.evaluate(() => {
      if (typeof cy === 'undefined') return {error: 'cy not initialised'};
      return {
        fnNodeCount: cy.nodes('[originalFnId]').length,
        fnNodeLabels: cy.nodes('[originalFnId]').map(n => (n.data('label') || '').trim()),
        edgeArgNames: cy.edges().map(e => e.data('argName')).filter(Boolean),
      };
    });

    if (snapshot.error) {
      throw new Error(snapshot.error);
    }

    // The root card MUST have an outgoing `parts` edge to the bound list.
    if (!snapshot.edgeArgNames.includes('parts')) {
      throw new Error(
        'no `parts` edge — the :sequence-typed fn-ref to _ex-regression-list ' +
        'didn\'t surface as an edge. Edges seen: ' +
        JSON.stringify(snapshot.edgeArgNames));
    }

    // Two fn-cards expected: the root and the bound list.
    if (snapshot.fnNodeCount < 2) {
      throw new Error(
        'expected ≥ 2 fn-cards (root + _ex-regression-list); got ' +
        snapshot.fnNodeCount + ', labels: ' +
        JSON.stringify(snapshot.fnNodeLabels));
    }

    console.log('  ✓ parts edge present');
    console.log('  ✓ ' + snapshot.fnNodeCount + ' fn-cards rendered: ' +
                JSON.stringify(snapshot.fnNodeLabels));
    console.log('regression-sequence-fn-ref — PASS');
  } catch (e) {
    console.error('regression-sequence-fn-ref — FAIL:', e.message);
    await browser.close();
    process.exit(1);
  }
  await browser.close();
})();
