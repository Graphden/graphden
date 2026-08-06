// Real HTMX form-submit e2e on the SINGLE-TENANT stack.
//
// The packages panel's
// "Publish a namespace" form is a genuine `<form hx-post>` served on the
// plain stack, so this file drives it end-to-end through HTMX itself:
// fill inputs → submit button → HTMX intercepts, form-encodes, POSTs
// with the Authorization header from the htmx:configRequest bridge →
// server publishes + re-renders → outerHTML swap of
// `[data-packages-panel]` → the browse list shows the new version.
//
// Publish only exports the namespace + writes an immutable
// :package-version row — it does NOT materialise fns into the graph
// (only install/fork do), so there are no recompile waits and cleanup
// is a single branch DELETE (pins + package-version rows are
// branch-scoped; see edit-packages-panel.test.js for the long form of
// that argument).

const {chromium} = require('playwright');
const {assert, newContext, nodeApi, nodeApiJson} = require('./edit-test-helpers');


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
  console.log('edit-packages-publish-form — real hx-post form submit, single-tenant');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/?branch=' + encodeURIComponent(BRANCH));
    await page.evaluate(() => document.body.classList.remove('sidebar-collapsed'));
    await page.waitForSelector('.sidebar-packages', {timeout: 15000});
    await page.waitForFunction(() => {
      const sec = document.querySelector('.sidebar-packages');
      return sec && sec.querySelector('[data-packages-panel]');
    }, null, {timeout: 15000, polling: 100});

    // ===================================================================
    // Drive the publish form THROUGH HTMX — no JS fetch, no node API.
    // ===================================================================
    await page.evaluate(() => {
      const form = document.querySelector('.packages-publish-form');
      const details = form && form.closest('details');
      if (details) details.open = true;
    });
    await page.fill('.packages-publish-form input[name="name"]', PKG);
    await page.fill('.packages-publish-form input[name="version"]', '1.0.0');
    // app.contact-demo is the canonical always-present fixture namespace
    // (documented intent in its fns.edn header; the panel lifecycle e2e
    // publishes it too).
    await page.fill('.packages-publish-form input[name="ns-root"]', 'app.contact-demo');
    // A REAL click on the submit button — HTMX owns everything after it.
    await page.click('.packages-publish-form button');

    // The response swaps the WHOLE [data-packages-panel] via outerHTML.
    // The swapped-in browse list must now offer the just-published
    // version (its Install button's hx-post carries name+version).
    await page.waitForFunction((pkg) => {
      const sec = document.querySelector('.sidebar-packages');
      if (!sec || !sec.querySelector('[data-packages-panel]')) return false;
      const posts = [...sec.querySelectorAll('.packages-install-btn')]
        .map((b) => b.getAttribute('hx-post') || '');
      return posts.some((p) => p.includes('name=' + pkg) && p.includes('version=1.0.0'));
    }, PKG, {timeout: 30000, polling: 150});
    assert(true, 'panel outerHTML swap landed and lists ' + PKG + '@1.0.0');

    // Server-side truth, not just DOM: the freshly-rendered partial on
    // this branch carries the published row.
    const partial = await nodeApi('GET', '/partials/packages-panel', undefined, BH);
    assert(partial.ok, 'GET /partials/packages-panel → 200 (got ' + partial.status + ')');
    const html = await partial.text();
    assert(html.includes(PKG), 'server-rendered panel mentions the package');
    assert(html.includes('1.0.0'), 'server-rendered panel mentions the version');

    // The form was form-encoded by HTMX itself — a blank-fields
    // regression (the :lambda-params wire-break class) would have
    // published an empty-named package or 4xx'd; both are caught above.

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
