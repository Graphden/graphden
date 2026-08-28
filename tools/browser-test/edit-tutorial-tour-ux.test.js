// Lessons 12, 18, 17, 09, 15 — running fns, workspaces, the
// Explorer/Inspector view layer, in-graph state, and tracing a run.
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
  runViaRowActions, tourTitle, extendViaRowActions, bindFirstPlaceholder,
  bindFnRefPlaceholder, finishAndDelete, runWithEffectAck,
} = require('./tutorial-tour-helpers');


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ux — lessons 12 / 17 / 18 / 09 / 15');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- Lesson 12 — executing a fn ----------
    await page.goto(BASE + '/?tutorial=12');
    await waitTourTitle(page, 'Running is part of editing', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 12 Next');
    await waitTourTitle(page, 'Find str-len');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, 'Free args become the form', 150000);
    await runViaRowActions(page, 'hello');
    await waitTourTitle(page, 'Keep the interesting one', 150000);
    // The step now completes on a REAL persisted run (the
    // body[data-gd-persisted-run] marker) — do what the lesson says:
    // reopen Run, tick "Save to history", enter graphden, Run.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.execute-popover.visible .execute-run-btn',
      {timeout: 10000});
    await page.waitForFunction(() => {
      const p = document.querySelector('.execute-popover.visible');
      return p && p.querySelector('[data-form-field]');
    }, null, {timeout: 10000, polling: 100});
    await page.evaluate(() => {
      const p = document.querySelector('.execute-popover.visible');
      const f = p.querySelector('[data-form-field]');
      f.value = 'graphden';
      f.dispatchEvent(new Event('input', {bubbles: true}));
      const persist = p.querySelector('.execute-persist-checkbox');
      if (persist && !persist.checked) persist.click();
    });
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, 'History is a graph read', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 12 history Next');
    await waitTourTitle(page, "That's the run loop", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 12 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 12: walked (nothing created)');

    // ---------- Lesson 17 — Explorer and Inspector ----------
    await page.goto(BASE + '/?tutorial=17');
    await waitTourTitle(page, 'Two panes, one graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 17 Next');
    await waitTourTitle(page, 'Narrow by kind');
    // The lens chips are the step's target; exercise one for real.
    await page.waitForSelector('.kind-toggle', {timeout: 30000});
    const lensCount = await page.evaluate(
      () => document.querySelectorAll('.kind-toggle').length);
    assert(lensCount >= 3,
      'the Explorer offers kind lenses (got ' + lensCount + ')');
    await page.evaluate(() => {
      const t = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((b) => /types/.test(b.textContent));
      if (t) t.click();
    });
    assert(await clickTourButton(page, 'Next'), 'lesson 17 lens Next');
    await waitTourTitle(page, 'Back to everything');
    await page.evaluate(() => {
      const all = document.querySelector('.kind-toggle.kind-all');
      if (all) all.click();
    });
    assert(await clickTourButton(page, 'Next'), 'lesson 17 all-lens Next');
    await waitTourTitle(page, 'Select something');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, "The Inspector's tabs", 150000);
    const tabs = await page.evaluate(() => Array.from(
      document.querySelectorAll('.gd-insp-tab')).map((t) => t.textContent.trim()));
    assert(tabs.includes('Bindings') && tabs.includes('Runs'),
      'the Inspector shows its tabs (got ' + JSON.stringify(tabs) + ')');
    await waitTourTitle(page, "That's the view layer", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 17 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 17: walked (nothing created)');

    // ---------- Lesson 18 — workspaces ----------
    await page.goto(BASE + '/?tutorial=18');
    await waitTourTitle(page, 'Your slice of a shared graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 18 Next');
    await waitTourTitle(page, 'Open the workspace chip');
    await page.waitForSelector('#gd-ws-chip', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-ws-chip').click());
    await waitTourTitle(page, 'Pick a root', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 18 root Next');
    await waitTourTitle(page, 'And back');
    assert(await clickTourButton(page, 'Next'), 'lesson 18 back Next');
    await waitTourTitle(page, "That's workspaces");
    assert(await clickTourButton(page, 'Finish'), 'lesson 18 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 18: walked (nothing created)');

    // ---------- Lesson 09 — in-graph state ----------
    await page.goto(BASE + '/?tutorial=09');
    await waitTourTitle(page, 'A graph can remember', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 09 Next');
    await waitTourTitle(page, 'Find cell');
    await filterAndSelect(page, 'cell', 'cell');
    await waitTourTitle(page, 'Make your own', 150000);
    await extendViaRowActions(page, 'tutorial-cell', 'cell');
    await waitTourTitle(page, 'Seed it with an empty list', 150000);
    await bindFirstPlaceholder(page, '[]');
    await waitTourTitle(page, 'Now something that writes to it', 150000);
    await filterAndSelect(page, 'swap-conj', 'swap-conj');
    await waitTourTitle(page, 'Extend it too', 150000);
    await extendViaRowActions(page, 'tutorial-bump', 'swap-conj');
    await waitTourTitle(page, 'Point it at your cell', 150000);
    await bindFnRefPlaceholder(page, 'tutorial-cell');
    await waitTourTitle(page, 'Run it twice', 150000);
    // Writing to a cell is the :state effect, so Run is gated behind the
    // acknowledgement checkbox — the same gate lesson 13 teaches.
    await runWithEffectAck(page, 'tick');
    // The lesson's whole claim: the SECOND run sees the first one's value.
    // Read the result only once it has SETTLED — the host shows
    // "Submitting…" first, and reading through that compares nothing.
    await waitTourTitle(page, "That's in-graph state", 150000);
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
    assert(after.length > before.length,
      'the cell kept its value between runs (' + JSON.stringify(before)
      + ' → ' + JSON.stringify(after) + ')');
    await finishAndDelete(page);
    console.log('  lesson 09: walked + cleaned (state survived the second run)');

    // ---------- Lesson 15 — tracing a run ----------
    await page.goto(BASE + '/?tutorial=15');
    await waitTourTitle(page, 'What actually ran?', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 15 Next');
    await waitTourTitle(page, 'Build something with a step in it');
    await filterAndSelect(page, 'const', 'const');
    await extendViaRowActions(page, 'tutorial-inner', 'const');
    await waitTourTitle(page, 'Give the inner fn a value', 150000);
    await bindFirstPlaceholder(page, '"hi"');
    await waitTourTitle(page, 'Now the outer fn', 150000);
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await extendViaRowActions(page, 'tutorial-outer', 'str-upper');
    await waitTourTitle(page, 'Chain them', 150000);
    await bindFnRefPlaceholder(page, 'tutorial-inner');
    await waitTourTitle(page, 'Run it with a trace', 150000);
    // Run WITH the trace box ticked — that is what makes the path button
    // appear at all (an untraced run has no entries to draw).
    await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 15000});
    await page.evaluate(() => {
      const tr = document.querySelector('.execute-trace-checkbox');
      if (tr && !tr.checked) tr.click();
    });
    await page.evaluate(() => document.querySelector('.execute-run-btn').click());
    await waitTourTitle(page, 'Draw the path', 150000);
    await page.waitForSelector('.execute-show-path-btn', {timeout: 60000});
    await page.evaluate(() => document.querySelector('.execute-show-path-btn').click());
    await waitTourTitle(page, "That's debugging in place", 150000);
    await finishAndDelete(page);
    console.log('  lesson 15: walked + cleaned (path drawn on canvas)');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-ux-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-ux-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
