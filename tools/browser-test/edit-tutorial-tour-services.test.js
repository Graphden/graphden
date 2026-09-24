// Drift guard for the lesson-38 interactive tour (services talking to
// services). Walks the tour by doing the real UI actions — Extend a response
// and bind its body, Extend a listener and hand it that response as the
// handler plus a port, make it a service (ENABLED — the reconciler starts
// it), Extend a consumer,
// bind the listener through the picker on a DEEP `:fn-ref` placeholder, Run,
// then delete the service — so a renamed class or a changed flow fails HERE,
// not on a visitor. Runs standalone (`./run-edit-tests.sh` picks up every
// `edit-*.test.js`).
'use strict';

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  extendViaRowActions, finishAndDelete, bindNamedPlaceholder, bindPlaceholderOn,
  runWithEffectAck, waitUntil, tourWhere,
} = require('./tutorial-tour-helpers');

const SERVER_TRIG = '.node-overlay[data-fn-name="tutorial-server"] .ancestor-line[data-level="0"] button.more-actions-trigger';

// ⋯ → ⚙ on tutorial-server's OWN row (level 0 — the use-site rows of the
// wrap / response cards carry a ⋯ too, without a ⚙). A blocked gear is
// aria-disabled, not `button.disabled`, so assert the enabled shape.
async function openServiceGear(page) {
  await page.waitForSelector(SERVER_TRIG, {timeout: 30000});
  // The popover's buttons are a server partial (hx-trigger=load): wait for
  // the ⚙ itself, not for the first button to render. A re-opened popover
  // can come up with its partial never loading (the lesson-29 flake shape),
  // so a quiet attempt is closed and the ⋯ pressed once more.
  const gearShown = () => Array.from(document.querySelectorAll('.row-actions-popover button'))
    .some((b) => b.textContent.trim() === '⚙');
  let seen = false;
  for (let attempt = 0; attempt < 3 && !seen; attempt++) {
    if (attempt > 0) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(800);
    }
    await page.dispatchEvent(SERVER_TRIG, 'mousedown');
    try {
      await page.waitForFunction(gearShown, null, {timeout: 8000, polling: 100});
      seen = true;
    } catch (_) { /* retry */ }
  }
  assert(seen, 'the ⚙ button is in the row-actions popover');
  const gear = await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.row-actions-popover button'))
      .find((b) => b.textContent.trim() === '⚙');
    const blocked = btn.disabled || btn.getAttribute('aria-disabled') === 'true'
      || btn.className.includes('action-icon-disabled');
    if (!blocked) btn.click();
    return {blocked, title: btn.getAttribute('title')};
  });
  assert(!gear.blocked, 'the ⚙ is enabled once every free arg is bound (got: ' + gear.title + ')');
  await page.waitForSelector('.service-popover', {timeout: 20000});
}

