// Types-lens visibility e2e — sidebar kind filter, `types` chip.
//
// Regression guard for the "types lens shows only (primitives)" bug:
// namespace leaves lazy-load on expand, and under an active lens
// nodeShouldShow hides unloaded namespaces — so before the per-ns
// `:type-count` landed in the `:tree` counts payload, every
// type-bearing namespace (web.ring-adapter, core.refinements, …)
// vanished from the types lens until manually expanded once.
//
// Verifies:
//   • With the `types` lens active and NOTHING expanded, at least one
//     real (non-primitives) namespace header stays visible.
//   • The lens is still a filter: a namespace with zero type-rows is
//     hidden.
//   • Expanding web.ring-adapter under the lens lazy-loads its leaves
//     and shows ONLY its type-rows (ring-request-shape /
//     security-headers-shape); composed fn-defs stay hidden.
//   • The "nothing matches" hint does not show.
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

    console.log('PASS');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    await browser.close();
    process.exit(1);
  }
})();
