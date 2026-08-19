// Interactive-tutorial drift guard — walks EVERY step of lesson 01 by
// performing the real UI actions the tour asks for, asserting the tour
// auto-advances after each. This is the contract that keeps the tour's
// spotlight selectors + completion checks honest against the live editor:
// a renamed class or a changed create/bind/run flow fails HERE, not on a
// visitor (same philosophy as `bb devtour-check` for the code tour).
//
// Also covers the end-of-tour cleanup offer: the final "Delete them" click
// must actually remove the tutorial ns + fn (leak discipline — the runner
// counts rows before/after each file).
//
// Run from this directory:  node edit-tutorial-tour.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, deleteFnByName} = require('./edit-test-helpers');

const NS_NAME = 'tutorial';
const FN_NAME = 'hello-handler';


async function hardCleanup(page) {
  // Belt for a mid-test failure: remove the tutorial fn + ns via the API
  // so the runner's leak counter stays clean even when the tour's own
  // cleanup never ran. The fn is deleted BY NAME first — a prior crash can
  // leave it orphaned outside the (deleted) tutorial ns, where the
  // ns-subtree walk below would miss it and the next run's create 409s.
  try { await deleteFnByName(page, FN_NAME); } catch (_) { /* absent */ }
  try {
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    const ns = (tree.namespaces || []).find((n) => n.name === NS_NAME);
    if (ns) {
      const sub = await api(
        page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + ns.id);
      for (const f of (sub.fns || [])) {
        if (f['namespace-id'] === ns.id) {
          await api(page, 'DELETE', '/api/entities/fn/' + f.id);
        }
      }
      await api(page, 'DELETE', '/api/entities/ns/' + ns.id);
    }
  } catch (_) { /* best-effort */ }
}


function tourTitle(page) {
  return page.evaluate(() => {
    const t = document.querySelector('#gd-tour-pop .gd-tour-title');
    return t ? t.textContent.trim() : null;
  });
}


async function waitTourTitle(page, title, timeoutMs) {
  await page.waitForFunction((expected) => {
    const t = document.querySelector('#gd-tour-pop .gd-tour-title');
    return t && t.textContent.trim() === expected;
  }, title, {timeout: timeoutMs || 20000, polling: 150});
}


