// Lesson 37 — the Marketplace: theme saved + shared, a key rebound, a review.
//
// Part of the interactive-tutorial drift guard: walks every step of the
// lesson by doing the real UI actions (docs/MARKETPLACE.md; the lesson is
// docs/tutorial/37-marketplace-themes-keymaps.md), so a renamed class or a
// changed flow fails HERE, not on a reader. Needs the registry package (the
// e2e stack has it); the theme version the lesson publishes is withdrawn by
// the tour's own end-of-lesson cleanup, and the preferences are reset here.
//
// Run from this directory:  node edit-tutorial-tour-market.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, nodeApi} = require('./edit-test-helpers');
const {waitTourTitle, clickTourButton, finishAndDelete} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-market — lesson 37 walked end-to-end');
  let failed = false;
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    await page.goto(BASE + '/?tutorial=37');
    await waitTourTitle(page, 'Make it yours, then share it', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 37 Next');

    // --- Open the theme editor: account menu → Settings → Customize…
    await waitTourTitle(page, 'Open the theme editor');
    await page.evaluate(() => window.gdShellSurface('settings'));
    await page.waitForSelector('#gd-theme-edit', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-theme-edit').click());
    await waitTourTitle(page, 'Pick a paper', 60000);
    console.log('  step 2: theme editor open');

    // --- Pick a paper: drive the colour input the way a picker would.
    await page.evaluate(() => {
      const sw = document.querySelector('#gd-theme-editor .gd-theme-swatch[data-token="--gd-paper"]');
      sw.value = '#223344';
      sw.dispatchEvent(new Event('input', {bubbles: true}));
    });
    await waitTourTitle(page, 'Save it as a version', 60000);
    console.log('  step 3: paper picked, custom theme live');

    // --- Save it as a version: the share dialog.
    await page.evaluate(() => document.getElementById('gd-theme-save').click());
    await page.waitForSelector('#gd-mkpub-pop #gd-mkpub-name', {timeout: 30000});
    await page.evaluate(() => {
      const set = (id, v) => { const el = document.getElementById(id); el.value = v; el.dispatchEvent(new Event('input', {bubbles: true})); };
      set('gd-mkpub-name', 'my-board');
      document.getElementById('gd-mkpub-public').checked = true;
      document.getElementById('gd-mkpub-go').click();
    });
    await page.waitForSelector('#gd-mkpub-result.packages-fork-ok', {timeout: 60000});
    await waitTourTitle(page, 'Roll back is a version', 60000);
    console.log('  step 4: my-board saved');
    // The dialog stays open on success (the reader sees the outcome) — close it.
    await page.evaluate(() => document.getElementById('gd-mkpub-cancel').click());
    await page.waitForFunction(() => !document.querySelector('#gd-mkpub-pop'), null, {timeout: 15000, polling: 200});
    assert(await clickTourButton(page, 'Next'), 'roll-back Next');

    // --- Now the keys: rebind graph-fit to f f.
    await waitTourTitle(page, 'Now the keys');
    await page.evaluate(() => document.querySelector('#gd-settings-nav [data-section="keyboard"]').click());
    await page.waitForSelector('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-km-change', {timeout: 30000});
    await page.evaluate(() => document.querySelector('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-km-change').click());
    await page.waitForSelector('#gd-keymap-root .gd-km-recording', {timeout: 10000});
    await page.keyboard.press('f');
    await page.keyboard.press('f');
    await page.keyboard.press('Enter');
    await waitTourTitle(page, 'Browse the Marketplace', 60000);
    console.log('  step 6: graph-fit rebound');

    // --- Browse the Marketplace: Themes tab with the my-board card.
    await page.evaluate(() => window.gdMarketOpen({kind: 'theme'}));
    await waitTourTitle(page, 'Review it', 60000);
    console.log('  step 7: marketplace lists my-board');

    // --- Review it.
    await page.evaluate(() => document.querySelector('#gd-market-root [data-mk-card="my-board"] .mk-card-open').click());
    await page.waitForSelector('#gd-market-root .mk-review-submit', {timeout: 30000});
    await page.waitForFunction(() => !!document.querySelector('#gd-market-root .mk-review-form')?.['htmx-internal-data'], null, {timeout: 15000, polling: 100});
    await page.evaluate(() => {
      const sel = document.querySelector('#gd-market-root .mk-review-form select[name="rating"]');
      sel.value = '5';
      const ta = document.querySelector('#gd-market-root .mk-review-form textarea[name="body"]');
      ta.value = 'Lesson 37 says hello.';
      document.querySelector('#gd-market-root .mk-review-submit').click();
    });
    await waitTourTitle(page, "That's the Marketplace", 60000);
    console.log('  step 8: review posted');

    await finishAndDelete(page);
    console.log('  lesson 37: walked + cleaned (theme version withdrawn)');
  } catch (e) {
    failed = true;
    console.error('FAIL edit-tutorial-tour-market:', e);
  } finally {
    await browser.close();
    await nodeApi('PUT', '/api/prefs/theme', {value: null}).catch(() => {});
    await nodeApi('PUT', '/api/prefs/keymap', {value: null}).catch(() => {});
    await nodeApi('DELETE', '/api/marketplace/unreview?name=my-board').catch(() => {});
    for (const v of ['1.0.0', '1.0.1']) {
      await nodeApi('DELETE', '/api/packages/withdraw?name=my-board&version=' + v).catch(() => {});
    }
  }
  if (failed) process.exit(1);
  console.log('PASS edit-tutorial-tour-market');
})().catch((e) => { console.error('FAIL edit-tutorial-tour-market:', e); process.exit(1); });
