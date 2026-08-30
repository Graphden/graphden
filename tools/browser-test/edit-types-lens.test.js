// Lens visibility e2e — sidebar kind filter chips vs LAZY-loaded tree.
//
// Regression guard for the "lens shows only what happened to be
// loaded" bug family: namespace leaves lazy-load on expand, and under
// an active lens nodeShouldShow hides unloaded namespaces — so before
// the per-ns kind signals (`:type-count` / `:fn-count` in the `:tree`
// counts payload, `namespace-id` on /api/services rows), the types
// lens showed only the (primitives) bucket, the services lens showed
// an empty tree under a chip that said "1", and the fn lens hid every
// never-expanded namespace. secrets/apps ride the same generalized
// nsHoldsLensKind path the services phase proves (their signals are
// Set-of-namespace-ids built from their list payloads too).
//
// Verifies, with NOTHING expanded:
//   • types: type-bearing namespaces visible; expanding
//     web.ring-adapter shows ONLY its type-rows; type-less namespaces
//     stay hidden; no "nothing matches" hint.
//   • fn: core/storage (never expanded, plain-fn-bearing) visible.
//   • services: the namespace of the web-server service fn is visible
//     and the "nothing matches" hint does not show.
//
// Run from this directory:  node edit-types-lens.test.js
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
  console.log('edit-types-lens — unloaded type-bearing namespaces stay visible');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/');

    await page.waitForSelector('#kind-filters [data-kind="types"]', {timeout: 15000});
    await page.click('#kind-filters [data-kind="types"]');

    // Phase A: with no namespace ever expanded, the lens must still
    // surface the top-level namespaces whose subtrees hold type-rows.
    await page.waitForFunction(() => {
      const headers = [...document.querySelectorAll('.ns-header[data-ns-path]')];
      return headers.some((h) => !h.hidden);
    }, null, {timeout: 15000});
    const topLevel = await page.evaluate(() => {
      return [...document.querySelectorAll('.ns-header[data-ns-path]')]
        .filter((h) => !h.hidden)
        .map((h) => h.dataset.nsPath);
    });
    assert(topLevel.includes('web'),
           'web (holds ring shapes / hiccup types) visible unexpanded: ' + topLevel.join(','));
    assert(topLevel.includes('core'),
           'core (holds refinements) visible unexpanded');
    const hint = await page.evaluate(() => {
      const el = document.querySelector('.lens-empty-hint');
      return el ? !el.hidden : false;
    });
    assert(!hint, 'no "nothing matches" hint while type namespaces are visible');

    // Phase B: expand web → ring-adapter; only its type-rows show.
    const expand = async (path) => {
      await page.evaluate((p) => {
        const h = [...document.querySelectorAll('.ns-header')]
          .find((x) => x.dataset.nsPath === p);
        h?.querySelector('.ns-label')?.click();
      }, path);
      await page.waitForFunction((p) => {
        const h = [...document.querySelectorAll('.ns-header')]
          .find((x) => x.dataset.nsPath === p);
        return !!h;
      }, path, {timeout: 15000});
    };
    await expand('web');
    await expand('web.ring-adapter');
    await page.waitForFunction(() => {
      return document.querySelectorAll(
        '.ns-children[data-ns-children="web.ring-adapter"] .entity-item').length > 0;
    }, null, {timeout: 30000});
    const rows = await page.evaluate(() => {
      return [...document.querySelectorAll(
        '.ns-children[data-ns-children="web.ring-adapter"] .entity-item')]
        .map((e) => ({name: e.dataset.fnName || e.textContent.trim(), hidden: e.hidden}));
    });
    const visible = rows.filter((r) => !r.hidden).map((r) => r.name);
    assert(visible.some((n) => n.includes('ring-request-shape')),
           'ring-request-shape visible under the lens: ' + visible.join(','));
    assert(visible.every((n) => n.includes('shape')),
           'only the *-shape type-rows visible; fn-defs filtered out: ' + visible.join(','));
    const hiddenCount = rows.filter((r) => r.hidden).length;
    assert(hiddenCount > 0,
           'the namespace also holds hidden non-type rows (' + hiddenCount + ')');

    // Phase C: the lens is still a filter — a type-less namespace
    // (web.branch-router: fn-defs only) must be hidden.
    const branchRouter = await page.evaluate(() => {
      const h = [...document.querySelectorAll('.ns-header')]
        .find((x) => x.dataset.nsPath === 'web.branch-router');
      return h ? h.hidden : 'absent';
    });
    assert(branchRouter === true || branchRouter === 'absent',
           'web.branch-router (no type-rows) is not surfaced by the lens: ' + branchRouter);

    // Phase D: fn lens — never-expanded plain-fn namespaces stay visible
    // (before `:fn-count` they were hidden until manually expanded).
    const switchLens = async (kind) => {
      await page.click('#kind-filters [data-kind="all"]');
      await page.click(`#kind-filters [data-kind="${kind}"]`);
    };
    await switchLens('fn');
    await page.waitForFunction(() => {
      const vis = [...document.querySelectorAll('.ns-header[data-ns-path]')]
        .filter((h) => !h.hidden).map((h) => h.dataset.nsPath);
      return vis.includes('core') && vis.includes('storage');
    }, null, {timeout: 15000});
    console.log('  ✓ fn lens: core + storage visible unexpanded');

    // Phase E: services lens — the namespace holding the web-server
    // service fn is knowable from /api/services rows' namespace-id.
    await switchLens('services');
    // `app` shows because its DESCENDANT app.server holds the service
    // (child headers only render after the parent expands).
    await page.waitForFunction(() => {
      const vis = [...document.querySelectorAll('.ns-header[data-ns-path]')]
        .filter((h) => !h.hidden).map((h) => h.dataset.nsPath);
      return vis.includes('app');
    }, null, {timeout: 15000});
    await expand('app');
    await page.waitForFunction(() => {
      const vis = [...document.querySelectorAll('.ns-header[data-ns-path]')]
        .filter((h) => !h.hidden).map((h) => h.dataset.nsPath);
      return vis.includes('app.server');
    }, null, {timeout: 15000});
    const svcHint = await page.evaluate(() => {
      const el = document.querySelector('.lens-empty-hint');
      return el ? !el.hidden : false;
    });
    assert(!svcHint, 'services lens: no "nothing matches" hint while app.server is visible');
    console.log('  ✓ services lens: app.server visible unexpanded, no lying hint');

    // ── the "internal N" toggle tells the truth under a lens ──────────
    //
    // The toggle's count was captured at build time and never updated, so
    // a types lens kept advertising "internal 658" on app.branches while
    // hiding every row behind it — a disclosure that could only ever
    // reveal nothing. Now: N is the lens-visible count, and a toggle with
    // nothing to show is hidden with its group. Checked as a sweep over
    // EVERY toggle rather than one namespace, so the invariant holds
    // wherever the group renders.
    await switchLens('types');
    await page.waitForTimeout(700);
    const toggles = await page.evaluate(() => {
      const out = [];
      for (const t of document.querySelectorAll('.ns-internal-toggle')) {
        const holder = t.nextElementSibling;
        if (!holder || !holder.classList.contains('ns-internal-group')) continue;
        const visibleRows = [...holder.children]
          .filter((el) => el.classList.contains('entity-item') && !el.hidden).length;
        const advertised = parseInt((t.textContent.match(/internal (\d+)/) || [])[1], 10);
        out.push({hidden: t.hidden, advertised, visibleRows});
      }
      return out;
    });
    for (const t of toggles) {
      if (t.hidden) {
        assert(t.visibleRows === 0,
               'a hidden internal toggle hides only when the lens left nothing: '
               + JSON.stringify(t));
      } else {
        assert(t.advertised === t.visibleRows,
               'a visible internal toggle advertises the LENS-visible count: '
               + JSON.stringify(t));
        assert(t.visibleRows > 0, 'and never advertises an empty group');
      }
    }
    const anyHidden = toggles.some((t) => t.hidden);
    const anyShown = toggles.some((t) => !t.hidden);
    console.log('  ✓ internal toggles under the types lens: '
                + toggles.length + ' checked (' + (anyHidden ? 'some hidden, ' : '')
                + (anyShown ? 'some shown' : 'none shown') + ')');

    // Clearing the lens restores the full counts.
    await switchLens('all');
    await page.waitForTimeout(700);
    const restored = await page.evaluate(() => {
      const bad = [];
      for (const t of document.querySelectorAll('.ns-internal-toggle')) {
        const holder = t.nextElementSibling;
        if (!holder || !holder.classList.contains('ns-internal-group')) continue;
        const rows = [...holder.children]
          .filter((el) => el.classList.contains('entity-item') && !el.hidden).length;
        const n = parseInt((t.textContent.match(/internal (\d+)/) || [])[1], 10);
        if (t.hidden || n !== rows) bad.push({n, rows, hidden: t.hidden});
      }
      return bad;
    });
    assert(restored.length === 0,
           'clearing the lens restores every toggle: ' + JSON.stringify(restored));
    console.log('  ✓ lens off: every internal toggle back to its full count');

    console.log('PASS');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    await browser.close();
    process.exit(1);
  }
})();
