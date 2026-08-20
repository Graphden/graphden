// A branch can vanish under a tab that is still standing on it: someone
// merges and deletes it elsewhere, or the tour's own "Delete branch &
// return" runs in another window. The stored branch name then rides out on
// every internal call and the server answers 400 "Unknown branch".
//
// Two failures were hiding there (found 2026-08-20 walking the tutorial
// guard), and this pins both:
//
//   1. A PAGE load naming the dead branch answered 400 — the 400 replaced
//      the HTML, so the editor never booted: blank screen, no scripts, no
//      explanation, nothing to click. Navigations now redirect to the same
//      URL minus `?branch=`.
//   2. A tab already open on the branch kept 400ing on every internal call
//      forever. It now drops the stale branch and reloads on the default.
//
// Run from this directory:  node edit-stale-branch-recovery.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

const BRANCH = 'stale-recovery-probe';

(async () => {
  const {browser, page} = await newContext(chromium);
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  let failed = false;
  try {
    await page.goto(BASE + '/');
    await page.waitForFunction(
      () => window.API && typeof authFetch === 'function',
      null, {timeout: 120000, polling: 300});

    // Branch lifecycle through the editor's own authenticated client — the
    // API takes JSON here, not a form body.
    const created = await page.evaluate(async (name) => {
      const r = await authFetch(API.api_branches, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({name, 'base-branch-id': 'main'})});
      return r.status;
    }, BRANCH);
    assert(created === 200, 'probe branch created (got ' + created + ')');

    // ---- case 2: a tab standing ON the branch when it disappears --------
    await page.goto(BASE + '/?branch=' + BRANCH);
    await page.waitForFunction(
      () => window.API && typeof authFetch === 'function',
      null, {timeout: 120000, polling: 300});
    await page.evaluate(async (name) => {
      // Delete it from the DEFAULT branch's context, the way another tab
      // (or the tour's cleanup) would.
      await authFetch(API.api_branches_ref(name),
        {method: 'DELETE', headers: {'X-Graphden-Branch': 'main'}});
    }, BRANCH);
    await page.evaluate(() => {
      fetch('/api/graph/entities?scope=tree').catch(() => {}); // api-url-drift-allow: probing the wrapper, not a UI path
    });
    await page.waitForFunction(() => {
      const onDefault = !new URLSearchParams(location.search).get('branch');
      let stored = 'x';
      try { stored = localStorage.getItem('graphden.branch'); } catch (_) { /* ignore */ }
      return onDefault && !stored;
    }, null, {timeout: 90000, polling: 200});
    assert(true, 'open tab dropped the deleted branch and returned to default');

    await page.waitForFunction(
      () => window.API && typeof authFetch === 'function',
      null, {timeout: 120000, polling: 300});
    const readable = await page.evaluate(async () => {
      const r = await authFetch('/api/graph/entities?scope=tree'); // api-url-drift-allow: status probe
      return r.status;
    });
    assert(readable === 200,
      'graph reads work again after recovery (got ' + readable + ')');

    // ---- case 1: a FRESH navigation naming the (now gone) branch --------
    const resp = await page.goto(BASE + '/?demo=1&branch=' + BRANCH);
    assert(resp.status() === 200,
      'a page load naming a dead branch still serves the editor (got '
      + resp.status() + ')');
    const url = new URL(page.url());
    assert(!url.searchParams.get('branch'),
      'the dead branch was stripped from the URL (got ' + page.url() + ')');
    assert(url.searchParams.get('demo') === '1',
      'other query params survived the redirect (got ' + page.url() + ')');
    await page.waitForFunction(
      () => window.API && typeof authFetch === 'function',
      null, {timeout: 120000, polling: 300});
    assert(true, 'the editor booted on the default branch');

    console.log('✓ stale-branch recovery verified (navigation + open tab)');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
  } finally {
    try {
      await page.evaluate(async (name) => {
        await authFetch(API.api_branches_ref(name),
          {method: 'DELETE', headers: {'X-Graphden-Branch': 'main'}});
      }, BRANCH);
    } catch (_) { /* already gone */ }
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
