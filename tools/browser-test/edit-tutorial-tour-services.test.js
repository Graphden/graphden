// Drift guard for the lesson-35 interactive tour (services talking to
// services). Walks the tour by doing the real UI actions — Extend, bind a
// fn-ref through the picker on a DEEP `:fn-ref` placeholder, bind a literal
// on a deep `path` placeholder, Run — so
// a renamed class or a changed flow fails HERE, not on a visitor. Runs
// standalone (`./run-edit-tests.sh` picks up every `edit-*.test.js`).
'use strict';

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  extendViaRowActions, finishAndDelete, bindNamedPlaceholder, runWithEffectAck,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-services — lesson 35');
  let failed = false;
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    await hardCleanup(page);

    await page.goto(BASE + '/?tutorial=35');
    await waitTourTitle(page, 'Two services, one graph', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 35 Next');

    await waitTourTitle(page, 'A consumer');
    await filterAndSelect(page, 'service-get', 'service-get');
    await extendViaRowActions(page, 'tutorial-fetch', 'service-get');

    await waitTourTitle(page, 'Which service', 150000);
    await filterAndSelect(page, 'tutorial-fetch', 'tutorial-fetch');
    // `service` and `path` are DEEP holes of the template (they live on
    // :service-endpoint and the URL join), drawn on the extension's own
    // card as dashed placeholders; `service` is a `:fn-ref` slot — a
    // scalar to the chooser, so the literal/fn-ref choice appears, and
    // every fn is a candidate for an identity slot.
    await bindNamedPlaceholder(page, 'service', 'fn-ref', 'web-server');

    await waitTourTitle(page, 'Which path', 150000);
    // The slot is :text now — the literal editor takes the raw text.
    await bindNamedPlaceholder(page, 'path', 'literal', '/version');

    await waitTourTitle(page, 'Ask it', 150000);
    await runWithEffectAck(page);
    // The editor's own /version answers: the result pane shows the 200
    // and the three build hashes.
    await page.waitForFunction(() => {
      const p = document.querySelector('.execute-popover.visible');
      const t = p ? (p.textContent || '') : '';
      return /frontend/.test(t) && /200/.test(t);
    }, null, {timeout: 60000, polling: 250});
    assert(await clickTourButton(page, 'Next'), 'lesson 35 run Next');
    await waitTourTitle(page, 'Your own services', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 35 explain Next');
    await waitTourTitle(page, "That's naming a service", 150000);
    await finishAndDelete(page);
    console.log('  lesson 35: walked + cleaned (consumer created, called web-server /version, deleted)');
  } catch (e) {
    failed = true;
    console.log('FAIL: ' + (e && e.stack ? e.stack : e));
  } finally {
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
