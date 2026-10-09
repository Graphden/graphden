// Lesson 40 — the Marketplace: theme saved + shared, a key rebound, a review.
//
// Part of the interactive-tutorial drift guard: walks every step of the
// lesson by doing the real UI actions (docs/MARKETPLACE.md; the lesson is
// docs/tutorial/40-marketplace-themes-keymaps.md), so a renamed class or a
// changed flow fails HERE, not on a reader. Needs the registry package (the
// e2e stack has it); the theme version the lesson publishes is withdrawn by
// the tour's own end-of-lesson cleanup, and the preferences are reset here.
//
// Run from this directory:  node edit-tutorial-tour-market.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, nodeApi} = require('./edit-test-helpers');
const {waitTourTitle, clickTourButton, finishAndDelete, tourWhere} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-market — lesson 40 walked end-to-end');
  let failed = false;
  let ownedPackage = null;
  let reviewCreated = false;
  let preferencesChanged = false;
  const requests = new Map();
  const knownRoute = request => {
    const path = new URL(request.url()).pathname;
    return ['/api/packages', '/api/packages/withdraw', '/api/graph/layout',
      '/api/marketplace/publish', '/api/marketplace/review'].includes(path) ? path : null;
  };
  page.on('request', request => {
    const route = knownRoute(request);
    if (route) requests.set(request, {route, status: 'pending'});
  });
  page.on('response', response => {
    const row = requests.get(response.request());
    if (row) row.status = response.status();
  });
  page.on('requestfailed', request => {
    const row = requests.get(request);
    if (row) row.status = 'transport-failed';
  });
  const cleanupDiagnostics = async stage => {
    const flags = await page.evaluate(() => {
      const rows = typeof _tourState === 'object' ? _tourState?.created || [] : [];
      const entry = rows.find(row => row.type === 'package-version' && row.name === 'my-board');
      const text = document.querySelector('.gd-toast, #gd-toast')?.textContent || '';
      return {receipt: entry?.receipt || 'absent', expectedId: !!entry?.id,
        hash: !!entry?.['content-hash'], version: !!entry?.version,
        failureToast: /refused|kept|failed/i.test(text) ? 'cleanup-refused'
          : /deleted/i.test(text) ? 'deleted' : 'none-or-other'};
    }).catch(() => ({unavailable: true}));
    console.log('  cleanup diagnostic:', JSON.stringify({stage, ...flags,
      requests: [...requests.values()].slice(-16)}));
  };
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    const index = await nodeApi('GET', '/api/packages');
    assert(index.ok, 'registry readable before owned publication');
    const indexBody = await index.json();
    const versions = Array.isArray(indexBody) ? indexBody : indexBody.packages;
    assert(Array.isArray(versions) && !versions.some(row => row.name === 'my-board'),
      'fixture package absent before publication; no adoption');
    await page.goto(BASE + '/?tutorial=40');
    await waitTourTitle(page, 'Make it yours, then share it', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 40 Next');

    // --- Open the theme editor: account menu → Settings → Customize…
    await waitTourTitle(page, 'Open the theme editor');
    await page.evaluate(() => window.gdShellSurface('settings'));
    await page.waitForSelector('#gd-theme-edit', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-theme-edit').click());
    await waitTourTitle(page, 'Pick a paper', 60000);
    console.log('  step 2: theme editor open');

    // --- Pick a paper: drive the colour input the way a picker would.
    preferencesChanged = true;
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
    // aria-modal="true" is kept: everything but the dialog, its scrim (the
    // click-outside close) and the tour's own layers is inert while it is up.
    const modal = await page.evaluate(() => [...document.body.children]
      .filter((e) => !['gd-mkpub-pop', 'gd-mkpub-scrim'].includes(e.id) && !/^gd-tour-/.test(e.id || ''))
      .filter((e) => !e.hasAttribute('inert')).map((e) => e.id || e.tagName));
    assert(modal.length === 0, 'the page behind the share dialog is inert (live: ' + modal.join(', ') + ')');
    assert(await page.evaluate(() => !document.getElementById('gd-mkpub-scrim').hasAttribute('inert')),
           'the scrim stays clickable');
    await page.evaluate(() => {
      const set = (id, v) => { const el = document.getElementById(id); el.value = v; el.dispatchEvent(new Event('input', {bubbles: true})); };
      set('gd-mkpub-name', 'my-board');
      document.getElementById('gd-mkpub-public').checked = true;
      document.getElementById('gd-mkpub-go').click();
    });
    await page.waitForSelector('#gd-mkpub-result.packages-fork-ok', {timeout: 60000});
    await waitTourTitle(page, 'Roll back is a version', 60000);
    ownedPackage = await page.evaluate(() => {
      const entry = _tourState.created.find(row => row.type === 'package-version'
        && row.name === 'my-board' && row.receipt === 'created');
      return entry ? {id: entry.id, name: entry.name, version: entry.version,
        'content-hash': entry['content-hash']} : null;
    });
    assert(ownedPackage?.id && ownedPackage.version && ownedPackage['content-hash'],
      'successful publication has exact canonical cleanup receipt');
    console.log('  step 4: my-board saved');
    // The dialog stays open on success (the reader sees the outcome) — close it.
    await page.evaluate(() => document.getElementById('gd-mkpub-cancel').click());
    await page.waitForFunction(() => !document.querySelector('#gd-mkpub-pop'), null, {timeout: 15000, polling: 200});
    assert(await page.evaluate(() => ![...document.body.children].some((e) => e.hasAttribute('inert'))),
           'closing the share dialog lifts the inert');
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
    // The reader's path: open the surface (it lands on Packages), then click
    // the Themes tab the step rings — opening straight on the theme kind
    // would pass the step's check at the very instant its target appeared,
    // and the spotlight audit would rightly report the tab as never ringed.
    await page.evaluate(() => window.gdShellSurface('market'));
    await page.waitForSelector('#gd-market-root .mk-tab[data-mk-tab="theme"]', {timeout: 30000});
    await new Promise((r) => setTimeout(r, 700));
    await page.click('#gd-market-root .mk-tab[data-mk-tab="theme"]');
    await waitTourTitle(page, 'Open the listing', 60000);
    console.log('  step 7: marketplace lists my-board');

    // --- Open the listing, then review it (two rings: the card, the form).
    await page.evaluate(() => document.querySelector('#gd-market-root [data-mk-card="my-board"] .mk-card-open').click());
    await page.waitForSelector('#gd-market-root .mk-review-submit', {timeout: 30000});
    await waitTourTitle(page, 'Review it', 60000);
    await page.waitForFunction(() => !!document.querySelector('#gd-market-root .mk-review-form')?.['htmx-internal-data'], null, {timeout: 15000, polling: 100});
    const reviewResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/marketplace/review'
      && response.request().method() === 'POST'
      && new URLSearchParams(response.request().postData() || '').get('name') === ownedPackage.name);
    await page.evaluate(() => {
      const sel = document.querySelector('#gd-market-root .mk-review-form select[name="rating"]');
      sel.value = '5';
      const ta = document.querySelector('#gd-market-root .mk-review-form textarea[name="body"]');
      ta.value = 'Lesson 40 says hello.';
      document.querySelector('#gd-market-root .mk-review-submit').click();
    });
    const reviewResult = await reviewResponse;
    assert(reviewResult.status() === 200, 'owned package review creation succeeded');
    reviewCreated = true;
    await waitTourTitle(page, "That's the Marketplace", 60000);
    console.log('  step 8: review posted');

    await cleanupDiagnostics('before-finish');
    await finishAndDelete(page);
    console.log('  lesson 40: walked + cleaned (theme version withdrawn)');
  } catch (e) {
    failed = true;
    await cleanupDiagnostics('failure-before-finally');
    console.error('FAIL edit-tutorial-tour-market:', {name: e.name});
    console.error('  tour at failure:', await tourWhere(page));
  } finally {
    try {
      if (reviewCreated && ownedPackage) {
        const response = await nodeApi('DELETE', '/api/marketplace/unreview?name='
          + encodeURIComponent(ownedPackage.name));
        assert(response.ok, 'delete only review successfully created on owned package');
      }
      if (ownedPackage) {
        const response = await nodeApi('GET', '/api/packages');
        assert(response.ok, 'read exact owned publication before cleanup');
        const body = await response.json();
        const rows = Array.isArray(body) ? body : body.packages;
        assert(Array.isArray(rows), 'valid publication cleanup index');
        const row = rows.find(candidate => candidate.id === ownedPackage.id);
        if (row) {
          assert(row.name === ownedPackage.name && row.version === ownedPackage.version
            && row['content-hash'] === ownedPackage['content-hash'], 'owned publication identity unchanged');
          const removed = await nodeApi('DELETE', '/api/packages/withdraw?name='
            + encodeURIComponent(ownedPackage.name) + '&version=' + encodeURIComponent(ownedPackage.version)
            + '&expected-id=' + encodeURIComponent(ownedPackage.id));
          assert(removed.ok, 'exact owned publication cleanup succeeded');
        }
      }
      if (preferencesChanged) {
        assert((await nodeApi('PUT', '/api/prefs/theme', {value: null})).ok, 'restore fixture theme');
        assert((await nodeApi('PUT', '/api/prefs/keymap', {value: null})).ok, 'restore fixture keymap');
      }
    } catch (error) {
      failed = true;
      console.error('FAIL exact market cleanup:', {name: error.name});
    } finally {
      await browser.close();
    }
  }
  if (failed) process.exit(1);
  console.log('PASS edit-tutorial-tour-market');
})().catch((e) => { console.error('FAIL edit-tutorial-tour-market:', e); process.exit(1); });
