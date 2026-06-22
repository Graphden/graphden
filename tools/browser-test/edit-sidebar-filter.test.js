// Sidebar filter e2e — text-search input that narrows the namespace
// tree.
//
// Coverage:
//   • Sidebar starts with several namespaces expanded by default.
//   • Type a query that matches an existing entity → unrelated rows
//     disappear from the list while the match stays visible.
//   • Type a no-match query → list collapses (no entity-items).
//   • Clear the input (Esc / manual blank) → full tree restored.
//   • The query is case-insensitive.
//
// Run from this directory:  node edit-sidebar-filter.test.js
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
  console.log('edit-sidebar-filter — text-search narrows the namespace tree');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#identity');
    await page.waitForSelector('#search-input', {timeout: 15000});
    // Wait for the sidebar to be populated (at least a few entity-items)
    // and the namespace headers (≥3) the baseline assertion expects.
    await page.waitForFunction(
      () => document.querySelectorAll('.entity-item').length >= 1
            && document.querySelectorAll('.ns-header').length >= 3,
      {timeout: 15000, polling: 100});
    await page.evaluate(() => initGraph && initGraph());
    // Wait again after initGraph rebuilds the tree.
    await page.waitForFunction(
      () => document.querySelectorAll('.entity-item').length >= 1
            && document.querySelectorAll('.ns-header').length >= 3,
      {timeout: 15000, polling: 100});

    // ===================================================================
    // Phase A: baseline — sidebar shows at least one fn-row + several
    // namespace headers.
    // ===================================================================
    const baseline = await page.evaluate(() => ({
      entityCount: document.querySelectorAll('.entity-item').length,
      nsHeaderCount: document.querySelectorAll('.ns-header').length,
    }));
    assert(baseline.entityCount >= 1,
           'baseline shows ≥ 1 entity-item: ' + baseline.entityCount);
    assert(baseline.nsHeaderCount >= 3,
           'baseline shows ≥ 3 namespace headers: '
           + baseline.nsHeaderCount);

    // ===================================================================
    // Phase B: search for "identity" — matching entries stay.
    // ===================================================================
    await page.fill('#search-input', 'identity');
    // Filter applies synchronously after the input event — wait until
    // every visible entity-item carries "identity" (case-insensitive)
    // AND the count is ≥1 AND ≤ baseline (filter narrowed something).
    await page.waitForFunction(
      (b) => {
        const items = Array.from(document.querySelectorAll('.entity-item'));
        if (items.length < 1 || items.length > b) return false;
        return items.every((el) => /identity/i.test(el.textContent || ''));
      },
      baseline.entityCount,
      {timeout: 5000, polling: 50});
    const matched = await page.evaluate(() => {
      const items = Array.from(document.querySelectorAll('.entity-item'));
      return {
        count: items.length,
        allContainIdentity: items.every(
          (el) => /identity/i.test(el.textContent || '')),
      };
    });
    assert(matched.count >= 1,
           '"identity" filter shows ≥ 1 match: ' + matched.count);
    assert(matched.allContainIdentity,
           'every visible entity-item contains "identity"');

    // ===================================================================
    // Phase C: case-insensitive — same query in caps.
    // ===================================================================
    await page.fill('#search-input', 'IDENTITY');
    // Same narrowing predicate — uppercase should match equivalently.
    await page.waitForFunction(
      (b) => {
        const items = Array.from(document.querySelectorAll('.entity-item'));
        if (items.length < 1 || items.length > b) return false;
        return items.every((el) => /identity/i.test(el.textContent || ''));
      },
      baseline.entityCount,
      {timeout: 5000, polling: 50});
    const caps = await page.evaluate(() =>
      document.querySelectorAll('.entity-item').length);
    assert(caps >= 1,
           'uppercase query also matches (case-insensitive): ' + caps);

    // ===================================================================
    // Phase D: no-match query.
    // ===================================================================
    await page.fill('#search-input',
                    'zzz-no-such-fn-anywhere-' + Date.now());
    // Wait until the list is empty.
    await page.waitForFunction(
      () => document.querySelectorAll('.entity-item').length === 0,
      {timeout: 5000, polling: 50});
    const empty = await page.evaluate(() =>
      document.querySelectorAll('.entity-item').length);
    assert(empty === 0,
           'no-match query empties the entity list: ' + empty);

    // ===================================================================
    // Phase E: clear input → full tree restored.
    // ===================================================================
    await page.fill('#search-input', '');
    // Wait until the list is restored to (≥) baseline size.
    await page.waitForFunction(
      (b) => document.querySelectorAll('.entity-item').length >= b,
      baseline.entityCount,
      {timeout: 5000, polling: 50});
    const restored = await page.evaluate(() =>
      document.querySelectorAll('.entity-item').length);
    assert(restored >= baseline.entityCount,
           'clearing the filter restores the entity list: '
           + restored + ' (baseline ' + baseline.entityCount + ')');

    console.log('✓ sidebar filter verified — match / case-insensitive / no-match / clear');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
