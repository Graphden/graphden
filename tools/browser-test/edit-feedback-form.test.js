// Feedback ("Report a problem") — the form + both /api/feedback routes.
//
// Pins the whole v1 contract against a live stack:
//   * GET /api/feedback/config answers {url: <non-empty>} (env unset ⇒
//     the official-intake default) and the editor resolved it at boot.
//   * The form opens (window.openFeedbackForm), carries the honeypot
//     field OUT of layout, and closes on Cancel.
//   * POST /api/feedback on an UNARMED instance (no
//     GRAPHDEN_FEEDBACK_INTAKE on the test stack) answers
//     {ok:false, error:"disabled"} — and the response carries the
//     Access-Control-Allow-Origin the cross-origin form depends on.
//
// Run from this directory:  node edit-feedback-form.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('feedback-form — config probe, form lifecycle, unarmed intake');
  try {
    // --- config probe ---
    const cfg = await (await fetch(BASE + '/api/feedback/config')).json();
    assert(typeof cfg.url === 'string' && cfg.url.length > 0,
           '/api/feedback/config announces a non-empty intake url: '
           + JSON.stringify(cfg));

    // --- unarmed intake: polite refusal + CORS header ---
    const resp = await fetch(BASE + '/api/feedback', {
      method: 'POST',
      headers: {'Content-Type': 'text/plain;charset=UTF-8'},
      body: JSON.stringify({category: 'bug', text: 'e2e probe'}),
    });
    assert(resp.headers.get('access-control-allow-origin') === '*',
           'intake response carries Access-Control-Allow-Origin: *');
    const j = await resp.json();
    assert(j.ok === false && j.error === 'disabled',
           'unarmed instance answers {ok:false, error:"disabled"}: '
           + JSON.stringify(j));

    // --- the form in the editor ---
    const opened = await page.evaluate(() => {
      if (typeof window.openFeedbackForm !== 'function') return 'no-fn';
      window.openFeedbackForm();
      const card = document.querySelector('.feedback-popover');
      if (!card) return 'no-card';
      const honeypot = card.querySelector('.feedback-honeypot input');
      if (!honeypot) return 'no-honeypot';
      if (honeypot.offsetParent !== null) return 'honeypot-visible';
      return 'ok';
    });
    assert(opened === 'ok',
           'form opens with an out-of-layout honeypot (got: ' + opened + ')');

    const closed = await page.evaluate(() => {
      document.querySelector('.feedback-cancel').click();
      return !document.getElementById('feedback-backdrop');
    });
    assert(closed, 'Cancel removes the form');

    // The shell-menu entry point exists (button text, not position).
    const menuHasItem = await page.evaluate(() => {
      if (typeof openShellMenu !== 'function') return 'no-menu-fn';
      openShellMenu();
      const items = Array.from(document.querySelectorAll('#auth-popover .auth-menu-item'));
      const hit = items.some((el) => el.textContent.trim() === 'Report a problem');
      if (typeof closeAuthPopover === 'function') closeAuthPopover();
      return hit ? 'ok' : 'missing';
    });
    assert(menuHasItem === 'ok',
           'shell menu carries "Report a problem" (got: ' + menuHasItem + ')');
  } finally {
    await browser.close();
  }
  console.log('feedback-form — PASS');
})().catch(e => {
  console.error('feedback-form — FAIL:', e.message);
  process.exit(1);
});
