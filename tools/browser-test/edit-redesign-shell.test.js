// edit-redesign-shell — e2e coverage for the 2026-08 editor redesign shell.
//
// Exercises the NEW surfaces so the redesign is regression-covered:
//   1. Rail surface switching  — Operate reveals the ops panels; Build hides them.
//   2. Inspector on selection  — selecting a fn fills the right inspector
//                                (name + resolved effects), no empty state.
//   3. Workspace switcher      — scoping to a namespace root filters the
//                                explorer to that subtree.
//   4. Details toggle          — reveals / hides the card metadata strips.
//
// Run from this directory:  node edit-redesign-shell.test.js
// Points at GRAPHDEN_URL (default http://localhost:9002).
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('  (console.error: ' + m.text().slice(0, 160) + ')');
  });
  console.log('edit-redesign-shell — rail / inspector / workspace / details toggle');

  try {
    await page.goto(BASE + '/#web-server');
    // Shell + a rendered graph + the inspector are all up.
    await page.waitForSelector('#gd-rail .gd-rail-btn[data-surface="build"]', {timeout: 30000});
    await page.waitForFunction(
      () => !!document.querySelector('.node-overlay')
            && !!document.querySelector('#gd-inspector .gd-insp-name'),
      null, {timeout: 30000, polling: 100});

    // --- 1. Inspector reflects the selected fn ---
    const insp = await page.evaluate(() => ({
      name: (document.querySelector('#gd-inspector .gd-insp-name') || {}).textContent,
      hasEmpty: !!document.querySelector('#gd-inspector .gd-insp-empty'),
      effects: document.querySelectorAll('#gd-inspector .effects-chip').length,
    }));
    assert(insp.name === 'web-server', 'inspector shows the selected fn name (got ' + insp.name + ')');
    assert(!insp.hasEmpty, 'inspector left the empty state once a fn is selected');
    assert(insp.effects > 0, 'inspector lists this fn’s effects (got ' + insp.effects + ')');

    // --- 2. Rail: Operate reveals the ops panels; Build hides them ---
    await page.click('#gd-rail .gd-rail-btn[data-surface="operate"]');
    await page.waitForSelector('#gd-operate:not([hidden])', {timeout: 5000});
    const opVisible = await page.evaluate(
      () => !!document.querySelector('#gd-operate-panels .sidebar-packages'));
    assert(opVisible, 'Operate surface hosts the relocated panels (packages)');
    await page.click('#gd-rail .gd-rail-btn[data-surface="build"]');
    const opHidden = await page.evaluate(
      () => document.getElementById('gd-operate').hidden === true);
    assert(opHidden, 'Build hides the Operate surface again');

    // --- 3. Workspace switcher scopes the explorer to a namespace root ---
    await page.click('#gd-ws-chip');
    await page.waitForSelector('#gd-ws-pop .gd-pop-item[data-ws]', {timeout: 5000});
    // Pick a top-level namespace that is NOT the selected fn's (core), so the
    // filter visibly drops the others.
    const picked = await page.evaluate(() => {
      const items = [...document.querySelectorAll('#gd-ws-pop .gd-pop-item[data-ws]')]
        .map((i) => i.getAttribute('data-ws')).filter(Boolean);
      return items.find((n) => n !== 'app') || items[0];
    });
    assert(picked, 'workspace popover lists namespace roots');
    await page.click('#gd-ws-pop .gd-pop-item[data-ws="' + picked + '"]');
    await page.waitForFunction((nm) => {
      const b = document.querySelector('#gd-ws-chip b');
      return b && b.textContent === nm;
    }, picked, {timeout: 5000});
    const scoped = await page.evaluate((nm) => {
      const headers = [...document.querySelectorAll('#entity-list [data-ns-path]')]
        .map((h) => h.getAttribute('data-ns-path'));
      // Every top-level namespace header shown must be the picked root (or its
      // descendant); "app" (the other root) must be gone.
      const topLevel = headers.filter((p) => p && p.indexOf('.') === -1);
      return { chip: (document.querySelector('#gd-ws-chip b') || {}).textContent,
               topLevel, appGone: !topLevel.includes('app') };
    }, picked);
    assert(scoped.chip === picked, 'chip shows the scoped workspace (' + scoped.chip + ')');
    assert(scoped.appGone, 'scoping hid the out-of-workspace "app" namespace');
    // Reset scope so the tail of the test runs against the full tree.
    await page.evaluate(() => { if (window.setGraphdenWorkspace) window.setGraphdenWorkspace(null); });

    // --- 4. Details toggle reveals / hides the card metadata strips ---
    // The test env opts into full cards, so a strip is present + visible now.
    const beforeToggle = await page.evaluate(() => {
      const s = document.querySelector('.effects-strip');
      return { present: !!s, visible: !!(s && s.offsetParent !== null) };
    });
    assert(beforeToggle.present, 'an effects strip exists on the canvas');
    assert(beforeToggle.visible, 'effects strip is visible with details on');
    await page.click('.nav-btn[aria-label="Show card details"]');
    const afterToggle = await page.evaluate(() => {
      const s = document.querySelector('.effects-strip');
      return { compactClass: document.body.classList.contains('gd-cards-compact'),
               visible: !!(s && s.offsetParent !== null) };
    });
    assert(afterToggle.compactClass, 'Details toggle turned on compact mode');
    assert(!afterToggle.visible, 'compact mode hides the effects strip');

    console.log('redesign-shell — PASS');
  } catch (e) {
    console.error('✗ test failed:', e.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
