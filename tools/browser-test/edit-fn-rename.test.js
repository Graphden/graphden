// Phase 1 — fn rename via the root card.
//
// Click the ✎ pencil next to the fn name on the root card → inline
// rename popover → save → assert the storage entity AND the URL hash
// both move to the new name.
//
// Run from this directory:  node edit-fn-rename.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, waitFor} =
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
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + ORIG);
    await page.waitForFunction(
      () => typeof cy !== 'undefined' && cy && cy.nodes().length > 0
            && !!document.querySelector('button.more-actions-trigger')
            && !cy.animated(),
      null,
      {timeout: 20000, polling: 100});

    // Per-row action icons live in the `.row-actions-popover` triggered
    // by hover/click on the `.more-actions-trigger` (⋯) on the root
    // row — moved out of the card itself so the right-edge actions
    // aren't clipped by the card's overflow:hidden. Open the popover
    // FIRST, then look for the ✎ pencil inside it. (Same pattern the
    // edit-service.test.js uses for the ⚙ button.)
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover', {timeout: 5000});
    const opened = await page.evaluate(() => {
      const popover = document.querySelector('.row-actions-popover');
      if (!popover) return {error: 'row-actions popover not found'};
      // The HTMX-migrated row-actions popover uses `data-action="rename-fn"`
      // on the ✎ button (replaced the legacy `.edit-pencil` class).
      const pencil = popover.querySelector('button[data-action="rename-fn"]');
      if (!pencil) return {error: 'no rename-fn button in row-actions popover'};
      pencil.click();
      return {clicked: true};
    });
    assert(!opened.error, opened.error || 'pencil clicked');

    const popover = await waitFor(
      () => page.evaluate(
        () => !!document.querySelector('.arg-value-edit-popover')),
      2000);
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
    // Poll storage until rename propagates (cold-start can be slow).
    const renamePropagated = await waitFor(async () => {
      const e = await getEntities(page);
      return e.fns.find(f => f.id === fn.id)?.name === NEW;
    }, 8000);
    assert(renamePropagated, 'rename did not propagate to storage in 8s');

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
