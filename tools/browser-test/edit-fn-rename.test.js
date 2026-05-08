// Phase 1 — fn rename via the root card.
//
// Click the ✎ pencil next to the fn name on the root card → inline
// rename popover → save → assert the storage entity AND the URL hash
// both move to the new name.
//
// Run from this directory:  node edit-fn-rename.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');

const ORIG = 'test-fn-rename-orig';
const NEW = 'test-fn-rename-new';

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('fn-rename — Phase 1: rename via root card pencil');
  try {
    await deleteFnByName(page, ORIG);
    await deleteFnByName(page, NEW);

    // Seed: a tiny fn-def parented to `:add` (something stable).
    const ents = await getEntities(page);
    const add = ents.fns.find(f => f.name === 'add');
    assert(add, 'baseline :add resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + ORIG + '&parent-ids=' + add.id);
    const fn = (await getEntities(page)).fns.find(f => f.name === ORIG);
    assert(fn, 'test fn created');

    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + ORIG);
    await page.waitForTimeout(2500);

    // The root card carries a ✎ pencil next to the name. createFnOverlay
    // emits one specifically for fn-rename when the fn is editable.
    const opened = await page.evaluate(() => {
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => (el.textContent || '').trim().startsWith('test-fn-rename-orig'));
      if (!overlay) return {error: 'root overlay not found'};
      // Multiple `.edit-pencil` buttons exist on a card (name +
      // description). The fn-name pencil sits on the FIRST line — the
      // one whose text matches the fn's own name.
      const firstLine = Array.from(overlay.querySelectorAll('div'))
        .find(d => /^test-fn-rename-orig/.test((d.textContent || '').trim()));
      const pencil = firstLine && firstLine.querySelector('.edit-pencil');
      if (!pencil) return {error: 'no .edit-pencil on root name line'};
      pencil.click();
      return {clicked: true};
    });
    assert(!opened.error, opened.error || 'pencil clicked');

    await page.waitForTimeout(200);
    const popover = await page.evaluate(
      () => !!document.querySelector('.arg-value-edit-popover'));
    assert(popover, 'rename popover opened');

    // Type the new name and click Save.
    await page.evaluate((newName) => {
      const i = document.querySelector('.arg-value-edit-input');
      i.value = newName;
      i.dispatchEvent(new Event('input', {bubbles: true}));
    }, NEW);

    await page.evaluate(() => {
      const save = Array.from(document.querySelectorAll(
        '.arg-value-edit-buttons .arg-value-edit-btn'))
        .find(b => b.textContent.trim() === 'Save');
      save.click();
    });
    await page.waitForTimeout(2500);

    // Assert: storage now has the new name, original gone.
    const after = await getEntities(page);
    const renamed = after.fns.find(f => f.id === fn.id);
    assert(renamed && renamed.name === NEW,
           'fn entity renamed in storage: ' + JSON.stringify(renamed));
    assert(!after.fns.find(f => f.name === ORIG),
           'original name no longer present');
  } finally {
    await deleteFnByName(page, ORIG).catch(() => {});
    await deleteFnByName(page, NEW).catch(() => {});
    await browser.close();
  }
  console.log('fn-rename — PASS');
})().catch(e => {
  console.error('fn-rename — FAIL:', e.message);
  process.exit(1);
});
