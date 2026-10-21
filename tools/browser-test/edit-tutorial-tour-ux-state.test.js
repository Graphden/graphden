// Lesson 12 — running fns, the Explorer/Inspector,
// working without the mouse, filters and views, in-graph state, and
// tracing a run.
//
// Part of the interactive-tutorial drift guard: walks every step of its
// lessons by doing the real UI actions, so a renamed class or a changed
// flow fails HERE, not on a visitor. The lessons are split across files
// because the runner caps one file at 5 minutes — see
// tutorial-tour-helpers.js.
//
// Run from this directory:  node edit-tutorial-tour-ux.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  runViaRowActions, tourWhere, extendViaRowActions, bindFirstPlaceholder,
  bindFnRefPlaceholder, finishAndDelete, runWithEffectAck, waitTourClosed,
  openRowActionsFor, bindNamedPlaceholder, bindPlaceholderOn, extendInPlace,
  waitUntil,
} = require('./tutorial-tour-helpers');


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ux-state.test — lesson 12');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- Lesson 12 — in-graph state ----------
    await page.goto(BASE + '/?tutorial=12');
    await waitTourTitle(page, 'A graph can remember', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 12 Next');
    // Built from the outside in (2026-09-16): the writer first, then the
    // cell it needs is bound as the base fn and EXTENDED IN PLACE from its
    // card — no trip through the Explorer, no retyping its name.
    await waitTourTitle(page, 'Find swap-conj');
    await filterAndSelect(page, 'swap-conj', 'swap-conj');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-bump', 'swap-conj');
    await waitTourTitle(page, 'tutorial-bump is open', 150000);
    await waitTourTitle(page, 'Point it at a cell', 150000);
    await bindPlaceholderOn(page, 'tutorial-bump', 'a', 'fn-ref', 'cell');
    await waitTourTitle(page, 'Make it yours, in place', 150000);
    await extendInPlace(page, 'cell', 'tutorial-cell');
    await waitTourTitle(page, 'Seed it with an empty list', 150000);
    await bindPlaceholderOn(page, 'tutorial-cell', 'initial-value', 'literal', '[]');
    // The slot points at the CHILD now, not at the base fn.
    const bumpBinds = await page.evaluate(async () => {
      const r = await authFetch(API.api_graph_entities + '?scope=search&q=tutorial-');
      const d = await r.json();
      const bump = (d.fns || []).find((f) => f.name === 'tutorial-bump');
      const cell = (d.fns || []).find((f) => f.name === 'tutorial-cell');
      const sub = await (await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + bump.id)).json();
      return (sub.bindings || []).filter((b) => b['fn-id'] === bump.id).map((b) => b['ref-fn-id'] === cell.id);
    });
    assert(bumpBinds.length === 1 && bumpBinds[0] === true,
      'tutorial-bump :a now references tutorial-cell (' + JSON.stringify(bumpBinds) + ')');
    await waitTourTitle(page, 'Run it', 150000);
    // Writing to a cell is the :state effect, so Run is gated behind the
    // acknowledgement checkbox — the same gate lesson 16 teaches.
    await openRowActionsFor(page, 'tutorial-bump', 30000, {root: true});
    await page.click('.row-actions-popover [data-action="run-fn"]');
    await waitTourTitle(page, 'Choose the item', 150000);
    const itemField = '.execute-popover.visible .execute-arg-form[data-slot-name="value"] .cm-content';
    await page.waitForSelector(itemField, {timeout: 30000});
    await page.fill(itemField, 'wrong');
    // Observe at least two completion ticks: entering a different value
    // must keep the lesson on the input step, without executing the cell.
    await page.waitForFunction(() => performance.now() - _tourState._shownAt > 1500,
      null, {timeout: 10000});
    assert(await page.locator('#gd-tour-pop .gd-tour-title').textContent() === 'Choose the item',
      'a missing or wrong tick does not skip the input step');
    await page.fill(itemField, 'tick');
    await waitTourTitle(page, 'Acknowledge, then run', 30000);
    assert(await page.locator('.execute-run-btn').isDisabled(),
      'entering tick does not bypass the side-effect acknowledgement');
    await page.check('.execute-confirm-checkbox');
    await page.click('.execute-popover.visible .execute-run-btn');
    // The lesson's whole claim: the SECOND run sees the first one's value —
    // and since 2026-09-13 the tour makes the reader do that second run
    // (the old single "Run it twice" step passed on the first).
    await waitTourTitle(page, 'Run it again', 150000);
    await page.waitForSelector('.execute-popover.visible .execute-run-btn:not([disabled])', {timeout: 30000});
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, "That's in-graph state", 150000);
    const rows = await page.evaluate(() => document.querySelectorAll('.execute-result-list > li').length);
    assert(rows >= 2, 'the pane lists two rows after the second run (got ' + rows + ')');
    // The lesson's whole claim, checked where it is unambiguous: run the
    // fn twice through the API and watch the cell's list GROW. (The result
    // pane renders effects and typed representations, so asserting on its
    // text would be asserting on the rendering, not on the state.)
    const runViaApi = async () => {
      const r = await page.evaluate(async () => {
        const resp = await authFetch(API.api_execute, {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({'fn-name': 'tutorial-bump', args: {value: 'tick'}})});
        return resp.json();
      });
      assert(r.status === 'succeeded',
        'tutorial-bump ran (got ' + JSON.stringify(r).slice(0, 120) + ')');
      return r.result || [];
    };
    const before = await runViaApi();
    const after = await runViaApi();
    assert(before.every((value) => value === 'tick') && after.every((value) => value === 'tick'),
      'all state items are tick, including the two browser runs');
    assert(after.length > before.length,
      'the cell kept its value between runs (' + JSON.stringify(before)
      + ' → ' + JSON.stringify(after) + ')');
    await finishAndDelete(page);
    for (const name of ['tutorial-cell', 'tutorial-bump']) {
      const remaining = await api(page, 'GET', '/api/graph/entities?scope=search&q=' + name);
      assert(!(remaining.fns || []).some((fn) => fn.name === name), name + ' was removed by lesson cleanup');
    }
    console.log('  lesson 12: walked + cleaned (state survived the second run)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-ux-state.test-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-ux-state.test-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
