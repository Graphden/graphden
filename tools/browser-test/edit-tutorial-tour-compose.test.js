// Lessons 10, 11, 13 — components, the escape hatch, recursion
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-compose.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, settleTourRing, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  bindNamedPlaceholder,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourWhere,
  bindOptionalArgChip, appendFnRefViaChip, createRecordType,
  clickTourAdvance, waitTourClosed, appendSeqItemViaEdge, bindSeqAnchorPlaceholder,
  deleteBoundValue, setLambdaParamsViaInspector,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  // Lesson 08's "remove this binding" step fires a native confirm().
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-compose — lessons 10 / 11 / 13');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Lesson 10 — components (free-arg chips + list append) ------
    await page.goto(BASE + '/?tutorial=10');
    await waitTourTitle(page, 'A page is a function', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 10 Next');
    await waitTourTitle(page, 'Find button');
    await filterAndSelect(page, 'button', 'button');
    await waitTourTitle(page, 'Make it yours', 150000);
    await extendViaRowActions(page, 'tutorial-button', 'button');
    // Selection gate — the chip must be the CHILD's.
    // Selection-gate steps carry a `selected` check — they advance on their
    // own once the child is open; there is no Next to click.
    await waitTourTitle(page, 'tutorial-button is open', 150000);
    await waitTourTitle(page, 'Give it a label', 150000);
    // Unified-arg-edges: a component's propagated inputs render as the
    // SAME placeholder edges any argument gets — `label` must be there,
    // as a binder on a placeholder edge. That IS the lesson's claim now.
    const labelEdge = await page.evaluate(() => {
      const e = window.graphView.edgeList().find(
        (x) => x.data?.argName === 'label' && x.data?.isUnset);
      return e ? {target: e.data.target,
                  binder: !!document.querySelector(
                    '.placeholder-binder[data-node-id="' + e.data.target + '"]')}
               : null;
    });
    assert(labelEdge && labelEdge.binder,
      'the propagated label input is a bindable placeholder edge');
    await bindOptionalArgChip(page, 'label', 'Run');
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'A real button', 150000);
    // The preview is a real document with the components stylesheet: the
    // button inside the frame must carry the sheet's padding, not the
    // browser default — that is what the reader is told to look at.
    await page.waitForSelector('iframe.execute-result-component-preview', {timeout: 60000});
    const previewed = await page.evaluate(() => {
      const f = document.querySelector('iframe.execute-result-component-preview');
      return {link: /rel="stylesheet"/.test(f.getAttribute('srcdoc') || ''),
              button: /<button[^>]*>Run<\/button>/.test(f.getAttribute('srcdoc') || '')};
    });
    assert(previewed.link && previewed.button,
      'the preview frame is a styled document holding the button (got: ' + JSON.stringify(previewed) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 10 button Next');
    await waitTourTitle(page, 'Style it', 150000);
    await bindOptionalArgChip(page, 'attrs', '{"class": "primary"}');
    await waitTourTitle(page, 'Run it again', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Now something to put it in', 150000);
    await filterAndSelect(page, 'card', 'card');
    await waitTourTitle(page, 'Extend the card too', 150000);
    await extendViaRowActions(page, 'tutorial-card', 'card');
    await waitTourTitle(page, 'tutorial-card is open', 150000);
    await waitTourTitle(page, 'Put your button inside', 150000);
    await appendFnRefViaChip(page, 'children', 'tutorial-button');
    await waitTourTitle(page, 'Run the card', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Nested — and still your button', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 10 card Next');
    await waitTourTitle(page, "That's a page, in pieces", 150000);
    // The composition itself, asserted over the API — the card's hiccup
    // must nest the button's.
    const cardFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-card');
    const cardFn = (cardFound.fns || []).find((f) => f.name === 'tutorial-card');
    assert(cardFn, 'tutorial-card exists');
    const ran = await api(page, 'POST', '/api/execute',
      {'fn-id': cardFn.id, args: {}});
    assert(JSON.stringify(ran.result) === '["div",{"class":"card"},["button",{"class":"primary"},"Run"]]',
      'card renders with the button nested inside (got: '
      + JSON.stringify(ran.result) + ')');
    await finishAndDelete(page);
    console.log('  lesson 10: walked + cleaned');

    // ---------- Lesson 11 — the escape hatch (code editor + rename) --------
    await page.goto(BASE + '/?tutorial=11');
    await waitTourTitle(page, 'When no component fits', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 11 Next');
    await waitTourTitle(page, 'Find wrap-custom-script');
    await filterAndSelect(page, 'custom-script', 'wrap-custom-script');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-script', 'wrap-custom-script');
    await waitTourTitle(page, 'tutorial-script is open', 150000);
    await waitTourTitle(page, 'Write some JavaScript', 150000);
    await bindOptionalArgChip(page, 'body', "document.title = 'Graphden';",
                              {code: true});
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Source, not a preview', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 11 look-step Next');
    await waitTourTitle(page, 'Know what you gave up', 150000);
    // `?body` is a RENAME of the inherited `:content` slot, and a binding
    // must land on the declared slot — written on the rename view it shows
    // on the card and is invisible at run time. Assert the value actually
    // arrives.
    const scriptFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-script');
    const scriptFn = (scriptFound.fns || [])
      .find((f) => f.name === 'tutorial-script');
    assert(scriptFn, 'tutorial-script exists');
    const scriptRan = await api(page, 'POST', '/api/execute',
      {'fn-id': scriptFn.id, args: {}});
    assert(JSON.stringify(scriptRan.result)
             === '["script",{},"document.title = \'Graphden\';"]',
      'the JS reached the rendered tag (got: '
      + JSON.stringify(scriptRan.result) + ')');
    await finishAndDelete(page);
    console.log('  lesson 11: walked + cleaned');

    // ---------- Lesson 13 — recursion (a READING tour) ----------
    // The only lesson that asks the reader to READ a fn rather than build
    // one: `:fix` needs ~7 fn-defs, which is a written lesson, not twenty
    // steps of clicking. What the tour must prove is that the fn it points
    // at is really there and really recursive — a renamed step or a
    // re-parented `:branch-chain` would leave the lesson describing a graph
    // that no longer exists.
    await page.goto(BASE + '/?tutorial=13');
    await waitTourTitle(page, 'Loops, where cycles are forbidden', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 13 opening Next');
    await waitTourTitle(page, 'Find a real one', 30000);
    await filterAndSelect(page, 'branch-chain', 'branch-chain');
    await waitTourTitle(page, 'Its parent is :fix', 150000);

    const shape = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=branch-chain');
    const chain = (shape.fns || []).find((f) => f.name === 'branch-chain');
    assert(chain, 'the lesson\'s example fn exists');
    const sub = await api(page, 'GET',
      '/api/graph/entities?scope=subtree&root-id=' + chain.id);
    const byId = new Map((sub.fns || []).map((f) => [f.id, f]));
    const parents = (chain['parent-ids'] || []).map((id) => byId.get(id)?.name);
    assert(parents.includes('fix'),
      'branch-chain still inherits :fix (parents: ' + parents.join(', ') + ')');
    const names = (sub.fns || []).map((f) => f.name);
    assert(names.includes('_branch-chain-step'),
      'its step fn is still in the closure');
    assert(names.includes('_branch-chain-recurse'),
      'and so is the arm that invokes :self — the recursion the lesson reads');

    for (let i = 0; i < 5; i++) {
      assert(await clickTourAdvance(page, 'Next'), 'lesson 13 Next #' + (i + 1));
    }
    await waitTourTitle(page, "That's recursion", 30000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 13 Finish');
    await waitTourClosed(page, 30000);
    console.log('  lesson 13: walked (reading tour — :fix, its step, its :self arm)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
