// Marketplace e2e — themes, reviews and keyboard layouts through the real
// editor (docs/MARKETPLACE.md).
//
//   • The packages chip's "Browse marketplace →" opens the Marketplace
//     surface; the Themes tab lists a theme published in setup.
//   • Opening its card shows the item; Apply makes it the active theme —
//     the page carries the theme's inline token and the custom-theme marker.
//   • A review posts and renders under the item; the card then shows a rating.
//   • Settings → Appearance names the applied theme; "Reset to built-in"
//     clears it.
//   • Settings → Keyboard: Change on a row records a new sequence; the
//     layout survives a reload (server preference).
//
// Setup publishes the theme through the JSON route; cleanup withdraws it,
// deletes the review and resets both preferences, so the default state is
// left exactly as found.
//
// Run from this directory:  node edit-marketplace.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, clickWhenHtmxReady, nodeApiJson, nodeApi} = require('./edit-test-helpers');

const RUN_ID = process.pid.toString(36) + Date.now().toString(36);
const THEME = 'e2e-theme-' + RUN_ID;
const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
const PAPER = '#123456';

(async () => {
  // ---- setup: a public theme in the registry
  const pub = await nodeApiJson('POST', '/api/marketplace/publish', {
    kind: 'theme', name: THEME, version: '1.0.0', description: 'An e2e theme',
    category: 'dark', tags: 'e2e, dark', public: true,
    payload: {mode: 'dark', tokens: {'--gd-paper': PAPER}, scale: 100},
  });
  assert(pub && pub.ok === true, 'setup: theme published: ' + JSON.stringify(pub));

  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => d.accept());
  console.log('edit-marketplace — browse / apply / review / keymap');
  const surfaceRoot = () => page.evaluate(() => {
    const r = document.querySelector('#gd-market-root [data-marketplace]');
    return r ? {kind: r.dataset.mkKind, item: r.dataset.marketplaceItem || null, html: r.innerHTML.length} : null;
  });

  try {
    await page.goto(BASE + '/');
    // ---- the door: packages chip → Browse marketplace
    await page.waitForSelector('#gd-pkg-chip:not([hidden])', {timeout: 15000});
    await page.click('#gd-pkg-chip');
    await page.waitForSelector('#gd-pkg-pop .packages-market-link', {timeout: 15000});
    await page.click('#gd-pkg-pop .packages-market-link');
    await page.waitForFunction(() => document.body.dataset.surface === 'market'
      && !!document.querySelector('#gd-market-root [data-marketplace] .mk-tabs'), null, {timeout: 15000, polling: 100});
    assert((await surfaceRoot()).kind === 'fns', 'the surface opens on the Packages tab');
    assert(await page.evaluate(() => window.location.hash === '#@marketplace'), 'surface hash mirrors @marketplace');

    // ---- Themes tab lists the published theme
    await page.click('#gd-market-root .mk-tab[data-mk-tab="theme"]');
    await page.waitForSelector('#gd-market-root [data-mk-card="' + THEME + '"]', {timeout: 15000});
    const card = await page.evaluate((name) => {
      const c = document.querySelector('[data-mk-card="' + name + '"]');
      return {
        desc: c.querySelector('.mk-card-desc')?.textContent,
        tags: [...c.querySelectorAll('.mk-tag')].map((t) => t.textContent),
        noReviews: !!c.querySelector('.mk-rating-none'),
        category: c.querySelector('.mk-category')?.textContent,
      };
    }, THEME);
    assert(card.desc === 'An e2e theme', 'card shows the description: ' + card.desc);
    assert(card.tags.includes('#e2e') && card.tags.includes('#dark'), 'card shows the tags: ' + card.tags);
    assert(card.category === 'dark', 'card shows the category');
    assert(card.noReviews, 'no reviews yet');

    // ---- open the item, Apply
    await clickWhenHtmxReady(page, (name) => document.querySelector('[data-mk-card="' + name + '"] .mk-card-open'), THEME);
    await page.waitForSelector('#gd-market-root [data-marketplace-item="' + THEME + '"]', {timeout: 15000});
    await clickWhenHtmxReady(page, () => document.querySelector('#gd-market-root .mk-apply'));
    await page.waitForFunction(() => !!document.querySelector('#gd-market-root .mk-current'), null, {timeout: 15000, polling: 100});
    await page.waitForFunction((paper) => document.body.classList.contains('gd-custom-theme')
      && document.body.style.getPropertyValue('--gd-paper') === paper, PAPER, {timeout: 15000, polling: 100});
    assert(await page.evaluate(() => document.body.classList.contains('theme-dark')), 'a dark theme sets the dark base');
    const pref = await nodeApiJson('GET', '/api/prefs');
    assert(pref && pref.theme && pref.theme.source && pref.theme.source.name === THEME, 'server preference records the source: ' + JSON.stringify(pref && pref.theme && pref.theme.source));

    // ---- post a review
    await page.selectOption('#gd-market-root .mk-review-form select[name="rating"]', '4');
    await page.fill('#gd-market-root .mk-review-form textarea[name="body"]', 'Lovely dark paper.');
    await clickWhenHtmxReady(page, () => document.querySelector('#gd-market-root .mk-review-submit'));
    await page.waitForFunction(() => [...document.querySelectorAll('#gd-market-root .mk-review-body')]
      .some((b) => b.textContent.includes('Lovely dark paper.')), null, {timeout: 15000, polling: 100});
    assert(await page.evaluate(() => /Update review/.test(document.querySelector('#gd-market-root .mk-review-submit')?.textContent || '')),
      'the form now offers an update (one review per author)');
    await clickWhenHtmxReady(page, () => document.querySelector('#gd-market-root .mk-back'));
    await page.waitForSelector('#gd-market-root [data-mk-card="' + THEME + '"] .mk-rating:not(.mk-rating-none)', {timeout: 15000});
    const rating = await page.evaluate((name) => document.querySelector('[data-mk-card="' + name + '"] .mk-rating').textContent, THEME);
    assert(/4\.0 \(1\)/.test(rating), 'the card shows the rating: ' + rating);

    // ---- Settings → Appearance names the theme; reset clears it
    await page.evaluate(() => window.gdShellSurface('settings'));
    await page.waitForSelector('#gd-theme-root #gd-theme-reset', {timeout: 15000});
    const hint = await page.evaluate(() => document.querySelector('#gd-theme-root .gd-set-hint')?.textContent);
    assert(hint && hint.includes(THEME + '@1.0.0'), 'Appearance names the applied theme: ' + hint);
    await page.click('#gd-theme-root #gd-theme-reset');
    await page.waitForFunction(() => !document.body.classList.contains('gd-custom-theme'), null, {timeout: 10000, polling: 100});
    assert(await page.evaluate(() => document.body.style.getPropertyValue('--gd-paper') === ''), 'reset clears the inline token');

    // ---- Settings → Keyboard: rebind, then it survives a reload
    await page.click('#gd-settings-nav [data-section="keyboard"]');
    await page.waitForSelector('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-km-change', {timeout: 15000});
    await page.click('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-km-change');
    await page.waitForSelector('#gd-keymap-root .gd-km-recording', {timeout: 5000});
    await page.keyboard.press('p');
    await page.keyboard.press('q');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => {
      const caps = [...document.querySelectorAll('#gd-keymap-root .gd-km-row[data-shortcut="graph-fit"] .gd-key-cap')].map((k) => k.textContent);
      return caps.join(' ') === 'Space p q';
    }, null, {timeout: 10000, polling: 100});
    await page.waitForFunction(async () => true, null, {timeout: 1000}).catch(() => {});
    const km = await nodeApiJson('GET', '/api/prefs');
    assert(km && km.keymap && km.keymap.payload && km.keymap.payload.bindings && km.keymap.payload.bindings['graph-fit']
      && km.keymap.payload.bindings['graph-fit'].keys === 'p q', 'server keymap preference holds the override: ' + JSON.stringify(km && km.keymap));
    await page.reload();
    await page.waitForFunction(() => typeof window.gdShortcutEntries === 'function'
      && (window.gdShortcutEntries().find((e) => e.id === 'graph-fit') || {}).keys === 'p q', null, {timeout: 20000, polling: 200});
    assert(true, 'the layout is live again after a reload');
  } finally {
    await browser.close();
    // ---- cleanup: preferences, review, the theme
    await nodeApi('PUT', '/api/prefs/theme', {value: null}).catch(() => {});
    await nodeApi('PUT', '/api/prefs/keymap', {value: null}).catch(() => {});
    await nodeApi('DELETE', '/api/marketplace/unreview?name=' + encodeURIComponent(THEME)).catch(() => {});
    await nodeApi('DELETE', '/api/packages/withdraw?name=' + encodeURIComponent(THEME) + '&version=1.0.0').catch(() => {});
  }
  console.log('PASS edit-marketplace');
})().catch((e) => { console.error('FAIL edit-marketplace:', e); process.exit(1); });