async function serviceRowExists(page) {
  return page.evaluate(async () => {
    const d = await (await window.authFetch('/api/services')).json();
    return (d.services || []).some((s) => s['fn-name'] === 'tutorial-server');
  });
}

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-services — lesson 38');
  let failed = false;
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    await hardCleanup(page);

    await page.goto(BASE + '/?tutorial=38');
    await waitTourTitle(page, 'Two services, one graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 38 Next');

    // ---- inside out: the answer, then the listener ----
    await waitTourTitle(page, 'The answer');
    await filterAndSelect(page, 'text-ok-response', 'text-ok-response');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-hello', 'text-ok-response');
    await waitTourTitle(page, 'tutorial-hello is open', 150000);
    await waitTourTitle(page, 'What it says', 150000);
    await bindPlaceholderOn(page, 'tutorial-hello', 'body', 'literal', 'hello');
    await waitTourTitle(page, 'A listener', 150000);
    await filterAndSelect(page, 'http-server', 'http-server');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-server', 'http-server');
    await waitTourTitle(page, 'tutorial-server is open', 150000);
    await waitTourTitle(page, 'Give it the answer', 150000);
    // A callable slot — the `+` opens the picker straight away. The reader's
    // own fn must be listed Compatible: a nullary callee fits a 1-arg slot.
    await bindPlaceholderOn(page, 'tutorial-server', 'handler', 'fn-ref', 'tutorial-hello');
    await waitTourTitle(page, 'Where it listens', 150000);
    await bindPlaceholderOn(page, 'tutorial-server', 'port', 'literal', '9101');

    // ---- the row, ENABLED ----
    await waitTourTitle(page, 'Make it a service', 150000);
    await openServiceGear(page);
    await waitTourTitle(page, 'Start it', 150000);
    await page.waitForSelector('.service-popover-enabled', {timeout: 15000});
    assert(await page.$eval('.service-popover-enabled', (b) => b.checked),
      'the Enabled box is ticked by default');
    await page.evaluate(() => document.querySelector('.service-popover-save-btn').click());
    await page.waitForFunction(() => {
      const el = document.querySelector('.service-popover');
      return !el || el.style.display === 'none';
    }, null, {timeout: 20000, polling: 200});
    assert(await serviceRowExists(page), 'the :service row exists after Create & reconcile');
    assert(await clickTourButton(page, 'Next'), 'lesson 38 started Next');

    // ---- the consumer ----
    await waitTourTitle(page, 'A consumer', 150000);
    await filterAndSelect(page, 'service-get', 'service-get');
    await waitTourTitle(page, 'Extend it', 150000);
    await extendViaRowActions(page, 'tutorial-fetch', 'service-get');
    await waitTourTitle(page, 'tutorial-fetch is open', 150000);
    await waitTourTitle(page, 'Which service', 150000);
    await filterAndSelect(page, 'tutorial-fetch', 'tutorial-fetch');
    // `service` and `path` are DEEP holes of the template (they live on
    // :service-endpoint and the URL join), drawn on the extension's own
    // card as dashed placeholders; `service` is a `:fn-ref` slot — a
    // scalar to the chooser, so the literal/fn-ref choice appears, and
    // every fn is a candidate for an identity slot.
    await bindNamedPlaceholder(page, 'service', 'fn-ref', 'tutorial-server');
    await waitTourTitle(page, 'Which path', 150000);
    await bindNamedPlaceholder(page, 'path', 'literal', '/hello');

    await waitTourTitle(page, 'Ask it', 150000);
    // Pinned to tutorial-fetch's own row: tutorial-server sits on this canvas
    // as the bound service, and its use-site ⋯ (no ▶ Run) can be the first
    // trigger in the DOM.
    await runWithEffectAck(page, undefined, 'tutorial-fetch');
    // The listener answers: the result pane shows the 200 and `hello`.
    try {
      await page.waitForFunction(() => {
        const p = document.querySelector('.execute-popover.visible');
        const t = p ? (p.textContent || '') : '';
        return /hello/.test(t) && /200/.test(t);
      }, null, {timeout: 60000, polling: 250});
    } catch (e) {
      const dump = await page.evaluate(() => {
        const p = document.querySelector('.execute-popover.visible');
        return p ? (p.textContent || '').replace(/\s+/g, ' ').slice(0, 1500) : '(no visible run pane)';
      });
      console.log('  run pane text: ' + dump);
      throw e;
    }
    assert(await clickTourButton(page, 'Next'), 'lesson 38 run Next');

    // ---- stop it ----
    await waitTourTitle(page, 'Back to the listener', 150000);
    await filterAndSelect(page, 'tutorial-server', 'tutorial-server');
    await waitTourTitle(page, 'Stop it', 150000);
    await openServiceGear(page);
    await page.waitForSelector('.service-popover-delete-btn', {timeout: 20000});
    await page.evaluate(() => document.querySelector('.service-popover-delete-btn').click());
    // `waitUntil`, not `waitForFunction`: Playwright does not await an async
    // predicate — the pending Promise is truthy and the wait returns at once.
    assert(await waitUntil(page, async () => {
      const d = await (await window.authFetch('/api/services')).json();
      return !(d.services || []).some((s) => s['fn-name'] === 'tutorial-server');
    }, null, 20000), 'the :service row is gone after Delete service');
    assert(await clickTourButton(page, 'Next'), 'lesson 38 stopped Next');
    await waitTourTitle(page, "That’s naming a service", 150000);
    await finishAndDelete(page);
    console.log('  lesson 38: walked + cleaned (answer + listener built, served, called by name, stopped, deleted)');
  } catch (e) {
    failed = true;
    console.log('FAIL: ' + (e && e.stack ? e.stack : e));
    console.log('  tour at failure: ' + await tourWhere(page));
  } finally {
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
