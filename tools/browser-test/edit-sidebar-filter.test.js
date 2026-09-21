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
//   • A graph refresh (initGraph) racing an in-flight lazy namespace load
//     keeps that namespace's leaves on screen (the fresh shell is hydrated
//     from the fn cache).
//
// Run from this directory:  node edit-sidebar-filter.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('edit-sidebar-filter — text-search narrows the namespace tree');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#const');
    await page.waitForSelector('#search-input', {timeout: 60000});
    // Wait for the sidebar to be populated (at least a few entity-items)
    // and the namespace headers (≥3) the baseline assertion expects.
    // 60 s, like every other initial load in this suite: the first tree
    // render sits behind the page's layout POSTs (project_e2e_slow_stack_deadlines).
    await page.waitForFunction(
      () => document.querySelectorAll('.entity-item').length >= 1
            && document.querySelectorAll('.ns-header').length >= 3,
      null,
      {timeout: 60000, polling: 100});
    // A second `initGraph` on top of a boot that may still be settling —
    // this is the shape that flaked (2026-09-19, twice, green on retry): the
    // refresh reset the fn cache, the boot's lazy namespace load landed while
    // the new tree was in flight and synced its rows into the OLD graphData,
    // and the fresh shell rendered that namespace as loaded-with-no-leaves.
    // Phase F below pins the invariant directly; this call keeps the original
    // reproduction in the walk.
    await page.evaluate(() => initGraph && initGraph());
    // Wait again after initGraph rebuilds the tree.
    await page.waitForFunction(
      () => document.querySelectorAll('.entity-item').length >= 1
            && document.querySelectorAll('.ns-header').length >= 3,
      null,
      {timeout: 60000, polling: 100});

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
    // Phase B: search for "constantly" — matching entries stay.
    // ===================================================================
    await page.fill('#search-input', 'constantly');
    // Filter applies synchronously after the input event — wait until
    // every visible entity-item carries "constantly" (case-insensitive)
    // AND the count is ≥1 AND ≤ baseline (filter narrowed something).
    await page.waitForFunction(
      (b) => {
        const items = Array.from(document.querySelectorAll('.entity-item'));
        if (items.length < 1 || items.length > b) return false;
        return items.every((el) => /constantly/i.test(el.textContent || ''));
      },
      baseline.entityCount,
      {timeout: 5000, polling: 50});
    const matched = await page.evaluate(() => {
      const items = Array.from(document.querySelectorAll('.entity-item'));
      return {
        count: items.length,
        allContainIdentity: items.every(
          (el) => /constantly/i.test(el.textContent || '')),
      };
    });
    assert(matched.count >= 1,
           '"constantly" filter shows ≥ 1 match: ' + matched.count);
    assert(matched.allContainIdentity,
           'every visible entity-item contains "constantly"');

    // ===================================================================
    // Phase C: case-insensitive — same query in caps.
    // ===================================================================
    await page.fill('#search-input', 'IDENTITY');
    // Same narrowing predicate — uppercase should match equivalently.
    await page.waitForFunction(
      (b) => {
        const items = Array.from(document.querySelectorAll('.entity-item'));
        if (items.length < 1 || items.length > b) return false;
        return items.every((el) => /constantly/i.test(el.textContent || ''));
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
      null,
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

    // ===================================================================
    // Phase F: a graph refresh racing a lazy namespace load. `initGraph`
    // resets the fn cache and awaits the tree; a `loadNamespaceFns` that
    // was already in flight lands in that window, marks its namespace
    // loaded and syncs its rows — into the graphData that is about to be
    // replaced. Unless the fresh shell is hydrated from the cache, the
    // tree then renders that namespace as loaded-with-no-leaves and nothing
    // refetches it: an expanded namespace with zero rows (the 2026-09-19
    // flake — twice in one day, green on retry, because the ordering is
    // the network's). The ordering is pinned here by holding the tree
    // response back, so the leaves always land inside the window.
    // ===================================================================
    await page.route('**/api/graph/entities?scope=tree*', async (route) => {
      await new Promise((r) => setTimeout(r, 400));
      await route.continue();
    });
    const race = await page.evaluate(async () => {
      const fn = lookups.fnMap.get(selectedFnId);
      const nsId = fn ? (fn['namespace-id'] || '') : '';
      const nsPath = lookups.nsPathMap.get(nsId);
      const out = [];
      for (let i = 0; i < 2; i++) {
        _loadedNamespaceIds.delete(nsId);           // force a real leaf fetch …
        const leaves = loadNamespaceFns(nsId);      // … in flight …
        const refresh = initGraph();                // … when the refresh resets the cache
        await leaves;
        await refresh;
        await new Promise((r) => requestAnimationFrame(() => r()));
        const grp = document.querySelector('.ns-children[data-ns-children="' + nsPath + '"]');
        out.push({
          loaded: _loadedNamespaceIds.has(nsId),
          leaves: grp ? grp.querySelectorAll('.entity-item').length : -1,
          inGraph: graphData.fns.filter((f) => (f['namespace-id'] || '') === nsId).length,
        });
      }
      return {nsPath, rounds: out};
    });
    await page.unroute('**/api/graph/entities?scope=tree*');
    for (const [i, r] of race.rounds.entries()) {
      assert(r.loaded, 'round ' + i + ': the namespace is marked loaded after the race');
      assert(r.inGraph >= 1,
             'round ' + i + ': the refreshed graph carries the namespace rows that landed mid-refresh ('
             + r.inGraph + ')');
      assert(r.leaves >= 1,
             'round ' + i + ': the expanded namespace ' + race.nsPath
             + ' shows its leaves right after the racing refresh (got ' + r.leaves + ')');
    }

    console.log('✓ sidebar filter verified — match / case-insensitive / no-match / clear / refresh race');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
