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
    await page.waitForSelector('#gd-rail .gd-rail-btn[data-surface="build"]', {timeout: 30000});
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

    // Run surface: the launch button is an EXECUTE path separate from the
    // row-actions run-fn trigger; it must hide, and say why.
    await page.click('#gd-rail .gd-rail-btn[data-surface="run"]');
    await page.waitForSelector('#gd-run-body', {timeout: 10000});
    assert(await hidden('#gd-run-launch'), 'Run-surface launch hidden under gd-no-execute');
    assert(await visible('.gd-run-noexec'), 'Run surface shows the no-execute hint');

    // Branch popover: create/merge/delete (writes) hidden; the list +
    // switch/diff (reads) stay usable.
    await page.click('#gd-rail .gd-rail-btn[data-surface="build"]');
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
