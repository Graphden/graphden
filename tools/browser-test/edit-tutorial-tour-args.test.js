// Lessons 05, 06, 07 — free arguments, lists, optional / required / sealed
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-args.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  NS_NAME, FN_NAME, hardCleanup, waitTourTitle, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  renameArgViaEdgeLabel,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions, runFromOpenPane,
  appendSeqItemViaEdge,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, tourWhere, waitTourClosed,
  bindPlaceholderOn, setSealsViaBadge, settleTourRing,
  moveSeqItem, insertSeqLiteralBefore, addMiParentViaParentRow,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  // Lesson 08's "remove this binding" step fires a native confirm(); with no
  // handler Playwright auto-dismisses it and the step would never complete.
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-args — lessons 05 / 06 / 07');
  let failed = false;
  try {
    await hardCleanup(page); // a previous failed run must not pre-pass checks

    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- Lesson 05 — free arguments ----------
    await page.goto(BASE + '/?tutorial=05');
    await waitTourTitle(page, 'Free args: the template mechanism', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 05 Next');
    await waitTourTitle(page, 'Find to-json-string');
    await filterAndSelect(page, 'to-json', 'to-json-string');
    await waitTourTitle(page, 'A free arg becomes a Run field');
    await runViaRowActions(page, '{"a": 1}');
    await waitTourTitle(page, 'A string came back', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 05 look-step Next');
    await waitTourTitle(page, 'Pin it in a child', 150000);
    await extendViaRowActions(page, 'tutorial-json', 'to-json-string');
    // Selection gate again — "Bind :data in the child" only appears once
    // tutorial-json is the selected fn, so the "+" is the child's.
    await waitTourTitle(page, 'Bind :data in the child', 150000);
    await bindFirstPlaceholder(page, '{"greeting": "hello"}');
    await waitTourTitle(page, 'Bound beats free', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 05 step-5 Next');
    // --- the rename arc ---
    await waitTourTitle(page, 'A free arg can also be RENAMED', 150000);
    await filterAndSelect(page, 'to-json', 'to-json-string');
    await waitTourTitle(page, 'A second child', 150000);
    await extendViaRowActions(page, 'tutorial-renamed', 'to-json-string');
    await waitTourTitle(page, 'tutorial-renamed is open', 150000);
    await waitTourTitle(page, 'Rename it', 150000);
    await renameArgViaEdgeLabel(page, 'data', 'payload');
    await waitTourTitle(page, 'The new name is the interface', 150000);
    // The result pane still holds the run from earlier in this lesson, so
    // this step is `manual` — a dom check on it would pass before the user
    // ran anything. Run the way a reader does: the Runs pane is ALREADY
    // open from the earlier run, so type into it — its field must be the
    // renamed `payload`, or the server rejects the run ("Unknown arg(s):
    // [:data]", the stale-form bug of 2026-09-14). Then the lesson's own
    // ⋯ → ▶ Run path, which rebuilds the pane.
    const renamedRun = await runFromOpenPane(page, '{"a": 1}', 'payload');
    assert(renamedRun.status === 'succeeded' && /"a":\s*1/.test(String(renamedRun.result)),
           'run from the already-open pane uses the renamed arg: ' + JSON.stringify(renamedRun).slice(0, 160));
    await runViaRowActions(page, '{"a": 1}');
    assert(await clickTourButton(page, 'Next'), 'lesson 05 rename-run Next');
    await waitTourTitle(page, 'Templates, specialized', 150000);
    // The rename must be a VIEW over the same slot, so the value has to
    // arrive under the NEW name — a binding written on the view slot
    // instead of the declared one would look right and run empty.
    const renamedFound = await api(page, 'GET',
      '/api/graph/entities?scope=search&q=tutorial-renamed');
    const renamedFn = (renamedFound.fns || [])
      .find((f) => f.name === 'tutorial-renamed');
    assert(renamedFn, 'tutorial-renamed exists');
    const renamedRan = await api(page, 'POST', '/api/execute',
      {'fn-id': renamedFn.id, args: {payload: {a: 1}}});
    assert(renamedRan.result === '{"a":1}',
      'the value arrives under the new name (got: '
      + JSON.stringify(renamedRan.result) + ')');
    await finishAndDelete(page);
    console.log('  lesson 05: walked + cleaned');

    // ---------- Lesson 06 — lists: seed, append, close ----------
    await page.goto(BASE + '/?tutorial=06');
    await waitTourTitle(page, 'Lists on the card', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 06 Next');
    await waitTourTitle(page, 'Find add');
    await filterAndSelect(page, 'add', 'add');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-base-sum', 'add');
    await waitTourTitle(page, 'Seed the list', 150000);
    await bindPlaceholderOn(page, 'tutorial-base-sum', 'nums', 'literal', '1');
    await waitTourTitle(page, 'One more', 150000);
    await bindPlaceholderOn(page, 'tutorial-base-sum', 'nums', 'literal', '2');
    // Order + insert on LITERAL items — the item's own ↑ / + (2026-09-20).
    await waitTourTitle(page, 'Swap them', 150000);
    await moveSeqItem(page, 1, 'up');
    await waitTourTitle(page, 'Insert before', 150000);
    await insertSeqLiteralBefore(page, 1, '0');
    await waitTourTitle(page, 'Now extend the seeded fn', 150000);
    const order = await page.evaluate(() => {
      const fn = Array.from(lookups.fnMap.values()).find((f) => f.name === 'tutorial-base-sum');
      const b = (lookups.bindingsByFn.get(fn.id) || [])[0];
      return (lookups.itemsByBinding.get(b?.id) || []).map((i) => String(i.value));
    });
    assert(JSON.stringify(order) === JSON.stringify(['2', '0', '1']),
      'the list reads 2, 0, 1 after ↑ on the 2 and + before the 1 (got: ' + JSON.stringify(order) + ')');
    await extendViaRowActions(page, 'tutorial-sum-more', 'tutorial-base-sum');
    await waitTourTitle(page, 'The list you inherited', 150000);
    // Unfold the parent's row on the child's card: the PARENT's items appear
    // (provenance-marked) followed by a tail of the child's own — the
    // inherited-list rendering the lesson is about.
    const parentRow = await page.waitForSelector(
      '.node-overlay[data-fn-name="tutorial-sum-more"] .ancestor-line[data-level="1"]', {timeout: 60000});
    await parentRow.click({position: {x: 40, y: 8}});
    await page.waitForSelector('.placeholder-binder.is-seq-anchor[data-fn-name="tutorial-sum-more"][data-arg-name="nums"]',
      {timeout: 60000});
    const inherited = await page.evaluate(() => ({
      items: [...graph.edges.values()].filter((e) => e.data?.seqGroup && !e.data?.isUnset).length,
      provenance: document.querySelectorAll('.arg-source-link, .provenance-badge').length,
      tailOwner: document.querySelector('.placeholder-binder.is-seq-anchor')?.dataset.fnName,
    }));
    assert(inherited.items === 3, 'the unfolded child shows the parent\'s three items (got: ' + JSON.stringify(inherited) + ')');
    assert(inherited.tailOwner === 'tutorial-sum-more', 'and the tail is the child\'s own');
    await waitTourTitle(page, 'Append here', 150000);
    await bindPlaceholderOn(page, 'tutorial-sum-more', 'nums', 'literal', '3');
    await waitTourTitle(page, 'Run it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'Six', 150000);
    const six = await page.evaluate(() =>
      (document.querySelector('.execute-result-host .execute-result-pane')?.textContent || '').trim());
    assert(/\b6\b/.test(six), 'the parent\'s seed plus the child\'s append run as one list: ' + six);
    // The append landed on the CHILD as a list-append binding — the parent
    // keeps its two items.
    const split = await api(page, 'GET', '/api/graph/entities?scope=search&q=tutorial-');
    const baseSum = (split.fns || []).find((f) => f.name === 'tutorial-base-sum');
    const sumMore = (split.fns || []).find((f) => f.name === 'tutorial-sum-more');
    const baseSub = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + baseSum.id);
    const moreSub = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + sumMore.id);
    const itemsOf = (sub, fnId) => (sub['list-items'] || []).filter((i) =>
      (sub.bindings || []).some((b) => b.id === i['binding-id'] && b['fn-id'] === fnId)).length;
    assert(itemsOf(baseSub, baseSum.id) === 3 && itemsOf(moreSub, sumMore.id) === 1,
      'three items on the parent, one on the child (got: ' + itemsOf(baseSub, baseSum.id) + ' / '
      + itemsOf(moreSub, sumMore.id) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 06 six Next');
    await waitTourTitle(page, 'Back to the parent', 150000);
    await filterAndSelect(page, 'tutorial-base-sum', 'tutorial-base-sum');
    await waitTourTitle(page, 'Close the list', 150000);
    await setSealsViaBadge(page, 'nums', {'list-closed': true}, 'tutorial-base-sum');
    await waitTourTitle(page, 'See it from the child', 150000);
    await filterAndSelect(page, 'tutorial-sum-more', 'tutorial-sum-more');
    await waitTourTitle(page, 'A lock where the tail was', 150000);
    await page.waitForSelector('.placeholder-sealed[data-arg-name="nums"] .seal-ghost', {timeout: 60000});
    // The step's target landed after its title — let the ring find it before
    // the walk moves on (the audit samples the ring, a reader would see it).
    await settleTourRing(page, 20000);
    const locked = await page.evaluate(() => ({
      tail: document.querySelectorAll('.placeholder-binder.is-seq-anchor').length,
      ghost: document.querySelector('.placeholder-sealed[data-arg-name="nums"] .seal-ghost')?.title || '',
      items: [...graph.edges.values()].filter((e) => e.data?.seqGroup && !e.data?.isUnset).length,
    }));
    assert(locked.tail === 0 && /List closed in tutorial-base-sum/.test(locked.ghost),
      'the child\'s tail is a lock naming the closer (got: ' + JSON.stringify(locked) + ')');
    assert(locked.items === 4, 'its four items stay (got: ' + locked.items + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 06 lock Next');
    await waitTourTitle(page, "That's a list", 150000);
    await finishAndDelete(page);
    console.log('  lesson 06: walked + cleaned (seed, reorder, insert, append from the child, close)');

    // ---------- Lesson 07 — optional, required and sealed ----------
    await page.goto(BASE + '/?tutorial=07');
    await waitTourTitle(page, 'Three decisions beside a value', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 07 Next');
    await waitTourTitle(page, 'Find subs');
    await filterAndSelect(page, 'subs', 'subs');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-cut', 'subs');
    await waitTourTitle(page, 'Optional, on the label', 150000);
    await page.waitForSelector('.edge-label-overlay[data-arg-name="end"] .seal-badge', {timeout: 60000});
    await settleTourRing(page, 20000);
    const optional = await page.evaluate(() => ({
      dimmed: document.querySelector('.placeholder-binder[data-fn-name="tutorial-cut"][data-arg-name="end"]')
        ?.classList.contains('is-optional'),
      badge: document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge')?.dataset.seal,
    }));
    assert(optional.dimmed === true && optional.badge === 'optional',
      'the optional slot is dimmed and its badge says so (got: ' + JSON.stringify(optional) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 07 optional Next');
    await waitTourTitle(page, 'Bind the string', 150000);
    await bindPlaceholderOn(page, 'tutorial-cut', 'string', 'literal', 'graphden');
    await waitTourTitle(page, 'And the start', 150000);
    await bindPlaceholderOn(page, 'tutorial-cut', 'start', 'literal', '5');
    await waitTourTitle(page, 'Run without it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'den', 150000);
    const den = await page.evaluate(() =>
      (document.querySelector('.execute-result-host .execute-result-pane')?.textContent || '').trim());
    assert(/den/.test(den), 'the optional :end defaulted to the string\'s end: ' + den);
    assert(await clickTourButton(page, 'Next'), 'lesson 07 den Next');
    await waitTourTitle(page, 'Seal it', 150000);
    await setSealsViaBadge(page, 'end', {terminal: true}, 'tutorial-cut');
    await waitTourTitle(page, 'Extend the sealed fn', 150000);
    await extendViaRowActions(page, 'tutorial-cut-more', 'tutorial-cut');
    await waitTourTitle(page, 'A lock where the + would be', 150000);
    await page.waitForSelector('.placeholder-sealed[data-arg-name="end"] .seal-ghost', {timeout: 60000});
    await settleTourRing(page, 20000);
    const sealedChild = await page.evaluate(() => ({
      ghost: document.querySelector('.placeholder-sealed[data-arg-name="end"] .seal-ghost')?.title || '',
      finalGone: !document.querySelector('.placeholder-binder[data-arg-name="string"]')
        && !document.querySelector('.placeholder-binder[data-arg-name="start"]'),
    }));
    assert(/Sealed in tutorial-cut/.test(sealedChild.ghost) && sealedChild.finalGone,
      'the child sees a lock on :end and no + for the final values (got: ' + JSON.stringify(sealedChild) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 07 lock Next');
    await waitTourTitle(page, 'Back to tutorial-cut', 150000);
    await filterAndSelect(page, 'tutorial-cut', 'tutorial-cut');
    await waitTourTitle(page, 'Require it instead', 150000);
    await setSealsViaBadge(page, 'end', {terminal: false, required: true}, 'tutorial-cut');
    await waitTourTitle(page, 'See it from the child', 150000);
    await filterAndSelect(page, 'tutorial-cut-more', 'tutorial-cut-more');
    await waitTourTitle(page, 'The + is back — and solid', 150000);
    await page.waitForSelector('.placeholder-binder[data-fn-name="tutorial-cut-more"][data-arg-name="end"]',
      {timeout: 60000});
    await settleTourRing(page, 20000);
    const ratchet = await page.evaluate(() => ({
      dimmed: document.querySelector('.placeholder-binder[data-fn-name="tutorial-cut-more"][data-arg-name="end"]')
        ?.classList.contains('is-optional'),
      ghost: !!document.querySelector('.placeholder-sealed[data-arg-name="end"]'),
      badge: document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge')?.dataset.seal,
    }));
    assert(ratchet.dimmed === false && !ratchet.ghost && ratchet.badge === 'required',
      'the + is back and solid — required since the parent (got: ' + JSON.stringify(ratchet) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 07 ratchet Next');
    await waitTourTitle(page, 'Optional, required, sealed', 150000);
    await finishAndDelete(page);
    console.log('  lesson 07: walked + cleaned (optional run, seal, lock on the child, ratchet)');

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
