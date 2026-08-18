// Asset-override e2e (UI Step 1) — the Operate "Assets" section
// edits the editor's own frontend files in place: server-rendered
// panel (GET /partials/assets-panel), per-row edit form
// (GET /partials/asset-edit), upsert via POST /api/assets/save,
// revert via DELETE /api/assets/revert. All htmx; the JS module only
// mounts the section shell.
//
// Coverage:
//   • Operate surface mounts an "assets" section listing bundle files
//     with baseline chips.
//   • Edit on editor-styles.css prefills the classpath baseline into
//     the textarea (large body — also exercises the :form-decode
//     no-regex parse path on save).
//   • Save flips the row chip to "override" and rolls the effective
//     asset hash (?v=) served in a FRESH page load.
//   • Revert restores the baseline chip and the baked hash.
//
// Run from this directory:  node edit-asset-override.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
const MARKER = '/* e2e-asset-override-' + process.pid + ' */';

async function openAssetsSection(page) {
  await page.evaluate(async () => {
    gdShellSurface('operate');
    document.querySelector('#gd-operate-nav button[data-section="assets"]')?.click();
  });
  await page.waitForSelector('section[data-section="assets"] .gd-asset-row',
                             {timeout: 15000});
}

function stylesRow(page) {
  return page.locator('section[data-section="assets"] .gd-asset-row',
                      {hasText: 'editor-styles.css'}).first();
}

async function revertViaApi(page) {
  // Belt-and-braces cleanup — direct DELETE, independent of the UI.
  await page.evaluate(async ({base}) => {
    const url = base + '/api/assets/revert?path='
      + encodeURIComponent('packages/app/editor/editor-styles.css');
    await window.authFetch(url, {method: 'DELETE'});
  }, {base: BASE});
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-asset-override — panel / edit / save / hash roll / revert');

  try {
    await page.goto(BASE + '/', {waitUntil: 'networkidle'});
    await openAssetsSection(page);

    // ================================================================
    // Panel lists bundle files, editor-styles.css starts baseline.
    // ================================================================
    const row = stylesRow(page);
    assert(await row.count() > 0, 'editor-styles.css row is listed');
    const chip0 = await row.locator('.gd-asset-chip').textContent();
    assert(chip0.trim() === 'baseline',
           'starts baseline (got: ' + chip0 + ')');

    // ================================================================
    // Edit → prefilled textarea → append a marker → save.
    // ================================================================
    await row.locator('.gd-asset-edit-btn').click();
    await page.waitForSelector('#gd-asset-editor textarea[name="content"]',
                               {timeout: 15000});
    const prefillLen = await page.$eval(
      '#gd-asset-editor textarea[name="content"]', (t) => t.value.length);
    assert(prefillLen > 10000,
           'textarea prefilled with the classpath baseline (' + prefillLen + ' chars)');

    await page.$eval('#gd-asset-editor textarea[name="content"]',
                     (t, marker) => { t.value = t.value + '\n' + marker + '\n'; },
                     MARKER);
    await page.click('#gd-asset-editor .gd-asset-save-btn');
    await page.waitForSelector(
      'section[data-section="assets"] .gd-asset-chip-override',
      {timeout: 20000});
    const chip1 = await stylesRow(page).locator('.gd-asset-chip').textContent();
    assert(chip1.trim() === 'override', 'chip flipped to override');

    // ================================================================
    // The served asset carries the marker; a fresh page load links a
    // ROLLED ?v= (effective hash ≠ baked hash).
    // ================================================================
    // Write-then-poll: cache invalidation propagates via NOTIFY, so the
    // asset can serve stale for a moment right after the save.
    let sawMarker = false;
    for (let i = 0; i < 30 && !sawMarker; i++) {
      const tail = await page.evaluate(async ({base}) => {
        const r = await fetch(base + '/assets/editor.css', {cache: 'no-store'});
        return (await r.text()).slice(-500);
      }, {base: BASE});
      sawMarker = tail.includes(MARKER);
      if (!sawMarker) await new Promise((r) => setTimeout(r, 1000));
    }
    assert(sawMarker, 'served asset carries the override marker (30s poll)');

    await page.reload({waitUntil: 'networkidle'});
    // window.BUILD_HASH is the EFFECTIVE hash too (same substitution), so
    // the true baked hash comes from /version.
    const {href, baked} = await page.evaluate(async () => ({
      href: document.querySelector('link[href*="editor.css"]').getAttribute('href'),
      baked: (await (await fetch('/version')).json()).frontend.slice(0, 12),
    }));
    assert(!href.includes(baked),
           'fresh shell links a rolled ?v= (href=' + href + ' baked=' + baked + ')');

    // ================================================================
    // Revert restores baseline chip + baked hash.
    // ================================================================
    await openAssetsSection(page);
    await stylesRow(page).locator('.gd-asset-edit-btn').click();
    await page.waitForSelector('#gd-asset-editor .gd-asset-revert-btn',
                               {timeout: 15000});
    await page.click('#gd-asset-editor .gd-asset-revert-btn');
    await page.waitForFunction(() => {
      const sec = document.querySelector('section[data-section="assets"]');
      return sec && sec.querySelectorAll('.gd-asset-chip-override').length === 0;
    }, {timeout: 20000});

    // Same stale window on the way back — poll the fresh shell's href.
    let backToBaked = false;
    for (let i = 0; i < 30 && !backToBaked; i++) {
      await page.reload({waitUntil: 'networkidle'});
      const after = await page.evaluate(async () => ({
        href: document.querySelector('link[href*="editor.css"]').getAttribute('href'),
        baked: (await (await fetch('/version')).json()).frontend.slice(0, 12),
      }));
      backToBaked = after.href.includes(after.baked);
      if (!backToBaked) await new Promise((r) => setTimeout(r, 1000));
    }
    assert(backToBaked, 'after revert the shell links the baked hash again (30s poll)');

    console.log('PASS: edit-asset-override');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    try { await revertViaApi(page); } catch (_) {}
    try { await browser.close(); } catch (_) {}
    process.exit(1);
  }
})();
