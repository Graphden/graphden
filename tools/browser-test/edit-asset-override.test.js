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
    // Save gate — a syntax-broken JS override is blocked client-side.
    // Done FIRST, on a clean editor, and typed through the real
    // CodeMirror editor (click → select-all → type) so the view and the
    // serialized textarea stay in sync exactly like a user.
    // ================================================================
    const jsRow = page.locator('section[data-section="assets"] .gd-asset-row',
                               {hasText: 'editor-main.js'}).first();
    await jsRow.locator('.gd-asset-edit-btn').click();
    await page.waitForSelector('#gd-asset-editor .cm-content', {timeout: 15000});
    await page.click('#gd-asset-editor .cm-content');
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type('function broken( {');
    await page.click('#gd-asset-editor .gd-asset-save-btn');
    await page.waitForSelector('#gd-asset-editor .gd-asset-error', {timeout: 5000});
    const errText = await page.textContent('#gd-asset-editor .gd-asset-error');
    assert(/syntax error/i.test(errText),
           'broken JS save blocked with a syntax error (got: ' + errText + ')');

    // ================================================================
    // Edit → prefilled textarea → append a marker → save.
    // ================================================================
    await row.locator('.gd-asset-edit-btn').click();
    // CodeMirror hides the textarea (editor-code.js) — wait for presence,
    // not visibility, AND for the CSS baseline to actually load (the prior
    // phase left a small JS editor in the slot; the swap is async).
    await page.waitForFunction(() => {
      const t = document.querySelector('#gd-asset-editor textarea[name="content"]');
      return t && t.value.includes('--gd-');
    }, null, {timeout: 15000});
    const prefillLen = await page.$eval(
      '#gd-asset-editor textarea[name="content"]', (t) => t.value.length);
    assert(prefillLen > 10000,
           'textarea prefilled with the classpath baseline (' + prefillLen + ' chars)');

    // The textarea is CodeMirror-enhanced (editor-code.js) — write via
    // the gdCode seam so the view and the serialized value stay in sync.
    const enhanced = await page.$eval(
      '#gd-asset-editor textarea[name="content"]', (t) => !!t.dataset.cmEnhanced);
    assert(enhanced, 'asset textarea is CodeMirror-enhanced');
    await page.$eval('#gd-asset-editor textarea[name="content"]',
                     (t, marker) => { window.gdCode.set(t, window.gdCode.get(t) + '\n' + marker + '\n'); },
                     MARKER);
    await page.click('#gd-asset-editor .gd-asset-save-btn');
    await page.waitForSelector(
      'section[data-section="assets"] .gd-asset-chip-override',
      {timeout: 20000});
    const chip1 = await stylesRow(page).locator('.gd-asset-chip').textContent();
    assert(chip1.trim() === 'override', 'chip flipped to override');

    // ================================================================
    // A fresh page load links a ROLLED ?v= (effective ≠ baked), and the
    // asset AT THAT hashed URL carries the marker. The response cache is
    // query-keyed, so the new ?v= is a fresh key → the override is served
    // immediately with no cross-node flush to wait on (browsers always
    // request the hashed URL the shell links).
    // ================================================================
    await page.reload({waitUntil: 'networkidle'});
    const {href, baked} = await page.evaluate(async () => ({
      href: document.querySelector('link[href*="editor.css"]').getAttribute('href'),
      // window.BUILD_HASH is the EFFECTIVE hash too, so read the baked
      // one from /version.
      baked: (await (await fetch('/version')).json()).frontend.slice(0, 12),
    }));
    assert(!href.includes(baked),
           'fresh shell links a rolled ?v= (href=' + href + ' baked=' + baked + ')');
    const hashedTail = await page.evaluate(async ({h}) => {
      const r = await fetch(h, {cache: 'no-store'});
      return (await r.text()).slice(-500);
    }, {h: href});
    assert(hashedTail.includes(MARKER),
           'the hashed asset URL serves the override');

    // ================================================================
    // Diff view — a read-only MergeView of baseline vs current content.
    // ================================================================
    await openAssetsSection(page);
    await stylesRow(page).locator('.gd-asset-edit-btn').click();
    await page.waitForSelector('#gd-asset-editor .gd-asset-diff-btn', {timeout: 15000});
    await page.click('#gd-asset-editor .gd-asset-diff-btn');
    await page.waitForSelector('#gd-asset-editor .gd-asset-diff .cm-mergeView, #gd-asset-editor .gd-asset-diff .cm-editor',
                               {timeout: 15000});
    assert(true, 'diff MergeView opened');
    await page.click('#gd-asset-editor .gd-asset-diff-btn'); // toggle back
    await page.waitForFunction(
      () => !document.querySelector('#gd-asset-editor .gd-asset-diff'), {timeout: 5000});

    // ================================================================
    // Revert restores the CSS override's baseline chip + baked hash.
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
    // Revert deletes the row synchronously → the effective hash is the
    // baked one on the next shell render (no stale window to poll).
    await page.reload({waitUntil: 'networkidle'});
    const after = await page.evaluate(async () => ({
      href: document.querySelector('link[href*="editor.css"]').getAttribute('href'),
      baked: (await (await fetch('/version')).json()).frontend.slice(0, 12),
    }));
    assert(after.href.includes(after.baked),
           'after revert the shell links the baked hash again');

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
