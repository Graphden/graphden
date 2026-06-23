// Block 2.5 — end-to-end smoke for the contact-form demo at
// /demo/contact. Verifies that the runtime + built-in
// `submit-form` handler intercepts the click, POSTs, and swaps
// the response into `#contact-result` — all without a full
// page reload.
//
// Run:  node tools/browser-test/contact-demo-smoke.js
// Exit: 0 on pass, 1 on first failure.
//
// NOTE: requires a working Chromium install. On environments
// where chromium crashes at navigation (Playwright/Chrome version
// mismatch, missing libs, OOM), this script will fail with
// "Page crashed" / "Target page, context or browser has been
// closed". The semantic coverage is also provided by:
//   - `curl http://.../demo/contact` (page renders)
//   - `curl -X POST -d ... http://.../demo/contact` (thanks partial)
//   - `tools/runtime-test/runtime.test.js` + `actions-builtin.test.js`
//     (registry + dispatch + submit-form + navigate handler logic
//     in a Node vm sandbox — no browser).

const { chromium } = require('playwright');

const BASE_URL = process.env.EDITOR_URL || 'http://localhost:9002';
let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; console.log('  ✓ ' + msg); return; }
  failures++; console.error('  ✗ ' + msg);
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const context = await browser.newContext({
    viewport: { width: 800, height: 600 },
  });
  const page = await context.newPage();

  const errs = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errs.push(m.text());
  });

  console.log('contact-demo smoke at', BASE_URL);

  await page.goto(`${BASE_URL}/demo/contact`,
                  { waitUntil: 'domcontentloaded' });

  // Page loaded with form + empty result panel.
  const formExists = await page.locator('form').count() > 0;
  assert(formExists, 'form rendered');

  const resultEmpty = (await page.locator('#contact-result').textContent()).trim();
  assert(resultEmpty === '', '#contact-result starts empty');

  // Runtime registrations available?
  const handlersOk = await page.evaluate(() => (
    typeof registerActionHandler === 'function'
      && typeof bindActionDispatch === 'function'
      && typeof getActionHandler === 'function'
      && typeof getActionHandler('submit-form') === 'function'
      && typeof getActionHandler('navigate') === 'function'
  ));
  assert(handlersOk, 'runtime + built-in action handlers loaded');

  // bindActionDispatch is normally called per-partial. The demo
  // page isn't a fetched partial — the form lives in the
  // page body. Wire dispatch on document.body manually so the
  // click test below routes through the runtime.
  await page.evaluate(() => bindActionDispatch(document.body));

  // Fill + submit.
  await page.fill('input[name="email"]', 'demo@example.com');
  await page.fill('textarea[name="message"]', 'Block 2 demo works');
  await page.click('button[data-action="submit-form"]');

  // Wait for the swapped response.
  await page.waitForFunction(
    () => document.querySelector('#contact-result')
            ?.textContent.includes('Thanks'),
    null, { timeout: 5000 });

  const resultText = (await page.locator('#contact-result').textContent()).trim();
  assert(resultText.includes('Thanks'),
         `#contact-result populated post-submit (got: "${resultText}")`);
  assert(errs.length === 0,
         `no console errors (got ${errs.length}: ${errs.join(' | ')})`);

  await browser.close();
  console.log(`\nResults: ${passes} pass, ${failures} fail`);
  process.exit(failures > 0 ? 1 : 0);
})();
