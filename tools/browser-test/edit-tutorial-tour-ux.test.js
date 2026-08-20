// Lessons 09, 22, 23 — running fns, workspaces, the Explorer/Inspector
// view layer.
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
  runViaRowActions, tourTitle,
} = require('./tutorial-tour-helpers');


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-ux — lessons 09 / 23 / 22');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

    // ---------- Lesson 09 — executing a fn ----------
    await page.goto(BASE + '/?tutorial=09');
    await waitTourTitle(page, 'Running is part of editing', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 09 Next');
    await waitTourTitle(page, 'Find str-len');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, 'Free args become the form', 150000);
    await runViaRowActions(page, 'hello');
    await waitTourTitle(page, 'Keep the interesting one', 150000);
    // The step completes on the history toggle being present, which it is
    // as soon as the popover reopens — reopen it the way the lesson says.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 30000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶')
        .dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await waitTourTitle(page, 'History is a graph read', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 09 history Next');
    await waitTourTitle(page, "That's the run loop", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 09 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 09: walked (nothing created)');

    // ---------- Lesson 23 — Explorer and Inspector ----------
    await page.goto(BASE + '/?tutorial=23');
    await waitTourTitle(page, 'Two panes, one graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 23 Next');
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
    assert(await clickTourButton(page, 'Next'), 'lesson 23 lens Next');
    await waitTourTitle(page, 'Back to everything');
    await page.evaluate(() => {
      const all = document.querySelector('.kind-toggle.kind-all');
      if (all) all.click();
    });
    assert(await clickTourButton(page, 'Next'), 'lesson 23 all-lens Next');
    await waitTourTitle(page, 'Select something');
    await filterAndSelect(page, 'str-len', 'str-len');
    await waitTourTitle(page, "The Inspector's tabs", 150000);
    const tabs = await page.evaluate(() => Array.from(
      document.querySelectorAll('.gd-insp-tab')).map((t) => t.textContent.trim()));
    assert(tabs.includes('Bindings') && tabs.includes('Runs'),
      'the Inspector shows its tabs (got ' + JSON.stringify(tabs) + ')');
    await waitTourTitle(page, "That's the view layer", 150000);
    assert(await clickTourButton(page, 'Finish'), 'lesson 23 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 23: walked (nothing created)');

    // ---------- Lesson 22 — workspaces ----------
    await page.goto(BASE + '/?tutorial=22');
    await waitTourTitle(page, 'Your slice of a shared graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 22 Next');
    await waitTourTitle(page, 'Open the workspace chip');
    await page.waitForSelector('#gd-ws-chip', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-ws-chip').click());
    await waitTourTitle(page, 'Pick a root', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 22 root Next');
    await waitTourTitle(page, 'And back');
    assert(await clickTourButton(page, 'Next'), 'lesson 22 back Next');
    await waitTourTitle(page, "That's workspaces");
    assert(await clickTourButton(page, 'Finish'), 'lesson 22 Finish');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 30000, polling: 200});
    console.log('  lesson 22: walked (nothing created)');

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
