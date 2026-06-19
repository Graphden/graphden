// Record remove-field e2e — open record-row via pencil, click the ×
// on a row, Save → record-update diff removes the corresponding
// fn-slot.
//
// Coverage:
//   • Seed a record with 3 fields (a:int, b:text, c:bool).
//   • Open the edit popover.
//   • Click the × on the "b" row → it leaves the DOM.
//   • Save → PUT /api/types/record-update.
//   • Storage now has 2 fn-slots: "a" and "c".
//
// Run from this directory:  node edit-type-edit-record-remove.test.js

const {chromium} = require('playwright');
const {assert, newContext, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const REC_FN = 'edit-rec-rm-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, REC_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-type-edit-record-remove — open / × / save / fn-slot removed');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: 3-field record.
    // ===================================================================
    const seedResp = await page.evaluate(async ({base, auth, name}) => {
      const r = await fetch(base + '/api/types/record', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + auth,
        },
        body: JSON.stringify({
          name,
          fields: [
            {name: 'a', type: 'int'},
            {name: 'b', type: 'text'},
            {name: 'c', type: 'bool'},
          ],
        }),
      });
      return r.json();
    }, {base: (process.env.GRAPHDEN_URL || 'http://localhost:9002')+'', auth: 'test123', name: REC_FN});
    assert(seedResp.ok && seedResp.id,
           'record created: ' + JSON.stringify(seedResp).slice(0, 120));

    const ents = await getEntities(page);
    const recFn = ents.fns.find((f) => f.name === REC_FN);
    const slotsById = Object.fromEntries(ents.slots.map((s) => [s.id, s]));
    const seedSlots = ents['fn-slots']
      .filter((fs) => fs['fn-id'] === recFn.id)
      .map((fs) => slotsById[fs['slot-id']]);
    assert(seedSlots.length === 3,
           'seed has 3 fn-slots: ' + seedSlots.length);

    // ===================================================================
    // Navigate + open edit popover.
    // ===================================================================
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + REC_FN,
                    {waitUntil: 'networkidle'});
    await page.waitForFunction(
      () => typeof openTypeEditForm === 'function'
            && lookups?.fnMap?.size > 50,
      {timeout: 30000});
    await page.evaluate(async () => { await initGraph(); });
    await page.waitForTimeout(1500);
    const inLookups = await page.evaluate(
      (fnId) => !!lookups?.fnMap?.get(fnId), recFn.id);
    assert(inLookups, 'record in editor lookups');

    await page.evaluate((fnId) => {
      window.openTypeEditForm(fnId, document.body);
    }, recFn.id);
    await page.waitForSelector('.type-create-popover', {timeout: 5000});
    await page.waitForFunction(
      () => document.querySelectorAll('.type-create-popover .type-create-pair-row')
              .length >= 3,
      {timeout: 5000});

    // ===================================================================
    // Phase A: prefill verified.
    // ===================================================================
    const prefill = await page.evaluate(() => {
      const el = document.querySelector('.type-create-popover');
      return Array.from(el?.querySelectorAll('.type-create-pair-row') || [])
        .map((r) => ({
          name: r.querySelector('.type-create-pair-key')?.value,
          type: r.querySelector('.type-create-pair-val')?.value,
        }));
    });
    assert(prefill.length === 3,
           '3 pair-rows prefilled: ' + prefill.length);
    assert(prefill.some((p) => p.name === 'b' && p.type === 'text'),
           'row "b: text" present: ' + JSON.stringify(prefill));

    // ===================================================================
    // Phase B: click × on the "b" row → row leaves DOM, Save.
    // ===================================================================
    await page.evaluate(() => {
      const rows = document.querySelectorAll('.type-create-popover .type-create-pair-row');
      const bRow = Array.from(rows).find(
        (r) => r.querySelector('.type-create-pair-key')?.value === 'b');
      bRow?.querySelector('.type-create-pair-rm')?.click();
    });
    await page.waitForFunction(
      () => document.querySelectorAll('.type-create-popover .type-create-pair-row')
              .length === 2,
      {timeout: 3000});

    await page.evaluate(() => {
      document.querySelector('.type-create-popover .type-create-submit')?.click();
    });
    await page.waitForFunction(
      () => {
        const el = document.querySelector('.type-create-popover');
        return !el || el.style.display === 'none';
      },
      {timeout: 15000});
    await page.waitForTimeout(800);

    // ===================================================================
    // Phase C: storage — only "a" and "c" remain.
    // ===================================================================
    const ents2 = await getEntities(page);
    const slotsById2 = Object.fromEntries(ents2.slots.map((s) => [s.id, s]));
    const finalSlots = ents2['fn-slots']
      .filter((fs) => fs['fn-id'] === recFn.id)
      .map((fs) => slotsById2[fs['slot-id']]);
    assert(finalSlots.length === 2,
           'record now has 2 fn-slots: ' + finalSlots.length);
    const finalNames = finalSlots.map((s) => s.name).sort();
    assert(JSON.stringify(finalNames) === '["a","c"]',
           'fields are ["a","c"] (b removed): '
           + JSON.stringify(finalNames));

    console.log('✓ record-remove verified — open / × / save / fn-slot gone');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.stack || e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
