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
    await page.goto('http://localhost:9002/#identity');
    await page.waitForSelector('#search-input', {timeout: 15000});
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForTimeout(800);

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
    await page.waitForTimeout(400);
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
    await page.waitForTimeout(400);
    const caps = await page.evaluate(() =>
      document.querySelectorAll('.entity-item').length);
    assert(caps >= 1,
           'uppercase query also matches (case-insensitive): ' + caps);

    // ===================================================================
    // Phase D: no-match query.
    // ===================================================================
    await page.fill('#search-input',
                    'zzz-no-such-fn-anywhere-' + Date.now());
    await page.waitForTimeout(400);
    const empty = await page.evaluate(() =>
      document.querySelectorAll('.entity-item').length);
    assert(empty === 0,
           'no-match query empties the entity list: ' + empty);

    // ===================================================================
    // Phase E: clear input → full tree restored.
    // ===================================================================
    await page.fill('#search-input', '');
    await page.waitForTimeout(400);
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
