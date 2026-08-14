// Lazy sidebar e2e — the tree loads namespaces + counts up front and
// fetches each namespace's fn leaves only when it is expanded.
//
// Coverage:
//   • Fresh load (no fn selected): the sidebar shows namespace headers
//     but ZERO fn rows — nothing is fetched until a namespace opens.
//   • The "(primitives)" pseudo-namespace (the old "(root)" label —
//     a developer-ism users never shared) shows a fn count from the :tree
//     payload even though its leaves are not loaded.
//   • Expanding a namespace lazily fetches + renders its fn leaves.
//
// This is the behaviour that replaced the O(all-fns) scope=index pull:
// init is O(namespaces); leaves arrive per-expand via ?scope=namespace.
//
// Run from this directory:  node edit-sidebar-lazy.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-sidebar-lazy — namespaces + counts up front, leaves on expand');

  try {
    // Load WITHOUT a hash so no fn is selected → no namespace is
    // auto-expanded → the lazy path is exercised cleanly.
    await page.goto(process.env.GRAPHDEN_URL || 'http://localhost:9002');
    await page.waitForSelector('#search-input', {timeout: 15000});
    // The namespace tree paints from ?scope=tree — several ns headers,
    // and crucially NO fn rows yet.
    await page.waitForFunction(
      () => document.querySelectorAll('.ns-header').length >= 3,
      null,
      {timeout: 15000, polling: 100});

    // ===================================================================
    // Phase A: fresh load — namespaces present, ZERO fn leaves loaded.
    // ===================================================================
    const initial = await page.evaluate(() => ({
      nsHeaders: document.querySelectorAll('.ns-header').length,
      entityItems: document.querySelectorAll('.entity-item').length,
      // The (primitives) node's count badge comes from the :tree counts, not
      // from loaded leaves.
      rootCount: (() => {
        const hdr = [...document.querySelectorAll('.ns-header')]
          .find((h) => /\(primitives\)/.test(h.textContent || ''));
        const badge = hdr && hdr.querySelector('.ns-count');
        return badge ? Number(badge.textContent) : null;
      })(),
    }));
    assert(initial.nsHeaders >= 3,
           'tree shows ≥ 3 namespace headers up front: ' + initial.nsHeaders);
    assert(initial.entityItems === 0,
           'NO fn leaves are loaded before any namespace is expanded: '
           + initial.entityItems);
    assert(initial.rootCount !== null && initial.rootCount > 0,
           'the (primitives) node shows a fn count from :tree without loading its '
           + 'leaves: ' + initial.rootCount);

    // ===================================================================
    // Phase B: expand core → its child namespaces appear (no fetch needed,
    // the whole namespace list ships in the :tree payload).
    // ===================================================================
    await page.click('[data-ns-path="core"] .ns-label');
    await page.waitForSelector('[data-ns-path="core.arithmetic"]', {timeout: 5000});

    // ===================================================================
    // Phase C: expand core.arithmetic → its fn leaves lazily load.
    // ===================================================================
    await page.click('[data-ns-path="core.arithmetic"] .ns-label');
    await page.waitForFunction(
      () => {
        const names = [...document.querySelectorAll('.entity-item .name')]
          .map((n) => n.textContent);
        return names.includes('add') && names.includes('mul');
      },
      null,
      {timeout: 15000, polling: 100});
    const loaded = await page.evaluate(() =>
      [...document.querySelectorAll('.entity-item .name')].map((n) => n.textContent));
    assert(loaded.includes('add') && loaded.includes('mul'),
           'expanding core.arithmetic lazily loaded its fn leaves (add, mul): '
           + loaded.join(', '));

    console.log('✓ lazy sidebar verified — O(namespaces) init, leaves on expand, '
                + '(primitives) count from :tree');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
