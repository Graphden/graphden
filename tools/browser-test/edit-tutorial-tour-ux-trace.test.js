// Lesson 18 — running fns, the Explorer/Inspector,
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
  openRowActionsFor, bindPlaceholderOn, extendInPlace,
  waitUntil,
} = require('./tutorial-tour-helpers');


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ux-trace.test — lesson 18');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- Lesson 18 — tracing a run ----------
    await page.goto(BASE + '/?tutorial=18');
    await waitTourTitle(page, 'What actually ran?', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 18 Next');
    // Built from the OUTSIDE IN (2026-09-16): the outer fn is extended from
    // the Explorer once; every inner fn is the base fn bound into a slot and
    // then EXTENDED IN PLACE from its card — the reader never leaves
    // tutorial-sentence's canvas and never retypes a name. One ring per
    // action: each bind / extend is its own step, so the walk waits for
    // every title a reader would see.
    await waitTourTitle(page, 'Find str-join');
    await filterAndSelect(page, 'str-join', 'str-join');
    await waitTourTitle(page, 'The outer fn', 150000);
    await extendViaRowActions(page, 'tutorial-sentence', 'str-join');
    await waitTourTitle(page, 'tutorial-sentence is open', 150000);
    await waitTourTitle(page, 'Spaces between the words', 150000);
    await bindPlaceholderOn(page, 'tutorial-sentence', 'separator', 'literal', ' ');
    await waitTourTitle(page, 'Feed it a list', 150000);
    // :coll is a LIST slot — its empty `+` offers the whole-list fn-ref.
    await bindPlaceholderOn(page, 'tutorial-sentence', 'coll', 'whole-list', 'map');
    await waitTourTitle(page, 'Extend it in place', 150000);
    await extendInPlace(page, 'map', 'tutorial-shout');
    await waitTourTitle(page, 'Pick the function', 150000);
    // The child's own `+`s are on THIS canvas — a one-hop ref child draws
    // its unbound slots.
    await bindPlaceholderOn(page, 'tutorial-shout', 'func', 'fn-ref', 'str-upper');
    await waitTourTitle(page, 'And the words', 150000);
    await bindPlaceholderOn(page, 'tutorial-shout', 'coll', 'whole-list', 'str-split');
    // Binding a ref on a CHILD card unfolds it, so the bound fn is drawn —
    // the reader must be able to reach its ⋯ for the next step.
    await page.waitForSelector('.node-overlay[data-fn-name="str-split"]', {timeout: 60000});
    await waitTourTitle(page, 'Extend that one too', 150000);
    await extendInPlace(page, 'str-split', 'tutorial-words');
    await waitTourTitle(page, 'The whole pipeline, unfolded', 150000);
    // …and the fit after the in-place extend brings the new card on screen.
    await page.waitForFunction(() => {
      const r = document.querySelector('.node-overlay[data-fn-name="tutorial-words"]').getBoundingClientRect();
      const surface = document.getElementById('graph-surface').getBoundingClientRect();
      return r.width > 0 && r.right <= surface.right + 8 && r.left >= surface.left - 8;
    }, null, {timeout: 15000, polling: 100});
    assert(await clickTourButton(page, 'Next'), 'pipeline look-step Next');
    await waitTourTitle(page, 'Give it text', 150000);
    await bindPlaceholderOn(page, 'tutorial-words', 'string', 'literal', 'hello,big,world');
    await waitTourTitle(page, 'And where to cut', 150000);
    await bindPlaceholderOn(page, 'tutorial-words', 'separator', 'literal', ',');
    await waitTourTitle(page, 'Peek inside without leaving', 150000);
    await openRowActionsFor(page, 'tutorial-shout');
    await page.waitForSelector('.row-actions-popover [data-action="peek-fn"]',
      {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.row-actions-popover [data-action="peek-fn"]')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.fn-peek-panel', {timeout: 15000});
    const peek = await page.evaluate(() => ({
      title: document.querySelector('.fn-peek-title')?.textContent,
      hasBody: !!document.querySelector('.fn-peek-body'),
    }));
    assert(peek.title === 'tutorial-shout',
      'peek panel names the peeked fn (got ' + peek.title + ')');
    await waitTourTitle(page, 'Run it with a trace', 150000);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.fn-peek-panel'),
      null, {timeout: 15000, polling: 100});
    // Escape must have been CONSUMED by the panel — the tour survives.
    assert(await page.evaluate(() => !!document.querySelector('#gd-tour-pop')),
      'closing the peek with Escape does not end the tour');
    // The pipeline the reader built is still unfolded on this canvas.
    for (const name of ['tutorial-sentence', 'tutorial-shout', 'tutorial-words', 'str-upper']) {
      assert(await page.evaluate((n) => !!document.querySelector('.node-overlay[data-fn-name="' + n + '"]'), name),
        name + ' is on the canvas before the run');
    }
    // The visible node Run uses the existing Inspector pane.
    await page.click('.node-overlay[data-fn-name="tutorial-sentence"] .fn-run-trigger');
    await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 15000});
    const options = page.locator('.execute-popover.visible .execute-options');
    assert(!(await options.evaluate(element => element.open)), 'Run options start collapsed');
    await options.locator('summary').click();
    await page.check('.execute-popover.visible .execute-persist-checkbox');
    await page.check('.execute-popover.visible .execute-trace-checkbox');
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, 'Draw the timing path', 150000);
    await page.click('.execute-show-path-btn');
    await waitTourTitle(page, 'Run with captured values', 150000);
    assert(await page.locator('.path-trace-badge').count() > 0, 'timing path has actual call badges');
    assert(await page.locator('.path-value-badge').count() === 0, 'without capture there are no value badges');
    await page.keyboard.press('Escape');
    await page.click('.execute-popover.visible .execute-capture-values-checkbox');
    assert(await page.evaluate(() =>
      document.querySelector('.execute-popover.visible .execute-capture-values-checkbox').checked),
      'capture values stays ticked after the confirm');
    await page.evaluate(() => document.querySelector('.execute-run-btn').click());
    await waitTourTitle(page, 'Draw the path', 150000);
    await page.waitForSelector('.execute-show-path-btn', {timeout: 60000});
    await page.evaluate(() => document.querySelector('.execute-show-path-btn').click());
    await waitTourTitle(page, 'Read the path', 150000);
    // What the reader is told to see: every fn that ran lit — the three
    // made here plus str-upper with its 3× call count (a callable run by
    // :map is a traced frame per call) — and the value transforming hop
    // by hop on the chips.
    const path = await page.evaluate(() => ({
      highlighted: Array.from(document.querySelectorAll('.node-overlay.path-highlighted'))
        .map((el) => el.dataset.fnName),
      badges: Array.from(document.querySelectorAll('.node-overlay.path-highlighted'))
        .map((el) => [el.dataset.fnName, el.querySelector('.path-trace-badge')?.textContent]),
      chips: Array.from(document.querySelectorAll('.path-value-badge'))
        .map((el) => el.textContent),
    }));
    for (const name of ['tutorial-sentence', 'tutorial-shout', 'tutorial-words', 'str-upper']) {
      assert(path.highlighted.includes(name),
        name + ' is on the drawn path: ' + JSON.stringify(path.highlighted));
    }
    assert(path.badges.some(([n, b]) => n === 'str-upper' && /^3× /.test(b || '')),
      'str-upper ran once per word — its badge counts 3×: ' + JSON.stringify(path.badges));
    assert(path.chips.includes('= "HELLO BIG WORLD"'),
      'the sentence prints on its card: ' + JSON.stringify(path.chips));
    assert(path.chips.some((c) => /^= value$/.test(c) || /HELLO/.test(c)),
      'the list hops carry value chips: ' + JSON.stringify(path.chips));
    assert(await clickTourButton(page, 'Next'), 'Read the path Next');
    await waitTourTitle(page, 'Open a captured value', 150000);
    const captured = page.locator('.node-overlay[data-fn-name="tutorial-shout"] .path-value-badge');
    await captured.focus();
    await page.keyboard.press('Enter');
    await page.waitForSelector('.path-value-popover-body');
    assert(JSON.stringify(JSON.parse(await page.locator('.path-value-popover-body').textContent()))
      === JSON.stringify(['HELLO', 'BIG', 'WORLD']), 'captured list opens in full from keyboard');
    await waitTourTitle(page, 'Open the call tree', 150000);
    await page.keyboard.press('Escape');
    await page.waitForSelector('#gd-insp-runs .execute-history-tree-btn', {timeout: 30000});
    await page.click('#gd-insp-runs .execute-history-tree-btn');
    await waitTourTitle(page, 'Read the tree', 150000);
    const tree = await page.evaluate(() => ({
      rows: Array.from(document.querySelectorAll('.trace-view-panel .trace-row'))
        .map((r) => r.textContent.replace(/\s+/g, ' ').trim().slice(0, 60)),
      values: Array.from(document.querySelectorAll('.trace-view-panel .trace-value'))
        .map((v) => v.textContent.replace(/\s+/g, ' ').trim().slice(0, 40)),
    }));
    assert(tree.rows.length >= 6,
      'the tree lists the root, two hops and three str-upper calls: ' + JSON.stringify(tree.rows));
    assert(tree.rows[0].includes('tutorial-sentence'),
      'the run\'s own fn is the tree\'s root: ' + JSON.stringify(tree.rows[0]));
    assert(tree.values.some((v) => /HELLO BIG WORLD/.test(v)),
      'the tree carries the captured values: ' + JSON.stringify(tree.values));
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.trace-view-panel'),
      null, {timeout: 15000, polling: 100});
    assert(await clickTourButton(page, 'Next'), 'Read the tree Next');
    await waitTourTitle(page, 'Your trail', 150000);
    // The filter is still holding the last search — clear it for real,
    // as the step instructs; the Recent list only shows outside search.
    await page.evaluate(() => {
      if (typeof clearSearch === 'function') clearSearch();
    });
    await page.waitForSelector('#gd-recent-fns:not([hidden]) .gd-recent-row',
      {timeout: 15000});
    const recents = await page.evaluate(() => ({
      hidden: document.getElementById('gd-recent-fns')?.hidden,
      rows: Array.from(document.querySelectorAll('.gd-recent-row'))
        .map((r) => r.textContent),
    }));
    assert(recents.hidden === false && recents.rows.length > 0,
      'Recent list is visible with rows (' + JSON.stringify(recents.rows) + ')');
    await waitTourTitle(page, "That's debugging in place", 150000);
    // A recent row navigates — the trail is the way back.
    const trailHash = await page.evaluate(() => location.hash);
    await page.locator('#gd-recent-fns:not([hidden]) .gd-recent-row').first().click();
    await page.waitForFunction((h) => location.hash !== h, trailHash,
      {timeout: 30000, polling: 200});
    console.log('  lesson 18: Recent row navigated to '
      + await page.evaluate(() => location.hash));
    await finishAndDelete(page);
    for (const name of ['tutorial-sentence', 'tutorial-shout', 'tutorial-words']) {
      const remaining = await api(page, 'GET', '/api/graph/entities?scope=search&q=' + name);
      assert(!(remaining.fns || []).some((fn) => fn.name === name), name + ' was removed by lesson cleanup');
    }
    console.log('  lesson 18: walked + cleaned (pipeline + path + tree + peek + trail)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-ux-trace.test-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-ux-trace.test-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
