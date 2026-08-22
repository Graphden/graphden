// Regression: a `:sequence`-typed slot bound to a fn-ref must surface
// as an EDGE plus a downstream fn-card. The bug made the bound fn
// invisible from the caller — in production, `:web-server`'s router
// chain ends at `:_router/:routes :all` and the `:all` card simply
// wasn't there unless you navigated to it directly. The binding was in
// storage the whole time; the layout BFS dropped the ref.
//
// This test used to be `regression-sequence-fn-ref.test.js` and drove
// `:ex-regression-str-via-ref` from the dev-only `examples` package.
// That package is not in `graphden-executor`, so the file could not run
// in the e2e stack, was excluded from run-edit-tests.sh's glob, and
// therefore ran NOWHERE — a regression test guarding nothing. It now
// builds the same shape through the API, which is both runnable here
// and closer to what a user does.
//
//   test-regr-seq-list   :parent list, three literal items
//   test-regr-seq-str    :parent str,  :parts (a :sequence slot) → ref
//
// Run from this directory:  node edit-regression-sequence-fn-ref.test.js
// Exit code 0 = PASS, 1 = FAIL.

const { chromium } = require('playwright');
const { assert, newContext, api, getEntities, deleteFnByName } =
  require('./edit-test-helpers');

const LIST_FN = 'test-regr-seq-list';
const STR_FN = 'test-regr-seq-str';
const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

(async () => {
  const { browser, page } = await newContext(chromium);
  console.log('regression — :sequence slot bound to a fn-ref must produce an edge');
  try {
    // The str fn refs the list fn, so it has to go first.
    await deleteFnByName(page, STR_FN);
    await deleteFnByName(page, LIST_FN);

    const listEnts = await getEntities(page, 'list');
    const listBase = listEnts.fns.find((f) => f.name === 'list');
    const strEnts = await getEntities(page, 'str');
    const strBase = strEnts.fns.find((f) => f.name === 'str');
    assert(listBase && strBase, ':list and :str base fns resolved');

    // `parts` is :sequence-typed — the slot whose ref binding regressed.
    const partsSlot = (() => {
      const slots = new Map((strEnts.slots || []).map((s) => [s.id, s]));
      const fs = (strEnts['fn-slots'] || []).find(
        (x) => x['fn-id'] === strBase.id && slots.get(x['slot-id'])?.name === 'parts');
      return fs && fs['slot-id'];
    })();
    assert(partsSlot, ':str `parts` slot resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + LIST_FN + '&parent-ids=' + listBase.id);
    const listFn = (await getEntities(page, LIST_FN)).fns.find((f) => f.name === LIST_FN);
    assert(listFn, LIST_FN + ' created');

    for (const v of ['a', 'b', 'c']) {
      const r = await api(page, 'POST', '/api/sequence/append/' + listFn.id, { value: v });
      assert(r && r['item-id'], 'item "' + v + '" appended: ' + JSON.stringify(r));
    }

    await api(page, 'POST', '/api/entities/fn',
              'name=' + STR_FN + '&parent-ids=' + strBase.id);
    const strFn = (await getEntities(page, STR_FN)).fns.find((f) => f.name === STR_FN);
    assert(strFn, STR_FN + ' created');
    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + strFn.id + '&slot-id=' + partsSlot + '&ref-fn-id=' + listFn.id);

    // The binding must actually be in storage — otherwise a missing edge
    // below would be a write failure wearing a layout bug's clothes.
    const stored = await getEntities(page, STR_FN);
    const refBinding = (stored.bindings || []).find(
      (b) => b['fn-id'] === strFn.id && b['ref-fn-id'] === listFn.id);
    assert(refBinding, 'the sequence-slot ref binding is in storage');

    await page.goto('about:blank');
    await page.goto(BASE + '/#' + STR_FN);
    await page.waitForFunction(
      () => graphReady() && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null, { timeout: 20000, polling: 100 });
    // The ref's subtree loads async AFTER the root card's graphReady
    // gate, so a single read races it. Poll for the shape instead.
    await page.waitForFunction(
      () => graphReady()
            && graphView.nodeList().filter((n) => n.data.originalFnId).length >= 2
            && graphView.edgeList().some((e) => e.data.argName === 'parts'),
      null, { timeout: 15000, polling: 100 });

    const snapshot = await page.evaluate(() => ({
      fnNodes: graphView.nodeList().filter((n) => n.data.originalFnId)
                 .map((n) => ({ label: (n.data.label || '').trim(), orig: n.data.originalFnId })),
      edges: graphView.edgeList().map((e) => ({
        arg: e.data.argName, source: e.data.source, target: e.data.target })),
    }));

    const partsEdges = snapshot.edges.filter((e) => e.arg === 'parts');
    assert(partsEdges.length === 1,
           'exactly one `parts` edge leaves the root card, got '
           + JSON.stringify(snapshot.edges.map((e) => e.arg)));

    const rootNode = snapshot.fnNodes.find((n) => n.orig === strFn.id);
    const listNode = snapshot.fnNodes.find((n) => n.orig === listFn.id);
    assert(rootNode, 'the root card is present');
    assert(listNode,
           'the bound list renders its OWN card (this is the regression), cards: '
           + JSON.stringify(snapshot.fnNodes.map((n) => n.label)));

    const edge = partsEdges[0];
    const nodeIdOf = (orig) => {
      const all = snapshot.fnNodes;
      return all.find((n) => n.orig === orig);
    };
    assert(nodeIdOf(listFn.id), 'the edge target resolves to the list card');
    assert(edge.source.startsWith('fn-' + strFn.id),
           'the `parts` edge starts at the root card, got ' + edge.source);

    console.log('  ✓ parts edge present, ' + snapshot.fnNodes.length + ' fn-cards: '
                + JSON.stringify(snapshot.fnNodes.map((n) => n.label)));
    console.log('regression-sequence-fn-ref — PASS');
  } catch (e) {
    console.error('regression-sequence-fn-ref — FAIL:', e.message);
    process.exitCode = 1;
  } finally {
    try {
      await deleteFnByName(page, STR_FN);
      await deleteFnByName(page, LIST_FN);
    } catch (_) { /* cleanup best-effort; the leak check reports it */ }
    await browser.close();
  }
})();