function clickTourButton(page, label) {
  return page.evaluate((want) => {
    const btn = Array.from(
      document.querySelectorAll('#gd-tour-pop .gd-tour-btn'))
      .find((b) => b.textContent.trim() === want);
    if (!btn) return false;
    btn.click();
    return true;
  }, label);
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-tutorial-tour — lesson 01 walked end-to-end');
  let failed = false;
  try {
    await hardCleanup(page); // a previous failed run must not pre-pass checks

    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    await page.goto(BASE + '/?tutorial=01');

    // Step 1 — welcome (manual).
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 30000);
    console.log('  step 1: welcome shown');
    assert(await clickTourButton(page, 'Next'), 'welcome Next button');

    // Step 2 — create the namespace through the real sidebar flow.
    await waitTourTitle(page, 'Create a namespace');
    await page.waitForSelector('.create-root-ns-btn', {timeout: 10000});
    await page.click('.create-root-ns-btn');
    await page.waitForSelector('.inline-input', {timeout: 5000});
    await page.fill('.inline-input', NS_NAME);
    await page.press('.inline-input', 'Enter');
    await waitTourTitle(page, 'Add a function');
    console.log('  step 2: namespace created, tour advanced');

    // Step 3 — create the fn via the ns "+" menu. The inline row only
    // renders inside an EXPANDED namespace (the lesson text says so too),
    // so expand first.
    await page.evaluate((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      if (!target) throw new Error('namespace row not found: ' + name);
      const arrow = target.querySelector('.ns-arrow');
      if (arrow && /▶/.test(arrow.textContent || '')) target.click();
    }, NS_NAME);
    await page.waitForFunction((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      const arrow = target?.querySelector('.ns-arrow');
      return arrow && /▼/.test(arrow.textContent || '');
    }, NS_NAME, {timeout: 5000, polling: 100});
    await page.evaluate((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      const plus = target.querySelector('.ns-plus-btn');
      if (!plus) throw new Error('ns-plus-btn not found');
      plus.click();
    }, NS_NAME);
    await page.waitForSelector('.create-menu', {timeout: 5000});
    await page.click('.create-menu-item[data-type="fn"]');
    await page.waitForSelector('.inline-input', {timeout: 5000});
    await page.fill('.inline-input', FN_NAME);
    await page.press('.inline-input', 'Enter');
    await waitTourTitle(page, 'Set the parent', 30000);
    console.log('  step 3: fn created, tour advanced');

    // Step 4 — assign :const through the reparent strip + fn picker.
    await page.waitForSelector('.reparent-strip', {timeout: 15000});
    await page.click('.reparent-strip');
    await page.waitForSelector('.fn-picker-popover', {timeout: 10000});
    await page.fill('.fn-picker-search', 'const');
    await page.waitForFunction(() => {
      return Array.from(document.querySelectorAll('.fn-picker-row'))
        .some((r) => {
          const main = r.querySelector('.fn-picker-row-main');
          return main && /(^|\.)const$/.test(main.textContent.trim().replace(/^:/, ''));
        });
    }, null, {timeout: 10000, polling: 100});
    await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('.fn-picker-row'))
        .find((r) => {
          const main = r.querySelector('.fn-picker-row-main');
          return main && /(^|\.)const$/.test(main.textContent.trim().replace(/^:/, ''));
        });
      row.click();
    });
    await waitTourTitle(page, 'Bind :value', 30000);
    console.log('  step 4: parent set, tour advanced');

    // Step 5 — bind the :value literal.
    await page.waitForSelector('.placeholder-binder', {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.placeholder-binder').click();
    });
    await page.waitForSelector('.free-arg-bind-chooser', {timeout: 5000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find((b) => /Bind literal/.test(b.textContent || '')).click();
    });
    // The value form is a server partial; a plain field mounts async.
    await page.waitForFunction(() => {
      const pop = document.querySelector('.arg-value-edit-popover');
      return pop && (pop.querySelector('.arg-value-edit-input')
        || pop.querySelector('[data-form-field]'));
    }, null, {timeout: 10000, polling: 100});
    await page.evaluate(() => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const field = pop.querySelector('.arg-value-edit-input')
        || pop.querySelector('[data-form-field]');
      field.value = '{"status": 200, "body": "Hello!"}';
      field.dispatchEvent(new Event('input', {bubbles: true}));
      field.dispatchEvent(new Event('change', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    });
    await waitTourTitle(page, 'Run it', 30000);
    console.log('  step 5: value bound, tour advanced');

    // Step 6 — run the fn via ⋯ → ▶ → Run.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    const ranOpen = await page.evaluate(() => {
      const runBtn = Array.from(
        document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶');
      if (!runBtn) return false;
      runBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
      return true;
    });
    assert(ranOpen, '▶ surfaced in row-actions popover');
    await page.waitForSelector('.execute-popover.visible .execute-run-btn',
      {timeout: 10000});
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, "That's the whole loop", 30000);
    console.log('  step 6: executed, tour advanced');

    // Step 7 — finish → cleanup dialog → delete what the tour created.
    assert(await clickTourButton(page, 'Finish'), 'Finish button');
    await waitTourTitle(page, 'Clean up tutorial items?');
    assert(await clickTourButton(page, 'Delete them'), 'Delete them button');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 20000, polling: 200});
    console.log('  step 7: cleanup ran, tour closed');

    // The tour's own cleanup must have removed both rows.
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    assert(!(tree.namespaces || []).some((n) => n.name === NS_NAME),
      'tutorial namespace deleted by the tour cleanup');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
