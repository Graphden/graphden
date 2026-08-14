// Publish-a-namespace e2e — the ⬆ namespace row-action, single-tenant.
//
// Publishing moved OFF the packages panel (packages spec §3, 87565ee6):
// it is an AUTHORING act on the thing you built, so it lives on the
// namespace row — a ⬆ button that opens `#gd-nspub-pop` (name pre-filled
// from the ns tail, version input), which POSTs JSON
// {name, version, ns-root} to /api/packages/publish and renders the
// {ok, fn-count} result client-side. The old panel `<form hx-post>` is
// deliberately not surfaced anymore (its POST route stays live at the
// API level only), so this file drives the SHIPPED path end to end:
// expand the tree → ⬆ on app.contact-demo → fill → Publish → result
// note → the registry (server-side truth) carries the new version.
//
// Publish only exports the namespace + writes an immutable
// :package-version row — it does NOT materialise fns into the graph
// (only install/fork do), so there are no recompile waits and cleanup
// is a single branch DELETE (package-version rows are branch-scoped;
// see edit-packages-panel.test.js for the long form of that argument).

const {chromium} = require('playwright');
const {assert, newContext, nodeApi, nodeApiJson, waitForServerHealthy} =
  require('./edit-test-helpers');


const RUN_ID = process.pid.toString(36) + Date.now().toString(36);
const PKG = 'pub-form-e2e-' + RUN_ID;
const BRANCH = 'pubf-e2e-' + RUN_ID;
const BH = {'X-Graphden-Branch': BRANCH};


(async () => {
  await nodeApiJson('POST', '/api/branches', {name: BRANCH});

  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('  (console.error: ' + m.text().slice(0, 160) + ')');
  });
  console.log('edit-packages-publish-form — ⬆ ns row-action publish, single-tenant');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/?branch=' + encodeURIComponent(BRANCH));
    await page.evaluate(() => document.body.classList.remove('sidebar-collapsed'));

    // The lazy tree ships namespaces up front — expand `app` so the
    // app.contact-demo row (the canonical always-present fixture ns,
    // documented intent in its fns.edn header) is in the DOM.
    await page.waitForSelector('[data-ns-path="app"] .ns-label', {timeout: 15000});
    await page.click('[data-ns-path="app"] .ns-label');
    await page.waitForSelector('[data-ns-path="app.contact-demo"]', {timeout: 15000});

    // ⬆ is a row-action (hover-revealed) — fire its listener directly;
    // the popover anchors on the button either way.
    const hasBtn = await page.evaluate(() => {
      const row = document.querySelector('[data-ns-path="app.contact-demo"]');
      const btn = row && row.querySelector('.ns-publish-btn');
      if (btn) btn.click();
      return !!btn;
    });
    assert(hasBtn, 'the app.contact-demo row offers the ⬆ publish action');

    await page.waitForSelector('#gd-nspub-pop', {timeout: 5000});
    const prefill = await page.evaluate(() => ({
      sub: document.querySelector('#gd-nspub-pop .gd-nspub-sub')?.textContent,
      name: document.querySelector('#gd-nspub-name')?.value,
      version: document.querySelector('#gd-nspub-version')?.value,
    }));
    assert(prefill.sub === 'app.contact-demo',
           'popover cites the namespace: ' + prefill.sub);
    assert(prefill.name === 'contact-demo',
           'package name pre-fills from the ns tail: ' + prefill.name);
    assert(prefill.version === '1.0.0', 'version defaults to 1.0.0');

    await page.fill('#gd-nspub-name', PKG);
    await page.fill('#gd-nspub-version', '1.0.0');
    // The publish is SYNCHRONOUS whole-package work server-side and can
    // queue behind a full-graph recompile a PREVIOUS sweep file kicked
    // off — absorb any in-flight stall BEFORE the click (same pattern
    // service-lifecycle uses for its Save).
    await waitForServerHealthy();
    await page.click('#gd-nspub-go');

    // The client renders the {ok, fn-count} result into the popover.
    // 60s: the export is synchronous server-side and its latency swings
    // with the executor's load window (measured 17s..30s+ across gate
    // runs); the poll returns the moment the note lands.
    await page.waitForFunction((pkg) => {
      const r = document.querySelector('#gd-nspub-result');
      return r && r.className.includes('packages-fork-ok')
          && r.textContent.includes('Published ' + pkg + '@1.0.0');
    }, PKG, {timeout: 60000, polling: 150});
    const note = await page.evaluate(
      () => document.querySelector('#gd-nspub-result')?.textContent || '');
    assert(/\(\d+ fns\)/.test(note),
           'result note carries the fn-count: ' + note);

    // Server-side truth, not just DOM: the registry on this branch
    // carries the published row (the panel partial's browse list offers
    // it to install).
    const partial = await nodeApi('GET', '/partials/packages-panel', undefined, BH);
    assert(partial.ok, 'GET /partials/packages-panel → 200 (got ' + partial.status + ')');
    const html = await partial.text();
    assert(html.includes(PKG), 'server-rendered panel mentions the package');
    assert(html.includes('1.0.0'), 'server-rendered panel mentions the version');

    console.log('PASS edit-packages-publish-form');
  } catch (e) {
    console.error('FAIL edit-packages-publish-form:', e.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
    const r = await nodeApi('DELETE', '/api/branches/' + encodeURIComponent(BRANCH));
    if (!r.ok && r.status !== 404) {
      console.error('cleanup: branch delete → HTTP ' + r.status);
      process.exitCode = 1;
    }
  }
})();
