// edit-capability-gating — the tenancy addon reports X-Graphden-Capabilities;
// the fetch wrap turns "no write" / "no execute" into the body classes
// gd-no-write / gd-no-execute, and CSS hides the affordances a tenant can't
// use (server-side enforcement is the real gate; this is the matching UX).
// This proves the gate covers the redesign + the previously-ungated paths,
// and that self-hosted (no classes) is unchanged.
//
// Run from this directory:  node edit-capability-gating.test.js
// Points at GRAPHDEN_URL (default http://localhost:9002).
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('edit-capability-gating — write/execute affordances hide under caps');

  const hidden = (sel) => page.evaluate((s) => {
    const el = document.querySelector(s);
    return !el || el.offsetParent === null;
  }, sel);
  const visible = (sel) => page.evaluate((s) => {
    const el = document.querySelector(s);
    return !!el && el.offsetParent !== null;
  }, sel);
  const setCaps = (write, exec) => page.evaluate(([w, e]) => {
    document.body.classList.toggle('gd-no-write', !w);
    document.body.classList.toggle('gd-no-execute', !e);
  }, [write, exec]);

  try {
    await page.goto(BASE + '/#web-server');
    await page.waitForSelector('#gd-brand-home', {timeout: 30000});
    await page.waitForFunction(() => !!document.querySelector('.node-overlay'),
                              null, {timeout: 30000, polling: 100});

    // --- Baseline: self-hosted (no caps) shows the write affordance ---
    assert(await visible('#create-root-ns-btn'),
           'create-root-ns visible without caps (self-hosted unchanged)');

    // --- Read-only + no-execute tenant ---
    await setCaps(false, false);

    // Explorer create (a write) — hidden. This is one of the paths the
    // [data-action] row-actions gate never covered.
    assert(await hidden('#create-root-ns-btn'),
           'create-root-ns hidden under gd-no-write');

    // Execute gating: running a fn is the ▶ node/card action (the Run rail
    // surface was removed as a duplicate). Verify the CSS execute-gate hides
    // that affordance under gd-no-execute — on a synthetic node so the test
    // doesn't depend on a particular fn's row-actions popover being open.
    const runActionHidden = await page.evaluate(() => {
      const el = document.createElement('button');
      el.setAttribute('data-action', 'run-fn');
      document.body.appendChild(el);
      const h = getComputedStyle(el).display === 'none';
      el.remove();
      return h;
    });
    assert(runActionHidden, 'run-fn node action hidden under gd-no-execute');

    // Branch popover: create/merge/delete (writes) hidden; the list +
    // switch/diff (reads) stay usable.
    await page.evaluate(() => window.gdShellGoHome());
    await page.click('#branch-chip-btn');
    await page.waitForSelector('#branch-popover .branch-popover-list', {timeout: 10000});
    assert(await hidden('.branch-popover-create'),
           'branch create area hidden under gd-no-write');
    assert(await visible('.branch-popover-list'),
           'branch list (read) still shown — you can still switch/diff');

    // --- Caps restored: everything comes back (proves it's purely additive) ---
    await page.evaluate(() =>
      document.body.classList.remove('gd-no-write', 'gd-no-execute'));
    assert(await visible('#create-root-ns-btn'),
           'affordances return once capabilities are restored');

    console.log('capability-gating — PASS');
  } catch (e) {
    console.error('✗ test failed:', e.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
